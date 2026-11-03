package com.example.yaesucatcontroladv;

/**
 * Demodulatorul RTTY (45.45 baud, shift 170 Hz), extras din bucla de decodare a lui
 * EcranRttyDroid. Fara dependente Android: primeste esantioane PCM 16-bit si livreaza
 * coduri Baudot de 5 biti prin Listener - deci poate fi testat cu semnal sintetic
 * (vezi RttyDemodulatorTest), nu doar pe aer.
 *
 * Lantul, in ordine: DC blocker -> Noise Blanker -> DNR (bandpass) -> detector Mark/Space
 * (rezonatoare Chebyshev sau Matched Filter) -> AFC -> SNR gating / integrator ->
 * decizie de bit -> clock recovery + framing (start, 5 biti, stop).
 *
 * Configurarea (campurile non-private de mai jos) e scrisa din thread-ul UI si citita din
 * thread-ul audio, la fel ca inainte de extragere, cand erau campuri ale ecranului.
 */
public class RttyDemodulator {

    public interface Listener {
        /** Cod Baudot de 5 biti cu stop-bit valid. */
        void onCodBaudot(int cod);

        /** Apelat periodic (~ de 10 ori pe secunda la 48 kHz) cu SNR-ul si frecventa Mark curenta. */
        void onStare(String snrText, int frecventaMarkHz);
    }

    public static final double BAUD_RTTY = 45.45;
    public static final double SHIFT_HZ = 170.0;

    private Listener listener;

    // ---- Configurare (din UI) ----
    boolean regimReverseSoftware = false;
    double frecventaTunedBaza = 2125.0;
    double sampleRateFiltre = 48000.0;
    double rCheb = 0.992;
    volatile double pragSensibilitate = 0.05;
    int modUrmarireFrecventa = 0;

    // Noise Blanker (NB) - suprima pulsurile scurte de zgomot impulsiv (QRN) direct in
    // esantioanele audio brute, INAINTE de AFC/Chebyshev/matched filter, ca tot lantul de
    // decodare sa beneficieze de intrare mai curata.
    volatile boolean folosesteNoiseBlanker = false;
    volatile double nbFactorPrag = 5.0; // cat de mult peste media recenta declanseaza blanking-ul

    // DNR (Digital Noise Reduction) - filtru bandpass biquad
    volatile boolean folosesteDnr = false;
    volatile double latimeDnrHz = 600.0;
    private double dnrB0 = 1.0, dnrB1 = 0.0, dnrB2 = 0.0;
    private double dnrA1 = 0.0, dnrA2 = 0.0;

    // SNR Gating - ingheata decizia de bit la energie insuficienta
    volatile boolean folosesteSnrGating = false;
    volatile double snrGatingK = 1.5;

    volatile boolean folosesteMatchedFilter = false;
    volatile boolean solicitaResetFiltre = false;

    /**
     * Filtru trece-jos (Butterworth ordin 2) pe diferenta Mark-Space, inainte de decizia de
     * bit. Fara el, decizia citeste un singur esantion dintr-un semnal practic nefiltrat:
     * integratorul (alfaIntegrator) are constanta de timp ~0.26 ms la 48 kHz, fata de un bit
     * de 22 ms, iar |y| al rezonatorului Chebyshev mai are si ripple la 2x frecventa tonului.
     *
     * 1x rata de baud a iesit optim in RttyDemodulatorTest (zgomot gaussian, 5 seed-uri):
     * Chebyshev la -3 dB/2.7kHz scade de la ~67% la ~0% CER, la -6 dB de la ~86% la ~16%;
     * Matched Filter castiga putin (la -9 dB ~37% -> ~31%). Ramane mai bun si cu acord
     * gresit cu +/-15 Hz sau baud 45.0/45.9. Testat si 30/60/90 Hz - mai slab. 0 = dezactivat.
     */
    double lpfDecizieHz = BAUD_RTTY;
    private final Biquad lpfMark = new Biquad();
    private final Biquad lpfSpace = new Biquad();

    /**
     * ATC (Automatic Threshold Correction), varianta "optimal ATC" a lui W7AY (cea din fldigi):
     * urmareste separat anvelopa si podeaua de zgomot a fiecarui ton si pondereaza decizia cu
     * cat de puternic e fiecare ton acum. La fading selectiv (Mark si Space se sting separat),
     * diferenta simpla Mark-Space ar fi trasa spre tonul puternic si ar rata bitii tonului slab.
     *
     * Masurat (Chebyshev, CER): fading selectiv 0.2 Hz la 10 dB 8.5% -> ~0.5%, la 0 dB
     * 25% -> ~16%; fading 1 Hz la 20 dB 14.5% -> ~5%. Costul: fara fading, la -3 dB
     * 0% -> ~2.5%, iar pe inregistrarea reala cu QRN (W6IWI) 34 -> 30 transmisii perfecte
     * din 60. De aceea e o optiune (ca in fldigi/MMTTY), oprita implicit.
     * Pe inregistrarea reala cu fading selectiv (W6IWI, 14086 kHz, 95 de transmisii):
     * 157 -> 104 caractere gresite, 72 -> 73 randuri perfecte. Actioneaza doar pe Chebyshev.
     */
    volatile boolean folosesteAtc = false;
    /**
     * Volant de caracter: la RTTY continuu (caractere lipite, cum trimit MMTTY/fldigi), dupa
     * cateva caractere valide decodorul prezice unde incepe urmatorul si nu mai cauta startul
     * oriunde - o descarcare sau o stingere nu mai poate muta sincronizarea.
     *
     * Masurat (Chebyshev, CER): zgomot -6 dB 19.7% -> 8.6%; fading 0.2 Hz + ATC la 0 dB
     * 15% -> 7.7%; inregistrarea reala cu QSB (W6IWI 14086 kHz) 157 -> 87 caractere gresite
     * (cu ATC 62). Tastare manuala si rafale cu pauze: identic cu detectia libera.
     *
     * Un mark la startul prezis inseamna pauza -> deblocare imediata. Varianta care sarea
     * peste startul lipsa (presupunand zgomot) castiga mai mult pe flux continuu, dar pierdea
     * caracterul de dupa fiecare pauza (~24% CER pe tastare), iar cu un prag de "incredere"
     * (doar dupa >=10 caractere lipite) strica tot rafalele de type-ahead (1-4% la 20 dB).
     * In schimb, grila veche ramane "fantoma" si reblocheaza imediat daca fluxul a continuat.
     */
    volatile boolean folosesteVolant = true;
    /** Caractere valide consecutive, la perioada constanta, pana se blocheaza volantul. */
    private static final int VOLANT_PRAG_BLOCARE = 3;
    /** Biti de stop invalizi consecutivi (cu volantul blocat) pana la deblocare. */
    private static final int VOLANT_MAX_RATARI = 3;
    /** Cat din abaterea frontului real fata de prezicere se aplica (0.25 / 1.0 au iesit mai slab). */
    private static final double VOLANT_CORECTIE = 0.5;
    /** Cat asteptam (in biti de la startul prezis) un space, inainte sa declaram pauza. */
    private static final double VOLANT_FEREASTRA_BITI = 1.0;
    /** Abaterea admisa a perioadei fata de 7 / 7.5 / 8 biti si fata de perioada invatata. */
    private static final double VOLANT_TOLERANTA_BITI = 0.2;
    private boolean fantomaActiva;
    private double fantomaGrila, fantomaPerioada;
    private boolean volantBlocat;
    private int volantPotriviri, ratariVolant;
    private boolean areStartAnterior;
    private double startCadru, startAnterior, startPrezis, perioadaCaracter;
    private long esantionAbs;

    private double atcMarkEnv, atcSpaceEnv, atcMarkZgomot, atcSpaceZgomot;

    // ---- Coeficienti derivati din frecventa ----
    private double qMarkA, qSpaceA;

    // Matched Filter & SNR
    // OPTIMIZARE: nu mai pastram sabloanele sin/cos pe toata fereastra (O(N) per esantion),
    // ci doar constantele de rotatie pentru o corelatie glisanta recursiva O(1) per esantion
    // (echivalent matematic cu corelatia completa pe fereastra, actualizat incremental).
    double[] mfBuffer; // buffer circular cu ultimele mfSamplesPerBit esantioane (pt. x_iesit)
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
    private double noiseFloor = 1e-9;

    // Matched Filter - doua corectii gasite pe inregistrari reale (W6IWI):
    //
    // 1. Fading selectiv. Corelatorul integreaza un bit intreg, deci la o tranzitie |Mark| si |Space|
    //    se schimba liniar pe toata durata bitului; cand un ton e mai slab, trecerea prin zero a
    //    diferentei se muta spre el, cu pana la 1/2 bit - fronturile de start si momentele de
    //    esantionare aluneca, iar caracterele pica la bitul de stop. Fiecare ton e raportat acum la
    //    amplitudinea lui, invatata la momentele de esantionare (fereastra aliniata pe bit, SNR maxim):
    //    trecerea prin zero ramane la mijlocul tranzitiei. Cu MF, startul se confirma dupa 0.35 biti
    //    (semnalul MF e deja integrat pe un bit; 0.7 biti respingea starturile scurtate de fading).
    //    Sintetic, fading 0.2-1 Hz: 24-32% -> 0.2-9% CER; zgomot fara fading: neschimbat.
    //
    // 2. Faza instabila in interiorul unui bit (flutter, propagare cu Doppler): doua jumatati de
    //    fereastra cu faze diferite se anuleaza partial in suma coerenta. In paralel ruleaza un
    //    corelator de 1/2 bit; doua jumatati consecutive adunate necoerent (|S1| + |S2|) nu depind de
    //    faza. Coerenta |S1 + S2| / (|S1| + |S2|) se masoara la fiecare bit; sub 0.93 (medie pe ~200
    //    de biti) se trece pe jumatati, peste 0.97 inapoi. Pe semnal stabil nu comuta.
    //
    // Limita: la flutter rapid (fading complex de 5-10 Hz) MF ramane slab (~25% CER). Acolo Chebyshev.
    private static final double MF_PRAG_START_BITI = 0.35;
    private static final double MF_DD_ALFA = 0.4;
    private static final double MF_DD_PODEA = 0.1;
    private static final double MF_PRAG_NECOERENT = 0.93;
    private static final double MF_PRAG_COERENT = 0.97;
    private static final double MF_ALFA_COERENTA = 0.005;
    private double ddAm, ddAs;
    private final CorelatorGlisant jumMark = new CorelatorGlisant(), jumSpace = new CorelatorGlisant();
    private double[] jumIstMark = new double[1], jumIstSpace = new double[1];
    private int jumPoz;
    private double magCohMark, magCohSpace, magNcMark, magNcSpace;
    double coerentaMedie = 1.0;
    boolean mfNecoerent;

    // ---- Stare de decodare (pornita de porneste()) ----
    private int sampleRate = 48000;
    private double samplesPerBit;
    private double mx1, mx2, sx1, sx2;
    private double dcBlockerInVech, dcBlockerOutVech;
    private double integratVech;
    private final double alfaIntegrator = 0.08;
    private double nivelZgomotMediu;
    private boolean inFrame;
    private double fazaCeasAcumulator;
    private int bitCounter;
    private int currentByte;

    // OPTIMIZARE: debounce la declansarea cadrului (start-bit). Inainte, un SINGUR
    // esantion decis "space" (posibil doar zgomot, pe semnal slab) declansa imediat
    // un cadru nou, la o faza arbitrara fata de bitstream-ul real - sursa principala
    // a simptomului "litere corecte izolate, text neinteligibil per total" (cadre
    // false + cadre reale defazate, ambele esuand testul stop-bitului de la final).
    // Acum cerem ca starea "space" sa persiste un numar minim de esantioane inainte
    // sa ne angajam pe un cadru nou.
    private int contorConfirmareStart;
    // Prag confirmat de utilizator pe semnal real cu QRN/QRM (0.7 a mers mai bine
    // decat 0.6 la respingerea puseurilor scurte de zgomot).
    private int pragConfirmareStart, pragConfirmareStartMf;

    // ISTORIC: am experimentat cu esantionarea aproape de finalul bitului pentru
    // Matched Filter (teoria: fereastra corelatorului, care are exact lungimea unui
    // simbol, ar da o citire mai "pura" langa tranzitie decat la mijloc). Testat pe
    // hardware real, ideea a stricat decodarea: marja de timp fata de jitter/drift
    // (acumulat pe cei 7.5 biti ai unui caracter, plus intarzierea de grup a
    // filtrelor DNR/NB) conteaza mult mai mult decat "puritatea" teoretica a
    // ferestrei. Revenit definitiv la esantionare la mijlocul bitului (0.5) pentru
    // AMBII decodori - ofera marja maxima si simetrica fata de orice eroare de timing.
    private static final double PCT_ESANTIONARE = 0.5;

    private int contorEroriFramingConsecutive;
    // Numar de cadre invalide consecutive (stop-bit gresit) care declanseaza
    // un reset complet al starii de decodare - semn de QRM/zgomot sustinut sau
    // desincronizare majora, nu doar un cadru izolat corupt.
    private static final int PRAG_ERORI_FRAMING_RESYNC = 5;

    // Noise Blanker - nbNivelMediu urmareste amplitudinea "normala" recenta a semnalului
    // audio brut (medie EMA a |sample|), actualizata DOAR din esantioane NEsuprimate (ca sa
    // nu se auto-contamineze cu propriile pulsuri de zgomot pe care le respinge).
    private double nbNivelMediu;
    private double nbUltimulEsantionValid;
    private static final double NB_EMA_ALPHA = 0.0008;
    // CIZELARE: supapa de siguranta - daca blanking-ul dureaza prea mult consecutiv,
    // nu mai poate fi vorba de un puls real de zgomot impulsiv (QRN/ignition noise
    // dureaza de regula sub 1-2ms) - e mai probabil un salt real de amplitudine
    // (AGC-ul statiei reactionand, schimbare de RF/AF gain, retunare pe semnal mai
    // puternic) pe care NB l-a interpretat gresit ca zgomot. Fortam acceptarea
    // esantionului dupa acest prag, ca nbNivelMediu sa poata invata noul nivel si
    // sa iasa din blocare, in loc sa ramana inghetat la nesfarsit.
    private int nbContorBlankConsecutiv;
    private static final int NB_MAX_BLANK_CONSECUTIV = 150; // ~3ms la 48kHz

    private double dnrX1, dnrX2, dnrY1, dnrY2; // stare filtru bandpass DNR

    // AFC / PLL - discriminator de frecventa pe ambele tonuri (vezi actualizeazaAfc).
    // CORECTIE: varianta veche corela audio-ul cu un fazor e^{+j w_mark t} pe ferestre de 2 biti:
    // semnul erorii iesea INVERSAT (reactie pozitiva - acordul fugea de semnal, +9..+13 Hz pe
    // inregistrarile W6IWI), fereastra amesteca Mark cu Space, iar pe semnal stabil conditia
    // magMark > 2 x nivelZgomotMediu bloca de altfel complet bucla.
    private final AfcTon afcMark = new AfcTon();
    private final AfcTon afcSpace = new AfcTon();
    private double afcSumRe, afcSumIm, afcSumAbs;
    private int afcContor, afcDominant, afcDominantDecodor;
    private boolean afcFereastraAnterioaraValida;
    private int afcFereastra;
    private int afcIntarziere;
    private double freqErrorFiltrat;
    /** Banda trece-jos a fiecarui ton in banda de baza (Hz): taie celalalt ton (la 170 Hz). */
    private static final double AFC_LPF_HZ = 50.0;
    /** Intarzierea pe care se masoara rotatia fazei: 4 ms (domeniu neambiguu +-125 Hz). */
    private static final double AFC_INTARZIERE_SEC = 0.004;
    /** Un ton "domina" daca anvelopa lui e de cel putin atatea ori mai mare decat a celuilalt. */
    private static final double AFC_DOMINANTA = 2.0;
    /** Coerenta minima |suma| / suma |.| a masuratorilor dintr-o fereastra (zgomotul da ~0). */
    private static final double AFC_COERENTA_MIN = 0.5;
    /** Fractia minima din fereastra in care un ton domina clar (semnal RTTY real, nu zgomot). */
    private static final double AFC_FRACTIE_DOMINANTA_MIN = 0.6;
    private static final double AFC_EMA_ALPHA = 0.25;
    private static final double AFC_MAX_CORECTIE_HZ = 5.0;
    private static final double AFC_PRAG_OUTLIER_HZ = 60.0;

    // CIZELARE: cand SNR Gating ingheata decizia (energie insuficienta), nu mai
    // pastram pur si simplu semnul vechi al integratorului - daca fade-ul incepe
    // chiar in mijlocul unei tranzitii Space->Mark, am ramane "blocati" fals pe
    // Space pe toata durata fade-ului, ceea ce produce framing de gunoi cand
    // semnalul revine (RTTY asteapta Mark = idle intre caractere). In loc sa
    // "inghetam" valoarea, o derivam lin catre o tinta pozitiva (Mark) pe durata
    // aproximativa a unui simbol RTTY (~22ms).
    private static final double SNR_GATING_MARK_BIAS_ALPHA = 0.001; // ~1000 esantioane (~20ms) pana converge

    // CORECTIE: tinta era inainte "nivelZgomotMediu * pragSensibilitate * 2.0" - cu
    // sliderul de Sensibilitate pe 0% tinta devenea EXACT 0 si decizia cadea pe SPACE.
    // Marja FIXA, independenta de orice slider, garanteaza o valoare strict pozitiva.
    private static final double SNR_GATING_MARK_BIAS_MARGINE = 0.1;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public double getFrecventaMarkHz() {
        return frecventaTunedBaza;
    }

    double getSpaceOffsetHz() {
        return regimReverseSoftware ? -SHIFT_HZ : SHIFT_HZ;
    }

    // ============================================================
    // Coeficienti
    // ============================================================

    void initMatchedFilterTemplates(int sampleRate, double baud) {
        mfSamplesPerBit = (int) Math.round(sampleRate / baud);
        mfBuffer = new double[mfSamplesPerBit];
        int jumatate = Math.max(1, (int) Math.round(sampleRate / baud / 2));
        jumMark.configureaza(jumatate);
        jumSpace.configureaza(jumatate);
        jumIstMark = new double[jumatate];
        jumIstSpace = new double[jumatate];
        jumPoz = 0;
        mfBufferPos = 0;
        mfMarkAccRe = mfMarkAccIm = 0;
        mfSpaceAccRe = mfSpaceAccIm = 0;

        double fMark = frecventaTunedBaza;
        double fSpace = frecventaTunedBaza + getSpaceOffsetHz();
        setareaConstantelorMF(fMark, fSpace, sampleRate);
    }

    /**
     * OPTIMIZARE: in loc sa recalculam N valori sin/cos (O(N)) de fiecare data cand se
     * schimba frecventa (ex: din AFC, la fiecare fractiune de secunda), recalculam doar
     * cateva constante de rotatie (O(1)). Acumulatorul corelatiei glisante NU se reseteaza
     * aici - continua sa evolueze lin sub noua rotatie, la fel cum bufferul audio (mfBuffer)
     * nu e resetat la o simpla derivare de frecventa din AFC (se reseteaza doar explicit,
     * la solicitaResetFiltre).
     */
    void recalculeazaShabloaneMF() {
        if (mfBuffer != null) {
            double fMark = frecventaTunedBaza;
            double fSpace = frecventaTunedBaza + getSpaceOffsetHz();
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
        jumMark.setFrecventa(wMark);
        jumSpace.setFrecventa(wSpace);
        mfMarkPowNRe = Math.cos(wMarkN);
        mfMarkPowNIm = Math.sin(wMarkN);
        mfSpacePowNRe = Math.cos(wSpaceN);
        mfSpacePowNIm = Math.sin(wSpaceN);

        // CORECTIE: recursia S(n+1) = e^{-jw} (S(n) - x_iesit + x_nou e^{jwN}) e corecta doar cat w
        // ramane acelasi. La o schimbare de frecventa esantioanele din fereastra au intrat cu vechea
        // referinta de faza si ar fi scazute cu cea noua - eroarea ramanea in suma pentru totdeauna.
        // Cu AFC-ul care corecteaza la ~2 biti se aduna (7-28% din nivelul semnalului; MF + PLL pe
        // W6IWI 14086 kHz: 1548 -> 110 erori). Sumele se recalculeaza exact din buffer.
        if (mfBuffer != null) {
            double[] m = sumaExacta(mfBuffer, mfBufferPos, wMark);
            mfMarkAccRe = m[0]; mfMarkAccIm = m[1];
            double[] sp = sumaExacta(mfBuffer, mfBufferPos, wSpace);
            mfSpaceAccRe = sp[0]; mfSpaceAccIm = sp[1];
        }
    }

    /**
     * Suma exacta a corelatorului glisant din buffer: S = suma b_i e^{j w i}, b_0 = esantionul cel
     * mai vechi - forma inchisa a recursiei S(n+1) = e^{-jw} (S(n) - x_iesit + x_nou e^{jwN}).
     */
    private static double[] sumaExacta(double[] buf, int pozCelMaiVechi, double w) {
        double re = 0, im = 0, pasRe = Math.cos(w), pasIm = Math.sin(w), fRe = 1, fIm = 0;
        int n = buf.length, idx = pozCelMaiVechi;
        for (int i = 0; i < n; i++) {
            double x = buf[idx];
            re += x * fRe;
            im += x * fIm;
            double t = fRe * pasRe - fIm * pasIm;
            fIm = fRe * pasIm + fIm * pasRe;
            fRe = t;
            if (++idx == n) idx = 0;
        }
        return new double[]{re, im};
    }

    /** Calculeaza coeficientii pentru filtrul bandpass biquad DNR (RBJ cookbook). */
    void calculeazaCoeficientiDnr(double sampleRate) {
        double f0 = frecventaTunedBaza + getSpaceOffsetHz() / 2.0; // centrat intre Mark si Space
        double q = f0 / Math.max(latimeDnrHz, 1.0);
        double w0 = 2.0 * Math.PI * f0 / sampleRate;
        double alpha = Math.sin(w0) / (2.0 * q);
        double cosW0 = Math.cos(w0);

        double b0 = alpha, b1 = 0.0, b2 = -alpha;
        double a0 = 1.0 + alpha, a1 = -2.0 * cosW0, a2 = 1.0 - alpha;

        dnrB0 = b0 / a0; dnrB1 = b1 / a0; dnrB2 = b2 / a0;
        dnrA1 = a1 / a0; dnrA2 = a2 / a0;
    }

    /** Recalculeaza tot ce depinde de frecventa Mark / reverse si cere reset de filtre. */
    void sincronizeazaCoeficienti() {
        recalculeazaCoeficientiFrecventa();
        recalculeazaShabloaneMF();
        solicitaResetFiltre = true;
    }

    private void recalculeazaCoeficientiFrecventa() {
        afcMark.setFrecventa(frecventaTunedBaza, sampleRateFiltre);
        afcSpace.setFrecventa(frecventaTunedBaza + getSpaceOffsetHz(), sampleRateFiltre);

        calculeazaCoeficientiDnr(sampleRateFiltre);

        qMarkA = 2.0 * Math.cos(2.0 * Math.PI * frecventaTunedBaza / sampleRateFiltre);
        double fSpace = frecventaTunedBaza + getSpaceOffsetHz();
        qSpaceA = 2.0 * Math.cos(2.0 * Math.PI * fSpace / sampleRateFiltre);
    }

    // ============================================================
    // Decodare
    // ============================================================

    /** Initializeaza starea de decodare (echivalentul inceputului thread-ului de receptie). */
    public void porneste(int sampleRate) {
        this.sampleRate = sampleRate;
        samplesPerBit = (double) sampleRate / BAUD_RTTY;

        mx1 = 0; mx2 = 0; sx1 = 0; sx2 = 0;
        dcBlockerInVech = 0; dcBlockerOutVech = 0;
        integratVech = 0;
        nivelZgomotMediu = 0.05;
        inFrame = false;
        fazaCeasAcumulator = 0;
        bitCounter = 0;
        currentByte = 0;
        contorConfirmareStart = 0;
        pragConfirmareStart = (int) Math.round(samplesPerBit * 0.7);
        pragConfirmareStartMf = (int) Math.round(samplesPerBit * MF_PRAG_START_BITI);
        ddAm = ddAs = 0;
        coerentaMedie = 1.0;
        mfNecoerent = false;
        contorEroriFramingConsecutive = 0;
        nbNivelMediu = 0.05;
        nbUltimulEsantionValid = 0;
        nbContorBlankConsecutiv = 0;
        dnrX1 = 0; dnrX2 = 0; dnrY1 = 0; dnrY2 = 0;
        afcIntarziere = Math.max(1, (int) Math.round(AFC_INTARZIERE_SEC * sampleRate));
        afcFereastra = Math.max(1, (int) Math.round(samplesPerBit)) * 2;
        afcMark.configureaza(AFC_LPF_HZ, sampleRate, afcIntarziere);
        afcSpace.configureaza(AFC_LPF_HZ, sampleRate, afcIntarziere);
        reseteazaAfc();

        noiseFloor = 1e-9;
        if (lpfDecizieHz > 0) {
            lpfMark.configureazaTreceJos(lpfDecizieHz, sampleRate);
            lpfSpace.configureazaTreceJos(lpfDecizieHz, sampleRate);
        }
        reseteazaAtc();
        deblocheazaVolant();
        esantionAbs = 0;

        if (folosesteMatchedFilter) {
            initMatchedFilterTemplates(sampleRate, BAUD_RTTY);
        }

        sampleRateFiltre = sampleRate;
        rCheb = 0.992;
        sincronizeazaCoeficienti();
    }

    /** Proceseaza un bloc de esantioane PCM 16-bit (primele `read` din `audioBuffer`). */
    public void proceseazaBloc(short[] audioBuffer, int read) {
        if (solicitaResetFiltre) {
            mx1 = 0; mx2 = 0; sx1 = 0; sx2 = 0;
            integratVech = 0;
            inFrame = false;
            fazaCeasAcumulator = 0;
            bitCounter = 0;
            currentByte = 0;
            contorConfirmareStart = 0;
            contorEroriFramingConsecutive = 0;
            // CORECTIE: nbNivelMediu/nbUltimulEsantionValid NU se reseteaza aici - nivelul
            // de amplitudine invatat de NB nu depinde de filtre/clock recovery; resetat la
            // 0.05 la fiecare schimbare de setare, putea bloca NB permanent pe un semnal
            // normal (nu mai accepta niciun esantion, deci nu mai avea din ce sa invete).
            nbContorBlankConsecutiv = 0;
            noiseFloor = 1e-9; // Resetam si podeaua de zgomot pentru a recalibra SNR-G
            dnrX1 = 0; dnrX2 = 0; dnrY1 = 0; dnrY2 = 0;
            lpfMark.reseteaza();
            lpfSpace.reseteaza();
            reseteazaAfc();
            reseteazaAtc();
            deblocheazaVolant();
            if (folosesteMatchedFilter && mfBuffer != null) {
                for (int k = 0; k < mfBuffer.length; k++) mfBuffer[k] = 0;
                mfBufferPos = 0;
                mfMarkAccRe = mfMarkAccIm = 0;
                mfSpaceAccRe = mfSpaceAccIm = 0;
                jumMark.reseteaza();
                jumSpace.reseteaza();
                java.util.Arrays.fill(jumIstMark, 0);
                java.util.Arrays.fill(jumIstSpace, 0);
                jumPoz = 0;
            }
            // amplitudinile invatate tin de semnal (acord, statie); coerenta tine de emitator - ramane
            ddAm = ddAs = 0;
            solicitaResetFiltre = false;
        }

        for (int i = 0; i < read; i++) {
            double rawSample = audioBuffer[i] / 32768.0;
            double sample = rawSample - dcBlockerInVech + 0.995 * dcBlockerOutVech;
            dcBlockerInVech = rawSample;
            dcBlockerOutVech = sample;

            // Noise Blanker. nbNivelMediu se calibreaza mereu, din semnalul brut, INDIFERENT
            // daca gating-ul NB e activ - la activare, pragul e deja realist, fara nicio
            // fereastra de "orbire". Supapa NB_MAX_BLANK_CONSECUTIV ramane ultima plasa,
            // pentru salturi bruste in timpul receptiei (AGC statie, retunare).
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

            double magMark, magSpace;
            double diferentaBruta;

            if (folosesteMatchedFilter) {
                // Corelatie glisanta recursiva, matematic echivalenta cu suma completa
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

                // jumatatile de bit: corelatorul de 1/2 bit acum si cel de acum 1/2 bit
                jumMark.proceseaza(sample);
                jumSpace.proceseaza(sample);
                double jm = jumMark.magnitudine() * scalareMF, js = jumSpace.magnitudine() * scalareMF;
                magCohMark = magMark;
                magCohSpace = magSpace;
                magNcMark = jm + jumIstMark[jumPoz];
                magNcSpace = js + jumIstSpace[jumPoz];
                jumIstMark[jumPoz] = jm;
                jumIstSpace[jumPoz] = js;
                if (++jumPoz == jumIstMark.length) jumPoz = 0;
                if (mfNecoerent) {
                    magMark = magNcMark;
                    magSpace = magNcSpace;
                }
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

            // Filtrul de decizie se aplica separat pe fiecare ton (liniar, deci fara ATC e
            // identic cu filtrarea diferentei), ca ATC sa primeasca anvelopele netezite.
            double markFiltrat = lpfDecizieHz > 0 ? lpfMark.proceseaza(magMark) : magMark;
            double spaceFiltrat = lpfDecizieHz > 0 ? lpfSpace.proceseaza(magSpace) : magSpace;
            // ATC doar pe Chebyshev; MF are propria normalizare a tonurilor (decizieMf).
            diferentaBruta = folosesteMatchedFilter
                    ? decizieMf(markFiltrat, spaceFiltrat)
                    : folosesteAtc
                    ? decizieAtc(markFiltrat, spaceFiltrat)
                    : markFiltrat - spaceFiltrat;

            if (modUrmarireFrecventa > 0) actualizeazaAfc(sample, markFiltrat, spaceFiltrat);

            double magTotala = magMark + magSpace;

            if (magTotala < noiseFloor) {
                noiseFloor = magTotala;
            } else {
                noiseFloor = 0.999 * noiseFloor + 0.001 * magTotala;
            }

            nivelZgomotMediu = (0.0005 * magTotala) + (0.9995 * nivelZgomotMediu);

            // SNR Gating: energie insuficienta -> derivam lin spre Mark (idle RTTY).
            double integratCurent;
            if (folosesteSnrGating && magTotala < noiseFloor * snrGatingK) {
                double tintaMark = Math.max(nivelZgomotMediu, 1e-9) * SNR_GATING_MARK_BIAS_MARGINE;
                integratCurent = integratVech + (tintaMark - integratVech) * SNR_GATING_MARK_BIAS_ALPHA;
            } else {
                integratCurent = (alfaIntegrator * diferentaBruta) + ((1.0 - alfaIntegrator) * integratVech);
            }
            integratVech = integratCurent;

            // SNR LIVE
            if (i % 4800 == 0 && listener != null) {
                double signalPeak = Math.max(magMark, magSpace);
                double snrLinear = signalPeak / Math.max(noiseFloor, 1e-9);
                double snrDb = 20 * Math.log10(snrLinear);
                double snrNetezit = Math.max(0, snrDb);
                listener.onStare(String.format("%.1f dB", snrNetezit), (int) frecventaTunedBaza);
            }

            boolean isMark;
            if (Math.abs(integratCurent) < (nivelZgomotMediu * pragSensibilitate)) {
                isMark = true;
            } else {
                isMark = integratCurent > 0;
            }
            int bitCurent = isMark ? 1 : 0;

            esantionAbs++;

            // Clock Recovery
            if (!inFrame) {
                // Detectia libera a frontului de start ruleaza si cu volantul blocat - acolo
                // doar masoara unde a fost frontul real, ca sa corecteze prezicerea.
                if (bitCurent == 0) {
                    contorConfirmareStart++;
                } else {
                    contorConfirmareStart = 0; // revenire la mark inainte de prag = fals pozitiv
                }

                if (folosesteVolant && volantBlocat) {
                    long mijlocStart = Math.round(startPrezis + 0.5 * samplesPerBit);
                    long sfarsitStart = Math.round(startPrezis + VOLANT_FEREASTRA_BITI * samplesPerBit);
                    if (esantionAbs >= mijlocStart && (bitCurent == 0 || esantionAbs >= sfarsitStart)) {
                        if (bitCurent == 0 && (esantionAbs == mijlocStart
                                || contorConfirmareStart >= samplesPerBit * 0.3)) {
                            double start = startPrezis;
                            if (contorConfirmareStart >= samplesPerBit * 0.3) {
                                double abatere = (esantionAbs - contorConfirmareStart) - startPrezis;
                                double limita = 0.25 * samplesPerBit;
                                start += VOLANT_CORECTIE * Math.max(-limita, Math.min(limita, abatere));
                            }
                            incepeCadru(start);
                        } else if (esantionAbs >= sfarsitStart) {
                            // Mark pe tot inceputul bitului de start prezis: de regula emitatorul
                            // a facut o pauza (tastare manuala) si urmatorul caracter vine la o
                            // faza oarecare - inapoi la cautarea libera, care si-a tinut contorul
                            // la zi. (Sa sarim o perioada intreaga ar pierde caracterul de dupa o
                            // pauza scurta.) Daca doar mijlocul startului a fost lovit de zgomot,
                            // space-ul apare in fereastra si cadrul porneste pe grila.
                            double grila = startPrezis, perioada = perioadaCaracter;
                            deblocheazaVolant();
                            // Grila veche ramane "fantoma" cateva caractere: daca startul a fost
                            // doar mancat de zgomot (emitatorul a continuat), primul caracter
                            // valid gasit liber cade pe ea si volantul se reblocheaza imediat.
                            fantomaGrila = grila;
                            fantomaPerioada = perioada;
                            fantomaActiva = true;
                        }
                    }
                } else if (bitCurent == 0 && contorConfirmareStart >= (folosesteMatchedFilter ? pragConfirmareStartMf : pragConfirmareStart)) {
                    // CORECTIE: faza nu porneste de la 0, ci de la cate esantioane au trecut
                    // deja de la INCEPUTUL real al starii "space" (cat am asteptat sa
                    // confirmam) - altfel am introduce o intarziere sistematica la fiecare bit.
                    incepeCadru(esantionAbs - contorConfirmareStart);
                }
            } else {
                fazaCeasAcumulator += 1.0;
                double punctEstantionareTinta = (bitCounter == 0)
                        ? (samplesPerBit * (1.0 + PCT_ESANTIONARE))
                        : samplesPerBit;

                if (fazaCeasAcumulator >= punctEstantionareTinta) {
                    fazaCeasAcumulator -= punctEstantionareTinta;
                    if (folosesteMatchedFilter) invataMf(bitCurent, markFiltrat, spaceFiltrat);
                    if (bitCounter < 5) {
                        if (bitCurent == 1) currentByte |= (1 << bitCounter);
                        bitCounter++;
                    } else {
                        // Am ajuns la stop-bit (bitCounter == 5)
                        boolean volantEraBlocat = folosesteVolant && volantBlocat;
                        if (folosesteVolant) actualizeazaVolant(bitCurent == 1);
                        if (bitCurent == 1) {
                            if (listener != null) listener.onCodBaudot(currentByte & 0x1F);
                            contorEroriFramingConsecutive = 0;
                        } else if (!volantEraBlocat) {
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

    private void reseteazaAfc() {
        afcMark.reseteaza();
        afcSpace.reseteaza();
        afcSumRe = afcSumIm = afcSumAbs = 0;
        afcContor = afcDominant = afcDominantDecodor = 0;
        afcFereastraAnterioaraValida = false;
        freqErrorFiltrat = 0;
    }

    /**
     * AFC / PLL. Fiecare ton e coborat in banda de baza la frecventa lui nominala si filtrat
     * (~50 Hz); rotatia fazei pe 4 ms, z[n]*conj(z[n-D]), da direct abaterea tonului fata de
     * acord, cu semnul corect. Se masoara doar tonul care domina clar in acel moment (nu la
     * tranzitii), iar produsele se aduna vectorial pe ~2 biti: un ton curat le aliniaza
     * (coerenta ~1), zgomotul le imprastie (~0) - sub prag corectia nu se aplica, deci pauzele
     * dintre transmisii nu muta acordul. Castigurile (AFC 5%, PLL 25%) si limitele sunt cele vechi.
     */
    private void actualizeazaAfc(double sample, double markFiltrat, double spaceFiltrat) {
        afcMark.proceseaza(sample);
        afcSpace.proceseaza(sample);
        // Dominanta se judeca pe filtrele proprii AFC (raspund in cateva ms), nu pe anvelopele
        // decodorului: cele MF intarzie ~1/2 bit, iar dupa fiecare tranzitie AFC-ul masura inca pe
        // tonul disparut (coada filtrului + scurgerea celuilalt ton) - cu Mark mai slab decat Space,
        // acordul aluneca in sus (MF + PLL: +9..+10 Hz pe inregistrarile W6IWI).
        double pm = afcMark.putere(), ps = afcSpace.putere();
        double d2 = AFC_DOMINANTA * AFC_DOMINANTA;
        if (pm > d2 * ps) {
            acumuleaza(afcMark);
            afcDominant++;
        } else if (ps > d2 * pm) {
            acumuleaza(afcSpace);
            afcDominant++;
        }
        // CORECTIE: filtrele AFC (50 Hz, cativa ms) sunt prea rapide ca sa deosebeasca singure
        // semnalul de zgomot - pe zgomot un ton pare des "dominant", iar AFC-ul muta acordul
        // (3 min de zgomot, Chebyshev + PLL: 2125 -> 2072 Hz; QRN W6IWI: 2048 -> 2036 Hz). Ca
        // ferestra sa conteze trebuie sa fie semnal si dupa anvelopele decodorului (integrate pe
        // bit): cu ele pe zgomot acordul nu se misca, iar masuratoarea ramane pe filtrele rapide.
        if (markFiltrat > AFC_DOMINANTA * spaceFiltrat || spaceFiltrat > AFC_DOMINANTA * markFiltrat) {
            afcDominantDecodor++;
        }

        if (++afcContor < afcFereastra) return;
        afcContor = 0;
        boolean semnal = afcDominant >= AFC_FRACTIE_DOMINANTA_MIN * afcFereastra
                && afcDominantDecodor >= AFC_FRACTIE_DOMINANTA_MIN * afcFereastra;
        afcDominant = afcDominantDecodor = 0;
        double sumAbs = afcSumAbs;
        double coerenta = sumAbs > 0 ? Math.sqrt(afcSumRe * afcSumRe + afcSumIm * afcSumIm) / sumAbs : 0;
        double eroareHz = Math.atan2(afcSumIm, afcSumRe) * sampleRate / (2 * Math.PI * afcIntarziere);
        afcSumRe = afcSumIm = afcSumAbs = 0;
        afcMark.renormalizeaza();
        afcSpace.renormalizeaza();
        boolean valida = semnal && sumAbs > 0 && coerenta >= AFC_COERENTA_MIN && Math.abs(eroareHz) <= AFC_PRAG_OUTLIER_HZ;
        // Corectia se aplica doar daca si fereastra anterioara a fost valida. Pe zgomot trec
        // izolat ~0.6% din ferestre (niciodata doua la rand in 2 x 3 min), destul ca MF + PLL sa
        // derive cu ~8 Hz in 3 minute; pe semnal ferestrele valide vin in serii (W6IWI 14086 kHz:
        // 98.7% -> 98.0% aplicate).
        boolean aplica = valida && afcFereastraAnterioaraValida;
        afcFereastraAnterioaraValida = valida;
        if (!aplica) return;

        freqErrorFiltrat = freqErrorFiltrat * (1.0 - AFC_EMA_ALPHA) + eroareHz * AFC_EMA_ALPHA;
        double limitat = Math.max(-AFC_MAX_CORECTIE_HZ, Math.min(AFC_MAX_CORECTIE_HZ, freqErrorFiltrat));
        double castig = modUrmarireFrecventa == 2 ? 0.25 : 0.05;
        frecventaTunedBaza += limitat * castig;
        // Eroarea masurata e relativa la acordul vechi: dupa corectie scade cu cat s-a corectat.
        freqErrorFiltrat -= limitat * castig;

        sampleRateFiltre = sampleRate;
        recalculeazaCoeficientiFrecventa();
        if (folosesteMatchedFilter) recalculeazaShabloaneMF();
    }

    private void acumuleaza(AfcTon t) {
        afcSumRe += t.pRe;
        afcSumIm += t.pIm;
        afcSumAbs += Math.sqrt(t.pRe * t.pRe + t.pIm * t.pIm);
    }

    /** Un ton pentru AFC: mixer complex, trece-jos de ordin 4 pe I/Q, linie de intarziere. */
    private static final class AfcTon {
        private final Biquad i1 = new Biquad(), i2 = new Biquad(), q1 = new Biquad(), q2 = new Biquad();
        private double osRe = 1, osIm, pasRe = 1, pasIm;
        private double[] intRe = new double[1], intIm = new double[1];
        private int poz;
        private double zRe, zIm;
        double pRe, pIm;

        /** |z|^2 - puterea tonului in banda de baza acum. */
        double putere() {
            return zRe * zRe + zIm * zIm;
        }

        void configureaza(double lpfHz, double fs, int intarziere) {
            i1.configureazaTreceJos(lpfHz, fs); i2.configureazaTreceJos(lpfHz, fs);
            q1.configureazaTreceJos(lpfHz, fs); q2.configureazaTreceJos(lpfHz, fs);
            intRe = new double[intarziere];
            intIm = new double[intarziere];
            reseteaza();
        }

        void setFrecventa(double hz, double fs) {
            double w = 2 * Math.PI * hz / fs;
            pasRe = Math.cos(w);
            pasIm = -Math.sin(w); // e^{-jw}: tonul la frecventa nominala ajunge la 0 Hz
        }

        void reseteaza() {
            i1.reseteaza(); i2.reseteaza(); q1.reseteaza(); q2.reseteaza();
            java.util.Arrays.fill(intRe, 0);
            java.util.Arrays.fill(intIm, 0);
            poz = 0;
            zRe = zIm = pRe = pIm = 0;
        }

        /** Un esantion: z[n] in banda de baza si produsul z[n]*conj(z[n-D]). */
        void proceseaza(double x) {
            zRe = i2.proceseaza(i1.proceseaza(x * osRe));
            zIm = q2.proceseaza(q1.proceseaza(x * osIm));
            double r = osRe * pasRe - osIm * pasIm;
            osIm = osRe * pasIm + osIm * pasRe;
            osRe = r;

            double vRe = intRe[poz], vIm = intIm[poz];
            pRe = zRe * vRe + zIm * vIm;
            pIm = zIm * vRe - zRe * vIm;
            intRe[poz] = zRe;
            intIm[poz] = zIm;
            if (++poz == intRe.length) poz = 0;
        }

        void renormalizeaza() {
            double n = Math.sqrt(osRe * osRe + osIm * osIm);
            osRe /= n;
            osIm /= n;
        }
    }

    /** MF: fiecare ton raportat la amplitudinea lui invatata (vezi comentariul de la campuri). */
    private double decizieMf(double mark, double space) {
        if (ddAm <= 0 || ddAs <= 0) return mark - space; // pana se invata ambele tonuri
        double am = Math.max(ddAm, MF_DD_PODEA * ddAs);
        double asp = Math.max(ddAs, MF_DD_PODEA * ddAm);
        // inapoi pe scara unei amplitudini, ca pragul de Sensibilitate sa ramana comparabil
        return (mark / am - space / asp) * 0.5 * (am + asp);
    }

    /** La fiecare bit esantionat: amplitudinea tonului decis si coerenta pe jumatati. */
    private void invataMf(int bit, double markFiltrat, double spaceFiltrat) {
        if (bit == 1) ddAm = ddAm <= 0 ? markFiltrat : ddAm + MF_DD_ALFA * (markFiltrat - ddAm);
        else ddAs = ddAs <= 0 ? spaceFiltrat : ddAs + MF_DD_ALFA * (spaceFiltrat - ddAs);

        double coh = bit == 1 ? magCohMark : magCohSpace;
        double nc = bit == 1 ? magNcMark : magNcSpace;
        if (nc <= 0) return;
        coerentaMedie += MF_ALFA_COERENTA * (Math.min(1.0, coh / nc) - coerentaMedie);
        mfNecoerent = mfNecoerent ? coerentaMedie < MF_PRAG_COERENT : coerentaMedie < MF_PRAG_NECOERENT;
    }

    /** Corelator glisant recursiv pe o fereastra de lungime data (aceeasi formula ca MF). */
    private static final class CorelatorGlisant {
        private double[] buf = new double[1];
        private int poz;
        private double w, rotRe = 1, rotIm, powRe = 1, powIm, accRe, accIm;

        void configureaza(int lungime) {
            buf = new double[lungime];
            reseteaza();
            setFrecventa(w);
        }

        void setFrecventa(double omega) {
            w = omega;
            rotRe = Math.cos(-w);
            rotIm = Math.sin(-w);
            powRe = Math.cos(w * buf.length);
            powIm = Math.sin(w * buf.length);
            double[] s = sumaExacta(buf, poz, w); // vezi setareaConstantelorMF
            accRe = s[0];
            accIm = s[1];
        }

        void reseteaza() {
            java.util.Arrays.fill(buf, 0);
            poz = 0;
            accRe = accIm = 0;
        }

        void proceseaza(double x) {
            double iesit = buf[poz];
            buf[poz] = x;
            if (++poz == buf.length) poz = 0;
            double inRe = accRe - iesit + x * powRe;
            double inIm = accIm + x * powIm;
            accRe = rotRe * inRe - rotIm * inIm;
            accIm = rotRe * inIm + rotIm * inRe;
        }

        double magnitudine() {
            return Math.sqrt(accRe * accRe + accIm * accIm);
        }
    }

    private void incepeCadru(double start) {
        inFrame = true;
        startCadru = start;
        fazaCeasAcumulator = esantionAbs - start;
        bitCounter = 0;
        currentByte = 0;
        contorConfirmareStart = 0;
    }

    /**
     * Apelat la fiecare bit de stop. Caractere valide consecutive, fiecare la 7-8 biti dupa
     * precedentul (RTTY continuu, 1 / 1.5 / 2 biti de stop), blocheaza volantul si ii invata
     * perioada; cu volantul blocat, rateurile (stop invalid sau start lipsa) doar se numara.
     */
    private void actualizeazaVolant(boolean stopValid) {
        if (fantomaActiva && !volantBlocat) {
            double k = Math.round((startCadru - fantomaGrila) / fantomaPerioada);
            if (k < 0 || k > 4) {
                fantomaActiva = false;
            } else if (stopValid && Math.abs(startCadru - (fantomaGrila + k * fantomaPerioada)) <= VOLANT_TOLERANTA_BITI * samplesPerBit) {
                fantomaActiva = false;
                volantBlocat = true;
                volantPotriviri = VOLANT_PRAG_BLOCARE;
                perioadaCaracter = fantomaPerioada;
                startAnterior = startCadru;
                areStartAnterior = true;
                ratariVolant = 0;
                startPrezis = startCadru + perioadaCaracter;
                return;
            }
        }
        if (stopValid) {
            if (areStartAnterior) {
                double perioada = startCadru - startAnterior;
                double biti = perioada / samplesPerBit;
                // Caractere lipite: 7 / 7.5 / 8 biti (1 / 1.5 / 2 biti de stop), si aceeasi
                // perioada ca pana acum. O pauza scurta de tastare (ex. 0.5 bit) da tot o
                // valoare "plauzibila" (8.0) - fara conditia de consecventa, media ar amesteca
                // 7.5 cu 8.0 si prezicerile ar iesi decalate.
                double celMaiAproape = Math.round(biti * 2) / 2.0;
                boolean plauzibila = celMaiAproape >= 7.0 && celMaiAproape <= 8.0
                        && Math.abs(biti - celMaiAproape) <= VOLANT_TOLERANTA_BITI;
                boolean consecventa = volantPotriviri == 0
                        || Math.abs(perioada - perioadaCaracter) <= VOLANT_TOLERANTA_BITI * samplesPerBit;
                if (plauzibila && consecventa) {
                    perioadaCaracter = volantPotriviri == 0 ? perioada : 0.8 * perioadaCaracter + 0.2 * perioada;
                    volantPotriviri++;
                } else if (!volantBlocat) {
                    volantPotriviri = plauzibila ? 1 : 0;
                    if (plauzibila) perioadaCaracter = perioada;
                }
            }
            startAnterior = startCadru;
            areStartAnterior = true;
            ratariVolant = 0;
            if (volantPotriviri >= VOLANT_PRAG_BLOCARE) volantBlocat = true;
        } else if (volantBlocat) {
            ratariVolant++;
            if (ratariVolant >= VOLANT_MAX_RATARI) {
                deblocheazaVolant();
                return;
            }
        } else {
            volantPotriviri = 0;
            areStartAnterior = false;
        }
        if (volantBlocat) startPrezis = startCadru + perioadaCaracter;
    }

    private void deblocheazaVolant() {
        fantomaActiva = false;
        volantBlocat = false;
        volantPotriviri = 0;
        ratariVolant = 0;
        areStartAnterior = false;
        perioadaCaracter = 7.5 * samplesPerBit;
    }

    private void reseteazaAtc() {
        atcMarkEnv = atcSpaceEnv = 0;
        atcMarkZgomot = atcSpaceZgomot = 0;
    }

    private static double medieDescrescatoare(double medie, double x, double fereastra) {
        return medie + (x - medie) / fereastra;
    }

    /**
     * Anvelopa: atac rapid (1/4 bit), decadere in 8 biti. Zgomotul: coboara rapid (1/4 bit),
     * urca lent (48 de biti). fldigi foloseste 16 biti la decadere; in RttyDemodulatorTest,
     * pe fading selectiv de 1 Hz la 20 dB, 8 biti dau ~5% CER fata de ~14% cu 16, iar la
     * fading lent (0.2 Hz) sunt la fel. 4 si 2 biti strica semnalele slabe fara fading.
     */
    private double decizieAtc(double mark, double space) {
        double bit = samplesPerBit;
        atcMarkEnv = medieDescrescatoare(atcMarkEnv, mark, mark > atcMarkEnv ? bit / 4 : bit * 8);
        atcSpaceEnv = medieDescrescatoare(atcSpaceEnv, space, space > atcSpaceEnv ? bit / 4 : bit * 8);
        atcMarkZgomot = medieDescrescatoare(atcMarkZgomot, mark, mark < atcMarkZgomot ? bit / 4 : bit * 48);
        atcSpaceZgomot = medieDescrescatoare(atcSpaceZgomot, space, space < atcSpaceZgomot ? bit / 4 : bit * 48);

        double zgomot = Math.min(atcMarkZgomot, atcSpaceZgomot);
        double m = Math.max(Math.min(mark, atcMarkEnv), zgomot) - zgomot;
        double s = Math.max(Math.min(space, atcSpaceEnv), zgomot) - zgomot;
        double em = Math.max(atcMarkEnv - zgomot, 0);
        double es = Math.max(atcSpaceEnv - zgomot, 0);
        double v = m * em - s * es - 0.25 * (em * em - es * es);
        // Normalizat la unitatea unei amplitudini, ca pragul de Sensibilitate (relativ la
        // nivelZgomotMediu) sa ramana pe aceeasi scara ca diferenta Mark-Space simpla.
        return v / Math.max(em + es, 1e-12);
    }

    /** Biquad RBJ trece-jos, Q = 1/sqrt(2) (Butterworth de ordin 2). */
    private static final class Biquad {
        private double b0 = 1, b1, b2, a1, a2;
        private double x1, x2, y1, y2;

        void configureazaTreceJos(double fc, double fs) {
            double w = 2 * Math.PI * fc / fs;
            double alpha = Math.sin(w) / Math.sqrt(2);
            double c = Math.cos(w);
            double a0 = 1 + alpha;
            b0 = (1 - c) / 2 / a0;
            b1 = (1 - c) / a0;
            b2 = (1 - c) / 2 / a0;
            a1 = -2 * c / a0;
            a2 = (1 - alpha) / a0;
            reseteaza();
        }

        void reseteaza() {
            x1 = x2 = y1 = y2 = 0;
        }

        double proceseaza(double x) {
            double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = x;
            y2 = y1; y1 = y;
            return y;
        }
    }
}
