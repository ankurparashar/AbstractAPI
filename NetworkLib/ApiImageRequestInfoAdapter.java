import java.util.Map;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.widget.ImageView.ScaleType;

import com.android.volley.AuthFailureError;
import com.android.volley.Request;
import com.android.volley.Response.Listener;
import com.android.volley.toolbox.ImageRequest;

public class ApiImageRequestInfoAdapter extends ApiRequestInfo<Bitmap> {

	ImageRequest imageRequest;

	public Request<Bitmap> getVolleyRequest() {
		return imageRequest;
	}
	
	public ApiImageRequestInfoAdapter(Context context, String url, int maxWidth, int maxHeight, ScaleType scaleType, Config decodeConfig, 
			Map<String, String> headers, int apiRequestOpts, ApiRequestListener<Bitmap> listener) {
		
			super(null, null, context, Method.GET, url, null, null, headers, apiRequestOpts, listener);
		
			VolleyApiRequestErrorHandler errorHandler = new VolleyApiRequestErrorHandler(this);
			
			imageRequest = new ImageRequest(url, new Listener<Bitmap>() {

				@Override
				public void onResponse(Bitmap response) {
					ApiImageRequestInfoAdapter.this.deliverResponse(response);
				}
			}, maxWidth, maxHeight, scaleType, decodeConfig, errorHandler) {
				@Override
				public Map<String, String> getHeaders() throws AuthFailureError {
					return ApiImageRequestInfoAdapter.this.getHeaders();
				}
			};
	}
}
