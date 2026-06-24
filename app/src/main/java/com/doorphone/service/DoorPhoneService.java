package com.doorphone.service;



import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.widget.Toast;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import java.util.Hashtable;
import java.util.concurrent.atomic.AtomicBoolean;
import se.lublin.humla.Constants;
import se.lublin.humla.HumlaService;
import se.lublin.humla.exception.AudioException;
import se.lublin.humla.model.IMessage;
import se.lublin.humla.model.IUser;
import se.lublin.humla.model.TalkState;
import se.lublin.humla.util.HumlaException;
import se.lublin.humla.util.HumlaObserver;
import com.doorphone.R;
import com.doorphone.Settings;
import com.doorphone.app.DoorPhoneActivity;
import com.doorphone.app.MyApp;
import com.doorphone.ui.VideoVLCActivity;
import com.doorphone.util.HtmlUtils;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;




/**
 * @brief Servizio principale di DoorPhone per la comunicazione Mumble con DoorPi.
 *
 * Estende {@link HumlaService} (protocollo Mumble/Humla) aggiungendo:
 * - Gestione del ciclo di vita della chiamata DoorPi (ring, accept, close).
 * - Invio di comandi testuali a DoorPi via Mumble TextMessage.
 * - Riconnessione automatica al WiFi in Doze mode tramite {@link ConnectivityManager.NetworkCallback}.
 * - Text-To-Speech per i messaggi Mumble (opzionale, configurabile).
 * - Notifica foreground per mantenere il servizio attivo in background.
 * - Tracciamento delle sessioni utente ({@link #user_in_chat}) per l'invio messaggi.
 *
 * Implementa {@link SharedPreferences.OnSharedPreferenceChangeListener} per aggiornare
 * la configurazione audio senza richiedere riavvio del servizio.
 *
 * @note Il servizio è avviato e tenuto in foreground per tutto il ciclo di vita dell'app kiosk.
 */
@SuppressWarnings("deprecation")
public class DoorPhoneService extends HumlaService implements SharedPreferences.OnSharedPreferenceChangeListener, IDoorPhoneService {

    /**
     * @brief Numero massimo di caratteri di un messaggio letto dal TTS.
     * Messaggi più lunghi non vengono letti ad alta voce.
     */
    public static final int TTS_THRESHOLD = 250;

    /** @brief Ritardo in ms per la riconnessione automatica al server Mumble. */
    public static final int RECONNECT_DELAY = 10000;

    /** @brief Preferenze dell'applicazione (singleton). */
    private Settings mSettings;

    /** @brief Gestore delle notifiche chat nella status bar. */
    private DoorPhoneMessageNotification mMessageNotification;


    /** @brief {@code true} se il suono PTT (push-to-talk) è abilitato nelle preferenze. */
    private boolean mPTTSoundEnabled;

    /** @brief {@code true} se i messaggi TTS devono essere accorciati (solo hostname per i link). */
    private boolean mShortTtsMessagesEnabled;

    /** @brief {@code true} se l'errore di connessione è già stato mostrato all'utente. */
    private boolean mErrorShown;

    /**
     * @brief Indica se la connessione Mumble è attiva e sincronizzata.
     * Aggiornato da {@link #mObserver} in risposta agli eventi di connessione/disconnessione.
     */
    boolean is_connected = false;

    /**
     * @brief {@code true} se una chiamata DoorPi è attiva (dopo {@link #openCall()},
     * prima di {@link #closeCall()} o disconnessione). Usato da {@link com.doorphone.ui.VideoVLCActivity}
     * per non interrompere una chiamata in corso quando l'Activity viene ricreata.
     */
    private volatile boolean mDoorCallActive = false;

    /** @brief Tag per i log Logcat. */
    private static final String TAG = DoorPhoneService.class.getSimpleName();

    /**
     * @brief Tag dedicato per i log diagnostici del flusso ring.
     *
     * Usato al posto di {@link #TAG} sui log relativi a "cmd-ring" / Ring() per
     * permettere il filtro preciso in Android Studio Logcat con {@code tag:RING_DBG}.
     */
    private static final String RING_TAG = "RING_DBG";

    /**
     * @brief Mappa nome utente → session ID degli utenti attualmente nel canale Mumble.
     *
     * Popolata in {@link HumlaObserver#onUserConnected(IUser)} e svuotata in
     * {@link HumlaObserver#onUserRemoved(IUser, String)}.
     * Usata da {@link #sendMessage(String, String)} per risolvere il session ID
     * del destinatario prima dell'invio.
     */
    Hashtable<String, Integer> user_in_chat = new Hashtable<String, Integer>();

    /**
     * @brief NetworkCallback per la riconnessione Mumble al ritorno del WiFi.
     *
     * Sostituisce la dipendenza da {@code CONNECTIVITY_ACTION} (broadcast deprecato
     * che NON viene consegnato in Doze mode) con {@code NetworkCallback} che viene
     * notificato dal sistema operativo anche quando il dispositivo è in sleep profondo.
     *
     * Flusso:
     * 1. WiFi cade → HumlaService entra in stato "reconnecting" aspettando CONNECTIVITY_ACTION.
     * 2. Dispositivo va in Doze → CONNECTIVITY_ACTION non arriva mai [PROBLEMA].
     * 3. WiFi torna → il sistema chiama onAvailable() [QUESTA CALLBACK — funziona in Doze].
     * 4. Se non connessi: cancella il vecchio receiver bloccato e forza la riconnessione.
     */
    private ConnectivityManager.NetworkCallback mNetworkCallback;

    /**
     * @brief Flag per ignorare il primo {@code onAvailable()} post-registrazione.
     *
     * Android invoca {@link ConnectivityManager.NetworkCallback#onAvailable(Network)}
     * immediatamente dopo {@link ConnectivityManager#registerNetworkCallback} per
     * ogni rete già matching (documentato). Al boot la rete c'è sempre, quindi
     * questo scatto iniziale si sovrapporrebbe al normale auto-connect avviato
     * altrove, causando DUE connect() in parallelo: il server Mumble scalcia
     * il primo client quando il secondo si autentica con lo stesso certificato.
     */
    private final AtomicBoolean mFirstNetworkCallback = new AtomicBoolean(true);

    /** @brief Istanza Text-To-Speech per la lettura ad alta voce dei messaggi chat. */
    private TextToSpeech mTTS;

    /**
     * @brief Listener di inizializzazione TTS.
     * Logga un warning se il motore TTS non è disponibile sul dispositivo.
     */
    private TextToSpeech.OnInitListener mTTSInitListener = new TextToSpeech.OnInitListener() {
        @Override
        public void onInit(int status) {
            if(status == TextToSpeech.ERROR){
                logWarning(getString(R.string.tts_failed));
            }
        }
    };

    // -------------------------------------------------------------------------
    // HumlaObserver — callback eventi Mumble
    // -------------------------------------------------------------------------

    /**
     * @brief Observer degli eventi Mumble (connessione, utenti, messaggi, audio).
     *
     * Ogni metodo corrisponde a un evento del protocollo Humla (Mumble).
     * L'observer è registrato in {@link #onCreate()} e deregistrato in {@link #onDestroy()}.
     */
    private HumlaObserver mObserver = new HumlaObserver() {

        /**
         * @brief Chiamato quando il servizio Humla inizia il tentativo di connessione.
         * Azzera il flag di errore già mostrato per permettere di mostrare nuovi errori.
         */
        @Override
        public void onConnecting() {
            mErrorShown = false;
        }

        /**
         * @brief Chiamato quando la connessione TCP al server Mumble è stabilita (prima della sync).
         *
         * Aggiorna la notifica foreground e notifica subito la UI con stato "Connected", senza
         * attendere la sync degli utenti. Questo rende il pallino verde immediatamente visibile
         * non appena Mumble è raggiungibile, invece di aspettare {@link #onUserConnected}.
         */
        @Override
        public void onConnected() {
            Log.d(TAG, "Connected");
            showForegroundNotification(getString(R.string.app_name) + " — Connesso a DoorPi", "Servizio Mumble attivo");
            is_connected = true;
            UpdateClientStatus("Connected");
        }

        /**
         * @brief Chiamato quando la connessione al server Mumble viene persa.
         *
         * - Aggiorna la notifica foreground a "In riconnessione..." in modo che l'utente
         *   sappia che il servizio è ancora attivo ma non connesso, e non veda erroneamente
         *   "Connesso a DoorPi" nella status bar durante un'interruzione di rete.
         * - Aggiorna la UI dell'Activity tramite broadcast (ActionBar rossa).
         * - Azzera il flag di connessione.
         *
         * @param e Eccezione con il motivo della disconnessione (null se volontaria).
         */
        @Override
        public void onDisconnected(HumlaException e) {
            Log.d(TAG, "Disconnected");
            showForegroundNotification(getString(R.string.app_name), "In riconnessione...");
            UpdateClientStatus("Disconnected");
            is_connected = false;
            mDoorCallActive = false;
        }

        /**
         * @brief Chiamato quando un nuovo utente entra nel canale Mumble.
         *
         * Aggiorna {@link #user_in_chat} con il session ID dell'utente appena connesso,
         * necessario per inviare messaggi diretti tramite {@link #sendMessage(String, String)}.
         * Notifica l'Activity dello stato "Connected".
         *
         * @param user Utente che si è connesso al canale.
         */
        @Override
        public void onUserConnected(IUser user) {
            user_in_chat.put(user.getName(), user.getSession());
            is_connected = true;
            UpdateClientStatus("Connected");
        }

        /**
         * @brief Chiamato quando un utente lascia il canale Mumble.
         *
         * Rimuove l'utente da {@link #user_in_chat} per evitare invii a sessioni non più valide.
         *
         * @param user   Utente che ha lasciato il canale.
         * @param reason Motivo della disconnessione (può essere null o vuoto).
         */
        @Override
        public void onUserRemoved(IUser user, String reason) {
            user_in_chat.remove(user.getName());
        }

        /**
         * @brief Chiamato quando lo stato di un utente viene aggiornato dal server.
         *
         * Aggiorna {@link #user_in_chat}: Humla chiama questo callback (invece di
         * {@link #onUserConnected}) per utenti già presenti in {@code ModelHandler.mUsers}
         * (es. alla riconnessione). Senza questo put, {@link #sendMessage} non troverebbe
         * mai la sessione di quegli utenti.
         *
         * Se l'utente aggiornato è l'utente locale (session corrente):
         * - Sincronizza lo stato muto/sordo nelle preferenze.
         * - Se non è mutato, notifica l'Activity con stato "Connected".
         *
         * @param user Utente il cui stato è cambiato.
         */
        @Override
        public void onUserStateUpdated(IUser user) {
            Log.d(TAG, "onUserStateUpdated" );
            user_in_chat.put(user.getName(), user.getSession());

            boolean isLocalUser;
            try {
                isLocalUser = (user.getSession() == getSessionId());
            } catch (RuntimeException e) {
                Log.w(TAG, "onUserStateUpdated: sessione non disponibile: " + e.getMessage());
                return;
            }
            if(isLocalUser) {

                    mSettings.setMutedAndDeafened(user.isSelfMuted(), user.isSelfDeafened());

                    String status;
                    if (user.isSelfMuted() && user.isSelfDeafened()) {
                        status = getString(R.string.status_notify_muted_and_deafened);
                    }else if (user.isSelfMuted()) {
                        status = getString(R.string.status_notify_muted);
                    }else {
                        status = getString(R.string.connected);
                        is_connected = true;
                        UpdateClientStatus(status);
                    }

            }
        }

        /**
         * @brief Chiamato quando viene ricevuto un messaggio testuale nel canale Mumble.
         *
         * Gestisce due casi:
         * - Messaggi di comando DoorPi (prefisso "cmd-"): esegue il comando localmente
         *   (es. "cmd-ring" → suona il campanello via {@link #Ring()}).
         * - Messaggi normali: li legge ad alta voce via TTS se abilitato e sotto soglia caratteri.
         *
         * @param message Messaggio ricevuto.
         */
        @Override
        public void onMessageLogged(IMessage message) {
            Log.d(RING_TAG, "[1/onMessageLogged] raw=[" + message.getMessage()
                    + "] actor=" + message.getActorName());
            // Rimuove tutti i tag HTML dal messaggio per elaborazione testo puro
            Document parsedMessage = Jsoup.parseBodyFragment(message.getMessage());
            String strippedMessage = parsedMessage.text();

            String ttsMessage;

            if(mShortTtsMessagesEnabled) {
                for (Element anchor : parsedMessage.getElementsByTag("A")) {
                    // Accorcia i link: mostra solo il dominio anziché l'URL completo
                    String href = anchor.attr("href");
                    if (href != null && href.equals(anchor.text())) {
                        String urlHostname = HtmlUtils.getHostnameFromLink(href);
                        if (urlHostname != null) {
                            anchor.text(getString(R.string.chat_message_tts_short_link, urlHostname));
                        }
                    }
                }
                ttsMessage = parsedMessage.text();
            } else {
                ttsMessage = strippedMessage;
            }

            String formattedTtsMessage = getString(R.string.notification_message, message.getActorName(), ttsMessage);

            // Gestione comandi DoorPi: i messaggi che iniziano con "cmd-" sono comandi interni
            String cmd_message = ttsMessage.trim();
            Log.d(RING_TAG, "[2/parsed] tts=[" + ttsMessage + "] trim=[" + cmd_message
                    + "] startsWithCmd=" + cmd_message.startsWith("cmd-"));
            if ( cmd_message.startsWith("cmd-") ) {
                // substring su cmd_message (gia' trimmato): usare ttsMessage.length() qui
                // causava StringIndexOutOfBoundsException se il messaggio originale aveva
                // spazi/newline in coda (length originale > length trimmato).
                String cmd = cmd_message.substring(4).trim();
                Log.d(RING_TAG, "[3/cmd] cmd=[" + cmd + "] equalsRing=" + cmd.equals("ring"));
                Log.d(TAG, "CMD: ------>[" +  cmd + "]");
                if (cmd.equals("ring")){
                    Log.d(RING_TAG, "[4/match] -> chiamo Ring()");
                    Ring();
                } else if (cmd.equals("ack-accept-call") || cmd.equals("ack-close-call")) {
                    Log.i(TAG, "[ACK] ricevuto: cmd-" + cmd);
                    Intent ackIntent = new Intent("my-message");
                    ackIntent.putExtra("message", cmd);
                    LocalBroadcastManager.getInstance(DoorPhoneService.this).sendBroadcast(ackIntent);
                }
            } else {
                // Legge ad alta voce se TTS è abilitato, il messaggio è sotto soglia e non siamo assordati
                if(mSettings.isTextToSpeechEnabled() && mTTS != null && formattedTtsMessage.length() <= TTS_THRESHOLD && getSessionUser() != null ) {
                    mTTS.speak(formattedTtsMessage, TextToSpeech.QUEUE_ADD, null);
                }
            }
        }

        /**
         * @brief Chiamato quando il server Mumble nega un'operazione per mancanza di permessi.
         * Non implementato: nell'app kiosk i permessi sono fissi.
         * @param reason Motivo del rifiuto.
         */
        @Override
        public void onPermissionDenied(String reason) {
        }

        /**
         * @brief Chiamato quando lo stato "sta parlando" di un utente cambia.
         *
         * Se l'utente locale sta parlando in modalità PTT e il suono PTT è abilitato,
         * riproduce il suono di feedback della tastiera.
         *
         * @param user Utente il cui stato di parlata è cambiato.
         */
        @Override
        public void onUserTalkStateUpdated(IUser user) {
            // Log diagnostico audio: traccia chi sta parlando nel canale.
            // Se vediamo "Doorpi" (o nome del Raspberry) entrare in TALKING significa
            // che l'audio del citofono arriva fino al server Mumble. Se NON lo vediamo,
            // il problema e' a monte (Raspberry non trasmette / canale sbagliato).
            try {
                boolean isLocal = isConnectionEstablished() && getSessionId() == user.getSession();
                Log.d("AUDIO_DBG", "[talkState] user=" + user.getName()
                        + " state=" + user.getTalkState()
                        + " isLocal=" + isLocal
                        + " selfDeafened=" + (getSessionUser() != null && getSessionUser().isSelfDeafened()));
            } catch (Exception ignored) {}
            if (isConnectionEstablished() && getSessionId() == user.getSession() && getTransmitMode() == Constants.TRANSMIT_PUSH_TO_TALK && user.getTalkState() == TalkState.TALKING && mPTTSoundEnabled) {
                AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
                if (audioManager != null) {
                    audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, -1);
                }
            }
        }
    };

    // -------------------------------------------------------------------------
    // Ciclo di vita Service
    // -------------------------------------------------------------------------

    /**
     * @brief Inizializzazione del servizio.
     *
     * - Porta immediatamente il servizio in foreground con notifica "Connessione in corso...":
     *   questo garantisce che il servizio sia foreground dal primo istante, indipendentemente
     *   dalla velocità di connessione al server Mumble. Se in futuro il servizio venisse avviato
     *   con {@code startForegroundService()}, la chiamata a {@link #startForeground(int, Notification)}
     *   deve avvenire entro 5 secondi — avvenire in {@code onCreate()} è il modo più sicuro.
     * - Registra {@link #mObserver} per ricevere gli eventi Humla.
     * - Carica le preferenze e registra il listener per i cambiamenti.
     * - Inizializza il log messaggi, la notifica e il TTS (se abilitato).
     * - Registra il {@link #mNetworkCallback} per la riconnessione in Doze mode.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate" );

        showForegroundNotification(getString(R.string.app_name), "Connessione in corso...");

        registerObserver(mObserver);

        mSettings = Settings.getInstance(this);
        mPTTSoundEnabled = mSettings.isPttSoundEnabled();
        mShortTtsMessagesEnabled = mSettings.isShortTextToSpeechMessagesEnabled();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        preferences.registerOnSharedPreferenceChangeListener(this);

        // Il tema deve essere impostato manualmente qui: il tag <application> in XML non lo applica
        // alle View create dal servizio (overlay, dialog).
        setTheme(R.style.Theme_DoorPhone);

        mMessageNotification = new DoorPhoneMessageNotification(DoorPhoneService.this);

        if(mSettings.isTextToSpeechEnabled()){
            mTTS = new TextToSpeech(this, mTTSInitListener);
        }

        registerNetworkCallback();
    }

    /**
     * @brief Restituisce il Binder per il binding dall'Activity.
     * @param intent Intent usato per il binding (non usato).
     * @return Istanza di {@link DoorPhoneBinder} che espone l'interfaccia {@link IDoorPhoneService}.
     */
    @Override
    public IBinder onBind(Intent intent) {
        return new DoorPhoneBinder(this);
    }

    /**
     * @brief Pulizia delle risorse alla distruzione del servizio.
     *
     * - Deregistra il listener delle preferenze.
     * - Tenta di deregistrare il TalkReceiver (ignorando se già deregistrato).
     * - Deregistra l'observer Humla e spegne il TTS.
     * - Deregistra il NetworkCallback.
     */
    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy" );

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        preferences.unregisterOnSharedPreferenceChangeListener(this);

        unregisterObserver(mObserver);
        if(mTTS != null) mTTS.shutdown();
        mMessageNotification.dismiss();

        unregisterNetworkCallback();

        super.onDestroy();
    }

    // -------------------------------------------------------------------------
    // Override HumlaService — eventi connessione
    // -------------------------------------------------------------------------

    /**
     * @brief Chiamato da Humla quando il server ha completato la sincronizzazione iniziale.
     *
     * Gestisce la race condition in cui {@code onConnectionDisconnected()} azzera
     * {@code mModelHandler} prima che questo metodo venga eseguito sul thread di rete.
     * In tal caso la RuntimeException viene catturata e si esce senza crash.
     *
     * Se la sync va a buon fine:
     * - Ripristina lo stato muto/sordo dalle preferenze.
     */
    @Override
    public void onConnectionSynchronized() {
        // Race condition nota: onConnectionDisconnected() può azzerare mModelHandler in HumlaService
        // prima che onConnectionSynchronized() venga eseguito sul thread di rete (Handler post).
        // La RuntimeException viene catturata per uscire silenziosamente senza crash.
        try {
            super.onConnectionSynchronized();
        } catch (RuntimeException e) {
            Log.d(Constants.TAG, "HumlaService, exception in onConnectionSynchronized: " + e);
            return;
        }

        // Ripristina lo stato muto/sordo dell'utente locale dall'ultima sessione
        if(mSettings.isMuted() || mSettings.isDeafened()) {
            setSelfMuteDeafState(mSettings.isMuted(), mSettings.isDeafened());
        }
    }

    /**
     * @brief Chiamato da Humla quando la connessione viene chiusa (volontariamente o per errore).
     *
     * - Azzera lo stato di connessione e svuota il log messaggi.
     * - Svuota {@link #user_in_chat} per eliminare sessioni stantie: in caso di disconnessione
     *   brusca (WiFi cade, timeout), il server potrebbe non inviare i {@code UserRemove} per
     *   ogni utente, lasciando nella mappa session ID non più validi. Alla riconnessione,
     *   {@link HumlaObserver#onUserConnected(IUser)} ripopola correttamente la mappa.
     * - Rimuove la notifica chat.
     *
     * @param e Eccezione con il motivo della disconnessione (null se volontaria).
     */
    @Override
    public void onConnectionDisconnected(HumlaException e) {
        super.onConnectionDisconnected(e);
        Log.d(TAG, "onConnectionDisconnected" );
        is_connected = false;
        user_in_chat.clear();
        mMessageNotification.dismiss();
    }

    // -------------------------------------------------------------------------
    // SharedPreferences.OnSharedPreferenceChangeListener
    // -------------------------------------------------------------------------

    /**
     * @brief Chiamato quando l'utente modifica una preferenza nell'Activity delle impostazioni.
     *
     * Aggiorna in tempo reale i parametri audio di HumlaService senza richiedere riconnessione,
     * tranne per le preferenze che necessitano di riconnessione completa
     * ({@link Settings#PREF_CERT_DATA}, {@link Settings#PREF_FORCE_TCP}, {@link Settings#PREF_DISABLE_OPUS}).
     *
     * @param sharedPreferences Preferenze condivise modificate.
     * @param key               Chiave della preferenza modificata.
     */
    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        Bundle changedExtras = new Bundle();
        boolean requiresReconnect = false;
        switch (key) {
            case Settings.PREF_INPUT_METHOD:
                /* Converte il metodo di input (stringa) nel formato intero usato da Humla. */
                int inputMethod = mSettings.getHumlaInputMethod();
                changedExtras.putInt(HumlaService.EXTRAS_TRANSMIT_MODE, inputMethod);
                break;
            case Settings.PREF_HANDSET_MODE:
                break;
            case Settings.PREF_THRESHOLD:
                changedExtras.putFloat(HumlaService.EXTRAS_DETECTION_THRESHOLD, mSettings.getDetectionThreshold());
                break;
            case Settings.PREF_HOT_CORNER_KEY:
                break;
            case Settings.PREF_USE_TTS:
                if (mTTS == null && mSettings.isTextToSpeechEnabled())
                    mTTS = new TextToSpeech(this, mTTSInitListener);
                else if (mTTS != null && !mSettings.isTextToSpeechEnabled()) {
                    mTTS.shutdown();
                    mTTS = null;
                }
                break;
            case Settings.PREF_SHORT_TTS_MESSAGES:
                mShortTtsMessagesEnabled = mSettings.isShortTextToSpeechMessagesEnabled();
                break;
            case Settings.PREF_AMPLITUDE_BOOST:
                changedExtras.putFloat(EXTRAS_AMPLITUDE_BOOST, mSettings.getAmplitudeBoostMultiplier());
                break;
            case Settings.PREF_HALF_DUPLEX:
                changedExtras.putBoolean(EXTRAS_HALF_DUPLEX, mSettings.isHalfDuplex());
                break;
            case Settings.PREF_PREPROCESSOR_ENABLED:
                changedExtras.putBoolean(EXTRAS_ENABLE_PREPROCESSOR, mSettings.isPreprocessorEnabled());
                break;
            case Settings.PREF_PTT_SOUND:
                mPTTSoundEnabled = mSettings.isPttSoundEnabled();
                break;
            case Settings.PREF_INPUT_QUALITY:
                changedExtras.putInt(EXTRAS_INPUT_QUALITY, mSettings.getInputQuality());
                break;
            case Settings.PREF_INPUT_RATE:
                changedExtras.putInt(EXTRAS_INPUT_RATE, mSettings.getInputSampleRate());
                break;
            case Settings.PREF_FRAMES_PER_PACKET:
                changedExtras.putInt(EXTRAS_FRAMES_PER_PACKET, mSettings.getFramesPerPacket());
                break;
            case Settings.PREF_CERT_DATA:
            case Settings.PREF_FORCE_TCP:
            case Settings.PREF_DISABLE_OPUS:
                // Queste preferenze richiedono una riconnessione completa al server
                requiresReconnect = true;
                break;
        }

        if (changedExtras.size() > 0) {
            try {
                requiresReconnect |= configureExtras(changedExtras);
            } catch (AudioException e) {
                e.printStackTrace();
            }
        }

        if (requiresReconnect && isConnectionEstablished()) {
            Toast.makeText(this, R.string.change_requires_reconnect, Toast.LENGTH_LONG).show();
        }
    }

    // -------------------------------------------------------------------------
    // Override IDoorPhoneService
    // -------------------------------------------------------------------------

    /** @brief Annulla il tentativo di riconnessione in corso (delegato a HumlaService). */
    @Override
    public void cancelReconnect() {
        super.cancelReconnect();
    }

    /**
     * @brief Imposta la visibilità dell'overlay canali. Non usato in questa app kiosk.
     * @param showOverlay {@code true} per mostrare, {@code false} per nascondere.
     */
    @Override
    public void setOverlayShown(boolean showOverlay) {
    }

    /**
     * @brief Restituisce lo stato dell'overlay canali.
     * @return Sempre {@code true} (overlay sempre visibile nell'app kiosk).
     */
    @Override
    public boolean isOverlayShown() {
        return true ;
    }

    /** @brief Rimuove la notifica chat dalla status bar. */
    @Override
    public void clearChatNotifications() {
        mMessageNotification.dismiss();
    }

    /**
     * @brief Termina la chiamata vocale mutando e assordando l'utente locale.
     *
     * Mette l'utente in stato muto+sordo (self-muted + self-deafened) tramite Mumble,
     * segnalando al server che la sessione audio è terminata.
     */
    public void closeCall() {
        Log.d(TAG, "CLOSE CALL");
        mDoorCallActive = false;
        setSelfMuteDeafState(true, true);
    }

    /** @brief {@return {@code true} se una chiamata DoorPi è attiva} */
    public boolean isDoorCallActive() { return mDoorCallActive; }

    /**
     * @brief Avvia la chiamata vocale rimuovendo muto e sordità dell'utente locale.
     *
     * Controparte simmetrica di {@link #closeCall()}: abilita la trasmissione audio
     * chiamando {@link #setSelfMuteDeafState(boolean, boolean)} con entrambi i flag a
     * {@code false}. Punto di ingresso centralizzato per l'accettazione della chiamata:
     * qualsiasi logica aggiuntiva futura (cambio canale, reset stato audio, ecc.)
     * va aggiunta qui anziché dispersa nell'Activity.
     *
     * Chiamato da {@link com.doorphone.ui.VideoVLCActivity} quando l'utente preme
     * il pulsante di accettazione chiamata.
     */
    public void openCall() {
        Log.d(TAG, "OPEN CALL");
        mDoorCallActive = true;
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        int volMusic = am != null ? am.getStreamVolume(AudioManager.STREAM_MUSIC) : -1;
        int volMaxMusic = am != null ? am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) : -1;
        int volVoice = am != null ? am.getStreamVolume(AudioManager.STREAM_VOICE_CALL) : -1;
        int volMaxVoice = am != null ? am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL) : -1;
        Log.d("AUDIO_DBG", "[openCall] connected=" + isConnectionEstablished()
                + " handset=" + (mSettings != null && mSettings.isHandsetMode())
                + " STREAM_MUSIC=" + volMusic + "/" + volMaxMusic
                + " STREAM_VOICE_CALL=" + volVoice + "/" + volMaxVoice);
        try {
            IUser self = getSessionUser();
            if (self != null) {
                Log.d("AUDIO_DBG", "[openCall] PRE  selfMuted=" + self.isSelfMuted()
                        + " selfDeafened=" + self.isSelfDeafened()
                        + " serverMuted=" + self.isMuted()
                        + " serverDeafened=" + self.isDeafened()
                        + " channel=" + (self.getChannel() != null ? self.getChannel().getName() : "null"));
            } else {
                Log.w("AUDIO_DBG", "[openCall] getSessionUser() == null (non sincronizzato)");
            }
        } catch (Exception ex) {
            Log.e("AUDIO_DBG", "[openCall] PRE state read error: " + ex.getMessage());
        }
        setSelfMuteDeafState(false, false);
    }

    /**
     * @brief Marca l'errore di connessione come già mostrato all'utente.
     * Evita la visualizzazione di messaggi di errore duplicati.
     */
    @Override
    public void markErrorShown() {
        mErrorShown = true;
    }

    /**
     * @brief Indica se l'errore di connessione è già stato mostrato.
     * @return {@code true} se l'errore è già stato visualizzato.
     */
    @Override
    public boolean isErrorShown() {
        return mErrorShown;
    }

    // -------------------------------------------------------------------------
    // PTT — Push To Talk
    // -------------------------------------------------------------------------

    /**
     * @brief Chiamato quando l'utente preme il tasto PTT.
     *
     * Avvia la trasmissione vocale se la modalità PTT è attiva e il toggle PTT è disabilitato.
     * In modalità toggle PTT, il tasto giù non fa nulla (attende il rilascio).
     */
    @Override
    public void onTalkKeyDown() {
        if(isConnectionEstablished() && Settings.ARRAY_INPUT_METHOD_PTT.equals(mSettings.getInputMethod())) {
            if (!mSettings.isPushToTalkToggle() && !isTalking()) {
                setTalkingState(true);
            }
        }
    }

    /**
     * @brief Chiamato quando l'utente rilascia il tasto PTT.
     *
     * - Modalità toggle PTT: inverte lo stato di trasmissione.
     * - Modalità normale PTT: ferma la trasmissione.
     */
    @Override
    public void onTalkKeyUp() {
        if(isConnectionEstablished() && Settings.ARRAY_INPUT_METHOD_PTT.equals(mSettings.getInputMethod())) {
            if (mSettings.isPushToTalkToggle()) {
                setTalkingState(!isTalking());
            } else if (isTalking()) {
                setTalkingState(false);
            }
        }
    }

    /**
     * @brief Sopprime le notifiche chat (non implementato in questa app kiosk).
     * @param suppressNotifications {@code true} per sopprimere.
     */
    @Override
    public void setSuppressNotifications(boolean suppressNotifications) {
    }

    // -------------------------------------------------------------------------
    // Binder IPC
    // -------------------------------------------------------------------------

    /**
     * @brief Binder IPC che espone {@link IDoorPhoneService} all'Activity.
     *
     * Permette all'Activity di ottenere il riferimento al servizio tramite
     * {@link android.content.ServiceConnection#onServiceConnected(ComponentName, IBinder)}.
     */
    public static class DoorPhoneBinder extends Binder {
        /** @brief Riferimento al servizio Mumble. */
        private final DoorPhoneService mService;

        /**
         * @brief Costruttore.
         * @param service Istanza del servizio da esporre.
         */
        private DoorPhoneBinder(DoorPhoneService service) {
            mService = service;
        }

        /**
         * @brief Restituisce l'interfaccia del servizio.
         * @return Interfaccia {@link IDoorPhoneService} del servizio.
         */
        public IDoorPhoneService getService() {
            return mService;
        }
    }

    // -------------------------------------------------------------------------
    // Invio messaggi Mumble
    // -------------------------------------------------------------------------

    /**
     * @brief Invia un messaggio testuale diretto a un utente nel canale Mumble.
     *
     * Prima di inviare:
     * 1. Verifica che la connessione Mumble sia attiva ({@link #isConnectionEstablished()}).
     * 2. Risolve il session ID dell'utente tramite {@link #user_in_chat}.
     * 3. Chiama {@link #sendUserTextMessage(int, String)} con il session ID.
     *
     * Se l'utente non è in {@link #user_in_chat} (es. si è disconnesso), il messaggio
     * viene scartato con un warning nel log.
     *
     * @param user    Nome utente Mumble destinatario (es. "Doorpi").
     * @param message Testo del messaggio da inviare.
     */
    public void sendMessage(String user, String message) {
        if (!isConnectionEstablished()) {
            Log.w(TAG, "sendMessage: non connesso, messaggio scartato");
            return;
        }
        Integer user_session = user_in_chat.get(user);
        Log.d(TAG, "User:" + "" + user );
        Log.d(TAG, "Message:" + "" + message );
        Log.d(TAG, "Session: " + user_session );
        if (user_session == null) {
            Log.w(TAG, "sendMessage: utente '" + user + "' non in user_in_chat, messaggio scartato");
            return;
        }
        sendUserTextMessage(user_session, message);
    }

    // -------------------------------------------------------------------------
    // NetworkCallback — riconnessione in Doze mode
    // -------------------------------------------------------------------------

    /**
     * @brief Registra il {@link ConnectivityManager.NetworkCallback} per la riconnessione Mumble.
     *
     * Richiede API 21+ (minSdk = 24, quindi sempre disponibile).
     * Il callback {@link ConnectivityManager.NetworkCallback#onAvailable(Network)} viene
     * notificato dal sistema anche in Doze mode, a differenza del broadcast
     * {@code CONNECTIVITY_ACTION} usato da HumlaService che viene differito o bloccato.
     *
     * Ascolta reti con capacità {@link NetworkCapabilities#NET_CAPABILITY_INTERNET}
     * (WiFi o dati mobili).
     */
    private void registerNetworkCallback() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;

        /*
         * Android richiama subito onAvailable() se al momento della registrazione
         * esiste gia' una rete matching. In quel caso lo ignoriamo per evitare un
         * doppio connect() al boot. Se invece il servizio parte mentre il WiFi e'
         * giu', il primo onAvailable() sara' il vero ritorno della rete e NON deve
         * essere ignorato, altrimenti il Nexus resta in attesa fino al prossimo cambio rete.
         */
        mFirstNetworkCallback.set(hasInternetNetwork(cm));

        mNetworkCallback = new ConnectivityManager.NetworkCallback() {
            /**
             * @brief Chiamato quando una rete con accesso Internet diventa disponibile.
             *
             * Se il servizio Mumble non è connesso e l'auto-reconnect è abilitato:
             * 1. Cancella il vecchio reconnect di HumlaService (basato su CONNECTIVITY_ACTION,
             *    bloccato in Doze mode).
             * 2. Chiama {@code connect()} per avviare immediatamente la riconnessione
             *    usando il server target memorizzato nell'ultima sessione.
             *
             * @param network La rete diventata disponibile.
             */
            @Override
            public void onAvailable(Network network) {
                // Primo scatto post-registrazione: ignorato per evitare doppio connect()
                // (vedi commento su mFirstNetworkCallback).
                if (mFirstNetworkCallback.getAndSet(false)) {
                    Log.d(TAG, "NetworkCallback.onAvailable: primo scatto post-registrazione, ignorato");
                    return;
                }

                if (!mSettings.isAutoReconnectEnabled()) return;

                // Tutti i controlli di stato e il connect() vanno sul main thread:
                // - getConnectionState() legge mConnectionState (non volatile nella libreria)
                //   → lettura stale se letta dal thread del callback
                // - doppio connect() prevenuto: se onAvailable() scatta due volte rapide,
                //   il secondo post vede stato CONNECTING e si ferma
                // - getTargetServer() null check: se il callback scatta prima che DoorPhoneActivity
                //   abbia chiamato Connect() (e impostato mServer), connect() lancerebbe NPE
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (getTargetServer() == null) {
                        Log.d(TAG, "NetworkCallback.onAvailable: server non configurato, skip");
                        return;
                    }
                    HumlaService.ConnectionState state = getConnectionState();
                    if (state != HumlaService.ConnectionState.DISCONNECTED
                            && state != HumlaService.ConnectionState.CONNECTION_LOST) {
                        Log.d(TAG, "NetworkCallback.onAvailable: stato=" + state + ", skip reconnect");
                        return;
                    }
                    Log.d(TAG, "NetworkCallback.onAvailable: rete disponibile, forzo riconnessione Mumble");
                    cancelReconnect();
                    connect();
                });
            }
        };

        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        cm.registerNetworkCallback(request, mNetworkCallback);
        Log.d(TAG, "NetworkCallback registrato");
    }

    /**
     * @brief Indica se al momento della registrazione esiste gia' una rete con Internet.
     *
     * Usato solo per distinguere il primo {@code onAvailable()} sintetico inviato da
     * Android quando la rete e' gia' presente dal primo {@code onAvailable()} reale,
     * cioe' quello che arriva quando il WiFi torna dopo essere stato assente.
     *
     * @param cm ConnectivityManager di sistema.
     * @return {@code true} se almeno una rete registrata espone NET_CAPABILITY_INTERNET.
     */
    private boolean hasInternetNetwork(ConnectivityManager cm) {
        Network[] networks = cm.getAllNetworks();
        if (networks == null) return false;
        for (Network network : networks) {
            NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
            if (capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @brief Deregistra il {@link ConnectivityManager.NetworkCallback}.
     *
     * Chiamato in {@link #onDestroy()} per evitare memory leak.
     * Azzera {@link #mNetworkCallback} dopo la deregistrazione.
     */
    private void unregisterNetworkCallback() {
        if (mNetworkCallback != null) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                try {
                    cm.unregisterNetworkCallback(mNetworkCallback);
                    Log.d(TAG, "NetworkCallback deregistrato");
                } catch (Exception e) {
                    Log.e(TAG, "unregisterNetworkCallback: " + e.getMessage());
                }
            }
            mNetworkCallback = null;
        }
    }

    // -------------------------------------------------------------------------
    // Notifica foreground
    // -------------------------------------------------------------------------

    /**
     * @brief Crea o aggiorna la notifica foreground con titolo e testo specificati.
     *
     * Centralizza la costruzione della notifica per evitare duplicazioni tra
     * {@link #onCreate()}, {@link HumlaObserver#onConnected()} e
     * {@link HumlaObserver#onDisconnected(HumlaException)}.
     *
     * Se il servizio è già foreground, chiamare {@link #startForeground(int, Notification)}
     * con lo stesso ID aggiorna silenziosamente la notifica esistente senza effetti collaterali.
     *
     * Il canale di notifica viene ricreato in modo idempotente (Android ignora la chiamata
     * se il canale esiste già) come misura di sicurezza nel caso in cui {@link com.doorphone.app.MyApp}
     * non lo avesse ancora creato.
     *
     * @param title Titolo della notifica (prima riga, in grassetto).
     * @param text  Testo della notifica (seconda riga).
     */
    private void showForegroundNotification(String title, String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(
                        new NotificationChannel(MyApp.CHANNEL_ID, MyApp.CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
                );
            }
        }
        Intent notificationIntent = new Intent(getApplicationContext(), DoorPhoneActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                getApplicationContext(), 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new NotificationCompat.Builder(getApplicationContext(), MyApp.CHANNEL_ID)
                .setContentTitle(title)
                .setSmallIcon(R.drawable.ic_action_save)
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .build();
        startForeground(1, notification);
    }

    // -------------------------------------------------------------------------
    // Schermo e notifiche
    // -------------------------------------------------------------------------

    /**
     * @brief Risveglia lo schermo del dispositivo tramite WakeLock temporaneo.
     *
     * Acquisisce un WakeLock con flag {@link PowerManager#ACQUIRE_CAUSES_WAKEUP} per
     * accendere lo schermo anche se il dispositivo è in sleep.
     * Il WakeLock viene rilasciato automaticamente dopo 3 secondi.
     *
     * Usato quando arriva una chiamata in ingresso da DoorPi con schermo spento.
     */
    private void wakeupScreen() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager == null) return;
            PowerManager.WakeLock fullWakeLock = powerManager.newWakeLock((PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP), "MyAPP:LOCK");
            fullWakeLock.acquire(3000);
        } catch (Exception e) {
            Log.d(TAG, "wakeupScreen:" +  e.getMessage());
        }
    }

    /**
     * @brief Gestisce l'arrivo di una chiamata da DoorPi (messaggio "cmd-ring").
     *
     * - Se lo schermo è spento, lo risveglia tramite {@link #wakeupScreen()}.
     * - Lancia {@link VideoVLCActivity} con extra {@code ring=true}: l'Activity gestisce
     *   il ring nel proprio {@code onNewIntent()}/{@code onResume()}. In questo modo il
     *   trigger arriva anche se l'Activity era stoppata (schermo bloccato), perche' viene
     *   prima portata in foreground e solo poi consuma l'extra.
     * - Mantiene anche il LocalBroadcast per compatibilita' col caso in cui l'Activity
     *   sia gia' resumed: il duplicato e' filtrato dal flag {@code ring} dell'Activity.
     */
    void Ring(){
        Log.d(RING_TAG, "[5/Ring] entry, appForeground=" + MyApp.APPisForeground());
        Context ctx = getApplicationContext();
        PowerManager pm = (PowerManager)ctx.getSystemService(Context.POWER_SERVICE);
        boolean isScreenOn = pm == null || pm.isInteractive();
        Log.d(RING_TAG, "[5a/Ring] screenOn=" + isScreenOn);
        if (!isScreenOn){
            Log.d(TAG, "WAKEUP SCREEN" );
            wakeupScreen();
        }

        /*
         * Lancia VideoVLCActivity SOLO se l'app non e' gia' in foreground.
         * Su Android 7.1, startActivity con REORDER_TO_FRONT su una Activity gia'
         * top puo' causare un rebind transitorio della SurfaceView del video,
         * generando glitch visibili sul flusso RTSP. Quando l'app e' gia' visibile
         * il LocalBroadcast e' sufficiente: il messageReceiver e' registrato e
         * triggerRing() partira' senza dover toccare il task stack.
         */
        if (!MyApp.APPisForeground()) {
            try {
                Intent activityIntent = new Intent(this, VideoVLCActivity.class);
                activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                activityIntent.putExtra(VideoVLCActivity.EXTRA_RING, true);
                startActivity(activityIntent);
                Log.d(RING_TAG, "[5b/Ring] startActivity OK con extra ring=true");
            } catch (Exception ex) {
                Log.e(RING_TAG, "[5b/Ring] startActivity FAILED: " + ex.getMessage(), ex);
            }
        } else {
            Log.d(RING_TAG, "[5b/Ring] app gia' in foreground -> skip startActivity (evita glitch SurfaceView)");
        }

        /*
         * Doppia consegna del segnale ring:
         *   1) immediato: copre il caso Activity gia' resumed (riceiver registrato)
         *   2) ritardato 1500 ms: copre lo schermo-spento. Tra wakeupScreen e
         *      l'onResume passano ~400 ms (misurato in logcat su Nexus 7), durante
         *      i quali il receiver e' ancora deregistrato e il broadcast immediato
         *      finisce nel vuoto. Il replay garantisce che almeno il secondo
         *      tentativo trovi il receiver attivo. triggerRing() e' idempotente
         *      grazie al guard su "ring", quindi nessun doppio start.
         */
        Intent intent = new Intent("my-message");
        intent.putExtra("message", "ring");
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        Log.d(RING_TAG, "[5c/Ring] LocalBroadcast 'my-message' inviato (immediato)");

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Intent retry = new Intent("my-message");
            retry.putExtra("message", "ring");
            LocalBroadcastManager.getInstance(DoorPhoneService.this).sendBroadcast(retry);
            Log.d(RING_TAG, "[5d/Ring] LocalBroadcast 'my-message' inviato (retry +1500ms)");
        }, 1500);
    }

    /**
     * @brief Notifica l'Activity dell'aggiornamento dello stato della connessione Mumble.
     *
     * - Se lo schermo è spento, lo risveglia (necessario per visualizzare lo stato
     *   anche quando il dispositivo era in sleep durante la riconnessione).
     * - Invia il broadcast locale con il campo "status" all'Activity per aggiornare
     *   il colore della ActionBar (verde = connesso, rosso = disconnesso).
     *
     * @param status Stringa di stato da mostrare (es. "Connected", "Disconnected").
     */
    void UpdateClientStatus(String status){
        Context ctx = getApplicationContext();
        PowerManager pm = (PowerManager)ctx.getSystemService(Context.POWER_SERVICE);
        boolean isScreenOn = pm == null || pm.isInteractive();

        if (!isScreenOn){
            Log.d(TAG, "WAKEUP SCREEN" );
            wakeupScreen();
        }

        Log.d(TAG, "UpdateClientStatus:" + status);

        Intent intent = new Intent("my-message");
        intent.putExtra("status", status);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }
}
