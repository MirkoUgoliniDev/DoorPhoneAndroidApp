package com.doorphone.util;

/**
 * @see <a href="http://loopj.com/android-async-http/">android-async-http</a>
 * @see <a href="https://guides.codepath.com/android/Using-Android-Async-Http-Client">Guida AsyncHttpClient</a>
 */

import android.util.Log;
import cz.msebera.android.httpclient.Header;
import com.doorphone.Settings;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import com.doorphone.callbacks.PostDataCallback;
import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.AsyncHttpResponseHandler;

/**
 * @brief Classe di utilità per l'invio di comandi HTTP all'API REST di DoorPi.
 *
 * Tutti i comandi vengono inviati tramite HTTP GET verso l'endpoint
 * configurato in {@link Settings} (host + porta API DoorPi).
 * Le risposte sono JSON con lo stato dei dispositivi controllati.
 *
 * Esempio di URL generato:
 * {@code http://192.168.1.54:8080/?command=unlockdoor}
 */
public class CommandSubmitter {

    private static final String TAG = CommandSubmitter.class.getSimpleName();


    /**
     * @brief Invia un comando HTTP GET a DoorPi in modo asincrono.
     *
     * La chiamata non blocca il thread UI. Il risultato viene notificato
     * tramite il {@link PostDataCallback} fornito.
     * Timeout di connessione e risposta: 30 secondi.
     *
     * @param mSettings Istanza delle impostazioni per ricavare host e porta DoorPi.
     * @param command   Stringa del comando da inviare (es. "unlockdoor", "light_on").
     * @param callback  Callback notificato su {@code onDataReceived()} o {@code onError()}.
     */
    public static void postDataAsync(Settings mSettings, String command, PostDataCallback callback) {

        if (callback == null) {
            Log.w(TAG, "postDataAsync called with null callback");
            return;
        }

        String commandUrl = getCompleteCommandUrl(mSettings, command);
        if (commandUrl.isEmpty()) {
            Log.e(TAG, "postDataAsync: URL vuoto, comando annullato");
            callback.onError("Configurazione DoorPi non disponibile");
            return;
        }
        Log.d(TAG, "commandUrl: " + commandUrl);

        AsyncHttpClient client = new AsyncHttpClient();
        client.setConnectTimeout(30000);
        client.setResponseTimeout(30000);

        // Header che simulano una richiesta browser per compatibilità con DoorPi
        client.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
        client.addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
        client.addHeader("Accept-Language", "en-US,en;q=0.5");
        client.addHeader("Accept-Encoding", "gzip, deflate, br");
        client.addHeader("Connection", "keep-alive");
        client.addHeader("Upgrade-Insecure-Requests", "1");
        client.addHeader("Cache-Control", "max-age=0");

        client.get(commandUrl, null, new AsyncHttpResponseHandler() {

            @Override
            public void onStart() {}

            @Override
            public void onSuccess(int statusCode, Header[] headers, byte[] responseBody) {
                String json = responseBody != null ? new String(responseBody) : "";
                Log.d(TAG, "postDataAsync result: " + json);
                callback.onDataReceived(json);
            }

            @Override
            public void onFailure(int statusCode, Header[] headers, byte[] responseBody, Throwable error) {
                String errorMessage;
                if (error instanceof UnknownHostException) {
                    errorMessage = "Unable to reach the server. Please check your internet connection.";
                } else if (error instanceof SocketTimeoutException) {
                    errorMessage = "The request timed out. Please try again.";
                } else {
                    String message = error != null && error.getMessage() != null
                            ? error.getMessage()
                            : "unknown error";
                    errorMessage = "An error occurred: " + message;
                }

                StringWriter sw = new StringWriter();
                if (error != null) {
                    error.printStackTrace(new PrintWriter(sw));
                } else {
                    sw.write("unknown error");
                }
                Log.e(TAG, "Request failed. Status code: " + statusCode + ". Error: " + sw.toString());

                callback.onError(errorMessage);
            }
        });
    }


    /**
     * @brief Costruisce l'URL completo per un comando DoorPi.
     *
     * Formato: {@code http://<host>:<apiPort>/?command=<command>}
     *
     * @param mSettings Istanza delle impostazioni contenente host e porta API.
     * @param command   Stringa del comando da accodare all'URL.
     * @return URL completo come stringa.
     */
    public static String getCompleteCommandUrl(Settings mSettings, String command) {
        if (mSettings == null) {
            Log.e(TAG, "getCompleteCommandUrl: mSettings null");
            return "";
        }
        String doorpi_host = mSettings.getDoorPiIP();
        String doorpi_port = mSettings.getDoorPiAPIPort();
        if (doorpi_host == null) doorpi_host = "";
        if (doorpi_port == null) doorpi_port = "";
        return "http://" + doorpi_host + ":" + doorpi_port + "/?command=" + command;
    }

}
