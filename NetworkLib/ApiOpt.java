/**
 * Options to control the behavior of the RequestApi subsystem. 
 * Specify one or more ORed together.
 * Some options can be set/reset within the listener's onXxxx events.  
 */
public interface ApiOpt {

	/**
	 * Placeholder for when no options is specified.
	 */
	public static final int NONE						= (0);

	
	/**
	 * true to "silently stop" the previous ones (with matching cancelTag) before starting this one.
	 */
	public static final int CANCEL_ALL_PREVIOUS			= (1<<0);
	
	/**
	 * 	Avoids showing automatic Toasts when there is an API-related error.
	 *  (Api-related errors have a 'valid' response)
	 * 	use this if you want to do your own UI Error messages or 
	 * 	for a fire & forget type of call
	 *  Does NOT affect dialogs that Pending Actions could be showing.
	 */
	public static final int SILENT_API_FAILURE			= (1<<1);
	
	/**
	 * 	Avoids showing automatic Toasts when there is a network-related error.
	 *  (A network-related error has no response or a bad one)
	 * 	use this if you want to do your own UI Error messages or 
	 * 	for a fire & forget type of call
	 *  Does NOT affect dialogs that Pending Actions could be showing.
	 */
	public static final int SILENT_NETWORK_FAILURE		= (1<<2);

	/**
	 * 	Skips the 503 server down handling. This is used to "check" if it's up.
	 */
	public static final int NO_503_HANDLING				= (1<<3);
}
