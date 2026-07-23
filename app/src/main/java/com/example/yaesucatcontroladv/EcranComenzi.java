package com.example.yaesucatcontroladv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

public class EcranComenzi {

    private final Context context;
    private final RadioManager radioManager;
    private ScrollView viewPrincipal;

    // Elemente grafice care trebuie actualizate din exterior (prin mesaje CAT)
    private TextView tvSMeterValue;
    private ProgressBar pbSMeter;
    private Button btnLSB, btnUSB, btnCW, btnRTTYL;
    private Button btnBkInToggle, btnApfToggle, btnWidthToggle, btnConnect;
    private LinearLayout containerButoaneCwExtra;

    // Variabile de stare internă pentru butoane
    private boolean stareBkInOn = false;
    private boolean stareApfOn = false;
    private boolean stareWidth200On = false;
    private int modLucruCurent = 1; // 1 = Voce, 2 = CW

    public EcranComenzi(Context context, RadioManager radioManager) {
        this.context = context;
        this.radioManager = radioManager;
        construiesteInterfata();
    }

    public ScrollView getView() {
        return viewPrincipal;
    }

    private void construiesteInterfata() {
        viewPrincipal = new ScrollView(context);
        LinearLayout llCAT = new LinearLayout(context);
        llCAT.setOrientation(LinearLayout.VERTICAL);
        llCAT.setPadding(30, 30, 30, 30);

        // --- BUTTON STATIE CONECTARE ---
        btnConnect = new Button(context);
        btnConnect.setText("CONNECT RADIO STATION");
        btnConnect.setBackgroundColor(Color.parseColor("#007ACC"));
        btnConnect.setTextColor(Color.WHITE);
        btnConnect.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams pConn = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 140);
        pConn.setMargins(0, 0, 0, 30);
        btnConnect.setLayoutParams(pConn);
        btnConnect.setOnClickListener(v -> radioManager.conecteazaStatie());
        llCAT.addView(btnConnect);

        // --- S-METRU GRAFIC ---
        LinearLayout randSMeter = new LinearLayout(context);
        randSMeter.setOrientation(LinearLayout.HORIZONTAL);
        randSMeter.setGravity(Gravity.CENTER_VERTICAL);
        randSMeter.setPadding(10, 15, 10, 15);

        tvSMeterValue = new TextView(context);
        tvSMeterValue.setText("S: 0");
        tvSMeterValue.setTextColor(Color.parseColor("#007ACC"));
        tvSMeterValue.setTextSize(15);
        tvSMeterValue.setTypeface(null, Typeface.BOLD);
        tvSMeterValue.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.2f));
        tvSMeterValue.setGravity(Gravity.CENTER);

        pbSMeter = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        pbSMeter.setScaleY(3.0f); // Îngroșare scală Y conform cerințelor tale
        pbSMeter.setMax(255);
        pbSMeter.setProgress(0);
        pbSMeter.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#FFB300"))); // Chihlimbar
        pbSMeter.setLayoutParams(new LinearLayout.LayoutParams(0, 65, 0.8f));

        randSMeter.addView(tvSMeterValue);
        randSMeter.addView(pbSMeter);
        llCAT.addView(randSMeter);

        // --- BUTOANE SCHIMBARE MODURI DE LUCRU ---
        LinearLayout rMod1 = creeazaRandButoane();
        btnLSB = creeazaButonMod("LSB", "#757575", v -> {
            radioManager.trimiteComandaCAT("MD01;");
            actualizeazaInterfataModuriInstant("01");
        });
        btnUSB = creeazaButonMod("USB", "#757575", v -> {
            radioManager.trimiteComandaCAT("MD02;");
            actualizeazaInterfataModuriInstant("02");
        });
        rMod1.addView(btnLSB);
        rMod1.addView(btnUSB);
        llCAT.addView(rMod1);

        LinearLayout rMod2 = creeazaRandButoane();
        btnCW = creeazaButonMod("CW", "#757575", v -> {
            radioManager.trimiteComandaCAT("MD03;");
            actualizeazaInterfataModuriInstant("03");
        });
        btnRTTYL = creeazaButonMod("RTTY-L", "#757575", v -> {
            radioManager.trimiteComandaCAT("MD06;");
            actualizeazaInterfataModuriInstant("06");
        });
        rMod2.addView(btnCW);
        rMod2.addView(btnRTTYL);
        llCAT.addView(rMod2);

        // --- ETICHETĂ MESAJE CANALE ---
        TextView tvL = new TextView(context);
        tvL.setText("CW MESSAGES / DVS VOICE");
        tvL.setTextColor(Color.BLACK);
        tvL.setTypeface(null, Typeface.BOLD);
        tvL.setGravity(Gravity.CENTER);
        tvL.setPadding(0, 30, 0, 15);
        llCAT.addView(tvL);

        // --- CASUTE MEMORII M1 - M5 ---
        LinearLayout rCw = creeazaRandButoane();
        for (int i = 1; i <= 5; i++) {
            final int canal = i;
            Button btnM = new Button(context);
            btnM.setText("M" + i);
            btnM.setBackgroundColor(Color.parseColor("#607D8B"));
            btnM.setTextColor(Color.WHITE);
            btnM.setLayoutParams(new LinearLayout.LayoutParams(0, 130, 1f));
            btnM.setOnClickListener(v -> executaMesajPeCanal(canal));
            rCw.addView(btnM);
        }
        llCAT.addView(rCw);

        // --- BUTON OPRIRE TRANSMISIE (STOP) ---
        Button btnStop = new Button(context);
        btnStop.setText("STOP TRANSMISSION");
        btnStop.setBackgroundColor(Color.parseColor("#B71C1C"));
        btnStop.setTextColor(Color.WHITE);
        btnStop.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams pStop = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 130);
        pStop.setMargins(0, 40, 0, 0);
        btnStop.setLayoutParams(pStop);
        btnStop.setOnClickListener(v -> radioManager.trimiteComandaCAT(modLucruCurent == 1 ? "PB00;" : "KY00;"));
        llCAT.addView(btnStop);

        // --- CONTAINER DINAMIC: MATRICE EXTRA REPARATĂ PENTRU MODUL CW ---
        containerButoaneCwExtra = new LinearLayout(context);
        containerButoaneCwExtra.setOrientation(LinearLayout.VERTICAL);
        containerButoaneCwExtra.setVisibility(View.GONE);
        LinearLayout.LayoutParams pCwExtra = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pCwExtra.setMargins(0, 30, 0, 0);
        containerButoaneCwExtra.setLayoutParams(pCwExtra);

        LinearLayout.LayoutParams pButonOrizontal = new LinearLayout.LayoutParams(0, 130, 1f);
        pButonOrizontal.setMargins(5, 0, 5, 15);

        // Rândul 1 CW (Zero-In | BK-IN)
        LinearLayout randCw1 = new LinearLayout(context);
        randCw1.setOrientation(LinearLayout.HORIZONTAL);
        Button btnZi1 = new Button(context);
        btnZi1.setText("ZI1 (Zero-In)");
        btnZi1.setBackgroundColor(Color.parseColor("#455A64"));
        btnZi1.setTextColor(Color.WHITE);
        btnZi1.setLayoutParams(pButonOrizontal);
        btnZi1.setOnClickListener(v -> radioManager.trimiteComandaCAT("ZI0;"));
        randCw1.addView(btnZi1);

        btnBkInToggle = new Button(context);
        btnBkInToggle.setText("BK-IN: OFF");
        btnBkInToggle.setBackgroundColor(Color.parseColor("#757575"));
        btnBkInToggle.setTextColor(Color.WHITE);
        btnBkInToggle.setLayoutParams(pButonOrizontal);
        btnBkInToggle.setOnClickListener(v -> radioManager.trimiteComandaCAT(stareBkInOn ? "BI0;" : "BI1;"));
        randCw1.addView(btnBkInToggle);
        containerButoaneCwExtra.addView(randCw1);

        // Rândul 2 CW (APF | WIDTH)
        LinearLayout randCw2 = new LinearLayout(context);
        randCw2.setOrientation(LinearLayout.HORIZONTAL);
        btnApfToggle = new Button(context);
        btnApfToggle.setText("APF: OFF");
        btnApfToggle.setBackgroundColor(Color.parseColor("#757575"));
        btnApfToggle.setTextColor(Color.WHITE);
        btnApfToggle.setLayoutParams(pButonOrizontal);
        btnApfToggle.setOnClickListener(v -> radioManager.trimiteComandaCAT(stareApfOn ? "CO020000;" : "CO020001;"));
        randCw2.addView(btnApfToggle);

        btnWidthToggle = new Button(context);
        btnWidthToggle.setText("WIDTH: STND");
        btnWidthToggle.setBackgroundColor(Color.parseColor("#757575"));
        btnWidthToggle.setTextColor(Color.WHITE);
        btnWidthToggle.setLayoutParams(pButonOrizontal);
        btnWidthToggle.setOnClickListener(v -> radioManager.trimiteComandaCAT(stareWidth200On ? "SH0000;" : "SH0004;"));
        randCw2.addView(btnWidthToggle);
        containerButoaneCwExtra.addView(randCw2);

        llCAT.addView(containerButoaneCwExtra);
        viewPrincipal.addView(llCAT);
    }

    private LinearLayout creeazaRandButoane() {
        LinearLayout r = new LinearLayout(context);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 5, 0, 15);
        r.setLayoutParams(p);
        return r;
    }

    private Button creeazaButonMod(String t, String c, View.OnClickListener cl) {
        Button b = new Button(context);
        b.setText(t);
        b.setBackgroundColor(Color.parseColor(c));
        b.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 130, 1f);
        p.setMargins(5, 0, 5, 0);
        b.setLayoutParams(p);
        b.setOnClickListener(cl);
        return b;
    }

    private void executaMesajPeCanal(int canal) {
        // --- CONFIGURAT CONFORM TESTULUI LIVE: Trimitem DOAR comanda unică KY0 pentru CW ---
        android.content.SharedPreferences prefs = context.getSharedPreferences("CW_MACROS", Context.MODE_PRIVATE);
        String content = prefs.getString("cw_content_" + (canal - 1), "");

        if (modLucruCurent == 1) {
            // 1. MOD VOCE (LSB/USB): Rămâne neschimbat pe PB0
            radioManager.trimiteComandaCAT("PB0" + canal + ";");
        } else {
            // 2. MOD TELEGRAFIE (CW)
            if (!content.isEmpty()) {
                radioManager.trimiteComandaCAT("KY " + content.toUpperCase() + ";");
            } else {
                // Dacă e gol, forțăm strict declanșarea memoriei prin KY0, eliminând complet formatul KY1 parazit!
                radioManager.trimiteComandaCAT("KY0" + canal + ";");
            }
        }
    }


    // --- PARSER LOCAL PENTRU COORDONAREA LIVE A INTERFEȚEI DUPĂ RĂSPUNSURILE CAT ---
    public void proceseazaDateCATInEcran(String prefix, String comandaScurta) {
        if (comandaScurta == null) return; // FIX: protecție null

        if (prefix.equals("MD")) {
            if (comandaScurta.length() >= 2) actualizeazaInterfataModuriInstant(comandaScurta.substring(2));
        } else if (prefix.equals("BI")) {
            if (comandaScurta.length() >= 3) {
                stareBkInOn = comandaScurta.substring(2).startsWith("1");
                btnBkInToggle.setText(stareBkInOn ? "BK-IN: ON" : "BK-IN: OFF");
                btnBkInToggle.setBackgroundColor(Color.parseColor(stareBkInOn ? "#2E7D32" : "#757575"));
            }
        } else if (prefix.equals("CO") && comandaScurta.startsWith("CO02")) {
            if (comandaScurta.length() >= 6) {
                stareApfOn = comandaScurta.endsWith("01");
                btnApfToggle.setText(stareApfOn ? "APF: ON" : "APF: OFF");
                btnApfToggle.setBackgroundColor(Color.parseColor(stareApfOn ? "#2E7D32" : "#757575"));
            }
        } else if (prefix.equals("SH") && comandaScurta.startsWith("SH0")) {
            if (comandaScurta.length() >= 5) {
                stareWidth200On = comandaScurta.endsWith("04");
                btnWidthToggle.setText(stareWidth200On ? "WIDTH: 200Hz" : "WIDTH: STND");
                btnWidthToggle.setBackgroundColor(Color.parseColor(stareWidth200On ? "#2E7D32" : "#757575"));
            }
        } else if (prefix.equals("RM")) {
            try {
                if (comandaScurta.length() < 6) return; // FIX: verificare lungime
                // Verificăm dacă este pentru S-Meter (RM1); RM8 (tensiune) este ignorat aici
                if (comandaScurta.charAt(2) != '1') return;

                int nivelBrut = Integer.parseInt(comandaScurta.substring(3, 6));
                pbSMeter.setProgress(nivelBrut);
                String label = "S " + (nivelBrut / 15);
                if (nivelBrut > 110) label = "S9+";
                tvSMeterValue.setText(label);
                pbSMeter.setProgressTintList(android.content.res.ColorStateList.valueOf(
                        Color.parseColor(nivelBrut > 110 ? "#D32F2F" : "#FFB300")));
            } catch (Exception e) { /* parsing failed, ignore */ }
        } else if (prefix.equals("PC")) {
            if (comandaScurta.length() >= 3) {
                char pfx = comandaScurta.charAt(2);
                String model = (pfx == '1') ? "FTX1 FIELD" : "FTX1 OPTIMA";
                btnConnect.setText("CONNECTED TO " + model);
                btnConnect.setBackgroundColor(Color.parseColor("#2E7D32")); // Verde succes
            }
        }
    }
    private void actualizeazaInterfataModuriInstant(String codMod) {
        final boolean esteCW = codMod.startsWith("03") || codMod.startsWith("07");
        modLucruCurent = esteCW ? 2 : 1;

        btnLSB.setBackgroundColor(Color.parseColor("#757575"));
        btnUSB.setBackgroundColor(Color.parseColor("#757575"));
        btnCW.setBackgroundColor(Color.parseColor("#757575"));
        btnRTTYL.setBackgroundColor(Color.parseColor("#757575"));

        if (codMod.startsWith("01")) btnLSB.setBackgroundColor(Color.parseColor("#007ACC"));
        else if (codMod.startsWith("02")) btnUSB.setBackgroundColor(Color.parseColor("#007ACC"));
        else if (codMod.startsWith("03") || codMod.startsWith("07")) btnCW.setBackgroundColor(Color.parseColor("#2E7D32"));
        else if (codMod.startsWith("06") || codMod.startsWith("09")) btnRTTYL.setBackgroundColor(Color.parseColor("#E65100"));

        containerButoaneCwExtra.setVisibility(esteCW ? View.VISIBLE : View.GONE);
    }
} // Ultima acoladă din fișier, cea care închide corect toată clasa EcranComenzi!
