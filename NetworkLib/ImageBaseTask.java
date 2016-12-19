import java.util.Map;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;

import com.android.volley.AuthFailureError;
import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Response.ErrorListener;
import com.android.volley.Response.Listener;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.ImageRequest;
import com.app.loadboard.reporting.Report;

public class ImageBaseTask extends ImageRequest {

	protected Map<String, String> headers;

	@SuppressWarnings("deprecation")
	public ImageBaseTask(String url, Listener<Bitmap> listener, int maxWidth,
			int maxHeight, Config decodeConfig, ErrorListener errorListener) {
		super(url, listener, maxWidth, maxHeight, decodeConfig, errorListener);
		 this.setRetryPolicy(new DefaultRetryPolicy(0, 3, 0));
	}

	@Override
	public Map<String, String> getHeaders() throws AuthFailureError {
		return this.headers;
	}

	public void setHeaders(Map<String, String> headers) {
		this.headers = headers;
	}

	@Override
	protected VolleyError parseNetworkError(VolleyError volleyError) {

		//NetworkResponse res = volleyError.networkResponse;
		Report.reportInternalException(volleyError);

		return super.parseNetworkError(volleyError);
	}
}
