import java.io.UnsupportedEncodingException;
import java.util.Map;

import org.apache.http.protocol.HTTP;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;

import com.android.volley.AuthFailureError;
import com.android.volley.NetworkResponse;
import com.android.volley.ParseError;
import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.toolbox.HttpHeaderParser;
import com.app.loadboard.reporting.Report;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

public class ApiRequestInfo<T> extends Request<T> 
{
	public String cancelTag;  // null for none
	public Context context; // verify validity (activity may be long gone) 
	public int method;
	public String url;
	public String body;	// null if none (like for GET)
	public String contentType;  // specify if a body is passed; null for application/json; charset=utf-8
	
	public int apiRequestOpts; 
	
	public ApiRequestListener<T> listener; 
	public java.lang.reflect.Type typeOfT;
	public Map<String,String> headers;
	public int logonAttempts; // fail-safe in case we run into an infinite loop (i.e. got a token but the server refuses it on the next API call).
	
	/** this field is set within a worker thread. the only purpose of this field is to 
	 * access the network data on normal completion. Do NOT USE THIS unless you know what you are doing. 
	 */
	private NetworkResponse __networkResponse;
	
	public ApiRequestInfo(java.lang.reflect.Type typeOfT, String cancelTag, Context context, int method,
			String url, String body, String contentType, Map<String,String> headers,
			int apiRequestOpts,
			final ApiRequestListener<T> listener) {

		super(method, url, new VolleyApiRequestErrorHandler(null));
		
		// cannot pass "this" before the constructor is called.
		((VolleyApiRequestErrorHandler)this.getErrorListener()).requestInfo = this;
		listener.requestInfo = this;
		
		this.typeOfT = typeOfT;
		this.cancelTag = cancelTag;
		this.context = context;
		this.method = method;
		this.url = url;
		this.body = body;
		this.contentType = contentType;
		this.listener = listener;
		this.headers = headers;
		this.apiRequestOpts = apiRequestOpts;
		this.logonAttempts = 0;
	}

	public boolean hasApiOpt(int apiOpt) {
		return (this.apiRequestOpts & apiOpt) == apiOpt;
	}

	public Request<T> getVolleyRequest() {
		return this;
	}
	
	@Override
	public byte[] getBody() throws AuthFailureError {
		try {
			if(body == null)
				return null;
			return body.getBytes("UTF-8");
		} catch (UnsupportedEncodingException e) {
			// will never occur; UTF-8 is present for sure.
			return null;
		}
	}

	@Override
	protected void deliverResponse(T response) {
		
		Report.feedNetworkResponse(this, __networkResponse);

		RequestApiHandler.fireCompletedEvent(this, response);
		RequestApiHandler.cleanupRequestAndDoFinally(this);
	}

	@Override
	public Map<String, String> getHeaders() throws AuthFailureError {
		return headers;
	}

	@Override
	public String getBodyContentType() {
		if(contentType == null)
			return "application/json; charset=utf-8";
		else
			return contentType;
	}

	@SuppressWarnings("unchecked")
	@Override
	protected Response<T> parseNetworkResponse(NetworkResponse response) {
		
		//-------------------------------------------------------------
		// **** WARNING: This is called from Volley's worker thread. 
		// **** DO NOT access anything outside this method!
		//-------------------------------------------------------------

		this.__networkResponse = response;

        try {
            if(typeOfT == Empty.class) {
            	return Response.success((T)Empty.instance, HttpHeaderParser.parseCacheHeaders(response));
            }

            String responseStr = new String(response.data, HttpHeaderParser.parseCharset(response.headers));

            if(typeOfT == String.class) {
            	return Response.success((T)responseStr, HttpHeaderParser.parseCacheHeaders(response));
            }

            // JSON-ONLY GOING FORWARD
            String contentType = null;
            if(response.headers != null)
            	contentType = response.headers.get(HTTP.CONTENT_TYPE);
            
            if(contentType == null || !contentType.startsWith("application/json")) {
            	// not expected contentType format
            	return Response.error(new ParseError()); 
            }
            
            if(typeOfT == JSONArray.class) {
				return Response.success((T)new JSONArray(responseStr), HttpHeaderParser.parseCacheHeaders(response));
			}
            
			if(typeOfT == JSONObject.class) {
				return Response.success((T)new JSONObject(responseStr), HttpHeaderParser.parseCacheHeaders(response));
			}
			
			Gson gson = new Gson();
            return (Response<T>)Response.success(gson.fromJson(responseStr, typeOfT), HttpHeaderParser.parseCacheHeaders(response));
			
        } catch (UnsupportedEncodingException e) {
            return Response.error(new ParseError(e));
        } catch (JSONException e) {
            return Response.error(new ParseError(e));
        } catch (JsonSyntaxException e) {
            return Response.error(new ParseError(e));
        }
	}
}
