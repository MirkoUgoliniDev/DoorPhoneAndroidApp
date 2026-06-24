package com.doorphone.screenoff;




import android.content.Context;
import android.telephony.PhoneStateListener;





/**
 * Listener class which detects phone state changes and locks the device when a
 * call is initiated or answered.
 */
@SuppressWarnings("deprecation")
public class ScreenOffPhoneListener extends PhoneStateListener {
	private Context context;

	public ScreenOffPhoneListener(Context context) {
		this.context = context;
	}


	@Override
	public void onCallStateChanged(int state, String incomingNumber) {
		// No-op: l'azione di blocco schermo su cambio stato chiamata è disattivata.
	}


}
