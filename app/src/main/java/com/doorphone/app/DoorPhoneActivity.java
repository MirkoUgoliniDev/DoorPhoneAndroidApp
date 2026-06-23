
package com.doorphone.app;


import android.Manifest;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.preference.PreferenceManager;
import android.text.InputType;
import android.util.Log;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Locale;
import se.lublin.humla.HumlaService;
import se.lublin.humla.IHumlaService;
import se.lublin.humla.model.Server;
import se.lublin.humla.net.HumlaCertificateGenerator;
import se.lublin.humla.protobuf.Mumble;
import se.lublin.humla.util.HumlaException;
import se.lublin.humla.util.HumlaObserver;
import java.io.ByteArrayOutputStream;
import com.doorphone.BuildConfig;
import com.doorphone.R;
import com.doorphone.Settings;
import com.doorphone.preference.Preferences;
import com.doorphone.service.IDoorPhoneService;
import com.doorphone.service.DoorPhoneService;
import com.doorphone.util.HumlaServiceFragment;
import com.doorphone.util.HumlaServiceProvider;
import com.doorphone.util.DoorPhoneTrustStore;





@SuppressWarnings("deprecation")
public class DoorPhoneActivity extends Activity implements HumlaServiceProvider, SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = DoorPhoneActivity.class.getSimpleName();
    public static final String EXTRA_DRAWER_FRAGMENT = "drawer_fragment";
    private IDoorPhoneService mService;
    private Settings mSettings;
    private static final int PERMISSIONS_REQUEST_RECORD_AUDIO = 1;
    private Server mServerPendingPerm = null;
    private ProgressDialog mConnectingDialog;
    private AlertDialog mErrorDialog;



    /**
     * @brief Inizializza l'Activity: tema, layout, database e stream audio.
     *
     * Non avvia la connessione Mumble: lo fa {@link #mConnection} in
     * {@code onServiceConnected()}, dopo aver verificato che il servizio
     * non sia già connesso. Questo evita il "Kicked: connected from another
     * device" quando l'Activity viene ricreata mentre {@link DoorPhoneService}
     * è ancora attivo in background.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d( TAG, "ON CREATE");

        mSettings = Settings.getInstance(this);
        setTheme(mSettings.getTheme());
        setContentView(R.layout.empty);

        setVolumeControlStream(mSettings.isHandsetMode() ? AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC);
    }


    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        Log.d( TAG, "ON POST CREATE");
    }



    /**
     * @brief Aggancia l'Activity al {@link DoorPhoneService} ogni volta che torna visibile.
     *
     * Usa {@code BIND_AUTO_CREATE}: se il servizio non esiste ancora lo avvia,
     * se è già in esecuzione si aggancia all'istanza esistente senza riavviarlo.
     * La decisione se connettersi a Mumble (o ignorare perché già connesso)
     * viene presa in {@code onServiceConnected()}, non qui.
     */
    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "ON RESUME — cert_sp=" + (mSettings.getCertificateBytes() != null ? "SI" : "NO")
                + " piano=" + mSettings.getDoorPiPiano()
                + " audio_perm=" + (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ? "OK" : "MANCANTE"));

        Intent connectIntent = new Intent(this, DoorPhoneService.class);
        /*
         * Stabilita' Android 7.1/Nexus 7:
         * Connect() avvia DoorPhoneService tramite ServerConnectTask, quindi il bind puo'
         * arrivare prima che startService() sia effettivamente eseguito. Con flag 0 il
         * bind fallisce se il servizio non esiste ancora e l'observer non viene registrato.
         * BIND_AUTO_CREATE rende il collegamento deterministico senza cambiare il target API.
         */
        bindService(connectIntent, mConnection, Context.BIND_AUTO_CREATE);

    }



    /**
     * @brief Rilascia il binding al servizio e chiude i dialog attivi.
     *
     * Il {@link DoorPhoneService} resta in esecuzione come foreground service
     * e mantiene la connessione Mumble attiva in background: l'unbind
     * non interrompe la sessione vocale.
     */
    @Override
    protected void onPause() {
        super.onPause();
        Log.d( TAG, "ON PAUSE");

        if (mErrorDialog != null){
            mErrorDialog.dismiss();
        }

        if (mConnectingDialog != null){
            mConnectingDialog.dismiss();
        }

        if(mService != null) {
            mService.unregisterObserver(mObserver);
            //mService.setSuppressNotifications(false);
        }

        try {
            unbindService(mConnection);
        }catch (Exception ex){

        }

    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "ON DESTROY");
    }





    /*
    public void startService(View v) {
        Log.e("boot-status","START SERVICE");
        Intent serviceIntent = new Intent(this, ForegroundService.class);
        serviceIntent.putExtra("inputExtra", "passing any text");
        ContextCompat.startForegroundService(this, serviceIntent);
    }
    */







    private int checkFirstRun() {
        final String PREFS_NAME = "MyPrefsFile";
        final String PREF_VERSION_CODE_KEY = "version_code";
        final int DOESNT_EXIST = -1;
        int result = 0;


        // Get current version code
        int currentVersionCode = BuildConfig.VERSION_CODE;

        // Get saved version code
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int savedVersionCode = prefs.getInt(PREF_VERSION_CODE_KEY, DOESNT_EXIST);

        // Check for first run or upgrade
        if (currentVersionCode == savedVersionCode) {
            // Normal run: versione non cambiata
            result=0;

        } else if (savedVersionCode == DOESNT_EXIST) {
            // Prima installazione (o preferenze cancellate dall'utente)
            result=1;

        } else if (currentVersionCode > savedVersionCode) {
            // Aggiornamento dell'app
            result=2;
        }

        // Update the shared preferences with the current version code
        prefs.edit().putInt(PREF_VERSION_CODE_KEY, currentVersionCode).apply();

        return result;

    }






    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if(Settings.PREF_THEME.equals(key)) {
            recreate();
        } else if (Settings.PREF_STAY_AWAKE.equals(key)) {
            setStayAwake(mSettings.shouldStayAwake());
        } else if (Settings.PREF_HANDSET_MODE.equals(key)) {
            setVolumeControlStream(mSettings.isHandsetMode() ? AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC);
        }

    }




    /**
     * @brief Gestisce il ciclo di vita del binding al {@link DoorPhoneService}.
     *
     * <p><b>Logica di connessione Mumble</b> (in {@code onServiceConnected}):
     * il servizio viene interrogato sullo stato corrente <em>prima</em> di
     * decidere se avviare una nuova connessione. Se è già {@code CONNECTED}
     * o {@code CONNECTING} l'Activity si aggancia silenziosamente alla
     * sessione esistente, senza inviare un secondo {@code ACTION_CONNECT}.
     *
     * <p>Questo previene il "Kicked: You connected to the server from another
     * device": il server Mumble espelle la nuova connessione quando vede lo
     * stesso username già loggato, situazione che si verificava ogni volta
     * che l'Activity veniva ricreata (es. pressione Indietro + riapertura app)
     * mentre il {@link DoorPhoneService} rimaneva attivo in background.
     */
    private ServiceConnection mConnection = new ServiceConnection() {

        /**
         * @brief Chiamato quando il binding al servizio è completato.
         *
         * Registra l'observer e avvia la connessione Mumble solo se il servizio
         * è in stato {@link HumlaService.ConnectionState#DISCONNECTED}.
         * Se il piano non è ancora configurato mostra prima la modale di selezione.
         */
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mService = ((DoorPhoneService.DoorPhoneBinder) service).getService();
            mService.setSuppressNotifications(true);
            mService.registerObserver(mObserver);
            mService.clearChatNotifications();

            HumlaService.ConnectionState state = mService.getConnectionState();
            Log.d(TAG, "onServiceConnected — state=" + state
                    + " cert_sp=" + (mSettings.getCertificateBytes() != null ? "SI(" + mSettings.getCertificateBytes().length + "B)" : "NO")
                    + " piano=" + mSettings.getDoorPiPiano());

            boolean needsPianoSelection = false;
            if (state == HumlaService.ConnectionState.DISCONNECTED) {
                String piano = mSettings.getDoorPiPiano();
                if (piano == null || piano.trim().isEmpty()) {
                    needsPianoSelection = true;
                    Log.d(TAG, "onServiceConnected — piano non configurato, mostro dialog IP server");
                    showServerIpDialog();
                } else {
                    Log.d(TAG, "onServiceConnected — avvio Connect()");
                    Connect();
                }
            } else {
                Log.d(TAG, "onServiceConnected — servizio già in stato " + state + ", non riconnetto");
            }

            updateConnectionState(getService());
            // openVideoStream() NON va chiamata qui: se il servizio è ancora CONNECTING
            // e la chiamata manda DoorPhoneActivity in PAUSE, l'observer viene rimosso prima
            // che la connessione completi. Se il TLS handshake fallisce (es. primo avvio,
            // cert server non ancora nel trust store), nessuno gestisce onTLSHandshakeFailed
            // e la connessione si perde definitivamente.
            // L'unico punto che deve aprire VideoVLCActivity è updateConnectionState(CONNECTED).
        }

        /**
         * @brief Chiamato quando il binding al servizio viene perso inaspettatamente.
         *
         * Azzera il riferimento a {@link #mService} per evitare chiamate su un
         * binder non più valido. Il re-bind avverrà al prossimo {@code onResume()}.
         */
        @Override
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
        }

    };



    private HumlaObserver mObserver = new HumlaObserver() {
        @Override
        public void onConnected() {
            updateConnectionState(getService());
        }

        @Override
        public void onConnecting() {
            updateConnectionState(getService());
        }

        @Override
        public void onDisconnected(HumlaException e) {
            updateConnectionState(getService());
        }

        @Override
        public void onTLSHandshakeFailed(X509Certificate[] chain) {
            final Server lastServer = getService().getTargetServer();
            Log.d(TAG, "onTLSHandshakeFailed — chain.length=" + chain.length
                    + " server=" + (lastServer != null ? lastServer.getHost() : "null"));
            if (chain.length == 0) return;
            try {
                final X509Certificate x509 = chain[0];
                String alias = lastServer.getHost();
                KeyStore trustStore = DoorPhoneTrustStore.getTrustStore(DoorPhoneActivity.this);
                trustStore.setCertificateEntry(alias, x509);
                DoorPhoneTrustStore.saveTrustStore(DoorPhoneActivity.this, trustStore);
                Log.d(TAG, "onTLSHandshakeFailed — cert server accettato automaticamente: " + alias + ", riconnetto");
                connectToServer(lastServer);
            } catch (Exception e) {
                Log.e(TAG, "onTLSHandshakeFailed — ERRORE accettazione cert: " + e.getMessage(), e);
            }
        }

        @Override
        public void onPermissionDenied(String reason) {
            AlertDialog.Builder adb = new AlertDialog.Builder(DoorPhoneActivity.this);
            adb.setTitle(R.string.perm_denied);
            adb.setMessage(reason);
            adb.show();
        }
    };







    /**
     * @brief Mostra una modale obbligatoria centrata per inserire l'IP del server al primo avvio.
     *
     * Primo passo dell'onboarding (prima della selezione piano). Non cancellabile.
     * Il campo e' pre-compilato con l'host dell'URL di config attuale (default
     * {@code 192.168.1.54}). Alla conferma costruisce l'URL di config
     * {@code http://<IP>:8080/config/} — porta e path sono fissi — lo salva in
     * {@link Settings#PREF_RASPBERRY_CONFIG_URL} e prosegue con
     * {@link #showPianoSelectionDialog()}.
     */
    private void showServerIpDialog() {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        // Pre-compila con l'host dell'URL di config salvato, fallback al default
        String currentUrl = prefs.getString(
                Settings.PREF_RASPBERRY_CONFIG_URL,
                Settings.DEFAULT_PREF_RASPBERRY_CONFIG_URL);
        String currentIp = Uri.parse(currentUrl.trim()).getHost();
        if (currentIp == null || currentIp.isEmpty()) {
            currentIp = Settings.DEFAULT_PREF_DOORPI_IP;
        }

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);

        android.view.View view = getLayoutInflater().inflate(R.layout.dialog_server_ip, null);
        dialog.setContentView(view);

        // Finestra centrata, larghezza 80% dello schermo, sfondo trasparente
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.CENTER);
            int widthPx = (int) (getResources().getDisplayMetrics().widthPixels * 0.80f);
            window.setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        final EditText editIp = view.findViewById(R.id.server_ip_edit);
        editIp.setText(currentIp);
        editIp.setSelection(editIp.getText().length());

        Button btnConferma = view.findViewById(R.id.btn_server_ip_conferma);
        btnConferma.setOnClickListener(v -> {
            String ip = editIp.getText().toString().trim();
            if (ip.isEmpty()) {
                Toast.makeText(this, "Inserisci l'indirizzo IP del server", Toast.LENGTH_SHORT).show();
                return;
            }
            String url = "http://" + ip + ":8080/config/";
            prefs.edit().putString(Settings.PREF_RASPBERRY_CONFIG_URL, url).apply();
            Log.d(TAG, "IP server configurato: " + ip + " — config URL: " + url);
            dialog.dismiss();

            // Secondo passo: selezione del piano
            showPianoSelectionDialog();
        });

        dialog.show();
    }

    /**
     * @brief Mostra una modale obbligatoria centrata per selezionare il piano al primo avvio.
     *
     * Non cancellabile. Dopo la conferma salva il piano nelle SharedPreferences, esegue
     * il fetch della configurazione dal Raspberry (parametro {@code ?p=<piano>}) e solo
     * al completamento del fetch avvia {@link #Connect()}.
     */
    private void showPianoSelectionDialog() {
        final String[] names  = getResources().getStringArray(R.array.piani_names);
        final String[] values = getResources().getStringArray(R.array.piani_values);

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);

        android.view.View view = getLayoutInflater().inflate(R.layout.dialog_piano_selection, null);
        dialog.setContentView(view);

        // Finestra centrata, larghezza 80% dello schermo, sfondo trasparente
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.CENTER);
            int widthPx = (int) (getResources().getDisplayMetrics().widthPixels * 0.80f);
            window.setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        // Popola il RadioGroup con i piani disponibili
        RadioGroup radioGroup = view.findViewById(R.id.piano_radio_group);
        float density = getResources().getDisplayMetrics().density;
        int minHeightPx = (int) (56 * density);
        int paddingHPx  = (int) (18 * density);
        int gapPx       = (int) (8 * density);

        for (int i = 0; i < names.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(i);
            rb.setText(names[i]);
            rb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            rb.setTextColor(Color.parseColor("#222831"));
            rb.setMinimumHeight(minHeightPx);
            // Riquadro arrotondato che si evidenzia quando la riga e' selezionata
            rb.setBackgroundResource(R.drawable.bg_radio_item);
            // Sposta il pallino di selezione e dà respiro al testo
            rb.setPadding(paddingHPx, 0, paddingHPx, 0);
            RadioGroup.LayoutParams lp = new RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = gapPx;
            rb.setLayoutParams(lp);
            radioGroup.addView(rb);
        }
        radioGroup.check(0);

        // Pulsante CONFERMA
        Button btnConferma = view.findViewById(R.id.btn_piano_conferma);
        btnConferma.setOnClickListener(v -> {
            int selectedId = radioGroup.getCheckedRadioButtonId();
            if (selectedId < 0 || selectedId >= values.length) return;
            String piano = values[selectedId];

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            prefs.edit().putString(Settings.PREF_DOORPI_PIANO, piano).apply();
            Log.d(TAG, "Piano configurato: " + piano);
            dialog.dismiss();

            String configUrl = prefs.getString(
                    Settings.PREF_RASPBERRY_CONFIG_URL,
                    Settings.DEFAULT_PREF_RASPBERRY_CONFIG_URL);
            Uri finalUri = Uri.parse(configUrl.trim()).buildUpon()
                    .clearQuery()
                    .appendQueryParameter("p", piano.toLowerCase(Locale.US))
                    .build();
            Log.d(TAG, "Config URL: " + finalUri);

            RaspberryConfigFetcher.fetch(finalUri.toString(), prefs, this, () -> {
                Log.d(TAG, "Fetch completato, avvio Connect()");
                Connect();
                // NON chiamare openVideoStream() qui: se il certificato deve essere
                // generato (primo avvio), Connect() parte in background e openVideoStream()
                // manderebbe DoorPhoneActivity in pause PRIMA che la generazione finisca.
                // Con DoorPhoneActivity in pause, il dialog RECORD_AUDIO non viene gestito
                // correttamente e la connessione non parte mai.
                // openVideoStream() viene chiamata da updateConnectionState() al CONNECTED.
            });
        });

        dialog.show();
    }

    protected void Connect(){
        String piano =  mSettings.getDoorPiPiano();
        String mumblaserverName = mSettings.getDoorPiName();
        String mumblaserverIP = mSettings.getDoorPiHost();
        String mumblaserverPassword = mSettings.getDoorPiPassword();
        int doorphoneServerPort = mSettings.getDoorPiPort();

        Server server = new Server(1, mumblaserverName, mumblaserverIP, doorphoneServerPort, piano, mumblaserverPassword);
        ensureCertificateAndConnect(server);
    }

    /**
     * @brief Assicura che esista un certificato client Mumble prima di connettersi.
     *
     * Se non è configurato alcun certificato, ne genera uno RSA-2048 in background
     * (la generazione può richiedere 1-2 s su hardware datato) e poi chiama
     * {@link #connectToServer(Server)} sul main thread.
     * Se il certificato esiste già, chiama subito {@link #connectToServer(Server)}.
     *
     * @param server Server a cui connettersi dopo la verifica/generazione del certificato.
     */
    private void ensureCertificateAndConnect(final Server server) {
        byte[] certSp = mSettings.getCertificateBytes();
        if (certSp != null) {
            Log.d(TAG, "ensureCert — cert trovato in SP (" + certSp.length + " B), connetto subito");
            connectToServer(server);
            return;
        }
        // Nessun certificato: genera RSA-2048 e salvalo in SharedPreferences.
        Log.d(TAG, "ensureCert — nessun certificato trovato, genero RSA-2048 in background...");
        new Thread(() -> {
            try {
                long t0 = System.currentTimeMillis();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                HumlaCertificateGenerator.generateCertificate(out);
                byte[] certBytes = out.toByteArray();
                mSettings.setCertificateBytes(certBytes);
                Log.d(TAG, "ensureCert — cert generato (" + certBytes.length + " B) in " + (System.currentTimeMillis() - t0) + " ms");
            } catch (Exception e) {
                Log.e(TAG, "ensureCert — ERRORE generazione certificato: " + e.getMessage(), e);
            }
            runOnUiThread(() -> {
                Log.d(TAG, "ensureCert — runOnUiThread: chiamo connectToServer");
                connectToServer(server);
            });
        }).start();
    }






     public void openVideoStream(){
        Intent vIntent = new Intent(this, com.doorphone.ui.VideoVLCActivity.class);
        vIntent.putExtra("key","change_videoformat");
        startActivity(vIntent);
     }





    public void connectToServer(final Server server) {
        try {
            mServerPendingPerm = null;
            boolean audioOk = ContextCompat.checkSelfPermission(DoorPhoneActivity.this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            String svcState = (mService != null) ? mService.getConnectionState().name() : "null";
            Log.d(TAG, "connectToServer — server=" + server.getHost() + ":" + server.getPort()
                    + " audio_perm=" + (audioOk ? "OK" : "MANCANTE")
                    + " mService=" + svcState);

            if (!audioOk) {
                Log.d(TAG, "connectToServer — richiedo RECORD_AUDIO, salvo server in mServerPendingPerm");
                ActivityCompat.requestPermissions(DoorPhoneActivity.this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSIONS_REQUEST_RECORD_AUDIO);
                mServerPendingPerm = server;
                return;
            }

            if(mService != null && mService.isConnected()) {
                Log.d(TAG, "connectToServer — già connesso, mostro dialog riconnessione");
                AlertDialog.Builder adb = new AlertDialog.Builder(this);
                adb.setMessage(R.string.reconnect_dialog_message);
                adb.setPositiveButton(R.string.connect, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        mService.registerObserver(new HumlaObserver() {
                            @Override
                            public void onDisconnected(HumlaException e) {
                                connectToServer(server);
                                mService.unregisterObserver(this);
                            }
                        });
                        mService.disconnect();
                    }
                });
                adb.setNegativeButton(android.R.string.cancel, null);
                adb.show();
                return;
            }

            Log.d(TAG, "connectToServer — avvio ServerConnectTask");
            ServerConnectTask connectTask = new ServerConnectTask(this);
            connectTask.execute(server);

        } catch (Exception e) {
            Log.e(TAG, "connectToServer — ECCEZIONE: " + e.getMessage(), e);
        }

    }





    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Log.d(TAG, "onRequestPermissionsResult — requestCode=" + requestCode
                + " grantResults.length=" + grantResults.length
                + " mServerPendingPerm=" + (mServerPendingPerm != null ? mServerPendingPerm.getHost() : "null"));
        if (requestCode == PERMISSIONS_REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "onRequestPermissionsResult — RECORD_AUDIO concesso");
                if (mServerPendingPerm != null) {
                    connectToServer(mServerPendingPerm);
                } else {
                    Log.w(TAG, "onRequestPermissionsResult — RECORD_AUDIO concesso ma mServerPendingPerm è null!");
                }
            } else {
                Log.w(TAG, "onRequestPermissionsResult — RECORD_AUDIO NEGATO");
                Toast.makeText(DoorPhoneActivity.this, getString(R.string.grant_perm_microphone),
                        Toast.LENGTH_LONG).show();
            }
        }
    }


    private void setStayAwake(boolean stayAwake) {
        if (stayAwake) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }



    /**
     * Updates the activity to represent the connection state of the given service.
     * Will show reconnecting dialog if reconnecting, dismiss otherwise, etc.
     * Basically, this service will do catch-up if the activity wasn't bound to receive
     * connection state updates.
     * @param service A bound IHumlaService.
     */
    private void updateConnectionState(IHumlaService service) {
        Log.d(TAG, "updateConnectionState — state=" + (mService != null ? mService.getConnectionState() : "mService null"));

        if (mConnectingDialog != null){
            mConnectingDialog.dismiss();
        }

        if (mErrorDialog != null){
            mErrorDialog.dismiss();
        }

        if (mService == null) return;

        switch (mService.getConnectionState()) {

            case CONNECTING:
                Server server = service.getTargetServer();
                mConnectingDialog = new ProgressDialog(this);
                mConnectingDialog.setIndeterminate(true);
                mConnectingDialog.setCancelable(true);
                mConnectingDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        mService.disconnect();
                        Toast.makeText(DoorPhoneActivity.this, R.string.cancelled, Toast.LENGTH_SHORT).show();
                    }
                });


                // SRV lookup is done later, so we no longer show the port (and
                // only the configured hostname)
                mConnectingDialog.setMessage(getString(R.string.connecting_to_server, server.getHost()));
                mConnectingDialog.show();
                break;


            case CONNECTED:
                // TODO: MIRKO
                Log.d(TAG, "CONNECTED !!!");
                openVideoStream();
                mSettings.setMutedAndDeafened(false, false);
                break;


            case CONNECTION_LOST:
                // Only bother the user if the error hasn't already been shown.
                if (getService() != null && !getService().isErrorShown()) {


                    // TODO:  popup che si apre quando si perde la connessione
                    AlertDialog.Builder ab = new AlertDialog.Builder(DoorPhoneActivity.this);
                    ab.setTitle(getString(R.string.connectionRefused));
                    HumlaException error = getService().getConnectionError();
                    if (error != null && mService.isReconnecting()) {
                        ab.setMessage(error.getMessage() + "\n\n" + getString(R.string.attempting_reconnect,
                                error.getCause() != null ? error.getCause().getMessage() : "unknown"));
                        ab.setPositiveButton(R.string.cancel_reconnect, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (getService() != null) {
                                    getService().cancelReconnect();
                                    getService().markErrorShown();
                                }
                            }
                        });


                    } else if (error != null &&
                               error.getReason() == HumlaException.HumlaDisconnectReason.REJECT &&
                               (error.getReject().getType() == Mumble.Reject.RejectType.WrongUserPW ||
                                error.getReject().getType() == Mumble.Reject.RejectType.WrongServerPW)) {
                        final EditText passwordField = new EditText(this);
                        passwordField.setInputType(InputType.TYPE_CLASS_TEXT |
                                InputType.TYPE_TEXT_VARIATION_PASSWORD);
                        passwordField.setHint(R.string.password);
                        ab.setTitle(R.string.invalid_password);
                        ab.setMessage(error.getMessage());
                        ab.setView(passwordField);
                        ab.setPositiveButton(R.string.reconnect, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Server server = getService().getTargetServer();
                                if (server == null)
                                    return;
                                String password = passwordField.getText().toString();
                                server.setPassword(password);
                                connectToServer(server);
                            }
                        });
                        ab.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (getService() != null)
                                    getService().markErrorShown();
                            }
                        });
                    } else {
                        String msg = error != null ? error.getMessage() : getString(R.string.unknown);
                        ab.setMessage(msg);
                        ab.setPositiveButton(R.string.settings, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (getService() != null)
                                    getService().markErrorShown();
                                startActivity(new Intent(DoorPhoneActivity.this, Preferences.class));
                            }
                        });
                        ab.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (getService() != null)
                                    getService().markErrorShown();
                            }
                        });
                    }
                    ab.setCancelable(false);
                    mErrorDialog = ab.show();
                }
                break;
        }

    }

    @Override
    public IDoorPhoneService getService() {
        return mService;
    }

    @Override
    public void addServiceFragment(HumlaServiceFragment fragment) {
        //mServiceFragments.add(fragment);
    }

    @Override
    public void removeServiceFragment(HumlaServiceFragment fragment) {
        //mServiceFragments.remove(fragment);
    }








    @Override
    protected void onActivityResult (int requestCode, int resultCode, Intent data) {
        if (resultCode == Activity.RESULT_OK && requestCode == 123) {
            mSettings.setMutedAndDeafened(false, false);
        }
    }

}
