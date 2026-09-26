package com.example.yaesucatcontroladv;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
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
    private Button btnAdaugaQso, btnPreiaDinRadio;
    private Button btnAnuleazaEditare;
    private TextView tvStareEditare, tvTitluCeas;

    private Long idQsoInEditare = null;
    private String qsoDateInEditare = null;

    // Frecvența și modul "de sesiune": ultimele valori introduse (manual sau din radio)
    // pe un QSO nou. Rămân în formular de la un QSO la altul până la EXIT (instanța
    // EcranLogger trăiește cât aplicația); editarea unui QSO vechi nu le modifică.
    private String sesiuneFreq = "";
    private String sesiuneMod = "";

    // Ora QSO-ului = momentul în care se introduce indicativul (câmpul trece din gol în
    // ne-gol). Dacă utilizatorul scrie ora de mână (ex. logare întârziată), o respectăm
    // și nu o mai suprascriem automat până la salvarea / anularea acelui QSO.
    private boolean oraSetataManual = false;
    private boolean setamOraProgramatic = false;
    private boolean callEraGol = true;

    private long lastQrzClickTime = 0;
    private static final long DOUBLE_CLICK_TIME_DELTA = 500; // milisecunde

    private static final int REQUEST_CODE_LOCATION = 501;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable runnableCeas = new Runnable() {
        @Override
        public void run() {
            actualizeazaCeasHeader();
            handler.postDelayed(this, 10000); // Actualizează la fiecare 10 secunde
        }
    };

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
            // Indicativ nou (poate înlocui unul existent) -> ora de acum
            if (!oraSetataManual) puneOraUtcAcum();

            solicitaFrecventaSiModDinRadio(true);
            executaCautareQrz(call);
        } else {
            // Comportamentul normal cand nu avem nimic in buffer. Radioul completeaza
            // doar campurile goale - nu suprascrie frecventa/modul de sesiune.
            refreshListaQso();
            solicitaFrecventaSiModDinRadio(false);
            // Doar ca valoare afișată pentru formularul gol; ora reală a QSO-ului se pune
            // când se introduce indicativul. Nu atingem un QSO început deja (indicativ scris).
            if (idQsoInEditare == null && !oraSetataManual
                    && etCall.getText().toString().trim().isEmpty()) {
                puneOraUtcAcum();
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

        tvTitluCeas = new TextView(context);
        tvTitluCeas.setTextColor(Color.WHITE);
        tvTitluCeas.setTextSize(16);
        tvTitluCeas.setTypeface(null, Typeface.BOLD);
        tvTitluCeas.setPadding(0, 0, 0, 20);
        tvTitluCeas.setClickable(true);
        tvTitluCeas.setOnClickListener(v -> {
            puneOraUtcAcum();
            Toast.makeText(context, "UTC Time Updated", Toast.LENGTH_SHORT).show();
        });
        viewPrincipal.addView(tvTitluCeas);
        handler.post(runnableCeas);

        // RAND 1: Callsign (75%) + QRZ.COM (25%)
        LinearLayout rAndCall = new LinearLayout(context);
        rAndCall.setOrientation(LinearLayout.HORIZONTAL);
        rAndCall.setGravity(Gravity.CENTER_VERTICAL);

        etCall = new EditText(context);
        etCall.setHint("Callsign");
        etCall.setHintTextColor(Color.GRAY);
        etCall.setTextColor(Color.WHITE);
        etCall.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        etCall.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.AllCaps()});
        LinearLayout.LayoutParams pEtCall = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.75f);
        etCall.setLayoutParams(pEtCall);

        etCall.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                callEraGol = s.toString().trim().isEmpty();
            }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                // Prima literă a unui indicativ nou = începutul QSO-ului -> ora UTC de acum
                if (callEraGol && s.toString().trim().length() > 0
                        && idQsoInEditare == null && !oraSetataManual) {
                    puneOraUtcAcum();
                }
                if (s.length() > 0) {
                    etCall.setCompoundDrawablesWithIntrinsicBounds(null, null, createXIcon(), null);
                } else {
                    etCall.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
                }
            }
        });

        etCall.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                if (etCall.getCompoundDrawables()[2] != null) {
                    if (event.getRawX() >= (etCall.getRight() - etCall.getCompoundPaddingRight())) {
                        curataCampuriLoggerSelectiv();
                        return true;
                    }
                }
                v.performClick();
            }
            return false;
        });

        rAndCall.addView(etCall);

        Button btnCautaQrz = new Button(context);
        btnCautaQrz.setText("QRZ.COM");
        btnCautaQrz.setBackgroundColor(Color.parseColor("#5D4037"));
        btnCautaQrz.setTextColor(Color.WHITE);
        btnCautaQrz.setTextSize(10);
        LinearLayout.LayoutParams pBtnQrz = new LinearLayout.LayoutParams(0, 110, 0.25f);
        pBtnQrz.setMargins(10, 0, 0, 0);
        btnCautaQrz.setLayoutParams(pBtnQrz);
        btnCautaQrz.setOnClickListener(v -> {
            long clickTime = System.currentTimeMillis();
            if (clickTime - lastQrzClickTime < DOUBLE_CLICK_TIME_DELTA) {
                // DOUBLE CLICK -> Deschide Browser
                String call = etCall.getText().toString().trim();
                if (!call.isEmpty()) {
                    String url = "https://www.qrz.com/db/" + call;
                    android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                    i.setData(android.net.Uri.parse(url));
                    context.startActivity(i);
                }
            } else {
                // SINGLE CLICK -> Căutare XML existentă
                initiazaCautareQrz();
            }
            lastQrzClickTime = clickTime;
        });
        rAndCall.addView(btnCautaQrz);
        viewPrincipal.addView(rAndCall);

        LinearLayout rAndRst = new LinearLayout(context);
        rAndRst.setOrientation(LinearLayout.HORIZONTAL);
        etRstSent = creeazaEditTextPondere("RST Tx", InputType.TYPE_CLASS_TEXT);
        etRstSent.setText("59");
        etRstRcvd = creeazaEditTextPondere("RST Rx", InputType.TYPE_CLASS_TEXT);
        etRstRcvd.setText("59");

        etTimeOn = creeazaEditTextPondere("UTC Time", InputType.TYPE_CLASS_TEXT);
        etTimeOn.setFocusable(true);
        etTimeOn.setClickable(true);
        etTimeOn.setCursorVisible(true);
        etTimeOn.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                // Modificare făcută de utilizator (nu de cod) pe un QSO nou -> oră manuală
                if (!setamOraProgramatic && idQsoInEditare == null) oraSetataManual = true;
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
        etFreq = creeazaEditTextPondere("Freq. (MHz)", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etBand = creeazaEditTextPondere("Band", InputType.TYPE_CLASS_TEXT);
        etMode = creeazaEditTextPondere("Mode", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        etMode.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.AllCaps()});
        etMode.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (idQsoInEditare == null) sesiuneMod = s.toString().trim();
            }
        });

        // AUTO-BAND logic
        etFreq.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                // Pe un QSO nou, orice frecvență (tastată sau din radio) devine frecvența de sesiune
                if (idQsoInEditare == null) sesiuneFreq = s.toString().trim();
                try {
                    String val = s.toString();
                    if (!val.isEmpty()) {
                        double mhz = Double.parseDouble(val);
                        long hz = (long) (mhz * 1_000_000);
                        String banda = StareRadio.deriveazaBanda(hz);
                        if (!banda.isEmpty()) etBand.setText(banda);
                    } else {
                        // Revine la hint (golește textul)
                        etBand.setText("");
                    }
                } catch (Exception ignored) {}
            }
        });

        rAndFrecventa.addView(etFreq);
        rAndFrecventa.addView(spatiuOrizontal());
        rAndFrecventa.addView(etBand);
        rAndFrecventa.addView(spatiuOrizontal());
        rAndFrecventa.addView(etMode);
        viewPrincipal.addView(rAndFrecventa);

        // RAND BUTOANE: GET FREQ / CANCEL (50%) + ADD QSO / SAVE (50%)
        LinearLayout rAndActionButtons = new LinearLayout(context);
        rAndActionButtons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams pBtnHalf = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);

        btnPreiaDinRadio = new Button(context);
        btnPreiaDinRadio.setText("GET FREQ/MODE");
        btnPreiaDinRadio.setBackgroundColor(Color.parseColor("#007ACC"));
        btnPreiaDinRadio.setTextColor(Color.WHITE);
        btnPreiaDinRadio.setLayoutParams(pBtnHalf);
        btnPreiaDinRadio.setOnClickListener(v -> solicitaFrecventaSiModDinRadio(true));
        rAndActionButtons.addView(btnPreiaDinRadio);

        btnAnuleazaEditare = new Button(context);
        btnAnuleazaEditare.setText("CANCEL EDIT QSO");
        btnAnuleazaEditare.setBackgroundColor(Color.parseColor("#757575"));
        btnAnuleazaEditare.setTextColor(Color.WHITE);
        btnAnuleazaEditare.setLayoutParams(pBtnHalf);
        btnAnuleazaEditare.setVisibility(View.GONE);
        btnAnuleazaEditare.setOnClickListener(v -> iesiDinModEditare());
        rAndActionButtons.addView(btnAnuleazaEditare);

        rAndActionButtons.addView(spatiuOrizontal());

        btnAdaugaQso = new Button(context);
        btnAdaugaQso.setText("ADD QSO TO LOG");
        btnAdaugaQso.setBackgroundColor(Color.parseColor("#2E7D32"));
        btnAdaugaQso.setTextColor(Color.WHITE);
        btnAdaugaQso.setTypeface(null, Typeface.BOLD);
        btnAdaugaQso.setLayoutParams(pBtnHalf);
        btnAdaugaQso.setOnClickListener(v -> salveazaQso());
        rAndActionButtons.addView(btnAdaugaQso);

        LinearLayout.LayoutParams pActionRow = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pActionRow.setMargins(0, 0, 0, 15);
        rAndActionButtons.setLayoutParams(pActionRow);
        viewPrincipal.addView(rAndActionButtons);


        LinearLayout rAndNume = new LinearLayout(context);
        rAndNume.setOrientation(LinearLayout.HORIZONTAL);
        etName = creeazaEditTextPondere("Name", InputType.TYPE_CLASS_TEXT);
        etQth = creeazaEditTextPondere("QTH", InputType.TYPE_CLASS_TEXT);
        rAndNume.addView(etName);
        rAndNume.addView(spatiuOrizontal());
        rAndNume.addView(etQth);
        viewPrincipal.addView(rAndNume);

        LinearLayout rAndGrid = new LinearLayout(context);
        rAndGrid.setOrientation(LinearLayout.HORIZONTAL);
        etGrid = creeazaEditTextPondere("Locator (Grid)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        etPower = creeazaEditTextPondere("Power (W)", InputType.TYPE_CLASS_NUMBER);
        rAndGrid.addView(etGrid);
        rAndGrid.addView(spatiuOrizontal());
        rAndGrid.addView(etPower);
        viewPrincipal.addView(rAndGrid);

        LinearLayout rAndMyGrid = new LinearLayout(context);
        rAndMyGrid.setOrientation(LinearLayout.HORIZONTAL);
        rAndMyGrid.setGravity(Gravity.CENTER_VERTICAL);
        etMyGrid = creeazaEditTextPondere("My Locator (My Grid)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        Button btnGpsGrid = new Button(context);
        btnGpsGrid.setText("GPS");
        btnGpsGrid.setBackgroundColor(Color.parseColor("#00695C"));
        btnGpsGrid.setTextColor(Color.WHITE);
        btnGpsGrid.setOnClickListener(v -> preiaGridDinGps());
        rAndMyGrid.addView(etMyGrid);
        rAndMyGrid.addView(spatiuOrizontal());
        rAndMyGrid.addView(btnGpsGrid);
        viewPrincipal.addView(rAndMyGrid);

        etComment = creeazaEditText("Comment", InputType.TYPE_CLASS_TEXT);
        viewPrincipal.addView(etComment);

        tvStareEditare = new TextView(context);
        tvStareEditare.setText("");
        tvStareEditare.setTextColor(Color.parseColor("#FFA000"));
        tvStareEditare.setTextSize(13);
        tvStareEditare.setTypeface(null, Typeface.BOLD);
        tvStareEditare.setPadding(0, 0, 0, 8);
        tvStareEditare.setVisibility(View.GONE);
        viewPrincipal.addView(tvStareEditare);

        TextView tvIstoricLabel = new TextView(context);
        tvIstoricLabel.setText("QSO History (long press to delete):");
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

    /**
     * Cere frecvența și modul de la radio și le pune în formular.
     * fortat=true (butonul "Preia din radio", indicativ importat din RTTY/Cluster):
     * suprascrie ambele câmpuri. fortat=false (simpla intrare în Logger): completează
     * doar câmpurile goale, ca să nu se piardă frecvența/modul de sesiune introduse manual.
     */
    private void solicitaFrecventaSiModDinRadio(boolean fortat) {
        if (radioManager == null) return;
        try {
            radioManager.trimiteComandaCAT("FA;");
            radioManager.trimiteComandaCAT("MD0;");
        } catch (Exception e) { return; }
        handler.postDelayed(() -> {
            // În editarea unui QSO vechi nu atingem câmpurile lui
            if (idQsoInEditare != null) return;
            long hz = StareRadio.getFrecventaHz();
            if (hz > 0 && (fortat || etFreq.getText().toString().trim().isEmpty())) {
                double mhz = hz / 1_000_000.0;
                etFreq.setText(String.format(Locale.US, "%.6f", mhz));
                String banda = StareRadio.deriveazaBanda(hz);
                if (!banda.isEmpty()) etBand.setText(banda);
            }
            String modText = StareRadio.getModText();
            if (modText != null && !modText.isEmpty()
                    && (fortat || etMode.getText().toString().trim().isEmpty())) {
                etMode.setText(traduModCatInText(modText));
            }
        }, 400);
    }

    /**
     * Răspunsul CAT "MD0x" -> denumirea modului pentru log. Codurile x (FTX-1):
     * 1 LSB, 2 USB, 3 CW-U, 4 FM, 5 AM, 6 RTTY-L, 7 CW-L, 8 DATA-L, 9 RTTY-U,
     * A DATA-FM, B FM-N, C DATA-U, D AM-N, E PSK, F DATA-FM-N.
     */
    private String traduModCatInText(String codMod) {
        String c = codMod.replace(";", "").trim().toUpperCase(Locale.US);
        if (c.length() < 2) return c;
        switch (c.charAt(1)) {
            case '1': return "LSB";
            case '2': return "USB";
            case '3': case '7': return "CW";
            case '4': case 'B': return "FM";
            case '5': case 'D': return "AM";
            case '6': case '9': return "RTTY";
            case '8': case 'A': case 'C': case 'F': return "DATA";
            case 'E': return "PSK";
            default: return c;
        }
    }

    private void salveazaQso() {
        String call = etCall.getText().toString().trim().toUpperCase(Locale.US);
        if (call.isEmpty()) {
            Toast.makeText(context, "Enter the callsign of the station worked.", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(context, "QSO updated: " + call, Toast.LENGTH_SHORT).show();
        } else {
            long idNou = dbHelper.insereazaQso(qso);
            Toast.makeText(context, "QSO saved: " + call, Toast.LENGTH_SHORT).show();
            // Fără grid -> îl căutăm în fundal (QRZ, apoi HamQTH) și completăm QSO-ul salvat
            if (idNou > 0 && qso.gridsquare.isEmpty() && GridLookup.areCont(context)) {
                GridLookup.cauta(context, call, grid -> {
                    if (grid != null && dbHelper.completeazaGridDacaLipseste(idNou, grid)) {
                        refreshListaQso();
                    }
                });
            }
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
        
        btnAdaugaQso.setText("SAVE CHANGES");
        btnAnuleazaEditare.setVisibility(View.VISIBLE);
        if (btnPreiaDinRadio != null) btnPreiaDinRadio.setVisibility(View.GONE);
        
        tvStareEditare.setText("Editing QSO: " + q.call + " (" + q.qsoDate + ")");
        tvStareEditare.setVisibility(View.VISIBLE);
    }

    /** Pune ora UTC curentă în câmpul de oră, fără să o marcheze ca introdusă manual. */
    private void puneOraUtcAcum() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        setamOraProgramatic = true;
        try {
            etTimeOn.setText(sdf.format(Calendar.getInstance(TimeZone.getTimeZone("UTC")).getTime()));
        } finally {
            setamOraProgramatic = false;
        }
    }

    private void iesiDinModEditare() {
        boolean eraInEditare = idQsoInEditare != null;
        idQsoInEditare = null;
        qsoDateInEditare = null;
        if (eraInEditare) {
            // Revenim la frecvența/modul de sesiune (formularul arăta valorile QSO-ului editat)
            etFreq.setText(sesiuneFreq);
            etMode.setText(sesiuneMod);
        }
        // QSO încheiat (salvat / anulat) -> următorul pornește cu ora automată
        oraSetataManual = false;
        puneOraUtcAcum();
        etCall.setText("");
        etName.setText("");
        etQth.setText("");
        etGrid.setText("");
        etComment.setText("");
        etRstSent.setText("59");
        etRstRcvd.setText("59");
        btnAdaugaQso.setText("ADD QSO TO LOG");
        btnAnuleazaEditare.setVisibility(View.GONE);
        if (btnPreiaDinRadio != null) btnPreiaDinRadio.setVisibility(View.VISIBLE);
        
        tvStareEditare.setVisibility(View.GONE);
    }

    private void initiazaCautareQrz() {
        String call = etCall.getText().toString().trim();
        if (call.isEmpty()) {
            Toast.makeText(context, "Enter a callsign first.", Toast.LENGTH_SHORT).show();
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(EcranSetariProprii.PREFS_NAME, Context.MODE_PRIVATE);
        String user = prefs.getString(EcranSetariProprii.PREF_QRZ_USER, "");
        String pass = SecurePrefsHelper.getPassword(context, EcranSetariProprii.PREF_QRZ_PASS, "");
        if (user.isEmpty() || pass.isEmpty()) {
            Toast.makeText(context, "Configure QRZ.com username/password in the MY SETTINGS tab.", Toast.LENGTH_LONG).show();
            return;
        }
        executaCautareQrz(call);
    }

    private void executaCautareQrz(String call) {
        SharedPreferences prefs = context.getSharedPreferences(EcranSetariProprii.PREFS_NAME, Context.MODE_PRIVATE);
        String userQrz = prefs.getString(EcranSetariProprii.PREF_QRZ_USER, "");
        String passQrz = SecurePrefsHelper.getPassword(context, EcranSetariProprii.PREF_QRZ_PASS, "");
        if (userQrz.isEmpty() || passQrz.isEmpty()) {
            Toast.makeText(context, "Configure QRZ.com in MY SETTINGS", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(context, "Searching " + call + " on QRZ.com...", Toast.LENGTH_SHORT).show();
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
                    Toast.makeText(context, "QRZ: found " + call + " (with grid)", Toast.LENGTH_SHORT).show();
                    return;
                }
                Toast.makeText(context, "QRZ: no grid. Trying HamQTH...", Toast.LENGTH_SHORT).show();
                cautaPeHamQthPentruGrid(call);
            }
            @Override
            public void onEroare(String mesaj) {
                Toast.makeText(context, "QRZ error: " + mesaj + "\nTrying HamQTH...", Toast.LENGTH_SHORT).show();
                cautaPeHamQthPentruGrid(call);
            }
        });
    }

    private void cautaPeHamQthPentruGrid(String call) {
        SharedPreferences prefs = context.getSharedPreferences(EcranSetariProprii.PREFS_NAME, Context.MODE_PRIVATE);
        String userHam = prefs.getString(EcranSetariProprii.PREF_HAMQTH_USER, "");
        String passHam = SecurePrefsHelper.getPassword(context, EcranSetariProprii.PREF_HAMQTH_PASS, "");
        if (userHam.isEmpty() || passHam.isEmpty()) {
            Toast.makeText(context, "Configure HamQTH.com in MY SETTINGS for grid!", Toast.LENGTH_LONG).show();
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
                Toast.makeText(context, "HamQTH error: " + mesaj, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void preiaGridDinGps() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions((Activity) context,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_CODE_LOCATION);
            Toast.makeText(context, "Allow location access, then press GPS again.", Toast.LENGTH_LONG).show();
            return;
        }
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            Toast.makeText(context, "Location service not available.", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(context, "Searching for GPS position...", Toast.LENGTH_SHORT).show();
        LocationListener listener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                String grid = GridLocatorHelper.calculeazaGrid(location.getLatitude(), location.getLongitude());
                etMyGrid.setText(grid);
                Toast.makeText(context, "GPS Grid: " + grid, Toast.LENGTH_SHORT).show();
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
                Toast.makeText(context, "Enable location (GPS) in phone settings.", Toast.LENGTH_LONG).show();
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
            Toast.makeText(context, "Missing location permission.", Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshListaQso() {
        List<QsoDbHelper.Qso> qsouri = dbHelper.getToateQsourile();
        List<String> linii = new ArrayList<>();
        List<CharSequence> liniiStilizate = new ArrayList<>();
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
            String inainteDeCall = dataFormatata + " " + oraFormatata + "Z  |  ";
            String linie = String.format(Locale.US, "%s%-10s  |  %6s %-5s  |  RST %s/%s",
                    inainteDeCall, callStr, freqStr, modeStr, rstSentStr, rstRcvdStr);
            linii.add(linie);

            // Indicativul evidentiat: bold, galben-verzui, putin mai mare. Span-ul acopera
            // tot campul de 10 caractere (cu spatiile de padding), ca latimea coloanei sa fie
            // aceeasi pe toate randurile si restul sa ramana aliniat.
            SpannableString linieStilizata = new SpannableString(linie);
            int start = inainteDeCall.length();
            int end = start + Math.max(10, callStr.length());
            linieStilizata.setSpan(new ForegroundColorSpan(Color.parseColor("#C6FF00")), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            linieStilizata.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            linieStilizata.setSpan(new RelativeSizeSpan(1.2f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            liniiStilizate.add(linieStilizata);
        }

        ArrayAdapter<CharSequence> adapter = new ArrayAdapter<CharSequence>(context, android.R.layout.simple_list_item_1, liniiStilizate) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView tv = (TextView) view.findViewById(android.R.id.text1);
                
                // Stil text discret
                tv.setTextColor(Color.parseColor("#E0E0E0"));
                tv.setTextSize(11);
                tv.setTypeface(Typeface.MONOSPACE);

                // Alternanță culori (Zebra Striping)
                if (position % 2 == 0) {
                    view.setBackgroundColor(Color.parseColor("#1A1A1A"));
                } else {
                    view.setBackgroundColor(Color.parseColor("#222222"));
                }
                return view;
            }
        };

        lvIstoricQso.setAdapter(adapter);
        lvIstoricQso.setOnItemClickListener((parent, view, position, id) ->
                intraInModEditare(qsouri.get(position)));
        lvIstoricQso.setOnItemLongClickListener((parent, view, position, id) -> {
            long idQsoDeSters = idUri.get(position);
            String linieAfisata = linii.get(position);
            new AlertDialog.Builder(context)
                    .setTitle("Delete QSO")
                    .setMessage("Delete this QSO from the log?\n\n" + linieAfisata)
                    .setPositiveButton("Delete", (dialog, which) -> {
                        dbHelper.stergeQso(idQsoDeSters);
                        if (idQsoInEditare != null && idQsoInEditare == idQsoDeSters) iesiDinModEditare();
                        refreshListaQso();
                    })
                    .setNeutralButton("Delete ALL", (dialog, which) -> confirmaStergereTotLogul(qsouri.size()))
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        });
    }

    /**
     * A doua confirmare pentru ștergerea întregului log: butonul Delete rămâne
     * dezactivat până când utilizatorul bifează explicit căsuța de confirmare.
     * Opțional (bifat implicit) se face întâi un backup ADIF - același export ca
     * "EXPORT ADIF LOG" din My Settings; dacă backup-ul eșuează, NU se șterge nimic.
     */
    private void confirmaStergereTotLogul(int numarQso) {
        CheckBox cbConfirm = new CheckBox(context);
        cbConfirm.setText("Yes, delete all " + numarQso + " QSOs");
        CheckBox cbBackup = new CheckBox(context);
        cbBackup.setText("Make ADIF backup first (Downloads)");
        cbBackup.setChecked(true);
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(50, 20, 50, 0);
        container.addView(cbBackup);
        container.addView(cbConfirm);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Delete entire log")
                .setMessage("Do you really want to delete ALL QSOs from the log?\n\nThis cannot be undone.")
                .setView(container)
                .setPositiveButton("Delete", (d, which) -> {
                    if (cbBackup.isChecked()) {
                        backupApoiStergeTotLogul();
                    } else {
                        stergeTotLogulSiActualizeaza(null);
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(d -> {
            Button btnDelete = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            btnDelete.setEnabled(false);
            btnDelete.setTextColor(Color.GRAY);
            cbConfirm.setOnCheckedChangeListener((cb, bifat) -> {
                btnDelete.setEnabled(bifat);
                btnDelete.setTextColor(bifat ? Color.parseColor("#D32F2F") : Color.GRAY);
            });
        });
        dialog.show();
    }

    private void backupApoiStergeTotLogul() {
        Toast.makeText(context, "Creating ADIF backup...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                String numeBackup = AdifExporter.exportaAdif(context, dbHelper);
                handler.post(() -> stergeTotLogulSiActualizeaza(numeBackup));
            } catch (Exception e) {
                handler.post(() -> Toast.makeText(context,
                        "Backup FAILED - log was NOT deleted.\n" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "LogBackupDelete").start();
    }

    private void stergeTotLogulSiActualizeaza(String numeBackup) {
        int sterse = dbHelper.stergeTotLogul();
        if (idQsoInEditare != null) iesiDinModEditare();
        refreshListaQso();
        String mesaj = sterse + " QSOs deleted.";
        if (numeBackup != null) mesaj = "Backup saved:\n" + numeBackup + "\n\n" + mesaj;
        Toast.makeText(context, mesaj, Toast.LENGTH_LONG).show();
    }

    private void actualizeazaCeasHeader() {
        SimpleDateFormat sdfUtc = new SimpleDateFormat("HH:mm", Locale.US);
        sdfUtc.setTimeZone(TimeZone.getTimeZone("UTC"));
        String utcStr = sdfUtc.format(Calendar.getInstance(TimeZone.getTimeZone("UTC")).getTime());

        SimpleDateFormat sdfLocal = new SimpleDateFormat("HH:mm", Locale.US);
        sdfLocal.setTimeZone(TimeZone.getDefault());
        String localStr = sdfLocal.format(Calendar.getInstance().getTime());

        if (tvTitluCeas != null) {
            tvTitluCeas.setText(String.format("QSO LOGGER | UTC: %s | LOCAL: %s", utcStr, localStr));
        }
    }

    private void curataCampuriLoggerSelectiv() {
        etCall.setText("");
        etName.setText("");
        etQth.setText("");
        etGrid.setText("");
        etComment.setText("");
        etPower.setText("");
        // NU curatam etMyGrid, etTimeOn, etRstSent, etRstRcvd conform cerintei.
        // Nici etFreq/etBand/etMode - sunt frecventa/modul de sesiune (pana la EXIT).
        Toast.makeText(context, "Station data cleared", Toast.LENGTH_SHORT).show();
    }

    private Drawable createXIcon() {
        int size = 48; // dimensiune în pixeli
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        // Fundal roșu circular
        Paint bgPaint = new Paint();
        bgPaint.setColor(Color.parseColor("#B71C1C"));
        bgPaint.setAntiAlias(true);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bgPaint);

        // X alb în centru
        Paint xPaint = new Paint();
        xPaint.setColor(Color.WHITE);
        xPaint.setStrokeWidth(4f);
        xPaint.setAntiAlias(true);
        xPaint.setStrokeCap(Paint.Cap.ROUND);

        float margin = size * 0.3f;
        canvas.drawLine(margin, margin, size - margin, size - margin, xPaint);
        canvas.drawLine(size - margin, margin, margin, size - margin, xPaint);

        return new BitmapDrawable(context.getResources(), bitmap);
    }

    public void preiaIndicativDinRtty(String callsign) {
        if (callsign == null || callsign.isEmpty()) return;
        etCall.setText(callsign.toUpperCase());
        etCall.setSelection(etCall.getText().length());
        // Indicativ nou (poate înlocui unul existent) -> ora de acum
        if (!oraSetataManual) puneOraUtcAcum();
        solicitaFrecventaSiModDinRadio(true);
        executaCautareQrz(callsign);
        Toast.makeText(context, callsign + " imported into Logger", Toast.LENGTH_SHORT).show();
    }
}