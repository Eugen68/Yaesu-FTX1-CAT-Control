package com.example.yaesucatcontroladv;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class EcranRttyDroid {
    private final Context context;
    private final RadioManager radioManager;
    private ScrollView viewPrincipal;

    // Elemente UI
    private Waterfall waterfall;
    private TextView tvFrecventaCurenta;
    private SeekBar sbSensibilitate, sbLatimeFiltru;
    private TextView tvSensibilitateValoare, tvFiltruValoare;
    private ScrollView svRttyScroll;
    private TextView tvRttyTerminal;
    private Button btnRttyAudioToggle, btnRttyClear, btnRttyReverseToggle, btnAutoTune;
    private Button btnDecoderToggle, btnTrackToggle;
    private EditText etRttyInputLive;
    private Button btnRttySendLive, btnRttyPttToggle;
    private Button[] btnMacrosRtty = new Button[8];

    // NOU: Interfață pentru comunicare cu Logger (click pe indicativ)
    public interface OnCallsignTappedListener {
        void onCallsignTapped(String callsign);
    }
    private OnCallsignTappedListener callsignListener;
    private final StringBuilder bufferCuvantCurent = new StringBuilder();

    public void setOnCallsignTappedListener(OnCallsignTappedListener listener) {
        this.callsignListener = listener;
    }

    // Stări recepție
    private boolean decodareRttyActiva = false;
    private boolean regimReverseSoftware = false;
    private char currentRttyShift = 'L';
    private short[] miniBufferFft = new short[512];

    // Coeficienți
    private double frecventaTunedBaza = 2125.0;
    private double qMarkA, qSpaceA;
    private double sampleRateFiltre = 48000.0;
    private double rCheb = 0.992;
    private volatile double pragSensibilitate = 0.05;
    private int modUrmarireFrecventa = 0;

    // Matched Filter & SNR
    private volatile boolean folosesteMatchedFilter = false;
    private double[] mfBuffer;
    private int mfBufferPos = 0;
    private double[] mfMarkTemplateSin, mfMarkTemplateCos;
    private double[] mfSpaceTemplateSin, mfSpaceTemplateCos;
    private int mfSamplesPerBit = 1056;
    private String snrTextLive = "--.- dB";
    private double noiseFloor = 1e-9;

    private final short[] vectorIstoricPeak = new short[512];
    private volatile boolean solicitaResetFiltre = false;

    // Emisie
    private boolean isTransmittingRtty = false;
    private char currentTxShift = 'L';

    // ITA2 (Baudot)
    private static final char[] ITA2_LTRS_MAP = {
            '\0', 'E', '\n', 'A', ' ', 'S', 'I', 'U', '\n', 'D', 'R', 'J', 'N', 'F', 'C', 'K',
            'T', 'Z', 'L', 'W', 'H', 'Y', 'P', 'Q', 'O', 'B', 'G', '\0', 'M', 'X', 'V', '\0'
    };
    private static final char[] ITA2_FIGS_MAP = {
            '\0', '3', '\n', '-', ' ', '\'', '8', '7', '\n', '4', '4', '\u0007', ',', '!', ':', '(',
            '5', '+', ')', '2', '#', '6', '0', '1', '9', '?', '&', '\0', '.', '/', ';', '\0'
    };
    private static final int[] CHAR_TO_BAUDOT_CODE = new int[256];
    private static final boolean[] CHAR_IS_FIGS = new boolean[256];

    static {
        for (int i = 0; i < 32; i++) {
            char l = ITA2_LTRS_MAP[i];
            if (l != '\0' && l != '\n') { CHAR_TO_BAUDOT_CODE[l] = i; CHAR_IS_FIGS[l] = false; }
            char f = ITA2_FIGS_MAP[i];
            if (f != '\0' && f != '\n' && f != '\u0007') { CHAR_TO_BAUDOT_CODE[f] = i; CHAR_IS_FIGS[f] = true; }
        }
        CHAR_TO_BAUDOT_CODE[' '] = 4;  CHAR_IS_FIGS[' '] = false;
        CHAR_TO_BAUDOT_CODE['\n'] = 2; CHAR_IS_FIGS['\n'] = false;
        CHAR_TO_BAUDOT_CODE['\r'] = 8;  CHAR_IS_FIGS['\r'] = false;
    }

    public EcranRttyDroid(Context context, RadioManager radioManager) {
        this.context = context;
        this.radioManager = radioManager;
        construiesteInterfata();
    }

    private void initMatchedFilterTemplates(int sampleRate, double baud) {
        mfSamplesPerBit = (int) Math.round(sampleRate / baud);
        mfBuffer = new double[mfSamplesPerBit];
        mfMarkTemplateSin = new double[mfSamplesPerBit];
        mfMarkTemplateCos = new double[mfSamplesPerBit];
        mfSpaceTemplateSin = new double[mfSamplesPerBit];
        mfSpaceTemplateCos = new double[mfSamplesPerBit];
        mfBufferPos = 0;

        double fMark = frecventaTunedBaza;
        double fSpace = frecventaTunedBaza + (regimReverseSoftware ? -170.0 : 170.0);

        for (int i = 0; i < mfSamplesPerBit; i++) {
            double t = (double) i / sampleRate;
            mfMarkTemplateSin[i] = Math.sin(2 * Math.PI * fMark * t);
            mfMarkTemplateCos[i] = Math.cos(2 * Math.PI * fMark * t);
            mfSpaceTemplateSin[i] = Math.sin(2 * Math.PI * fSpace * t);
            mfSpaceTemplateCos[i] = Math.cos(2 * Math.PI * fSpace * t);
        }
    }

    private void recalculeazaShabloaneMF() {
        if (mfMarkTemplateSin != null) {
            double fMark = frecventaTunedBaza;
            double fSpace = frecventaTunedBaza + (regimReverseSoftware ? -170.0 : 170.0);
            for (int i = 0; i < mfSamplesPerBit; i++) {
                double t = (double) i / sampleRateFiltre;
                mfMarkTemplateSin[i] = Math.sin(2 * Math.PI * fMark * t);
                mfMarkTemplateCos[i] = Math.cos(2 * Math.PI * fMark * t);
                mfSpaceTemplateSin[i] = Math.sin(2 * Math.PI * fSpace * t);
                mfSpaceTemplateCos[i] = Math.cos(2 * Math.PI * fSpace * t);
            }
        }
    }

    private void sincronizeazaSetariReceptie() {
        qMarkA = 2.0 * Math.cos(2.0 * Math.PI * frecventaTunedBaza / sampleRateFiltre);
        double fSpace = frecventaTunedBaza + (regimReverseSoftware ? -170.0 : 170.0);
        qSpaceA = 2.0 * Math.cos(2.0 * Math.PI * fSpace / sampleRateFiltre);

        recalculeazaShabloaneMF();

        if (waterfall != null) {
            waterfall.setTunedFrequency((int) frecventaTunedBaza);
            waterfall.setShiftHz(regimReverseSoftware ? -170 : 170);
        }
        if (btnRttyReverseToggle != null) {
            btnRttyReverseToggle.setText(regimReverseSoftware ? "REV" : "NORM");
            btnRttyReverseToggle.setBackgroundColor(Color.parseColor(regimReverseSoftware ? "#B71C1C" : "#757575"));
        }
        if (tvFrecventaCurenta != null) {
            tvFrecventaCurenta.setText(String.format("MARK: %d Hz | Shift: 170 Hz | SNR: %s",
                    (int) frecventaTunedBaza, snrTextLive));
        }
        solicitaResetFiltre = true;
    }

    public ScrollView getView() {
        actualizeazaEticheteButoaneMacro();
        return viewPrincipal;
    }

    private void actualizeazaEticheteButoaneMacro() {
        SharedPreferences prefs = context.getSharedPreferences("RTTY_MACROS", Context.MODE_PRIVATE);
        for (int i = 0; i < 8; i++) {
            if (btnMacrosRtty[i] != null) {
                String titluSalvat = prefs.getString("rtty_title_" + i, "").trim();
                if (!titluSalvat.isEmpty()) {
                    btnMacrosRtty[i].setText(titluSalvat.toUpperCase());
                } else {
                    String continut = prefs.getString("rtty_content_" + i, "");
                    if (continut.isEmpty()) {
                        btnMacrosRtty[i].setText("M" + (i + 1));
                    } else {
                        String preview = continut.trim().replaceAll("\\s+", " ");
                        if (preview.length() > 5) preview = preview.substring(0, 5).trim();
                        btnMacrosRtty[i].setText(preview.toUpperCase());
                    }
                }
            }
        }
    }

    private void construiesteInterfata() {
        viewPrincipal = new ScrollView(context);
        viewPrincipal.setBackgroundColor(Color.parseColor("#121212"));
        LinearLayout llRttyMain = new LinearLayout(context);
        llRttyMain.setOrientation(LinearLayout.VERTICAL);
        llRttyMain.setPadding(30, 30, 30, 30);

        // HEADER
        tvFrecventaCurenta = new TextView(context);
        tvFrecventaCurenta.setText(String.format("MARK: %d Hz | Shift: 170 Hz | SNR: %s",
                (int) frecventaTunedBaza, snrTextLive));
        tvFrecventaCurenta.setTextColor(Color.parseColor("#00E5FF"));
        tvFrecventaCurenta.setTextSize(11);
        tvFrecventaCurenta.setTypeface(null, Typeface.BOLD);
        tvFrecventaCurenta.setPadding(4, 0, 0, 4);
        tvFrecventaCurenta.setSingleLine(true);
        llRttyMain.addView(tvFrecventaCurenta);

        // WATERFALL
        waterfall = new Waterfall(context);
        LinearLayout.LayoutParams pWater = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 260);
        pWater.setMargins(0, 10, 0, 15);
        waterfall.setLayoutParams(pWater);
        waterfall.setTunedFrequency((int) frecventaTunedBaza);
        waterfall.setShiftHz(170);
        waterfall.setOnFrequencyTappedListener(frequencyHz -> {
            frecventaTunedBaza = frequencyHz;
            sincronizeazaSetariReceptie();
        });
        llRttyMain.addView(waterfall);

        // TERMINAL
        svRttyScroll = new ScrollView(context);
        LinearLayout.LayoutParams pSv = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 480);
        pSv.setMargins(0, 0, 0, 20);
        svRttyScroll.setLayoutParams(pSv);
        svRttyScroll.setBackgroundColor(Color.BLACK);
        svRttyScroll.setPadding(15, 15, 15, 15);
        tvRttyTerminal = new TextView(context);
        tvRttyTerminal.setText("--- RTTY RECEIVE TERMINAL ---\n");
        tvRttyTerminal.setTextColor(Color.parseColor("#00FF00"));
        tvRttyTerminal.setTextSize(14);
        tvRttyTerminal.setTypeface(Typeface.MONOSPACE);
        // NOU: Activăm click-urile pe SpannableString
        tvRttyTerminal.setMovementMethod(LinkMovementMethod.getInstance());
        tvRttyTerminal.setHighlightColor(Color.TRANSPARENT);
        svRttyScroll.addView(tvRttyTerminal);
        llRttyMain.addView(svRttyScroll);

        // RÂND 1: START | CLEAR | NORM/REV | A-TUNE
        LinearLayout rAndControaleRtty = new LinearLayout(context);
        rAndControaleRtty.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams pBtn = new LinearLayout.LayoutParams(0, 120, 1f);
        pBtn.setMargins(4, 5, 4, 15);

        btnRttyAudioToggle = new Button(context);
        btnRttyAudioToggle.setText("START");
        btnRttyAudioToggle.setBackgroundColor(Color.parseColor("#E65100"));
        btnRttyAudioToggle.setTextColor(Color.WHITE);
        btnRttyAudioToggle.setLayoutParams(pBtn);
        btnRttyAudioToggle.setOnClickListener(v -> {
            if (!decodareRttyActiva) {
                radioManager.trimiteComandaCAT("AI0;");
                verificareSiPornireDecodor();
            } else {
                opresteDecodorRtty();
                radioManager.trimiteComandaCAT("AI1;");
            }
        });
        rAndControaleRtty.addView(btnRttyAudioToggle);

        btnRttyClear = new Button(context);
        btnRttyClear.setText("CLEAR");
        btnRttyClear.setBackgroundColor(Color.parseColor("#455A64"));
        btnRttyClear.setTextColor(Color.WHITE);
        btnRttyClear.setLayoutParams(pBtn);
        btnRttyClear.setOnClickListener(v -> {
            tvRttyTerminal.setText("--- RTTY RECEIVE TERMINAL ---\n");
            bufferCuvantCurent.setLength(0);
        });
        rAndControaleRtty.addView(btnRttyClear);

        btnRttyReverseToggle = new Button(context);
        btnRttyReverseToggle.setText("NORM");
        btnRttyReverseToggle.setBackgroundColor(Color.parseColor("#757575"));
        btnRttyReverseToggle.setTextColor(Color.WHITE);
        btnRttyReverseToggle.setLayoutParams(pBtn);
        btnRttyReverseToggle.setOnClickListener(v -> {
            if (regimReverseSoftware) frecventaTunedBaza -= 170;
            else frecventaTunedBaza += 170;
            regimReverseSoftware = !regimReverseSoftware;
            sincronizeazaSetariReceptie();
        });
        rAndControaleRtty.addView(btnRttyReverseToggle);

        btnAutoTune = new Button(context);
        btnAutoTune.setText("A-TUNE");
        btnAutoTune.setBackgroundColor(Color.parseColor("#007ACC"));
        btnAutoTune.setTextColor(Color.WHITE);
        btnAutoTune.setLayoutParams(pBtn);
        btnAutoTune.setOnClickListener(v -> executaAutoTuneMecanic());
        rAndControaleRtty.addView(btnAutoTune);
        llRttyMain.addView(rAndControaleRtty);

        // RÂND 2: TOGGLE DECODER & TRACK
        LinearLayout rAndDecoderToggle = new LinearLayout(context);
        rAndDecoderToggle.setOrientation(LinearLayout.HORIZONTAL);
        rAndDecoderToggle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams pRandDecoder = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pRandDecoder.setMargins(0, 5, 0, 15);
        rAndDecoderToggle.setLayoutParams(pRandDecoder);

        btnDecoderToggle = new Button(context);
        btnDecoderToggle.setText(folosesteMatchedFilter ? "DECODER: MATCHED FILTER" : "DECODER: CHEBYSHEV");
        btnDecoderToggle.setBackgroundColor(Color.parseColor(folosesteMatchedFilter ? "#6A1B9A" : "#00695C"));
        btnDecoderToggle.setTextColor(Color.WHITE);
        btnDecoderToggle.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams pBtnDecoder = new LinearLayout.LayoutParams(0, 110, 1f);
        pBtnDecoder.setMargins(10, 0, 10, 0);
        btnDecoderToggle.setLayoutParams(pBtnDecoder);
        btnDecoderToggle.setOnClickListener(v -> {
            folosesteMatchedFilter = !folosesteMatchedFilter;
            btnDecoderToggle.setText(folosesteMatchedFilter ? "DECODER: MATCHED FILTER" : "DECODER: CHEBYSHEV");
            btnDecoderToggle.setBackgroundColor(Color.parseColor(folosesteMatchedFilter ? "#6A1B9A" : "#00695C"));
            if (folosesteMatchedFilter && mfMarkTemplateSin == null) {
                initMatchedFilterTemplates(48000, 45.45);
            }
            recalculeazaShabloaneMF();
            solicitaResetFiltre = true;
            Toast.makeText(context, "Decoder: " + (folosesteMatchedFilter ? "Matched Filter" : "Chebyshev"),
                    Toast.LENGTH_SHORT).show();
        });
        rAndDecoderToggle.addView(btnDecoderToggle);

        btnTrackToggle = new Button(context);
        btnTrackToggle.setText("TRK:OFF");
        btnTrackToggle.setBackgroundColor(Color.parseColor("#757575"));
        btnTrackToggle.setTextColor(Color.WHITE);
        btnTrackToggle.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams pBtnTrack = new LinearLayout.LayoutParams(0, 110, 1f);
        pBtnTrack.setMargins(10, 0, 10, 0);
        btnTrackToggle.setLayoutParams(pBtnTrack);
        btnTrackToggle.setOnClickListener(v -> {
            modUrmarireFrecventa = (modUrmarireFrecventa + 1) % 3;
            if (modUrmarireFrecventa == 0) {
                btnTrackToggle.setText("TRK:OFF");
                btnTrackToggle.setBackgroundColor(Color.parseColor("#757575"));
            } else if (modUrmarireFrecventa == 1) {
                btnTrackToggle.setText("TRK:AFC");
                btnTrackToggle.setBackgroundColor(Color.parseColor("#007ACC"));
            } else {
                btnTrackToggle.setText("TRK:PLL");
                btnTrackToggle.setBackgroundColor(Color.parseColor("#2E7D32"));
            }
        });
        rAndDecoderToggle.addView(btnTrackToggle);
        llRttyMain.addView(rAndDecoderToggle);

        // SLIDER SENSIBILITATE
        LinearLayout rAndSensibilitate = new LinearLayout(context);
        rAndSensibilitate.setOrientation(LinearLayout.HORIZONTAL);
        rAndSensibilitate.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams pRandSens = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pRandSens.setMargins(0, 0, 0, 15);
        rAndSensibilitate.setLayoutParams(pRandSens);

        TextView tvSensLabel = new TextView(context);
        tvSensLabel.setText("Sensibilitate:");
        tvSensLabel.setTextColor(Color.WHITE);
        tvSensLabel.setTextSize(13);
        rAndSensibilitate.addView(tvSensLabel);

        sbSensibilitate = new SeekBar(context);
        sbSensibilitate.setMax(20);
        sbSensibilitate.setProgress((int) Math.round(pragSensibilitate * 100));
        LinearLayout.LayoutParams pSeek = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        pSeek.setMargins(15, 0, 15, 0);
        sbSensibilitate.setLayoutParams(pSeek);
        rAndSensibilitate.addView(sbSensibilitate);

        tvSensibilitateValoare = new TextView(context);
        tvSensibilitateValoare.setText(String.format("%d%%", (int) Math.round(pragSensibilitate * 100)));
        tvSensibilitateValoare.setTextColor(Color.parseColor("#00E5FF"));
        tvSensibilitateValoare.setTextSize(13);
        tvSensibilitateValoare.setTypeface(null, Typeface.BOLD);
        rAndSensibilitate.addView(tvSensibilitateValoare);

        sbSensibilitate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                pragSensibilitate = progress / 100.0;
                tvSensibilitateValoare.setText(String.format("%d%%", progress));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        llRttyMain.addView(rAndSensibilitate);

        // SLIDER LĂȚIME FILTRU
        LinearLayout rAndFiltru = new LinearLayout(context);
        rAndFiltru.setOrientation(LinearLayout.HORIZONTAL);
        rAndFiltru.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams pRandFiltru = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pRandFiltru.setMargins(0, 0, 0, 15);
        rAndFiltru.setLayoutParams(pRandFiltru);

        TextView tvFiltruLabel = new TextView(context);
        tvFiltruLabel.setText("Lățime Filtru:");
        tvFiltruLabel.setTextColor(Color.WHITE);
        tvFiltruLabel.setTextSize(13);
        rAndFiltru.addView(tvFiltruLabel);

        sbLatimeFiltru = new SeekBar(context);
        sbLatimeFiltru.setMax(20);
        sbLatimeFiltru.setProgress(12);
        LinearLayout.LayoutParams pSeekFiltru = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        pSeekFiltru.setMargins(15, 0, 15, 0);
        sbLatimeFiltru.setLayoutParams(pSeekFiltru);
        rAndFiltru.addView(sbLatimeFiltru);

        tvFiltruValoare = new TextView(context);
        tvFiltruValoare.setText("~60 Hz");
        tvFiltruValoare.setTextColor(Color.parseColor("#00E5FF"));
        tvFiltruValoare.setTextSize(13);
        tvFiltruValoare.setTypeface(null, Typeface.BOLD);
        rAndFiltru.addView(tvFiltruValoare);

        sbLatimeFiltru.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                rCheb = 0.985 + (progress * 0.00065);
                int bwHz = (int) Math.round((1.0 - rCheb) * 48000.0 / (2 * Math.PI));
                tvFiltruValoare.setText("~" + bwHz + " Hz");
                solicitaResetFiltre = true;
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        llRttyMain.addView(rAndFiltru);

        // TX LIVE
        LinearLayout rAndTxLive = new LinearLayout(context);
        rAndTxLive.setOrientation(LinearLayout.HORIZONTAL);
        rAndTxLive.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams pRandTx = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pRandTx.setMargins(0, 5, 0, 15);
        rAndTxLive.setLayoutParams(pRandTx);

        etRttyInputLive = new EditText(context);
        etRttyInputLive.setHint("Type live FSK text...");
        etRttyInputLive.setHintTextColor(Color.GRAY);
        etRttyInputLive.setTextColor(Color.WHITE);
        etRttyInputLive.setTextSize(14);
        etRttyInputLive.setLayoutParams(new LinearLayout.LayoutParams(0, 130, 2.2f));
        rAndTxLive.addView(etRttyInputLive);

        btnRttySendLive = new Button(context);
        btnRttySendLive.setText("SEND");
        btnRttySendLive.setBackgroundColor(Color.parseColor("#2E7D32"));
        btnRttySendLive.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams pBtnSend = new LinearLayout.LayoutParams(0, 130, 0.9f);
        pBtnSend.setMargins(10, 0, 10, 0);
        btnRttySendLive.setLayoutParams(pBtnSend);
        btnRttySendLive.setOnClickListener(v -> {
            String txt = etRttyInputLive.getText().toString();
            if (!txt.isEmpty()) {
                transmiteMesajTextRttyBaudot(txt);
                etRttyInputLive.setText("");
            }
        });
        rAndTxLive.addView(btnRttySendLive);

        btnRttyPttToggle = new Button(context);
        btnRttyPttToggle.setText("PTT");
        btnRttyPttToggle.setBackgroundColor(Color.parseColor("#424242"));
        btnRttyPttToggle.setTextColor(Color.WHITE);
        btnRttyPttToggle.setLayoutParams(new LinearLayout.LayoutParams(0, 130, 0.9f));
        btnRttyPttToggle.setOnClickListener(v -> comutaStarePttManual());
        rAndTxLive.addView(btnRttyPttToggle);
        llRttyMain.addView(rAndTxLive);

        // MACRO-URI
        LinearLayout.LayoutParams pMacro = new LinearLayout.LayoutParams(0, 120, 1f);
        pMacro.setMargins(4, 5, 4, 10);

        LinearLayout rAndMacros1 = new LinearLayout(context);
        rAndMacros1.setOrientation(LinearLayout.HORIZONTAL);
        rAndMacros1.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        for (int i = 0; i < 4; i++) {
            final int indexMacro = i;
            btnMacrosRtty[i] = new Button(context);
            btnMacrosRtty[i].setText("M" + (i + 1));
            btnMacrosRtty[i].setBackgroundColor(Color.parseColor("#607D8B"));
            btnMacrosRtty[i].setTextColor(Color.WHITE);
            btnMacrosRtty[i].setLayoutParams(pMacro);
            btnMacrosRtty[i].setOnClickListener(v -> executaMacroRttyPeCanal(indexMacro));
            rAndMacros1.addView(btnMacrosRtty[i]);
        }
        llRttyMain.addView(rAndMacros1);

        LinearLayout rAndMacros2 = new LinearLayout(context);
        rAndMacros2.setOrientation(LinearLayout.HORIZONTAL);
        rAndMacros2.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        for (int i = 4; i < 8; i++) {
            final int indexMacro = i;
            btnMacrosRtty[i] = new Button(context);
            btnMacrosRtty[i].setText("M" + (i + 1));
            btnMacrosRtty[i].setBackgroundColor(Color.parseColor("#455A64"));
            btnMacrosRtty[i].setTextColor(Color.WHITE);
            btnMacrosRtty[i].setLayoutParams(pMacro);
            btnMacrosRtty[i].setOnClickListener(v -> executaMacroRttyPeCanal(indexMacro));
            rAndMacros2.addView(btnMacrosRtty[i]);
        }
        llRttyMain.addView(rAndMacros2);

        viewPrincipal.addView(llRttyMain);
    }

    // ============================================================
    // CLICK PE INDICATIVE (Callsign → Logger)
    // ============================================================
    private boolean esteIndicativValid(String text) {
        if (text == null) return false;
        text = text.trim().toUpperCase();
        if (text.length() < 3 || text.length() > 10) return false;
        // Regex: prefix + număr zonă + sufix (1-4 litere)
        return text.matches("^(?:[A-Z]{1,2}|[0-9][A-Z]|[A-Z][0-9])[0-9][A-Z]{1,4}$");
    }

    private void adaugaIndicativCuClick(String callsign) {
        if (tvRttyTerminal == null) return;
        final String callFinal = callsign.toUpperCase().trim();

        SpannableString spannable = new SpannableString(callFinal);
        ClickableSpan clickableSpan = new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                if (callsignListener != null) {
                    callsignListener.onCallsignTapped(callFinal);
                }
            }

            @Override
            public void updateDrawState(TextPaint ds) {
                super.updateDrawState(ds);
                ds.setColor(Color.parseColor("#FFD700")); // Auriu
                ds.setUnderlineText(true);
            }
        };
        spannable.setSpan(clickableSpan, 0, callFinal.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tvRttyTerminal.append(spannable);
    }

    private void proceseazaCaracterPentruClick(char c) {
        if (tvRttyTerminal == null) return;

        if (c == ' ' || c == '\n' || c == '\r') {
            String cuvant = bufferCuvantCurent.toString().trim().toUpperCase();
            bufferCuvantCurent.setLength(0);

            if (!cuvant.isEmpty()) {
                if (esteIndicativValid(cuvant)) {
                    adaugaIndicativCuClick(cuvant);
                } else {
                    tvRttyTerminal.append(cuvant);
                }
            }

            if (c == '\n' || c == '\r') tvRttyTerminal.append("\n");
            else tvRttyTerminal.append(" ");
        } else {
            bufferCuvantCurent.append(c);
        }

        if (svRttyScroll != null) {
            svRttyScroll.post(() -> svRttyScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void verificareSiPornireDecodor() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions((Activity) context, new String[]{Manifest.permission.RECORD_AUDIO}, 123);
        } else {
            pornesteDecodorRtty();
        }
    }

    @SuppressWarnings("MissingPermission")
    private void pornesteDecodorRtty() {
        decodareRttyActiva = true;
        btnRttyAudioToggle.setText("STOP");
        btnRttyAudioToggle.setBackgroundColor(Color.RED);

        new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
            int sampleRate = 48000;
            double baudRTTY = 45.45;
            double samplesPerBit = (double) sampleRate / baudRTTY;
            int bufferSize = AudioRecord.getMinBufferSize(sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (bufferSize <= 0) bufferSize = 4096;

            AudioRecord recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) return;
            recorder.startRecording();

            short[] audioBuffer = new short[bufferSize];
            double mx1 = 0, mx2 = 0, sx1 = 0, sx2 = 0;
            double dcBlockerInVech = 0, dcBlockerOutVech = 0;
            double integratVech = 0;
            double alfaIntegrator = 0.08;
            double nivelZgomotMediu = 0.05;
            boolean inFrame = false;
            double fazaCeasAcumulator = 0;
            int bitCounter = 0;
            int currentByte = 0;

            // Variabile AFC/PLL
            double fazaAFC = 0;
            double I_acc = 0, Q_acc = 0;
            double last_phase = 0;
            int samplesSinceLastAFC = 0;

            noiseFloor = 1e-9;

            if (folosesteMatchedFilter) {
                initMatchedFilterTemplates(sampleRate, baudRTTY);
            }

            sampleRateFiltre = sampleRate;
            rCheb = 0.992;
            sincronizeazaSetariReceptie();

            while (decodareRttyActiva) {
                int read = recorder.read(audioBuffer, 0, audioBuffer.length);
                if (read <= 0) {
                    try { Thread.sleep(10); } catch (Exception e) {}
                    continue;
                }

                if (solicitaResetFiltre) {
                    mx1 = 0; mx2 = 0; sx1 = 0; sx2 = 0;
                    integratVech = 0;
                    inFrame = false;
                    fazaCeasAcumulator = 0;
                    bitCounter = 0;
                    currentByte = 0;
                    if (folosesteMatchedFilter && mfBuffer != null) {
                        for (int k = 0; k < mfBuffer.length; k++) mfBuffer[k] = 0;
                        mfBufferPos = 0;
                    }
                    solicitaResetFiltre = false;
                }

                // FFT pentru Waterfall (decimare 48k -> 8k)
                if (waterfall != null && read >= 3072) {
                    synchronized (vectorIstoricPeak) {
                        for (int j = 0; j < 512; j++) {
                            short sample = audioBuffer[j * 6];
                            miniBufferFft[j] = sample;
                            vectorIstoricPeak[j] = sample;
                        }
                    }
                    short[] fftSlice = new short[512];
                    System.arraycopy(miniBufferFft, 0, fftSlice, 0, 512);
                    waterfall.pushSamples(fftSlice);
                }

                for (int i = 0; i < read; i++) {
                    double rawSample = audioBuffer[i] / 32768.0;
                    double sample = rawSample - dcBlockerInVech + 0.995 * dcBlockerOutVech;
                    dcBlockerInVech = rawSample;
                    dcBlockerOutVech = sample;

                    double magMark = 0, magSpace = 0;
                    double diferentaBruta = 0.0;

                    if (folosesteMatchedFilter) {
                        mfBuffer[mfBufferPos] = sample;
                        mfBufferPos = (mfBufferPos + 1) % mfSamplesPerBit;

                        double corrMarkSin = 0, corrMarkCos = 0;
                        double corrSpaceSin = 0, corrSpaceCos = 0;
                        int idx = mfBufferPos;
                        for (int k = 0; k < mfSamplesPerBit; k++) {
                            corrMarkSin += mfBuffer[idx] * mfMarkTemplateSin[k];
                            corrMarkCos += mfBuffer[idx] * mfMarkTemplateCos[k];
                            corrSpaceSin += mfBuffer[idx] * mfSpaceTemplateSin[k];
                            corrSpaceCos += mfBuffer[idx] * mfSpaceTemplateCos[k];
                            idx = (idx + 1) % mfSamplesPerBit;
                        }
                        corrMarkSin = (2.0 * corrMarkSin) / mfSamplesPerBit;
                        corrMarkCos = (2.0 * corrMarkCos) / mfSamplesPerBit;
                        corrSpaceSin = (2.0 * corrSpaceSin) / mfSamplesPerBit;
                        corrSpaceCos = (2.0 * corrSpaceCos) / mfSamplesPerBit;

                        magMark = Math.sqrt(corrMarkSin * corrMarkSin + corrMarkCos * corrMarkCos);
                        magSpace = Math.sqrt(corrSpaceSin * corrSpaceSin + corrSpaceCos * corrSpaceCos);
                        diferentaBruta = magMark - magSpace;
                    } else {
                        double m0 = sample - rCheb * rCheb * mx2 + qMarkA * rCheb * mx1;
                        mx2 = mx1; mx1 = m0;
                        magMark = Math.abs(m0);

                        double s0 = sample - rCheb * rCheb * sx2 + qSpaceA * rCheb * sx1;
                        sx2 = sx1; sx1 = s0;
                        magSpace = Math.abs(s0);
                        diferentaBruta = magMark - magSpace;
                    }

                    // AFC/PLL
                    fazaAFC += 2.0 * Math.PI * frecventaTunedBaza / sampleRate;
                    if (fazaAFC > 2.0 * Math.PI) fazaAFC -= 2.0 * Math.PI;

                    I_acc += sample * Math.cos(fazaAFC);
                    Q_acc += sample * Math.sin(fazaAFC);
                    samplesSinceLastAFC++;

                    if (samplesSinceLastAFC >= 960) {
                        double current_phase = Math.atan2(Q_acc, I_acc);
                        double delta_phase = current_phase - last_phase;

                        while (delta_phase > Math.PI) delta_phase -= 2.0 * Math.PI;
                        while (delta_phase < -Math.PI) delta_phase += 2.0 * Math.PI;

                        double freq_error = (delta_phase * sampleRate) / (2.0 * Math.PI * samplesSinceLastAFC);

                        if (magMark > magSpace * 1.2 && magMark > nivelZgomotMediu * 2.0) {
                            if (modUrmarireFrecventa == 1) {
                                frecventaTunedBaza += freq_error * 0.05;
                            } else if (modUrmarireFrecventa == 2) {
                                frecventaTunedBaza += freq_error * 0.25;
                            }

                            if (modUrmarireFrecventa > 0) {
                                qMarkA = 2.0 * Math.cos(2.0 * Math.PI * frecventaTunedBaza / sampleRate);
                                double fSpace = frecventaTunedBaza + (regimReverseSoftware ? -170.0 : 170.0);
                                qSpaceA = 2.0 * Math.cos(2.0 * Math.PI * fSpace / sampleRate);

                                if (folosesteMatchedFilter && Math.abs(freq_error) > 0.5) {
                                    recalculeazaShabloaneMF();
                                }
                            }
                        }
                        I_acc = 0; Q_acc = 0;
                        last_phase = current_phase;
                        samplesSinceLastAFC = 0;
                    }

                    double magTotala = magMark + magSpace;

                    if (magTotala < noiseFloor) {
                        noiseFloor = magTotala;
                    } else {
                        noiseFloor = 0.999 * noiseFloor + 0.001 * magTotala;
                    }

                    nivelZgomotMediu = (0.0005 * magTotala) + (0.9995 * nivelZgomotMediu);

                    double integratCurent = (alfaIntegrator * diferentaBruta) + ((1.0 - alfaIntegrator) * integratVech);
                    integratVech = integratCurent;

                    // SNR LIVE
                    if (i % 4800 == 0) {
                        double signalPeak = Math.max(magMark, magSpace);
                        double snrLinear = signalPeak / Math.max(noiseFloor, 1e-9);
                        double snrDb = 20 * Math.log10(snrLinear);
                        double snrNetezit = Math.max(0, snrDb);
                        snrTextLive = String.format("%.1f dB", snrNetezit);

                        final String snrFinal = snrTextLive;
                        final int freqFinal = (int) frecventaTunedBaza;
                        Activity activity = (Activity) context;
                        activity.runOnUiThread(() -> {
                            if (tvFrecventaCurenta != null) {
                                tvFrecventaCurenta.setText(String.format("MARK: %d Hz | Shift: 170 Hz | SNR: %s",
                                        freqFinal, snrFinal));
                            }
                            if (waterfall != null && modUrmarireFrecventa > 0) {
                                waterfall.setTunedFrequency(freqFinal);
                            }
                        });
                    }

                    boolean isMark;
                    if (Math.abs(integratCurent) < (nivelZgomotMediu * pragSensibilitate)) {
                        isMark = true;
                    } else {
                        isMark = integratCurent > 0;
                    }
                    int bitCurent = isMark ? 1 : 0;

                    // Clock Recovery
                    if (!inFrame) {
                        if (bitCurent == 0) {
                            inFrame = true;
                            fazaCeasAcumulator = 0;
                            bitCounter = 0;
                            currentByte = 0;
                        }
                    } else {
                        fazaCeasAcumulator += 1.0;
                        double punctEstantionareTinta = (bitCounter == 0) ? (samplesPerBit * 1.5) : samplesPerBit;

                        if (fazaCeasAcumulator >= punctEstantionareTinta) {
                            fazaCeasAcumulator -= punctEstantionareTinta;
                            if (bitCounter < 5) {
                                if (bitCurent == 1) currentByte |= (1 << bitCounter);
                                bitCounter++;
                            } else {
                                if (bitCurent == 1) proceseazaCodBaudot(currentByte & 0x1F);
                                inFrame = false;
                            }
                        }
                    }
                }
            }
            try { recorder.stop(); recorder.release(); } catch (Exception e) {}
        }).start();
    }

    private void opresteDecodorRtty() {
        decodareRttyActiva = false;
        btnRttyAudioToggle.setText("START");
        btnRttyAudioToggle.setBackgroundColor(Color.parseColor("#E65100"));
    }

    private void executaAutoTuneMecanic() {
        if (!decodareRttyActiva) {
            Toast.makeText(context, "Pornește recepția (START) înainte de Auto-Tune.", Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(() -> {
            final int NUMAR_CADRE_MEDIATE = 8;
            final long PAUZA_INTRE_CADRE_MS = 35;
            float[] magnitudeMediata = null;
            int cadreAcumulate = 0;

            for (int cadru = 0; cadru < NUMAR_CADRE_MEDIATE; cadru++) {
                short[] snapshot = new short[512];
                synchronized (vectorIstoricPeak) {
                    System.arraycopy(vectorIstoricPeak, 0, snapshot, 0, 512);
                }
                float[] magnitude = SimpleFFT.magnitudeSpectrum(snapshot);
                if (magnitudeMediata == null) magnitudeMediata = new float[magnitude.length];
                for (int i = 0; i < magnitude.length; i++) magnitudeMediata[i] += magnitude[i];
                cadreAcumulate++;
                try { Thread.sleep(PAUZA_INTRE_CADRE_MS); } catch (InterruptedException ignored) {}
            }
            for (int i = 0; i < magnitudeMediata.length; i++) magnitudeMediata[i] /= cadreAcumulate;

            int lowerBin = -1;
            float maxPairScore = -1;
            int shiftBins = 11;

            for (int i = 32; i < 192 && (i + shiftBins) < magnitudeMediata.length; i++) {
                float s1 = magnitudeMediata[i];
                float s2 = magnitudeMediata[i + shiftBins];
                float currentScore = s1 + s2;
                if (currentScore > maxPairScore) {
                    maxPairScore = currentScore;
                    lowerBin = i;
                }
            }

            Activity activity = (Activity) context;
            if (lowerBin != -1 && maxPairScore > 0.002) {
                double freqBinDeJos = lowerBin * (8000.0 / 512.0);
                double freqBinDeSus = (lowerBin + shiftBins) * (8000.0 / 512.0);
                frecventaTunedBaza = regimReverseSoftware ? freqBinDeSus : freqBinDeJos;

                activity.runOnUiThread(() -> {
                    sincronizeazaSetariReceptie();
                    Toast.makeText(context, "Auto-Tune: " + (int) frecventaTunedBaza + " Hz",
                            Toast.LENGTH_SHORT).show();
                });
            } else {
                activity.runOnUiThread(() ->
                        Toast.makeText(context, "Semnal slab. Faceți acordul manual.", Toast.LENGTH_SHORT).show());
            }
        }, "AutoTune-Frecventa").start();
    }

    private void proceseazaCodBaudot(int code) {
        if (code == 0x1F) { currentRttyShift = 'L'; return; }
        if (code == 0x1B) { currentRttyShift = 'F'; return; }
        if (code == 0x04) { currentRttyShift = 'L'; }
        char decoded = (currentRttyShift == 'L') ? ITA2_LTRS_MAP[code] : ITA2_FIGS_MAP[code];
        if (decoded != '\0') {
            Activity activity = (Activity) context;
            activity.runOnUiThread(() -> {
                if (tvRttyTerminal != null) {
                    if (decoded == '\u0007') {
                        // Bell - ignorăm
                    } else {
                        proceseazaCaracterPentruClick(decoded);
                    }
                }
            });
        }
    }

    private void comutaStarePttManual() {
        if (radioManager == null || radioManager.getDataPort() == null) return;
        try {
            isTransmittingRtty = !isTransmittingRtty;
            radioManager.getDataPort().setDTR(isTransmittingRtty);
            btnRttyPttToggle.setBackgroundColor(Color.parseColor(isTransmittingRtty ? "#B71C1C" : "#424242"));
            btnRttyPttToggle.setText(isTransmittingRtty ? "TX" : "PTT");
        } catch (Exception e) {
            Toast.makeText(context, "Err PTT: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void transmiteMesajTextRttyBaudot(final String text) {
        if (radioManager == null || radioManager.getDataPort() == null || !radioManager.getDataPort().isOpen()) {
            Toast.makeText(context, "Porturile DATA nu sunt conectate!", Toast.LENGTH_SHORT).show();
            return;
        }
        Activity activity = (Activity) context;
        activity.runOnUiThread(() -> {
            if (tvRttyTerminal != null) {
                tvRttyTerminal.append(android.text.Html.fromHtml(
                        "<br><font color='#FFFF00' face='monospace'>TX: " + text.trim() + "</font><br>",
                        android.text.Html.FROM_HTML_MODE_LEGACY));
                if (svRttyScroll != null) svRttyScroll.post(() -> svRttyScroll.fullScroll(View.FOCUS_DOWN));
            }
        });

        new Thread(() -> {
            try {
                isTransmittingRtty = true;
                activity.runOnUiThread(() -> {
                    btnRttyPttToggle.setBackgroundColor(Color.parseColor("#B71C1C"));
                    btnRttyPttToggle.setText("TX");
                });
                radioManager.getDataPort().setDTR(true);
                Thread.sleep(250);
                currentTxShift = 'L';

                long dur = 22002200L;
                long next = System.nanoTime();
                next = trimiteOctetBaudotHardware(0x1F, next, dur);
                next = trimiteOctetBaudotHardware(0x1F, next, dur);
                next = trimiteOctetBaudotHardware(0x1F, next, dur);

                for (int i = 0; i < text.length(); i++) {
                    char c = Character.toUpperCase(text.charAt(i));
                    if (c != ' ' && c != '\n' && c != '\r') {
                        boolean cereFigs = CHAR_IS_FIGS[c];
                        if (cereFigs && currentTxShift == 'L') {
                            next = trimiteOctetBaudotHardware(0x1B, next, dur);
                            currentTxShift = 'F';
                        } else if (!cereFigs && currentTxShift == 'F') {
                            next = trimiteOctetBaudotHardware(0x1F, next, dur);
                            currentTxShift = 'L';
                        }
                    } else {
                        if (currentTxShift == 'F') currentTxShift = 'L';
                    }
                    int codBaudot = CHAR_TO_BAUDOT_CODE[c];
                    next = trimiteOctetBaudotHardware(codBaudot, next, dur);
                }
                Thread.sleep(150);
                isTransmittingRtty = false;
                activity.runOnUiThread(() -> {
                    btnRttyPttToggle.setBackgroundColor(Color.parseColor("#424242"));
                    btnRttyPttToggle.setText("PTT");
                });
                radioManager.getDataPort().setDTR(false);
            } catch (Exception e) {
                android.util.Log.e("RTTY_TX", "Eroare loop FSK", e);
            }
        }).start();
    }

    private long trimiteOctetBaudotHardware(int codBaudot, long n, long d) throws Exception {
        boolean reverse = regimReverseSoftware;
        radioManager.getDataPort().setRTS(reverse);
        n += d;
        while (System.nanoTime() < n);

        for (int b = 0; b < 5; b++) {
            boolean bitVal = ((codBaudot >> b) & 1) == 1;
            radioManager.getDataPort().setRTS(bitVal ? !reverse : reverse);
            n += d;
            while (System.nanoTime() < n);
        }

        radioManager.getDataPort().setRTS(!reverse);
        n += (long) (d * 1.5);
        while (System.nanoTime() < n);
        return n;
    }

    private void executaMacroRttyPeCanal(int indexCanal) {
        SharedPreferences prefs = context.getSharedPreferences("RTTY_MACROS", Context.MODE_PRIVATE);
        String textMacro = prefs.getString("rtty_content_" + indexCanal, "");
        if (!textMacro.isEmpty()) {
            transmiteMesajTextRttyBaudot(textMacro);
        } else {
            String[] implicite = {
                    "CQ CQ CQ DE YO9VA YO9VA K\n",
                    "UR RST 599 599 = BTU DE YO9VA K\n",
                    "73 GL SK DE YO9VA SK\n",
                    "NAME IS EUGENE QTH ALEXANDRIA = BTU DE YO9VA K\n",
                    "MY RIG IS YAESU FTX1 FIELD = BTU DE YO9VA K\n",
                    "WEATHER HERE IS SUNNY TEMP 25C K\n",
                    "PSE QSL ON LOTW K\n",
                    "TNX FOR QSO BI BI DE YO9VA SK\n"
            };
            if (indexCanal >= 0 && indexCanal < implicite.length) {
                transmiteMesajTextRttyBaudot(implicite[indexCanal]);
            }
        }
    }
}