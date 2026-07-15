package com.example.yaesucatcontroladv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class EcranLogger {
    private final Context context;
    private final RadioManager radioManager;
    private ScrollView viewPrincipal;

    // Campuri editabile
    private EditText etCall, etDate, etTimeOn, etFreq, etMode;
    private EditText etRstSent, etRstRcvd, etName, etQth, etGrid, etComment, etPower;

    // Butoane
    private Button btnSave, btnClear, btnQrz, btnList;
    private TextView tvLogStatus;

    public EcranLogger(Context context, RadioManager radioManager) {
        this.context = context;
        this.radioManager = radioManager;
        construiesteInterfata();
    }

    public ScrollView getView() {
        // NOU: Verificam buffer-ul static de indicative din RTTY
        if (EcranRttyDroid.callsignBuffer != null && !EcranRttyDroid.callsignBuffer.isEmpty()) {
            String call = EcranRttyDroid.callsignBuffer;
            EcranRttyDroid.callsignBuffer = ""; // Golim buffer-ul

            if (etCall != null) {
                etCall.setText(call);
                etCall.setSelection(etCall.getText().length());
                seteazaTimpUtcCurent();
                solicitaFrecventaSiModDinRadio();
                executaCautareQrz(call);
            }
        }
        return viewPrincipal;
    }

    private void construiesteInterfata() {
        viewPrincipal = new ScrollView(context);
        viewPrincipal.setBackgroundColor(Color.parseColor("#121212"));

        LinearLayout llMain = new LinearLayout(context);
        llMain.setOrientation(LinearLayout.VERTICAL);
        llMain.setPadding(30, 30, 30, 30);

        TextView tvTitlu = new TextView(context);
        tvTitlu.setText("QSO LOGGER");
        tvTitlu.setTextColor(Color.WHITE);
        tvTitlu.setTextSize(18);
        tvTitlu.setTypeface(null, Typeface.BOLD);
        tvTitlu.setPadding(0, 0, 0, 20);
        llMain.addView(tvTitlu);

        // Rand 1: Call, Date, Time
        etCall = creeazaCamp("Callsign", true);
        llMain.addView(etCall);

        LinearLayout rAndDateTime = new LinearLayout(context);
        rAndDateTime.setOrientation(LinearLayout.HORIZONTAL);
        etDate = creeazaCampPondere("Date (YYYYMMDD)", false);
        etTimeOn = creeazaCampPondere("Time UTC (HHMM)", false);
        rAndDateTime.addView(etDate);
        rAndDateTime.addView(creeazaSpatiu());
        rAndDateTime.addView(etTimeOn);
        llMain.addView(rAndDateTime);

        // Rand 2: Freq, Mode
        LinearLayout rAndFreqMode = new LinearLayout(context);
        rAndFreqMode.setOrientation(LinearLayout.HORIZONTAL);
        etFreq = creeazaCampPondere("Frequency (MHz)", false);
        etMode = creeazaCampPondere("Mode", true);
        rAndFreqMode.addView(etFreq);
        rAndFreqMode.addView(creeazaSpatiu());
        rAndFreqMode.addView(etMode);
        llMain.addView(rAndFreqMode);

        // Rand 3: RST Sent, RST Rcvd
        LinearLayout rAndRst = new LinearLayout(context);
        rAndRst.setOrientation(LinearLayout.HORIZONTAL);
        etRstSent = creeazaCampPondere("RST Sent", false);
        etRstRcvd = creeazaCampPondere("RST Rcvd", false);
        etRstSent.setText("599");
        etRstRcvd.setText("599");
        rAndRst.addView(etRstSent);
        rAndRst.addView(creeazaSpatiu());
        rAndRst.addView(etRstRcvd);
        llMain.addView(rAndRst);

        // Nume, QTH, Grid
        etName = creeazaCamp("Name", false);
        etQth = creeazaCamp("QTH", false);
        etGrid = creeazaCamp("Grid Locator", true);
        llMain.addView(etName);
        llMain.addView(etQth);
        llMain.addView(etGrid);

        // Comentarii si Putere
        etComment = creeazaCamp("Comment", false);
        etPower = creeazaCamp("TX Power (W)", false);
        llMain.addView(etComment);
        llMain.addView(etPower);

        // Butoane actiune
        LinearLayout rAndButoane = new LinearLayout(context);
        rAndButoane.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams pBtn = new LinearLayout.LayoutParams(0, 120, 1f);
        pBtn.setMargins(4, 15, 4, 15);

        btnQrz = new Button(context);
        btnQrz.setText("QRZ / HAM");
        btnQrz.setBackgroundColor(Color.parseColor("#5D4037"));
        btnQrz.setTextColor(Color.WHITE);
        btnQrz.setLayoutParams(pBtn);
        btnQrz.setOnClickListener(v -> {
            String call = etCall.getText().toString().trim();
            if (!call.isEmpty()) executaCautareQrz(call);
            else Toast.makeText(context, "Introdu un indicativ!", Toast.LENGTH_SHORT).show();
        });

        btnSave = new Button(context);
        btnSave.setText("SAVE QSO");
        btnSave.setBackgroundColor(Color.parseColor("#2E7D32"));
        btnSave.setTextColor(Color.WHITE);
        btnSave.setLayoutParams(pBtn);
        btnSave.setOnClickListener(v -> salveazaQso());

        btnClear = new Button(context);
        btnClear.setText("CLEAR");
        btnClear.setBackgroundColor(Color.parseColor("#455A64"));
        btnClear.setTextColor(Color.WHITE);
        btnClear.setLayoutParams(pBtn);
        btnClear.setOnClickListener(v -> stergeCampuri());

        rAndButoane.addView(btnQrz);
        rAndButoane.addView(btnSave);
        rAndButoane.addView(btnClear);
        llMain.addView(rAndButoane);

        // Status / Lista
        tvLogStatus = new TextView(context);
        tvLogStatus.setText("--- LAST 5 QSOS ---");
        tvLogStatus.setTextColor(Color.parseColor("#00E5FF"));
        tvLogStatus.setTextSize(12);
        tvLogStatus.setTypeface(Typeface.MONOSPACE);
        tvLogStatus.setPadding(0, 10, 0, 10);
        llMain.addView(tvLogStatus);

        btnList = new Button(context);
        btnList.setText("REFRESH LIST");
        btnList.setBackgroundColor(Color.parseColor("#007ACC"));
        btnList.setTextColor(Color.WHITE);
        btnList.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 100));
        btnList.setOnClickListener(v -> afiseazaListaQso());
        llMain.addView(btnList);

        viewPrincipal.addView(llMain);
        seteazaTimpUtcCurent();
        afiseazaListaQso();
    }

    // ============================================================
    // METODE HELPER UI
    // ============================================================
    private EditText creeazaCamp(String hint, boolean uppercase) {
        EditText et = new EditText(context);
        et.setHint(hint);
        et.setHintTextColor(Color.GRAY);
        et.setTextColor(Color.WHITE);
        et.setTextSize(14);
        if (uppercase) {
            et.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        }
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, 0, 10);
        et.setLayoutParams(p);
        return et;
    }

    private EditText creeazaCampPondere(String hint, boolean uppercase) {
        EditText et = new EditText(context);
        et.setHint(hint);
        et.setHintTextColor(Color.GRAY);
        et.setTextColor(Color.WHITE);
        et.setTextSize(14);
        if (uppercase) {
            et.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        }
        et.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return et;
    }

    private View creeazaSpatiu() {
        View v = new View(context);
        v.setLayoutParams(new LinearLayout.LayoutParams(15, 1));
        return v;
    }

    // ============================================================
    // LOGICA QSO
    // ============================================================
    private void seteazaTimpUtcCurent() {
        SimpleDateFormat sdfDate = new SimpleDateFormat("yyyyMMdd", Locale.US);
        sdfDate.setTimeZone(TimeZone.getTimeZone("UTC"));
        SimpleDateFormat sdfTime = new SimpleDateFormat("HHmm", Locale.US);
        sdfTime.setTimeZone(TimeZone.getTimeZone("UTC"));

        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        if (etDate != null && etDate.getText().toString().isEmpty()) etDate.setText(sdfDate.format(cal.getTime()));
        if (etTimeOn != null && etTimeOn.getText().toString().isEmpty()) etTimeOn.setText(sdfTime.format(cal.getTime()));
    }

    public void solicitaFrecventaSiModDinRadio() {
        try {
            long freqHz = StareRadio.getFrecventaHz();
            if (freqHz > 0 && etFreq != null) {
                double freqMHz = freqHz / 1000000.0;
                etFreq.setText(String.format(Locale.US, "%.4f", freqMHz));
            }
            String mod = StareRadio.getModText();
            if (mod != null && !mod.isEmpty() && etMode != null) {
                if (etMode.getText().toString().isEmpty()) etMode.setText(mod.toUpperCase());
            }
        } catch (Exception e) {
            // Ignoram daca StareRadio nu e populat inca
        }
    }

    private void salveazaQso() {
        String call = etCall.getText().toString().trim().toUpperCase();
        if (call.isEmpty()) {
            Toast.makeText(context, "Callsign-ul este obligatoriu!", Toast.LENGTH_SHORT).show();
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences(EcranSetariProprii.PREFS_NAME, Context.MODE_PRIVATE);

        QsoDbHelper.Qso q = new QsoDbHelper.Qso();
        q.call = call;
        q.qsoDate = etDate.getText().toString().trim();
        q.timeOn = etTimeOn.getText().toString().trim();
        q.freq = etFreq.getText().toString().trim();
        q.band = calculeazaBanda(q.freq);
        q.mode = etMode.getText().toString().trim().toUpperCase();
        q.rstSent = etRstSent.getText().toString().trim();
        q.rstRcvd = etRstRcvd.getText().toString().trim();
        q.name = etName.getText().toString().trim();
        q.qth = etQth.getText().toString().trim();
        q.gridsquare = etGrid.getText().toString().trim().toUpperCase();
        q.comment = etComment.getText().toString().trim();
        q.power = etPower.getText().toString().trim();
        q.myCall = prefs.getString(EcranSetariProprii.PREF_MY_CALL, "");
        q.myGridsquare = prefs.getString(EcranSetariProprii.PREF_MY_GRID, "");

        try {
            QsoDbHelper dbHelper = new QsoDbHelper(context);
            dbHelper.insereazaQso(q);
            Toast.makeText(context, "QSO salvat: " + call, Toast.LENGTH_SHORT).show();
            stergeCampuri();
            afiseazaListaQso();
        } catch (Exception e) {
            Toast.makeText(context, "Eroare salvare: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private String calculeazaBanda(String freqMHz) {
        try {
            double f = Double.parseDouble(freqMHz);
            if (f >= 1.8 && f <= 2.0) return "160M";
            if (f >= 3.5 && f <= 4.0) return "80M";
            if (f >= 7.0 && f <= 7.3) return "40M";
            if (f >= 10.1 && f <= 10.15) return "30M";
            if (f >= 14.0 && f <= 14.35) return "20M";
            if (f >= 18.068 && f <= 18.168) return "17M";
            if (f >= 21.0 && f <= 21.45) return "15M";
            if (f >= 24.89 && f <= 24.99) return "12M";
            if (f >= 28.0 && f <= 29.7) return "10M";
            if (f >= 50.0 && f <= 54.0) return "6M";
            if (f >= 144.0 && f <= 148.0) return "2M";
        } catch (Exception ignored) {}
        return "";
    }

    private void stergeCampuri() {
        etCall.setText("");
        etName.setText("");
        etQth.setText("");
        etGrid.setText("");
        etComment.setText("");
        etRstSent.setText("599");
        etRstRcvd.setText("599");
        seteazaTimpUtcCurent();
        etCall.requestFocus();
    }

    private void afiseazaListaQso() {
        try {
            QsoDbHelper dbHelper = new QsoDbHelper(context);
            List<QsoDbHelper.Qso> lista = dbHelper.getToateQsourile();
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (int i = lista.size() - 1; i >= 0 && count < 5; i--) {
                QsoDbHelper.Qso q = lista.get(i);
                sb.append(String.format("%s | %s | %s | %s\n", q.call, q.qsoDate, q.mode, q.gridsquare));
                count++;
            }
            if (count == 0) sb.append("(Niciun QSO salvat)");
            tvLogStatus.setText("--- LAST 5 QSOS ---\n" + sb.toString());
        } catch (Exception e) {
            tvLogStatus.setText("Eroare citire DB.");
        }
    }

    // ============================================================
    // CAUTARE QRZ + HAMQTH FALLBACK
    // ============================================================
    private void executaCautareQrz(String call) {
        SharedPreferences prefs = context.getSharedPreferences(EcranSetariProprii.PREFS_NAME, Context.MODE_PRIVATE);
        String userQrz = prefs.getString(EcranSetariProprii.PREF_QRZ_USER, "");
        String passQrz = prefs.getString(EcranSetariProprii.PREF_QRZ_PASS, "");

        if (userQrz.isEmpty() || passQrz.isEmpty()) {
            Toast.makeText(context, "Configureaza QRZ.com in MY SETTINGS", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(context, "Caut " + call + " pe QRZ.com...", Toast.LENGTH_SHORT).show();

        QrzLookupHelper.cautaIndicativ(userQrz, passQrz, call, new QrzLookupHelper.QrzCallback() {
            @Override
            public void onSucces(QrzLookupHelper.QrzRezultat r) {
                if (r == null) return;

                String fname = r.fname == null ? "" : r.fname;
                String name = r.name == null ? "" : r.name;
                if (!fname.isEmpty() || !name.isEmpty()) {
                    etName.setText((fname + " " + name).trim());
                }
                if (r.addr2 != null && !r.addr2.isEmpty()) {
                    String qthText = r.addr2;
                    if (r.state != null && !r.state.isEmpty()) qthText += ", " + r.state;
                    etQth.setText(qthText);
                }

                if (r.grid != null && !r.grid.isEmpty()) {
                    etGrid.setText(r.grid.toUpperCase());
                    Toast.makeText(context, "QRZ: gasit " + call + " (cu grid)", Toast.LENGTH_SHORT).show();
                    return;
                }

                Toast.makeText(context, "QRZ: fara grid. Incerc HamQTH...", Toast.LENGTH_SHORT).show();
                cautaPeHamQthPentruGrid(call);
            }

            @Override
            public void onEroare(String mesaj) {
                Toast.makeText(context, "QRZ eroare: " + mesaj + "\nIncerc HamQTH...", Toast.LENGTH_SHORT).show();
                cautaPeHamQthPentruGrid(call);
            }
        });
    }

    private void cautaPeHamQthPentruGrid(String call) {
        SharedPreferences prefs = context.getSharedPreferences(EcranSetariProprii.PREFS_NAME, Context.MODE_PRIVATE);
        String userHam = prefs.getString(EcranSetariProprii.PREF_HAMQTH_USER, "");
        String passHam = prefs.getString(EcranSetariProprii.PREF_HAMQTH_PASS, "");

        if (userHam.isEmpty() || passHam.isEmpty()) {
            Toast.makeText(context, "Configureaza HamQTH.com in MY SETTINGS pentru grid!", Toast.LENGTH_LONG).show();
            return;
        }

        HamQthLookupHelper.cautaIndicativ(userHam, passHam, call, new HamQthLookupHelper.HamQthCallback() {
            @Override
            public void onSucces(HamQthLookupHelper.HamQthRezultat r) {
                if (r == null) return;

                if (r.grid != null && !r.grid.isEmpty() && etGrid.getText().toString().isEmpty()) {
                    etGrid.setText(r.grid.toUpperCase());
                }
                if (etName.getText().toString().isEmpty()) {
                    if (r.nick != null && !r.nick.isEmpty()) {
                        etName.setText((r.nick + " " + (r.adr_name != null ? r.adr_name : "")).trim());
                    }
                }
                if (etQth.getText().toString().isEmpty() && r.qth != null && !r.qth.isEmpty()) {
                    etQth.setText(r.qth);
                }
                Toast.makeText(context, "HamQTH: Grid = " + (r.grid != null ? r.grid : "-"), Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onEroare(String mesaj) {
                Toast.makeText(context, "HamQTH eroare: " + mesaj, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // Metoda pastrata pentru compatibilitate, desi acum folosim buffer-ul static
    public void preiaIndicativDinRtty(String callsign) {
        if (callsign == null || callsign.isEmpty()) return;
        etCall.setText(callsign.toUpperCase());
        etCall.setSelection(etCall.getText().length());
        seteazaTimpUtcCurent();
        solicitaFrecventaSiModDinRadio();
        executaCautareQrz(callsign);
        Toast.makeText(context, "Preluat: " + callsign, Toast.LENGTH_SHORT).show();
    }
}