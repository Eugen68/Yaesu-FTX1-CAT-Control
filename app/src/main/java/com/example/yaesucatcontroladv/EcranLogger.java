package com.example.yaesucatcontroladv;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class EcranLogger {
    private final Context context;
    private final RadioManager radioManager;
    private final QsoDbHelper dbHelper;
    private LinearLayout viewPrincipal;

    private EditText etCall, etRstSent, etRstRcvd, etFreq, etBand, etMode;
    private EditText etTimeOn;
    private EditText etName, etQth, etGrid, etPower, etComment, etMyGrid;
    private ListView lvIstoricQso;
    private Button btnAdaugaQso;
    private Button btnAnuleazaEditare;
    private TextView tvStareEditare;

    private Long idQsoInEditare = null;
    private String qsoDateInEditare = null;

    private static final int REQUEST_CODE_LOCATION = 501;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public EcranLogger(Context context, RadioManager radioManager) {
        this.context = context;
        this.radioManager = radioManager;
        this.dbHelper = new QsoDbHelper(context);
        construiesteInterfata();
    }

    public LinearLayout getView() {
        // LOGICA BUFFER STATIC: Preia indicativul din RTTY
        if (EcranRttyDroid.callsignBuffer != null && !EcranRttyDroid.callsignBuffer.isEmpty()) {
            String call = EcranRttyDroid.callsignBuffer;
            EcranRttyDroid.callsignBuffer = ""; // Golim buffer-ul

            etCall.setText(call.toUpperCase());
            etCall.setSelection(etCall.getText().length());

            SimpleDateFormat sdfOraAcum = new SimpleDateFormat("HH:mm", Locale.US);
            sdfOraAcum.setTimeZone(TimeZone.getTimeZone("UTC"));
            etTimeOn.setText(sdfOraAcum.format(Calendar.getInstance(TimeZone.getTimeZone("UTC")).getTime()));

            solicitaFrecventaSiModDinRadio();
            executaCautareQrz(call);
        } else {
            // Comportamentul normal cand nu avem nimic in buffer
            refreshListaQso();
            solicitaFrecventaSiModDinRadio();
            if (idQsoInEditare == null) {
                SimpleDateFormat sdfOraAcum = new SimpleDateFormat("HH:mm", Locale.US);
                sdfOraAcum.setTimeZone(TimeZone.getTimeZone("UTC"));
                etTimeOn.setText(sdfOraAcum.format(Calendar.getInstance(TimeZone.getTimeZone("UTC")).getTime()));
            }
            if (etMyGrid.getText().toString().trim().isEmpty()) {
                String gridDeBaza = context.getSharedPreferences(EcranSetariProprii.PREFS_NAME, Context.MODE_PRIVATE)
                        .getString(EcranSetariProprii.PREF_MY_GRID, "");
                if (!gridDeBaza.isEmpty()) etMyGrid.setText(gridDeBaza);
            }
        }
        return viewPrincipal;
    }

    private void construiesteInterfata() {
        viewPrincipal = new LinearLayout(context);
        viewPrincipal.setOrientation(LinearLayout.VERTICAL);
        viewPrincipal.setBackgroundColor(Color.parseColor("#121212"));
        viewPrincipal.setPadding(30, 30, 30, 0);

        TextView tvTitlu = new TextView(context);
        tvTitlu.setText("QSO LOGGER");
        tvTitlu.setTextColor(Color.WHITE);
        tvTitlu.setTextSize(18);
        tvTitlu.setTypeface(null, Typeface.BOLD);
        tvTitlu.setPadding(0, 0, 0, 20);
        viewPrincipal.addView(tvTitlu);

        etCall = creeazaEditText("Indicativ (Call)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        viewPrincipal.addView(etCall);

        Button btnCautaQrz = new Button(context);
        btnCautaQrz.setText("CAUTA PE QRZ.COM");
        btnCautaQrz.setBackgroundColor(Color.parseColor("#5D4037"));
        btnCautaQrz.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams pBtnQrz = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pBtnQrz.setMargins(0, 0, 0, 15);
        btnCautaQrz.setLayoutParams(pBtnQrz);
        btnCautaQrz.setOnClickListener(v -> initiazaCautareQrz());
        viewPrincipal.addView(btnCautaQrz);

        LinearLayout rAndRst = new LinearLayout(context);
        rAndRst.setOrientation(LinearLayout.HORIZONTAL);
        etRstSent = creeazaEditTextPondere("RST Tx", InputType.TYPE_CLASS_TEXT);
        etRstSent.setText("599");
        etRstRcvd = creeazaEditTextPondere("RST Rx", InputType.TYPE_CLASS_TEXT);
        etRstRcvd.setText("599");

        etTimeOn = creeazaEditTextPondere("Ora UTC (HH:MM)", InputType.TYPE_CLASS_TEXT);
        etTimeOn.setKeyListener(android.text.method.DigitsKeyListener.getInstance("0123456789:"));
        etTimeOn.addTextChangedListener(new TextWatcher() {
            private boolean modificandu = false;
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (modificandu) return;
                String doarCifre = s.toString().replaceAll("[^0-9]", "");
                if (doarCifre.length() > 4) doarCifre = doarCifre.substring(0, 4);
                StringBuilder formatat = new StringBuilder();
                for (int i = 0; i < doarCifre.length(); i++) {
                    if (i == 2) formatat.append(":");
                    formatat.append(doarCifre.charAt(i));
                }
                String textNou = formatat.toString();
                if (!s.toString().equals(textNou)) {
                    modificandu = true;
                    etTimeOn.removeTextChangedListener(this);
                    etTimeOn.setText(textNou);
                    etTimeOn.post(() -> {
                        int lungimeReala = etTimeOn.getText().length();
                        int pozitieDorita = textNou.length();
                        etTimeOn.setSelection(Math.min(pozitieDorita, lungimeReala));
                        etTimeOn.addTextChangedListener(this);
                        modificandu = false;
                    });
                }
            }
        });

        rAndRst.addView(etRstSent);
        rAndRst.addView(spatiuOrizontal());
        rAndRst.addView(etRstRcvd);
        rAndRst.addView(spatiuOrizontal());
        rAndRst.addView(etTimeOn);
        viewPrincipal.addView(rAndRst);

        LinearLayout rAndFrecventa = new LinearLayout(context);
        rAndFrecventa.setOrientation(LinearLayout.HORIZONTAL);
        etFreq = creeazaEditTextPondere("Frecv. (MHz)", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etBand = creeazaEditTextPondere("Banda", InputType.TYPE_CLASS_TEXT);
        etMode = creeazaEditTextPondere("Mod", InputType.TYPE_CLASS_TEXT);
        rAndFrecventa.addView(etFreq);
        rAndFrecventa.addView(spatiuOrizontal());
        rAndFrecventa.addView(etBand);
        rAndFrecventa.addView(spatiuOrizontal());
        rAndFrecventa.addView(etMode);
        viewPrincipal.addView(rAndFrecventa);

        Button btnPreiaDinRadio = new Button(context);
        btnPreiaDinRadio.setText("PREIA FRECVENTA/MOD DIN RADIO");
        btnPreiaDinRadio.setBackgroundColor(Color.parseColor("#007ACC"));
        btnPreiaDinRadio.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams pBtnPreia = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pBtnPreia.setMargins(0, 0, 0, 15);
        btnPreiaDinRadio.setLayoutParams(pBtnPreia);
        btnPreiaDinRadio.setOnClickListener(v -> solicitaFrecventaSiModDinRadio());
        viewPrincipal.addView(btnPreiaDinRadio);

        LinearLayout rAndNume = new LinearLayout(context);
        rAndNume.setOrientation(LinearLayout.HORIZONTAL);
        etName = creeazaEditTextPondere("Nume", InputType.TYPE_CLASS_TEXT);
        etQth = creeazaEditTextPondere("QTH", InputType.TYPE_CLASS_TEXT);
        rAndNume.addView(etName);
        rAndNume.addView(spatiuOrizontal());
        rAndNume.addView(etQth);
        viewPrincipal.addView(rAndNume);

        LinearLayout rAndGrid = new LinearLayout(context);
        rAndGrid.setOrientation(LinearLayout.HORIZONTAL);
        etGrid = creeazaEditTextPondere("Locator (Grid)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        etPower = creeazaEditTextPondere("Putere (W)", InputType.TYPE_CLASS_NUMBER);
        rAndGrid.addView(etGrid);
        rAndGrid.addView(spatiuOrizontal());
        rAndGrid.addView(etPower);
        viewPrincipal.addView(rAndGrid);

        LinearLayout rAndMyGrid = new LinearLayout(context);
        rAndMyGrid.setOrientation(LinearLayout.HORIZONTAL);
        rAndMyGrid.setGravity(Gravity.CENTER_VERTICAL);
        etMyGrid = creeazaEditTextPondere("Locatorul meu (My Grid)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        Button btnGpsGrid = new Button(context);
        btnGpsGrid.setText("GPS");
        btnGpsGrid.setBackgroundColor(Color.parseColor("#00695C"));
        btnGpsGrid.setTextColor(Color.WHITE);
        btnGpsGrid.setOnClickListener(v -> preiaGridDinGps());
        rAndMyGrid.addView(etMyGrid);
        rAndMyGrid.addView(spatiuOrizontal());
        rAndMyGrid.addView(btnGpsGrid);
        viewPrincipal.addView(rAndMyGrid);

        etComment = creeazaEditText("Comentariu", InputType.TYPE_CLASS_TEXT);
        viewPrincipal.addView(etComment);

        tvStareEditare = new TextView(context);
        tvStareEditare.setText("");
        tvStareEditare.setTextColor(Color.parseColor("#FFA000"));
        tvStareEditare.setTextSize(13);
        tvStareEditare.setTypeface(null, Typeface.BOLD);
        tvStareEditare.setPadding(0, 0, 0, 8);
        tvStareEditare.setVisibility(View.GONE);
        viewPrincipal.addView(tvStareEditare);

        btnAdaugaQso = new Button(context);
        btnAdaugaQso.setText("ADAUGA QSO IN JURNAL");
        btnAdaugaQso.setBackgroundColor(Color.parseColor("#2E7D32"));
        btnAdaugaQso.setTextColor(Color.WHITE);
        btnAdaugaQso.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams pBtnAdauga = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pBtnAdauga.setMargins(0, 5, 0, 5);
        btnAdaugaQso.setLayoutParams(pBtnAdauga);
        btnAdaugaQso.setOnClickListener(v -> salveazaQso());
        viewPrincipal.addView(btnAdaugaQso);

        btnAnuleazaEditare = new Button(context);
        btnAnuleazaEditare.setText("ANULEAZA EDITAREA / QSO NOU");
        btnAnuleazaEditare.setBackgroundColor(Color.parseColor("#757575"));
        btnAnuleazaEditare.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams pBtnAnuleaza = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pBtnAnuleaza.setMargins(0, 0, 0, 15);
        btnAnuleazaEditare.setLayoutParams(pBtnAnuleaza);
        btnAnuleazaEditare.setVisibility(View.GONE);
        btnAnuleazaEditare.setOnClickListener(v -> iesiDinModEditare());
        viewPrincipal.addView(btnAnuleazaEditare);

        TextView tvIstoricLabel = new TextView(context);
        tvIstoricLabel.setText("Istoric QSO (tine apasat pentru a sterge):");
        tvIstoricLabel.setTextColor(Color.GRAY);
        tvIstoricLabel.setTextSize(12);
        tvIstoricLabel.setPadding(0, 0, 0, 5);
        viewPrincipal.addView(tvIstoricLabel);

        lvIstoricQso = new ListView(context);
        LinearLayout.LayoutParams pLista = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        lvIstoricQso.setLayoutParams(pLista);
        viewPrincipal.addView(lvIstoricQso);
    }

    private EditText creeazaEditText(String hint, int inputType) {
        EditText et = new EditText(context);
        et.setHint(hint);
        et.setHintTextColor(Color.GRAY);
        et.setTextColor(Color.WHITE);
        et.setInputType(inputType);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, 0, 12);
        et.setLayoutParams(p);
        return et;
    }

    private EditText creeazaEditTextPondere(String hint, int inputType) {
        EditText et = new EditText(context);
        et.setHint(hint);
        et.setHintTextColor(Color.GRAY);
        et.setTextColor(Color.WHITE);
        et.setInputType(inputType);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        et.setLayoutParams(p);
        return et;
    }

    private View spatiuOrizontal() {
        View v = new View(context);
        v.setLayoutParams(new LinearLayout.LayoutParams(15, 1));
        return v;
    }

    private void solicitaFrecventaSiModDinRadio() {
        if (radioManager == null) return;
        try {
            radioManager.trimiteComandaCAT("FA;");
            radioManager.trimiteComandaCAT("MD0;");
        } catch (Exception e) { return; }
        handler.postDelayed(() -> {
            long hz = StareRadio.getFrecventaHz();
            if (hz > 0) {
                double mhz = hz / 1_000_000.0;
                etFreq.setText(String.format(Locale.US, "%.6f", mhz));
                String banda = StareRadio.deriveazaBanda(hz);
                if (!banda.isEmpty()) etBand.setText(banda);
            }
            String modText = StareRadio.getModText();
            if (modText != null && !modText.isEmpty()) etMode.setText(traduModCatInText(modText));
        }, 400);
    }

    private String traduModCatInText(String codMod) {
        if (codMod.startsWith("01")) return "LSB";
        if (codMod.startsWith("02")) return "USB";
        if (codMod.startsWith("03") || codMod.startsWith("07")) return "CW";
        if (codMod.startsWith("06") || codMod.startsWith("09")) return "RTTY";
        return codMod;
    }

    private void salveazaQso() {
        String call = etCall.getText().toString().trim().toUpperCase(Locale.US);
        if (call.isEmpty()) {
            Toast.makeText(context, "Introdu indicativul statiei lucrate.", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean esteEditare = (idQsoInEditare != null);
        QsoDbHelper.Qso qso = new QsoDbHelper.Qso();
        qso.call = call;
        if (esteEditare) {
            qso.qsoDate = qsoDateInEditare;
        } else {
            SimpleDateFormat sdfData = new SimpleDateFormat("yyyyMMdd", Locale.US);
            sdfData.setTimeZone(TimeZone.getTimeZone("UTC"));
            qso.qsoDate = sdfData.format(Calendar.getInstance(TimeZone.getTimeZone("UTC")).getTime());
        }
        qso.timeOn = etTimeOn.getText().toString().replace(":", "").trim();
        qso.rstSent = etRstSent.getText().toString().trim();
        qso.rstRcvd = etRstRcvd.getText().toString().trim();
        qso.freq = etFreq.getText().toString().trim();
        qso.band = etBand.getText().toString().trim();
        qso.mode = etMode.getText().toString().trim();
        qso.name = etName.getText().toString().trim();
        qso.qth = etQth.getText().toString().trim();
        qso.gridsquare = etGrid.getText().toString().trim().toUpperCase(Locale.US);
        qso.comment = etComment.getText().toString().trim();
        qso.power = etPower.getText().toString().trim();
        qso.myGridsquare = etMyGrid.getText().toString().trim().toUpperCase(Locale.US);
        qso.myCall = context.getSharedPreferences(EcranSetariProprii.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(EcranSetariProprii.PREF_MY_CALL, "");

        if (esteEditare) {
            qso.id = idQsoInEditare;
            dbHelper.actualizeazaQso(qso);
            Toast.makeText(context, "QSO actualizat: " + call, Toast.LENGTH_SHORT).show();
        } else {
            dbHelper.insereazaQso(qso);
            Toast.makeText(context, "QSO salvat: " + call, Toast.LENGTH_SHORT).show();
        }
        iesiDinModEditare();
        refreshListaQso();
    }

    private void intraInModEditare(QsoDbHelper.Qso q) {
        idQsoInEditare = q.id;
        qsoDateInEditare = q.qsoDate;
        etCall.setText(q.call);
        etRstSent.setText(q.rstSent);
        etRstRcvd.setText(q.rstRcvd);
        String oraFormatataEditare = (q.timeOn != null && q.timeOn.length() == 4)
                ? q.timeOn.substring(0, 2) + ":" + q.timeOn.substring(2, 4)
                : (q.timeOn == null ? "" : q.timeOn);
        etTimeOn.setText(oraFormatataEditare);
        etFreq.setText(q.freq);
        etBand.setText(q.band);
        etMode.setText(q.mode);
        etName.setText(q.name);
        etQth.setText(q.qth);
        etGrid.setText(q.gridsquare);
        etComment.setText(q.comment);
        etPower.setText(q.power);
        etMyGrid.setText(q.myGridsquare);
        btnAdaugaQso.setText("SALVEAZA MODIFICARILE");
        btnAnuleazaEditare.setVisibility(View.VISIBLE);
        tvStareEditare.setText("Editezi QSO: " + q.call + " (" + q.qsoDate + ")");
        tvStareEditare.setVisibility(View.VISIBLE);
    }

    private void iesiDinModEditare() {
        idQsoInEditare = null;
        qsoDateInEditare = null;
        etCall.setText("");
        etName.setText("");
        etQth.setText("");
        etGrid.setText("");
        etComment.setText("");
        etRstSent.setText("599");
        etRstRcvd.setText("599");
        btnAdaugaQso.setText("ADAUGA QSO IN JURNAL");
        btnAnuleazaEditare.setVisibility(View.GONE);
        tvStareEditare.setVisibility(View.GONE);
    }

    private void initiazaCautareQrz() {
        String call = etCall.getText().toString().trim();
        if (call.isEmpty()) {
            Toast.makeText(context, "Introdu mai intai un indicativ.", Toast.LENGTH_SHORT).show();
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(EcranSetariProprii.PREFS_NAME, Context.MODE_PRIVATE);
        String user = prefs.getString(EcranSetariProprii.PREF_QRZ_USER, "");
        String pass = prefs.getString(EcranSetariProprii.PREF_QRZ_PASS, "");
        if (user.isEmpty() || pass.isEmpty()) {
            Toast.makeText(context, "Configureaza userul/parola QRZ.com in tab-ul MY SETTINGS.", Toast.LENGTH_LONG).show();
            return;
        }
        executaCautareQrz(call);
    }

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
                    etGrid.setText(r.grid);
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

    private void preiaGridDinGps() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions((Activity) context,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_CODE_LOCATION);
            Toast.makeText(context, "Permite accesul la locatie, apoi apasa din nou GPS.", Toast.LENGTH_LONG).show();
            return;
        }
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            Toast.makeText(context, "Serviciul de localizare nu este disponibil.", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(context, "Se cauta pozitia GPS...", Toast.LENGTH_SHORT).show();
        LocationListener listener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                String grid = GridLocatorHelper.calculeazaGrid(location.getLatitude(), location.getLongitude());
                etMyGrid.setText(grid);
                Toast.makeText(context, "Grid GPS: " + grid, Toast.LENGTH_SHORT).show();
                lm.removeUpdates(this);
            }
            @Override public void onStatusChanged(String p, int s, Bundle e) {}
            @Override public void onProviderEnabled(String p) {}
            @Override public void onProviderDisabled(String p) {}
        };
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper());
            } else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, Looper.getMainLooper());
            } else {
                Toast.makeText(context, "Activeaza locatia (GPS) din setarile telefonului.", Toast.LENGTH_LONG).show();
            }
            Location ultimaCunoscuta = null;
            try {
                ultimaCunoscuta = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (ultimaCunoscuta == null) ultimaCunoscuta = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            } catch (SecurityException ignored) {}
            if (ultimaCunoscuta != null) {
                String grid = GridLocatorHelper.calculeazaGrid(ultimaCunoscuta.getLatitude(), ultimaCunoscuta.getLongitude());
                etMyGrid.setText(grid);
            }
        } catch (SecurityException e) {
            Toast.makeText(context, "Permisiune de locatie lipsa.", Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshListaQso() {
        List<QsoDbHelper.Qso> qsouri = dbHelper.getToateQsourile();
        List<String> linii = new ArrayList<>();
        final List<Long> idUri = new ArrayList<>();
        for (QsoDbHelper.Qso q : qsouri) {
            idUri.add(q.id);
            String qsoDate = q.qsoDate == null ? "" : q.qsoDate;
            String timeOn = q.timeOn == null ? "" : q.timeOn;
            String callStr = q.call == null ? "" : q.call;
            String freqStr = q.freq == null ? "" : q.freq;
            String modeStr = q.mode == null ? "" : q.mode;
            String rstSentStr = q.rstSent == null ? "" : q.rstSent;
            String rstRcvdStr = q.rstRcvd == null ? "" : q.rstRcvd;
            String dataFormatata = qsoDate.length() == 8
                    ? qsoDate.substring(0, 4) + "-" + qsoDate.substring(4, 6) + "-" + qsoDate.substring(6, 8)
                    : qsoDate;
            String oraFormatata = timeOn.length() == 4
                    ? timeOn.substring(0, 2) + ":" + timeOn.substring(2, 4)
                    : timeOn;
            String linie = String.format(Locale.US, "%s %sZ  |  %-10s  |  %6s %-5s  |  RST %s/%s",
                    dataFormatata, oraFormatata, callStr, freqStr, modeStr, rstSentStr, rstRcvdStr);
            linii.add(linie);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, linii);
        lvIstoricQso.setAdapter(adapter);
        lvIstoricQso.setOnItemClickListener((parent, view, position, id) ->
                intraInModEditare(qsouri.get(position)));
        lvIstoricQso.setOnItemLongClickListener((parent, view, position, id) -> {
            long idQsoDeSters = idUri.get(position);
            String linieAfisata = linii.get(position);
            new AlertDialog.Builder(context)
                    .setTitle("Sterge QSO")
                    .setMessage("Stergi acest QSO din jurnal?\n\n" + linieAfisata)
                    .setPositiveButton("Sterge", (dialog, which) -> {
                        dbHelper.stergeQso(idQsoDeSters);
                        if (idQsoInEditare != null && idQsoInEditare == idQsoDeSters) iesiDinModEditare();
                        refreshListaQso();
                    })
                    .setNegativeButton("Anuleaza", null)
                    .show();
            return true;
        });
    }

    public void preiaIndicativDinRtty(String callsign) {
        if (callsign == null || callsign.isEmpty()) return;
        etCall.setText(callsign.toUpperCase());
        etCall.setSelection(etCall.getText().length());
        if (etTimeOn.getText().toString().isEmpty()) {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US);
            sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            etTimeOn.setText(sdf.format(java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).getTime()));
        }
        solicitaFrecventaSiModDinRadio();
        executaCautareQrz(callsign);
        Toast.makeText(context, callsign + " preluat in Logger", Toast.LENGTH_SHORT).show();
    }
}