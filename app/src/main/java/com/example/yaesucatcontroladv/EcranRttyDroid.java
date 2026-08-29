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
    private boolean regimReverseSoftware = false;
    private short[] miniBufferFft = new short[512];

    // Coeficienti
    private double frecventaTunedBaza = 2125.0;
    private double qMarkA, qSpaceA;
    private double afcRotRe = 1.0, afcRotIm = 0.0; // increment de rotatie per esantion: cos(w), sin(w)
    private double sampleRateFiltre = 48000.0;
    private double rCheb = 0.992;
    private volatile double pragSensibilitate = 0.05;
    private int modUrmarireFrecventa = 0;

    // FUNCTIONALITATE NOUA: Noise Blanker (NB) - suprima pulsurile scurte de zgomot
    // impulsiv (QRN) direct in eșantioanele audio brute, INAINTE de AFC/Chebyshev/
    // matched filter, ca tot lantul de decodare sa beneficieze de intrare mai curata.
    private volatile boolean folosesteNoiseBlanker = false;
    private volatile double nbFactorPrag = 5.0; // cat de mult peste media recenta declanseaza blanking-ul
    private Button btnNoiseBlanker;
    private LinearLayout rAndSliderNoiseBlanker;
    private SeekBar sbNoiseBlanker;
    private TextView tvNoiseBlankerValoare;

    // DNR (Digital Noise Reduction) - filtru bandpass biquad
    private volatile boolean folosesteDnr = false;
    private volatile double latimeDnrHz = 600.0;
    private double dnrB0 = 1.0, dnrB1 = 0.0, dnrB2 = 0.0;
    private double dnrA1 = 0.0, dnrA2 = 0.0;
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

    // SNR Gating - îngheață decizia de bit la energie insuficientă
    private volatile boolean folosesteSnrGating = false;
    private volatile double snrGatingK = 1.5;
    private Button btnSnrGate;
    private LinearLayout rAndSliderSnrGate;
    private SeekBar sbSnrGateK;
    private TextView tvSnrGateValoare;

    // OPTIMIZARE: constanta de clasa (nu doar variabila locala in threadul de decodare),
    // ca sa poata fi folosita si de slider-ul "Latime Filtru" pentru a lega podeaua
    // minima de banda de rata reala de simbol (evitarea ISI la benzi prea inguste).
    private static final double BAUD_RTTY = 45.45;
    private static final long BIT_DURATION_NANOS = (long) ((1.0 / BAUD_RTTY) * 1_000_000_000L); // ~22.002.200 ns
    private static final double RCHEB_MIN = 0.985; // capatul larg al slider-ului (~115 Hz)

    // Matched Filter & SNR
    // OPTIMIZARE: nu mai pastram sabloanele sin/cos pe toata fereastra (O(N) per esantion),
    // ci doar constantele de rotatie pentru o corelatie glisanta recursiva O(1) per esantion
    // (echivalent matematic cu corelatia completa pe fereastra, actualizat incremental).
    private volatile boolean folosesteMatchedFilter = false;
    // CIZELARE: punctul de esantionare al bitului pentru Matched Filter (fractie din
    // durata unui bit, la care se face citirea) - vezi explicatia detaliata din
    // bucla de decodare RTTY, langa PRAG_CONFIRMARE_START. Camp de clasa (nu constanta
    // locala in thread-ul audio) tocmai ca sa poata fi ajustat live din UI (slider-ul
    // "Latime Filtru", repurpozat pentru modul MF - vezi actualizeazaStareSliderLatime()).
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

    /**
     * Calculează coeficienții pentru filtrul bandpass biquad DNR (RBJ cookbook).
     */
    private void calculeazaCoeficientiDnr(double sampleRate) {
        double shift = regimReverseSoftware ? -170.0 : 170.0;
        double f0 = frecventaTunedBaza + shift / 2.0; // centrat între Mark și Space
        double q = f0 / Math.max(latimeDnrHz, 1.0);
        double w0 = 2.0 * Math.PI * f0 / sampleRate;
        double alpha = Math.sin(w0) / (2.0 * q);
        double cosW0 = Math.cos(w0);

        double b0 = alpha, b1 = 0.0, b2 = -alpha;
        double a0 = 1.0 + alpha, a1 = -2.0 * cosW0, a2 = 1.0 - alpha;

        dnrB0 = b0 / a0; dnrB1 = b1 / a0; dnrB2 = b2 / a0;
        dnrA1 = a1 / a0; dnrA2 = a2 / a0;
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
        if (!folosesteMatchedFilter || latimeDnrHz >= DNR_LATIME_MIN_HZ_MATCHED_FILTER) return;

        int progresMinim = progresMinimDnrPentruMF();
        latimeDnrHz = 250.0 + progresMinim * (1250.0 / 20.0);
        calculeazaCoeficientiDnr(sampleRateFiltre);

        if (sbDnrLatime != null && tvDnrValoare != null) {
            sbDnrLatime.setProgress(progresMinim);
            tvDnrValoare.setText("~" + Math.round(latimeDnrHz) + " Hz");
            salveazaSetareRtty(PREF_RTTY_DNR_BW, progresMinim);
        }
    }

    private void sincronizeazaSetariReceptie() {
        double wAfc = 2.0 * Math.PI * frecventaTunedBaza / sampleRateFiltre;
        afcRotRe = Math.cos(wAfc);
        afcRotIm = Math.sin(wAfc);

        calculeazaCoeficientiDnr(sampleRateFiltre);

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

                        // Macrourile se leagă mereu prin spațiu - dacă vrei o linie nouă
                        // explicit, apasă butonul M-CR (inserează \n la poziția cursorului).
                        etRttyInputLive.append(textFinal + " ");
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
                (int) frecventaTunedBaza, snrTextLive));
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
        waterfall.setTunedFrequency((int) frecventaTunedBaza);
        waterfall.setShiftHz(170);
        waterfall.setOnFrequencyTappedListener(frequencyHz -> {
            frecventaTunedBaza = frequencyHz;
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
        llTopContent.addView(rAndControaleRtty);

        // RAND 2: TOGGLE DECODER & TRACK
        LinearLayout rAndDecoderToggle = new LinearLayout(context);
        rAndDecoderToggle.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams pRandDecoder = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pRandDecoder.setMargins(0, 0, 0, 10);
        rAndDecoderToggle.setLayoutParams(pRandDecoder);

        btnDecoderToggle = new Button(context);
        btnDecoderToggle.setText(folosesteMatchedFilter ? "DECODER: MATCHED FILTER" : "DECODER: CHEBYSHEV");
        btnDecoderToggle.setBackgroundColor(Color.parseColor(folosesteMatchedFilter ? "#6A1B9A" : "#00695C"));
        btnDecoderToggle.setTextColor(Color.WHITE);
        btnDecoderToggle.setTypeface(null, Typeface.BOLD);
        btnDecoderToggle.setTextSize(10);
        LinearLayout.LayoutParams pBtnDecoder = new LinearLayout.LayoutParams(0, 110, 1f);
        pBtnDecoder.setMargins(5, 0, 5, 0);
        btnDecoderToggle.setLayoutParams(pBtnDecoder);
        btnDecoderToggle.setOnClickListener(v -> {
            folosesteMatchedFilter = !folosesteMatchedFilter;
            btnDecoderToggle.setText(folosesteMatchedFilter ? "DECODER: MATCHED FILTER" : "DECODER: CHEBYSHEV");
            btnDecoderToggle.setBackgroundColor(Color.parseColor(folosesteMatchedFilter ? "#6A1B9A" : "#00695C"));
            if (folosesteMatchedFilter && mfBuffer == null) {
                initMatchedFilterTemplates(48000, BAUD_RTTY);
            }
            recalculeazaShabloaneMF();
            aplicaClampDnrPentruMatchedFilter();
            solicitaResetFiltre = true;
            actualizeazaStareSliderLatime();
            Toast.makeText(context, "Decoder: " + (folosesteMatchedFilter ? "Matched Filter" : "Chebyshev"),
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
            modUrmarireFrecventa = (modUrmarireFrecventa + 1) % 3;
            if (modUrmarireFrecventa == 0) {
                btnTrackToggle.setText("OFF");
                btnTrackToggle.setBackgroundColor(Color.parseColor("#757575"));
            } else if (modUrmarireFrecventa == 1) {
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
                        folosesteNoiseBlanker = !folosesteNoiseBlanker;
                        btnNoiseBlanker.setBackgroundColor(Color.parseColor(folosesteNoiseBlanker ? "#007ACC" : "#757575"));
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
                        folosesteDnr = !folosesteDnr;
                        btnDnr.setBackgroundColor(Color.parseColor(folosesteDnr ? "#00897B" : "#757575"));
                        if (!isInitialLoading) salveazaSetareRtty(PREF_RTTY_DNR_ON, folosesteDnr);
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
                        folosesteSnrGating = !folosesteSnrGating;
                        btnSnrGate.setBackgroundColor(Color.parseColor(folosesteSnrGating ? "#00897B" : "#757575"));
                        if (!isInitialLoading) salveazaSetareRtty(PREF_RTTY_SNRG_ON, folosesteSnrGating);
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
                nbFactorPrag = 2.0 + (progress * (10.0 / 20.0));
                tvNoiseBlankerValoare.setText(String.format("x%.1f", nbFactorPrag));
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
                if (folosesteMatchedFilter) {
                    int progresMinim = progresMinimDnrPentruMF();
                    if (progresEfectiv < progresMinim) {
                        progresEfectiv = progresMinim;
                        ajustareProgramaticaDnr = true;
                        seekBar.setProgress(progresEfectiv); // repozitioneaza vizual thumb-ul la minimul permis
                        ajustareProgramaticaDnr = false;
                    }
                }

                latimeDnrHz = 250.0 + progresEfectiv * (1250.0 / 20.0);
                tvDnrValoare.setText("~" + Math.round(latimeDnrHz) + " Hz");
                calculeazaCoeficientiDnr(sampleRateFiltre);
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
                snrGatingK = 1.0 + progress * (2.0 / 20.0);
                tvSnrGateValoare.setText(String.format("x%.1f", snrGatingK));
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
        sbSensibilitate.setProgress((int) Math.round(pragSensibilitate * 100));
        sbSensibilitate.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tvSensibilitateValoare = new TextView(context);
        tvSensibilitateValoare.setText(String.format("%d%%", (int) Math.round(pragSensibilitate * 100)));
        tvSensibilitateValoare.setTextColor(Color.parseColor("#00E5FF"));
        tvSensibilitateValoare.setTextSize(13);
        rAndSensibilitate.addView(sbSensibilitate);
        rAndSensibilitate.addView(tvSensibilitateValoare);
        sbSensibilitate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                pragSensibilitate = progress / 100.0;
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
                double rChebMax = 1.0 - (bwMinHz * 2.0 * Math.PI / sampleRateFiltre);
                rCheb = RCHEB_MIN + progress * ((rChebMax - RCHEB_MIN) / 20.0);
                int bwHz = (int) Math.round((1.0 - rCheb) * sampleRateFiltre / (2 * Math.PI));
                tvFiltruValoare.setText("~" + bwHz + " Hz");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { solicitaResetFiltre = true; }
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
            double fazaVectRe = 1.0, fazaVectIm = 0.0; // Vector de faza complex (inlocuieste fazaAFC)
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
            // Prag confirmat de utilizator pe semnal real cu QRN/QRM (0.7 a mers mai bine
            // decat 0.6 la respingerea puseurilor scurte de zgomot).
            final int PRAG_CONFIRMARE_START = (int) Math.round(samplesPerBit * 0.7);

            // ISTORIC: am experimentat cu eșantionarea aproape de finalul bitului pentru
            // Matched Filter (teoria: fereastra corelatorului, care are exact lungimea unui
            // simbol, ar da o citire mai "pura" langa tranzitie decat la mijloc). Testat pe
            // hardware real, ideea a stricat decodarea: marja de timp fata de jitter/drift
            // (acumulat pe cei 7.5 biti ai unui caracter, plus intarzierea de grup a
            // filtrelor DNR/NB) conteaza mult mai mult decat "puritatea" teoretica a
            // ferestrei. Revenit definitiv la esantionare la mijlocul bitului (0.5) pentru
            // AMBII decodori - ofera marja maxima si simetrica fata de orice eroare de timing.
            final double PCT_ESANTIONARE = 0.5;


            int contorEroriFramingConsecutive = 0;
            // Numar de cadre invalide consecutive (stop-bit gresit) care declanseaza
            // un reset complet al starii de decodare - semn de QRM/zgomot sustinut sau
            // desincronizare majora, nu doar un cadru izolat corupt.
            final int PRAG_ERORI_FRAMING_RESYNC = 5;

            // FUNCTIONALITATE NOUA: Noise Blanker - stare locala pe threadul de decodare.
            // nbNivelMediu urmareste amplitudinea "normala" recenta a semnalului audio brut
            // (medie EMA a |sample|), actualizata DOAR din esantioane NEsuprimate (ca sa nu
            // se auto-contamineze cu propriile pulsuri de zgomot pe care le respinge).
            double nbNivelMediu = 0.05;
            double nbUltimulEsantionValid = 0;
            final double NB_EMA_ALPHA = 0.0008;
            // CIZELARE: supapa de siguranta - daca blanking-ul dureaza prea mult consecutiv,
            // nu mai poate fi vorba de un puls real de zgomot impulsiv (QRN/ignition noise
            // dureaza de regula sub 1-2ms) - e mai probabil un salt real de amplitudine
            // (AGC-ul statiei reactionand, schimbare de RF/AF gain, retunare pe semnal mai
            // puternic) pe care NB l-a interpretat gresit ca zgomot. Fortam acceptarea
            // esantionului dupa acest prag, ca nbNivelMediu sa poata invata noul nivel si
            // sa iasa din blocare, in loc sa ramana inghetat la nesfarsit.
            int nbContorBlankConsecutiv = 0;
            final int NB_MAX_BLANK_CONSECUTIV = 150; // ~3ms la 48kHz

            double dnrX1 = 0, dnrX2 = 0, dnrY1 = 0, dnrY2 = 0; // stare filtru bandpass DNR

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

            // CIZELARE: cand SNR Gating ingheata decizia (energie insuficienta), nu mai
            // pastram pur si simplu semnul vechi al integratorului - daca fade-ul incepe
            // chiar in mijlocul unei tranzitii Space->Mark, am ramane "blocati" fals pe
            // Space pe toata durata fade-ului, ceea ce produce framing de gunoi quando
            // semnalul revine (RTTY asteapta Mark = idle intre caractere). In loc sa
            // "inghetam" valoarea, o derivam lin catre o tinta pozitiva (Mark) - suficient
            // de departe de zero ca sa fie robusta indiferent de ramura din decizia de bit
            // de mai jos - pe durata aproximativa a unui simbol RTTY (~22ms), evitand atat
            // framing-ul de gunoi cat si o discontinuitate brusca in integrator.
            final double SNR_GATING_MARK_BIAS_ALPHA = 0.001; // ~1000 esantioane (~20ms) pana converge

            // CORECTIE: ținta era inainte "nivelZgomotMediu * pragSensibilitate * 2.0" -
            // dar pragSensibilitate e sliderul de "Sensibilitate" din UI, ajustabil de
            // utilizator intre 0% si 100%. Daca utilizatorul il seta la 0%, tinta devenea
            // EXACT 0, integratorul deriva spre zero (nu spre Mark), iar decizia finala
            // (Math.abs(integratCurent) < prag=0 -> fals; apoi integratCurent>0 -> 0>0 = fals)
            // cadea pe SPACE - exact opusul bias-ului intentionat, la o setare complet
            // valida de UI. Decuplam tinta de pragSensibilitate si folosim o marja FIXA,
            // independenta de orice slider al utilizatorului, ca sa garanteze intotdeauna
            // o valoare strict pozitiva.
            final double SNR_GATING_MARK_BIAS_MARGINE = 0.1;

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
                    fazaVectRe = 1.0; fazaVectIm = 0.0;
                    bitCounter = 0;
                    currentByte = 0;
                    contorConfirmareStart = 0;
                    contorEroriFramingConsecutive = 0;
                    // CORECTIE: nbNivelMediu/nbUltimulEsantionValid NU se mai reseteaza aici.
                    // Erau resetate la orice schimbare de setare neinrudita (latime DNR, latime
                    // filtru Chebyshev etc.), aducandu-le la valoarea minuscula implicita 0.05 -
                    // daca semnalul receptionat avea deja amplitudine normala peste
                    // 0.05*nbFactorPrag (foarte probabil pe un semnal decent), PRIMUL esantion
                    // de dupa reset era deja blocat de NB, iar nbNivelMediu ramanea inghetat la
                    // 0.05 la nesfarsit (se actualizeaza DOAR din esantioane acceptate - daca
                    // niciunul nu mai e acceptat, nu mai are din ce sa invete) - o blocare
                    // completa si permanenta a decodarii pana la un restart de aplicatie. Nivelul
                    // de amplitudine invatat de NB nu depinde de filtre/clock recovery, deci nu
                    // are niciun motiv sa fie resetat aici (acelasi principiu ca la
                    // nivelZgomotMediu, care nici el nu se reseteaza la solicitaResetFiltre).
                    nbContorBlankConsecutiv = 0;
                    noiseFloor = 1e-9; // Resetăm și podeaua de zgomot pentru a recalibra SNR-G
                    dnrX1 = 0; dnrX2 = 0; dnrY1 = 0; dnrY2 = 0;
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
                            short sample = audioBuffer[j * WATERFALL_FACTOR_DECIMARE];
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

                    // FUNCTIONALITATE NOUA: Noise Blanker - suprima pulsurile scurte de
                    // zgomot impulsiv (QRN) INAINTE ca esantionul sa alimenteze AFC/
                    // Chebyshev/matched filter, ca tot lantul de decodare sa beneficieze.
                    // CORECTIE (gaina si oul): nbNivelMediu se actualiza DOAR cat timp NB era
                    // activ. Daca userul decoda o vreme cu NB OPRIT (implicit la pornire),
                    // nbNivelMediu ramanea inghetat la valoarea minuscula initiala (0.05) -
                    // la PRIMA activare a NB, pragul (0.05*nbFactorPrag) era aproape sigur sub
                    // nivelul real al semnalului, blocand imediat primele esantioane, iar media
                    // nu mai avea din ce sa invete nivelul real (se actualizeaza doar din
                    // esantioane acceptate) - blocare completa, deterministica, la fiecare
                    // prima activare a NB. Acum calibram nbNivelMediu mereu, din semnalul brut,
                    // INDIFERENT daca gating-ul NB e activ - la activare, pragul e deja realist,
                    // fara nicio fereastra de "orbire". Supapa de siguranta (NB_MAX_BLANK_
                    // CONSECUTIV) ramane ca ultima plasa, pentru salturi bruste in timpul
                    // receptiei (AGC statie, retunare), nu ca mecanism principal de calibrare.
                    double sampleAbsNb = Math.abs(sample);
                    if (folosesteNoiseBlanker) {
                        double pragNb = nbNivelMediu * nbFactorPrag;
                        if (sampleAbsNb > pragNb && nbContorBlankConsecutiv < NB_MAX_BLANK_CONSECUTIV) {
                            // Puls detectat: "hold" - repetam ultimul esantion valid, mai
                            // lin decat sa punem pe zero (evita un "click" spectral brusc).
                            sample = nbUltimulEsantionValid;
                            nbContorBlankConsecutiv++;
                        } else {
                            nbUltimulEsantionValid = sample;
                            nbNivelMediu = (NB_EMA_ALPHA * sampleAbsNb) + ((1.0 - NB_EMA_ALPHA) * nbNivelMediu);
                            nbContorBlankConsecutiv = 0;
                        }
                    } else {
                        nbNivelMediu = (NB_EMA_ALPHA * sampleAbsNb) + ((1.0 - NB_EMA_ALPHA) * nbNivelMediu);
                        nbUltimulEsantionValid = sample;
                        nbContorBlankConsecutiv = 0;
                    }

                    // DNR: filtru bandpass biquad centrat Mark/Space - reduce zgomotul de banda larga
                    if (folosesteDnr) {
                        double dnrOut = dnrB0 * sample + dnrB1 * dnrX1 + dnrB2 * dnrX2
                                - dnrA1 * dnrY1 - dnrA2 * dnrY2;
                        dnrX2 = dnrX1; dnrX1 = sample;
                        dnrY2 = dnrY1; dnrY1 = dnrOut;
                        sample = dnrOut;
                    }

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
                    double newPhaseRe = fazaVectRe * afcRotRe - fazaVectIm * afcRotIm;
                    double newPhaseIm = fazaVectRe * afcRotIm + fazaVectIm * afcRotRe;
                    fazaVectRe = newPhaseRe;
                    fazaVectIm = newPhaseIm;

                    I_acc += sample * fazaVectRe;
                    Q_acc += sample * fazaVectIm;
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
                                    double wAfc = 2.0 * Math.PI * frecventaTunedBaza / sampleRate;
                                    afcRotRe = Math.cos(wAfc);
                                    afcRotIm = Math.sin(wAfc);

                                    calculeazaCoeficientiDnr(sampleRate);

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

                        // Renormalizare anti-drift a vectorului de faza
                        double normAfc = Math.sqrt(fazaVectRe * fazaVectRe + fazaVectIm * fazaVectIm);
                        if (normAfc > 1e-9) {
                            fazaVectRe /= normAfc;
                            fazaVectIm /= normAfc;
                        }
                    }

                    double magTotala = magMark + magSpace;

                    if (magTotala < noiseFloor) {
                        noiseFloor = magTotala;
                    } else {
                        noiseFloor = 0.999 * noiseFloor + 0.001 * magTotala;
                    }

                    nivelZgomotMediu = (0.0005 * magTotala) + (0.9995 * nivelZgomotMediu);

                    // SNR Gating: energie insuficienta -> derivam lin spre Mark (idle RTTY),
                    // in loc sa inghetam pe orice semn avea integratorul chiar inainte de
                    // fade (vezi comentariul de la SNR_GATING_MARK_BIAS_ALPHA mai sus).
                    double integratCurent;
                    if (folosesteSnrGating && magTotala < noiseFloor * snrGatingK) {
                        double tintaMark = Math.max(nivelZgomotMediu, 1e-9) * SNR_GATING_MARK_BIAS_MARGINE;
                        integratCurent = integratVech + (tintaMark - integratVech) * SNR_GATING_MARK_BIAS_ALPHA;
                    } else {
                        integratCurent = (alfaIntegrator * diferentaBruta) + ((1.0 - alfaIntegrator) * integratVech);
                    }
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
                        double punctEstantionareTinta = (bitCounter == 0)
                                ? (samplesPerBit * (1.0 + PCT_ESANTIONARE))
                                : samplesPerBit;

                        if (fazaCeasAcumulator >= punctEstantionareTinta) {
                            fazaCeasAcumulator -= punctEstantionareTinta;
                            if (bitCounter < 5) {
                                if (bitCurent == 1) currentByte |= (1 << bitCounter);
                                bitCounter++;
                            } else {
                                // Am ajuns la stop-bit (bitCounter == 5)
                                if (bitCurent == 1) {
                                    // Stop bit valid
                                    proceseazaCodBaudot(currentByte & 0x1F);
                                    contorEroriFramingConsecutive = 0;
                                } else {
                                    // Stop bit invalid (framing error)
                                    contorEroriFramingConsecutive++;
                                    if (contorEroriFramingConsecutive >= PRAG_ERORI_FRAMING_RESYNC) {
                                        solicitaResetFiltre = true;
                                        contorEroriFramingConsecutive = 0;
                                    }
                                }
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
                            setHardwareFsk(!regimReverseSoftware); // Mark tone (Idle)
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
        boolean reverse = regimReverseSoftware;
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

        folosesteDnr = prefs.getBoolean(PREF_RTTY_DNR_ON, false);
        int dnrBwProgress = prefs.getInt(PREF_RTTY_DNR_BW, 9); // ~600 Hz
        latimeDnrHz = 250.0 + dnrBwProgress * (1250.0 / 20.0);

        folosesteSnrGating = prefs.getBoolean(PREF_RTTY_SNRG_ON, false);
        int snrGKProgress = prefs.getInt(PREF_RTTY_SNRG_K, 3); // ~x1.3
        snrGatingK = 1.0 + snrGKProgress * (2.0 / 20.0);

        folosesteRtsPtPtt = prefs.getBoolean(PREF_RTTY_USE_RTS_PTT, false);

        // Actualizăm UI
        if (btnDnr != null) {
            btnDnr.setBackgroundColor(Color.parseColor(folosesteDnr ? "#00897B" : "#757575"));
        }
        if (sbDnrLatime != null) {
            sbDnrLatime.setProgress(dnrBwProgress);
            tvDnrValoare.setText("~" + Math.round(latimeDnrHz) + " Hz");
        }
        if (btnSnrGate != null) {
            btnSnrGate.setBackgroundColor(Color.parseColor(folosesteSnrGating ? "#00897B" : "#757575"));
        }
        if (sbSnrGateK != null) {
            sbSnrGateK.setProgress(snrGKProgress);
            tvSnrGateValoare.setText(String.format("x%.1f", snrGatingK));
        }
        if (btnToggleLine != null) {
            btnToggleLine.setText(folosesteRtsPtPtt ? "USE: RTS" : "USE: DTR");
        }
        calculeazaCoeficientiDnr(sampleRateFiltre);
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