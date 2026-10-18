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
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
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
    private LinearLayout viewPrincipal;
    public static String callsignBuffer = "";

    /**
     * Inlocuieste placeholder-ul {call} (orice combinatie de majuscule/minuscule) dintr-un
     * text de macro cu ultimul indicativ memorat (tap pe indicativ decodat RTTY/CW sau spot
     * din DX Cluster). Daca nu exista niciun indicativ memorat, placeholder-ul ramane
     * neschimbat in text, ca sa fie evident ca substitutia nu s-a facut.
     */
    public static String inlocuiestePlaceholderIndicativ(String text) {
        if (text == null || text.isEmpty() || callsignBuffer == null || callsignBuffer.isEmpty()) {
            return text;
        }
        return text.replaceAll("(?i)\\{call\\}",
                java.util.regex.Matcher.quoteReplacement(callsignBuffer.trim().toUpperCase()));
    }

    // Elemente UI
    private Waterfall waterfall;
    // CIZELARE: constanta unica pentru factorul de subsampling audio -> waterfall
    // (48000 Hz / 6 = 8000 Hz feed catre FFT-ul waterfall-ului). Inainte, valoarea "6"
    // era hardcodata separat aici SI ca "SAMPLE_RATE = 8000" in Waterfall.java, fara
    // nicio legatura explicita intre ele - o modificare facuta doar intr-un loc ar fi
    // dezaliniat tacit axa de frecventa afisata fata de datele reale. Acum se calculeaza
    // rata efectiva din aceasta constanta si se transmite explicit la constructia
    // Waterfall-ului (vezi construiesteInterfata()).
    private static final int WATERFALL_FACTOR_DECIMARE = 6;
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
    private short[] miniBufferFft = new short[512];

    // REFACTORIZARE: tot lantul DSP de receptie (filtre, AFC, clock recovery, framing) si
    // configurarea lui sunt in RttyDemodulator (fara dependente Android, testabil cu semnal
    // sintetic). Ecranul pastreaza doar captura audio, waterfall-ul si afisarea.
    private final RttyDemodulator demod = new RttyDemodulator();

    // Noise Blanker (NB) - vezi RttyDemodulator
    private Button btnNoiseBlanker;
    private LinearLayout rAndSliderNoiseBlanker;
    private SeekBar sbNoiseBlanker;
    private TextView tvNoiseBlankerValoare;

    // DNR (Digital Noise Reduction) - filtru bandpass biquad, vezi RttyDemodulator
    private Button btnDnr;
    private LinearLayout rAndSliderDnr;
    private SeekBar sbDnrLatime;
    private TextView tvDnrValoare;
    // Garda anti-recursivitate: setProgress() apelat programatic (din interiorul
    // clamp-ului de mai jos) re-declanseaza onProgressChanged - flag-ul opreste
    // procesarea dubla a aceluiasi eveniment.
    private boolean ajustareProgramaticaDnr = false;

    // CIZELARE: Matched Filter-ul are nevoie de cel putin 2-3 armonici ale semnalului
    // dreptunghiular RTTY (fundamentala 45.45 Hz) ca sa functioneze optim, adica o banda
    // de cel putin ~225 Hz fata de centru. Sub acest prag, marginile filtrului biquad DNR
    // (cu distorsiunea lui de faza/grup-delay specifica IIR) incep sa taie chiar din
    // energia utila de la Mark/Space, distorsionand pulsurile RTTY (ISI) inainte ca MF
    // sa aiba pe ce corela. Fortam un prag de siguranta putin mai generos (400 Hz) cat
    // timp decodorul Matched Filter e activ.
    private static final double DNR_LATIME_MIN_HZ_MATCHED_FILTER = 400.0;

    // SNR Gating - îngheață decizia de bit la energie insuficientă, vezi RttyDemodulator
    private Button btnSnrGate;
    private LinearLayout rAndSliderSnrGate;
    private SeekBar sbSnrGateK;
    private TextView tvSnrGateValoare;

    // OPTIMIZARE: constanta de clasa (nu doar variabila locala in threadul de decodare),
    // ca sa poata fi folosita si de slider-ul "Latime Filtru" pentru a lega podeaua
    // minima de banda de rata reala de simbol (evitarea ISI la benzi prea inguste).
    private static final double BAUD_RTTY = RttyDemodulator.BAUD_RTTY;
    private static final long BIT_DURATION_NANOS = (long) ((1.0 / BAUD_RTTY) * 1_000_000_000L); // ~22.002.200 ns
    private static final double RCHEB_MIN = 0.985; // capatul larg al slider-ului (~115 Hz)

    private volatile String snrTextLive = "--.- dB";

    private final short[] vectorIstoricPeak = new short[512];

    // Chei SharedPreferences pentru RTTY
    private static final String PREF_RTTY_DNR_ON = "rtty_dnr_on";
    private static final String PREF_RTTY_DNR_BW = "rtty_dnr_bw";
    private static final String PREF_RTTY_SNRG_ON = "rtty_snrg_on";
    private static final String PREF_RTTY_SNRG_K = "rtty_snrg_k";
    private static final String PREF_RTTY_USE_RTS_PTT = "rtty_use_rts_ptt";

    private boolean isInitialLoading = false;

    // Emisie
    private boolean isTransmittingRtty = false;
    private boolean isPttManualOn = false; // Flag separat pentru PTT manual (buton)
    private int lastLiveTxLength = 0;      // Urmărire caractere deja trimise "live"
    private volatile boolean folosesteDiddle = true; // Emisie caractere de umplutură în idle
    private volatile boolean folosesteRtsPtPtt = false;
    private CheckBox cbDiddle;
    private Button btnToggleLine, btnMacroCr;
    private LinearLayout rAndSetariAscunsePtt;

    // Gestionare vizibilitate setări PTT (Long Press / Auto-hide)
    private final Handler handlerPttSettings = new Handler(Looper.getMainLooper());
    private final Runnable runnableAscundeSetariPtt = () -> {
        if (rAndSetariAscunsePtt != null) rAndSetariAscunsePtt.setVisibility(View.GONE);
    };

    private void reseteazaTemporizatorSetari(int ms) {
        handlerPttSettings.removeCallbacks(runnableAscundeSetariPtt);
        if (rAndSetariAscunsePtt != null && rAndSetariAscunsePtt.getVisibility() == View.VISIBLE) {
            handlerPttSettings.postDelayed(runnableAscundeSetariPtt, ms);
        }
    }

    // FUNCTIONALITATE NOUA: coada de mesaje pentru transmisie RTTY. Un singur thread
    // dedicat de transmisie (workerTxActiv) proceseaza mesajele STRICT secvential - daca
    // se apasa SEND cat timp un mesaj e deja in curs de transmisie, noul mesaj asteapta
    // in coada, nu porneste un thread separat care ar concura pe acelasi port serial.
    private final java.util.concurrent.ConcurrentLinkedQueue<String> coadaMesajeTxRtty = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicBoolean workerTxRttyActiv = new java.util.concurrent.atomic.AtomicBoolean(false);

    // CORECTIE: referinte pastrate catre thread-urile de fundal (decodare + TX), ca
    // eliberareResurse() sa poata astepta (join) oprirea lor efectiva, nu doar sa
    // seteze un flag si sa continue imediat sperand ca s-au oprit. Fara asta, la EXIT
    // se putea intampla o cursa: threadul TX inca scriind pe port dupa ce RadioManager
    // il inchisese deja (mai ales cu coada TX noua, daca EXIT venea in mijlocul unei
    // transmisii).
    private volatile Thread threadDecodareRtty;
    private volatile Thread threadWorkerTxRtty;
    private volatile boolean opreteTxRttySolicitata = false;

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
        incarcaSetariRtty();
    }

    /**
     * OPTIMIZARE 1 (Varianta A): slider-ul "Latime Filtru" controleaza rCheb, folosit
     * doar de rezonatorul Chebyshev. In modul Matched Filter, rCheb nu are niciun efect
     * asupra decodarii - il dezactivam vizual, ca sa nu para ca ajusteaza ceva ce de
     * fapt nu face nimic in acel mod.
     *
     * ISTORIC: am incercat sa repurpozam acest slider in modul MF pentru a controla
     * punctul de esantionare al bitului (0.45/0.50/0.55) - teste pe hardware real au
     * aratat ca nicio valoare nu aducea o imbunatatire clara si vizibila fata de
     * mijlocul fix (0.5), asa ca am simplificat inapoi: punctul de esantionare e acum
     * o constanta fixa (PCT_ESANTIONARE = 0.5) in bucla de decodare, identica pentru
     * ambii decodori, fara control din UI.
     */
    private void actualizeazaStareSliderLatime() {
        if (sbLatimeFiltru == null || tvFiltruValoare == null) return;
        boolean activ = !demod.folosesteMatchedFilter;
        sbLatimeFiltru.setEnabled(activ);
        sbLatimeFiltru.setAlpha(activ ? 1.0f : 0.4f);
        if (activ) {
            int bwHz = (int) Math.round((1.0 - demod.rCheb) * demod.sampleRateFiltre / (2 * Math.PI));
            tvFiltruValoare.setText("~" + bwHz + " Hz");
        } else {
            tvFiltruValoare.setText("N/A (Matched Filter)");
        }
    }

    /**
     * Progresul minim de slider (0-20) care garanteaza latimeDnrHz efectiv rezultat
     * >= DNR_LATIME_MIN_HZ_MATCHED_FILTER, tinand cont ca slider-ul are pasi discreti
     * de 62.5 Hz (1250/20) - vezi comentariul din aplicaClampDnrPentruMatchedFilter.
     */
    private int progresMinimDnrPentruMF() {
        int progresMinim = (int) Math.ceil((DNR_LATIME_MIN_HZ_MATCHED_FILTER - 250.0) * 20.0 / 1250.0);
        return Math.max(0, Math.min(20, progresMinim));
    }

    /**
     * CIZELARE: cand decodorul Matched Filter e activ, forteaza latimea DNR la un
     * minim de siguranta (DNR_LATIME_MIN_HZ_MATCHED_FILTER), ca marginile filtrului
     * biquad sa nu taie din energia utila de la Mark/Space inainte ca MF sa aiba pe
     * ce corela. Actualizeaza si UI-ul (slider + text) ca schimbarea sa fie vizibila
     * pentru utilizator, nu doar aplicata silentios pe fundal.
     *
     * CORECTIE: progresul minim se calculeaza cu Math.ceil() in loc de Math.round().
     * Slider-ul are pasi discreti de 62.5 Hz, iar 400 Hz nu cade exact pe un pas ->
     * Math.round(2.4) da progres 2, adica 375 Hz dupa recalcularea facuta de
     * onProgressChanged (sub prag!). Cu Math.ceil(2.4) = 3 obtinem 437.5 Hz, primul
     * pas de slider care respecta efectiv pragul de 400 Hz.
     *
     * NOTA: aceasta metoda acopera comutarea decoderului; onProgressChanged de mai
     * jos acopera separat cazul in care utilizatorul (sau alt cod) misca slider-ul
     * sub prag DUPA ce Matched Filter e deja activ - cele doua se completeaza.
     */
    private void aplicaClampDnrPentruMatchedFilter() {
        if (!demod.folosesteMatchedFilter || demod.latimeDnrHz >= DNR_LATIME_MIN_HZ_MATCHED_FILTER) return;

        int progresMinim = progresMinimDnrPentruMF();
        demod.latimeDnrHz = 250.0 + progresMinim * (1250.0 / 20.0);
        demod.calculeazaCoeficientiDnr(demod.sampleRateFiltre);

        if (sbDnrLatime != null && tvDnrValoare != null) {
            sbDnrLatime.setProgress(progresMinim);
            tvDnrValoare.setText("~" + Math.round(demod.latimeDnrHz) + " Hz");
            salveazaSetareRtty(PREF_RTTY_DNR_BW, progresMinim);
        }
    }

    private void sincronizeazaSetariReceptie() {
        demod.sincronizeazaCoeficienti();
        actualizeazaUiReceptie();
    }

    private void actualizeazaUiReceptie() {
        if (waterfall != null) {
            waterfall.setTunedFrequency((int) demod.frecventaTunedBaza);
            waterfall.setShiftHz(demod.regimReverseSoftware ? -170 : 170);
        }
        if (btnRttyReverseToggle != null) {
            btnRttyReverseToggle.setText(demod.regimReverseSoftware ? "REV" : "NORM");
            btnRttyReverseToggle.setBackgroundColor(Color.parseColor(demod.regimReverseSoftware ? "#B71C1C" : "#757575"));
        }
        if (tvFrecventaCurenta != null) {
            tvFrecventaCurenta.setText(String.format("MARK: %d Hz | Shift: 170 Hz | SNR: %s",
                    (int) demod.frecventaTunedBaza, snrTextLive));
        }
    }

    public View getView() {
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
                        // REFACTORIZARE: folosim append() si adaugam sufix inteligent (Spatiu sau Newline)
                        String current = etRttyInputLive.getText().toString();

                        // Prefix inteligent
                        if (!current.isEmpty() && !current.endsWith(" ") && !current.endsWith("\n")) {
                            etRttyInputLive.append(" ");
                        }

                        // {call} -> ultimul indicativ memorat (tap pe indicativ decodat/spot)
                        String textDeInserat = inlocuiestePlaceholderIndicativ(textFinal);

                        // Macrourile se leagă mereu prin spațiu - dacă vrei o linie nouă
                        // explicit, apasă butonul M-CR (inserează \n la poziția cursorului).
                        etRttyInputLive.append(textDeInserat + " ");
                        etRttyInputLive.setSelection(etRttyInputLive.getText().length());
                        // Focus programatic (fără tap) - arată cursorul clipind la final,
                        // dar NU deschide tastatura (asta se întâmplă doar la tap direct
                        // pe câmp, din gestionarea implicită a atingerii pe EditText).
                        etRttyInputLive.requestFocus();
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
        viewPrincipal = new LinearLayout(context);
        viewPrincipal.setOrientation(LinearLayout.VERTICAL);
        viewPrincipal.setBackgroundColor(Color.parseColor("#121212"));
        viewPrincipal.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        // ZONA DE SUS - SCROLLABLE (Afișaj și Comenzi decodor)
        ScrollView svTop = new ScrollView(context);
        LinearLayout.LayoutParams pSvTop = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        svTop.setLayoutParams(pSvTop);

        LinearLayout llTopContent = new LinearLayout(context);
        llTopContent.setOrientation(LinearLayout.VERTICAL);
        llTopContent.setPadding(30, 20, 30, 10);
        svTop.addView(llTopContent);

        // HEADER
        tvFrecventaCurenta = new TextView(context);
        tvFrecventaCurenta.setText(String.format("MARK: %d Hz | Shift: 170 Hz | SNR: %s",
                (int) demod.frecventaTunedBaza, snrTextLive));
        tvFrecventaCurenta.setTextColor(Color.parseColor("#00E5FF"));
        tvFrecventaCurenta.setTextSize(11);
        tvFrecventaCurenta.setTypeface(null, Typeface.BOLD);
        tvFrecventaCurenta.setPadding(4, 0, 0, 4);
        tvFrecventaCurenta.setSingleLine(true);
        llTopContent.addView(tvFrecventaCurenta);

        // WATERFALL
        waterfall = new Waterfall(context, 48000 / WATERFALL_FACTOR_DECIMARE);
        LinearLayout.LayoutParams pWater = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 260);
        pWater.setMargins(0, 5, 0, 10);
        waterfall.setLayoutParams(pWater);
        waterfall.setTunedFrequency((int) demod.frecventaTunedBaza);
        waterfall.setShiftHz(170);
        waterfall.setOnFrequencyTappedListener(frequencyHz -> {
            demod.frecventaTunedBaza = frequencyHz;
            sincronizeazaSetariReceptie();
        });
        llTopContent.addView(waterfall);

        // TERMINAL RX
        svRttyScroll = new ScrollView(context);
        LinearLayout.LayoutParams pSv = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 420);
        pSv.setMargins(0, 0, 0, 15);
        svRttyScroll.setLayoutParams(pSv);
        svRttyScroll.setBackgroundColor(Color.BLACK);
        svRttyScroll.setPadding(15, 15, 15, 15);
        tvRttyTerminal = new TextView(context);
        tvRttyTerminal.setText("--- RTTY RECEIVE TERMINAL ---\n");
        tvRttyTerminal.setTextColor(Color.parseColor("#00FF00"));
        tvRttyTerminal.setTextSize(14);
        tvRttyTerminal.setTypeface(Typeface.MONOSPACE);
        tvRttyTerminal.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        tvRttyTerminal.setHighlightColor(Color.TRANSPARENT);

        View.OnTouchListener listenerPauzaAutoScroll = (v, event) -> {
            if (callsignDetector != null) {
                callsignDetector.inregistreazaInteractiuneManuala();
            }
            return false;
        };
        svRttyScroll.setOnTouchListener(listenerPauzaAutoScroll);
        tvRttyTerminal.setOnTouchListener(listenerPauzaAutoScroll);

        svRttyScroll.addView(tvRttyTerminal);
        llTopContent.addView(svRttyScroll);

        // RAND 1: START | CLEAR | NORM/REV | A-TUNE
        LinearLayout rAndControaleRtty = new LinearLayout(context);
        rAndControaleRtty.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams pBtn = new LinearLayout.LayoutParams(0, 110, 1f);
        pBtn.setMargins(4, 2, 4, 10);

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
            if (demod.regimReverseSoftware) demod.frecventaTunedBaza -= 170;
            else demod.frecventaTunedBaza += 170;
            demod.regimReverseSoftware = !demod.regimReverseSoftware;
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
        llTopContent.addView(rAndControaleRtty);

        // RAND 2: TOGGLE DECODER & TRACK
        LinearLayout rAndDecoderToggle = new LinearLayout(context);
        rAndDecoderToggle.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams pRandDecoder = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pRandDecoder.setMargins(0, 0, 0, 10);
        rAndDecoderToggle.setLayoutParams(pRandDecoder);

        btnDecoderToggle = new Button(context);
        btnDecoderToggle.setText(demod.folosesteMatchedFilter ? "DECODER: MATCHED FILTER" : "DECODER: CHEBYSHEV");
        btnDecoderToggle.setBackgroundColor(Color.parseColor(demod.folosesteMatchedFilter ? "#6A1B9A" : "#00695C"));
        btnDecoderToggle.setTextColor(Color.WHITE);
        btnDecoderToggle.setTypeface(null, Typeface.BOLD);
        btnDecoderToggle.setTextSize(10);
        LinearLayout.LayoutParams pBtnDecoder = new LinearLayout.LayoutParams(0, 110, 1f);
        pBtnDecoder.setMargins(5, 0, 5, 0);
        btnDecoderToggle.setLayoutParams(pBtnDecoder);
        btnDecoderToggle.setOnClickListener(v -> {
            demod.folosesteMatchedFilter = !demod.folosesteMatchedFilter;
            btnDecoderToggle.setText(demod.folosesteMatchedFilter ? "DECODER: MATCHED FILTER" : "DECODER: CHEBYSHEV");
            btnDecoderToggle.setBackgroundColor(Color.parseColor(demod.folosesteMatchedFilter ? "#6A1B9A" : "#00695C"));
            if (demod.folosesteMatchedFilter && demod.mfBuffer == null) {
                demod.initMatchedFilterTemplates(48000, BAUD_RTTY);
            }
            demod.recalculeazaShabloaneMF();
            aplicaClampDnrPentruMatchedFilter();
            demod.solicitaResetFiltre = true;
            actualizeazaStareSliderLatime();
            Toast.makeText(context, "Decoder: " + (demod.folosesteMatchedFilter ? "Matched Filter" : "Chebyshev"),
                    Toast.LENGTH_SHORT).show();
        });
        rAndDecoderToggle.addView(btnDecoderToggle);

        LinearLayout llTrkSiNb = new LinearLayout(context);
        llTrkSiNb.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams pLlTrkSiNb = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        llTrkSiNb.setLayoutParams(pLlTrkSiNb);

        btnTrackToggle = new Button(context);
        btnTrackToggle.setText("OFF");
        btnTrackToggle.setBackgroundColor(Color.parseColor("#757575"));
        btnTrackToggle.setTextColor(Color.WHITE);
        btnTrackToggle.setTypeface(null, Typeface.BOLD);
        btnTrackToggle.setTextSize(10);
        btnTrackToggle.setMinHeight(0);
        btnTrackToggle.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams pBtnTrack = new LinearLayout.LayoutParams(0, 110, 0.25f);
        pBtnTrack.setMargins(5, 0, 2, 0);
        btnTrackToggle.setLayoutParams(pBtnTrack);
        btnTrackToggle.setOnClickListener(v -> {
            demod.modUrmarireFrecventa = (demod.modUrmarireFrecventa + 1) % 3;
            if (demod.modUrmarireFrecventa == 0) {
                btnTrackToggle.setText("OFF");
                btnTrackToggle.setBackgroundColor(Color.parseColor("#757575"));
            } else if (demod.modUrmarireFrecventa == 1) {
                btnTrackToggle.setText("AFC");
                btnTrackToggle.setBackgroundColor(Color.parseColor("#007ACC"));
            } else {
                btnTrackToggle.setText("PLL");
                btnTrackToggle.setBackgroundColor(Color.parseColor("#2E7D32"));
            }
        });
        llTrkSiNb.addView(btnTrackToggle);

        btnNoiseBlanker = new Button(context);
        btnNoiseBlanker.setText("NB");
        btnNoiseBlanker.setBackgroundColor(Color.parseColor("#757575"));
        btnNoiseBlanker.setTextColor(Color.WHITE);
        btnNoiseBlanker.setTypeface(null, Typeface.BOLD);
        btnNoiseBlanker.setTextSize(10);
        btnNoiseBlanker.setMinHeight(0);
        btnNoiseBlanker.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams pBtnNb = new LinearLayout.LayoutParams(0, 110, 0.25f);
        pBtnNb.setMargins(2, 0, 2, 0);
        btnNoiseBlanker.setLayoutParams(pBtnNb);

        final Handler handlerNbLongPress = new Handler(Looper.getMainLooper());
        final boolean[] sliderAfisatPrinHold = {false};
        Runnable ascundeSliderNbAutomat = () -> rAndSliderNoiseBlanker.setVisibility(View.GONE);
        Runnable actiuneHoldNb = () -> {
            sliderAfisatPrinHold[0] = true;
            boolean eraVizibil = rAndSliderNoiseBlanker.getVisibility() == View.VISIBLE;
            if (eraVizibil) {
                rAndSliderNoiseBlanker.setVisibility(View.GONE);
                handlerNbLongPress.removeCallbacks(ascundeSliderNbAutomat);
            } else {
                rAndSliderNoiseBlanker.setVisibility(View.VISIBLE);
                handlerNbLongPress.removeCallbacks(ascundeSliderNbAutomat);
                handlerNbLongPress.postDelayed(ascundeSliderNbAutomat, 5000);
            }
        };
        btnNoiseBlanker.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    sliderAfisatPrinHold[0] = false;
                    handlerNbLongPress.postDelayed(actiuneHoldNb, 3000);
                    return true;
                case MotionEvent.ACTION_UP:
                    handlerNbLongPress.removeCallbacks(actiuneHoldNb);
                    if (!sliderAfisatPrinHold[0]) {
                        demod.folosesteNoiseBlanker = !demod.folosesteNoiseBlanker;
                        btnNoiseBlanker.setBackgroundColor(Color.parseColor(demod.folosesteNoiseBlanker ? "#007ACC" : "#757575"));
                    }
                    v.performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    handlerNbLongPress.removeCallbacks(actiuneHoldNb);
                    return true;
                default:
                    return false;
            }
        });
        llTrkSiNb.addView(btnNoiseBlanker);

        // Înălțime comună DNR/SNR-G - garantează valori identice, indiferent
        // de mărimea textului fiecărui buton.
        final int inaltimeDnrSnrG = 110;

        btnDnr = new Button(context);
        btnDnr.setText("DNR");
        btnDnr.setBackgroundColor(Color.parseColor("#757575"));
        btnDnr.setTextColor(Color.WHITE);
        btnDnr.setTypeface(null, Typeface.BOLD);
        btnDnr.setTextSize(10);
        btnDnr.setMinHeight(0);
        btnDnr.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams pBtnDnr = new LinearLayout.LayoutParams(0, inaltimeDnrSnrG, 0.25f);
        pBtnDnr.setMargins(2, 0, 2, 0);
        btnDnr.setLayoutParams(pBtnDnr);

        final Handler handlerDnrLongPress = new Handler(Looper.getMainLooper());
        final boolean[] sliderDnrAfisat = {false};
        Runnable ascundeSliderDnr = () -> rAndSliderDnr.setVisibility(View.GONE);
        Runnable actiuneHoldDnr = () -> {
            sliderDnrAfisat[0] = true;
            if (rAndSliderDnr.getVisibility() == View.VISIBLE) {
                rAndSliderDnr.setVisibility(View.GONE);
                handlerDnrLongPress.removeCallbacks(ascundeSliderDnr);
            } else {
                rAndSliderDnr.setVisibility(View.VISIBLE);
                handlerDnrLongPress.postDelayed(ascundeSliderDnr, 5000);
            }
        };
        btnDnr.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    sliderDnrAfisat[0] = false;
                    handlerDnrLongPress.postDelayed(actiuneHoldDnr, 3000);
                    return true;
                case MotionEvent.ACTION_UP:
                    handlerDnrLongPress.removeCallbacks(actiuneHoldDnr);
                    if (!sliderDnrAfisat[0]) {
                        demod.folosesteDnr = !demod.folosesteDnr;
                        btnDnr.setBackgroundColor(Color.parseColor(demod.folosesteDnr ? "#00897B" : "#757575"));
                        if (!isInitialLoading) salveazaSetareRtty(PREF_RTTY_DNR_ON, demod.folosesteDnr);
                    }
                    v.performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    handlerDnrLongPress.removeCallbacks(actiuneHoldDnr);
                    return true;
                default:
                    return false;
            }
        });
        llTrkSiNb.addView(btnDnr);

        btnSnrGate = new Button(context);
        btnSnrGate.setText("SNR-G");
        btnSnrGate.setBackgroundColor(Color.parseColor("#757575"));
        btnSnrGate.setTextColor(Color.WHITE);
        btnSnrGate.setTypeface(null, Typeface.BOLD);
        btnSnrGate.setTextSize(10);
        btnSnrGate.setMinHeight(0);
        btnSnrGate.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams pBtnSnrG = new LinearLayout.LayoutParams(0, inaltimeDnrSnrG, 0.25f);
        pBtnSnrG.setMargins(2, 0, 5, 0);
        btnSnrGate.setLayoutParams(pBtnSnrG);

        final Handler handlerSnrGLongPress = new Handler(Looper.getMainLooper());
        final boolean[] sliderSnrGAfisat = {false};
        Runnable ascundeSliderSnrG = () -> rAndSliderSnrGate.setVisibility(View.GONE);
        Runnable actiuneHoldSnrG = () -> {
            sliderSnrGAfisat[0] = true;
            if (rAndSliderSnrGate.getVisibility() == View.VISIBLE) {
                rAndSliderSnrGate.setVisibility(View.GONE);
                handlerSnrGLongPress.removeCallbacks(ascundeSliderSnrG);
            } else {
                rAndSliderSnrGate.setVisibility(View.VISIBLE);
                handlerSnrGLongPress.postDelayed(ascundeSliderSnrG, 5000);
            }
        };
        btnSnrGate.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    sliderSnrGAfisat[0] = false;
                    handlerSnrGLongPress.postDelayed(actiuneHoldSnrG, 3000);
                    return true;
                case MotionEvent.ACTION_UP:
                    handlerSnrGLongPress.removeCallbacks(actiuneHoldSnrG);
                    if (!sliderSnrGAfisat[0]) {
                        demod.folosesteSnrGating = !demod.folosesteSnrGating;
                        btnSnrGate.setBackgroundColor(Color.parseColor(demod.folosesteSnrGating ? "#00897B" : "#757575"));
                        if (!isInitialLoading) salveazaSetareRtty(PREF_RTTY_SNRG_ON, demod.folosesteSnrGating);
                    }
                    v.performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    handlerSnrGLongPress.removeCallbacks(actiuneHoldSnrG);
                    return true;
                default:
                    return false;
            }
        });
        llTrkSiNb.addView(btnSnrGate);
        // Sincronizarea reală de înălțime (SNR-G <-> DNR) se face în
        // sincronizeazaInaltimileButoanelor(), apelată din constructor -
        // acolo așteptăm atașarea la fereastră, nu aici (prea devreme).
        rAndDecoderToggle.addView(llTrkSiNb);
        llTopContent.addView(rAndDecoderToggle);

        // SLIDER NOISE BLANKER (ascuns implicit)
        rAndSliderNoiseBlanker = new LinearLayout(context);
        rAndSliderNoiseBlanker.setOrientation(LinearLayout.HORIZONTAL);
        rAndSliderNoiseBlanker.setVisibility(View.GONE);
        LinearLayout.LayoutParams pRandNb = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pRandNb.setMargins(0, 5, 0, 10);
        rAndSliderNoiseBlanker.setLayoutParams(pRandNb);
        TextView tvNbLabel = new TextView(context);
        tvNbLabel.setText("Prag NB:");
        tvNbLabel.setTextColor(Color.WHITE);
        tvNbLabel.setTextSize(13);
        rAndSliderNoiseBlanker.addView(tvNbLabel);
        sbNoiseBlanker = new SeekBar(context);
        sbNoiseBlanker.setMax(20);
        sbNoiseBlanker.setProgress(9);
        sbNoiseBlanker.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tvNoiseBlankerValoare = new TextView(context);
        tvNoiseBlankerValoare.setText("x5.0");
        tvNoiseBlankerValoare.setTextColor(Color.parseColor("#00FF00"));
        tvNoiseBlankerValoare.setTextSize(13);
        tvNoiseBlankerValoare.setTypeface(Typeface.MONOSPACE);
        sbNoiseBlanker.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                demod.nbFactorPrag = 2.0 + (progress * (10.0 / 20.0));
                tvNoiseBlankerValoare.setText(String.format("x%.1f", demod.nbFactorPrag));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { handlerNbLongPress.removeCallbacks(ascundeSliderNbAutomat); }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { handlerNbLongPress.postDelayed(ascundeSliderNbAutomat, 5000); }
        });
        rAndSliderNoiseBlanker.addView(sbNoiseBlanker);
        rAndSliderNoiseBlanker.addView(tvNoiseBlankerValoare);
        llTopContent.addView(rAndSliderNoiseBlanker);

        // SLIDER DNR
        rAndSliderDnr = new LinearLayout(context);
        rAndSliderDnr.setOrientation(LinearLayout.HORIZONTAL);
        rAndSliderDnr.setVisibility(View.GONE);
        rAndSliderDnr.setLayoutParams(pRandNb);
        TextView tvDnrLabel = new TextView(context);
        tvDnrLabel.setText("Lățime DNR:");
        tvDnrLabel.setTextColor(Color.WHITE);
        tvDnrLabel.setTextSize(13);
        rAndSliderDnr.addView(tvDnrLabel);
        sbDnrLatime = new SeekBar(context);
        sbDnrLatime.setMax(20);
        sbDnrLatime.setProgress(9);
        sbDnrLatime.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tvDnrValoare = new TextView(context);
        tvDnrValoare.setText("~600 Hz");
        tvDnrValoare.setTextColor(Color.parseColor("#00FF00"));
        tvDnrValoare.setTextSize(13);
        tvDnrValoare.setTypeface(Typeface.MONOSPACE);
        sbDnrLatime.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Garda: aceasta invocare a fost declansata chiar de setProgress() de mai jos -
                // evitam sa procesam de doua ori acelasi eveniment (si sa suprascriem
                // eronat latimeDnrHz cu valoarea NEclampata calculata la primul apel).
                if (ajustareProgramaticaDnr) return;

                // CIZELARE: cat timp Matched Filter e activ, nu lasa slider-ul (nici prin
                // drag manual, nici prin setProgress() programatic din alta parte) sa coboare
                // sub pragul minim de siguranta - altfel clamp-ul de la comutarea decoderului
                // (aplicaClampDnrPentruMatchedFilter) poate fi ocolit oricand de o simpla
                // miscare a slider-ului dupa aceea.
                int progresEfectiv = progress;
                if (demod.folosesteMatchedFilter) {
                    int progresMinim = progresMinimDnrPentruMF();
                    if (progresEfectiv < progresMinim) {
                        progresEfectiv = progresMinim;
                        ajustareProgramaticaDnr = true;
                        seekBar.setProgress(progresEfectiv); // repozitioneaza vizual thumb-ul la minimul permis
                        ajustareProgramaticaDnr = false;
                    }
                }

                demod.latimeDnrHz = 250.0 + progresEfectiv * (1250.0 / 20.0);
                tvDnrValoare.setText("~" + Math.round(demod.latimeDnrHz) + " Hz");
                demod.calculeazaCoeficientiDnr(demod.sampleRateFiltre);
                if (fromUser) salveazaSetareRtty(PREF_RTTY_DNR_BW, progresEfectiv);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { handlerDnrLongPress.removeCallbacks(ascundeSliderDnr); }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { handlerDnrLongPress.postDelayed(ascundeSliderDnr, 5000); }
        });
        rAndSliderDnr.addView(sbDnrLatime);
        rAndSliderDnr.addView(tvDnrValoare);
        llTopContent.addView(rAndSliderDnr);

        // SLIDER SNR GATING
        rAndSliderSnrGate = new LinearLayout(context);
        rAndSliderSnrGate.setOrientation(LinearLayout.HORIZONTAL);
        rAndSliderSnrGate.setVisibility(View.GONE);
        rAndSliderSnrGate.setLayoutParams(pRandNb);
        TextView tvSnrGLabel = new TextView(context);
        tvSnrGLabel.setText("Prag SNR-G:");
        tvSnrGLabel.setTextColor(Color.WHITE);
        tvSnrGLabel.setTextSize(13);
        rAndSliderSnrGate.addView(tvSnrGLabel);
        sbSnrGateK = new SeekBar(context);
        sbSnrGateK.setMax(20);
        sbSnrGateK.setProgress(3);
        sbSnrGateK.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tvSnrGateValoare = new TextView(context);
        tvSnrGateValoare.setText("x1.3");
        tvSnrGateValoare.setTextColor(Color.parseColor("#00FF00"));
        tvSnrGateValoare.setTextSize(13);
        tvSnrGateValoare.setTypeface(Typeface.MONOSPACE);
        sbSnrGateK.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                demod.snrGatingK = 1.0 + progress * (2.0 / 20.0);
                tvSnrGateValoare.setText(String.format("x%.1f", demod.snrGatingK));
                if (fromUser) salveazaSetareRtty(PREF_RTTY_SNRG_K, progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { handlerSnrGLongPress.removeCallbacks(ascundeSliderSnrG); }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { handlerSnrGLongPress.postDelayed(ascundeSliderSnrG, 5000); }
        });
        rAndSliderSnrGate.addView(sbSnrGateK);
        rAndSliderSnrGate.addView(tvSnrGateValoare);
        llTopContent.addView(rAndSliderSnrGate);

        // SLIDER SENSIBILITATE
        LinearLayout rAndSensibilitate = new LinearLayout(context);
        rAndSensibilitate.setOrientation(LinearLayout.HORIZONTAL);
        rAndSensibilitate.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams pRandSens = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pRandSens.setMargins(0, 0, 0, 10);
        rAndSensibilitate.setLayoutParams(pRandSens);
        TextView tvSensLabel = new TextView(context);
        tvSensLabel.setText("Sensibilitate:");
        tvSensLabel.setTextColor(Color.WHITE);
        tvSensLabel.setTextSize(13);
        rAndSensibilitate.addView(tvSensLabel);
        sbSensibilitate = new SeekBar(context);
        sbSensibilitate.setMax(20);
        sbSensibilitate.setProgress((int) Math.round(demod.pragSensibilitate * 100));
        sbSensibilitate.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tvSensibilitateValoare = new TextView(context);
        tvSensibilitateValoare.setText(String.format("%d%%", (int) Math.round(demod.pragSensibilitate * 100)));
        tvSensibilitateValoare.setTextColor(Color.parseColor("#00E5FF"));
        tvSensibilitateValoare.setTextSize(13);
        rAndSensibilitate.addView(sbSensibilitate);
        rAndSensibilitate.addView(tvSensibilitateValoare);
        sbSensibilitate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                demod.pragSensibilitate = progress / 100.0;
                tvSensibilitateValoare.setText(String.format("%d%%", progress));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        llTopContent.addView(rAndSensibilitate);

        // SLIDER LATIME FILTRU
        LinearLayout rAndFiltru = new LinearLayout(context);
        rAndFiltru.setOrientation(LinearLayout.HORIZONTAL);
        rAndFiltru.setGravity(Gravity.CENTER_VERTICAL);
        rAndFiltru.setLayoutParams(pRandSens);
        TextView tvFiltruLabel = new TextView(context);
        tvFiltruLabel.setText("Latime Filtru:");
        tvFiltruLabel.setTextColor(Color.WHITE);
        tvFiltruLabel.setTextSize(13);
        rAndFiltru.addView(tvFiltruLabel);
        sbLatimeFiltru = new SeekBar(context);
        sbLatimeFiltru.setMax(20);
        sbLatimeFiltru.setProgress(12);
        sbLatimeFiltru.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tvFiltruValoare = new TextView(context);
        tvFiltruValoare.setText("~60 Hz");
        tvFiltruValoare.setTextColor(Color.parseColor("#00E5FF"));
        tvFiltruValoare.setTextSize(13);
        rAndFiltru.addView(sbLatimeFiltru);
        rAndFiltru.addView(tvFiltruValoare);
        sbLatimeFiltru.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                double bwMinHz = Math.max(20.0, BAUD_RTTY * 1.2);
                double rChebMax = 1.0 - (bwMinHz * 2.0 * Math.PI / demod.sampleRateFiltre);
                demod.rCheb = RCHEB_MIN + progress * ((rChebMax - RCHEB_MIN) / 20.0);
                int bwHz = (int) Math.round((1.0 - demod.rCheb) * demod.sampleRateFiltre / (2 * Math.PI));
                tvFiltruValoare.setText("~" + bwHz + " Hz");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { demod.solicitaResetFiltre = true; }
        });
        llTopContent.addView(rAndFiltru);
        actualizeazaStareSliderLatime();

        viewPrincipal.addView(svTop);

        // ZONA DE JOS - FIXĂ (Operare: Input, Butoane, Macrouri)
        LinearLayout llBottomOperare = new LinearLayout(context);
        llBottomOperare.setOrientation(LinearLayout.VERTICAL);
        llBottomOperare.setPadding(30, 0, 30, 10);
        llBottomOperare.setBackgroundColor(Color.parseColor("#1A1A1A"));

        // RAND SETĂRI ASCUNSE (Diddle + RTS/DTR Toggle)
        rAndSetariAscunsePtt = new LinearLayout(context);
        rAndSetariAscunsePtt.setOrientation(LinearLayout.HORIZONTAL);
        rAndSetariAscunsePtt.setGravity(Gravity.CENTER_VERTICAL);
        rAndSetariAscunsePtt.setVisibility(View.GONE);
        LinearLayout.LayoutParams pRandHidden = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pRandHidden.setMargins(0, 0, 0, 5);
        rAndSetariAscunsePtt.setLayoutParams(pRandHidden);

        cbDiddle = new CheckBox(context);
        cbDiddle.setText("DIDDLE");
        cbDiddle.setTextColor(Color.YELLOW);
        cbDiddle.setChecked(folosesteDiddle);
        cbDiddle.setOnCheckedChangeListener((v, isChecked) -> {
            folosesteDiddle = isChecked;
            if (isChecked && isPttManualOn) porneteWorkerTxRttyDacaNuRuleaza();
            // Resetăm și prelungim timpul de vizibilitate (+3s față de cele 5s standard = 8s)
            reseteazaTemporizatorSetari(8000);
        });
        rAndSetariAscunsePtt.addView(cbDiddle);

        btnToggleLine = new Button(context);
        btnToggleLine.setText(folosesteRtsPtPtt ? "USE: RTS" : "USE: DTR");
        btnToggleLine.setBackgroundColor(Color.parseColor("#455A64"));
        btnToggleLine.setTextColor(Color.WHITE);
        btnToggleLine.setTextSize(13);
        btnToggleLine.setPadding(24, 0, 24, 0);
        LinearLayout.LayoutParams pBtnLine = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, 90);
        pBtnLine.setMargins(20, 0, 0, 0);
        btnToggleLine.setLayoutParams(pBtnLine);
        btnToggleLine.setOnClickListener(v -> {
            folosesteRtsPtPtt = !folosesteRtsPtPtt;
            btnToggleLine.setText(folosesteRtsPtPtt ? "USE: RTS" : "USE: DTR");
            salveazaSetareRtty(PREF_RTTY_USE_RTS_PTT, folosesteRtsPtPtt);
            // Resetăm și prelungim timpul de vizibilitate
            reseteazaTemporizatorSetari(8000);
        });
        rAndSetariAscunsePtt.addView(btnToggleLine);
        llBottomOperare.addView(rAndSetariAscunsePtt);

        // CAMP EDITARE FSK (multi-line)
        etRttyInputLive = new EditText(context);
        etRttyInputLive.setHint("Type live FSK text...");
        etRttyInputLive.setHintTextColor(Color.GRAY);
        etRttyInputLive.setTextColor(Color.WHITE);
        etRttyInputLive.setTextSize(14);
        etRttyInputLive.setMinLines(2);
        etRttyInputLive.setMaxLines(4);
        etRttyInputLive.setGravity(Gravity.TOP | Gravity.START);
        etRttyInputLive.setInputType(InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_FLAG_MULTI_LINE |
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        etRttyInputLive.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.AllCaps()});

        final android.graphics.drawable.Drawable clearIcon = new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT);
        etRttyInputLive.setCompoundDrawablesWithIntrinsicBounds(null, null, clearIcon, null);
        etRttyInputLive.setCompoundDrawablePadding(10);
        etRttyInputLive.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                String currentText = s.toString();
                if (!currentText.isEmpty()) {
                    etRttyInputLive.setCompoundDrawablesWithIntrinsicBounds(null, null, createXIcon(), null);
                } else {
                    etRttyInputLive.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
                }
                if (isPttManualOn) {
                    if (currentText.length() > lastLiveTxLength) {
                        adaugaInCoadaTransmisieRtty(currentText.substring(lastLiveTxLength));
                        lastLiveTxLength = currentText.length();
                    } else if (currentText.length() < lastLiveTxLength) {
                        lastLiveTxLength = currentText.length();
                    }
                }
            }
        });
        etRttyInputLive.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                if (event.getRawX() >= (etRttyInputLive.getRight() - etRttyInputLive.getCompoundPaddingRight())) {
                    etRttyInputLive.setText("");
                    lastLiveTxLength = 0;
                    return true;
                }
            }
            return false;
        });
        LinearLayout.LayoutParams pEtInput = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pEtInput.setMargins(0, 5, 0, 5);
        etRttyInputLive.setLayoutParams(pEtInput);
        llBottomOperare.addView(etRttyInputLive);

        // SEND & PTT BUTTONS
        // Înălțime comună pentru tot rândul (SEND, PTT, M-CR) - folosită explicit
        // de toate cele trei ca să fie garantat identice, indiferent de mărimea
        // textului fiecăruia.
        final int inaltimeRandSendPtt = 120;

        LinearLayout rAndSendPtt = new LinearLayout(context);
        rAndSendPtt.setOrientation(LinearLayout.HORIZONTAL);
        rAndSendPtt.setGravity(Gravity.END);
        btnRttySendLive = new Button(context);
        btnRttySendLive.setText("SEND");
        btnRttySendLive.setBackgroundColor(Color.parseColor("#2E7D32"));
        btnRttySendLive.setTextColor(Color.WHITE);
        btnRttySendLive.setMinHeight(0);
        LinearLayout.LayoutParams pBtnS = new LinearLayout.LayoutParams(0, inaltimeRandSendPtt, 1f);
        pBtnS.setMargins(0, 0, 10, 0);
        btnRttySendLive.setLayoutParams(pBtnS);
        btnRttySendLive.setOnClickListener(v -> {
            if (isPttManualOn) {
                etRttyInputLive.setText("");
                lastLiveTxLength = 0;
            } else {
                String txt = etRttyInputLive.getText().toString();
                if (!txt.isEmpty()) {
                    adaugaInCoadaTransmisieRtty(txt);
                    etRttyInputLive.setText("");
                    lastLiveTxLength = 0;
                }
            }
        });

        btnRttyPttToggle = new Button(context);
        btnRttyPttToggle.setText("PTT");
        btnRttyPttToggle.setBackgroundColor(Color.parseColor("#424242"));
        btnRttyPttToggle.setTextColor(Color.WHITE);
        btnRttyPttToggle.setMinHeight(0);
        // Micșorat față de SEND (care rămâne la weight 1f neschimbat) ca să facă loc
        // butonului M-CR de lângă el.
        LinearLayout.LayoutParams pBtnPtt = new LinearLayout.LayoutParams(0, inaltimeRandSendPtt, 0.6f);
        pBtnPtt.setMargins(0, 0, 10, 0);
        btnRttyPttToggle.setLayoutParams(pBtnPtt);

        final boolean[] settingAfisatPrinHold = {false};
        Runnable actiuneHoldPtt = () -> {
            if (!isPttManualOn) {
                settingAfisatPrinHold[0] = true;
                if (rAndSetariAscunsePtt.getVisibility() == View.VISIBLE) {
                    rAndSetariAscunsePtt.setVisibility(View.GONE);
                    handlerPttSettings.removeCallbacks(runnableAscundeSetariPtt);
                } else {
                    rAndSetariAscunsePtt.setVisibility(View.VISIBLE);
                    reseteazaTemporizatorSetari(5000); // 5 secunde la prima afișare
                }
            }
        };

        btnRttyPttToggle.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    settingAfisatPrinHold[0] = false;
                    handlerPttSettings.postDelayed(actiuneHoldPtt, 3000);
                    return true;
                case MotionEvent.ACTION_UP:
                    handlerPttSettings.removeCallbacks(actiuneHoldPtt);
                    if (!settingAfisatPrinHold[0]) {
                        comutaStarePttManual();
                        // Dacă setările sunt vizibile, prelungim timpul la apăsarea PTT
                        reseteazaTemporizatorSetari(8000);
                    }
                    v.performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    handlerPttSettings.removeCallbacks(actiuneHoldPtt);
                    return true;
                default:
                    return false;
            }
        });

        btnMacroCr = new Button(context);
        btnMacroCr.setText("M-CR");
        // Fără setTextSize explicit - moștenește exact același font implicit
        // ca SEND/PTT (care nici ele nu-l suprascriu), ca să aibă aceeași
        // "prezență" vizuală.
        btnMacroCr.setBackgroundColor(Color.parseColor("#424242"));
        btnMacroCr.setTextColor(Color.parseColor("#00E5FF"));
        btnMacroCr.setPadding(20, 0, 20, 0);
        btnMacroCr.setMinHeight(0);
        btnMacroCr.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, inaltimeRandSendPtt));
        btnMacroCr.setOnClickListener(v -> {
            // Inserează \n exact la poziția cursorului (sau înlocuiește selecția,
            // dacă există una) în câmpul de text live - nu mai adaugă automat
            // un CR la fiecare macro, userul decide explicit unde vrea linie nouă.
            int start = etRttyInputLive.getSelectionStart();
            int end = etRttyInputLive.getSelectionEnd();
            if (start < 0) start = etRttyInputLive.getText().length();
            if (end < 0) end = start;
            int from = Math.min(start, end);
            int to = Math.max(start, end);
            etRttyInputLive.getText().replace(from, to, "\n");
            etRttyInputLive.setSelection(from + 1);
            etRttyInputLive.requestFocus();
        });

        rAndSendPtt.addView(btnRttySendLive);
        rAndSendPtt.addView(btnRttyPttToggle);
        rAndSendPtt.addView(btnMacroCr);
        // Sincronizarea reală de înălțime (M-CR <-> PTT) se face în
        // sincronizeazaInaltimileButoanelor(), apelată din constructor -
        // acolo așteptăm atașarea la fereastră, nu aici (prea devreme).
        llBottomOperare.addView(rAndSendPtt);

        // MACRO LIST
        ScrollView svMacroList = new ScrollView(context);
        LinearLayout.LayoutParams pSvMacro = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 220);
        pSvMacro.setMargins(0, 5, 0, 0);
        svMacroList.setLayoutParams(pSvMacro);
        svMacroList.setBackgroundColor(Color.BLACK);
        svMacroList.setPadding(10, 5, 10, 5);
        llMacroListContainer = new LinearLayout(context);
        llMacroListContainer.setOrientation(LinearLayout.VERTICAL);
        svMacroList.addView(llMacroListContainer);
        llBottomOperare.addView(svMacroList);

        viewPrincipal.addView(llBottomOperare);
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

        threadDecodareRtty = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
            int sampleRate = 48000;
            int bufferSize = AudioRecord.getMinBufferSize(sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (bufferSize <= 0) bufferSize = 4096;

            AudioRecord recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) return;
            recorder.startRecording();

            short[] audioBuffer = new short[bufferSize];
            Activity activity = (Activity) context;

            demod.setListener(new RttyDemodulator.Listener() {
                @Override
                public void onCodBaudot(int cod) {
                    proceseazaCodBaudot(cod);
                }

                @Override
                public void onStare(String snrText, int frecventaMarkHz) {
                    snrTextLive = snrText;
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    activity.runOnUiThread(() -> {
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        if (tvFrecventaCurenta != null) {
                            tvFrecventaCurenta.setText(String.format("MARK: %d Hz | Shift: 170 Hz | SNR: %s",
                                    frecventaMarkHz, snrText));
                        }
                        if (waterfall != null && demod.modUrmarireFrecventa > 0) {
                            waterfall.setTunedFrequency(frecventaMarkHz);
                        }
                    });
                }
            });
            demod.porneste(sampleRate);
            activity.runOnUiThread(this::actualizeazaUiReceptie);

            while (decodareRttyActiva) {
                if (activity.isFinishing() || activity.isDestroyed()) break;
                int read = recorder.read(audioBuffer, 0, audioBuffer.length);
                if (read <= 0) {
                    try { Thread.sleep(10); } catch (Exception e) {}
                    continue;
                }

                if (waterfall != null && read >= 3072) {
                    synchronized (vectorIstoricPeak) {
                        for (int j = 0; j < 512; j++) {
                            short sample = audioBuffer[j * WATERFALL_FACTOR_DECIMARE];
                            miniBufferFft[j] = sample;
                            vectorIstoricPeak[j] = sample;
                        }
                    }
                    short[] fftSlice = new short[512];
                    System.arraycopy(miniBufferFft, 0, fftSlice, 0, 512);
                    waterfall.pushSamples(fftSlice);
                }

                demod.proceseazaBloc(audioBuffer, read);
            }
            try {
                if (recorder.getState() == AudioRecord.STATE_INITIALIZED) {
                    recorder.stop();
                }
                recorder.release();
            } catch (Exception e) {
                android.util.Log.e("RTTY_DECODER", "Eroare oprire AudioRecord", e);
            }
        });
        threadDecodareRtty.start();
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

            // CORECTIE: instanta proprie de SimpleFFT, locala acestui thread de Auto-Tune,
            // in loc de fosta metoda statica SimpleFFT.magnitudeSpectrum() care partaja o
            // singura instanta (cu buffere interne mutabile) cu thread-ul de receptie RTTY -
            // vezi comentariul din SimpleFFT.java. Fiind locala, e implicit thread-safe: nu
            // exista nicio cale prin care alt thread sa scrie concurent in bufferele ei.
            SimpleFFT fftAutoTune = new SimpleFFT(512);
            double[] magBufferAutoTune = new double[256];

            for (int cadru = 0; cadru < NUMAR_CADRE_MEDIATE; cadru++) {
                short[] snapshot = new short[512];
                synchronized (vectorIstoricPeak) {
                    System.arraycopy(vectorIstoricPeak, 0, snapshot, 0, 512);
                }
                fftAutoTune.calculeazaMagnitudine(snapshot, 0, magBufferAutoTune);
                float[] magnitude = new float[magBufferAutoTune.length];
                for (int i = 0; i < magnitude.length; i++) magnitude[i] = (float) magBufferAutoTune[i];
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
                demod.frecventaTunedBaza = demod.regimReverseSoftware ? freqBinDeSus : freqBinDeJos;

                if (activity.isFinishing() || activity.isDestroyed()) return;
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    sincronizeazaSetariReceptie();
                    Toast.makeText(context, "Auto-Tune: " + (int) demod.frecventaTunedBaza + " Hz",
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

    /**
     * Setează starea liniei dedicate pentru PTT.
     */
    private void setHardwarePtt(boolean active) {
        if (radioManager == null || radioManager.getDataPort() == null) return;
        try {
            if (folosesteRtsPtPtt) {
                radioManager.getDataPort().setRTS(active);
            } else {
                radioManager.getDataPort().setDTR(active);
            }
        } catch (Exception e) {
            android.util.Log.e("RTTY_HW", "Err PTT line", e);
        }
    }

    /**
     * Setează starea liniei dedicate pentru modulația FSK (Mark/Space).
     */
    private void setHardwareFsk(boolean active) {
        if (radioManager == null || radioManager.getDataPort() == null) return;
        try {
            if (folosesteRtsPtPtt) {
                radioManager.getDataPort().setDTR(active);
            } else {
                radioManager.getDataPort().setRTS(active);
            }
        } catch (Exception e) {
            android.util.Log.e("RTTY_HW", "Err FSK line", e);
        }
    }

    /**
     * Așteaptă până la momentul targetNanos folosind un sleep hibrid
     * (milisecunde + busy-wait) pentru precizie maximă.
     */
    private void waitPreciseNanos(long targetNanos) {
        long delta;
        while ((delta = targetNanos - System.nanoTime()) > 0) {
            if (delta > 3_000_000L) { // > 3ms
                try {
                    // Dormim dar lăsăm o rezervă de 2ms pentru a nu depăși momentul critic
                    Thread.sleep((delta - 2_000_000L) / 1_000_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } else if (delta > 500_000L) { // Între 3ms și 0.5ms
                // Cedăm controlul altor thread-uri pentru a evita Thermal Throttling (100% CPU)
                Thread.yield();
            }
            // Sub 0.5ms busy-wait pur pentru precizie maximă
        }
    }

    private void comutaStarePttManual() {
        if (radioManager == null || radioManager.getDataPort() == null) return;
        try {
            // Toggle stare PTT manual
            isPttManualOn = !isPttManualOn;

            if (!isPttManualOn) {
                // Trecem în RX
                coadaMesajeTxRtty.clear();
                opreteTxRttySolicitata = true;

                isTransmittingRtty = false;
                setHardwarePtt(false);
                setHardwareFsk(false);

                actualizeazaLabelSend();
                btnRttyPttToggle.setBackgroundColor(Color.parseColor("#424242"));
                btnRttyPttToggle.setText("PTT");
            } else {
                // Trecem în TX
                opreteTxRttySolicitata = false; // Resetăm flag-ul de stop înainte de a porni
                isTransmittingRtty = true;
                setHardwarePtt(true);

                // Inițializăm urmărirea textului la lungimea actuală (ignorăm textul vechi din casetă)
                lastLiveTxLength = etRttyInputLive.getText().length();

                btnRttyPttToggle.setBackgroundColor(Color.parseColor("#B71C1C"));
                btnRttyPttToggle.setText("TX");

                // Pornim worker-ul pentru Diddle (dacă e activ)
                porneteWorkerTxRttyDacaNuRuleaza();
            }
        } catch (Exception e) {
            Toast.makeText(context, "Err PTT: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            isPttManualOn = false;
            isTransmittingRtty = false;
        }
    }

    /**
     * Punct de intrare pentru butonul SEND: adauga mesajul in coada si porneste
     * worker-ul de transmisie daca nu ruleaza deja. Mesajele se transmit STRICT
     * secvential, fara intrerupere, indiferent de cate ori se apasa SEND.
     */
    private void adaugaInCoadaTransmisieRtty(String text) {
        if (radioManager == null || radioManager.getDataPort() == null || !radioManager.getDataPort().isOpen()) {
            Toast.makeText(context, "Porturile DATA nu sunt conectate!", Toast.LENGTH_SHORT).show();
            return;
        }
        // Deblocăm motorul de transmisie în caz că a fost oprit anterior
        opreteTxRttySolicitata = false;

        coadaMesajeTxRtty.add(text);
        actualizeazaLabelSend();
        porneteWorkerTxRttyDacaNuRuleaza();
    }

    /** Actualizeaza caption-ul butonului SEND cu numarul de mesaje in asteptare + cel curent. */
    private void actualizeazaLabelSend() {
        if (btnRttySendLive == null) return;
        int total = coadaMesajeTxRtty.size() + (isTransmittingRtty ? 1 : 0);
        String caption = (total <= 1) ? "SEND" : ("SEND " + total);
        Activity activity = (Activity) context;
        if (activity.isFinishing() || activity.isDestroyed()) return;
        activity.runOnUiThread(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed()) {
                btnRttySendLive.setText(caption);
            }
        });
    }

    /**
     * Un singur thread de fundal "workerTxRttyActiv" goleste coada strict secvential -
     * fiecare mesaj asteapta terminarea celui anterior, niciodata transmisii concurente
     * pe acelasi port serial.
     */
    /**
     * Motorul principal de transmisie bazat pe "Continuous Clocking" (Metronom).
     * Gestionează coada de mesaje, Live TX și Diddles cu sincronizare la nivel de nanosecundă.
     */
    private void porneteWorkerTxRttyDacaNuRuleaza() {
        if (!workerTxRttyActiv.compareAndSet(false, true)) return;

        threadWorkerTxRtty = new Thread(() -> {
            long anchorTimeNanos = System.nanoTime();
            String textCurent = "";
            int indexCaracter = 0;
            boolean inBurst = false;

            try {
                while (!opreteTxRttySolicitata) {
                    // PROTECȚIE ANTI-LAG: Dacă ancora a rămas prea mult în urmă (> 15ms),
                    // resincronizăm cu timpul real pentru a evita rafală de biți "fast-forward".
                    if (System.nanoTime() - anchorTimeNanos > 15_000_000L) {
                        anchorTimeNanos = System.nanoTime();
                        //android.util.Log.d("RTTY_TX", "Drift resync applied");
                    }

                    // 1. GESTIONARE SURSĂ DATE
                    if (indexCaracter >= textCurent.length()) {
                        String urmatorulMesaj = coadaMesajeTxRtty.poll();
                        if (urmatorulMesaj != null) {
                            textCurent = urmatorulMesaj;
                            indexCaracter = 0;
                            // S-a adăugat text -> marcăm începutul unei rafale (burst)
                            if (!inBurst) {
                                setHardwarePtt(true);
                                isTransmittingRtty = true;
                                actualizeazaUiTxStare(true);
                                // Pre-ambulă: 2 Diddles pentru sincronizare
                                anchorTimeNanos = trimiteCodBaudotMetronom(0x1F, anchorTimeNanos);
                                anchorTimeNanos = trimiteCodBaudotMetronom(0x1F, anchorTimeNanos);
                                inBurst = true;
                            }
                        } else {
                            // Coada e goală. Verificăm dacă păstrăm emisia (PTT manual / Diddle)
                            textCurent = "";
                            if (!isPttManualOn) break; // Ieșim din worker dacă nu mai e nimic de trimis
                            inBurst = false; // Revenim la starea idle (Diddle)
                        }
                    }

                    // 2. DETERMINARE CARACTER DE TRIMIS
                    int codBaudot;
                    boolean esteDiddle = textCurent.isEmpty();

                    if (esteDiddle) {
                        if (!folosesteDiddle) {
                            // Dacă Diddle e oprit, stăm în Mark (Idle) fără bit-banging activ
                            setHardwarePtt(true);
                            setHardwareFsk(!demod.regimReverseSoftware); // Mark tone (Idle)
                            Thread.sleep(50); // Mică pauză de relaxare CPU
                            anchorTimeNanos = System.nanoTime(); // Resetăm ancora după pauză
                            continue;
                        }
                        codBaudot = 0x1F; // Letters Shift (Diddle standard)
                    } else {
                        // Avem text real
                        char c = Character.toUpperCase(textCurent.charAt(indexCaracter));
                        indexCaracter++;

                        // Log în Terminal
                        if (indexCaracter == 1) {
                            logTxTerminal(textCurent);
                        }

                        // Encodăm caracterul (poate genera Shift + Cod)
                        int[] coduri = txBaudotCodec.encode(c);
                        for (int cod : coduri) {
                            anchorTimeNanos = trimiteCodBaudotMetronom(cod, anchorTimeNanos);
                        }

                        // DACĂ am terminat un bloc (Macro/SEND), adăugăm pauză de demarcație
                        if (indexCaracter >= textCurent.length() && textCurent.length() > 1) {
                            actualizeazaLabelSend();
                            // Sync final + pauză 300ms (aprox 14 biți)
                            anchorTimeNanos = trimiteCodBaudotMetronom(0x1F, anchorTimeNanos);
                            long targetDelay = anchorTimeNanos + 300_000_000L;
                            while (System.nanoTime() < targetDelay && !opreteTxRttySolicitata) {
                                if (targetDelay - System.nanoTime() > 5_000_000L) Thread.sleep(5);
                            }
                            anchorTimeNanos = System.nanoTime();
                        }
                        continue;
                    }

                    // 3. TRANSMISIE CARACTER (Diddle)
                    anchorTimeNanos = trimiteCodBaudotMetronom(codBaudot, anchorTimeNanos);
                }
            } catch (Exception e) {
                android.util.Log.e("RTTY_METRONOM", "Eroare worker", e);
            } finally {
                if (!isPttManualOn) {
                    isTransmittingRtty = false;
                    setHardwarePtt(false);
                    setHardwareFsk(false);
                    actualizeazaUiTxStare(false);
                }
                workerTxRttyActiv.set(false);
                actualizeazaLabelSend();
            }
        }, "RTTY-Metronom-Worker");
        threadWorkerTxRtty.start();
    }

    private void logTxTerminal(String text) {
        Activity activity = (Activity) context;
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            if (tvRttyTerminal != null) {
                tvRttyTerminal.append(android.text.Html.fromHtml(
                        "<br><font color='#FFFF00' face='monospace'>TX: " + text.trim() + "</font><br>",
                        android.text.Html.FROM_HTML_MODE_LEGACY));
                if (svRttyScroll != null) svRttyScroll.post(() -> svRttyScroll.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    /**
     * Trimite un cod Baudot de 5 biți cu timing precis 1-5-1.5 (Start-Data-Stop).
     * @return Momentul de timp (nanos) la care s-a încheiat bitul de stop.
     */
    private long trimiteCodBaudotMetronom(int codBaudot, long startAnchorNanos) {
        boolean reverse = demod.regimReverseSoftware;
        long timeNext = startAnchorNanos;

        // 1. START BIT (Space)
        setHardwareFsk(reverse);
        timeNext += BIT_DURATION_NANOS;
        waitPreciseNanos(timeNext);

        // 2. DATA BITS (5 biți, LSB first)
        for (int i = 0; i < 5; i++) {
            boolean bitVal = ((codBaudot >> i) & 1) == 1;
            setHardwareFsk(bitVal ? !reverse : reverse);
            timeNext += BIT_DURATION_NANOS;
            waitPreciseNanos(timeNext);
        }

        // 3. STOP BIT (1.5 biți Mark)
        setHardwareFsk(!reverse);
        timeNext += (long) (BIT_DURATION_NANOS * 1.5);
        waitPreciseNanos(timeNext);

        return timeNext;
    }

    /**
     * Helper pentru a actualiza culoarea și textul butonului PTT pe thread-ul UI.
     */
    private void actualizeazaUiTxStare(boolean isTx) {
        Activity activity = (Activity) context;
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            if (btnRttyPttToggle != null) {
                btnRttyPttToggle.setBackgroundColor(Color.parseColor(isTx ? "#B71C1C" : "#424242"));
                btnRttyPttToggle.setText(isTx ? "TX" : "PTT");
            }
        });
    }

    // ============================================================
    // CURATARE RESURSE LA INCHIDEREA APLICATIEI
    // Apelata din MainActivity.onDestroy()
    // ============================================================
    public void eliberareResurse() {
        // 1. Oprim decodarea (flag-ul opreste bucla while din thread)
        decodareRttyActiva = false;

        // 2. CORECTIE: golim coada TX si semnalam worker-ului sa opreasca mesajul curent
        // cat mai curand (intre caractere, nu in mijlocul unui cod Baudot, ca sa nu lasam
        // radioul intr-o stare inconsistenta), apoi asteptam efectiv sa se opreasca -
        // inainte doar setam isTransmittingRtty=false si continuam imediat, fara nicio
        // garantie ca threadul chiar s-a oprit (risc real daca EXIT venea in mijlocul
        // unei transmisii, threadul putea scrie pe port dupa ce RadioManager il inchidea).
        coadaMesajeTxRtty.clear();
        opreteTxRttySolicitata = true;
        isTransmittingRtty = false;
        setHardwarePtt(false);
        setHardwareFsk(false);

        Thread workerTx = threadWorkerTxRtty;
        if (workerTx != null && workerTx.isAlive()) {
            try {
                workerTx.join(500);
            } catch (InterruptedException ignored) {}
        }

        // 3. CORECTIE: asteptam (cu timeout scurt) si threadul de decodare audio, in loc
        // sa continuam imediat presupunand ca s-a oprit doar pentru ca am setat flag-ul.
        Thread decodThread = threadDecodareRtty;
        if (decodThread != null && decodThread.isAlive()) {
            try {
                decodThread.join(500);
            } catch (InterruptedException ignored) {}
        }

        // 4. Reciclam bitmap-urile Waterfall
        if (waterfall != null) {
            waterfall.eliberareBitmap();
        }

        // 5. Golim buffer-ul de cuvinte
        callsignDetector.resetBuffer();
    }

    // ============================================================
    // SALVARE / ÎNCĂRCARE SETĂRI AUDIO
    // ============================================================
    private void salveazaSetareRtty(String cheie, Object valoare) {
        SharedPreferences.Editor editor = context.getSharedPreferences(EcranSetariProprii.PREFS_NAME, Context.MODE_PRIVATE).edit();
        if (valoare instanceof Integer) editor.putInt(cheie, (Integer) valoare);
        else if (valoare instanceof Boolean) editor.putBoolean(cheie, (Boolean) valoare);
        editor.apply();
    }

    private void incarcaSetariRtty() {
        isInitialLoading = true;
        SharedPreferences prefs = context.getSharedPreferences(EcranSetariProprii.PREFS_NAME, Context.MODE_PRIVATE);

        demod.folosesteDnr = prefs.getBoolean(PREF_RTTY_DNR_ON, false);
        int dnrBwProgress = prefs.getInt(PREF_RTTY_DNR_BW, 9); // ~600 Hz
        demod.latimeDnrHz = 250.0 + dnrBwProgress * (1250.0 / 20.0);

        demod.folosesteSnrGating = prefs.getBoolean(PREF_RTTY_SNRG_ON, false);
        int snrGKProgress = prefs.getInt(PREF_RTTY_SNRG_K, 3); // ~x1.3
        demod.snrGatingK = 1.0 + snrGKProgress * (2.0 / 20.0);

        folosesteRtsPtPtt = prefs.getBoolean(PREF_RTTY_USE_RTS_PTT, false);

        // Actualizăm UI
        if (btnDnr != null) {
            btnDnr.setBackgroundColor(Color.parseColor(demod.folosesteDnr ? "#00897B" : "#757575"));
        }
        if (sbDnrLatime != null) {
            sbDnrLatime.setProgress(dnrBwProgress);
            tvDnrValoare.setText("~" + Math.round(demod.latimeDnrHz) + " Hz");
        }
        if (btnSnrGate != null) {
            btnSnrGate.setBackgroundColor(Color.parseColor(demod.folosesteSnrGating ? "#00897B" : "#757575"));
        }
        if (sbSnrGateK != null) {
            sbSnrGateK.setProgress(snrGKProgress);
            tvSnrGateValoare.setText(String.format("x%.1f", demod.snrGatingK));
        }
        if (btnToggleLine != null) {
            btnToggleLine.setText(folosesteRtsPtPtt ? "USE: RTS" : "USE: DTR");
        }
        demod.calculeazaCoeficientiDnr(demod.sampleRateFiltre);
        isInitialLoading = false;
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