package com.example.yaesucatcontroladv;

/**
 * A-TUNE pentru emisie: unde trebuie dusa statia (VFO-A) ca emisia FSK sa plece exact pe frecventa
 * corespondentului. In modul RTTY al statiei (FSK) tonurile receptiei sunt fixe - Mark pe 2125 Hz,
 * shift 170 - iar statia emite acolo unde e acordata. Daca semnalul corespondentului se aude cu
 * Mark-ul in alta parte, statia se muta cu diferenta (QSY), nu decodorul.
 *
 * Ca in RttyDecoder (C#, HamRig RigMath.QsyDial): in RTTY-L statia lucreaza ca in LSB (ton audio
 * mai jos = frecventa RF mai mare), in RTTY-U ca in USB. Conteaza doar diferenta, nu ce arata
 * afisajul. Verificat pe statie (FTDX10 / FTX-1): dupa QSY emisia a cazut la 2 Hz de corespondent.
 */
public final class RttyQsy {

    /** Tonul Mark al statiei in modul RTTY (FSK). */
    public static final double MARK_FSK_HZ = 2125.0;

    /** Sub atat statia e deja pe frecventa (Hz). */
    public static final long TOLERANTA_HZ = 3;

    private RttyQsy() { }

    /** Codul de mod din raspunsul CAT "MD0x" (x): '6' RTTY-L, '9' RTTY-U. */
    public static boolean esteRtty(char codMod) {
        return codMod == '6' || codMod == '9';
    }

    /** Ultimul caracter din textul de mod salvat de MainActivity ("06", "09"...), sau ' '. */
    public static char codMod(String modText) {
        String m = modText == null ? "" : modText.replace(";", "").trim().toUpperCase(java.util.Locale.US);
        return m.isEmpty() ? ' ' : m.charAt(m.length() - 1);
    }

    /**
     * Noua frecventa VFO astfel incat Mark-ul auzit acum la markHz sa ajunga pe 2125 Hz, sau null
     * daca statia nu e in RTTY-L / RTTY-U ori frecventa nu se stie.
     */
    public static Long frecventaPentruEmisie(long dialHz, char codMod, double markHz) {
        if (dialHz <= 0 || !esteRtty(codMod)) return null;
        double delta = markHz - MARK_FSK_HZ;
        return Math.round(codMod == '9' ? dialHz + delta : dialHz - delta);
    }

    /**
     * Acordul fin al perechii de tonuri: FFT-ul de la A-TUNE are binuri de 15.6 Hz (pana la ~8 Hz
     * abatere) - destul pentru decodor (are AFC), prea grosier pentru QSY.
     *
     * Discriminator de faza pe fiecare ton, ca AFC-ul demodulatorului: semnalul se coboara in banda
     * de baza pe tonul de jos si pe cel de sus (josHz + shiftHz), se filtreaza (2 poli, 50 Hz) si, cat
     * timp un ton domina clar (putere de 4x peste celalalt, doua decizii la rand), rotatia fazei lui
     * intre doua decizii da abaterea de frecventa; abaterile celor doua tonuri se mediaza ponderat.
     * Doua treceri (a doua pe tonul corectat).
     *
     * CORECTIE fata de varianta cu maximul puterii (Goertzel): pe semnal real maximul depinde de
     * continut (RYRY fata de text) - pe inregistrarile W6IWI tonul sarea cu 5-10 Hz intre ferestre
     * de 5 s; cu discriminatorul abaterea fata de mediana e sub 1 Hz in 90% din ferestre
     * (14089: p90 1.0 Hz fata de 10.8; 14086: 0.4 fata de 6.8; QRN 7086: 0.8 fata de 6.0).
     *
     * @return tonul de jos rafinat, sau josHz daca audio-ul e prea scurt ori fara semnal clar
     */
    public static double rafineazaTonJos(short[] audio, int n, int sampleRate, double josHz, double shiftHz) {
        n = Math.min(n, audio.length);
        if (sampleRate <= 0 || n < sampleRate / 2) return josHz;
        int pas = Math.max(1, (int) Math.round(sampleRate / (RttyPolarity.BAUD * 8)));
        double a = 1 - Math.exp(-2 * Math.PI * DISCR_LPF_HZ / sampleRate);
        int sari = (int) (0.05 * sampleRate);   // filtrele se stabilizeaza
        double jos = josHz;
        for (int trecere = 0; trecere < 2; trecere++) {
            double[] f = {jos, jos + shiftHz};
            double[] c = new double[2], s = new double[2], o = {1, 1}, q = {0, 0};
            double[] r1 = new double[2], i1 = new double[2], r2 = new double[2], i2 = new double[2];
            double[] accRe = new double[2], accIm = new double[2], antRe = new double[2], antIm = new double[2];
            boolean[] antDomina = new boolean[2];
            for (int k = 0; k < 2; k++) {
                double w = 2 * Math.PI * f[k] / sampleRate;
                c[k] = Math.cos(w);
                s[k] = Math.sin(w);
            }
            for (int i = 0; i < n; i++) {
                double x = audio[i] / 32768.0;
                for (int k = 0; k < 2; k++) {
                    // x * e^{-jwi}, apoi doi poli reali in cascada
                    r1[k] += a * (x * o[k] - r1[k]);
                    i1[k] += a * (x * q[k] - i1[k]);
                    r2[k] += a * (r1[k] - r2[k]);
                    i2[k] += a * (i1[k] - i2[k]);
                    double t = o[k] * c[k] + q[k] * s[k];
                    q[k] = q[k] * c[k] - o[k] * s[k];
                    o[k] = t;
                }
                if ((i & 1023) == 1023) {       // renormalizare (erorile de rotunjire)
                    for (int k = 0; k < 2; k++) {
                        double g = 1 / Math.sqrt(o[k] * o[k] + q[k] * q[k]);
                        o[k] *= g;
                        q[k] *= g;
                    }
                }
                if (i < sari || i % pas != 0) continue;
                double p0 = r2[0] * r2[0] + i2[0] * i2[0], p1 = r2[1] * r2[1] + i2[1] * i2[1];
                boolean[] domina = {p0 > DISCR_DOMINANTA * p1, p1 > DISCR_DOMINANTA * p0};
                for (int k = 0; k < 2; k++) {
                    if (domina[k] && antDomina[k]) {   // z * conj(z anterior)
                        accRe[k] += r2[k] * antRe[k] + i2[k] * antIm[k];
                        accIm[k] += i2[k] * antRe[k] - r2[k] * antIm[k];
                    }
                    antRe[k] = r2[k];
                    antIm[k] = i2[k];
                    antDomina[k] = domina[k];
                }
            }
            double suma = 0, pondere = 0;
            for (int k = 0; k < 2; k++) {
                double mag = Math.sqrt(accRe[k] * accRe[k] + accIm[k] * accIm[k]);
                if (mag == 0) continue;
                // un ton mai sus cu df se roteste cu +df fata de oscilatorul local
                suma += mag * Math.atan2(accIm[k], accRe[k]) / (2 * Math.PI * pas / sampleRate);
                pondere += mag;
            }
            if (pondere == 0) return josHz;
            jos += Math.max(-DISCR_MAX_PAS_HZ, Math.min(DISCR_MAX_PAS_HZ, suma / pondere));
        }
        return jos;
    }

    /** Banda filtrelor discriminatorului (Hz). */
    private static final double DISCR_LPF_HZ = 50;

    /** De cate ori (in putere) trebuie sa domine un ton ca sa i se masoare faza. */
    private static final double DISCR_DOMINANTA = 4;

    /** Corectia maxima pe trecere (Hz) - binurile FFT dau pana la ~8 Hz abatere. */
    private static final double DISCR_MAX_PAS_HZ = 15;
}
