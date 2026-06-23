package com.doorphone.screenoff;




import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;



/**
 * Generic class which receives various events and dispatches them to the
 * appropriate listeners.
 */
@SuppressWarnings("deprecation")
public class ScreenOffEventReceiver extends BroadcastReceiver {

	/** Listener statico: registrato una volta sola per evitare accumulo ad ogni broadcast. */
	private static ScreenOffPhoneListener sPhoneListener = null;

	@Override
	public void onReceive(Context context, Intent intent) {
		if (sPhoneListener == null) {
			TelephonyManager telephony = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
			sPhoneListener = new ScreenOffPhoneListener(context);
			telephony.listen(sPhoneListener, PhoneStateListener.LISTEN_CALL_STATE);
		}
	}

}
