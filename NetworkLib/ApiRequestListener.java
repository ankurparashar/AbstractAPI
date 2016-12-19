
import com.android.volley.VolleyError;
import com.app.loadboard.objects.models.ApiErrorModel;

/**
 * Request callback interface
 * 
 * @author Ankur Parashar
 * 
 */
public abstract class ApiRequestListener<T> {

	ApiRequestInfo<T> requestInfo;

	/** if a standard error is returned, this is going to be non-null */
	public ApiErrorModel apiError;

	public static final ApiRequestListener<Empty> NoopEmpty = new ApiRequestListener<Empty>() {};

	public void addApiOpt(int apiOpt) {
		requestInfo.apiRequestOpts |= apiOpt; 
	}

	public void removeApiOpt(int apiOpt) {
		requestInfo.apiRequestOpts &= ~apiOpt;
	}

	public boolean hasApiOpt(int apiOpt) {
		return requestInfo.hasApiOpt(apiOpt);
	}

	
	/**
	 * Called when request started. Good place to start a spinner.
	 * 
	 * Exceptions thrown here will be silently reported
	 */
	public void onRequestStarted() throws Exception {}

	/**
	 * Called when request finished (error or not). Good place to remove a spinner.
	 * 
	 * Exceptions thrown here will be silently reported
	 */
	public void onRequestFinally() throws Exception {}

	/**
	 * When some UIs are displayed as a result of errors or pending actions, this will be called when they are 
	 * dismissed. If no UIs, will be called immediately.
	 * 
	 * Only executes if there is an error. Executes after 
	 * error dialogEvent dismissed (like a pending action message).
	 * Executes even if no error dialogEvent. Executes immediately
	 * on error Toast.
	 * 
	 * 
	 * Exceptions thrown here will be silently reported
	 */
	public void onErrorHandlingUIDone() throws Exception {}
	
	/**
	 * Called when request completed successfully (status = 2xx / request from cache)
	 * 
	 * @param response CANNOT BE NULL. An object (potentially empty) will be present. 
	 * (if null, it will go through the onRequestError -- this is Volley's internal logic)
	 * 
	 * Exceptions thrown here will be silently reported
	 */
	public void onRequestCompleted(T response) throws Exception {}

	/**
	 * Called when request did not even go. 
	 * NOTE: "Started" and "Finally" is called also in this case
	 * 
	 * Exceptions thrown here will be silently reported
	 */
	public void onRequestAborted() throws Exception {}

	/**
	 * Called when an error occurred and we received a valid ApiError structure. 
	 * This includes Pending Actions and anything else. Typically it is not required to hook to that event.  
	 * 
	 * @param apiError will never be null, but the fields inside could be.
	 * 
	 * Exceptions thrown here will be silently reported
	 */
	public void onRequestApiError(ApiErrorModel apiError) throws Exception {}

	/**
	 * Called when the request gets a 'final' error (no more pending actions or other internal actions to take). 
	 * NOTE: Error may come from dependent requests (i.e. token).
	 * NOTE: Most errors will be handled automatically, so this method is probably not required.
	 * 
	 * Exceptions thrown here will be silently reported
	 */
	public void onRequestError(VolleyError error) throws Exception {}
}
