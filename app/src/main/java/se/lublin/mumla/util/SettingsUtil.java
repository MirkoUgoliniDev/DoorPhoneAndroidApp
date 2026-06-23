
package se.lublin.mumla.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class SettingsUtil {

    public static final String TAG = SettingsUtil.class.getSimpleName();
    private static final String PREFERENCE_NAME = "your_preference_name";

    // SIP Account Preferences
    public static final String SETTING_SIP_HOST = "setting_sip_host";
    public static final String SETTING_COMMAND_DOORPI_NUMBER = "setting_doorpi_number";
    // SIP Account Preferences

    // SIP ECHO Cancellation Preferences
    public static final String SETTING_ECHO_CANCELLATION_TAIL_LENGTH = "setting_echo_cancellation_tail_length";
    public static final String SETTING_ECHO_CANCELLATION_NOISE_REDUCTION = "setting_echo_cancellation_noise_reduction";
    public static final String SETTING_ECHO_CANCELLATION_AGGRESSIVENESS = "setting_echo_cancellation_aggressiveness";
    // SIP ECHO Cancellation Preferences

    // VIDEO Preferences
    public static final String SETTING_VIDEO_PATH = "setting_video_path";
    public static final String SETTING_VIDEO_FORMAT = "setting_video_format";
    // VIDEO Preferences

    // NETWORK
    public static final String SETTING_NETWORK_SERVER_mDNS = "setting_mDNS";
    public static final String SETTING_NETWORK_SERVER_IP = "setting_ServerIP";
    public static final String SETTING_NETWORK_ACTION_PORT = "settings_action_port";
    // NETWORK

    //COMMANDS  Preferences
    public static final String SETTING_ADVANCED_UNLOCK_DOOR = "setting_unlock_door";
    public static final String SETTING_ADVANCED_LIGHT_INT_ON = "setting_light_int_on";
    public static final String SETTING_ADVANCED_LIGHT_EXT_ON = "setting_light_ext_on";
    //COMMANDS  Preferences

    // NOTIFICATION
    public static final String SETTING_SHOW_NOTIFICATION = "setting_show_notification";
    // NOTIFICATION

    // AUDIO Preferences
    public static final String SETTING_ADVANCED_USE_RINGTONE = "setting_use_ringtone";
    public static final String SETTING_ADVANCED_USE_VIBRATOR = "setting_use_vibrator";
    public static final String SETTING_ADVANCED_RINGTONE = "setting_ringtone";
    // AUDIO Preferences

    // ADVANCED Preferences
    public static final String SETTING_ADVANCED_START_SERVICE_ON_BOOT = "setting_start_service_on_boot";
    public static final String SETTING_PREF_KIOSK_MODE = "settings_kiosk_mode";
    public static final String SETTING_ADVANCED_START_APP_ON_INCOMING_CALL = "setting_start_app_on_incoming_call";
    public static final String SETTINGS_ADVANCED_CLOSE_AFTER_CALL = "setting_close_after_call";
    public static final String SETTING_ADVANCED_DIMM_LUMINOSITY_AFTER = "setting_dimmluminosityafter";
    // ADVANCED Preferences

    // UI Preferences
    public static final String SETTING_UI_USE_SURFACE_FOR_ANSWER = "setting_use_surface_for_answer";
    public static final String SETTING_UI_USE_SURFACE_FOR_UNLOCK = "setting_use_surface_for_unlock";
    public static final String SETTING_UI_SHOWBUTTON_CALL = "setting_show_botton_call";
    public static final String SETTING_UI_SHOWBUTTON_UNLOCK = "setting_show_botton_unlock";
    // UI Preferences

    // Services
    public static final String SETTING_SERVICES_ENABLE_LOGGIN = "settings_services_enable_logging";
    public static final String SETTING_SERVICES_UPDATE_FREQUENCY = "setting_services_update_frequency";
    // Services

    public static int getEchoCancellationTailLength(Context context) {
        String tailLength = getSharedPreferences(context).getString(SETTING_ECHO_CANCELLATION_TAIL_LENGTH, "30");
        int retVal = 30;
        try {
            retVal = Integer.parseInt(tailLength);
        } catch (NumberFormatException exc) {
            Log.e(TAG, "TailLength could not be converted to integer");
        }
        return retVal;
    }

    // VIDEO
    public static String getVideoPathLocal(Context context) {
        return getSharedPreferences(context).getString(SETTING_VIDEO_PATH, "http://doorpi2.local:9090/?action=stream");
    }
    // VIDEO

    // NETWORK
    public static Integer getACTION_PORT(Context context) {
        String actionPort = getSharedPreferences(context).getString(SETTING_NETWORK_ACTION_PORT, "8080");
        int retVal = 0;
        try {
            retVal = Integer.parseInt(actionPort);
        } catch (NumberFormatException exc) {
            Log.e(TAG, "ActionPort could not be converted to integer");
        }
        return retVal;
    }
    // NETWORK

    // ACTION
    public static String getUNLOCK_DOOR(Context context) {
        return getSharedPreferences(context).getString(SETTING_ADVANCED_UNLOCK_DOOR, "unlock_door");
    }

    public static String getLIGHT_INT_ON(Context context) {
        return getSharedPreferences(context).getString(SETTING_ADVANCED_LIGHT_INT_ON, "luce_interna_on");
    }

    public static String getLIGHT_EXT_ON(Context context) {
        return getSharedPreferences(context).getString(SETTING_ADVANCED_LIGHT_EXT_ON, "luce_esterna_on");
    }
    // ACTION

    // ADVANCED
    public static boolean getIsServiceAutostart(Context context) {
        return getSharedPreferences(context).getBoolean(SETTING_ADVANCED_START_SERVICE_ON_BOOT, true);
    }

    public static boolean getKIOSKMODE(Context context) {
        return getSharedPreferences(context).getBoolean(SETTING_PREF_KIOSK_MODE, false);
    }

    public static void setKIOSKMODE(final boolean active, Context context) {
        SharedPreferences sp = getSharedPreferences(context);
        sp.edit().putBoolean(SETTING_PREF_KIOSK_MODE, active).apply();
    }

    public static boolean getUseRingtone(Context context) {
        return getSharedPreferences(context).getBoolean(SETTING_ADVANCED_USE_RINGTONE, true);
    }

    public static boolean getUseVibrator(Context context) {
        return getSharedPreferences(context).getBoolean(SETTING_ADVANCED_USE_VIBRATOR, true);
    }

    public static String getRingtone(Context context) {
        return getSharedPreferences(context).getString(SETTING_ADVANCED_RINGTONE, "");
    }

    public static boolean getCloseAfterCall(Context context) {
        return getSharedPreferences(context).getBoolean(SETTINGS_ADVANCED_CLOSE_AFTER_CALL, true);
    }

    public static int getDIMM_LUMINOSITY_AFTER(Context context) {
        String dimmLuminosityAfter = getSharedPreferences(context).getString(SETTING_ADVANCED_DIMM_LUMINOSITY_AFTER, "30");
        int retVal = 0;
        try {
            retVal = Integer.parseInt(dimmLuminosityAfter);
        } catch (NumberFormatException exc) {
            Log.e(TAG, "DimmLuminosityAfter could not be converted to integer");
        }
        return retVal;
    }
    // ADVANCED

    // NOTIFICATION
    public static boolean getShowNotification(Context context) {
        return getSharedPreferences(context).getBoolean(SETTING_SHOW_NOTIFICATION, true);
    }

    // NOTIFICATION

    public static boolean getStartAppOnIncomingCall(Context context) {
        return getSharedPreferences(context).getBoolean(SETTING_ADVANCED_START_APP_ON_INCOMING_CALL, true);
    }

    public static String getDoorPiNumber(Context context) {
        return getSharedPreferences(context).getString(SETTING_COMMAND_DOORPI_NUMBER, "1000");
    }

    // Services
    public static boolean getServicesEnableLogin(Context context) {
        return getSharedPreferences(context).getBoolean(SETTING_SERVICES_ENABLE_LOGGIN, false);
    }

    public static Integer getServicesUpdateFrequency(Context context) {
        return getSharedPreferences(context).getInt(SETTING_SERVICES_UPDATE_FREQUENCY, 120);
    }
    // Services

    private static SharedPreferences getSharedPreferences(Context context) {
        return context.getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE);
    }

    public static void migratePreferences(Context context) {
        SharedPreferences defaultPrefs = getSharedPreferences(context); // Use the named preferences directly
        SharedPreferences namedPrefs = context.getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE);

        SharedPreferences.Editor editor = namedPrefs.edit();
        for (Map.Entry<String, ?> entry : defaultPrefs.getAll().entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Boolean) {
                editor.putBoolean(key, (Boolean) value);
            } else if (value instanceof Float) {
                editor.putFloat(key, (Float) value);
            } else if (value instanceof Integer) {
                editor.putInt(key, (Integer) value);
            } else if (value instanceof Long) {
                editor.putLong(key, (Long) value);
            } else if (value instanceof String) {
                editor.putString(key, (String) value);
            } else if (value instanceof Set) {
                // Safe cast with instanceof check
                Set<?> genericSet = (Set<?>) value;
                Set<String> stringSet = new HashSet<>();
                for (Object obj : genericSet) {
                    if (obj instanceof String) {
                        stringSet.add((String) obj);
                    }
                }
                editor.putStringSet(key, stringSet);
            }
        }
        editor.apply();
    }
}
