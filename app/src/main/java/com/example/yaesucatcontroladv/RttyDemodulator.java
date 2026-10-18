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
    private double atcMarkEnv, atcSpaceEnv, atcMarkZgomot, atcSpaceZgomot;

    // ---- Coeficienti derivati din frecventa ----
    private double qMarkA, qSpaceA;
    private double afcRotRe = 1.0, afcRotIm = 0.0; // increment de rotatie per esantion: cos(w), sin(w)

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
    private double fazaVectRe, fazaVectIm; // Vector de faza complex (inlocuieste fazaAFC)
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
    private int pragConfirmareStart;

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

    private double I_acc, Q_acc;
    private double last_phase;
    private int samplesSinceLastAFC;
    // OPTIMIZARE/CORECTIE: fereastra de acumulare AFC trebuie sa fie un multiplu
    // intreg al perioadei unui simbol (samplesPerBit), altfel punctul de esantionare
    // a fazei "aluneca" fata de granitele reale ale bitilor RTTY, ceea ce introduce
    // eroare suplimentara in estimarea de frecventa (mai ales pe semnal slab).
    // OPTIMIZARE: 2 perioade de simbol intre corectii - mai putina reactivitate, dar
    // discriminatorul de frecventa mediaza zgomotul pe o fereastra mai mare.
    private static final int FERESTRE_AFC = 2; // cate perioade de simbol acumulam intre 2 corectii AFC
    private int fereastraAFCSamples;

    // OPTIMIZARE: filtru de bucla AFC:
    //  - freqErrorFiltrat: o medie exponentiala (EMA) care neteze masuratorile brute
    //    intre ferestre succesive (echivalent unui filtru de bucla simplu);
    //  - AFC_MAX_CORECTIE_HZ: plafon pe marimea corectiei aplicate per fereastra -
    //    un semnal RTTY normal nu deriva brusc cu zeci de Hz intre doua ferestre
    //    consecutive (~44ms), deci limitam corectia la o valoare fizic plauzibila;
    //  - AFC_PRAG_OUTLIER_HZ: daca masuratoarea bruta e nefiziologic de mare (fereastra
    //    "murdara" - de ex. o tranzitie mark/space cade in mijlocul ei - sau zgomot),
    //    o ignoram complet in loc s-o aplicam (fie si trunchiata).
    private double freqErrorFiltrat;
    private static final double AFC_EMA_ALPHA = 0.25;
    private static final double AFC_MAX_CORECTIE_HZ = 5.0;
    private static final double AFC_PRAG_OUTLIER_HZ = 20.0;

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
        mfMarkPowNRe = Math.cos(wMarkN);
        mfMarkPowNIm = Math.sin(wMarkN);
        mfSpacePowNRe = Math.cos(wSpaceN);
        mfSpacePowNIm = Math.sin(wSpaceN);
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
        double wAfc = 2.0 * Math.PI * frecventaTunedBaza / sampleRateFiltre;
        afcRotRe = Math.cos(wAfc);
        afcRotIm = Math.sin(wAfc);

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
        fazaVectRe = 1.0; fazaVectIm = 0.0;
        bitCounter = 0;
        currentByte = 0;
        contorConfirmareStart = 0;
        pragConfirmareStart = (int) Math.round(samplesPerBit * 0.7);
        contorEroriFramingConsecutive = 0;
        nbNivelMediu = 0.05;
        nbUltimulEsantionValid = 0;
        nbContorBlankConsecutiv = 0;
        dnrX1 = 0; dnrX2 = 0; dnrY1 = 0; dnrY2 = 0;
        I_acc = 0; Q_acc = 0;
        last_phase = 0;
        samplesSinceLastAFC = 0;
        fereastraAFCSamples = Math.max(1, (int) Math.round(samplesPerBit)) * FERESTRE_AFC;
        freqErrorFiltrat = 0;

        noiseFloor = 1e-9;
        if (lpfDecizieHz > 0) {
            lpfMark.configureazaTreceJos(lpfDecizieHz, sampleRate);
            lpfSpace.configureazaTreceJos(lpfDecizieHz, sampleRate);
        }
        reseteazaAtc();

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
            fazaVectRe = 1.0; fazaVectIm = 0.0;
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
            reseteazaAtc();
            if (folosesteMatchedFilter && mfBuffer != null) {
                for (int k = 0; k < mfBuffer.length; k++) mfBuffer[k] = 0;
                mfBufferPos = 0;
                mfMarkAccRe = mfMarkAccIm = 0;
                mfSpaceAccRe = mfSpaceAccIm = 0;
            }
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
            // ATC doar pe Chebyshev: pe inregistrarea reala cu fading (W6IWI, 14086 kHz) MF + ATC
            // a scos 36 de randuri perfecte din 95, fata de 55 la MF simplu.
            diferentaBruta = folosesteAtc && !folosesteMatchedFilter
                    ? decizieAtc(markFiltrat, spaceFiltrat)
                    : markFiltrat - spaceFiltrat;

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
                    // Respingere outlieri - o masuratoare nefiziologic de mare e ignorata
                    // complet (nu doar trunchiata), tratata ca fereastra "murdara"/zgomot.
                    if (Math.abs(freq_error) <= AFC_PRAG_OUTLIER_HZ) {
                        // Filtru de bucla (EMA) - amortizeaza zgomotul dintre ferestre succesive.
                        freqErrorFiltrat = freqErrorFiltrat * (1.0 - AFC_EMA_ALPHA) + freq_error * AFC_EMA_ALPHA;

                        // Plafon pe marimea corectiei aplicate per fereastra.
                        double freqErrorLimitat = Math.max(-AFC_MAX_CORECTIE_HZ,
                                Math.min(AFC_MAX_CORECTIE_HZ, freqErrorFiltrat));

                        if (modUrmarireFrecventa == 1) {
                            frecventaTunedBaza += freqErrorLimitat * 0.05;
                        } else if (modUrmarireFrecventa == 2) {
                            frecventaTunedBaza += freqErrorLimitat * 0.25;
                        }

                        if (modUrmarireFrecventa > 0) {
                            sampleRateFiltre = sampleRate;
                            recalculeazaCoeficientiFrecventa();

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

            // Clock Recovery
            if (!inFrame) {
                if (bitCurent == 0) {
                    contorConfirmareStart++;
                    if (contorConfirmareStart >= pragConfirmareStart) {
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
                            if (listener != null) listener.onCodBaudot(currentByte & 0x1F);
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
