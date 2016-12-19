import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.net.Uri;
import android.net.http.AndroidHttpClient;
import android.os.Build;
import android.text.TextUtils;
import android.widget.ImageView.ScaleType;

import com.android.volley.Request.Method;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.HttpClientStack;
import com.android.volley.toolbox.HttpStack;
import com.android.volley.toolbox.HurlStack;
import com.android.volley.toolbox.Volley;

import com.google.gson.reflect.TypeToken;

/**
 * Single Instance for user application requests
 * 
 * @author Ankur Parashar
 * 
 */
public class RequestApi {

	private static RequestApi sInstance;

	RequestQueue mRequestQueue;

	private static String userAgent;
	
	public static class ProxyHurlStack extends HurlStack {

		@Override
		protected HttpURLConnection createConnection(URL url)
				throws IOException {

			try {
				ProxySelector selector = ProxySelector.getDefault();
				if (selector != null) {
					List<Proxy> proxyList = selector.select(url.toURI());
					if (!proxyList.isEmpty())
						return (HttpURLConnection) url.openConnection(proxyList.get(0));
				}
			} catch (Exception e) {
				Report.reportInternalException(e);
			}

			// this is the same as what's inside super.createConnection()
			return (HttpURLConnection) url.openConnection();
		}
	}

	@SuppressWarnings("deprecation")
	public static void initialize(Application appContext) {

		HttpStack stack;

		// Newer HttpURlConnection is better optimized.
		// PATCH method supported in HttpUrlConnection only in Lollipop SDK >=21 
		// In SDK < 9 HttpUrlConnection suffer issues so should never be used.
		//
		// TODO: switch on the fly based on method OR try to use X-HTTP-Method-Override (not great)
		if (Build.VERSION.SDK_INT >= 21) {
			// Java HttpUrlConnection (with Proxy Support)
			stack = new ProxyHurlStack();
		}
		else {
			// Apache HttpClient (probably no Proxy)
			stack = new HttpClientStack(AndroidHttpClient.newInstance(getUserAgent(appContext)));
		}
		
		sInstance = new RequestApi();
		sInstance.mRequestQueue = Volley.newRequestQueue(appContext, stack);
		RequestApiHandler.setup(appContext, sInstance.mRequestQueue);
		OAuthRequestApi.initialize(appContext);
	}

	public static RequestApi getInstance() {
		return sInstance;
	}

	public static String getUserAgent(Context context) {
		
		// User-Agent: 123Loadboard/1.0.0.0-b_G/1 (Nexus 5;Android 4.4.4)
		if(userAgent == null) {
			userAgent = context.getString(R.string.app_name) + "/"
					+ UtilityFunctions.getAppVersion(context) + "/"
					+ UtilityFunctions.getAppBuild(context) + " " + "("
					+ UtilityFunctions.getDeviceName() + ";" + "Android "
					+ UtilityFunctions.getAndroidVersion() + ";)";
		}
		
		return userAgent;
	}
	
	/**
	 * get Locations that matches the "string query".
	 */
	public void getLocation(Context context, String location, ApiRequestListener<SuggestionList> listener) {

		String url = Uri.parse(SUGGESTED_LOCATION_URL).buildUpon()
			.appendQueryParameter("q", location)
			.build().toString();
		
		RequestApiHandler.initiateStandardGetApiRequest(SuggestionList.class, context, url, 
				SUGGESTED_LOCATION_URL, ApiOpt.SILENT_API_FAILURE | ApiOpt.SILENT_NETWORK_FAILURE, listener);
	}

	/**
	 * get map Image
	 * @param listener
	 * @param loadId
	 * @param Activity
	 */
	public void getMap(Context context, String loadId, ApiRequestListener<Bitmap> listener) {

		String url = Uri.parse(LOAD_MAP).buildUpon()
				.appendPath(loadId)
				.appendPath("##PATH##")
				.toString();

		RequestApiHandler.initiateStandardApiImageRequest(context, url, 0, 0,  ScaleType.CENTER, Config.RGB_565, ApiOpt.NONE, listener);
	}
	
	/**
	 * cancel request
	 * 
	 * @param tag
	 *            tag
	 */
	public void cancelRequest(Context context, String tag) {
		// Jira issue Link https://123loadboard.atlassian.net/browse/BAPK-54
		try {
			mRequestQueue.cancelAll(tag);
		} catch (Exception e) {
			System.out.println(e.getMessage());
		}
	}

}
