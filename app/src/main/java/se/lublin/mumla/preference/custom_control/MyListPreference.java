package se.lublin.mumla.preference.custom_control;


/*
https://stackoverflow.com/questions/531427/how-do-i-display-the-current-value-of-an-android-preference-in-the-preference-su
https://stackoverflow.com/questions/11346916/listpreference-use-string-array-as-entry-and-integer-array-as-entry-values-does
*/

import android.content.Context;
import android.preference.ListPreference;
import android.util.AttributeSet;
import android.util.Log;


@SuppressWarnings("deprecation")
public class MyListPreference extends ListPreference  {

    public MyListPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public MyListPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }




    public MyListPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MyListPreference(Context context) {
        super(context);
    }


    /*
    @Override
    protected void onDialogClosed(boolean positiveResult) {
        super.onDialogClosed(positiveResult);
        if (positiveResult) {
            setSummary(getSummary());
        }
    }

    @Override
    public CharSequence getSummary() {
        int pos = findIndexOfValue(getValue());
        return getEntries()[pos];
    }
    */


    @Override
    protected boolean persistString(String value) {
        int intValue = Integer.parseInt(value);
        return persistInt(intValue);
    }


    @Override
    protected String getPersistedString(String defaultReturnValue) {
        int intValue;

        if (defaultReturnValue != null) {

            int intDefaultReturnValue = Integer.parseInt(defaultReturnValue);

            intValue = getPersistedInt(intDefaultReturnValue);

        } else {

            if (getPersistedInt(0) == getPersistedInt(1)) {
                intValue = getPersistedInt(0);
            } else {
                throw new IllegalArgumentException("Cannot get an int without a default return value");
            }
        }


        return Integer.toString(intValue);

    }


}