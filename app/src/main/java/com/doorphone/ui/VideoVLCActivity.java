package com.doorphone.ui;

import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Html;
import android.text.InputType;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.alexvas.rtsp.widget.RtspStatusListener;
import com.alexvas.rtsp.widget.RtspSurfaceView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

import com.doorphone.R;
import com.doorphone.Settings;
import com.doorphone.app.MyApp;
import com.doorphone.callbacks.PostDataCallback;
import com.doorphone.preference.Preferences;
import com.doorphone.screenoff.ScreenOffAdminReceiver;
import com.doorphone.service.DoorPhoneService;
import com.doorphone.util.AboutActivity;
import com.doorphone.util.CommandSubmitter;
import com.doorphone.util.Sounds;

/**
 * @brief Activity principale dell'applicazione DoorPhone per il controllo del videocitofono DoorPi.
 *
 * Gestisce:
 * - Lo streaming video RTSP tramite {@link RtspSurfaceView} (libreria alexvas/rtsp-client-android).
 * - La comunicazione vocale Mumble tramite {@link DoorPhoneService} (binding al servizio).
 * - Il ciclo di vita della chiamata in ingresso (ring → accept/reject → close).
 * - L'apertura della porta tramite HTTP REST verso DoorPi ({@link CommandSubmitter}).
 * - Il blocco schermo automatico dopo timeout o fine chiamata.
 *
 * Implementa {@link PostDataCallback} per ricevere la risposta HTTP del comando di apertura porta.
 *
 * @note Dispositivo target: Nexus 7 2012 (grouper), Android 7.1.2 (API 25), rootato.
 *       L'app è un kiosk dedicato, non è distribuita sul Play Store.
 */
@SuppressWarnings("deprecation")
public class VideoVLCActivity extends AppCompatActivity implements PostDataCallback {

    /** @brief Tag per i log Logcat. */
    private static final String TAG = VideoVLCActivity.class.getSimpleName();

    /**
     * @brief Tag dedicato per i log diagnostici del flusso ring.
     *
     * Usato al posto di {@link #TAG} sui log relativi alla gestione del ring per
     * permettere il filtro preciso in Android Studio Logcat con {@code tag:RING_DBG}.
     */
    private static final String RING_TAG = "RING_DBG";

    /**
     * @brief Extra di Intent usato da {@code DoorPhoneService.Ring()} per segnalare un ring in arrivo.
     *
     * Sostituisce il vecchio percorso basato solo su {@code LocalBroadcast}: con lo schermo
     * spento l'Activity era stoppata e il receiver deregistrato, quindi il broadcast veniva
     * perso. Passando il ring come extra dell'Intent di start, l'Activity entra prima in
     * {@code onResume()} e poi consuma il segnale, anche partendo da Activity stoppata.
     */
    public static final String EXTRA_RING = "ring";

    /** @brief URL dell'endpoint RTSP della telecamera, letto da {@link Settings}. */
    private String mMediaUrl;


    /** @brief Gestore dell'audio di sistema (usato per il controllo del volume). */
    private AudioManager mAudioManager;

    /** @brief FAB per accettare/terminare la chiamata vocale. */
    private FloatingActionButton mCallButton;

    /** @brief FAB per inviare il comando di apertura porta a DoorPi. */
    private FloatingActionButton mUnlockButton;

    /** @brief Overlay circolare visibile quando la status bar di sistema e' nascosta. */
    private TextView mConnectionStatusOverlay;

    /**
     * @brief Stato del pulsante di sblocco.
     * {@code true} se la chiamata è stata accettata e l'utente è in comunicazione.
     */
    private boolean mUnlockButtonStatus;

    /** @brief Gestore dei suoni di sistema (ring, conferma apertura porta). */
    private Sounds mySounds;

    /**
     * @brief Indica se c'è una chiamata in ingresso attiva (ring ricevuto, non ancora accettato).
     * Usato per decidere se mostrare il {@link #mCallButton} e per gestire il timeout.
     */
    private boolean mIncomingCall = false;

    /**
     * @brief Indica se il suoneria è attualmente attiva.
     * Gestisce il loop del suono tramite {@link #runnable_repeat_ring}.
     */
    private boolean ring = false;

    /** @brief Vista RTSP che gestisce internamente connessione TCP, demux RTP e decode H.264/H.265. */
    private RtspSurfaceView mRtspSurfaceView;

    /**
     * @brief Rotazione video memorizzata all'avvio.
     * Confrontata in {@link #onResume()} per rilevare cambi di configurazione che richiedono
     * {@link #recreate()}.
     */
    private int mLastKnownRotation = -1;


    /** @brief Riferimento al servizio Mumble, ottenuto tramite binding in {@link #mConnection}. */
    DoorPhoneService mService;

    /** @brief Preferenze dell'applicazione (singleton). */
    private Settings mSettings;

    /**
     * @brief Modalita' kiosk comandata dal JSON del Raspberry.
     *
     * Default false: il tablet resta manutenzionabile se il server non invia il flag.
     * Quando true abilita i blocchi "best effort" compatibili con Android 7.1:
     * Back disabilitato, ritorno task in foreground e lock screen a fine chiamata.
     * Non usa screen pinning/LockTask per evitare il messaggio di sistema
     * "To unpin this screen..." dopo un riavvio davanti all'inquilino.
     */
    private boolean mKioskMode;

    /**
     * @brief Se true, in kiosk nasconde anche la status bar superiore.
     *
     * Valore opzionale ricevuto dal Raspberry con {@code hide_status_bar}.
     * Default false per mantenere visibili ora, Wi-Fi e batteria durante l'uso normale.
     */
    private boolean mHideStatusBarInKiosk;

    /**
     * @brief Stato sintetico della connessione Mumble mostrato nell'overlay.
     *
     * Parte da false: finche' il servizio non invia uno stato esplicito, l'overlay
     * preferisce indicare KO invece di dare un falso verde.
     */
    private boolean mConnectionOk = false;

    /** @brief {@code true} se il binding con {@link DoorPhoneService} è attivo. */
    boolean isBound = false;

    /** @brief {@code true} se {@link #onStop()} ha fatto unbind e {@link #onResume()} deve rifarlo. */
    private boolean mNeedsRebind = false;

    // La password di accesso al menu impostazioni non e' piu' hardcoded:
    // viene letta da Settings.getSettingsPassword() (fornita dal config DoorPi).

    /** @brief ActionBar dell'activity, usata per mostrare lo stato della connessione Mumble. */
    private ActionBar actionBar;

    /**
     * @brief Timestamp (ms) dell'ultimo click sul pulsante call, per debounce.
     * Impedisce che un doppio-tap involontario accetti e chiuda la chiamata in rapida successione.
     */
    private long mLastCallButtonClickMs = 0;

    /** @brief Intervallo minimo in ms tra due click consecutivi sul pulsante call. */
    private static final long CALL_BUTTON_DEBOUNCE_MS = 1500;

    /** @brief Handler condiviso per i timeout ACK (accept-call e close-call). */
    private final Handler handler_ack_timeout = new Handler(Looper.getMainLooper());

    /** @brief Runnable del timeout ACK per accept-call: logga warning se ACK non arriva entro 10s. */
    private final Runnable runnable_ack_accept_timeout = new Runnable() {
        @Override
        public void run() {
            Log.w(TAG, "ACK accept-call non ricevuto entro 10s");
        }
    };

    /** @brief Runnable del timeout ACK per close-call: logga warning se ACK non arriva entro 10s. */
    private final Runnable runnable_ack_close_timeout = new Runnable() {
        @Override
        public void run() {
            Log.w(TAG, "ACK close-call non ricevuto entro 10s");
        }
    };

    /**
     * @brief Handler per il mute differito post close-call.
     *
     * Separato da {@link #handler_ack_timeout} perché NON deve essere cancellato in
     * {@link #onPause()}: il deafen deve avvenire anche se l'activity entra in pausa
     * a causa del {@code lockNow()} che segue la chiusura della chiamata.
     * Viene cancellato in {@link #onStop()} al teardown dell'activity.
     */
    private final Handler handler_deferred_mute = new Handler(Looper.getMainLooper());

    /**
     * @brief Runnable che esegue il mute+deafen differito dopo la chiusura della chiamata.
     *
     * Armato in {@link #closeCall(boolean)} con un ritardo di 1500ms.
     * Cancellato da {@link #messageReceiver} se l'ACK arriva prima dello scadere
     * del timer (in quel caso il mute è già stato eseguito immediatamente).
     * Se l'ACK non arriva entro 1500ms, questo runnable esegue il mute come fallback.
     */
    private final Runnable runnable_deferred_mute = new Runnable() {
        @Override
        public void run() {
            try {
                Log.d(TAG, "deferred mute: fallback (ACK non ricevuto entro 1500ms)");
                // I4: il timer puo' scattare dopo l'unbind del servizio (binder gia'
                // azzerato in onServiceDisconnected): guard esplicito invece di
                // affidarsi al catch dell'NPE.
                if (mService != null) {
                    mService.closeCall();
                }
            } catch (Exception ex) {
                Log.d(TAG, "deferred mute: " + ex.getMessage());
            }
        }
    };

    /**
     * @brief M7: auto-chiusura della chiamata 3s dopo l'apertura porta (quando
     * {@code isAutoCloseOnUnlock}). Runnable NOMINATO — non lambda anonima — cosi'
     * e' cancellabile via {@code removeCallbacks} al teardown ({@link #onStop()}),
     * evitando il "timer fantasma" che chiamava {@link #closeCall()} su un'activity
     * gia' in pausa/distrutta. Il guard {@code isFinishing()/isDestroyed()} protegge
     * comunque il caso in cui il runnable scatti durante la chiusura.
     */
    private final Runnable runnable_auto_close_on_unlock = new Runnable() {
        @Override
        public void run() {
            if (!isFinishing() && !isDestroyed()) {
                closeCall();
            }
        }
    };

    /**
     * @brief Handler per il timeout del ring.
     * Avvia {@link #runnable_ring_timeout} se la chiamata non viene accettata entro 50 s.
     */
    final Handler handler_ring_timeout = new Handler(Looper.getMainLooper());

    /**
     * @brief Handler per il loop del suono di ring.
     * Ripete {@link #runnable_repeat_ring} ogni 5 s finché {@link #ring} è {@code true}.
     */
    final Handler handler_repeat_ring = new Handler(Looper.getMainLooper());

    /**
     * @brief Handler per il timeout di foreground.
     * Chiude la chiamata automaticamente dopo 80 s se l'utente non interagisce.
     */
    final Handler handler_foreground_timeout = new Handler(Looper.getMainLooper());

    /** @brief ProgressBar mostrata SOLO durante il reload dello stream RTSP dopo resume da pausa. */
    private ProgressBar progressBar;

    /**
     * @brief Flag per mostrare il loader solo quando torniamo da una pausa effettiva.
     *
     * Settato a true in {@link #onPause()} e consumato in {@link #onResume()} per
     * decidere se mostrare il loader durante il reload del video. Al primissimo avvio
     * dopo onCreate il flag e' false, quindi nessun loader (stato iniziale "pulito").
     * Dopo ogni screen-off/screen-on il flag e' true, quindi il loader copre il
     * tempo di ricontrattazione dello stream RTSP.
     */
    private boolean mResumingFromPause = false;

    /**
     * @brief {@code true} mentre è in corso uno stop volontario dello stream RTSP.
     *
     * Settato in {@link #stopRtspStream()} (chiamato da {@link #onPause()}) e azzerato
     * in {@link #startRtspStream()}. Quando lo stream viene fermato durante l'handshake
     * TCP (pausa rapida ~40ms dopo lo start), il thread RTSP riceve EOF e la libreria
     * propaga {@code onRtspStatusFailed("Invalid status code -1")}: NON è un errore reale
     * del server ma l'effetto del teardown volontario. Il flag permette a
     * {@link RtspStatusListener#onRtspStatusFailed(String)} di declassare quel log da
     * {@code E/} a {@code D/} senza nascondere i fallimenti RTSP veri (camera irraggiungibile
     * a schermo acceso e stabile, dove non c'è stato alcuno stop volontario).
     *
     * {@code volatile}: settato sull'UI thread ma la callback può essere valutata dopo un
     * {@code uiHandler.post} interno alla libreria.
     */
    private volatile boolean mStoppingIntentionally = false;

    /**
     * @brief Handler per il timeout di sicurezza del loader.
     *
     * Safety net: se le callback RTSP non scattano (stream gia' avviato, errori
     * silenziosi, codec bloccato), il loader rimarrebbe appeso per sempre.
     * Dopo {@link #LOADER_TIMEOUT_MS} forziamo l'hide.
     */
    private final Handler handler_loader_timeout = new Handler(Looper.getMainLooper());

    /** @brief Timeout di sicurezza del loader in ms. */
    private static final long LOADER_TIMEOUT_MS = 10_000L;

    /** @brief Runnable che forza la chiusura del loader allo scadere del timeout. */
    private final Runnable runnable_loader_timeout = new Runnable() {
        @Override
        public void run() {
            Log.w(TAG, "loader timeout: forzo hideLoader()");
            hideLoader();
        }
    };

    /**
     * @brief Runnable eseguito allo scadere del timeout del ring (50 s).
     * Chiama {@link #closeCall()} per terminare la chiamata non risposta.
     */
    Runnable runnable_ring_timeout = new Runnable() {
        public void run() {
            Log.d(TAG, "runnable_ring_timeout");
            closeCall();
        }
    };

    /**
     * @brief Runnable eseguito allo scadere del timeout di foreground (80 s).
     * Chiama {@link #closeCall()} per terminare la sessione se l'utente non interagisce.
     */
    Runnable runnable_foreground_timeout = new Runnable() {
        public void run() {
            Log.d(TAG, "runnable_foreground_timeout");
            closeCall();
        }
    };

    /**
     * @brief Runnable che ripete il suono di ring ogni 5 secondi.
     * Si auto-riprogramma tramite {@link #handler_repeat_ring} finché {@link #ring} è {@code true}.
     */
    private Runnable runnable_repeat_ring = new Runnable() {
        @Override
        public void run() {
            handler_repeat_ring.postDelayed(runnable_repeat_ring, 5000);
            try {
                if (ring) {
                    Log.d(TAG, "----- RING ----");
                    mySounds.playSound(mySounds.getSound2());
                } else {
                    mySounds.StopSound(mySounds.getSound2());
                }
            } catch (Exception ex) {
                Log.e(TAG, "repeat_ring error: " + ex);
            }
        }
    };

    // -------------------------------------------------------------------------
    // Ciclo di vita Activity
    // -------------------------------------------------------------------------

    /**
     * @brief Inizializzazione dell'activity.
     *
     * - Imposta i flag per mostrare la schermata anche quando il dispositivo è bloccato.
     * - Carica le impostazioni e configura la ActionBar con il nome del piano configurato.
     * - Inizializza l'UI e avvia il binding con {@link DoorPhoneService}.
     *
     * @param savedInstanceState Bundle con lo stato salvato (non usato).
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent inCreate = getIntent();
        Log.d(TAG, "ON CREATE");
        Log.d(RING_TAG, "[lifecycle/onCreate] intent=" + inCreate
                + " hasRingExtra=" + (inCreate != null && inCreate.getBooleanExtra(EXTRA_RING, false)));

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

        mSettings = Settings.getInstance(this);
        mLastKnownRotation = mSettings.getVideoRotation();
        mKioskMode = mSettings.isKioskModeEnabled();
        mHideStatusBarInKiosk = mSettings.shouldHideStatusBarInKiosk();

        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        GetOptions();

        String piano = mSettings.getDoorPiPiano();
        actionBar = getSupportActionBar();
        ColorDrawable colorDrawable = new ColorDrawable(Color.parseColor("#15ff00"));
        if (actionBar != null) {
            actionBar.setBackgroundDrawable(colorDrawable);
            actionBar.setTitle(Html.fromHtml("<font color=\"black\">" + piano + "</font>"));
        }

        mAudioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        mMediaUrl = mSettings.getCameraEndPoint();
        mySounds = Sounds.getInstance(this, null);

        setContentView(com.doorphone.R.layout.activity_video_vlc);
        initUI();
        applyKioskModeIfNeeded();

        Log.d(TAG, "START BOUND WITH DOORPHONE SERVICE");
        Intent intent = new Intent(this, DoorPhoneService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    /**
     * @brief Chiamato quando l'activity diventa visibile.
     * Non esegue operazioni particolari: lo stream RTSP viene avviato in {@link #onResume()}.
     */
    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "ON START");
        // Registrato in onStart e deregistrato in onStop: coppia simmetrica.
        // (Prima era in onResume → un ciclo onPause/onResume senza onStop lo registrava due volte.)
        LocalBroadcastManager.getInstance(this).registerReceiver(messageReceiver, new IntentFilter("my-message"));
    }

    /**
     * @brief Chiamato quando l'activity torna in primo piano e interattiva.
     *
     * - Rileva cambi di rotazione video e ricrea l'activity se necessario.
     * - Aggiorna l'URL della telecamera dalle preferenze.
     * - Avvia lo stream RTSP.
     * - Registra il {@link BroadcastReceiver} per i messaggi dal servizio Mumble.
     * - Avvia il timeout di foreground (80 s) se non c'è una chiamata attiva.
     */
    @Override
    public void onResume() {
        super.onResume();
        Intent inResume = getIntent();
        Log.d(TAG, "ON RESUME");
        Log.d(RING_TAG, "[lifecycle/onResume] intent=" + inResume
                + " hasRingExtra=" + (inResume != null && inResume.getBooleanExtra(EXTRA_RING, false)));

        int currentRotation = mSettings.getVideoRotation();
        if (currentRotation != mLastKnownRotation) {
            mLastKnownRotation = currentRotation;
            recreate();
            return;
        }

        GetOptions();
        applyKioskModeIfNeeded();
        invalidateOptionsMenu();
        if (mResumingFromPause) {
            // Reload del video dopo screen-off / pausa: mostra il loader fino al
            // primo frame (vedi onRtspFirstFrameRendered) o errore RTSP.
            showLoader();
            mResumingFromPause = false;
        }
        startRtspStream();

        consumeRingExtra(getIntent());

        if (!mIncomingCall && !ring) {
            handler_foreground_timeout.postDelayed(runnable_foreground_timeout, 80000);
        }

        if (mNeedsRebind) {
            mNeedsRebind = false;
            bindService(new Intent(this, DoorPhoneService.class), mConnection, Context.BIND_AUTO_CREATE);
        }
    }

    /**
     * @brief Chiamato quando l'activity perde il focus ma è ancora visibile.
     *
     * - Ferma lo stream RTSP.
     * - Cancella tutti i Runnable in attesa (ring timeout, repeat ring, foreground timeout).
     *
     * Il {@link BroadcastReceiver} NON viene deregistrato qui: viene deregistrato in
     * {@link #onStop()} per permettere la ricezione degli ACK (ack-accept-call, ack-close-call)
     * anche durante la breve finestra tra {@code lockNow()} e la scomparsa effettiva dell'activity.
     */
    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "ON PAUSE");
        mResumingFromPause = true;
        stopRtspStream();

        // Il ring timeout deve scattare anche con schermo spento: il WakeLock di wakeupScreen
        // dura 3s, molto meno dei 50s del ring timeout. Se cancelliamo il timeout qui,
        // cmd_close_call non viene mai inviato automaticamente quando nessuno risponde.
        if (!ring && !mIncomingCall && !mUnlockButtonStatus) {
            handler_ring_timeout.removeCallbacks(runnable_ring_timeout);
            handler_repeat_ring.removeCallbacks(runnable_repeat_ring);
        }
        handler_foreground_timeout.removeCallbacks(runnable_foreground_timeout);
        handler_loader_timeout.removeCallbacks(runnable_loader_timeout);
        handler_ack_timeout.removeCallbacksAndMessages(null);
    }

    /**
     * @brief Chiamato quando l'activity non è più visibile.
     *
     * - Deregistra il {@link BroadcastReceiver} (NON fatto in onPause per evitare la race
     *   condition con gli ACK: dopo {@code lockNow()} l'activity è in pausa ma non ancora
     *   fermata, e gli ACK del Pi arrivano in quei ~200ms).
     * - NON chiama {@link #stopRtspStream()}: {@link #onPause()} è sempre eseguito prima di
     *   {@code onStop()} e garantisce che lo stream sia già fermo quando la Surface è ancora
     *   valida. Chiamare {@code stop()} qui causerebbe {@code cancelBuffer} su una Surface
     *   già distrutta dal sistema (spam SurfaceFlinger sul codec OMX.Nvidia.h264.decode).
     * - Sposta il task in primo piano nell'app switcher per impedire che appaia nella lista
     *   delle app recenti (comportamento kiosk).
     * - Esegue l'unbinding da {@link DoorPhoneService}.
     */
    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "ON STOP");
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(messageReceiver);
        } catch (Exception ex) {
            Log.d(TAG, "onStop unregisterReceiver: " + ex.getMessage());
        }
        handler_deferred_mute.removeCallbacks(runnable_deferred_mute);
        handler_ring_timeout.removeCallbacks(runnable_auto_close_on_unlock);  // M7
        disable_recent_app();
        if (isBound) {
            try {
                unbindService(mConnection);
            } catch (Exception ex) {
                Log.d(TAG, "onStop error: " + ex.getMessage());
            } finally {
                isBound = false;
                mNeedsRebind = true;
            }
        }
    }

    /**
     * @brief Chiamato prima della distruzione dell'activity.
     *
     * - NON chiama {@link #stopRtspStream()}: a questo punto la Surface è già stata rilasciata
     *   da Android e il decoder MediaCodec tenterebbe di restituire buffer pendenti su una
     *   Surface non più valida, generando {@code cancelBuffer} spam su SurfaceFlinger.
     *   Lo stream è già fermo grazie a {@link #onPause()}, che precede sempre {@code onDestroy()}.
     * - Rilascia le risorse audio di {@link Sounds}.
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "ON DESTROY");
        if (mySounds != null) mySounds.unprepare();
    }

    // -------------------------------------------------------------------------
    // Callback PostDataCallback (risposta HTTP DoorPi)
    // -------------------------------------------------------------------------

    /**
     * @brief Callback invocata da {@link CommandSubmitter} quando la risposta HTTP è ricevuta.
     *
     * Analizza il JSON di risposta di DoorPi:
     * - Se contiene {@code "unlock_door"} con il piano dell'utente corrente → conferma visiva verde
     *   per 10 s (porta aperta correttamente).
     * - Altrimenti → reset del pulsante dopo 5 s (porta aperta su altro piano o errore logico).
     *
     * @param json Stringa JSON ricevuta dal server DoorPi.
     */
    @Override
    public void onDataReceived(String json) {
        runOnUiThread(() -> {
            try {
                hideLoader();
                String piano = mSettings.getDoorPiPiano();
                JSONObject jsonObject = new JSONObject(json);

                if (jsonObject.has("unlock_door")) {
                    String unlockDoorValue = jsonObject.getString("unlock_door");
                    if (unlockDoorValue.equals(piano)) {
                        if (mUnlockButton != null && !isFinishing()) {
                            mUnlockButton.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#59981A")));
                            resetUnlockButton(10000);
                            if (mSettings.isAutoCloseOnUnlock()) {
                                // M7: runnable nominato e cancellabile (vedi onStop), non lambda fantasma
                                handler_ring_timeout.removeCallbacks(runnable_auto_close_on_unlock);
                                handler_ring_timeout.postDelayed(runnable_auto_close_on_unlock, 3000);
                            }
                        }
                    } else {
                        resetUnlockButton(5000);
                    }
                } else {
                    resetUnlockButton(5000);
                }
            } catch (JSONException e) {
                resetUnlockButton(5000);
            } catch (Exception e) {
                Log.e(TAG, "onDataReceived error: " + e.getMessage());
                resetUnlockButton(5000);
            }
        });
    }

    /**
     * @brief Callback invocata da {@link CommandSubmitter} in caso di errore HTTP.
     *
     * Mostra un Toast con il messaggio di errore e ripristina il pulsante dopo 5 s.
     *
     * @param errorMessage Descrizione dell'errore.
     */
    @Override
    public void onError(String errorMessage) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            hideLoader();
            Toast.makeText(this, "Error: " + errorMessage, Toast.LENGTH_LONG).show();
            resetUnlockButton(5000);
        });
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    /**
     * @brief Mostra la ProgressBar sul thread UI.
     * Chiamata prima di inviare una richiesta HTTP a DoorPi o durante la connessione RTSP.
     */
    private void showLoader() {
        runOnUiThread(() -> {
            if (progressBar != null && !isFinishing() && !isDestroyed()) {
                progressBar.setVisibility(View.VISIBLE);
                // Safety net: arma il timeout. Se le callback RTSP non scattano
                // (stream gia' avviato, errori silenziosi), il loader si chiude
                // comunque dopo LOADER_TIMEOUT_MS evitando il "loader infinito".
                handler_loader_timeout.removeCallbacks(runnable_loader_timeout);
                handler_loader_timeout.postDelayed(runnable_loader_timeout, LOADER_TIMEOUT_MS);
            }
        });
    }

    /**
     * @brief Nasconde la ProgressBar sul thread UI.
     * Chiamata al completamento (successo o errore) della richiesta HTTP o della connessione RTSP.
     */
    private void hideLoader() {
        runOnUiThread(() -> {
            handler_loader_timeout.removeCallbacks(runnable_loader_timeout);
            if (progressBar != null && !isFinishing() && !isDestroyed()) {
                progressBar.setVisibility(View.GONE);
            }
        });
    }

    /**
     * @brief Ripristina il pulsante di sblocco al colore e stato originali dopo un ritardo.
     *
     * @param delayMs Ritardo in millisecondi prima del ripristino.
     */
    private void resetUnlockButton(long delayMs) {
        if (mUnlockButton == null || isFinishing() || isDestroyed()) return;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (mUnlockButton != null && !isFinishing() && !isDestroyed()) {
                mUnlockButton.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#3F51B5")));
                mUnlockButton.setEnabled(true);
            }
        }, delayMs);
    }

    // -------------------------------------------------------------------------
    // Binding con DoorPhoneService
    // -------------------------------------------------------------------------

    /**
     * @brief Connessione al servizio {@link DoorPhoneService}.
     *
     * - {@link ServiceConnection#onServiceConnected}: ottiene il riferimento al servizio e,
     *   se non c'è una chiamata attiva E la connessione TCP Mumble è già stabilita,
     *   chiama {@link DoorPhoneService#closeCall()} per assicurarsi che il microfono sia mutato.
     *   Il guard {@code isConnected()} evita il NPE su {@code HumlaConnection.sendTCPMessage()}
     *   quando il bind avviene prima che la connessione TCP sia pronta (es. durante la
     *   generazione/migrazione del certificato client).
     * - {@link ServiceConnection#onServiceDisconnected}: azzera il flag {@link #isBound}.
     */
    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
            // I4: azzera il riferimento al binder stantio (coerente con
            // DoorPhoneActivity). I callback differiti che chiamano mService
            // (runnable_deferred_mute, ack-close-call) verificano mService != null.
            mService = null;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            try {
                mService = (DoorPhoneService) ((DoorPhoneService.DoorPhoneBinder) service).getService();
                isBound = true;
                if (!ring && !mService.isDoorCallActive() && mService.isConnected()) {
                    mService.closeCall();
                }
                mConnectionOk = mService.isConnected();
                updateConnectionStatusOverlay();
            } catch (ClassCastException e) {
                Log.e(TAG, "onServiceConnected: binder cast failed: " + e.getMessage());
                isBound = false;
            }
        }
    };

    // -------------------------------------------------------------------------
    // Preferenze e opzioni
    // -------------------------------------------------------------------------

    /**
     * @brief Aggiorna l'URL della telecamera dalle preferenze condivise.
     * Chiamato in {@link #onCreate(Bundle)} e {@link #onResume()}.
     */
    public void GetOptions() {
        mMediaUrl = mSettings.getCameraEndPoint();
        mKioskMode = mSettings.isKioskModeEnabled();
        mHideStatusBarInKiosk = mSettings.shouldHideStatusBarInKiosk();
    }

    /**
     * @brief Applica la modalita' kiosk solo quando il Raspberry la richiede.
     *
     * Scelta di stabilita'/manutenzione per Nexus 7 Android 7.1: non chiamiamo
     * {@code startLockTask()} perche' Android mostrerebbe il messaggio "To unpin
     * this screen..." al boot. Il flag abilita solo i blocchi leggeri gestiti
     * dall'app: Back disabilitato, ritorno in foreground e lock screen post-chiamata.
     * Nasconde la barra di navigazione inferiore con immersive sticky. La status
     * bar superiore viene nascosta solo se il Raspberry invia hide_status_bar=true.
     */
    private void applyKioskModeIfNeeded() {
        if (!mKioskMode) {
            showSystemBarsForMaintenance();
            Log.d(TAG, "Kiosk mode disabled by Raspberry config/default");
            return;
        }
        hideSystemBarsForKiosk();
        Log.d(TAG, "Kiosk mode enabled: lightweight mode active");
    }

    /**
     * @brief Nasconde i pulsanti software inferiori e, se configurato, la status bar.
     *
     * Usa {@link View#SYSTEM_UI_FLAG_IMMERSIVE_STICKY}, disponibile su Android 4.4+:
     * sul Nexus 7 Android 7.1 la barra puo' riapparire temporaneamente con uno swipe
     * dal bordo, ma Android la nasconde di nuovo dopo pochi secondi o al nuovo focus.
     * Usa {@link View#SYSTEM_UI_FLAG_FULLSCREEN} solo quando il Raspberry invia
     * {@code hide_status_bar=true}: di default la barra superiore resta visibile.
     */
    private void hideSystemBarsForKiosk() {
        View decorView = getWindow().getDecorView();
        int flags = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        if (mHideStatusBarInKiosk) {
            flags |= View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        }
        decorView.setSystemUiVisibility(flags);
        updateConnectionStatusOverlay();
    }

    /**
     * @brief Ripristina barre di sistema quando kiosk e' disabilitato da config.
     *
     * Serve per la manutenzione: con {@code kiosk=false} i tasti software tornano
     * visibili e il tablet resta gestibile senza swipe nascosti o adb.
     */
    private void showSystemBarsForMaintenance() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        updateConnectionStatusOverlay();
    }

    /**
     * @brief Aggiorna il cerchio stato quando la status bar superiore e' nascosta.
     *
     * L'overlay sostituisce l'informazione rapida normalmente data dalla barra
     * superiore/ActionBar: verde con testo nero se la connessione e' OK, rosso
     * con testo bianco se e' KO. Il testo e' il piano/device configurato (P1, P2...).
     */
    private void updateConnectionStatusOverlay() {
        if (mConnectionStatusOverlay == null) return;

        boolean visible = mKioskMode && mHideStatusBarInKiosk;
        mConnectionStatusOverlay.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) return;

        String piano = mSettings != null ? mSettings.getDoorPiPiano() : "";
        if (piano == null || piano.trim().length() == 0) {
            piano = Settings.DEFAULT_PREF_DOORPI_PIANO;
        }

        mConnectionStatusOverlay.setText(piano.trim());
        mConnectionStatusOverlay.setTextColor(mConnectionOk ? Color.BLACK : Color.WHITE);
        mConnectionStatusOverlay.setBackgroundTintList(ColorStateList.valueOf(
                Color.parseColor(mConnectionOk ? "#15ff00" : "#ff0033")));
    }

    // -------------------------------------------------------------------------
    // BroadcastReceiver messaggi da DoorPhoneService
    // -------------------------------------------------------------------------

    /**
     * @brief Ricevitore dei messaggi LocalBroadcast inviati da {@link DoorPhoneService}.
     *
     * Gestisce due tipi di extra:
     *
     * - {@code "message" = "ring"}: chiamata in ingresso da DoorPi.
     *   Attiva il suono, porta l'activity in foreground, mostra il pulsante call e
     *   avvia il timeout del ring (50 s).
     *
     * - {@code "status"}: aggiornamento dello stato della connessione Mumble.
     *   Aggiorna la ActionBar: rosso se disconnesso, verde se connesso.
     */
    private BroadcastReceiver messageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String message = intent.getStringExtra("message");
            Log.d(RING_TAG, "[6/receiver] onReceive message=" + message);
            if (message != null && message.equals("ring")) {
                Log.d(RING_TAG, "[6a/receiver] -> triggerRing() da broadcast");
                triggerRing();
            } else if ("ack-accept-call".equals(message)) {
                handler_ack_timeout.removeCallbacks(runnable_ack_accept_timeout);
                Log.i(TAG, "ACK accept-call ricevuto - sessione confermata");
            } else if ("ack-close-call".equals(message)) {
                handler_ack_timeout.removeCallbacks(runnable_ack_close_timeout);
                handler_deferred_mute.removeCallbacks(runnable_deferred_mute);
                Log.i(TAG, "ACK close-call ricevuto - mute immediato, timer fallback cancellato");
                try {
                    // I4: guard esplicito sul binder (vedi onServiceDisconnected).
                    if (mService != null) {
                        mService.closeCall();
                    }
                } catch (Exception ex) {
                    Log.d(TAG, "ack-close-call mService.closeCall: " + ex.getMessage());
                }
            }

            String status = intent.getStringExtra("status");
            if (mSettings == null) {
                Log.w(TAG, "messageReceiver: mSettings null, skip");
                return;
            }
            String piano = mSettings.getDoorPiPiano();

            if (status != null) {
                actionBar = getSupportActionBar();
                if (status.toLowerCase(Locale.US).equals("disconnected")) {
                    mConnectionOk = false;
                    if (actionBar != null) {
                        actionBar.setBackgroundDrawable(new ColorDrawable(Color.parseColor("#ff0033")));
                        actionBar.setTitle(Html.fromHtml("<font color=\"white\">" + piano + " " + status + "</font>"));
                    }
                } else {
                    mConnectionOk = true;
                    if (actionBar != null) {
                        actionBar.setBackgroundDrawable(new ColorDrawable(Color.parseColor("#15ff00")));
                        actionBar.setTitle(Html.fromHtml("<font color=\"black\">" + piano + " " + status + "</font>"));
                    }
                }
                updateConnectionStatusOverlay();
            }
        }
    };

    /**
     * @brief Porta l'activity in foreground se l'app è in background.
     *
     * Usato quando arriva un ring mentre lo schermo è spento o l'app non è visibile.
     * Utilizza {@link Intent#FLAG_ACTIVITY_REORDER_TO_FRONT} per evitare di creare
     * una nuova istanza se l'activity è già nello stack.
     *
     * @param context Contesto usato per avviare l'intent.
     */
    public void bringActivityForeground(Context context) {
        if (!MyApp.APPisForeground()) {
            try {
                Intent intent = new Intent(context, VideoVLCActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(intent);
            } catch (Exception ex) {
                Log.e(TAG, "bringActivityForeground error: " + ex.getMessage());
            }
        }
    }

    /**
     * @brief Avvia la suoneria di chiamata in ingresso (idempotente).
     *
     * Estrae la logica precedentemente inline nel {@code messageReceiver} per poter
     * essere chiamata anche da {@link #consumeRingExtra(Intent)} quando il segnale
     * arriva come extra dell'Intent di start (caso schermo bloccato + Activity stoppata).
     *
     * Il guard su {@link #ring} rende il metodo idempotente: ricevere il ring sia via
     * Intent extra che via LocalBroadcast non causa doppio start.
     */
    private void triggerRing() {
        Log.d(RING_TAG, "[7/triggerRing] entry, ring=" + ring
                + " mySounds=" + (mySounds != null)
                + " loaded=" + (mySounds != null && mySounds.isLoaded()));
        // Un ring in corso DEVE liberare la UI dal loader: l'utente ha bisogno di
        // vedere subito il pulsante CALL. Senza questo hide, un ring arrivato
        // mentre era in corso un reload video potrebbe lasciare il loader sopra
        // i pulsanti. Idempotente, no-op se il loader e' gia' nascosto.
        hideLoader();
        if (ring) {
            Log.d(RING_TAG, "[7a/triggerRing] gia' attivo, skip");
            return;
        }
        ring = true;
        if (mySounds != null) {
            Log.d(RING_TAG, "[7b/triggerRing] playSound sound2=" + mySounds.getSound2());
            mySounds.playSound(mySounds.getSound2());
        } else {
            Log.w(RING_TAG, "[7b/triggerRing] mySounds NULL, suono saltato");
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        mIncomingCall = true;
        HandleControlVisibility();
        handler_ring_timeout.postDelayed(runnable_ring_timeout, 50000);
        handler_repeat_ring.post(runnable_repeat_ring);
        Log.d(RING_TAG, "[7c/triggerRing] loop avviato");
    }

    /**
     * @brief Consuma l'extra {@link #EXTRA_RING} se presente nell'Intent.
     *
     * Chiamato da {@link #onResume()} (per coprire l'avvio da Activity stoppata) e
     * da {@link #onNewIntent(Intent)} (per Activity gia' resumed con SINGLE_TOP).
     * Rimuove l'extra dopo il consumo per non riavviare il ring a ogni rotazione/resume.
     *
     * @param intent Intent da ispezionare. Puo' essere null.
     */
    private void consumeRingExtra(Intent intent) {
        boolean has = intent != null && intent.getBooleanExtra(EXTRA_RING, false);
        Log.d(RING_TAG, "[8/consumeRingExtra] intent=" + (intent != null)
                + " hasRingExtra=" + has);
        if (intent == null) return;
        if (!has) return;
        intent.removeExtra(EXTRA_RING);
        Log.d(RING_TAG, "[8a/consumeRingExtra] -> triggerRing() da extra");
        triggerRing();
    }

    /**
     * @brief Gestisce un nuovo Intent quando l'Activity e' gia' istanziata (SINGLE_TOP).
     *
     * Necessario perche' {@code DoorPhoneService.Ring()} usa
     * {@link Intent#FLAG_ACTIVITY_SINGLE_TOP}: se l'Activity e' in cima allo stack,
     * Android riusa l'istanza e consegna l'Intent qui invece di passare per onCreate.
     *
     * @param intent Nuovo Intent ricevuto.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        consumeRingExtra(intent);
    }

    // -------------------------------------------------------------------------
    // Menu opzioni
    // -------------------------------------------------------------------------

    /**
     * @brief Infla il menu principale nell'ActionBar.
     * @param menu Menu da popolare.
     * @return {@code true} per mostrare il menu.
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_menu, menu);
        updateKioskStatusMenuItem(menu.findItem(R.id.nav_drawer_item_kiosk_status));
        return true;
    }

    /**
     * @brief Aggiorna l'icona in ActionBar che indica lo stato kiosk.
     *
     * Il menu resta non cliccabile: serve solo come spia visiva rapida durante
     * manutenzione e collaudo sul Nexus 7. Lucchetto chiuso = kiosk attivo,
     * lucchetto aperto = modalita' manutenzione/non kiosk.
     *
     * @param item Voce menu dedicata allo stato kiosk.
     */
    private void updateKioskStatusMenuItem(MenuItem item) {
        if (item == null) return;
        item.setIcon(mKioskMode ? R.drawable.ic_kiosk_locked : R.drawable.ic_kiosk_unlocked);
        item.setTitle(mKioskMode ? R.string.kiosk_mode_on : R.string.kiosk_mode_off);
    }

    /**
     * @brief Gestisce la selezione delle voci del menu principale.
     *
     * - Impostazioni: richiede autenticazione password prima di aprire {@link Preferences}.
     * - Riavvio: esegue {@code su -c reboot} (dispositivo rootato).
     * - Info: apre {@link AboutActivity}.
     *
     * @param item Voce di menu selezionata.
     * @return Risultato della gestione dell'evento.
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.nav_drawer_item_settings) {
            LayoutInflater li = LayoutInflater.from(this);
            View promptsView = li.inflate(R.layout.prompts, null);
            Authenticate_AndOpenOptionMenu(promptsView, Preferences.class);
        }
        if (id == R.id.nav_drawer_item_reboot) {
            RebootDevice();
        }
        if (id == R.id.nav_drawer_item_about) {
            startActivity(new Intent(this, AboutActivity.class));
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * @brief Riavvia il dispositivo tramite shell root.
     *
     * Esegue {@code su -c reboot}. Funziona perché il Nexus 7 target è rootato.
     * In caso di errore (permessi negati, su non disponibile) viene loggato senza crash.
     */
    private void RebootDevice() {
        // exec + waitFor su thread di background: niente I/O bloccante sul main thread.
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Process proc = Runtime.getRuntime().exec(new String[]{"su", "-c", "reboot"});
                    proc.waitFor();
                } catch (Exception ex) {
                    Log.e(TAG, "Could not Reboot Device: " + ex);
                }
            }
        }, "reboot-su").start();
    }

    /**
     * @brief Mostra un dialog di autenticazione password prima di aprire il menu impostazioni.
     *
     * Se la password inserita corrisponde a quella del config DoorPi ({@link Settings#getSettingsPassword()}), avvia l'activity specificata.
     * Il dialog non è cancellabile per evitare bypass accidentali.
     *
     * @param promptsView    Vista inflata con il campo di testo per la password.
     * @param activity_to_lunch Classe dell'activity da avviare se l'autenticazione ha successo.
     */
    void Authenticate_AndOpenOptionMenu(View promptsView, final Class<?> activity_to_lunch) {
        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
        alertDialogBuilder.setView(promptsView);
        final EditText userInput = (EditText) promptsView.findViewById(R.id.editTextDialogUserInput);
        userInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

        alertDialogBuilder
                .setCancelable(false)
                .setPositiveButton("OK",
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                String settingsPassword = mSettings != null
                                        ? mSettings.getSettingsPassword()
                                        : Settings.DEFAULT_PREF_SETTINGS_PASSWORD;
                                if (userInput.getText().toString().equals(settingsPassword)) {
                                    StartOptioMenu(activity_to_lunch);
                                }
                            }
                        })
                .setNegativeButton("Cancel",
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                dialog.cancel();
                            }
                        });

        AlertDialog alertDialog = alertDialogBuilder.create();
        alertDialog.show();
    }

    /**
     * @brief Avvia l'activity specificata.
     * @param cls Classe dell'activity da avviare.
     */
    public void StartOptioMenu(Class<?> cls) {
        startActivity(new Intent(this, cls));
    }

    // -------------------------------------------------------------------------
    // Stream RTSP
    // -------------------------------------------------------------------------

    /**
     * @brief Inizializza e avvia lo stream RTSP su {@link RtspSurfaceView}.
     *
     * Configura username e password (null se vuoti, la libreria gestisce l'autenticazione RTSP).
     * Imposta la rotazione video dalle preferenze e avvia la ricezione video.
     * Se {@link #mMediaUrl} è vuoto o null, non fa nulla.
     *
     * Chiamato da {@link #onResume()}.
     *
     * @note La telecamera Reolink trasmette sia video H.264 che audio AAC nel flusso RTSP.
     *       La libreria riporta entrambi nel log durante il parsing dell'SDP
     *       (es. "Audio: AAC, sample rate: 16000 Hz") — questo è puramente informativo
     *       e NON significa che l'audio venga decodificato o riprodotto.
     *       Il parametro {@code requestAudio=false} in {@link RtspSurfaceView#start} istruisce
     *       la libreria a ignorare completamente il flusso audio: nessun decoder audio
     *       viene inizializzato e nessun suono viene riprodotto.
     *       L'audio della chiamata è gestito esclusivamente da Mumble tramite
     *       {@link com.doorphone.service.DoorPhoneService}, che usa il microfono del
     *       dispositivo e il codec Opus — indipendente dallo stream RTSP.
     */
    private void startRtspStream() {
        // Reset PRIMA di ogni early-return: garantisce che il flag di teardown non resti
        // "appiccicato" oltre la finestra onPause->onResume. In particolare il ramo
        // isStarted() sotto esce senza un nuovo start, e senza questo reset un successivo
        // errore RTSP REALE (camera irraggiungibile a schermo acceso) verrebbe declassato
        // a D/ e perso. Da qui in poi un eventuale fallimento è un errore vero.
        mStoppingIntentionally = false;

        if (mRtspSurfaceView == null) {
            hideLoader();
            return;
        }

        String mediaUrl = mMediaUrl != null ? mMediaUrl.trim() : "";
        if (mediaUrl.length() == 0) {
            // Senza URL non partiranno callback RTSP -> il loader resterebbe appeso.
            hideLoader();
            return;
        }

        try {
            if (mRtspSurfaceView.isStarted()) {
                Log.d(TAG, "startRtspStream skipped: stream already started");
                // Stream gia' in corso: non arrivera' onRtspStatusConnecting ne'
                // onRtspFirstFrameRendered, quindi il loader non verrebbe mai
                // chiuso dalle callback. Lo nascondiamo qui.
                hideLoader();
                return;
            }

            Uri mediaUri = Uri.parse(mediaUrl);
            if (mediaUri.getScheme() == null || mediaUri.getScheme().length() == 0) {
                Log.w(TAG, "startRtspStream skipped: invalid camera URL");
                hideLoader();
                return;
            }

            String username = mSettings.getCameraUsername();
            String camPassword = mSettings.getCameraPassword();
            if (username == null) username = "";
            if (camPassword == null) camPassword = "";

            // Socket timeout 10s (default libreria: 5s).
            // Su WiFi brevi cali di segnale o Doze mode possono causare pause > 5s
            // sul socket TCP: con il default la libreria chiude e riavvia lo stream,
            // producendo glitch o freeze. 10s da piu' margine senza ritardare troppo
            // il rilevamento di un'effettiva disconnessione.
            final int RTSP_SOCKET_TIMEOUT_MS = 10_000;
            mRtspSurfaceView.init(
                    mediaUri,
                    username.isEmpty() ? null : username,
                    camPassword.isEmpty() ? null : camPassword,
                    "doorphone",
                    RTSP_SOCKET_TIMEOUT_MS
            );
            mRtspSurfaceView.setVideoRotation(mSettings.getVideoRotation());
            // Smoothing lato decoder: compensa il frame rate irregolare della telecamera
            // (burst di frame + pause) che senza stabilizzazione produce jitter visivo e
            // artefatti "fantasma" da P-frame decodificati su reference instabile.
            // Deve essere settato PRIMA di start() — viene passato al costruttore di
            // VideoDecoderSurfaceThread e non può essere cambiato a decoder avviato.
            mRtspSurfaceView.setVideoFrameRateStabilization(true);

            // Parametri start(requestVideo, requestAudio, requestApplication):
            //   requestVideo      = true  → decodifica e mostra il flusso video H.264
            //   requestAudio      = false → ignora il flusso audio AAC della telecamera:
            //                              l'audio della chiamata è gestito da Mumble (Opus),
            //                              non dallo stream RTSP della telecamera
            //   requestApplication= false → ignora eventuali flussi dati applicativi (non usati)
            mRtspSurfaceView.start(true, false, false);
            Log.d(TAG, "startRtspStream url=" + mediaUrl);
        } catch (Exception ex) {
            hideLoader();
            Log.e(TAG, "startRtspStream error: " + ex.getMessage());
        }
    }

    /**
     * @brief Ferma lo stream RTSP se è attivo.
     *
     * Controlla {@link RtspSurfaceView#isStarted()} prima di chiamare {@link RtspSurfaceView#stop()}
     * per evitare eccezioni se lo stream non è in esecuzione.
     *
     * Chiamato <b>solo</b> da {@link #onPause()}, dove la Surface è ancora garantita valida.
     * NON chiamare da {@link #onStop()} o {@link #onDestroy()}: a quel punto la Surface può
     * essere già distrutta e il decoder MediaCodec genererebbe {@code cancelBuffer} su
     * SurfaceFlinger.
     */
    public void stopRtspStream() {
        try {
            if (mRtspSurfaceView != null && mRtspSurfaceView.isStarted()) {
                // Segnala che il prossimo onRtspStatusFailed è atteso (teardown volontario),
                // così non viene loggato come errore. Settato PRIMA di stop() perché la
                // callback di fallimento può arrivare quasi subito sull'UI thread.
                mStoppingIntentionally = true;
                mRtspSurfaceView.stop();
                Log.d(TAG, "stopRtspStream");
            }
        } catch (Exception ex) {
            Log.e(TAG, "stopRtspStream error: " + ex.getMessage());
        }
    }

    /**
     * @brief Chiamato quando la configurazione del dispositivo cambia (es. orientamento).
     * Non esegue operazioni: la rotazione video è gestita tramite {@link #recreate()} in {@link #onResume()}.
     * @param newConfig Nuova configurazione.
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    /**
     * @brief Richiude la barra inferiore quando Android restituisce il focus all'activity.
     *
     * In immersive sticky l'utente puo' richiamare temporaneamente i tasti software
     * con uno swipe dal bordo. Quando la finestra torna attiva, se kiosk e' ancora
     * abilitato, riapplichiamo i flag per mantenere l'interfaccia pulita.
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && mKioskMode) {
            hideSystemBarsForKiosk();
        }
    }

    // -------------------------------------------------------------------------
    // Inizializzazione UI
    // -------------------------------------------------------------------------

    /**
     * @brief Inizializza tutti i componenti UI e i loro listener.
     *
     * - {@link RtspSurfaceView}: configura {@link RtspStatusListener} per mostrare/nascondere
     *   la ProgressBar durante la connessione.
     * - {@link #mCallButton}: accetta o termina la chiamata vocale.
     *   - Prima pressione (mUnlockButtonStatus = false): accetta la chiamata, smuta il microfono,
     *     invia "cmd-accept-call" a DoorPi, avvia timeout di 20 s.
     *   - Seconda pressione: chiude la chiamata.
     * - {@link #mUnlockButton}: invia il comando HTTP di apertura porta a DoorPi,
     *   poi chiude automaticamente la chiamata dopo 800 ms.
     * - Click sul frameLayout: mostra il pulsante di sblocco se nascosto.
     */
    private void initUI() {
        FrameLayout frameLayout = (FrameLayout) findViewById(R.id.frameLayout);
        progressBar = findViewById(R.id.progressBar);
        mConnectionStatusOverlay = findViewById(R.id.connection_status_overlay);
        updateConnectionStatusOverlay();

        mRtspSurfaceView = (RtspSurfaceView) findViewById(R.id.surface);
        if (mRtspSurfaceView == null) {
            Log.e(TAG, "CRITICO: RtspSurfaceView non trovato nel layout, finish()");
            finish();
            return;
        }
        mRtspSurfaceView.setStatusListener(new RtspStatusListener() {
            /** @brief Mostra la ProgressBar durante la connessione RTSP. */
            @Override
            public void onRtspStatusConnecting() {
                // Nessuno showLoader qui: il loader e' gestito esplicitamente in onResume()
                // solo quando il reload avviene dopo una pausa (mResumingFromPause).
                // Lo stato "connecting" si verifica anche durante connessioni rapide a
                // schermo gia' acceso, dove il loader sarebbe rumore visivo inutile.
            }
            /** @brief Nasconde la ProgressBar al primo frame ricevuto. */
            @Override
            public void onRtspFirstFrameRendered() {
                hideLoader();
            }
            /** @brief Nasconde la ProgressBar e logga l'errore RTSP. @param message Messaggio di errore. */
            @Override
            public void onRtspStatusFailed(String message) {
                hideLoader();
                if (mStoppingIntentionally) {
                    // Fallimento atteso: lo stream è stato fermato volontariamente in onPause
                    // mentre era ancora in handshake. Consuma il flag (un eventuale fallimento
                    // REALE successivo verrà loggato come errore) e declassa il log.
                    mStoppingIntentionally = false;
                    Log.d(TAG, "RTSP stream interrotto (teardown volontario): " + message);
                    return;
                }
                Log.e(TAG, "RTSP error: " + message);
            }
            /** @brief Nasconde la ProgressBar alla disconnessione RTSP. */
            @Override
            public void onRtspStatusDisconnected() {
                hideLoader();
            }
        });

        mCallButton = (FloatingActionButton) findViewById(R.id.button_call);
        mCallButton.setOnClickListener(view -> {
            if (mService == null) {
                /*
                 * Il servizio viene collegato in modo asincrono. Su hardware lento come
                 * Nexus 7 2012 il click puo' arrivare mentre il bind non e' ancora pronto:
                 * in quel caso non cambiamo stato UI e aspettiamo il prossimo tentativo.
                 */
                Log.w(TAG, "callButton: ignorato, DoorPhoneService non ancora connesso");
                return;
            }
            long now = android.os.SystemClock.elapsedRealtime();
            long elapsed = now - mLastCallButtonClickMs;
            if (elapsed < CALL_BUTTON_DEBOUNCE_MS) {
                Log.w(TAG, "callButton: debounce, elapsed=" + elapsed + "ms < " + CALL_BUTTON_DEBOUNCE_MS + "ms, ignorato");
                return;
            }
            mLastCallButtonClickMs = now;
            if (!mUnlockButtonStatus) {
                Log.d(TAG, "callButton: ACCEPT (elapsed=" + elapsed + "ms)");
                mUnlockButtonStatus = true;
                mCallButton.setImageResource(R.drawable.ic_call);
                mUnlockButton.show();
                mService.openCall();
                if (mySounds != null) mySounds.StopSound(mySounds.getSound2());
                sendMessage("accept-call");
                ring = false;
                handler_ring_timeout.removeCallbacks(runnable_ring_timeout);
                handler_repeat_ring.removeCallbacks(runnable_repeat_ring);
                handler_ring_timeout.postDelayed(runnable_ring_timeout, mSettings.getCallTimeoutMs());
            } else {
                Log.d(TAG, "callButton: CLOSE-MANUAL (elapsed=" + elapsed + "ms)");
                if (mySounds != null) mySounds.StopSound(mySounds.getSound2());
                // closeCall(false): l'utente sta chiudendo manualmente una chiamata
                // gia' accettata. Non bloccare lo schermo cosi' resta visibile il video
                // del citofono. Il timeout di inattivita' (80s) provvedera' a lockare.
                closeCall(false);
            }
        });

        mUnlockButton = (FloatingActionButton) findViewById(R.id.button_door_unlock);
        mUnlockButton.setOnClickListener(view -> {
            ring = false;
            String command_unlock_url = mSettings.getCMDUnlock() + "&P=" + mSettings.getDoorPiPiano();
            // Nessuno showLoader qui: il loader e' riservato al reload video dopo
            // resume da pausa. Il feedback visivo dell'unlock e' gia' dato dal
            // pulsante grigiato (setEnabled(false) + tint grigio) sotto.
            mUnlockButton.setEnabled(false);
            mUnlockButton.setBackgroundTintList(ColorStateList.valueOf(Color.GRAY));
            CommandSubmitter.postDataAsync(mSettings, command_unlock_url, VideoVLCActivity.this);
            if (mySounds != null) {
                mySounds.StopSound(mySounds.getSound2());
                mySounds.playSound(mySounds.getSound1());
            }
            mCallButton.hide();
            mUnlockButton.setImageResource(R.drawable.ic_lock_opened);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    if (mUnlockButton != null) {
                        mUnlockButton.setImageResource(R.drawable.ic_lock_open);
                    }
                    // closeCall(false): dopo unlock manuale l'app resta in foreground.
                    // L'utente vuole continuare a vedere il video del citofono dopo aver
                    // aperto la porta -- non bloccare lo schermo via DPM.lockNow().
                    closeCall(false);
                }
            }, 800);
        });
    }

    /**
     * @brief Aggiorna la visibilità e l'icona del pulsante call in base allo stato corrente.
     *
     * - Se la chiamata è già stata accettata ({@link #mUnlockButtonStatus} = true):
     *   icona "in chiamata" (per terminare).
     * - Se è in arrivo ({@link #mIncomingCall} = true): icona "chiamata in ingresso" + mostra il pulsante.
     * - Se non c'è chiamata: nasconde il pulsante.
     */
    private void HandleControlVisibility() {
        if (mUnlockButtonStatus) {
            mCallButton.setImageResource(R.drawable.ic_call);
        } else {
            mCallButton.setImageResource(R.drawable.ic_call_in);
        }
        if (mIncomingCall) mCallButton.show(); else mCallButton.hide();
    }

    // -------------------------------------------------------------------------
    // Comunicazione Mumble
    // -------------------------------------------------------------------------

    /**
     * @brief Invia un messaggio testuale all'utente "Doorpi" sul server Mumble.
     *
     * Il messaggio viene prefissato con "cmd-" per seguire il protocollo di comando DoorPi
     * (es. "accept-call" diventa "cmd-accept-call").
     * In caso di eccezione (servizio non connesso, sessione non valida) viene loggato e ignorato.
     *
     * @param message Comando da inviare (senza prefisso "cmd-").
     */
    public void sendMessage(String message) {
        try {
            mService.sendMessage("Doorpi", "cmd-" + message);
            // Arma il timer ACK solo se il messaggio è stato inviato con successo.
            // Fuori dal try il comando potrebbe non essere partito (mService null,
            // connessione non stabilita) e il warning "ACK non ricevuto" sarebbe fuorviante.
            if (message.equals("accept-call")) {
                handler_ack_timeout.removeCallbacks(runnable_ack_accept_timeout);
                handler_ack_timeout.postDelayed(runnable_ack_accept_timeout, 10000);
            } else if (message.equals("close-call")) {
                handler_ack_timeout.removeCallbacks(runnable_ack_close_timeout);
                handler_ack_timeout.postDelayed(runnable_ack_close_timeout, 10000);
            }
        } catch (Exception ex) {
            Log.d(TAG, "sendMessage: " + ex.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Gestione chiamata
    // -------------------------------------------------------------------------

    /**
     * @brief Termina la chiamata e riporta l'UI allo stato di riposo.
     *
     * Sequenza di operazioni:
     * 1. Invia "cmd-close-call" a DoorPi via Mumble.
     * 2. Nasconde il pulsante call, ripristina icona/colore dell'unlock (che resta sempre visibile).
     * 3. Azzera i flag di stato ({@link #mUnlockButtonStatus}, {@link #mIncomingCall}, {@link #ring}).
     * 4. Rimuove il flag {@link WindowManager.LayoutParams#FLAG_TURN_SCREEN_ON}.
     * 5. Blocca lo schermo tramite {@link DevicePolicyManager#lockNow()} (dispositivo rootato con Device Admin).
     * 6. Dopo 1500ms muta il microfono: il ritardo garantisce che il Pi possa inviare
     *    cmd-ack-close-call prima che Android si segni come "deaf" sul server Mumble.
     */
    void closeCall() {
        closeCall(true);
    }

    /**
     * @brief Variante di {@link #closeCall()} con controllo esplicito sul blocco schermo.
     *
     * @param lockScreen Se {@code true} e siamo in kiosk mode, esegue
     *                   {@link #turnOffScreen(Context)} a fine sequenza (blocca il device,
     *                   l'app va in background). Se {@code false} l'app resta visibile in
     *                   foreground -- usato dopo l'unlock manuale, perche' l'utente vuole
     *                   continuare a vedere il video del citofono dopo aver aperto la porta.
     */
    void closeCall(boolean lockScreen) {
        // Guard: se non c'è una chiamata attiva o in ingresso, non inviare cmd-close-call.
        // Senza questo check, path concorrenti (timeout ring + click utente) potevano
        // inviare due close-call al Raspberry causando il warning "nessuna sessione attiva".
        if (!mUnlockButtonStatus && !mIncomingCall) {
            Log.d(TAG, "closeCall: nessuna sessione attiva, skip cmd-close-call");
            return;
        }
        sendMessage("close-call");
        if (mCallButton != null) {
            mCallButton.hide();
        }
        if (mUnlockButton != null) {
            mUnlockButton.setEnabled(true);
            mUnlockButton.setImageResource(R.drawable.ic_lock_open);
            mUnlockButton.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#3F51B5")));
        }
        mUnlockButtonStatus = false;
        mIncomingCall = false;
        ring = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        if (mKioskMode && lockScreen) {
            turnOffScreen(getApplicationContext());
        }
        // Ritarda il mute+deafen di 1500ms per dare tempo al Pi di inviare cmd-ack-close-call
        // prima che Android si autosegni come "deaf" sul server Mumble.
        // Senza questo ritardo il server Mumble riceve il UserState(deaf=true) ~30ms dopo
        // cmd-close-call, mentre il Pi risponde in ~500-1000ms: l'ACK arriva quando P4 è
        // già sordo e il server lo scarta. Il Runnable named (runnable_deferred_mute) è
        // cancellabile: se l'ACK arriva prima di 1500ms, il receiver esegue il mute
        // immediatamente e cancella questo timer. Se l'ACK non arriva, il timer fa da fallback.
        handler_deferred_mute.removeCallbacks(runnable_deferred_mute);
        handler_deferred_mute.postDelayed(runnable_deferred_mute, 1500);
    }

    /**
     * @brief Blocca lo schermo del dispositivo tramite Device Policy Manager.
     *
     * Richiede che l'app sia configurata come Device Admin con il receiver
     * {@link ScreenOffAdminReceiver}. Se l'admin non è attivo, il metodo non fa nulla.
     *
     * @param context Contesto dell'applicazione.
     */
    static void turnOffScreen(final Context context) {
        DevicePolicyManager policyManager = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName adminReceiver = new ComponentName(context, ScreenOffAdminReceiver.class);
        if (policyManager != null && policyManager.isAdminActive(adminReceiver)) {
            policyManager.lockNow();
        }
    }

    /**
     * @brief Intercetta il tasto Back e non fa nulla.
     * Comportamento kiosk: l'utente non può uscire dall'applicazione con il tasto Back.
     */
    @Override
    public void onBackPressed() {
        if (mKioskMode) return;
        super.onBackPressed();
    }

    /**
     * @brief Impedisce che l'activity appaia nella lista delle app recenti.
     *
     * Chiama {@link ActivityManager#moveTaskToFront(int, int)} per riportare il task
     * in cima allo stack, rendendo invisibile la schermata nell'app switcher.
     * Chiamato in {@link #onStop()} per non innescare un loop con il ciclo onResume.
     */
    void disable_recent_app() {
        if (!mKioskMode) return;
        ActivityManager activityManager = (ActivityManager) getApplicationContext().getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager != null) {
            activityManager.moveTaskToFront(getTaskId(), 0);
        }
    }
}
