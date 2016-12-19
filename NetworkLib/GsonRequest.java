
import java.io.UnsupportedEncodingException;
import java.util.Map;

import com.android.volley.AuthFailureError;
import com.android.volley.NetworkResponse;
import com.android.volley.ParseError;
import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.Response.ErrorListener;
import com.android.volley.Response.Listener;
import com.android.volley.toolbox.HttpHeaderParser;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

/**
 * Perform a request parsing the response with GSON into type <T> 
 * 
 * @author Example class from the Google Volley Documentation
 * @author Ankur Parashar
 */
public class GsonRequest<T> extends Request<T> {
    private Gson gson = new Gson();
    private java.lang.reflect.Type typeOfT;
    private Map<String, String> headers;
    public Listener<T> listener;
    private boolean formUrlEncodedType;

    /**
     * Make a GET request and return a parsed object from JSON.
     *
     * @param url URL of the request to make
     * @param clazz Relevant class object, for Gson's reflection
     * @param headers Map of request headers
     */
    public GsonRequest(String cancelTag, int method, String url, java.lang.reflect.Type typeOfT, Map<String, String> headers,
    		boolean formUrlEncodedType, Listener<T> listener, ErrorListener errorListener) {
        super(method, url, errorListener);
        this.typeOfT = typeOfT;
        this.headers = headers;
        this.listener = listener;
        this.setTag(cancelTag);
        this.formUrlEncodedType = formUrlEncodedType;
    }

    @Override
    public Map<String, String> getHeaders() throws AuthFailureError {
        return headers != null ? headers : super.getHeaders();
    }

    @Override
    protected void deliverResponse(T response) {
        listener.onResponse(response);
    }

	@Override
	public String getBodyContentType() {
        if(formUrlEncodedType) {
        	return "application/x-www-form-urlencoded; charset=utf-8";
        } 
        else {
        	return "application/json; charset=utf-8";
        }
	}

    @SuppressWarnings("unchecked")
	@Override
    protected Response<T> parseNetworkResponse(NetworkResponse response) {
        try {
            String json = new String(response.data,
                    HttpHeaderParser.parseCharset(response.headers));
            return (Response<T>)Response.success(gson.fromJson(json, typeOfT), 
            		HttpHeaderParser.parseCacheHeaders(response));
        } catch (UnsupportedEncodingException e) {
            return Response.error(new ParseError(e));
        } catch (JsonSyntaxException e) {
            return Response.error(new ParseError(e));
        }
    }
}
