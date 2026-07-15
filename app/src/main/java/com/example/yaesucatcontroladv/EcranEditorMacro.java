package com.example.yaesucatcontroladv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class EcranEditorMacro {

    private final Context context;
    private ScrollView viewPrincipal;
    // Câmpuri pentru conținutul macroului și pentru titlul butonului
    private final EditText[] editTextsMacros = new EditText[8];
    private final EditText[] editTitlesMacros = new EditText[8];

    // Texte implicite corelate 100% cu cele din EcranRttyDroid
    private final String[] macrouriImplicite = {
            "CQ CQ CQ DE YO9VA YO9VA K\n",
            "UR RST 599 599 = BTU DE YO9VA K\n",
            "73 GL SK DE YO9VA SK\n",
            "NAME IS EUGENE QTH ALEXANDRIA = BTU DE YO9VA K\n",
            "MY RIG IS YAESU FTX1 FIELD = BTU DE YO9VA K\n",
            "WEATHER HERE IS SUNNY TEMP 25C K\n",
            "PSE QSL ON LOTW = K\n",
            "TNX FOR QSO BI BI DE YO9VA SK\n"
    };

    public EcranEditorMacro(Context context) {
        this.context = context;
        construiesteInterfata();
        incarcaMacrouriSalvate();
    }

    public ScrollView getView() {
        return viewPrincipal;
    }

    private void construiesteInterfata() {
        viewPrincipal = new ScrollView(context);
        viewPrincipal.setBackgroundColor(Color.parseColor("#121212")); // Fundal închis

        LinearLayout llMain = new LinearLayout(context);
        llMain.setOrientation(LinearLayout.VERTICAL);
        llMain.setPadding(35, 35, 35, 35);

        TextView tvTitlu = new TextView(context);
        tvTitlu.setText("RTTY MACROS EDITOR (8 CANALE)");
        tvTitlu.setTextColor(Color.WHITE);
        tvTitlu.setTextSize(19);
        tvTitlu.setTypeface(null, Typeface.BOLD);
        tvTitlu.setGravity(Gravity.CENTER);
        tvTitlu.setPadding(0, 10, 0, 40);
        llMain.addView(tvTitlu);

        // Generăm dinamic cele 8 căsuțe și butoane de salvare individuală
        for (int i = 0; i < 8; i++) {
            final int index = i;

            TextView tvLabel = new TextView(context);
            tvLabel.setText("Macro M" + (i + 1) + " (Title & Content):");
            tvLabel.setTextColor(Color.parseColor("#9E9E9E"));
            tvLabel.setTextSize(13);
            tvLabel.setPadding(10, 10, 0, 5);
            llMain.addView(tvLabel);

            LinearLayout randMacro = new LinearLayout(context);
            randMacro.setOrientation(LinearLayout.HORIZONTAL);
            randMacro.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams pRand = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            pRand.setMargins(0, 0, 0, 15);
            randMacro.setLayoutParams(pRand);

            // Câmp pentru TITLU (scurt, max 6-7 caractere recomandat)
            editTitlesMacros[i] = new EditText(context);
            editTitlesMacros[i].setHint("Title");
            editTitlesMacros[i].setHintTextColor(Color.GRAY);
            editTitlesMacros[i].setTextColor(Color.parseColor("#00E5FF"));
            editTitlesMacros[i].setTextSize(14);
            editTitlesMacros[i].setSingleLine(true);
            editTitlesMacros[i].setFilters(new android.text.InputFilter[] { new android.text.InputFilter.LengthFilter(8) });
            editTitlesMacros[i].setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f));
            randMacro.addView(editTitlesMacros[i]);

            // Câmp pentru CONȚINUT
            editTextsMacros[i] = new EditText(context);
            editTextsMacros[i].setHint("Macro content...");
            editTextsMacros[i].setHintTextColor(Color.GRAY);
            editTextsMacros[i].setTextColor(Color.WHITE);
            editTextsMacros[i].setTextSize(14);
            editTextsMacros[i].setSingleLine(false);
            editTextsMacros[i].setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2.2f));
            randMacro.addView(editTextsMacros[i]);

            Button btnSaveRow = new Button(context);
            btnSaveRow.setText("SAVE");
            btnSaveRow.setBackgroundColor(Color.parseColor("#007ACC"));
            btnSaveRow.setTextColor(Color.WHITE);
            btnSaveRow.setTextSize(10);
            LinearLayout.LayoutParams pBtnRow = new LinearLayout.LayoutParams(0, 115, 0.8f);
            pBtnRow.setMargins(10, 0, 0, 0);
            btnSaveRow.setLayoutParams(pBtnRow);
            btnSaveRow.setOnClickListener(v -> {
                String titlu = editTitlesMacros[index].getText().toString();
                String text = editTextsMacros[index].getText().toString();
                
                SharedPreferences prefs = context.getSharedPreferences("RTTY_MACROS", Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString("rtty_title_" + index, titlu);
                editor.putString("rtty_content_" + index, text);
                editor.apply();
                Toast.makeText(context, "Macro M" + (index + 1) + " saved!", Toast.LENGTH_SHORT).show();
            });
            randMacro.addView(btnSaveRow);

            llMain.addView(randMacro);
        }

        // BUTON MASTER: SALVEAZĂ TOT
        Button btnSaveAll = new Button(context);
        btnSaveAll.setText("SAVE ALL 8 MACROS");
        btnSaveAll.setBackgroundColor(Color.parseColor("#2E7D32")); // Verde emisie
        btnSaveAll.setTextColor(Color.WHITE);
        btnSaveAll.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams pSaveAll = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 140);
        pSaveAll.setMargins(0, 30, 0, 20);
        btnSaveAll.setLayoutParams(pSaveAll);
        btnSaveAll.setOnClickListener(v -> {
            SharedPreferences prefs = context.getSharedPreferences("RTTY_MACROS", Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            for (int i = 0; i < 8; i++) {
                if (editTextsMacros[i] != null && editTitlesMacros[i] != null) {
                    editor.putString("rtty_title_" + i, editTitlesMacros[i].getText().toString());
                    editor.putString("rtty_content_" + i, editTextsMacros[i].getText().toString());
                }
            }
            editor.apply();
            Toast.makeText(context, "All 8 RTTY macros synchronized!", Toast.LENGTH_SHORT).show();
        });
        llMain.addView(btnSaveAll);

        viewPrincipal.addView(llMain);
    }

    private void incarcaMacrouriSalvate() {
        SharedPreferences prefs = context.getSharedPreferences("RTTY_MACROS", Context.MODE_PRIVATE);
        for (int i = 0; i < 8; i++) {
            String titluIncarcat = prefs.getString("rtty_title_" + i, "M" + (i + 1));
            String textIncarcat = prefs.getString("rtty_content_" + i, macrouriImplicite[i]);
            
            if (editTitlesMacros[i] != null) {
                editTitlesMacros[i].setText(titluIncarcat);
            }
            if (editTextsMacros[i] != null) {
                editTextsMacros[i].setText(textIncarcat);
            }
        }
    }
}
