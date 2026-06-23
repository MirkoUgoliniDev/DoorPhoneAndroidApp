package com.doorphone.preference.custom_control;



import android.content.Context;
import android.preference.CheckBoxPreference;
import android.util.AttributeSet;
import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;



@SuppressWarnings("deprecation")
public class MyCheckBoxPreference extends CheckBoxPreference {
    private CheckBoxPreference clickedCheckBoxPreference;

    public MyCheckBoxPreference(Context context) {
        super(context);
    }

    public MyCheckBoxPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MyCheckBoxPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public MyCheckBoxPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }


    @Override
    protected void onBindView(View view) {
        super.onBindView(view);

        clickedCheckBoxPreference = this;

        // Ottieni l'oggetto e il titolo della casella di controllo da CheckBoxPreference
        CheckBox checkBox = (CheckBox) view.findViewById(android.R.id.checkbox);
        TextView title = (TextView) view.findViewById(android.R.id.title);


        // Consenti titoli su più righe
        title.setSingleLine(false);

        // Non fare nulla quando si fa clic sulla vista e sul titolo di CheckBoxPreference
        view.setOnClickListener(null);
        title.setOnClickListener(null);

        // Quando si fa clic sulla casella di controllo di CheckBoxPreference, cambia lo stato di CheckBoxPreference
        checkBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CheckBox clickedCheckBox = (CheckBox) v;

                if (clickedCheckBox.isChecked()) {
                    clickedCheckBoxPreference.setChecked(true);
                } else {
                    clickedCheckBoxPreference.setChecked(false);
                }
            }

        });

    }
}
