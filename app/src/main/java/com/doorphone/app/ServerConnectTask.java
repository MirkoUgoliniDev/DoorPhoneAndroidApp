package com.doorphone.app;


import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import java.util.ArrayList;
import se.lublin.humla.HumlaService;
import se.lublin.humla.model.Server;
import com.doorphone.R;
import com.doorphone.Settings;
import com.doorphone.service.DoorPhoneService;
import com.doorphone.util.DoorPhoneTrustStore;



/**
 * Constructs an intent for connection to a DoorPhoneService and executes it.
 * Created by andrew on 20/08/14.
 */
@SuppressWarnings("deprecation")
public class ServerConnectTask extends AsyncTask<Server, Void, Intent> {
    private Context mContext;
    private Settings mSettings;

    public ServerConnectTask(Context context) {
        mContext = context;
        mSettings = Settings.getInstance(context);
    }

    @Override
    protected Intent doInBackground(Server... params) {
        Server server = params[0];

        int inputMethod = mSettings.getHumlaInputMethod();

        int audioSource = mSettings.isHandsetMode() ?
                MediaRecorder.AudioSource.DEFAULT : MediaRecorder.AudioSource.MIC;
        int audioStream = mSettings.isHandsetMode() ?
                AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC;

        String applicationVersion = "";
        try {
            applicationVersion = mContext.getPackageManager().getPackageInfo(mContext.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

        Intent connectIntent = new Intent(mContext, DoorPhoneService.class);
        connectIntent.putExtra(HumlaService.EXTRAS_SERVER, server);
        connectIntent.putExtra(HumlaService.EXTRAS_CLIENT_NAME, mContext.getString(R.string.app_name) + "V: " + applicationVersion);
        connectIntent.putExtra(HumlaService.EXTRAS_TRANSMIT_MODE, inputMethod);
        connectIntent.putExtra(HumlaService.EXTRAS_DETECTION_THRESHOLD, mSettings.getDetectionThreshold());
        connectIntent.putExtra(HumlaService.EXTRAS_AMPLITUDE_BOOST, mSettings.getAmplitudeBoostMultiplier());
        connectIntent.putExtra(HumlaService.EXTRAS_AUTO_RECONNECT, mSettings.isAutoReconnectEnabled());
        connectIntent.putExtra(HumlaService.EXTRAS_AUTO_RECONNECT_DELAY, DoorPhoneService.RECONNECT_DELAY);
        connectIntent.putExtra(HumlaService.EXTRAS_USE_OPUS, !mSettings.isOpusDisabled());
        connectIntent.putExtra(HumlaService.EXTRAS_INPUT_RATE, mSettings.getInputSampleRate());
        connectIntent.putExtra(HumlaService.EXTRAS_INPUT_QUALITY, mSettings.getInputQuality());
        connectIntent.putExtra(HumlaService.EXTRAS_FORCE_TCP, mSettings.isTcpForced());
        connectIntent.putStringArrayListExtra(HumlaService.EXTRAS_ACCESS_TOKENS, new ArrayList<>());
        connectIntent.putIntegerArrayListExtra(HumlaService.EXTRAS_LOCAL_MUTE_HISTORY, new ArrayList<>());
        connectIntent.putIntegerArrayListExtra(HumlaService.EXTRAS_LOCAL_IGNORE_HISTORY, new ArrayList<>());
        connectIntent.putExtra(HumlaService.EXTRAS_AUDIO_SOURCE, audioSource);
        connectIntent.putExtra(HumlaService.EXTRAS_AUDIO_STREAM, audioStream);
        connectIntent.putExtra(HumlaService.EXTRAS_FRAMES_PER_PACKET, mSettings.getFramesPerPacket());
        connectIntent.putExtra(HumlaService.EXTRAS_TRUST_STORE, DoorPhoneTrustStore.getTrustStorePath(mContext));
        connectIntent.putExtra(HumlaService.EXTRAS_TRUST_STORE_PASSWORD, DoorPhoneTrustStore.getTrustStorePassword());
        connectIntent.putExtra(HumlaService.EXTRAS_TRUST_STORE_FORMAT, DoorPhoneTrustStore.getTrustStoreFormat());
        connectIntent.putExtra(HumlaService.EXTRAS_HALF_DUPLEX, mSettings.isHalfDuplex());
        connectIntent.putExtra(HumlaService.EXTRAS_ENABLE_PREPROCESSOR, mSettings.isPreprocessorEnabled());

        byte[] certificate = mSettings.getCertificateBytes();
        if (certificate != null) {
            connectIntent.putExtra(HumlaService.EXTRAS_CERTIFICATE, certificate);
        }

        connectIntent.setAction(HumlaService.ACTION_CONNECT);

        return connectIntent;
    }

    @Override
    protected void onPostExecute(Intent intent) {
        super.onPostExecute(intent);
        mContext.startService(intent);
    }
}
