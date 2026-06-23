package com.doorphone.util;




import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.AsyncTask;
import android.util.Log;
import com.doorphone.R;
import com.doorphone.ui.VideoVLCActivity;




/**
 * @brief Wrapper singleton su {@link SoundPool} per riprodurre i toni di sistema
 *        (beep di conferma apertura porta, doorbell del ring in ingresso).
 *
 * @note Il caricamento dei sample via {@link SoundPool#load(Context, int, int)}
 *       è asincrono: i {@code soundId} sono assegnati subito, ma i sample non
 *       sono riproducibili finché non arriva {@link SoundPool.OnLoadCompleteListener}.
 *       Esporre {@link #isLoaded()} permette ai chiamanti di evitare play silenti.
 */
@SuppressWarnings("deprecation")
public class Sounds {
    private static final String TAG = Sounds.class.getSimpleName();
    private Context ctx;
    private static Sounds mSounds = null;
    private static SoundPool mSPool = null;
    /** @brief ID assegnato da SoundPool.load() per R.raw.beep. */
    private int sound1;
    /** @brief ID assegnato da SoundPool.load() per R.raw.doorbell. */
    private int sound2;
    /** @brief streamId restituito da SoundPool.play() per sound1 (-1 = nessuno attivo). */
    private int sound1StreamId = -1;
    /** @brief streamId restituito da SoundPool.play() per sound2 (-1 = nessuno attivo). */
    private int sound2StreamId = -1;
    private int soundsToLoad = 2;
    /** @brief Flag atomico: {@code true} quando tutti i sample sono decodificati e pronti. */
    private volatile boolean mLoaded = false;


    /**
     *   Volumecontrol
     */
    private AudioManager audioManager;


    private IOnSoundReady callback = null;

    public static Sounds getInstance(Context ctx, IOnSoundReady mCallback){
        if(mSPool == null){
            mSounds =  new Sounds(ctx, mCallback);
        }
        return mSounds;
    }

    private Sounds(Context ctx, IOnSoundReady mIOSoundReady) {
        this.ctx = ctx;
        this.callback = mIOSoundReady;
        this.audioManager = (AudioManager) ctx.getSystemService(VideoVLCActivity.AUDIO_SERVICE);

        AsyncTask<Void, Void, Void> mTask = new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                initSoundPool();
                return null;
            }
        };
        mTask.execute();
    }

    public void unprepare() {
        if (mSPool == null) return;
        mSPool.release();
        mSPool = null;
        mLoaded = false;
    }

    private void initSoundPool(){
        mSPool = new SoundPool(6, AudioManager.STREAM_MUSIC, 0);
        mSPool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
            @Override
            public void onLoadComplete(SoundPool soundPool, int sampleId, int status) {
                Log.w(TAG, "loaded soundid: " + sampleId + " status=" + status);
                if(--soundsToLoad == 0){
                    mLoaded = true;
                    if (callback != null) {
                        callback.onSoundReady();
                    }
                }
            }
        });

        sound1 = mSPool.load(ctx, R.raw.beep, 1);
        sound2 = mSPool.load(ctx, R.raw.doorbell, 1);

    }


    /** @brief {@return ID del sample beep restituito da SoundPool.load()} */
    public int getSound1() { return sound1; }

    /** @brief {@return ID del sample doorbell restituito da SoundPool.load()} */
    public int getSound2() { return sound2; }

    /** @brief {@return {@code true} se tutti i sample sono decodificati e pronti alla riproduzione} */
    public boolean isLoaded() { return mLoaded; }


    /**
     * @brief Calcola il volume corrente dello stream musica (0.0–1.0).
     *
     * Ricalcolato ad ogni chiamata per riflettere variazioni live del volume
     * effettuate dall'utente dopo l'istanza del singleton.
     *
     * @return Rapporto volume corrente / volume massimo dello STREAM_MUSIC.
     */
    private float currentVolume() {
        float actual = (float) audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        float max = (float) audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (max <= 0f) return 0f;
        return actual / max;
    }


    /**
     * @brief Riproduce il sample indicato se SoundPool e decodifica sono pronti.
     *
     * Se il sample non è ancora caricato il play viene saltato e loggato;
     * il chiamante (tipicamente il loop {@code runnable_repeat_ring}) ritenterà
     * automaticamente al tick successivo.
     *
     * @param soundid ID restituito da {@link SoundPool#load(Context, int, int)}
     *                (ottenibile da {@link #getSound1()} o {@link #getSound2()}).
     */
    public void playSound(int soundid){
        if (mSPool == null) return;
        if (!mLoaded) {
            Log.w(TAG, "playSound(" + soundid + ") skipped: samples not loaded yet");
            return;
        }
        float v = currentVolume();
        int streamId = mSPool.play(soundid, v, v, 1, 0, 1f);
        if (streamId == 0) {
            Log.w(TAG, "playSound(" + soundid + ") failed: SoundPool.play returned 0");
        } else {
            if (soundid == sound1) sound1StreamId = streamId;
            else if (soundid == sound2) sound2StreamId = streamId;
        }
    }


    public void StopSound(int soundid){
        if (mSPool == null) return;
        // stop() vuole lo streamId (da play()), NON il soundId (da load())
        if (soundid == sound1 && sound1StreamId != -1) {
            mSPool.stop(sound1StreamId);
            sound1StreamId = -1;
        } else if (soundid == sound2 && sound2StreamId != -1) {
            mSPool.stop(sound2StreamId);
            sound2StreamId = -1;
        }
    }



    public interface IOnSoundReady {
        void onSoundReady();
    }




}
