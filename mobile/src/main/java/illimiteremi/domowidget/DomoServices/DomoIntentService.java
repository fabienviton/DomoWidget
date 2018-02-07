package illimiteremi.domowidget.DomoServices;

import android.annotation.SuppressLint;
import android.app.IntentService;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.cert.CertificateException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import illimiteremi.domowidget.DomoGeneralSetting.BoxSetting;
import illimiteremi.domowidget.DomoUtils.DomoBitmapUtils;
import illimiteremi.domowidget.DomoUtils.DomoConstants;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static illimiteremi.domowidget.DomoUtils.DomoConstants.BOX_MESSAGE;
import static illimiteremi.domowidget.DomoUtils.DomoConstants.BOX_PING;
import static illimiteremi.domowidget.DomoUtils.DomoConstants.DONE;
import static illimiteremi.domowidget.DomoUtils.DomoConstants.ERROR;
import static illimiteremi.domowidget.DomoUtils.DomoConstants.JEEDOM_API_URL;
import static illimiteremi.domowidget.DomoUtils.DomoConstants.JEEDOM_URL;
import static illimiteremi.domowidget.DomoUtils.DomoConstants.MATCH;
import static illimiteremi.domowidget.DomoUtils.DomoConstants.NO_MATCH;
import static illimiteremi.domowidget.DomoUtils.DomoConstants.PING_ACTION;
import static illimiteremi.domowidget.DomoUtils.DomoConstants.READ_TIME_OUT;
import static illimiteremi.domowidget.DomoUtils.DomoConstants.REQUEST;
import static illimiteremi.domowidget.DomoUtils.DomoConstants.REQUEST_BOX;
import static illimiteremi.domowidget.DomoUtils.DomoConstants.REQUEST_GEOLOC;
import static illimiteremi.domowidget.DomoUtils.DomoConstants.REQUEST_WEBCAM;

public class DomoIntentService extends IntentService {

    private static final String TAG            = "[DOMO_INTENT_SERVICE]";
    private static final String WIDGET_VALUE   = "WIDGET_VALUE";

    // OkHttpCallback (callback utilisé pour les widgets)
    private class OkHttpCallback implements Callback {

        final DomoSerializableWidget widget;
        final Request                reTryRequest;

        public OkHttpCallback(DomoSerializableWidget widget, Request request) {
            this.widget       = widget;
            this.reTryRequest = request;
        }

        @Override
        public void onFailure(Call call, IOException e) {
            Log.e(TAG, "onFailure : " + e);
            if (reTryRequest != null) {
                Log.d(TAG, "Nouvelle tentative sur la 2em url...");
                final OkHttpClient client = getOkHttpClient(DomoConstants.MOBILE_TIME_OUT);
                client.newCall(reTryRequest).enqueue(new OkHttpCallback(widget, null));
            } else {
                sendErrorToProvider(widget);
            }
        }

        @Override
        public void onResponse(Call call, Response response) throws IOException {
            String jeedomResponse = ERROR;
            try {
                // Traitement de la réponse
                ResponseBody responseBody = response.body();
                if (responseBody != null) {
                    jeedomResponse = responseBody.string();
                    if (response.code() == 200) {
                        if (!jeedomResponse.isEmpty()) {
                            // Si ExprReg
                            if (!widget.getDomoExpReg().isEmpty()) {
                                // Expression réguliere
                                if (jeedomResponse.matches(widget.getDomoExpReg())) {
                                    jeedomResponse = MATCH;
                                } else {
                                    jeedomResponse = NO_MATCH;
                                }
                            } else {
                                // Si la réponse contient Error
                                if (jeedomResponse.contains("error") ||
                                        jeedomResponse.contains("Aucune commande correspondant")) {
                                    jeedomResponse = ERROR;
                                }
                            }
                        }
                    }
                    Log.d(TAG, "réponse Jeedom : " + response.code() + " - " + (jeedomResponse.isEmpty() ? "none" : jeedomResponse )+ " - idWidget = " + widget.getDomoId());
                }
            } catch (NullPointerException e) {
                Log.e(TAG, "Erreur Jeedom : " + e + " - idWidget = " + widget.getDomoId());
            }
            if (!jeedomResponse.isEmpty()){
                // On ne pas traite pas la réponse suivant le widget
                switch (widget.getDomoType()) {
                    case PUSH :
                        break;
                    default:
                        sendToProvider(jeedomResponse, widget);
                }
            }
        }
    }

    public DomoIntentService() {
        super("DomoIntentService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {

        Bundle extras = intent.getExtras();
        if (extras != null) {
            try {
                BoxSetting boxSetting          = (BoxSetting) intent.getSerializableExtra("BOX");
                DomoSerializableWidget widget  = (DomoSerializableWidget) intent.getSerializableExtra("WIDGET");
                String intentAction = intent.getAction();
                Log.d(TAG, "=> Action : " + intent.getAction());
                if (boxSetting != null) {
                    switch (intentAction) {
                        case REQUEST_BOX :
                            sendRequestForTest(boxSetting);
                            break;
                        case REQUEST :
                            sendRequestToJeedom(boxSetting, widget);
                            break;
                        case REQUEST_GEOLOC :
                            sendRequestGeoToJeedom(boxSetting, widget);
                            break;
                        case REQUEST_WEBCAM :
                            getWebCamPictureFromToJeedom(boxSetting, widget);
                            break;
                        default:
                            // NOTHING
                    }
                } else {
                    sendErrorToProvider(widget);
                }
            } catch (Exception e) {
                Log.e(TAG, "Erreur -> IntentService : " + e);
            }
        }
    }

    /**
     * sendRequestForTest
     * @param boxSetting
     */
    private void sendRequestForTest(final BoxSetting boxSetting) {

        // OkHttpBoxCallback (callback utilisé pour le ping de la box)
        class OkHttpBoxCallback implements Callback {

            public OkHttpBoxCallback() {
            }

            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "onFailure : " + e);
                sendTestResponseToProvider(false, e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {

                Boolean pingOK;
                try {
                    String jsonData = response.body().string();
                    Log.d(TAG, "réponse de Jeedom : " + jsonData);
                    JSONObject jsonObject = new JSONObject(jsonData);
                    pingOK = jsonObject.getString("result").contains("pong");
                    sendTestResponseToProvider(pingOK,"OK");
                } catch (Exception e) {
                    Log.e(TAG, "Erreur : " + e);
                    sendTestResponseToProvider(false, e.getMessage());
                }
            }
        }

        // TimeOut
        Integer wifiTimeOut   = boxSetting.getBoxTimeOut() == 0 ? DomoConstants.WIFI_TIME_OUT : boxSetting.getBoxTimeOut();
        Integer mobileTimeOut = boxSetting.getBoxTimeOut() == 0 ? DomoConstants.MOBILE_TIME_OUT : boxSetting.getBoxTimeOut();
        Integer requestTimeOut;

        // Création de la requete http suivant le type de connexion
        Request request;
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("jsonrpc", "2.0");
            jsonObject.put("id"     , "1");
            jsonObject.put("method" , "ping");
            MediaType JSON   = MediaType.parse("application/json; charset=utf-8");
            RequestBody body = RequestBody.create(JSON, jsonObject.toString());

            if (checkWifi()) {
                // Url Interne (en Wifi)
                requestTimeOut = wifiTimeOut;
                request = new Request.Builder().url(boxSetting.getBoxUrlInterne() + JEEDOM_API_URL).post(body).build();
            } else {
                // Url Externe
                requestTimeOut = mobileTimeOut;
                request = new Request.Builder().url(boxSetting.getBoxUrlExterne() + JEEDOM_API_URL).post(body).build();
            }
            // Execution de la requete
            final OkHttpClient client = getOkHttpClient(requestTimeOut);
            client.newCall(request).enqueue(new OkHttpBoxCallback());
        } catch (OutOfMemoryError outOfMemoryError) {
            Log.e(TAG, "Erreur Mémoire : " + outOfMemoryError.getMessage());
            sendTestResponseToProvider(false, "OutOfMemoryError");
        } catch (Exception e) {
            Log.e(TAG, "Erreur : " + e.getMessage());
            sendTestResponseToProvider(false, e.getMessage());
        }
    }

    /**
     * sendRequestToJeedom
     * @param boxSetting
     * @param widget
     */
    private void sendRequestToJeedom(final BoxSetting boxSetting, final DomoSerializableWidget widget) {

        // TimeOut
        Integer wifiTimeOut   = boxSetting.getBoxTimeOut() == 0 ? DomoConstants.WIFI_TIME_OUT : boxSetting.getBoxTimeOut();
        Integer mobileTimeOut = boxSetting.getBoxTimeOut() == 0 ? DomoConstants.MOBILE_TIME_OUT : boxSetting.getBoxTimeOut();
        Integer requestTimeOut;

        // Request Http
        Request request, reTryRequest;

        // Création de la requete http suivant le type de connexion
        try {
            if (checkWifi()) {
                // Url Interne (en Wifi)
                Log.d(TAG, "url Interne (Wifi) => " + widget.getDomoAction());
                requestTimeOut = wifiTimeOut;

                try {
                    // URL - 1er tentative en interne
                    request = new Request.Builder().url(boxSetting.getBoxUrlInterne() + JEEDOM_URL + boxSetting.getBoxKey() + "&" + widget.getDomoAction()).build();
                } catch (Exception e) {
                    // Utilisation url externe (si interne ko)
                    request = new Request.Builder().url(boxSetting.getBoxUrlExterne() + JEEDOM_URL + boxSetting.getBoxKey() + "&" + widget.getDomoAction()).build();
                }

                try {
                    // URL - 2em Tentative en externe
                    reTryRequest = new Request.Builder().url(boxSetting.getBoxUrlExterne() + JEEDOM_URL + boxSetting.getBoxKey() + "&" + widget.getDomoAction()).build();
                } catch (Exception e) {
                    reTryRequest = null;
                }
            } else {
                // Url Externe (mobile)
                Log.d(TAG, "url Externe (Mobile) => " + widget.getDomoAction());
                requestTimeOut = mobileTimeOut;
                // URL - 1er tentative en externe
                request = new Request.Builder().url(boxSetting.getBoxUrlExterne() + JEEDOM_URL + boxSetting.getBoxKey() + "&" + widget.getDomoAction()).build();
                try {
                    // 2em Tentative en interne
                    reTryRequest = new Request.Builder().url(boxSetting.getBoxUrlInterne() + JEEDOM_URL + boxSetting.getBoxKey() + "&" + widget.getDomoAction()).build();
                } catch (Exception e) {
                    reTryRequest = null;
                }
            }
            final OkHttpClient client = getOkHttpClient(requestTimeOut);
            client.newCall(request).enqueue(new OkHttpCallback(widget, reTryRequest));
        } catch (OutOfMemoryError outOfMemoryError) {
            Log.e(TAG, "Erreur Mémoire : " + outOfMemoryError.getMessage());
            sendErrorToProvider(widget);
        } catch (Exception e) {
            Log.e(TAG, "Erreur : " + e.getMessage());
            sendErrorToProvider(widget);
        }
    }

    /**
     * getWebCamPictureFromToJeedom
     * @param boxSetting
     * @param widget
     */
    private void getWebCamPictureFromToJeedom(final BoxSetting boxSetting, final DomoSerializableWidget widget){

        // OkHttpWebCamCallback (callback utilisé pour le téléchargement de l'image webcam)
        class OkHttpWebCamCallback implements Callback {

            final DomoSerializableWidget widget;
            final Request                reTryRequest;
            final int                    widgetWidth;

            public OkHttpWebCamCallback(DomoSerializableWidget widget, Request request) {
                this.widget       = widget;
                this.reTryRequest = request;
                AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(getApplicationContext());
                Bundle widgetOption = appWidgetManager.getAppWidgetOptions(widget.getDomoId());
                widgetWidth = widgetOption.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH);
            }

            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "onFailure : " + e);
                sendErrorToProvider(widget);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String fileName = getApplicationContext().getFilesDir().getAbsolutePath() + "/" + widget.getDomoId() + ".jpg" ;
                    File file = new File (fileName);
                    if (file.exists ()) {
                        file.delete ();
                    }
                    // Get picture from Webcam
                    ResponseBody in = response.body();
                    InputStream inputStream = in.byteStream();
                    BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream);
                    Bitmap bitmap = BitmapFactory.decodeStream(bufferedInputStream);
                    // Convert bitmap to file
                    OutputStream os = new BufferedOutputStream(new FileOutputStream(file));
                    DomoBitmapUtils domoBitmapUtils = new DomoBitmapUtils(getApplicationContext());
                    bitmap = domoBitmapUtils.addBorderToBitmap(bitmap,5, Color.WHITE);
                    Bitmap scaleBitmap = domoBitmapUtils.scaleDown(bitmap, widgetWidth, false);
                    scaleBitmap.compress(Bitmap.CompressFormat.JPEG,100, os);
                    bitmap.recycle();
                    scaleBitmap.recycle();
                    //Log.d(TAG, "Fichier : " + bitmap.getHeight() + " / " + bitmap.getWidth());
                    os.close();
                    Log.d(TAG, "Fichier enregistrée sous : " + fileName);
                    sendToProvider(DONE, widget);
                } catch (Exception e) {
                    Log.e(TAG, "Erreur : " + e);
                    sendTestResponseToProvider(false, e.getMessage());
                }
            }
        }

        // TimeOut
        Integer wifiTimeOut   = boxSetting.getBoxTimeOut() == 0 ? DomoConstants.WIFI_TIME_OUT : boxSetting.getBoxTimeOut();
        Integer mobileTimeOut = boxSetting.getBoxTimeOut() == 0 ? DomoConstants.MOBILE_TIME_OUT : boxSetting.getBoxTimeOut();
        Integer requestTimeOut;

        // Request Http
        Request request, reTryRequest;
        String url = widget.getDomoAction();

        // Création de la requete http suivant le type de connexion
        try {
            if (checkWifi()) {
                // Url Interne (en Wifi)
                // Log.d(TAG, "url interne (Wifi) => " + widget.getDomoAction());
                requestTimeOut = wifiTimeOut;
                try {
                    // URL - 1er tentative en interne
                    request = new Request.Builder().url(boxSetting.getBoxUrlInterne() + url).build();
                } catch (Exception e) {
                    // Utilisation url externe (si interne ko)
                    request = new Request.Builder().url(boxSetting.getBoxUrlExterne() + url).build();
                }

                try {
                    // URL - 2em Tentative en externe
                    reTryRequest = new Request.Builder().url(boxSetting.getBoxUrlExterne() + url).build();
                } catch (Exception e) {
                    reTryRequest = null;
                }

            } else {
                // Url Externe (mobile)
                // Log.d(TAG, "url Externe (Mobile) => " + widget.getDomoAction());
                requestTimeOut = mobileTimeOut;
                // URL - 1er tentative en externe
                request = new Request.Builder().url(boxSetting.getBoxUrlExterne() + url).build();
                try {
                    // 2em Tentative en interne
                    reTryRequest = new Request.Builder().url(boxSetting.getBoxUrlInterne() + url).build();
                } catch (Exception e) {
                    reTryRequest = null;
                }
            }
            final OkHttpClient client =  getOkHttpClient(requestTimeOut);
            client.newCall(request).enqueue(new OkHttpWebCamCallback(widget, reTryRequest));
        } catch (OutOfMemoryError outOfMemoryError) {
            Log.e(TAG, "Erreur Mémoire : " + outOfMemoryError.getMessage());
            sendErrorToProvider(widget);
        } catch (Exception e) {
            Log.e(TAG, "Erreur : " + e.getMessage());
            sendErrorToProvider(widget);
        }
    }

    /**
     * sendRequestGeoloToJeedom
     * @param boxSetting
     * @param widget
     */
    private void sendRequestGeoToJeedom(final BoxSetting boxSetting, final DomoSerializableWidget widget) {

        // TimeOut
        Integer wifiTimeOut   = boxSetting.getBoxTimeOut() == 0 ? DomoConstants.WIFI_TIME_OUT : boxSetting.getBoxTimeOut();
        Integer mobileTimeOut = boxSetting.getBoxTimeOut() == 0 ? DomoConstants.MOBILE_TIME_OUT : boxSetting.getBoxTimeOut();
        Integer requestTimeOut;

        // Request Http
        Request request, reTryRequest;

        String url =  widget.getDomoPluginKey().isEmpty() ?
                    JEEDOM_URL + boxSetting.getBoxKey() + "&" + widget.getDomoAction() :
                        widget.getDomoPluginURL() + "?apikey=" + widget.getDomoPluginKey() + "&" + widget.getDomoAction();
        // Création de la requete http suivant le type de connexion
        try {
            if (checkWifi()) {
                // Url Interne (en Wifi)
                Log.d(TAG, "url interne (Wifi) => " + widget.getDomoAction());
                requestTimeOut = wifiTimeOut;
                try {
                    // URL - 1er tentative en interne
                    request = new Request.Builder().url(boxSetting.getBoxUrlInterne() + url).build();
                } catch (Exception e) {
                    // Utilisation url externe (si interne ko)
                    request = new Request.Builder().url(boxSetting.getBoxUrlExterne() + url).build();
                }

                try {
                    // URL - 2em Tentative en externe
                    reTryRequest = new Request.Builder().url(boxSetting.getBoxUrlExterne() + url).build();
                } catch (Exception e) {
                    reTryRequest = null;
                }

            } else {
                // Url Externe (mobile)
                Log.d(TAG, "url Externe (Mobile) => " + widget.getDomoAction());
                requestTimeOut = mobileTimeOut;
                // URL - 1er tentative en externe
                request = new Request.Builder().url(boxSetting.getBoxUrlExterne() + url).build();
                try {
                    // 2em Tentative en interne
                    reTryRequest = new Request.Builder().url(boxSetting.getBoxUrlInterne() + url).build();
                } catch (Exception e) {
                    reTryRequest = null;
                }
            }
            final OkHttpClient client =  getOkHttpClient(requestTimeOut);
            client.newCall(request).enqueue(new OkHttpCallback(widget, reTryRequest));
        } catch (OutOfMemoryError outOfMemoryError) {
            Log.e(TAG, "Erreur Mémoire : " + outOfMemoryError.getMessage());
            sendErrorToProvider(widget);
        } catch (Exception e) {
            Log.e(TAG, "Erreur : " + e.getMessage());
            sendErrorToProvider(widget);
        }
    }

    /**
     * sendIntent
     * @param i
     */
    private void sendIntent(Intent i) {
        // Recherche des receivers
        PackageManager pm = getPackageManager();
        List<ResolveInfo> matches = pm.queryBroadcastReceivers(i, 0);
        //Log.d(TAG, "Nombre de broadcastReceivers trouvé : " + matches.size());
        if (matches.size() != 0) {
            for (ResolveInfo resolveInfo : matches) {
                Intent explicit = new Intent(i);
                ComponentName cn= new ComponentName(resolveInfo.activityInfo.applicationInfo.packageName, resolveInfo.activityInfo.name);
                //Log.d(TAG, "Envoie Intent => " + cn.getClassName());
                explicit.setComponent(cn);
                // Envoi Explicit
                sendBroadcast(explicit);
            }
        } else {
            // Envoi Implicit
            sendBroadcast(i);
        }
    }

    /**
     * sendToProvider
     * @param httpResponse
     * @param widget
     */
    private void sendToProvider(String httpResponse, DomoSerializableWidget widget) {
        Log.d(TAG, "sendToProvider : " + widget.getDomoType().getWidgetAction() + " - idWidget = " + widget.getDomoId());
        Intent broadcastIntent = new Intent();
        // Check reponse en erreur
        if (httpResponse.equals(ERROR)) {
            broadcastIntent.setAction(widget.getDomoType().getWidgetError());
        } else {
            broadcastIntent.setAction(widget.getDomoType().getWidgetAction());
        }
        broadcastIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widget.getDomoId());
        broadcastIntent.putExtra(WIDGET_VALUE, httpResponse);
        sendIntent(broadcastIntent);
    }

    /**
     * sendErrorToProvider
     * @param widget
     */
    private void sendErrorToProvider(DomoSerializableWidget widget) {
        Log.d(TAG, "sendErrorToProvider : " + widget.getDomoType().getWidgetError());
        Intent broadcastIntent = new Intent();
        broadcastIntent.setAction(widget.getDomoType().getWidgetError());
        broadcastIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widget.getDomoId());
        sendIntent(broadcastIntent);
    }

    /**
     * sendTestResponseToPriver
     * @param result
     */
    private void sendTestResponseToProvider(boolean result, String message) {
        Intent callBackIntent = new Intent();
        callBackIntent.setAction(PING_ACTION);
        callBackIntent.putExtra(BOX_PING, result);
        callBackIntent.putExtra(BOX_MESSAGE, message);
        sendIntent(callBackIntent);
        Log.d(TAG, "réponse de Jeedom au ping : " + result + " => " + message);
    }

    /**
     * checkWifi
     * @return
     */
    private boolean checkWifi() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        if (null != activeNetwork) {
            if (activeNetwork.getType() == ConnectivityManager.TYPE_WIFI) {
                return true;
            }

            if (activeNetwork.getType() == ConnectivityManager.TYPE_MOBILE) {
                return false;
            }
        }
        return false;
    }

    /**
     * Creation d'un client okHttp (with self secure HTTPS)
     * @param connectTimeout
     * @return
     */
    private OkHttpClient getOkHttpClient(int connectTimeout) {
        try {
            // Create a trust manager that does not validate certificate chains
            final TrustManager[] trustAllCerts = new TrustManager[] {
                    new X509TrustManager() {
                        @SuppressLint("TrustAllX509TrustManager")
                        @Override
                        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {
                        }

                        @SuppressLint("TrustAllX509TrustManager")
                        @Override
                        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {
                        }

                        @Override
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[]{};
                        }
                    }
            };

            // Install the all-trusting trust manager
            final SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            // Create an ssl socket factory with our all-trusting manager
            final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

            final OkHttpClient.Builder builder = new OkHttpClient.Builder();
            //noinspection deprecation
            builder.sslSocketFactory(sslSocketFactory);
            builder.hostnameVerifier(new HostnameVerifier() {
                @SuppressLint("BadHostnameVerifier")
                @Override
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            });
            builder.connectTimeout(connectTimeout, TimeUnit.SECONDS);
            builder.readTimeout(READ_TIME_OUT, TimeUnit.SECONDS);
            return builder.build();
        } catch (OutOfMemoryError outOfMemoryError) {
            Log.e(TAG, "Erreur Mémoire : " + outOfMemoryError.getMessage());
            return null;
        } catch (Exception e) {
            Log.e(TAG,"Erreur " + e);
            return null;
        }
    }
}
