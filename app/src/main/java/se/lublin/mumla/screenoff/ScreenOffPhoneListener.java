package se.lublin.mumla.screenoff;




import android.content.Context;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import se.lublin.mumla.ui.VideoVLCActivity;





/**
 * Listener class which detects phone state changes and locks the device when a
 * call is initiated or answered.
 */
@SuppressWarnings("deprecation")
public class ScreenOffPhoneListener extends PhoneStateListener {
	private Context context;
	private boolean incomingCall;

	public ScreenOffPhoneListener(Context context) {
		this.context = context;
	}


	@Override
	public void onCallStateChanged(int state, String incomingNumber) {
		switch (state) {
		case TelephonyManager.CALL_STATE_RINGING:
			incomingCall = true;
			break;
		case TelephonyManager.CALL_STATE_OFFHOOK:
			//turnScreenOff(incomingCall ? 400 : 1200);
			incomingCall = false;
			break;
		default:
			incomingCall = false;
			break;
		}
	}


}
