package se.lublin.mumla.broadcastreceiver;


import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import se.lublin.mumla.app.MumlaActivity;


public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = BroadcastReceiver.class.getSimpleName();

    @Override
    public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "START ACTIVITY:");
            Intent myIntent = new Intent(context, MumlaActivity.class);
            myIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(myIntent);
    }

}
