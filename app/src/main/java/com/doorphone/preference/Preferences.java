/*

https://www.viralpatel.net/android-preferences-activity-example/

per eliminare le preferenze:
context.getSharedPreferences("YOUR_PREFS", 0).edit().clear().commit();
 */


package com.doorphone.preference;



import android.annotation.TargetApi;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.util.List;
import com.doorphone.R;
import com.doorphone.Settings;
import com.doorphone.screenoff.ScreenOffAdminReceiver;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;




@SuppressWarnings("deprecation")
public class Preferences extends PreferenceActivity {

    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);


        setTheme(Settings.getInstance(this).getTheme());
    }




    @Override
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public void onBuildHeaders(List<Header> target) {
        // Carica la lista delle preferenze Generali
        loadHeadersFromResource(R.xml.preference_headers, target);
    }




    @Override
    protected boolean isValidFragment(String fragmentName) {
        return DoorPhonePreferenceFragment.class.getName().equals(fragmentName);
    }




    private static void configureAudioPreferences(final PreferenceScreen screen) {
        ListPreference inputPreference = (ListPreference) screen.findPreference(Settings.PREF_INPUT_METHOD);
        inputPreference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                updateAudioDependents(screen, (String) newValue);
                return true;
            }
        });

        // Scan each bitrate and determine if the device supports it
        ListPreference inputQualityPreference = (ListPreference) screen.findPreference(Settings.PREF_INPUT_RATE);
        String[] bitrateNames = new String[inputQualityPreference.getEntryValues().length];

        for(int x=0;x<bitrateNames.length;x++) {
            int bitrate = Integer.parseInt(inputQualityPreference.getEntryValues()[x].toString());
            boolean supported = AudioRecord.getMinBufferSize(bitrate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0;
            bitrateNames[x] = bitrate+"Hz" + (supported ? "" : " (unsupported)");
        }

        inputQualityPreference.setEntries(bitrateNames);

        updateAudioDependents(screen, inputPreference.getValue());
    }


    private static void updateAudioDependents(PreferenceScreen screen, String inputMethod) {
        PreferenceCategory pttCategory = (PreferenceCategory) screen.findPreference("ptt_settings");
        PreferenceCategory vadCategory = (PreferenceCategory) screen.findPreference("vad_settings");
        pttCategory.setEnabled(Settings.ARRAY_INPUT_METHOD_PTT.equals(inputMethod));
        vadCategory.setEnabled(Settings.ARRAY_INPUT_METHOD_VOICE.equals(inputMethod));
    }



    /**
     * @brief Configura la preference "Abilita Device Admin" nella sezione DoorPi.
     *
     * Se l'app è già Device Admin, la voce mostra lo stato e viene disabilitata.
     * Se non lo è, al click esegue "dpm set-active-admin" via root (dispositivo rootato)
     * in un thread separato per non bloccare la UI, poi aggiorna la voce sul main thread.
     * Il Device Admin è necessario per chiamare lockNow() e spegnere il display post-chiamata.
     */
    private static void configureDoorPiPreferences(final Context context, PreferenceScreen screen) {
        Preference adminPref = screen.findPreference("enable_device_admin");
        if (adminPref == null) return;

        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName adminComp = new ComponentName(context, ScreenOffAdminReceiver.class);

        if (dpm.isAdminActive(adminComp)) {
            // Già abilitato: mostra stato e disabilita il click
            adminPref.setSummary("Device Admin attivo — lockNow() operativo");
            adminPref.setEnabled(false);
            return;
        }

        // Non ancora abilitato: al click esegue il comando root in background
        adminPref.setOnPreferenceClickListener(pref -> {
            // Il componente deve includere il package name del flavor corrente
            String component = context.getPackageName()
                    + "/com.doorphone.screenoff.ScreenOffAdminReceiver";

            new Thread(() -> {
                boolean success = false;
                try {
                    Process proc = Runtime.getRuntime().exec(
                            new String[]{"su", "-c", "dpm set-active-admin " + component});
                    proc.waitFor();
                    success = dpm.isAdminActive(adminComp);
                } catch (Exception e) {
                    // Errore root o comando non disponibile
                }

                final boolean ok = success;
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (ok) {
                        pref.setSummary("Device Admin attivo — lockNow() operativo");
                        pref.setEnabled(false);
                        Toast.makeText(context, "Device Admin abilitato", Toast.LENGTH_SHORT).show();
                    } else {
                        // Mostra le istruzioni per l'abilitazione manuale
                        String pkg = context.getPackageName();
                        Toast.makeText(context,
                                "Abilitazione fallita.\n" +
                                "Metodo 1 — Settings Android:\n" +
                                "  Sicurezza → Amministratori dispositivo → abilita DoorPhone\n\n" +
                                "Metodo 2 — ADB da PC:\n" +
                                "  adb shell dpm set-active-admin " + pkg + "/com.doorphone.screenoff.ScreenOffAdminReceiver",
                                Toast.LENGTH_LONG).show();
                    }
                });
            }).start();

            return true;
        });
    }


    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public static class DoorPhonePreferenceFragment extends PreferenceFragment {

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            String section = getArguments().getString("settings");

            if ("general".equals(section)) {
                addPreferencesFromResource(R.xml.settings_general);
                getActivity().setTitle(R.string.general);

            } else if ("doorpi".equals(section)) {
                addPreferencesFromResource(R.xml.settings_doorpi);
                getActivity().setTitle(R.string.doorpi_piano_title);
                configureDoorPiPreferences(getActivity(), getPreferenceScreen());

            } else if ("audio".equals(section)) {
                addPreferencesFromResource(R.xml.settings_audio);
                configureAudioPreferences(getPreferenceScreen());
                getActivity().setTitle(R.string.audio);
            }


        }


    }



}
