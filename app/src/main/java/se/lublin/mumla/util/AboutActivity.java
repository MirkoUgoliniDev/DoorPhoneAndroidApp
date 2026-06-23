package se.lublin.mumla.util;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import se.lublin.mumla.R;

/**
 * @brief Activity che mostra le informazioni sull'applicazione MumlaO.
 *
 * Visualizza il layout {@code about.xml} con versione, autore e note
 * sull'applicazione citofono basata su Mumble/DoorPi.
 */
public class AboutActivity extends AppCompatActivity {

    private static final String TAG = AboutActivity.class.getSimpleName();

    /** @brief Inizializza la schermata About e carica il layout. */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.about);
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

}
