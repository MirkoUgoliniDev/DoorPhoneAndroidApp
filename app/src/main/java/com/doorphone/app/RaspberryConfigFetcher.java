package com.doorphone.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.net.Uri;
import android.util.Log;

import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.AsyncHttpResponseHandler;

import org.json.JSONObject;

import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

import cz.msebera.android.httpclient.Header;
import com.doorphone.Settings;

/**
 * @brief Recupera la configurazione del Raspberry Pi all'avvio dell'app.
 *
 * Effettua una chiamata HTTP GET asincrona all'URL configurato in
 * {@link Settings#PREF_RASPBERRY_CONFIG_URL} e salva i parametri della
 * sezione {@code camera} nelle SharedPreferences, così che
 * {@link Settings#getCameraEndPoint()}, {@link Settings#getCameraUsername()}
 * e {@link Settings#getCameraPassword()} restituiscano i valori aggiornati.
 *
 * Struttura JSON attesa:
 * <pre>
 * {
 *   "kiosk": true,
 *   "hide_status_bar": true,
 *   "miclevel": 100,
 *   "speakerlevel": 100,
 *   "timezone":    "Europe/Rome",
 *   "server_time": 1778255304,
 *   "mumbleserver": {
 *     "server": "192.168.1.54",
 *     "port": "64738",
 *     "username": "Doorpi",
 *     "password": "..."
 *   },
 *   "doorpi": {
 *     "host": "192.168.1.54",
 *     "api_port": "8080",
 *     "unlock_command": "unlockdoor"
 *   },
 *   "apk": {
 *     "files": ["doorphone.apk"],
 *     "download_url": "http://192.168.1.54:8080/apk/"
 *   },
 *   "camera": {
 *     "video":    { "enabled": true, "endpoint": "rtsp://..." },
 *     "snapshot": { "enabled": true, "endpoint": "http://.../snap" },
 *     "username": "...",
 *     "password": "..."
 *   }
 * }
 * </pre>
 * {@code server_time} (opzionale): epoch Unix come intero ({@code int(time.time())} in Python).
 * Formato legacy accettato: stringa ISO 8601 UTC {@code "YYYY-MM-DDThh:mm:ss"}.
 * Applicato via {@code su -c date @<epoch>} (dispositivo rootato).
 * La sezione {@code apk} e' presente nel JSON ma viene <b>ignorata</b> da questa app:
 * e' consumata da un'app di aggiornamento separata sullo stesso device.
 * {@code miclevel} (opzionale, 0–200): amplificazione microfono in percentuale
 * (100 = nessun boost, 200 = 2×). Corrisponde alla preferenza {@code inputVolume}.
 * {@code speakerlevel} (opzionale, 0–100): volume altoparlante in percentuale.
 * Applicato su {@code STREAM_MUSIC} e {@code STREAM_VOICE_CALL}.
 * Il campo kiosk e' opzionale: se manca, l'app mantiene il default false per
 * permettere manutenzione del tablet. Per compatibilita' viene accettato anche
 * il nome {@code kiosk_mode}.
 * Il campo {@code hide_status_bar} e' opzionale: se manca, la barra superiore
 * resta visibile.
 * La sezione {@code mumbleserver} sostituisce le vecchie preferenze manuali
 * host/porta/password Mumble. Per le API HTTP DoorPi, se il JSON non contiene
 * una sezione {@code doorpi}, vengono usati host e porta dell'URL config.
 *
 * Viene usato solo {@code camera.video}: se {@code enabled} è true e {@code endpoint}
 * non è vuoto, l'URL RTSP viene salvato nelle preferenze. La sezione {@code snapshot}
 * è ignorata.
 *
 * Se il fetch fallisce (rete non disponibile, timeout, JSON malformato)
 * le preferenze non vengono modificate e l'app usa i valori cached.
 */
public class RaspberryConfigFetcher {

    private static final String TAG = "RaspberryConfigFetcher";

    /**
     * @brief I3: l'orologio ({@code server_time}) viene sincronizzato una sola volta
     * per avvio del processo. {@code fetch()} gira a ogni ritorno in foreground e
     * riscrivere l'ora ogni volta poteva farla "saltare" durante una chiamata. Il flag
     * si resetta da solo al riavvio del processo (cioe' una nuova sync per boot).
     *
     * @note Volume e luminosita' restano invece forzati a ogni fetch by design (lockdown
     * kiosk: l'utente non deve poterli cambiare in modo permanente).
     */
    private static final AtomicBoolean sSystemTimeApplied = new AtomicBoolean(false);

    /**
     * @brief I3: chiave interna (non una preferenza utente) per ricordare l'ultimo fuso
     * applicato, cosi' da NON rieseguire {@code su}/ribroadcast {@code TIMEZONE_CHANGED}
     * a ogni foreground ma solo quando il fuso dal server cambia davvero.
     */
    private static final String KEY_LAST_TIMEZONE = "_cfg_last_timezone";


    /**
     * @brief Avvia il fetch della configurazione in background.
     *
     * @param configUrl  URL dell'endpoint di configurazione del Raspberry Pi.
     * @param prefs      SharedPreferences in cui salvare i valori ricevuti.
     * @param context    Context dell'applicazione (necessario per AudioManager).
     */
    public static void fetch(String configUrl, SharedPreferences prefs, Context context) {
        fetch(configUrl, prefs, context, null);
    }

    /**
     * @brief Overload con callback opzionale: {@code onComplete} viene eseguito sul main thread
     *        al termine del fetch (successo o fallimento). Usato da {@link com.doorphone.app.DoorPhoneActivity}
     *        dopo la selezione del piano per concatenare fetch → Connect().
     *
     * @param onComplete Runnable eseguito sul main thread a fetch terminato (può essere null).
     */
    public static void fetch(String configUrl, SharedPreferences prefs, Context context, Runnable onComplete) {
        if (configUrl == null || configUrl.trim().isEmpty()) {
            Log.w(TAG, "Config fetch skipped: empty URL");
            if (onComplete != null) new Handler(Looper.getMainLooper()).post(onComplete);
            return;
        }
        Log.d(TAG, "┌─ CONFIG FETCH ──────────────────────────────");
        Log.d(TAG, "│  URL: " + configUrl);
        Log.d(TAG, "└─────────────────────────────────────────────");

        AsyncHttpClient client = new AsyncHttpClient();
        client.setConnectTimeout(10000);
        client.setResponseTimeout(10000);

        client.get(configUrl, null, new AsyncHttpResponseHandler() {

            @Override
            public void onSuccess(int statusCode, Header[] headers, byte[] responseBody) {
                try {
                    String json = new String(responseBody);
                    Log.d(TAG, "┌─ CONFIG RESPONSE ───────────────────────────");


                    JSONObject root = new JSONObject(json);
                    Log.d(TAG, "│  CAMPI   : " + root.names());
                    Log.d(TAG, "├─ PARSING ───────────────────────────────────");

                    boolean kioskMode = root.has("kiosk")
                            ? root.optBoolean("kiosk", Settings.DEFAULT_PREF_KIOSK_MODE)
                            : root.optBoolean("kiosk_mode", Settings.DEFAULT_PREF_KIOSK_MODE);
                    boolean hideStatusBar = root.optBoolean(
                            "hide_status_bar",
                            Settings.DEFAULT_PREF_HIDE_STATUS_BAR);
                    /*
                     * Il flag kiosk governa la manutenzione del tablet e non deve dipendere
                     * dalla validita' della sezione camera. Lo salviamo subito: se poi l'endpoint
                     * camera e' assente o disabilitato, la modalita' kiosk resta comunque aggiornata.
                     * Anche hide_status_bar viene salvato subito: controlla solo la UI di sistema
                     * e non deve dipendere dalla validita' della configurazione camera.
                     */
                    prefs.edit()
                            .putBoolean(Settings.PREF_KIOSK_MODE, kioskMode)
                            .putBoolean(Settings.PREF_HIDE_STATUS_BAR, hideStatusBar)
                            .apply();
                    Log.d(TAG, "│  kiosk_mode     = " + kioskMode);
                    Log.d(TAG, "│  hide_status_bar= " + hideStatusBar);

                    // miclevel: amplificazione microfono (0-200, 100 = nessun boost)
                    if (root.has("miclevel")) {
                        int micLevel = root.getInt("miclevel");
                        prefs.edit().putInt(Settings.PREF_AMPLITUDE_BOOST, micLevel).apply();
                        Log.d(TAG, "│  miclevel       = " + micLevel);
                    } else {
                        Log.d(TAG, "│  miclevel       = <assente, invariato>");
                    }

                    // speakerlevel: volume altoparlante (0-100%)
                    if (root.has("speakerlevel")) {
                        int speakerPct = root.getInt("speakerlevel");
                        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                        if (am != null) {
                            int[] streams = {AudioManager.STREAM_MUSIC, AudioManager.STREAM_VOICE_CALL};
                            for (int stream : streams) {
                                int maxVol = am.getStreamMaxVolume(stream);
                                int targetVol = (int) Math.round(speakerPct / 100.0 * maxVol);
                                am.setStreamVolume(stream, targetVol, 0);
                                Log.d(TAG, "│  speakerlevel   stream=" + stream
                                        + " target=" + targetVol + "/" + maxVol);
                            }
                        }
                        Log.d(TAG, "│  speakerlevel   = " + speakerPct + "%");
                    } else {
                        Log.d(TAG, "│  speakerlevel   = <assente, invariato>");
                    }

                    final boolean hasBrightness = root.has("screenbrightnesslevel");
                    final int brightnessValue;
                    if (hasBrightness) {
                        int pct = Math.max(0, Math.min(100, root.optInt("screenbrightnesslevel", -1)));
                        brightnessValue = (int) Math.round(pct / 100.0 * 255);
                        Log.d(TAG, "│  brightness     = " + pct + "% → " + brightnessValue + "/255");
                    } else {
                        brightnessValue = -1;
                        Log.d(TAG, "│  brightness     = <assente, invariato>");
                    }

                    // timezone + server_time: eseguiti in background per non bloccare il main thread.
                    // Il fuso va impostato PRIMA dell'ora (timezone → server_time).
                    // I3: fetch() gira a OGNI foreground. Per evitare di riapplicare ora e fuso
                    // ogni volta (l'orologio poteva "saltare" durante una chiamata):
                    //  - timezone  → applicato solo se cambiato rispetto all'ultimo applicato
                    //  - server_time → sincronizzato una sola volta per avvio del processo
                    final String tzValue = root.optString("timezone", "").trim();
                    final boolean tzChanged = !tzValue.isEmpty()
                            && !tzValue.equals(prefs.getString(KEY_LAST_TIMEZONE, ""));
                    final boolean hasTime = root.has("server_time");
                    final boolean applyTime = hasTime && sSystemTimeApplied.compareAndSet(false, true);
                    final long epochSeconds;
                    if (applyTime) {
                        long parsed = -1;
                        try { parsed = parseServerTime(root); } catch (Exception ignored) {}
                        epochSeconds = parsed;
                    } else {
                        epochSeconds = -1;
                    }

                    new Thread(() -> {
                        if (tzChanged) {
                            try {
                                boolean ok = applyTimezone(tzValue);
                                Log.d(TAG, "│  timezone       = " + tzValue + (ok ? " OK" : " TIMEOUT"));
                                // Memorizza il fuso applicato solo se il comando e' andato a buon
                                // fine, cosi' un timeout verra' ritentato al prossimo foreground.
                                if (ok) prefs.edit().putString(KEY_LAST_TIMEZONE, tzValue).apply();
                            } catch (Exception e) {
                                Log.w(TAG, "│  timezone       = ERRORE: " + e.getMessage());
                            }
                        } else if (tzValue.isEmpty()) {
                            Log.d(TAG, "│  timezone       = <assente, invariato>");
                        } else {
                            Log.d(TAG, "│  timezone       = <invariato dal server, skip>");
                        }

                        if (epochSeconds > 0) {
                            try {
                                boolean ok = applySystemTime(epochSeconds);
                                if (ok) Log.d(TAG, "│  server_time    = epoch=" + epochSeconds + " OK");
                            } catch (Exception e) {
                                Log.w(TAG, "│  server_time    = ERRORE: " + e.getMessage());
                            }
                        } else if (!hasTime) {
                            Log.d(TAG, "│  server_time    = <assente, invariato>");
                        } else if (!applyTime) {
                            Log.d(TAG, "│  server_time    = <gia' sincronizzato a questo avvio, skip>");
                        }

                        if (brightnessValue >= 0) {
                            try {
                                boolean ok = applyScreenBrightness(brightnessValue);
                                Log.d(TAG, "│  brightness     = " + brightnessValue + (ok ? " OK" : " TIMEOUT"));
                            } catch (Exception e) {
                                Log.w(TAG, "│  brightness     = ERRORE: " + e.getMessage());
                            }
                        }
                    }, "TimeSync").start();

                    JSONObject doorpi = root.optJSONObject("doorpi");
                    JSONObject mumble = root.optJSONObject("mumble");
                    JSONObject mumbleServer = root.optJSONObject("mumbleserver");
                    Uri configUri = Uri.parse(configUrl);
                    String configHost = configUri != null ? configUri.getHost() : "";
                    String configPort = configUri != null && configUri.getPort() > 0
                            ? String.valueOf(configUri.getPort())
                            : "";

                    String doorpiHost = firstNonEmpty(
                            optString(doorpi, "host"),
                            optString(doorpi, "ip"),
                            root.optString("doorpi_host", ""),
                            root.optString("doorpi_ip", ""),
                            root.optString("host", ""),
                            configHost);
                    String doorpiApiPort = firstNonEmpty(
                            optString(doorpi, "api_port"),
                            optString(doorpi, "port"),
                            root.optString("doorpi_api_port", ""),
                            root.optString("api_port", ""),
                            configPort);
                    String unlockCommand = firstNonEmpty(
                            optString(doorpi, "unlock_command"),
                            optString(doorpi, "cmd_unlock"),
                            root.optString("unlock_command", ""),
                            root.optString("cmd_unlock", ""));
                    String mumbleHost = firstNonEmpty(
                            optString(mumbleServer, "server"),
                            optString(mumbleServer, "host"),
                            optString(mumbleServer, "ip"),
                            optString(mumble, "host"),
                            optString(mumble, "ip"),
                            root.optString("mumble_host", ""),
                            root.optString("mumble_ip", ""),
                            doorpiHost);
                    String mumblePort = firstNonEmpty(
                            optString(mumbleServer, "port"),
                            optString(mumble, "port"),
                            root.optString("mumble_port", ""));
                    String mumblePassword = firstNonEmpty(
                            optString(mumbleServer, "password"),
                            optString(mumble, "password"),
                            root.optString("mumble_password", ""));

                    /*
                     * Questi valori sono configurazione operativa del device: non sono piu'
                     * editabili a mano nel menu, ma restano salvati in SharedPreferences come
                     * cache per i riavvii o per i boot in cui il Wi-Fi arriva in ritardo.
                     * Aggiorniamo solo i campi presenti/non vuoti per non cancellare l'ultima
                     * configurazione valida con una risposta parziale.
                     */
                    SharedPreferences.Editor runtimeEditor = prefs.edit();
                    putIfNotEmpty(runtimeEditor, Settings.PREF_DOORPI_IP, doorpiHost);
                    putIfNotEmpty(runtimeEditor, Settings.PREF_DOORPI_HOST, mumbleHost);
                    putIfNotEmpty(runtimeEditor, Settings.PREF_DOORPI_NAME, mumbleHost);
                    putIfNotEmpty(runtimeEditor, Settings.PREF_DOORPI_API_PORT, doorpiApiPort);
                    putIfNotEmpty(runtimeEditor, Settings.PREF_DOORPI_PORT, mumblePort);
                    putIfNotEmpty(runtimeEditor, Settings.PREF_DOORPI_PASSWORD, mumblePassword);
                    putIfNotEmpty(runtimeEditor, Settings.PREF_CMD_UNLOCK, unlockCommand);
                    runtimeEditor.apply();

                    Log.d(TAG, "│  doorpi host    = " + (doorpiHost.isEmpty() ? "<cached>" : doorpiHost));
                    Log.d(TAG, "│  doorpi api port= " + (doorpiApiPort.isEmpty() ? "<cached>" : doorpiApiPort));
                    Log.d(TAG, "│  unlock command = " + (unlockCommand.isEmpty() ? "<cached>" : unlockCommand));
                    Log.d(TAG, "│  mumble host    = " + (mumbleHost.isEmpty() ? "<cached>" : mumbleHost));
                    Log.d(TAG, "│  mumble port    = " + (mumblePort.isEmpty() ? "<cached>" : mumblePort));
                    Log.d(TAG, "│  mumble password= " + (mumblePassword.isEmpty() ? "<cached/empty>" : "***" + mumblePassword.length() + " chars***"));

                    JSONObject camera = root.optJSONObject("camera");
                    if (camera == null) {
                        Log.w(TAG, "│  camera         = SEZIONE ASSENTE - preferenze non aggiornate");
                        Log.d(TAG, "└─────────────────────────────────────────────");
                        return;
                    }

                    JSONObject video = camera.optJSONObject("video");

                    boolean videoEnabled = video != null && video.optBoolean("enabled", false);
                    String endpoint = video != null ? video.optString("endpoint", "") : "";

                    Log.d(TAG, "│  video.enabled  = " + videoEnabled);

                    if (!videoEnabled || endpoint.isEmpty()) {
                        Log.w(TAG, "│  video camera non abilitata o endpoint assente: preferenze non aggiornate");
                        return;
                    }

                    String username = camera.optString("username", "");
                    String password = camera.optString("password", "");

                    Log.d(TAG, "│  camera endpoint= " + endpoint);
                    Log.d(TAG, "│  camera username= " + username);
                    Log.d(TAG, "│  camera password= " + (password.isEmpty() ? "<empty>" : "***" + password.length() + " chars***"));

                    // Determina il tipo di stream dall'URL (rtsp:// → "rtsp", altrimenti "mjpeg")
                    String format = endpoint.toLowerCase(Locale.US).startsWith("rtsp") ? "rtsp" : "mjpeg";
                    Log.d(TAG, "│  camera format  = " + format);

                    prefs.edit()
                            .putString(Settings.PREF_CAMERA_ENDPOINT, endpoint)
                            .putString(Settings.PREF_CAMERA_USERNAME, username)
                            .putString(Settings.PREF_CAMERA_PASSWORD, password)
                            .putString(Settings.PREF_DOORPI_CAMERA_FORMAT, format)
                            .putBoolean(Settings.PREF_KIOSK_MODE, kioskMode)
                            .putBoolean(Settings.PREF_HIDE_STATUS_BAR, hideStatusBar)
                            .apply();

                    Log.d(TAG, "└─ CONFIG OK ─────────────────────────────────");

                } catch (Exception e) {
                    Log.e(TAG, "└─ CONFIG PARSE ERROR: " + e.getMessage());
                } finally {
                    if (onComplete != null) new Handler(Looper.getMainLooper()).post(onComplete);
                }
            }

            @Override
            public void onFailure(int statusCode, Header[] headers, byte[] responseBody, Throwable error) {
                String message = error != null ? error.getMessage() : "unknown error";
                Log.w(TAG, "└─ CONFIG FETCH FALLITO (status=" + statusCode + "): " + message);
                // Nessuna modifica alle preferenze: l'app usa i valori cached dall'avvio precedente.
                if (onComplete != null) new Handler(Looper.getMainLooper()).post(onComplete);
            }
        });
    }

    private static String optString(JSONObject object, String key) {
        return object != null ? object.optString(key, "") : "";
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && value.trim().length() > 0) {
                return value.trim();
            }
        }
        return "";
    }

    private static void putIfNotEmpty(SharedPreferences.Editor editor, String key, String value) {
        if (value != null && value.trim().length() > 0) {
            editor.putString(key, value.trim());
        }
    }

    /**
     * @brief Converte il campo {@code server_time} del JSON in Unix epoch seconds.
     *
     * Supporta due formati:
     * <ul>
     *   <li><b>Intero</b>: epoch seconds direttamente (es. {@code 1746692667}).
     *       Generato in Python con {@code int(time.time())}. Nessuna ambiguita' di fuso.</li>
     *   <li><b>Stringa ISO 8601</b> (legacy): {@code "YYYY-MM-DDThh:mm:ss"} interpretata
     *       come UTC. Opzionale suffisso {@code Z} accettato e ignorato.</li>
     * </ul>
     *
     * @param root JSON root dell'endpoint config.
     * @return epoch seconds, oppure -1 se il formato non e' riconosciuto.
     */
    private static long parseServerTime(JSONObject root) throws Exception {
        Object raw = root.get("server_time");

        // Formato preferito: intero epoch (niente fuso orario da gestire)
        if (raw instanceof Number) {
            long v = ((Number) raw).longValue();
            Log.d(TAG, "│  server_time    RAW (epoch int) = " + v);
            return v;
        }

        // Formato legacy: stringa ISO 8601
        String s = raw.toString().trim();
        Log.d(TAG, "│  server_time    RAW (string) = " + s);

        // Rimuovi eventuale suffisso Z o +00:00
        if (s.endsWith("Z")) s = s.substring(0, s.length() - 1);
        if (s.length() > 19) s = s.substring(0, 19);  // tronca microsecondi / offset

        if (!s.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}")) {
            Log.w(TAG, "│  server_time    = FORMATO NON VALIDO: " + raw);
            return -1;
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.parse(s).getTime() / 1000;
    }

    /**
     * @brief Imposta l'orologio di sistema tramite {@code su -c date @<epoch>}.
     *
     * Richiede dispositivo rootato. Usa un thread ausiliario con join(3000 ms)
     * per compatibilita' API 24/25 ({@code Process.waitFor(long, TimeUnit)} e' API 26+).
     *
     * @param epochSeconds Unix epoch seconds da impostare.
     */
    private static boolean applySystemTime(long epochSeconds) throws IOException, InterruptedException {
        Process proc = Runtime.getRuntime().exec(
                new String[]{"su", "-c", "date @" + epochSeconds});
        Thread waiter = new Thread(() -> {
            try { proc.waitFor(); } catch (InterruptedException ignored) {}
        });
        waiter.start();
        waiter.join(3000);
        if (waiter.isAlive()) {
            waiter.interrupt();
            proc.destroy();
            Log.w(TAG, "│  server_time    = TIMEOUT (>3s) - orologio NON aggiornato");
            return false;
        }
        return true;
    }

    /**
     * @brief Imposta il fuso orario di sistema via {@code su}.
     *
     * Esegue due comandi in sequenza nella stessa shell root:
     * <ol>
     *   <li>{@code settings put global time_zone <tz>} — salva il fuso in modo persistente</li>
     *   <li>{@code am broadcast -a android.intent.action.TIMEZONE_CHANGED} — notifica subito
     *       tutto il sistema senza aspettare il riavvio</li>
     * </ol>
     *
     * @param timezone Identificativo IANA (es. {@code "Europe/Rome"}).
     * @return {@code true} se i comandi hanno completato entro 5 s, {@code false} se timeout.
     */
    private static boolean applyTimezone(String timezone) throws IOException, InterruptedException {
        String cmd = "settings put global time_zone " + timezone
                + " && am broadcast -a android.intent.action.TIMEZONE_CHANGED --es time-zone " + timezone;
        Process proc = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
        Thread waiter = new Thread(() -> {
            try { proc.waitFor(); } catch (InterruptedException ignored) {}
        });
        waiter.start();
        waiter.join(5000);
        if (waiter.isAlive()) {
            waiter.interrupt();
            proc.destroy();
            return false;
        }
        return true;
    }

    /**
     * Applica la luminosità dello schermo via {@code su}.
     *
     * @param value valore Android 0-255 (0 = minimo, 255 = massimo)
     * @return {@code true} se il comando termina entro 3 s, {@code false} se va in timeout
     */
    private static boolean applyScreenBrightness(int value) throws IOException, InterruptedException {
        String cmd = "settings put system screen_brightness_mode 0"
                + " && settings put system screen_brightness " + value;
        Process proc = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
        Thread waiter = new Thread(() -> {
            try { proc.waitFor(); } catch (InterruptedException ignored) {}
        });
        waiter.start();
        waiter.join(3000);
        if (waiter.isAlive()) {
            waiter.interrupt();
            proc.destroy();
            return false;
        }
        return true;
    }
}
