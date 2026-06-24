package com.doorphone.app;

import android.util.Log;

/**
 * @brief Hardening del dispositivo per l'uso kiosk tramite comandi root ({@code su}).
 *
 * Applica impostazioni di sistema che valgono per <b>tutta la flotta</b> di tablet e
 * non dipendono dal singolo dispositivo, così da non richiedere intervento manuale
 * via adb/USB su ogni tablet: vengono eseguite dal codice dell'app a ogni avvio.
 *
 * Eseguito una sola volta per processo da {@link MyApp#onCreate()}, su un thread di
 * background perché i comandi {@code su} possono bloccare. Stesso pattern root già
 * usato da {@link RaspberryConfigFetcher} per timezone/ora/luminosità.
 *
 * Tutti i comandi sono <b>idempotenti</b> (no-op se lo stato è già quello voluto),
 * quindi è sicuro rieseguirli a ogni avvio.
 *
 * @note Richiede dispositivo rootato (su disponibile). Su device non rootati i comandi
 *       falliscono silenziosamente e l'app continua a funzionare.
 */
final class DeviceHardening {

    private static final String TAG = "DeviceHardening";

    /**
     * @brief Pacchetto AOSP che contiene Fotocamera + Galleria su questo device
     *        (Nexus 7 grouper, Android 7.1.2). Disabilitandolo si spegne sia la
     *        fotocamera sia la galleria: non servono su un citofono dedicato.
     */
    private static final String CAMERA_GALLERY_PACKAGE = "com.android.gallery3d";

    private DeviceHardening() {}

    /** @brief Avvia l'hardening in background. Non blocca il chiamante. */
    static void applyAsync() {
        new Thread(DeviceHardening::apply, "DeviceHardening").start();
    }

    private static void apply() {
        // 1) Disabilita la gesture "doppio-Power -> Fotocamera": su un kiosk una doppia
        //    pressione accidentale del tasto Power aprirebbe la fotocamera sopra il
        //    videocitofono, fuori dal controllo dell'app.
        runSu("settings put secure camera_double_tap_power_gesture_disabled 1",
                "gesture doppio-power camera OFF");

        // 2) Disabilita del tutto l'app Fotocamera/Galleria (stesso pacchetto su questo
        //    build AOSP). Belt-and-suspenders: anche se una gesture/intent riuscisse a
        //    invocarla, il pacchetto è disabilitato e non si apre.
        runSu("pm disable-user --user 0 " + CAMERA_GALLERY_PACKAGE,
                "disable " + CAMERA_GALLERY_PACKAGE);
    }

    /**
     * @brief Esegue un comando come root con timeout di 3s.
     *
     * Pattern compatibile API 24/25 ({@code Process.waitFor(long, TimeUnit)} è API 26+):
     * thread ausiliario + {@code join(3000)}. Identico a quello di
     * {@link RaspberryConfigFetcher#applyScreenBrightness(int)}.
     *
     * @param cmd   Comando shell da eseguire come root.
     * @param label Etichetta per il log.
     */
    private static void runSu(String cmd, String label) {
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            Thread waiter = new Thread(() -> {
                try { proc.waitFor(); } catch (InterruptedException ignored) {}
            });
            waiter.start();
            waiter.join(3000);
            if (waiter.isAlive()) {
                waiter.interrupt();
                proc.destroy();
                Log.w(TAG, label + " = TIMEOUT (>3s)");
            } else {
                Log.d(TAG, label + " = OK (exit " + proc.exitValue() + ")");
            }
        } catch (Exception e) {
            Log.w(TAG, label + " = ERRORE: " + e.getMessage());
        }
    }
}