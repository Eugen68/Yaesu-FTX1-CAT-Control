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
    public static String callsignBuffer = "";

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

    // Lista macro-uri stil terminal
    private LinearLayout llMacroListContainer;

    // REFACTORIZARE: detectarea/afisarea indicativelor a fost extrasa in CallsignDetector.
    // Instantiat in constructor, dupa ce construiesteInterfata() a creat tvRttyTerminal/
    // etRttyInputLive/svRttyScroll (de care are nevoie).
    private CallsignDetector callsignDetector;

    public void setOnCallsignTappedListener(CallsignDetector.OnCallsignTappedListener listener) {
        callsignDetector.setOnCallsignTappedListener(listener);
    }

    // Stari receptie
    private boolean decodareRttyActiva = false;
    private boolean regimReverseSoftware = false;
    private short[] miniBufferFft = new short[512];

    // Coeficienti
    private double frecventaTunedBaza = 2125.0;
    private double qMarkA, qSpaceA;
    private double sampleRateFiltre = 48000.0;
    private double rCheb = 0.992;
    private volatile double pragSensibilitate = 0.05;
    private int modUrmarireFrecventa = 0;

    // OPTIMIZARE: constanta de clasa (nu doar variabila locala in threadul de decodare),
    // ca sa poata fi folosita si de slider-ul "Latime Filtru" pentru a lega podeaua
    // minima de banda de rata reala de simbol (evitarea ISI la benzi prea inguste).
    private static final double BAUD_RTTY = 45.45;
    private static final double RCHEB_MIN = 0.985; // capatul larg al slider-ului (~115 Hz)

    // Matched Filter & SNR
    // OPTIMIZARE: nu mai pastram sabloanele sin/cos pe toata fereastra (O(N) per esantion),
    // ci doar constantele de rotatie pentru o corelatie glisanta recursiva O(1) per esantion
    // (echivalent matematic cu corelatia completa pe fereastra, actualizat incremental).
    private volatile boolean folosesteMatchedFilter = false;
    private double[] mfBuffer; // buffer circular cu ultimele mfSamplesPerBit esantioane (pt. x_iesit)
    private int mfBufferPos = 0;
    private int mfSamplesPerBit = 1056;

    // psi = e^{-j*w} (rotatia aplicata la fiecare pas nou)
    private double mfMarkRotRe, mfMarkRotIm;
    private double mfSpaceRotRe, mfSpaceRotIm;
    // PsiN = e^{+j*w*N} (constanta legata de lungimea ferestrei N = mfSamplesPerBit)
    private double mfMarkPowNRe, mfMarkPowNIm;
    private double mfSpacePowNRe, mfSpacePowNIm;
    // Acumulatorul complex curent al corelatiei glisante (echivalentul sumei S(n))
    private double mfMarkAccRe, mfMarkAccIm;
    private double mfSpaceAccRe, mfSpaceAccIm;
    private String snrTextLive = "--.- dB";
    private double noiseFloor = 1e-9;

    private final short[] vectorIstoricPeak = new short[512];
    private volatile boolean solicitaResetFiltre = false;

    // Emisie
    private boolean isTransmittingRtty = false;

    // REFACTORIZARE: tabelele ITA2 si logica de shift LTRS/FIGS au fost extrase in
    // BaudotCodec (clasa noua, fara dependente Android, testabila independent).
    // RX si TX au fiecare propria stare de shift, deci fiecare propria instanta.
    private final BaudotCodec rxBaudotCodec = new BaudotCodec();
    private final BaudotCodec txBaudotCodec = new BaudotCodec();

    public EcranRttyDroid(Context context, RadioManager radioManager) {
        this.context = context;
        this.radioManager = radioManager;
        construiesteInterfata();
        callsignDetector = new CallsignDetector(context, tvRttyTerminal, etRttyInputLive, svRttyScroll);
    }

    private void initMatchedFilterTemplates(int sampleRate, double baud) {
        mfSamplesPerBit = (int) Math.round(sampleRate / baud);
        mfBuffer = new double[mfSamplesPerBit];
        mfBufferPos = 0;
        mfMarkAccRe = mfMarkAccIm = 0;
        mfSpaceAccRe = mfSpaceAccIm = 0;

        double fMark = frecventaTunedBaza;
        double fSpace = frecventaTunedBaza + (regimReverseSoftware ? -170.0 : 170.0);
        setareaConstantelorMF(fMark, fSpace, sampleRate);
    }

    /**
     * OPTIMIZARE: in loc sa recalculam N valori sin/cos (O(N)) de fiecare data cand se
     * schimba frecventa (ex: din AFC, la fiecare fractiune de secunda), recalculam doar
     * cateva constante de rotatie (O(1)). Acumulatorul corelatiei glisante (mfMarkAccRe/Im,
     * mfSpaceAccRe/Im) NU se reseteaza aici - continua sa evolueze lin sub noua rotatie,
     * la fel cum bufferul audio (mfBuffer) nu e resetat la o simpla derivare de frecventa
     * din AFC (se reseteaza doar explicit, la solicitaResetFiltre).
     */
    /**
     * OPTIMIZARE 1 (Varianta A): slider-ul "Latime Filtru" controleaza rCheb, folosit
     * doar de rezonatorul Chebyshev. In modul Matched Filter, rCheb nu are niciun efect
     * asupra decodarii - il dezactivam vizual, ca sa nu para ca ajusteaza ceva ce de
     * fapt nu face nimic in acel mod.
     */
    private void actualizeazaStareSliderLatime() {
        if (sbLatimeFiltru == null || tvFiltruValoare == null) return;
        boolean activ = !folosesteMatchedFilter;
        sbLatimeFiltru.setEnabled(activ);
        sbLatimeFiltru.setAlpha(activ ? 1.0f : 0.4f);
        if (activ) {
            int bwHz = (int) Math.round((1.0 - rCheb) * sampleRateFiltre / (2 * Math.PI));
            tvFiltruValoare.setText("~" + bwHz + " Hz");
        } else {
            tvFiltruValoare.setText("N/A (Matched Filter)");
        }
    }

    private void recalculeazaShabloaneMF() {
        if (mfBuffer != null) {
            double fMark = frecventaTunedBaza;
            double fSpace = frecventaTunedBaza + (regimReverseSoftware ? -170.0 : 170.0);
            setareaConstantelorMF(fMark, fSpace, sampleRateFiltre);
        }
    }

    private void setareaConstantelorMF(double fMark, double fSpace, double sampleRate) {
        double wMark = 2.0 * Math.PI * fMark / sampleRate;
        double wSpace = 2.0 * Math.PI * fSpace / sampleRate;

        // psi = e^{-j*w}  (rotatia per esantion)
        mfMarkRotRe = Math.cos(-wMark);
        mfMarkRotIm = Math.sin(-wMark);
        mfSpaceRotRe = Math.cos(-wSpace);
        mfSpaceRotIm = Math.sin(-wSpace);

        // PsiN = e^{+j*w*N}  (corectia legata de esantionul care iese din fereastra)
        double wMarkN = wMark * mfSamplesPerBit;
        double wSpaceN = wSpace * mfSamplesPerBit;
        mfMarkPowNRe = Math.cos(wMarkN);
        mfMarkPowNIm = Math.sin(wMarkN);
        mfSpacePowNRe = Math.cos(wSpaceN);
        mfSpacePowNIm = Math.sin(wSpaceN);
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
        actualizeazaListaMacro();
        return viewPrincipal;
    }

    // ============================================================
    // Construire lista macro-uri stil terminal
    // ============================================================
    private void actualizeazaListaMacro() {
        if (llMacroListContainer == null) return;
        llMacroListContainer.removeAllViews();

        SharedPreferences prefs = context.getSharedPreferences("RTTY_MACROS", Context.MODE_PRIVATE);

        String[] implicite = {
                "CQ CQ CQ DE YO9VA YO9VA K",
                "UR RST 599 599 = BTU DE YO9VA K",
                "73 GL SK DE YO9VA SK",
                "NAME IS EUGENE QTH ALEXANDRIA = BTU DE YO9VA K",
                "MY RIG IS YAESU FTX1 FIELD = BTU DE YO9VA K",
                "WEATHER HERE IS SUNNY TEMP 25C K",
                "PSE QSL ON LOTW K",
                "TNX FOR QSO BI BI DE YO9VA SK"
        };

        for (int i = 0; i < 8; i++) {
            String continut = prefs.getString("rtty_content_" + i, "");
            if (continut.isEmpty() && i < implicite.length) {
                continut = implicite[i];
            }

            if (!continut.isEmpty()) {
                final String textFinal = continut.replace("\n", " ").trim();

                TextView tvMacro = new TextView(context);
                tvMacro.setText(textFinal);
                tvMacro.setTextColor(Color.parseColor("#00FF00"));
                tvMacro.setTextSize(13);
                tvMacro.setTypeface(Typeface.MONOSPACE);
                tvMacro.setPadding(10, 8, 10, 8);
                tvMacro.setBackgroundColor(Color.TRANSPARENT);

                tvMacro.setOnClickListener(v -> {
                    if (etRttyInputLive != null) {
                        etRttyInputLive.setText(textFinal);
                        etRttyInputLive.setSelection(etRttyInputLive.getText().length());
                    }
                    tvMacro.setBackgroundColor(Color.parseColor("#33FFFFFF"));
                    tvMacro.postDelayed(() -> tvMacro.setBackgroundColor(Color.TRANSPARENT), 150);
                });

                LinearLayout.LayoutParams pTv = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                tvMacro.setLayoutParams(pTv);
                llMacroListContainer.addView(tvMacro);
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

        // TERMINAL RX
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

        // ============================================================
        // CRITIC: Activează răspunsul la click pentru ClickableSpan
        // ============================================================
        tvRttyTerminal.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        tvRttyTerminal.setHighlightColor(Color.TRANSPARENT); // Elimină fundalul gri la apăsare

        svRttyScroll.addView(tvRttyTerminal);
        llRttyMain.addView(svRttyScroll);

        // RAND 1: START | CLEAR | NORM/REV | A-TUNE
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
            callsignDetector.resetBuffer();
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

        // RAND 2: TOGGLE DECODER & TRACK
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
            if (folosesteMatchedFilter && mfBuffer == null) {
                initMatchedFilterTemplates(48000, BAUD_RTTY);
            }
            recalculeazaShabloaneMF();
            solicitaResetFiltre = true;
            actualizeazaStareSliderLatime();
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

        // SLIDER LATIME FILTRU
        LinearLayout rAndFiltru = new LinearLayout(context);
        rAndFiltru.setOrientation(LinearLayout.HORIZONTAL);
        rAndFiltru.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams pRandFiltru = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pRandFiltru.setMargins(0, 0, 0, 15);
        rAndFiltru.setLayoutParams(pRandFiltru);

        TextView tvFiltruLabel = new TextView(context);
        tvFiltruLabel.setText("Latime Filtru:");
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
                // OPTIMIZARE 2: podeaua ingusta a slider-ului nu mai e fixa (~15 Hz), ci
                // legata dinamic de rata reala de baud - sub ~1.2x rata de baud, filtrul
                // devine mai lent decat ritmul simbolurilor si introduce ISI (distorsiune
                // intre biti) in loc sa "curete" semnalul.
                double bwMinHz = Math.max(20.0, BAUD_RTTY * 1.2);
                double rChebMax = 1.0 - (bwMinHz * 2.0 * Math.PI / sampleRateFiltre);
                rCheb = RCHEB_MIN + progress * ((rChebMax - RCHEB_MIN) / 20.0);

                int bwHz = (int) Math.round((1.0 - rCheb) * sampleRateFiltre / (2 * Math.PI));
                tvFiltruValoare.setText("~" + bwHz + " Hz");
                // OPTIMIZARE 3: NU mai resetam filtrele la fiecare tick de drag (doar
                // actualizam rCheb si afisajul) - resetul complet se intampla o singura
                // data, la eliberarea degetului (onStopTrackingTouch), ca sa nu sacadam
                // decodarea live cat utilizatorul cauta valoarea potrivita.
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                solicitaResetFiltre = true;
            }
        });
        llRttyMain.addView(rAndFiltru);

        // OPTIMIZARE 1 (Varianta A): slider-ul "Latime Filtru" controleaza doar rCheb,
        // folosit exclusiv de decodorul Chebyshev - matched filter-ul isi are propria
        // "latime" fixa, determinata de durata ferestrei legata de rata de baud, nu de
        // acest control. Il dezactivam vizual in modul Matched Filter, ca sa nu para ca
        // ajusteaza ceva ce de fapt nu are niciun efect in acel mod.
        actualizeazaStareSliderLatime();

        // CONTAINER PENTRU CAMPUL FSK + BUTON CLEAR (X)
        LinearLayout rFskContainer = new LinearLayout(context);
        rFskContainer.setOrientation(LinearLayout.HORIZONTAL);
        rFskContainer.setGravity(Gravity.TOP); // Aliniem sus pentru a arata bine cand textul are mai multe randuri
        LinearLayout.LayoutParams pFskContainer = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pFskContainer.setMargins(0, 5, 0, 10);
        rFskContainer.setLayoutParams(pFskContainer);

        // CAMP EDITARE FSK (multi-line cu wrap + buton X nativ)
        etRttyInputLive = new EditText(context);
        etRttyInputLive.setHint("Type live FSK text...");
        etRttyInputLive.setHintTextColor(Color.GRAY);
        etRttyInputLive.setTextColor(Color.WHITE);
        etRttyInputLive.setTextSize(14);
        etRttyInputLive.setSingleLine(false);
        etRttyInputLive.setMinLines(2);
        etRttyInputLive.setMaxLines(4);
        etRttyInputLive.setGravity(Gravity.TOP | Gravity.START);
        //etRttyInputLive.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        // Vechiul cod (doar o linie):
        // NOUL COD (înlocuiește cu acesta):
        etRttyInputLive.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE |
                android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);

        // Filtrul AllCaps forțează majuscule chiar și la textul lipit (Paste) din clipboard
        etRttyInputLive.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.AllCaps()});
        // ============================================================
        // BUTON X NATIV (apare doar când există text)
        // ============================================================
        // Iconița X (folosim un caracter Unicode simplu, dar poți înlocui cu o iconiță drawable)
        final android.graphics.drawable.Drawable clearIcon = new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT);
        etRttyInputLive.setCompoundDrawablesWithIntrinsicBounds(null, null, clearIcon, null);
        etRttyInputLive.setCompoundDrawablePadding(10);

        // TextWatcher pentru a afișa/ascunde iconița X
        etRttyInputLive.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(android.text.Editable s) {
                if (s.length() > 0) {
                    // Arătăm iconița X (roșu aprins)
                    android.graphics.drawable.Drawable xIcon = createXIcon();
                    etRttyInputLive.setCompoundDrawablesWithIntrinsicBounds(null, null, xIcon, null);
                } else {
                    // Ascundem iconița X
                    etRttyInputLive.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
                }
            }
        });

        // Click pe iconița X pentru a goli câmpul
        etRttyInputLive.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                if (event.getRawX() >= (etRttyInputLive.getRight() - etRttyInputLive.getCompoundPaddingRight())) {
                    etRttyInputLive.setText("");
                    return true;
                }
            }
            return false;
        });

        LinearLayout.LayoutParams pEtInput = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pEtInput.setMargins(0, 5, 0, 10);
        etRttyInputLive.setLayoutParams(pEtInput);
        llRttyMain.addView(etRttyInputLive);

        // Auto-scroll la campul de editare cand primeste focus
        etRttyInputLive.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && svRttyScroll != null) {
                svRttyScroll.postDelayed(() -> {
                    svRttyScroll.smoothScrollTo(0, etRttyInputLive.getTop());
                }, 200);
            }
        });

        // BUTOANE SEND SI PTT (aliniate la dreapta)
        LinearLayout rAndSendPtt = new LinearLayout(context);
        rAndSendPtt.setOrientation(LinearLayout.HORIZONTAL);
        rAndSendPtt.setGravity(Gravity.END);
        LinearLayout.LayoutParams pRandSend = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pRandSend.setMargins(0, 0, 0, 15);
        rAndSendPtt.setLayoutParams(pRandSend);

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
        rAndSendPtt.addView(btnRttySendLive);

        btnRttyPttToggle = new Button(context);
        btnRttyPttToggle.setText("PTT");
        btnRttyPttToggle.setBackgroundColor(Color.parseColor("#424242"));
        btnRttyPttToggle.setTextColor(Color.WHITE);
        btnRttyPttToggle.setLayoutParams(new LinearLayout.LayoutParams(0, 130, 0.9f));
        btnRttyPttToggle.setOnClickListener(v -> comutaStarePttManual());
        rAndSendPtt.addView(btnRttyPttToggle);
        llRttyMain.addView(rAndSendPtt);

        // FEREASTRA LISTA MACRO-URI (stil terminal)
        ScrollView svMacroList = new ScrollView(context);
        LinearLayout.LayoutParams pSvMacro = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 250);
        pSvMacro.setMargins(0, 0, 0, 10);
        svMacroList.setLayoutParams(pSvMacro);
        svMacroList.setBackgroundColor(Color.BLACK);
        svMacroList.setPadding(10, 10, 10, 10);

        llMacroListContainer = new LinearLayout(context);
        llMacroListContainer.setOrientation(LinearLayout.VERTICAL);
        svMacroList.addView(llMacroListContainer);
        llRttyMain.addView(svMacroList);

        viewPrincipal.addView(llRttyMain);
    }

    // REFACTORIZARE: logica de detectare/afisare a indicativelor a fost mutata in
    // CallsignDetector.java (vezi campul callsignDetector si constructorul).

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
            double baudRTTY = BAUD_RTTY;
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

            // OPTIMIZARE: debounce la declansarea cadrului (start-bit). Inainte, un SINGUR
            // esantion decis "space" (posibil doar zgomot, pe semnal slab) declansa imediat
            // un cadru nou, la o faza arbitrara fata de bitstream-ul real - sursa principala
            // a simptomului "litere corecte izolate, text neinteligibil per total" (cadre
            // false + cadre reale defazate, ambele esuand testul stop-bitului de la final).
            // Acum cerem ca starea "space" sa persiste un numar minim de esantioane inainte
            // sa ne angajam pe un cadru nou.
            int contorConfirmareStart = 0;
            final int PRAG_CONFIRMARE_START = (int) Math.round(samplesPerBit * 0.7);

            double fazaAFC = 0;
            double I_acc = 0, Q_acc = 0;
            double last_phase = 0;
            int samplesSinceLastAFC = 0;
            // OPTIMIZARE/CORECTIE: fereastra de acumulare AFC trebuie sa fie un multiplu
            // intreg al perioadei unui simbol (samplesPerBit), altfel punctul de esantionare
            // a fazei "aluneca" fata de granitele reale ale bitilor RTTY, ceea ce introduce
            // eroare suplimentara in estimarea de frecventa (mai ales pe semnal slab).
            // Vechea valoare fixa (960 esantioane = 20ms) nu se alinia cu perioada reala
            // de simbol (~1056 esantioane = 22ms la 45.45 baud).
            // OPTIMIZARE: am marit de la 1 la 2 perioade de simbol intre corectii - mai
            // putina reactivitate, dar discriminatorul de frecventa mediaza zgomotul pe o
            // fereastra mai mare, deci variaza mult mai putin pe semnal slab/QRM.
            final int ferestreAFC = 2; // cate perioade de simbol acumulam intre 2 corectii AFC
            final int fereastraAFCSamples = Math.max(1, (int) Math.round(samplesPerBit)) * ferestreAFC;

            // OPTIMIZARE: bucla AFC veche era proportionala pura (fara filtru de bucla) -
            // orice zgomot din masuratoarea de eroare de frecventa se transmitea direct si
            // integral in frecventaTunedBaza. Adaugam:
            //  - freqErrorFiltrat: o medie exponentiala (EMA) care neteze masuratorile brute
            //    intre ferestre succesive, in loc sa lase fiecare masuratoare bruta sa miste
            //    direct frecventa urmarita (echivalent unui filtru de bucla simplu);
            //  - AFC_MAX_CORECTIE_HZ: plafon pe marimea corectiei aplicate per fereastra -
            //    un semnal RTTY normal nu deriva brusc cu zeci de Hz intre doua ferestre
            //    consecutive (~44ms), deci limitam corectia la o valoare fizic plauzibila;
            //  - AFC_PRAG_OUTLIER_HZ: daca masuratoarea bruta e nefiziologic de mare (fereastra
            //    "murdara" - de ex. o tranzitie mark/space cade in mijlocul ei - sau zgomot),
            //    o ignoram complet in loc s-o aplicam (fie si trunchiata).
            double freqErrorFiltrat = 0;
            final double AFC_EMA_ALPHA = 0.25;
            final double AFC_MAX_CORECTIE_HZ = 5.0;
            final double AFC_PRAG_OUTLIER_HZ = 20.0;


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
                    contorConfirmareStart = 0;
                    if (folosesteMatchedFilter && mfBuffer != null) {
                        for (int k = 0; k < mfBuffer.length; k++) mfBuffer[k] = 0;
                        mfBufferPos = 0;
                        mfMarkAccRe = mfMarkAccIm = 0;
                        mfSpaceAccRe = mfSpaceAccIm = 0;
                    }
                    solicitaResetFiltre = false;
                }

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
                        // OPTIMIZARE (era O(N) per esantion, ~1056 x4 inmultiri -> acum O(1)):
                        // corelatie glisanta recursiva, matematic echivalenta cu suma completa
                        // pe fereastra de mfSamplesPerBit esantioane, actualizata incremental:
                        //   S(n+1) = psi * ( S(n) - x_iesit + x_nou * PsiN )
                        double xIesit = mfBuffer[mfBufferPos];
                        mfBuffer[mfBufferPos] = sample;
                        mfBufferPos = (mfBufferPos + 1) % mfSamplesPerBit;

                        double markInRe = mfMarkAccRe - xIesit + sample * mfMarkPowNRe;
                        double markInIm = mfMarkAccIm + sample * mfMarkPowNIm;
                        mfMarkAccRe = mfMarkRotRe * markInRe - mfMarkRotIm * markInIm;
                        mfMarkAccIm = mfMarkRotRe * markInIm + mfMarkRotIm * markInRe;

                        double spaceInRe = mfSpaceAccRe - xIesit + sample * mfSpacePowNRe;
                        double spaceInIm = mfSpaceAccIm + sample * mfSpacePowNIm;
                        mfSpaceAccRe = mfSpaceRotRe * spaceInRe - mfSpaceRotIm * spaceInIm;
                        mfSpaceAccIm = mfSpaceRotRe * spaceInIm + mfSpaceRotIm * spaceInRe;

                        double scalareMF = 2.0 / mfSamplesPerBit;
                        magMark = Math.sqrt(mfMarkAccRe * mfMarkAccRe + mfMarkAccIm * mfMarkAccIm) * scalareMF;
                        magSpace = Math.sqrt(mfSpaceAccRe * mfSpaceAccRe + mfSpaceAccIm * mfSpaceAccIm) * scalareMF;
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

                    if (samplesSinceLastAFC >= fereastraAFCSamples) {
                        double current_phase = Math.atan2(Q_acc, I_acc);
                        double delta_phase = current_phase - last_phase;

                        while (delta_phase > Math.PI) delta_phase -= 2.0 * Math.PI;
                        while (delta_phase < -Math.PI) delta_phase += 2.0 * Math.PI;

                        double freq_error = (delta_phase * sampleRate) / (2.0 * Math.PI * samplesSinceLastAFC);

                        if (magMark > magSpace * 1.2 && magMark > nivelZgomotMediu * 2.0) {
                            // OPTIMIZARE 3: respingere outlieri - o masuratoare nefiziologic
                            // de mare e ignorata complet (nu doar trunchiata), tratata ca
                            // fereastra "murdara"/zgomot, nu ca informatie partiala valida.
                            if (Math.abs(freq_error) <= AFC_PRAG_OUTLIER_HZ) {

                                // OPTIMIZARE 1: filtru de bucla (EMA) - amortizeaza zgomotul
                                // dintre ferestre succesive inainte de a aplica corectia.
                                freqErrorFiltrat = freqErrorFiltrat * (1.0 - AFC_EMA_ALPHA) + freq_error * AFC_EMA_ALPHA;

                                // OPTIMIZARE 2: plafon pe marimea corectiei aplicate per fereastra.
                                double freqErrorLimitat = Math.max(-AFC_MAX_CORECTIE_HZ,
                                        Math.min(AFC_MAX_CORECTIE_HZ, freqErrorFiltrat));

                                if (modUrmarireFrecventa == 1) {
                                    frecventaTunedBaza += freqErrorLimitat * 0.05;
                                } else if (modUrmarireFrecventa == 2) {
                                    frecventaTunedBaza += freqErrorLimitat * 0.25;
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
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        activity.runOnUiThread(() -> {
                            if (activity.isFinishing() || activity.isDestroyed()) return;
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
                            contorConfirmareStart++;
                            if (contorConfirmareStart >= PRAG_CONFIRMARE_START) {
                                inFrame = true;
                                // CORECTIE: nu pornim faza de la 0, ci de la cate esantioane
                                // au trecut deja de la INCEPUTUL real al starii "space" (cat
                                // am asteptat sa confirmam) - altfel am introduce o intarziere
                                // sistematica noua la fiecare bit, in loc sa reparam sincronizarea.
                                fazaCeasAcumulator = contorConfirmareStart;
                                bitCounter = 0;
                                currentByte = 0;
                                contorConfirmareStart = 0;
                            }
                        } else {
                            contorConfirmareStart = 0; // revenire la mark inainte de prag = fals pozitiv
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
            try {
                if (recorder.getState() == AudioRecord.STATE_INITIALIZED) {
                    recorder.stop();
                }
                recorder.release();
            } catch (Exception e) {
                android.util.Log.e("RTTY_DECODER", "Eroare oprire AudioRecord", e);
            }
        }).start();
    }

    private void opresteDecodorRtty() {
        decodareRttyActiva = false;
        btnRttyAudioToggle.setText("START");
        btnRttyAudioToggle.setBackgroundColor(Color.parseColor("#E65100"));
    }

    private void executaAutoTuneMecanic() {
        if (!decodareRttyActiva) {
            Toast.makeText(context, "Porneste receptia (START) inainte de Auto-Tune.", Toast.LENGTH_SHORT).show();
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

                if (activity.isFinishing() || activity.isDestroyed()) return;
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    sincronizeazaSetariReceptie();
                    Toast.makeText(context, "Auto-Tune: " + (int) frecventaTunedBaza + " Hz",
                            Toast.LENGTH_SHORT).show();
                });
            } else {
                activity.runOnUiThread(() ->
                        Toast.makeText(context, "Semnal slab. Faceti acordul manual.", Toast.LENGTH_SHORT).show());
            }
        }, "AutoTune-Frecventa").start();
    }

    private void proceseazaCodBaudot(int code) {
        char decoded = rxBaudotCodec.decode(code);
        if (decoded != '\0') {
            Activity activity = (Activity) context;
            if (activity.isFinishing() || activity.isDestroyed()) return;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (tvRttyTerminal != null) {
                    if (decoded == '\u0007') {
                        // Bell - ignoram
                    } else {
                        callsignDetector.proceseazaCaracterPentruClick(decoded);
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
        if (activity.isFinishing() || activity.isDestroyed()) return;
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
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
                if (activity.isFinishing() || activity.isDestroyed()) return;
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    btnRttyPttToggle.setBackgroundColor(Color.parseColor("#B71C1C"));
                    btnRttyPttToggle.setText("TX");
                });
                radioManager.getDataPort().setDTR(true);
                Thread.sleep(250);
                txBaudotCodec.resetShift();

                long dur = 22002200L;
                long next = System.nanoTime();
                next = trimiteOctetBaudotHardware(0x1F, next, dur);
                next = trimiteOctetBaudotHardware(0x1F, next, dur);
                next = trimiteOctetBaudotHardware(0x1F, next, dur);

                for (int i = 0; i < text.length(); i++) {
                    char c = Character.toUpperCase(text.charAt(i));
                    for (int codBaudot : txBaudotCodec.encode(c)) {
                        next = trimiteOctetBaudotHardware(codBaudot, next, dur);
                    }
                }
                Thread.sleep(150);
                isTransmittingRtty = false;
                if (activity.isFinishing() || activity.isDestroyed()) return;
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
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

    // ============================================================
    // CURATARE RESURSE LA INCHIDEREA APLICATIEI
    // Apelata din MainActivity.onDestroy()
    // ============================================================
    public void eliberareResurse() {
        // 1. Oprim decodarea (flag-ul opreste bucla while din thread)
        decodareRttyActiva = false;

        // 2. Oprim transmisia FSK daca e activa
        isTransmittingRtty = false;
        if (radioManager != null && radioManager.getDataPort() != null) {
            try {
                radioManager.getDataPort().setDTR(false);
                radioManager.getDataPort().setRTS(false);
            } catch (Exception ignored) {}
        }

        // 3. Reciclam bitmap-urile Waterfall
        if (waterfall != null) {
            waterfall.eliberareBitmap();
        }

        // 4. Golim buffer-ul de cuvinte
        callsignDetector.resetBuffer();
    }

    // ============================================================
    // Creează iconița X dinamic (fără resurse externe)
    // ============================================================
    private android.graphics.drawable.Drawable createXIcon() {
        int size = 64; // dimensiune în pixeli
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);

        // Fundal roșu circular
        android.graphics.Paint bgPaint = new android.graphics.Paint();
        bgPaint.setColor(Color.parseColor("#B71C1C"));
        bgPaint.setAntiAlias(true);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bgPaint);

        // X alb în centru
        android.graphics.Paint xPaint = new android.graphics.Paint();
        xPaint.setColor(Color.WHITE);
        xPaint.setStrokeWidth(6f);
        xPaint.setAntiAlias(true);
        xPaint.setStrokeCap(android.graphics.Paint.Cap.ROUND);

        float margin = size * 0.3f;
        canvas.drawLine(margin, margin, size - margin, size - margin, xPaint);
        canvas.drawLine(size - margin, margin, margin, size - margin, xPaint);

        return new android.graphics.drawable.BitmapDrawable(context.getResources(), bitmap);
    }
}