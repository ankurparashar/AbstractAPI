import java.util.HashMap;
import java.util.Map;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.support.v4.content.LocalBroadcastManager;

import com.android.volley.AuthFailureError;
import com.android.volley.NetworkResponse;
import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.Response.ErrorListener;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.HttpHeaderParser;
import com.app.loadboard.BuildConfig;
import com.app.loadboard.activity.GoogleSignInActivity;
import com.app.loadboard.activity.ServerErrorWebViewActivity;
import com.app.loadboard.data.AppPreferences;
import com.app.loadboard.data.LoadboardConstant;
import com.app.loadboard.objects.SearchParamsManager;
import com.app.loadboard.objects.models.JsonModels.LoginDetail;
import com.app.loadboard.objects.models.JsonModels.OAuthError;
import com.app.loadboard.reporting.Report;
import com.app.loadboard.utils.UtilityFunctions;
import com.facebook.Session;
import com.google.gson.Gson;

/**
 * API that deals with Authorization aspects in general.
 * This is where "/token" is being called for instance.
 * <p/>
 * Also contains login-related utilities.
 *
 * @author Ankur Parashar
 */
public class OAuthRequestApi implements LoadboardConstant {

    static Application appContext;

    public static void initialize(Application context) {
        appContext = context;
    }

    public static boolean hasRefreshToken() {

        AppPreferences prefs = AppPreferences.getInstance();

        //TODO: check refreshToken expiration date !
        return prefs.getRefreshToken() != null;
    }

    public static boolean hasAccessToken() {

        AppPreferences prefs = AppPreferences.getInstance();

        // NOTE: SaveTime/ExpireIn in preferences defaults to 0 if not set.
        // (i.e. this test works in that case)

        return prefs.getAccessToken() != null &&
                prefs.getSaveTime() + prefs.getExpireIn() * 1000 > System.currentTimeMillis();
    }

    public static void storeLoginDetail(LoginDetail loginDetail) {
        AppPreferences prefs = AppPreferences.getInstance();
        prefs.setExternalAccessToken(loginDetail.access_token);
        prefs.setAccessToken(loginDetail.access_token);
        prefs.setRefreshToken(loginDetail.refresh_token);
        prefs.setExpireIn(loginDetail.expires_in);
        prefs.setSaveTime(System.currentTimeMillis());
        prefs.setGuest(loginDetail.is_guest);
    }

    public static void clearTokenInformation() {
        // store empty loginDetail
        storeLoginDetail(new LoginDetail());
    }

    public static void clearFbSession() {
        Session session = Session.getActiveSession();
        if (session != null) {
            session.closeAndClearTokenInformation();
        }
    }

    public static void clearLogonInformation() {

        AppPreferences prefs = AppPreferences.getInstance();

        AppPreferences.setGcmRegisteredOnServer(appContext, false);
        AppPreferences.storeRegistrationId(appContext, "");

        clearFbSession();
        LocalBroadcastManager.getInstance(appContext).sendBroadcast(new Intent(GoogleSignInActivity.ACTION_GOOGLE_LOGOUT));
        System.out.println("BroadCast sent..");

        SearchParamsManager.getInstance().destroy();

        // preserve fields upon logout
        String uuid = prefs.getUUID();
        String aid = prefs.getAndroidId();
        String userName = prefs.getUserName();
        String password = prefs.getUserPassword();
        String contactEmail = prefs.getLastSeenContactEmail();
        String userNamePassJson = prefs.getUserNamePassJson();

        prefs.clearPreferences();
        prefs.setLaunchTypeSplash(true);

        prefs.setUUID(uuid);
        prefs.setAndroidId(aid);
        prefs.setUserName(userName);
        prefs.setUserPassword(password);
        prefs.setLastSeenContactEmail(contactEmail);
        prefs.setUserNamePassJson(userNamePassJson);
    }


    public static void refreshToken(final OAuthRequestApiListener listener) {

        RequestApiHandler.fireStartedEvent(listener);

        final String refreshToken = AppPreferences.getInstance().getRefreshToken();

        RequestApiHandler.requestQueue.add(
                new OAuthGsonRequest(REFRESH_TOKEN, Request.Method.POST, REFRESH_TOKEN,
                        RequestApiHandler.getBaseAuthenticationHeader(), true, listener,
                        new OAuthVolleyRequestErrorHandler(listener)) {

                    @Override
                    protected Map<String, String> getParams()
                            throws AuthFailureError {

                        HashMap<String, String> map = new HashMap<String, String>();

                        map.put("grant_type", "refresh_token");
                        map.put("refresh_token", refreshToken);

                        return map;
                    }
                }
        );
    }

    public static void loginWithUserNameAndPassword(final String username, final String password,
                                                    final OAuthRequestApiListener listener) {

        RequestApiHandler.fireStartedEvent(listener);

        RequestApiHandler.requestQueue.add(
                new OAuthGsonRequest(LOGIN_URL, Request.Method.POST, LOGIN_URL,
                        RequestApiHandler.getBaseAuthenticationHeader(), true,
                        listener, new OAuthVolleyRequestErrorHandler(listener)) {

                    @Override
                    protected Map<String, String> getParams() throws AuthFailureError {

                        HashMap<String, String> map = new HashMap<String, String>();

                        map.put("grant_type", "password");
                        map.put("username", username);
                        map.put("password", password);

                        return map;
                    }
                }
        );
    }

    /**
     * Login/Signup with facebook
     *
     * @param context     context
     * @param accessToken facebook access token
     * @param listener    callback listener
     */
    public static void externalGrantRequest(final Context context, final String provider, final String accessToken,
                                            final OAuthRequestApiListener listener) {

        RequestApiHandler.fireStartedEvent(listener);

        RequestApiHandler.requestQueue.add(
                new OAuthGsonRequest(LOGIN_URL, Request.Method.POST, LOGIN_URL,
                        RequestApiHandler.getBaseAuthenticationHeader(), true, listener,
                        new OAuthVolleyRequestErrorHandler(listener)) {

                    @Override
                    protected Map<String, String> getParams() throws AuthFailureError {

                        HashMap<String, String> map = new HashMap<String, String>();

                        map.put("grant_type", "external_grant");
                        map.put("external_provider", provider);
                        map.put("ticket", accessToken);
                        map.put("ticket_type", "token");

                        return map;
                    }
                }
        );
    }
}

class OAuthGsonRequest extends GsonRequest<LoginDetail> {

    /**
     * Set from Volley's worker thread
     */
    NetworkResponse __networkResponse;

    static abstract class OAuthResponseListener implements Response.Listener<LoginDetail> {
        OAuthGsonRequest request;
    }

    public OAuthGsonRequest(String cancelTag, int method, String url, Map<String, String> headers,
                            boolean formUrlEncodedType, final OAuthRequestApiListener listener, OAuthVolleyRequestErrorHandler errorListener) {

        super(cancelTag, method, url, LoginDetail.class, headers, formUrlEncodedType, new OAuthResponseListener() {

            @Override
            public void onResponse(LoginDetail loginDetail) {

                Report.feedNetworkResponse(request, request.__networkResponse);

                RequestApiHandler.fireCompletedEvent(listener, loginDetail);
                RequestApiHandler.fireFinallyEvent(listener);
            }
        }, errorListener);

        ((OAuthResponseListener) this.listener).request = this;
        errorListener.request = this;

        RequestApiHandler.defaultVolleyRequestSetup(this);
    }

    @Override
    protected Response<LoginDetail> parseNetworkResponse(NetworkResponse response) {
        __networkResponse = response;
        return super.parseNetworkResponse(response);
    }
}

/**
 * Automatic handling of Errors (like pending actions and standard error messages).
 * <p/>
 * Only meant for the authenticated APIs.
 *
 * @author BernardP
 */
class OAuthVolleyRequestErrorHandler implements ErrorListener {

    OAuthRequestApiListener listener;
    public Request<LoginDetail> request;

    public OAuthVolleyRequestErrorHandler(OAuthRequestApiListener listener) {
        this.listener = listener;
    }

    @Override
    public void onErrorResponse(VolleyError error) {

        Report.feedVolleyError(listener.requestInfo, error);

        // look at response httpStatus and attempt to parse
        NetworkResponse res = error.networkResponse;

        try {
            if (res == null /*|| error instanceof NetworkError || error instanceof NoConnectionError || error instanceof TimeoutError*/) {

                //----------------------------------------
                // Got no response. Connectivity issue.
                // This is a fatal error; abandon.
                //----------------------------------------

                Report.reportInternalException("Fatal Network Error of OAuth (/token) request", error);

                if (UtilityFunctions.isConnected(RequestApiHandler.appContext))
                    UtilityFunctions.displayNetworkError(RequestApiHandler.appContext);
                else
                    UtilityFunctions.displayNoInternetError(RequestApiHandler.appContext);

                RequestApiHandler.stopRequestWithErrorBeforeQueueing(listener, false);
                return;
            }

            //----------------------------------------
            // Got an actual network response. Check
            // if we can parse it and handle it.
            //----------------------------------------

            if (RequestApiHandler.isRedirect(res)) {

                RequestApiHandler.computeRedirectUrl(request, res);

                // re-queue directly to Volley (untracked)
                RequestApiHandler.requestQueue.add(request);
                return;
            }

            if (res.statusCode == 503) {
                // server is down error

                // abort everything and go to the server error page (will reload with the base_url)
                RequestApiHandler.abortAllRequests();

                ServerErrorWebViewActivity.jumpToServerError(RequestApiHandler.appContext);
                return;
            }


            if (res.data != null && res.headers != null) {

                String contentType = error.networkResponse.headers.get("Content-Type");

                if (contentType.contains("json")) {
                    Gson gson = new Gson();

                    String response;
                    try {
                        response = new String(error.networkResponse.data, HttpHeaderParser.parseCharset(error.networkResponse.headers));

                        OAuthError oauthError = (OAuthError) gson.fromJson(response, OAuthError.class);

                        try {
                            listener.onOAuthRequestError(oauthError);
                        } catch (Exception e) {
                            Report.reportInternalException(e);
                        }
                        RequestApiHandler.fireErrorEvent(listener, error);
                        RequestApiHandler.fireFinallyAndUIDoneEvent(listener);
                        return;
                    } catch (Exception e) {
                    }
                }
            }

        } catch (Exception e) {
            // just in case something goes really wrong. Will display the generic error.
        }

        //----------------------------------------
        // Could not parse the response.
        // This is a fatal error; abandon.
        //----------------------------------------

        RequestApiHandler.fireErrorEvent(listener, error);
        RequestApiHandler.fireFinallyAndUIDoneEvent(listener);

        if (!listener.hasApiOpt(ApiOpt.SILENT_NETWORK_FAILURE))
            UtilityFunctions.displayTechnicalDifficultyError(RequestApiHandler.appContext);
    }
}

