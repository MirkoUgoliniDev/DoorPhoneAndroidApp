package com.doorphone.app;

/**
 * @see <a href="https://medium.com/@iamsadesh/android-how-to-detect-when-app-goes-background-foreground-fd5a4d331f8a">Rilevare foreground/background</a>
 */

import android.app.Activity;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import java.util.Locale;

import androidx.preference.PreferenceManager;
import com.doorphone.Settings;

/**
 * @brief Classe Application principale dell'app MumlaO.
 *
 * Gestisce il ciclo di vita delle Activity per rilevare quando l'app
 * entra in foreground o background, e crea il canale di notifica
 * necessario per il foreground service.
 */
public class MyApp extends Application implements Application.ActivityLifecycleCallbacks {

    /** @brief ID interno del canale di notifica, usato da {@link com.doorphone.service.MumlaService}. */
    public static final String CHANNEL_ID = "autoStartServiceChannel";

    /**
     * @brief Nome visibile del canale di notifica nelle impostazioni di sistema Android.
     * Appare in Impostazioni → App → MumlaO → Notifiche.
     */
    public static final String CHANNEL_NAME = "MumlaO — Servizio Mumble";

    private int activityReferences = 0;
    private boolean isActivityChangingConfigurations = false;
    private static boolean isForeground = false;

    private static final String TAG = "MyApp";


    /** @brief Inizializza l'applicazione: registra i lifecycle callbacks, crea il canale notifiche e
     *          recupera la configurazione dal Raspberry Pi. */
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "ON CREATE");
        registerActivityLifecycleCallbacks(this);
        createNotificationChannel();
    }


    /**
     * @brief Avvia il fetch asincrono della configurazione dal Raspberry Pi.
     *
     * Legge l'URL di configurazione dalle preferenze e delega a
     * {@link RaspberryConfigFetcher#fetch(String, SharedPreferences)}.
     * I parametri video (endpoint, username, password) vengono salvati
     * nelle SharedPreferences appena la risposta arriva.
     */
    private void fetchRaspberryConfig() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String piano = prefs.getString(Settings.PREF_DOORPI_PIANO, Settings.DEFAULT_PREF_DOORPI_PIANO);

        if (piano == null || piano.trim().isEmpty()) {
            Log.w(TAG, "CONFIG FETCH SALTATO: piano non configurato. "
                    + "Vai in Impostazioni -> DoorPi e imposta il valore Piano.");
            return;
        }

        String configUrl = prefs.getString(
                Settings.PREF_RASPBERRY_CONFIG_URL,
                Settings.DEFAULT_PREF_RASPBERRY_CONFIG_URL);
        String finalUrl = buildPersonalizedConfigUrl(configUrl, piano);
        Log.d(TAG, "CONFIG FETCH -> piano='" + piano + "' url=" + finalUrl);
        RaspberryConfigFetcher.fetch(finalUrl, prefs, this);
    }

    /**
     * @brief Costruisce l'URL config Raspberry includendo l'identificativo del tablet.
     *
     * Il Raspberry usa il parametro {@code p} per restituire una configurazione
     * personalizzata per piano/tablet. Il valore viene inviato in minuscolo
     * (es. P4 -&gt; p4). Per compatibilita' con tablet gia' configurati, anche i vecchi
     * valori PIANO1/PIANO2 vengono normalizzati in p1/p2.
     * Se il piano non e' ancora configurato (stringa vuota), il parametro {@code p}
     * NON viene aggiunto: il server riceve la richiesta base senza identificativo.
     *
     * @param configUrl URL base configurato nelle preferenze (es. /config/).
     * @param piano Identificativo configurato nelle preferenze (vuoto = non configurato).
     * @return URL finale, con query parameter {@code p} solo se il piano e' configurato.
     */
    private String buildPersonalizedConfigUrl(String configUrl, String piano) {
        String pianoParam = normalizePianoForConfig(piano);
        String safeConfigUrl = configUrl;
        if (safeConfigUrl == null || safeConfigUrl.trim().length() == 0) {
            /*
             * Stabilita': SharedPreferences normalmente restituisce il default, ma se
             * l'URL venisse salvato vuoto da una manutenzione manuale evitiamo una
             * richiesta malformata che bloccherebbe il fetch della configurazione.
             */
            safeConfigUrl = Settings.DEFAULT_PREF_RASPBERRY_CONFIG_URL;
        }
        Uri baseUri = Uri.parse(safeConfigUrl.trim());
        Uri.Builder builder = baseUri.buildUpon().clearQuery();

        /*
         * Se l'URL configurato contiene gia' parametri query li preserviamo, ma
         * sostituiamo sempre l'eventuale parametro "p": l'identita' del tablet deve
         * essere una sola e deve venire dal valore locale P1/P2 normalizzato.
         */
        for (String queryName : baseUri.getQueryParameterNames()) {
            if ("p".equals(queryName)) continue;
            for (String queryValue : baseUri.getQueryParameters(queryName)) {
                builder.appendQueryParameter(queryName, queryValue);
            }
        }
        if (!pianoParam.isEmpty()) {
            builder.appendQueryParameter("p", pianoParam);
        }
        return builder.build().toString();
    }

    /**
     * @brief Normalizza l'identificativo piano per la chiamata al Raspberry.
     *
     * Esempi:
     * - "P1" -> "p1"
     * - "PIANO1" -> "p1"
     * - valore vuoto/null -> default "p1"
     *
     * @param piano Valore letto dalle preferenze.
     * @return Identificativo in minuscolo adatto al parametro query {@code p}.
     */
    private String normalizePianoForConfig(String piano) {
        String value = piano != null ? piano.trim().toLowerCase(Locale.US) : "";
        if (value.length() == 0) {
            return "";  // piano non ancora configurato: non aggiungere ?p= all'URL
        }
        if (value.startsWith("piano")) {
            value = "p" + value.substring("piano".length());
        }
        return value;
    }


    /**
     * @brief Crea il canale di notifica richiesto da Android 8.0+ per i foreground service.
     *
     * Necessario per {@link com.doorphone.service.MumlaService}.
     * Non fa nulla su versioni precedenti ad Android O.
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }


    /** @brief Traccia il numero di Activity avviate per rilevare il passaggio in foreground. */
    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}

    @Override
    public void onActivityStarted(Activity activity) {
        if (++activityReferences == 1 && !isActivityChangingConfigurations) {
            isForeground = true;
            Log.d(TAG, "APP ENTER FOREGROUND");
            fetchRaspberryConfig();
        }
    }

    @Override
    public void onActivityResumed(Activity activity) {}

    @Override
    public void onActivityPaused(Activity activity) {}

    /** @brief Traccia il numero di Activity fermate per rilevare il passaggio in background. */
    @Override
    public void onActivityStopped(Activity activity) {
        isActivityChangingConfigurations = activity.isChangingConfigurations();
        if (--activityReferences == 0 && !isActivityChangingConfigurations) {
            Log.d(TAG, "APP ENTER BACKGROUND");
            isForeground = false;
        }
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

    @Override
    public void onActivityDestroyed(Activity activity) {}


    /**
     * @brief Avvia un'app esterna tramite il suo package name.
     *
     * Usato per avviare l'app citofono associata (package: com.mumla.test).
     * Non fa nulla se il package non è installato sul dispositivo.
     */
    protected void launchApp() {
        Log.d(TAG, "------ TRY TO START ------");
        String DoorPhonePackage = "com.mumla.test";
        try {
            PackageManager pm = getApplicationContext().getPackageManager();
            Intent intent = pm.getLaunchIntentForPackage(DoorPhonePackage);
            intent.setAction(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Log.d(TAG, "------DOORPI STARTED------");
        } catch (Exception ex) {
            Log.e(TAG, ex.getMessage() + " Line number: " + ex.getStackTrace()[0].getLineNumber());
        }
    }


    /**
     * @brief Indica se l'app è attualmente in foreground.
     * @return {@code true} se almeno una Activity è avviata e visibile.
     */
    public static boolean APPisForeground() {
        return isForeground;
    }

}
