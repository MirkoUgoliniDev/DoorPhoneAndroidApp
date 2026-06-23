/*

https://stackoverflow.com/questions/531427/how-do-i-display-the-current-value-of-an-android-preference-in-the-preference-su

*/


package com.doorphone.preference.custom_control;


import android.content.Context;
import android.util.AttributeSet;


@SuppressWarnings("deprecation")
public class MyEditTextPreference extends android.preference.EditTextPreference{

        public MyEditTextPreference(Context context, AttributeSet attrs, int defStyle) {
            super(context, attrs, defStyle);
        }

        public MyEditTextPreference(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        public MyEditTextPreference(Context context) {
            super(context);
        }


        @Override
        public CharSequence getSummary() {
            if(super.getSummary() == null) {
                return null;
            }
            String value = super.getText().toString();
            if ( value == "" ){
                return "Not set";
            } else {
                return value;
            }
        }


    }