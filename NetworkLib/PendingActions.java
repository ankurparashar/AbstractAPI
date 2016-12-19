import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Queue;

import android.content.Intent;
import android.text.TextUtils;

import com.android.volley.VolleyError;
import com.app.loadboard.activity.BaseActivity;
import com.app.loadboard.activity.DoneListener;
import com.app.loadboard.activity.LinkAccountActivity;
import com.app.loadboard.data.AppPreferences;
import com.app.loadboard.objects.models.ApiErrorModel.Action;
import com.app.loadboard.utils.UtilityFunctions;

public class PendingActions {

	static PendingActionEntry currentUIAction;
	
	static Queue<PendingActionEntry> pendingUIActionEntries = new ArrayDeque<PendingActions.PendingActionEntry>();
	
	static class PendingActionEntry {
		
		public PendingActionEntry(ApiRequestInfo<?> requestInfo, Action action) {
			this.requestInfo = requestInfo;
			this.action = action;
		}
		
		ApiRequestInfo<?> requestInfo;
		Action 	action;
	}

	/**
	 * This won't trigger events. The associated requests are meant to be *aborted* when calling this. 
	 */
	public static void deleteAllPendingActions() {
		pendingUIActionEntries.clear();
	}
	
	private static void queueUIAction(ApiRequestInfo<?> requestInfo, Action action) {
		pendingUIActionEntries.add(new PendingActionEntry(requestInfo, action));
	}

	private static void dismissSimilarActions(PendingActionEntry currentEntry) {
		
		Iterator<PendingActionEntry> iter = pendingUIActionEntries.iterator();
		
		while(iter.hasNext()) {
			PendingActionEntry entry = iter.next();
			if(actionIsTheSame(entry.action, currentEntry.action))
			{
				// remove it and fire events
				iter.remove();
				
				// FIXME: for now only one UI action is permitted per RequestInfo 
				//    else the ErrorHandlingUIDone event will occur on all of them.
				RequestApiHandler.fireErrorHandlingUIDoneEvent(entry.requestInfo.listener);
			}
		}
	}
	
	static boolean actionIsTheSame(Action a, Action b) {
		if(!TextUtils.equals(a.message, b.message) ||
				!TextUtils.equals(a.name, b.name))
			return false;
		
		// both nulls
		if(a.links == null && b.links == null)
			return true;
		
		// one of them is null
		if(a.links == null || b.links == null)
			return false;

		// no looking inside links. Matching the size should be enough testing.
		if(a.links.size() != b.links.size())
			return false;
		
		return true;
	}

	/** 
	 *  Processes Pending Actions. 
	 *  
	 *  If a similar action is already queued to be executed, then "merge" it so it only occur once.
	 *  
	 *  Also sends the proper events back to the request's originator.
	 */
	public static void processPendingActions(final ApiRequestInfo<?> requestInfo, VolleyError error, ArrayList<Action> actions) {

		//----------------------------------------
		// Pending Actions processing
		//
		// NOTE: The presence of Pending Actions 
		// inhibits the normal Error Handling
		//----------------------------------------

		boolean doFireTerminationEvent = true;

		for (final Action action : actions) {

			if (action.name.equalsIgnoreCase("Display")) {
				// This is considered an "Error"
				// Error event should be thus called "normally" 
				// (unless another pending action in the current list decides otherwise)
				// In other words: let current doFireTerminationEvent value stay (defaults to true)
				
				// Will be waiting for a dialogEvent, hence the ErrorHandlingUIDone event MUST be
				// called afterwards on this queueUIAction()
				queueUIAction(requestInfo, action);
			} 
			else if (action.name.equalsIgnoreCase("Logout")) {

				// abort all requests including the current one
				if(doFireTerminationEvent) {
					RequestApiHandler.fireAbortedEvent(requestInfo);
					RequestApiHandler.cleanupRequest(requestInfo);
					RequestApiHandler.fireFinallyEvent(requestInfo);
				}

				doFireTerminationEvent = false;

				// will delete queued pending actions
				RequestApiHandler.logout(); 

			} 
			else if (action.name.equals("RefreshToken")) {

				//----------------------------------------------------
				// Got a Refresh Token pending action
				//----------------------------------------------------
				
				//----------------------------------------------------
				// Hold off this request until we have a new Refresh 
				// Token. (put back to waiting queue)
				// NOTE: This will also block other incoming requests 
				// that requires Authentication. 
				RequestApiHandler.addPendingRequest(requestInfo);
				
				//----------------------------------------------------
				// Do not fire the request completion events just yet.
				doFireTerminationEvent = false;
				
				RequestApiHandler.refreshTokenAndProceedWithPendingRequests();
				
			} 
			else if (action.name.equals("LinkAccount")) {

				// abort this request and flush all other ongoing requests
				if(doFireTerminationEvent) {
					RequestApiHandler.fireAbortedEvent(requestInfo);
					RequestApiHandler.cleanupRequest(requestInfo);
					RequestApiHandler.fireFinallyEvent(requestInfo);
				}

				doFireTerminationEvent = false;
				// will delete queued pending actions
				RequestApiHandler.abortAllRequests();
				//TODO: get the top BaseActivity instead.  (This would not make sense if we are at the splashscreen page though)
				BaseActivity baseActivity = UtilityFunctions.validBaseActivityContext(requestInfo.context);
				if(baseActivity != null) {
					Intent i = new Intent(baseActivity, LinkAccountActivity.class);
					i.putExtra("token", AppPreferences.getInstance().getExternalAccessToken());
					i.putExtra("name", AppPreferences.getInstance().getLinkAccountUserName());
					baseActivity.startActivity(i);
				}
			}
		}
		
		if(doFireTerminationEvent) {
			RequestApiHandler.fireErrorEvent(requestInfo.listener, error);
			RequestApiHandler.cleanupRequestAndDoFinally(requestInfo);
		}
		
		executeUIAction();
	}
	
	public static void executeUIAction() {
		
		if(currentUIAction != null) {
			// dialogEvent already being shown. Wait for it. Do nothing.
			return;
		}

		currentUIAction = pendingUIActionEntries.poll();
		
		if(currentUIAction == null)
			return; // oh well, nothing here afterall. :)
		
		//TODO: IMPORTANT !! fix to use the current "top" BaseActivity (instead of relying on the requestInfo's original context).
		
		BaseActivity baseActivity = UtilityFunctions.validBaseActivityContext(currentUIAction.requestInfo.context);
		if(baseActivity != null) {

			baseActivity.displayApiMessageWithLinks(currentUIAction.action, new DoneListener() {
				@Override
				public void onDone() {
					RequestApiHandler.fireErrorHandlingUIDoneEvent(currentUIAction.requestInfo.listener);
					// remove & fire events on similar actions waiting in queue
					dismissSimilarActions(currentUIAction);
					currentUIAction = null;
					executeUIAction();
				}
			});
		}
		else {
			// TODO: support using the AppContext for displaying the message in 
			// case the BaseActivity is no longer valid.			
			currentUIAction = null;
			executeUIAction();
		}

	}
}
