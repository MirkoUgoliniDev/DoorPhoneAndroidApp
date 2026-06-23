package com.doorphone.preference.custom_control;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.Preference;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.Locale;
import com.doorphone.R;

/**
 * @brief SeekBar inline nelle preferenze.
 *
 * Supporta gli attributi opzionali (senza namespace) {@code min}, {@code max} e {@code suffix}
 * direttamente nel tag XML della preferenza:
 * <pre>
 *   &lt;com.doorphone.preference.custom_control.MySeekBarPreference
 *       android:key="my_key"
 *       android:title="Il mio valore"
 *       min="20"
 *       max="200"
 *       suffix=" s" /&gt;
 * </pre>
 * Se non specificati: {@code min}=0, {@code max}=100, {@code suffix}="".
 * I valori già salvati in SharedPreferences fuori dal range vengono clampati silenziosamente.
 */
@SuppressWarnings("deprecation")
public class MySeekBarPreference extends Preference implements SeekBar.OnSeekBarChangeListener {

    private TextView textValue;
    private int mMin = 0;
    private int mMax = 100;
    private String mSuffix = "";

    public MySeekBarPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(attrs);
    }

    public MySeekBarPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(attrs);
    }

    public MySeekBarPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(attrs);
    }

    public MySeekBarPreference(Context context) {
        super(context);
    }

    private void init(AttributeSet attrs) {
        if (attrs == null) return;
        mMin = attrs.getAttributeIntValue(null, "min", 0);
        mMax = attrs.getAttributeIntValue(null, "max", 100);
        String suffix = attrs.getAttributeValue(null, "suffix");
        mSuffix = suffix != null ? suffix : "";
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        super.onCreateView(parent);
        View view = LayoutInflater.from(getContext()).inflate(R.layout.seekbar, parent, false);
        textValue = view.findViewById(R.id.textValue);

        TextView textTitle = view.findViewById(R.id.textTitle);
        textTitle.setText(getTitle());

        SeekBar seekBar = view.findViewById(R.id.seekBar);
        seekBar.setMax(mMax - mMin);
        seekBar.setOnSeekBarChangeListener(this);

        SharedPreferences preferences = getSharedPreferences();
        int stored = preferences.getInt(getKey(), mMin);
        int clamped = Math.max(mMin, Math.min(mMax, stored));
        textValue.setText(String.format(Locale.getDefault(), "%d%s", clamped, mSuffix));
        seekBar.setProgress(clamped - mMin);
        return view;
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        int value = progress + mMin;
        textValue.setText(String.format(Locale.getDefault(), "%d%s", value, mSuffix));
        SharedPreferences.Editor editor = getEditor();
        editor.putInt(getKey(), value);
        editor.apply();
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) { }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) { }
}
