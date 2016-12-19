import java.net.URI;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.text.TextUtils;
import android.widget.ImageView.ScaleType;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.NetworkResponse;
import com.android.volley.NoConnectionError;
import com.android.volley.Request;
import com.android.volley.Request.Method;
import com.android.volley.RequestQueue;
import com.android.volley.VolleyError;

import com.google.gson.Gson;

public class RequestApiHandler {

	/*package*/ static Context appContext;
	static RequestQueue requestQueue;

	/** this will "skip" doing two refresh token requests at the same time. 
	 * This is not to block API requests (controlled by pendingRequests !empty) */
	public static boolean refreshTokenInFlightSentinel = false;
	
	/**
	 * Tracked request instances within Volley's requestQueue.
	 */
	private static Map<ApiRequestInfo<?>, Empty> trackVolleyRequests = new ConcurrentHashMap<ApiRequestInfo<?>, Empty>();

	/**
	 * When requests are awaiting Authentication requests before proceeding,
	 * they appear here. If one request is here, all other requests will block
	 * (and will be appended to the list). Once it unblocks, all requests are
	 * queued to Volley. It's possible that the request comes back here (like if
	 * there's a pending action or when other non-immediate processing is 
	 * required before proceeding to send the request).
	 */
	static Queue<ApiRequestInfo<?>> pendingRequests = new ConcurrentLinkedQueue<ApiRequestInfo<?>>();

	public static void setup(Context appContext, RequestQueue requestQueue) {
		RequestApiHandler.appContext = appContext;
		RequestApiHandler.requestQueue = requestQueue;
	}

	/**
	 * Perform an authenticated GET request to the API.
	 * (this is NOT for unauthenticated requests like "checkVersion" and "/token")
	 * 
	 * @param typeOfT  Gson's TypeToken syntax.
	 * @param context
	 * @param url
	 *            complete url. Always use Uri.Builder !
	 * @param cancelTag
	 *            null for none
	 * @param listener
	 *            request lifecycle listener
	 */
	public static <T> void initiateStandardGetApiRequest(java.lang.reflect.Type typeOfT, 
			Context context,
			String url, String cancelTag,	// null for none
			int apiRequestOpts,
			ApiRequestListener<T> listener) {
		initiateStandardApiRequest(typeOfT, context, Method.GET, url, null, null,
				cancelTag, apiRequestOpts, listener);
	}

	/**
	 * Perform an authenticated request to the API.
	 * (this is NOT for unauthenticated requests like "checkVersion" and "/token")
	 * @param context
	 * @param method
	 *            volley's Request.Method
	 * @param url
	 *            complete url. Always use Uri.Builder !
	 * @param body
	 *            null if none (like for a GET or DELETE method)
	 * @param cancelTag
	 *            null for none
	 * @param listener
	 *            request lifecycle listener
	 */
	public static <T> void initiateStandardApiRequest(java.lang.reflect.Type typeOfT, Context context,
			int method, String url, String body, // null if none (like for GET or DELETE)
			String contentType, // specify if a body is passed; null for
								// application/json; charset=utf-8
			String cancelTag, // null for none
			int apiRequestOpts, 
			ApiRequestListener<T> listener) {
		initiateStandardApiRequest(new ApiRequestInfo<T>(typeOfT, cancelTag, context,
				method, url, body, contentType, RequestApi.getAuthorizedHeaders(context), 
				apiRequestOpts, listener));
	}

	/**
	 * Main Entry Point for every "standard" authenticated API requests.
	 * (Do NOT use this if AUTHENTICATED is not required like "checkVersion" and "/token")
	 * 
	 * @param requestInfo
	 */
	private static <T> void initiateStandardApiRequest(ApiRequestInfo<T> requestInfo) 
	{
		// fail-safe
		if(requestInfo.context == null) {
			Report.reportInternalException(new NullPointerException("Context is null when doing API request; tag:" + requestInfo.cancelTag));
			return;
		}
		
		// setup default retry and caching policy  
		defaultVolleyRequestSetup(requestInfo);
		
		//------------------------------------
		// Cancel requests with matching Tags
		//------------------------------------
		
		boolean queueWasEmpty = pendingRequests.isEmpty();
		
		if(requestInfo.hasApiOpt(ApiOpt.CANCEL_ALL_PREVIOUS) && requestInfo.cancelTag != null) {
			// remove all matching requests from Volley
			requestQueue.cancelAll(requestInfo.cancelTag);
			
			// remove all matching requests in our pending queue
			Iterator<ApiRequestInfo<?>> iter = pendingRequests.iterator();
			while(iter.hasNext()) {
				ApiRequestInfo<?> entry = iter.next();
				if(requestInfo.cancelTag.equals(entry.cancelTag)) {
					// silently remove requests (following the pattern used by Volley)
					iter.remove();
				}
			}
		}

		//------------------------------------
		// Notify request started (or queued)
		//------------------------------------
		
		fireStartedEvent(requestInfo.listener);

		//------------------------------------
		// Put in pendingRequests if already 
		// something in there waiting.
		//------------------------------------

		if(!queueWasEmpty) {
			pendingRequests.add(requestInfo);
			return;
		}
			
		//----------------------------
		// Refresh Token Check
		//----------------------------

		if(!OAuthRequestApi.hasRefreshToken()) 
		{
			//----------------------------
			// No Refresh token
			//----------------------------
			
			// put the request in our queue for later processing when we have a good token
			pendingRequests.add(requestInfo);
			
			// attempt to login with userName password and proceed.
			loginWithUserNamePasswordAndProceed();
			return;
		}
		
		//----------------------------
		// Access token check 
		//----------------------------
		
		if(!OAuthRequestApi.hasAccessToken()) 
		{
			// NOTE: SaveTime/ExpireIn in preferences defaults to 0 if not set and will end up here anyways

			//----------------------------
			// No or Expired Access Token
			//----------------------------

			// put the request in our queue for later processing when we have a good access token
			pendingRequests.add(requestInfo);
			
			refreshTokenAndProceedWithPendingRequests();
			return;
		}
		
		//------------------------------------
		// Send Request to Volley
		//------------------------------------

		queueVolleyRequest(requestInfo);
	}

	/**
	 * Perform a "standard" unauthenticated API requests. (like /checkversion)
	 * 
	 * This is NOT for AUTHENTICATED requests and NOT for /token either. 
	 * /token return OWIN-compliant errors, not API-compliant
	 * 
	 * @param context
	 * @param method
	 *            volley's Request.Method
	 * @param url
	 *            complete url. Always use Uri.Builder !
	 * @param body
	 *            null if none (like for a GET or DELETE method)
	 * @param contentType
	 *            used when a body is specified. null for "application/json"
	 * @param cancelTag
	 *            null for none
	 * @param listener
	 *            request lifecycle listener
	 */
	
	public static <T> void initiateStandardUnauthenticatedApiRequest(java.lang.reflect.Type typeOfT, Context context,
			int method, String url, String body, // null if none (like for GET or DELETE)
			String contentType, // specify if a body is passed; null for
								// application/json; charset=utf-8
			String cancelTag, // null for none
			int apiRequestOpts, ApiRequestListener<T> listener) {
		initiateStandardUnauthenticatedApiRequest(new ApiRequestInfo<T>(typeOfT, cancelTag, context,
				method, url, body, contentType, RequestApi.getHeaders(context), 
				apiRequestOpts, listener));
	}

	/**
	 * Main Entry Point for every "standard" unauthenticated API requests. (like /checkversion and signup)
	 * 
	 * (Do NOT use this for AUTHENTICATED requests and do not use for /token - as it does not behave 
	 * like a normal API call.)
	 * 
	 * @param requestInfo
	 */
	private static <T> void initiateStandardUnauthenticatedApiRequest(ApiRequestInfo<T> requestInfo) {

		// fail-safe
		if(requestInfo.context == null) {
			Report.reportInternalException(new NullPointerException("Context is null when doing unauth API request; tag:" + requestInfo.cancelTag));
			return;
		}
		
		//------------------------------------
		// Device Internet Connectivity check
		//------------------------------------
		
		if(!UtilityFunctions.isConnected(appContext)) {
			stopRequestWithNoInternetConnectionError(requestInfo);
			return;
		}
		
		// setup default retry and caching policy  
		defaultVolleyRequestSetup(requestInfo);
		
		//------------------------------------
		// Cancel requests with matching Tags
		//------------------------------------
		
		if(requestInfo.hasApiOpt(ApiOpt.CANCEL_ALL_PREVIOUS) && requestInfo.cancelTag != null) {
			// remove all matching requests from Volley
			requestQueue.cancelAll(requestInfo.cancelTag);
		}

		//------------------------------------
		// Notify request started (or queued)
		//------------------------------------
		
		fireStartedEvent(requestInfo.listener);
	
		//------------------------------------
		// Send Request to Volley
		//------------------------------------
		
		queueVolleyRequest(requestInfo);
	}
	
	private static void stopRequestWithNoInternetConnectionError(ApiRequestInfo<?> requestInfo) {
		
		if(!requestInfo.hasApiOpt(ApiOpt.SILENT_NETWORK_FAILURE)) {
			if(UtilityFunctions.isValidActivityContext(requestInfo.context))
				UtilityFunctions.displayNoInternetError(requestInfo.context);
			else
				UtilityFunctions.displayNoInternetError(appContext);
		}
		
		fireErrorEvent(requestInfo.listener, new NoConnectionError());
		fireFinallyEvent(requestInfo);
		fireErrorHandlingUIDoneEvent(requestInfo.listener);
	}
	
	private static String httpVerb(int method) {
		switch(method) {
			case Method.GET: return "GET";
			case Method.POST: return "POST";
			case Method.PATCH: return "PATCH";
			case Method.PUT: return "PUT";
			case Method.DELETE: return "DELETE";
			default: 
				return Integer.toString(method);
		}
	}
	
	/**
	 * send request to Volley. Track the request internally.
	 * @param requestInfo
	 */
	static void queueVolleyRequest(ApiRequestInfo<?> requestInfo) {
		
		trackVolleyRequests.put(requestInfo, Empty.instance);
		
		if(BuildConfig.NETWORK_TRACE) {
			System.out.println("[Queue]: "+httpVerb(requestInfo.method)+" "+requestInfo.url);
		}
		
		requestQueue.add(requestInfo.getVolleyRequest());
	}
	
	public static void proceedWithPendingRequests() {

		// queue all pending requests
		ApiRequestInfo<?> requestInfo;
		while((requestInfo = pendingRequests.poll()) != null) {
			
			// prep request for "resend" after OAuth/Pending Action issues 
			
			Map<String,String> headers = requestInfo.headers; 
			if(headers != null && headers.containsKey(HEADER_AUTHORIZATION)) {
				String accessToken = AppPreferences.getInstance().getAccessToken();
				headers.put(HEADER_AUTHORIZATION, "bearer " + accessToken);
			}
			
			queueVolleyRequest(requestInfo);
		}
	}
	
	/**
	 * cancel all requests initiated by this class
	 */
	public static <T> void abortAllRequests() {

		PendingActions.deleteAllPendingActions();
		
		// We do not want to cancel ALL Volley requests, just those that we
		// track here
		// (i.e. standard authenticated API calls)
		requestQueue.cancelAll(new RequestQueue.RequestFilter() {
			@Override
			public boolean apply(Request<?> request) {
				if (trackVolleyRequests.containsKey(request)) {
					ApiRequestInfo<?> requestInfo = ((ApiRequestInfo<?>) request);
					fireAbortedEvent(requestInfo);
					cleanupRequestAndDoFinally(requestInfo);
					return true;
				}

				return false;
			}
		});

		// clear remaining requests (the current one might very well be in that list)
		Iterator<ApiRequestInfo<?>> trackedIterator = trackVolleyRequests.keySet().iterator();
		while (trackedIterator.hasNext()) {
			ApiRequestInfo<?> requestInfo = trackedIterator.next();
			fireAbortedEvent(requestInfo);
			cleanupRequestAndDoFinally(requestInfo);
		}
		
		Iterator<ApiRequestInfo<?>> iter = pendingRequests.iterator();
		while (iter.hasNext()) {
			ApiRequestInfo<?> entry = iter.next();

			fireAbortedEvent(entry);
			fireFinallyEvent(entry);

			// silently remove requests (following the pattern used by Volley)
			iter.remove();
		}
	}

	public static <T> void fireStartedEvent(ApiRequestListener<T> listener) {
		try {
			listener.onRequestStarted();
		}
		catch(Exception ex) {
			Report.reportInternalException("Exception Calling onRequestStarted() on an ApiRequestListener", ex);
		}
	}

	public static <T> void fireCompletedEvent(ApiRequestListener<T> listener, T response) {
		try {
			listener.onRequestCompleted(response);
		}
		catch(Exception ex) {
			Report.reportInternalException("Exception Calling onRequestCompleted() on an ApiRequestListener response:" + response, ex);
		}
	}
	
	public static <T> void fireCompletedEvent(ApiRequestInfo<T> requestInfo, T response) {
		try {
			requestInfo.listener.onRequestCompleted(response);
		}
		catch(Exception ex) {
			Report.reportInternalException("Exception Calling onRequestCompleted() on RequestInfo url:" + 
							requestInfo.url + " tag:" + requestInfo.cancelTag, ex);
		}
	}
	
	public static <T> void fireErrorEvent(ApiRequestListener<T> listener, VolleyError error) {
		try {
			listener.onRequestError(error);
		}
		catch(Exception ex) {
			Report.reportInternalException("Exception Calling onRequestError() on an ApiRequestListener VolleyError:" + error.toString(), ex);
		}
	}

	public static <T> void fireApiErrorEvent(ApiRequestListener<T> listener, ApiErrorModel apiError) {
		try {
			listener.onRequestApiError(apiError);
		}
		catch(Exception ex) {
			Report.reportInternalException("Exception Calling onRequestApiError(). apiError: " + new Gson().toJson(apiError), ex);
		}
	}

	public static <T> void fireAbortedEvent(ApiRequestInfo<T> requestInfo) {
		try {
			requestInfo.listener.onRequestAborted();
		}
		catch(Exception ex) {
			Report.reportInternalException("Exception Calling onRequestAborted() on RequestInfo url:" + 
					requestInfo.url + " tag:" + requestInfo.cancelTag, ex);
		}
	}

	public static <T> void fireFinallyAndUIDoneEvent(ApiRequestListener<T> listener) {
		fireFinallyEvent(listener);
		fireErrorHandlingUIDoneEvent(listener);
	}
	
	public static <T> void fireFinallyEvent(ApiRequestListener<T> listener) {
		try {
			listener.onRequestFinally();
		}
		catch(Exception ex) {
			Report.reportInternalException("Exception Calling onRequestFinally() on an ApiRequestListener", ex);
		}
	}

	public static <T> void fireErrorHandlingUIDoneEvent(ApiRequestListener<T> listener) {
		try {
			listener.onErrorHandlingUIDone();
		}
		catch(Exception ex) {
			Report.reportInternalException("Exception Calling onErrorHandlingUIDone() on an ApiRequestListener", ex);
		}
	}

	public static <T> void fireFinallyEvent(ApiRequestInfo<T> requestInfo) {
		try {
			requestInfo.listener.onRequestFinally();
		}
		catch(Exception ex) {
			Report.reportInternalException("Exception Calling onRequestFinally() on RequestInfo url:" + 
					requestInfo.url + " tag:" + requestInfo.cancelTag, ex);
		}
	}
	
	public static <T> void cleanupRequestAndDoFinally(ApiRequestInfo<T> requestInfo) {
		cleanupRequest(requestInfo);
		fireFinallyEvent(requestInfo);
	}

	public static <T> void cleanupRequest(ApiRequestInfo<T> requestInfo) {
		trackVolleyRequests.remove(requestInfo);
	}
	
	public static void logout() {
		RequestApiHandler.abortAllRequests();
		OAuthRequestApi.clearLogonInformation();

		Intent intent = new Intent(appContext, SignInSignUpActivity.class);
		intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
		appContext.startActivity(intent);
	}
	
	static Map<String, String> getBaseAuthenticationHeader() {
		Map<String, String> headers = RequestApi.getHeaders(appContext);
		headers.put(HEADER_AUTHORIZATION, "Basic YW5kcm9pZGFwcDo5dEtRWG5ySXpqV1dEc1ZScXdiM0RSWUlmQk1PVzZ0WXBVc2FhaVE5");
		
		return headers;
	}

	static <T> Request<T> defaultVolleyRequestSetup(Request<T> request) {
		//to set on Volley Request :
		request.setShouldCache(false);
		request.setRetryPolicy(new DefaultRetryPolicy(15000, 0, 0));
		
		return request;
	}

	public static void addPendingRequest(ApiRequestInfo<?> requestInfo) {
		pendingRequests.add(requestInfo);
	}

	/**
	 * This will prematurely fire the relevant events to signify that the request did not work. 
	 * This is to report an "internal error". 
	 * Call this for requests that were never queued (or even created).
	 * 
	 * Nothing to "cleanup" here since it was never queued in the first place.
	 * 
	 * @param listener
	 * @param withStartEvent
	 */
	public static void stopRequestWithErrorBeforeQueueing(ApiRequestListener<?> listener, boolean withStartEvent) {
		if(withStartEvent)
			fireStartedEvent(listener);
		fireErrorEvent(listener, new VolleyError("Internal Error"));
		fireFinallyAndUIDoneEvent(listener);
	}

	public static void concludeWithNetworkError(ApiRequestInfo<?> requestInfo, VolleyError error) {

		Report.reportInternalException("Fatal Network Error; Tag:" + requestInfo.cancelTag, error);

		RequestApiHandler.fireErrorEvent(requestInfo.listener, error);
		RequestApiHandler.cleanupRequestAndDoFinally(requestInfo);

		if(!requestInfo.hasApiOpt(ApiOpt.SILENT_NETWORK_FAILURE)) {
			if(UtilityFunctions.isConnected(RequestApiHandler.appContext))
				UtilityFunctions.displayNetworkError(RequestApiHandler.appContext);
			else
				UtilityFunctions.displayNoInternetError(RequestApiHandler.appContext);
		}
		
		// The above Toasts use the Application context, they should "survive" an Activity dismiss. 
		// (errorHandlingUIDone can be used to finish current Activity) 
		RequestApiHandler.fireErrorHandlingUIDoneEvent(requestInfo.listener);
	}

	/**
	 * Perform an authenticated request to the API to get an Image
	 * (this is NOT for unauthenticated requests)
	 */
	public static void initiateStandardApiImageRequest(Context context,
			String url, int maxWidth, int maxHeight, ScaleType scaleType, Config decodeConfig, int apiRequestOpts, 
			ApiRequestListener<Bitmap> listener) {
		initiateStandardApiRequest(new ApiImageRequestInfoAdapter(context,
				url, maxWidth, maxHeight, scaleType, decodeConfig, RequestApi.getAuthorizedHeaders(context), 
				apiRequestOpts, listener));
	}

	public static void computeRedirectUrl(Request<?> requestInfo, NetworkResponse res) {
		// IMPORTANT: The redirection is NOT always happening here in all cases. It depends on the Stack layer and
		// also it depends if the protocol changes.
		// Volley already has code that pickups the redirect url from "Location", but the problem is it does not 
		// support relative ones and does not resend (unless you have a retry policy). 
		// (the internal redirect seems to support relative redirects too).
		// The following code support relative redirect URLs.
		
		String location = res.headers.get("Location");
		String newUrl = URI.create(requestInfo.getOriginUrl()).resolve(location).toString();
		System.out.println("Redirect ["+res.statusCode+"] to:" + newUrl + " from Location:" + location);
		requestInfo.setRedirectUrl(newUrl);
	}

	public static boolean isRedirect(NetworkResponse res) {
		return res.statusCode >= 301 && res.statusCode <= 303 && res.headers != null && res.headers.get("Location") != null;
	}
}
