package com.doorphone;


import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Gravity;
import java.util.HashSet;
import java.util.Set;
import se.lublin.humla.Constants;





/**
 * Singleton settings class for universal access to the app's preferences.
 * @author morlunk
 */
@SuppressWarnings("deprecation")
public class Settings {

    private static final String TAG = Settings.class.getSimpleName();


    public static final String PREF_INPUT_METHOD = "audioInputMethod";
    public static final Set<String> ARRAY_INPUT_METHODS;
    /** Voice activity transmits depending on the amplitude of user input. */
    public static final String ARRAY_INPUT_METHOD_VOICE = "voiceActivity";
    /** Push to talk transmits on command. */
    public static final String ARRAY_INPUT_METHOD_PTT = "ptt";
    /** Continuous transmits always. */
    public static final String ARRAY_INPUT_METHOD_CONTINUOUS = "continuous";

    // NOTE: When changing DEFAULTs, the default value in the corresponding
    // widget in settings_PAGE.xml must also be changed. It doesn't pick this
    // up itself...

    public static final String PREF_THRESHOLD = "vadThreshold";
    public static final int DEFAULT_THRESHOLD = 50;

    public static final String PREF_PUSH_KEY = "talkKey";
    public static final Integer DEFAULT_PUSH_KEY = -1;

    public static final String PREF_HOT_CORNER_KEY = "hotCorner";
    public static final String ARRAY_HOT_CORNER_NONE = "none";
    public static final String ARRAY_HOT_CORNER_TOP_LEFT = "topLeft";
    public static final String ARRAY_HOT_CORNER_BOTTOM_LEFT = "bottomLeft";
    public static final String ARRAY_HOT_CORNER_TOP_RIGHT = "topRight";
    public static final String ARRAY_HOT_CORNER_BOTTOM_RIGHT = "bottomRight";
    public static final String DEFAULT_HOT_CORNER = ARRAY_HOT_CORNER_NONE;

    public static final String PREF_PUSH_BUTTON_HIDE_KEY = "hidePtt";
    public static final Boolean DEFAULT_PUSH_BUTTON_HIDE = false;

    public static final String PREF_PTT_TOGGLE = "togglePtt";
    public static final Boolean DEFAULT_PTT_TOGGLE = false;

    public static final String PREF_INPUT_RATE = "input_quality";
    public static final String DEFAULT_RATE = "48000";

    public static final String PREF_INPUT_QUALITY = "input_bitrate";
    public static final int DEFAULT_INPUT_QUALITY = 40000;

    public static final String PREF_AMPLITUDE_BOOST = "inputVolume";
    public static final Integer DEFAULT_AMPLITUDE_BOOST = 100;

    public static final String PREF_CHAT_NOTIFY = "chatNotify";
    public static final Boolean DEFAULT_CHAT_NOTIFY = true;

    public static final String PREF_USE_TTS = "useTts";
    public static final Boolean DEFAULT_USE_TTS = true;

    public static final String PREF_SHORT_TTS_MESSAGES = "shortTtsMessages";
    public static final boolean DEFAULT_SHORT_TTS_MESSAGES = false;

    public static final String PREF_AUTO_RECONNECT = "autoReconnect";
    public static final Boolean DEFAULT_AUTO_RECONNECT = true;

    public static final String PREF_THEME = "theme";
    public static final String ARRAY_THEME_LIGHT = "lightDark";
    public static final String ARRAY_THEME_DARK = "dark";
    public static final String ARRAY_THEME_SOLARIZED_LIGHT = "solarizedLight";
    public static final String ARRAY_THEME_SOLARIZED_DARK = "solarizedDark";

    public static final String PREF_PTT_BUTTON_HEIGHT = "pttButtonHeight";
    public static final int DEFAULT_PTT_BUTTON_HEIGHT = 150;


    /**
     * @brief Certificato client PKCS#12 codificato in Base64, salvato direttamente
     * in SharedPreferences (Base64).
     * Se presente, ha la precedenza sul vecchio percorso DB.
     */
    public static final String PREF_CERT_DATA = "certificateData";

    public static final String PREF_DEFAULT_USERNAME = "defaultUsername";
    public static final String DEFAULT_DEFAULT_USERNAME = "Mumla_User"; // funny var name

    public static final String PREF_FORCE_TCP = "forceTcp";
    public static final Boolean DEFAULT_FORCE_TCP = false;


    public static final String PREF_DISABLE_OPUS = "disableOpus";
    public static final Boolean DEFAULT_DISABLE_OPUS = false;

    public static final String PREF_MUTED = "muted";
    public static final Boolean DEFAULT_MUTED = false;

    public static final String PREF_DEAFENED = "deafened";
    public static final Boolean DEFAULT_DEAFENED = false;

    public static final String PREF_FIRST_RUN = "firstRun";
    public static final Boolean DEFAULT_FIRST_RUN = true;

    public static final String PREF_FRAMES_PER_PACKET = "audio_per_packet";
    public static final String DEFAULT_FRAMES_PER_PACKET = "2";

    public static final String PREF_HALF_DUPLEX = "half_duplex";
    public static final boolean DEFAULT_HALF_DUPLEX = false;

    public static final String PREF_HANDSET_MODE = "handset_mode";
    public static final boolean DEFAULT_HANDSET_MODE = false;

    public static final String PREF_PTT_SOUND = "ptt_sound";
    public static final boolean DEFAULT_PTT_SOUND = false;

    public static final String PREF_PREPROCESSOR_ENABLED = "preprocessor_enabled";
    public static final boolean DEFAULT_PREPROCESSOR_ENABLED = true;

    public static final String PREF_STAY_AWAKE = "stay_awake";
    public static final boolean DEFAULT_STAY_AWAKE = false;

    public static final String PREF_SHOW_USER_COUNT = "show_user_count";
    public static final boolean DEFAULT_SHOW_USER_COUNT = false;

    public static final String PREF_START_UP_IN_PINNED_MODE = "startUpInPinnedMode";
    public static final boolean DEFAULT_START_UP_IN_PINNED_MODE = false;

    static {
        ARRAY_INPUT_METHODS = new HashSet<String>();
        ARRAY_INPUT_METHODS.add(ARRAY_INPUT_METHOD_VOICE);
        ARRAY_INPUT_METHODS.add(ARRAY_INPUT_METHOD_PTT);
        ARRAY_INPUT_METHODS.add(ARRAY_INPUT_METHOD_CONTINUOUS);
    }





    // TODO MIRKO:
    public static final String PREF_DOORPI_PIANO = "doorpi_piano";
    public static final String DEFAULT_PREF_DOORPI_PIANO = "";


    public static final String PREF_RASPBERRY_CONFIG_URL = "raspberry_config_url";
    public static final String DEFAULT_PREF_RASPBERRY_CONFIG_URL = "http://192.168.1.54:8080/config/";

    public static final String PREF_KIOSK_MODE = "kiosk_mode";
    public static final boolean DEFAULT_PREF_KIOSK_MODE = false;

    public static final String PREF_HIDE_STATUS_BAR = "hide_status_bar";
    public static final boolean DEFAULT_PREF_HIDE_STATUS_BAR = false;

    public static final String PREF_DOORPI_CAMERA_FORMAT = "camera_format";
    public static final String DEFAULT_PREF_DOORPI_CAMERA_FORMAT = "rtsp";

    public static final String PREF_CAMERA_USERNAME = "camera_username";
    public static final String DEFAULT_PREF_CAMERA_USERNAME = "";

    public static final String PREF_CAMERA_PASSWORD = "camera_password";
    public static final String DEFAULT_PREF_CAMERA_PASSWORD = "";

    public static final String PREF_VIDEO_ROTATION = "video_rotation";
    public static final String DEFAULT_PREF_VIDEO_ROTATION = "0";




    public static final String PREF_DOORPI_HOST = "mumble_host";
    public static final String DEFAULT_PREF_DOORPI_HOST = "192.168.1.54";



    public static final String PREF_DOORPI_NAME = "mumble_name";
    public static final String DEFAULT_PREF_DOORPI_NAME = "DoorPi";


    public static final String PREF_DOORPI_API_PORT = "doorpi_api_port";
    public static final String DEFAULT_PREF_DOORPI_API_PORT = "8080";



    public static final String PREF_DOORPI_IP = "doorpi_ip";
    public static final String DEFAULT_PREF_DOORPI_IP = "192.168.1.54";


    public static final String PREF_DOORPI_PORT = "doorpi_port";
    public static final String DEFAULT_PREF_DOORPI_PORT = "64738";



    public static final String PREF_DOORPI_PASSWORD = "door_pi_password";
    public static final String DEFAULT_PREF_DOORPI_PASSWORD= "gattadapelare";


    public static final String PREF_CAMERA_ENDPOINT = "camera_endpoint";
    public static final String DEFAULT_PREF_CAMERA_ENDPOINT = "";

    public static final String PREF_CALL_TIMEOUT_SECONDS = "call_timeout_seconds";
    public static final int DEFAULT_CALL_TIMEOUT_SECONDS = 60;

    public static final String PREF_AUTO_CLOSE_ON_UNLOCK = "auto_close_on_unlock";
    public static final boolean DEFAULT_AUTO_CLOSE_ON_UNLOCK = true;








    public static final String PREF_SHOW_BUTTON_CALL ="show_button_call";
    public static final boolean DEFAULT_SHOW_BUTTON_CALL =  true;

    public static final String PREF_SHOW_BUTTON_UNLOCK ="show_button_unlock";
    public static final boolean DEFAULT_SHOW_BUTTON_UNLOCK = true;

    public static final String PREF_CMD_UNLOCK ="cmd_unlock";
    public static final String DEFAULT_PREF_CMD_UNLOCK = "unlockdoor";


    public static final String PREF_CMD_INSIDE_LIGHT = "cmd_insidelight";
    public static final String DEFAULT_PREF_INSIDE_LIGHT = "device1";

    public static final String PREF_CMD_GET_ALL_DEVICE_STATUS = "cmd_getalldevicestatus";
    public static final String DEFAULT_GET_ALL_DEVICE_STATUS = "getalldevicestatus";


    public static final String PREF_CMD_GET_DEVICE_STATUS = "cmd_getdevicestatus";
    public static final String DEFAULT_GET_DEVICE_STATUS = "getdevicestatus";



    // TODO MIRKO:

    private final SharedPreferences preferences;

    public static Settings getInstance(Context context) {
        return new Settings(context);
    }

    private Settings(Context ctx) {

        preferences = PreferenceManager.getDefaultSharedPreferences(ctx);

        //preferences = ctx.getSharedPreferences("TEST", Context.MODE_PRIVATE);


        //mirko: PER RIPULIRE IN Caso di errori
        //PreferenceManager.getDefaultSharedPreferences(ctx).edit().clear().apply();


    }

    public String getInputMethod() {
        String method = preferences.getString(PREF_INPUT_METHOD, ARRAY_INPUT_METHOD_VOICE);
        if(!ARRAY_INPUT_METHODS.contains(method)) {
            // Set default method for users who used to use handset mode before removal.
            method = ARRAY_INPUT_METHOD_VOICE;
        }
        return method;
    }




    /**
     * Converts the preference input method value to the one used to connect to a server via Humla.
     * @return An input method value used to instantiate a Humla service.
     */
    public int getHumlaInputMethod() {
        String inputMethod = getInputMethod();
        if (ARRAY_INPUT_METHOD_VOICE.equals(inputMethod)) {
            return Constants.TRANSMIT_VOICE_ACTIVITY;
        } else if (ARRAY_INPUT_METHOD_PTT.equals(inputMethod)) {
            return Constants.TRANSMIT_PUSH_TO_TALK;
        } else if (ARRAY_INPUT_METHOD_CONTINUOUS.equals(inputMethod)) {
            return Constants.TRANSMIT_CONTINUOUS;
        }
        throw new RuntimeException("Could not convert input method '" + inputMethod + "' to a Humla input method id!");
    }


    public void setInputMethod(String inputMethod) {
        if(ARRAY_INPUT_METHOD_VOICE.equals(inputMethod) || ARRAY_INPUT_METHOD_PTT.equals(inputMethod) || ARRAY_INPUT_METHOD_CONTINUOUS.equals(inputMethod)) {
            preferences.edit().putString(PREF_INPUT_METHOD, inputMethod).apply();
        } else {
            throw new RuntimeException("Invalid input method " + inputMethod);
        }
    }


    public int getInputSampleRate() {
        try {
            return Integer.parseInt(preferences.getString(Settings.PREF_INPUT_RATE, DEFAULT_RATE));
        } catch (NumberFormatException e) {
            Log.e(TAG, "getInputSampleRate: valore non numerico, uso default " + DEFAULT_RATE);
            return Integer.parseInt(DEFAULT_RATE);
        }
    }

    public int getInputQuality() {
        return preferences.getInt(Settings.PREF_INPUT_QUALITY, DEFAULT_INPUT_QUALITY);
    }

    public float getAmplitudeBoostMultiplier() {
        return (float)preferences.getInt(Settings.PREF_AMPLITUDE_BOOST, DEFAULT_AMPLITUDE_BOOST)/100;
    }

    public float getDetectionThreshold() {
        return (float)preferences.getInt(PREF_THRESHOLD, DEFAULT_THRESHOLD)/100;
    }

    public int getPushToTalkKey() {
        return preferences.getInt(PREF_PUSH_KEY, DEFAULT_PUSH_KEY);
    }

    public String getHotCorner() {
        return preferences.getString(PREF_HOT_CORNER_KEY, DEFAULT_HOT_CORNER);
    }

    /**
     * Returns whether or not the hot corner is enabled.
     * @return true if a hot corner should be shown.
     */
    public boolean isHotCornerEnabled() {
        return !ARRAY_HOT_CORNER_NONE.equals(preferences.getString(PREF_HOT_CORNER_KEY, DEFAULT_HOT_CORNER));
    }

    /**
     * Returns the view gravity of the hot corner, or 0 if hot corner is disabled.
     * @return A {@link android.view.Gravity} value, or 0 if disabled.
     */
    public int getHotCornerGravity() {
        String hc = getHotCorner();
        if(ARRAY_HOT_CORNER_BOTTOM_LEFT.equals(hc)) {
            return Gravity.LEFT | Gravity.BOTTOM;
        } else if(ARRAY_HOT_CORNER_BOTTOM_RIGHT.equals(hc)) {
            return Gravity.RIGHT | Gravity.BOTTOM;
        } else if(ARRAY_HOT_CORNER_TOP_LEFT.equals(hc)) {
            return Gravity.LEFT | Gravity.TOP;
        } else if(ARRAY_HOT_CORNER_TOP_RIGHT.equals(hc)) {
            return Gravity.RIGHT | Gravity.TOP;
        }
        return 0;
    }

    /**
     * @return the resource ID of the user-defined theme.
     */
    public int getTheme() {
        String theme = preferences.getString(PREF_THEME, ARRAY_THEME_LIGHT);
        if(ARRAY_THEME_LIGHT.equals(theme))
            return R.style.Theme_Mumla;
        else if(ARRAY_THEME_DARK.equals(theme))
            return R.style.Theme_Mumla_Dark;
        else if(ARRAY_THEME_SOLARIZED_LIGHT.equals(theme))
            return R.style.Theme_Mumla_Solarized_Light;
        else if(ARRAY_THEME_SOLARIZED_DARK.equals(theme))
            return R.style.Theme_Mumla_Solarized_Dark;
        return -1;
    }

    /* @return the height of PTT button */
    public int getPTTButtonHeight() {
        return preferences.getInt(Settings.PREF_PTT_BUTTON_HEIGHT, DEFAULT_PTT_BUTTON_HEIGHT);
    }

    public String getDefaultUsername() {
        return preferences.getString(PREF_DEFAULT_USERNAME, DEFAULT_DEFAULT_USERNAME);
    }

    public boolean isPushToTalkToggle() {
        return preferences.getBoolean(PREF_PTT_TOGGLE, DEFAULT_PTT_TOGGLE);
    }

    public boolean isPushToTalkButtonShown() {
        return !preferences.getBoolean(PREF_PUSH_BUTTON_HIDE_KEY, DEFAULT_PUSH_BUTTON_HIDE);
    }

    public boolean isChatNotifyEnabled() {
        return preferences.getBoolean(PREF_CHAT_NOTIFY, DEFAULT_CHAT_NOTIFY);
    }

    public boolean isTextToSpeechEnabled() {
        return preferences.getBoolean(PREF_USE_TTS, DEFAULT_USE_TTS);
    }

    public boolean isShortTextToSpeechMessagesEnabled() {
        return preferences.getBoolean(PREF_SHORT_TTS_MESSAGES, DEFAULT_SHORT_TTS_MESSAGES);
    }

    public boolean isAutoReconnectEnabled() {
        return preferences.getBoolean(PREF_AUTO_RECONNECT, DEFAULT_AUTO_RECONNECT);
    }

    public boolean isTcpForced() {
        return preferences.getBoolean(PREF_FORCE_TCP, DEFAULT_FORCE_TCP);
    }

    public boolean isOpusDisabled() {
        return preferences.getBoolean(PREF_DISABLE_OPUS, DEFAULT_DISABLE_OPUS);
    }


    public boolean isMuted() {
        return preferences.getBoolean(PREF_MUTED, DEFAULT_MUTED);
    }

    public boolean isDeafened() {
        return preferences.getBoolean(PREF_DEAFENED, DEFAULT_DEAFENED);
    }

    public boolean isFirstRun() {
        return preferences.getBoolean(PREF_FIRST_RUN, DEFAULT_FIRST_RUN);
    }


    public void setMutedAndDeafened(boolean muted, boolean deafened) {
        Editor editor = preferences.edit();
        editor.putBoolean(PREF_MUTED, muted || deafened);
        editor.putBoolean(PREF_DEAFENED, deafened);
        editor.apply();
    }

    public void setFirstRun(boolean run) {
        preferences.edit().putBoolean(PREF_FIRST_RUN, run).apply();
    }

    public int getFramesPerPacket() {
        try {
            return Integer.parseInt(preferences.getString(PREF_FRAMES_PER_PACKET, DEFAULT_FRAMES_PER_PACKET));
        } catch (NumberFormatException e) {
            Log.e(TAG, "getFramesPerPacket: valore non numerico, uso default " + DEFAULT_FRAMES_PER_PACKET);
            return Integer.parseInt(DEFAULT_FRAMES_PER_PACKET);
        }
    }

    public boolean isHalfDuplex() {
        return preferences.getBoolean(PREF_HALF_DUPLEX, DEFAULT_HALF_DUPLEX);
    }

    public boolean isHandsetMode() {
        return preferences.getBoolean(PREF_HANDSET_MODE, DEFAULT_HANDSET_MODE);
    }

    public boolean isPttSoundEnabled() {
        return preferences.getBoolean(PREF_PTT_SOUND, DEFAULT_PTT_SOUND);
    }

    public boolean isPreprocessorEnabled() {
        return preferences.getBoolean(PREF_PREPROCESSOR_ENABLED, DEFAULT_PREPROCESSOR_ENABLED);
    }

    public boolean shouldStayAwake() {
        return preferences.getBoolean(PREF_STAY_AWAKE, DEFAULT_STAY_AWAKE);
    }


    /**
     * @brief Restituisce il certificato client PKCS#12 salvato in SharedPreferences.
     * @return Byte array del certificato, o {@code null} se non ancora salvato.
     */
    public byte[] getCertificateBytes() {
        String b64 = preferences.getString(PREF_CERT_DATA, null);
        if (b64 == null || b64.isEmpty()) return null;
        return android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
    }

    /**
     * @brief Salva il certificato client PKCS#12 in SharedPreferences codificato in Base64.
     * @param data Byte array del PKCS#12, o {@code null} per rimuoverlo.
     */
    public void setCertificateBytes(byte[] data) {
        if (data == null) {
            preferences.edit().remove(PREF_CERT_DATA).apply();
        } else {
            preferences.edit()
                    .putString(PREF_CERT_DATA, android.util.Base64.encodeToString(data, android.util.Base64.DEFAULT))
                    .apply();
        }
    }

    /**
     * @brief Ritorna true se esiste un certificato client utilizzabile.
     * Controlla prima il nuovo percorso SharedPreferences, poi il vecchio ID DB
     * (per compatibilità con installazioni precedenti).
     */
    public boolean isUsingCertificate() {
        return getCertificateBytes() != null;
    }

    /**
     * @return true if the user count should be shown next to channels.
     */
    public boolean shouldShowUserCount() {
        return preferences.getBoolean(PREF_SHOW_USER_COUNT, DEFAULT_SHOW_USER_COUNT);
    }

    public boolean shouldStartUpInPinnedMode() {
        return preferences.getBoolean(PREF_START_UP_IN_PINNED_MODE, DEFAULT_START_UP_IN_PINNED_MODE);
    }





    /** @return Piano/identificativo dell'unità (es. "PIANO4") usato come label nell'ActionBar. */
    public String getDoorPiPiano() {
        String value ="";

        try {
            value = preferences.getString(PREF_DOORPI_PIANO, DEFAULT_PREF_DOORPI_PIANO);
        }catch (Exception ex){

        }

        return value;
    }



    /** @return Nome configurato del server Mumble/DoorPi. */
    public String getDoorPiName() {
        return preferences.getString(PREF_DOORPI_NAME, DEFAULT_PREF_DOORPI_NAME);
    }

    /**
     * @return Hostname o IP del server Mumble, ricevuto dal config Raspberry.
     * Se assente usa il default storico per mantenere avviabile il tablet.
     */
    public String getDoorPiHost() {
        return preferences.getString(PREF_DOORPI_HOST, DEFAULT_PREF_DOORPI_HOST);
    }

    /**
     * @return Indirizzo IP/host DoorPi per le chiamate HTTP REST, ricevuto dal config Raspberry.
     * Se assente usa il default storico per mantenere avviabile il tablet.
     */
    public String getDoorPiIP() {
        return preferences.getString(PREF_DOORPI_IP, DEFAULT_PREF_DOORPI_IP);
    }

    /** @return Password di accesso al server DoorPi. */
    public String getDoorPassword() {
        return preferences.getString(PREF_DOORPI_PASSWORD, DEFAULT_PREF_DOORPI_PASSWORD);
    }

    /** @return Porta Mumble ricevuta dal config Raspberry (default storico: 64738). */
    public int getDoorPiPort() {
        try {
            return Integer.parseInt(preferences.getString(PREF_DOORPI_PORT, DEFAULT_PREF_DOORPI_PORT));
        } catch (NumberFormatException e) {
            Log.e(TAG, "getDoorPiPort: valore non numerico, uso default " + DEFAULT_PREF_DOORPI_PORT);
            return Integer.parseInt(DEFAULT_PREF_DOORPI_PORT);
        }
    }


    /** @return Porta HTTP API REST DoorPi ricevuta dal config Raspberry (default storico: 8080). */
    public String getDoorPiAPIPort() {
        return preferences.getString(PREF_DOORPI_API_PORT, DEFAULT_PREF_DOORPI_API_PORT);
    }


    /** @return Password Mumble ricevuta dal config Raspberry. */
    public String getDoorPiPassword() {
        return preferences.getString(PREF_DOORPI_PASSWORD, DEFAULT_PREF_DOORPI_PASSWORD);
    }

    /** @return true se la chiamata va chiusa automaticamente dopo lo sblocco porta. */
    public boolean isAutoCloseOnUnlock() {
        return preferences.getBoolean(PREF_AUTO_CLOSE_ON_UNLOCK, DEFAULT_AUTO_CLOSE_ON_UNLOCK);
    }

    /**
     * @return Timeout chiamata in millisecondi (per postDelayed).
     * Clampato nel range 20-200s; default 60s.
     */
    public int getCallTimeoutMs() {
        int seconds = preferences.getInt(PREF_CALL_TIMEOUT_SECONDS, DEFAULT_CALL_TIMEOUT_SECONDS);
        seconds = Math.max(20, Math.min(200, seconds));
        return seconds * 1000;
    }




    /**
     * @brief URL dell'endpoint di configurazione del Raspberry Pi.
     *
     * Viene usato da {@link com.doorphone.app.RaspberryConfigFetcher} al boot
     * per recuperare i parametri dello stream video (endpoint, username, password).
     *
     * @return URL configurazione (es. http://192.168.1.54:8080/config/).
     */
    public String getRaspberryConfigUrl() {
        return preferences.getString(PREF_RASPBERRY_CONFIG_URL, DEFAULT_PREF_RASPBERRY_CONFIG_URL);
    }

    /**
     * @brief URL dello stream video della telecamera.
     *
     * Valore popolato a runtime da {@link com.doorphone.app.RaspberryConfigFetcher}
     * leggendo il campo {@code videostream.endpoint} dalla configurazione del Raspberry.
     *
     * @return URL stream RTSP oppure stringa vuota se non ancora ricevuto.
     */
    public String getCameraEndPoint() {
        return preferences.getString(PREF_CAMERA_ENDPOINT, DEFAULT_PREF_CAMERA_ENDPOINT);
    }

    /**
     * @brief Tipo di stream video della telecamera.
     * @return "mjpeg" per stream HTTP MJPEG, "rtsp" per stream RTSP.
     */
    public String getCameraStreamType() {
        return preferences.getString(PREF_DOORPI_CAMERA_FORMAT, DEFAULT_PREF_DOORPI_CAMERA_FORMAT);
    }

    /**
     * @brief Username per l'autenticazione allo stream video (MJPEG o RTSP).
     * @return Username oppure stringa vuota se non configurato.
     */
    public String getCameraUsername() {
        return preferences.getString(PREF_CAMERA_USERNAME, DEFAULT_PREF_CAMERA_USERNAME);
    }

    /**
     * @brief Password per l'autenticazione allo stream video (MJPEG o RTSP).
     * @return Password oppure stringa vuota se non configurata.
     */
    public String getCameraPassword() {
        return preferences.getString(PREF_CAMERA_PASSWORD, DEFAULT_PREF_CAMERA_PASSWORD);
    }

    /**
     * @brief Indica se abilitare il comportamento kiosk.
     *
     * Default {@code false} per permettere manutenzione del Nexus 7. Il valore viene
     * aggiornato dal Raspberry tramite {@link com.doorphone.app.RaspberryConfigFetcher}
     * quando nel JSON di configurazione e' presente {@code kiosk} o {@code kiosk_mode}.
     *
     * @return {@code true} se l'app deve cercare di restare bloccata in primo piano.
     */
    public boolean isKioskModeEnabled() {
        return preferences.getBoolean(PREF_KIOSK_MODE, DEFAULT_PREF_KIOSK_MODE);
    }

    /**
     * @brief Indica se nascondere anche la status bar superiore in kiosk.
     *
     * Default {@code false}: lascia visibili ora, batteria e stato Wi-Fi.
     * Il Raspberry puo' abilitarlo con {@code hide_status_bar: true}.
     *
     * @return {@code true} se in kiosk deve essere nascosta anche la barra superiore.
     */
    public boolean shouldHideStatusBarInKiosk() {
        return preferences.getBoolean(PREF_HIDE_STATUS_BAR, DEFAULT_PREF_HIDE_STATUS_BAR);
    }

    /**
     * @brief Rotazione video applicata allo stream camera.
     * @return Angolo di rotazione in gradi: 0, 90, 180 o 270. Default: 0.
     */
    public int getVideoRotation() {
        try {
            return Integer.parseInt(preferences.getString(PREF_VIDEO_ROTATION, DEFAULT_PREF_VIDEO_ROTATION));
        } catch (NumberFormatException e) {
            Log.e(TAG, "getVideoRotation: valore non valido, uso default " + DEFAULT_PREF_VIDEO_ROTATION);
            return Integer.parseInt(DEFAULT_PREF_VIDEO_ROTATION);
        }
    }




    /** @return true se il pulsante Chiama deve essere visibile. */
    public boolean shouldShowButtonCall() {
        return preferences.getBoolean(PREF_SHOW_BUTTON_CALL, DEFAULT_SHOW_BUTTON_CALL);
    }



    /** @return true se il pulsante Sblocca Porta deve essere visibile. */
    public boolean shouldShowButtonUnlock() {
        return preferences.getBoolean(PREF_SHOW_BUTTON_UNLOCK, DEFAULT_SHOW_BUTTON_UNLOCK);
    }




    /** @return Comando DoorPi per sbloccare la porta. */
    public String getCMDUnlock() {
        return preferences.getString(PREF_CMD_UNLOCK, DEFAULT_PREF_CMD_UNLOCK);
    }

    /** @return Comando DoorPi per ottenere lo stato di tutti i dispositivi (risponde JSON). */
    public String getCMDGetAllDeviceStatus() {
        return  preferences.getString(PREF_CMD_GET_ALL_DEVICE_STATUS, DEFAULT_GET_ALL_DEVICE_STATUS) ;
    }


    /** @return Comando DoorPi per accendere la luce interna. */
    public String getCMDLightInSide_on() {
        return "setdevicestatus&D=" + preferences.getString(PREF_CMD_INSIDE_LIGHT, DEFAULT_PREF_INSIDE_LIGHT) + "&S=on";
    }

    /** @return Comando DoorPi per spegnere la luce interna. */
    public String getCMDLightInside_off() {
        return "setdevicestatus&D=" + preferences.getString(PREF_CMD_INSIDE_LIGHT, DEFAULT_PREF_INSIDE_LIGHT) + "&S=off";
    }

    // TODO  MIRKO:



}
