package com.doorphone.preference.custom_control;




import android.content.Context;
import android.preference.EditTextPreference;
import android.util.AttributeSet;
import android.widget.Toast;
import com.google.common.base.Charsets;
import com.google.common.hash.Hashing;




@SuppressWarnings("deprecation")
public class MyPwdEditTextPreference extends EditTextPreference {
    final static String PWD_SALT = "8L9f7BL5Ot5jCJ0Hu7iO";

    public MyPwdEditTextPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public MyPwdEditTextPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public MyPwdEditTextPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MyPwdEditTextPreference(Context context) {
        super(context);
    }



    @SuppressWarnings("deprecation")
    @Override
    public void setText(String text) {

        String pwdHash = super.getText();

        // Se la password inserita non è vuota ed è composta da almeno 4 caratteri
        if (text != null && text.trim().length() >= 4) {


            // Se recuperiamo la vecchia password, non eseguirne l'hashing
            // Se cambiamo la password, eseguiamo l'hashing
            if (pwdHash == null || pwdHash.trim().length() == 0 || pwdHash.equals(text)) {
                super.setText(text);
            } else if (!pwdHash.equals(text)) {
                pwdHash = Hashing.md5().hashString(text + PWD_SALT, Charsets.UTF_8).toString();
                super.setText(pwdHash);
            }


        } else {
            Toast.makeText(this.getContext(), "La password deve contenere almeno 4 caratteri", Toast.LENGTH_LONG).show();
        }

    }
}
