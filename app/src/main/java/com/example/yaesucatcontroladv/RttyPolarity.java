package com.example.yaesucatcontroladv;

/**
 * Care dintre cele doua tonuri ale unui semnal RTTY e Mark (NORM sau REV), fara sa stii modul
 * statiei (RTTY-L / RTTY-U) sau setarile corespondentului.
 *
 * In spectru tonurile arata la fel, dar in timp nu. Doua indicii, in ordinea increderii:
 *
 * 1. Duratele: bitul de stop are 1.5 biti, deci doar Mark-ul are rulari de lungime fractionara
 *    (1.5, 2.5... biti); rularile de Space sunt mereu numere intregi de biti. Se numara, pentru
 *    fiecare ton, ce fractie din rularile lui (1.2-8 biti) are partea fractionara ~0.5.
 *    CORECTIE fata de portul din C#: incadrarea singura se insela pe RYRY (biti alternanti, aproape
 *    simetrici la inversare) - pe inregistrarea reala 14089 (W6IWI, RYRY) 10 decizii gresite din
 *    180; cu duratele 0 din 402, iar pe 14086 / QRN 0 gresite din ~390, la 0 decizii pe 1000 de
 *    ferestre de zgomot.
 * 2. Incadrarea (doar daca duratele nu decid, de ex. stop de 1 sau 2 biti): cu polaritatea corecta
 *    fiecare caracter incepe cu start pe Space si se termina cu stop pe Mark; cu praguri stricte
 *    (12 caractere, diferenta 0.30, scor 0.6), ca sa nu se insele pe RYRY.
 *
 * Pe zgomot sau semnal prea scurt rezultatul e nesigur (markJos == null) si A-TUNE pastreaza
 * NORM/REV-ul curent.
 *
 * Pornit din RttyDecoder (C#, RttyDecoder.Core/Dsp/RttyPolarity.cs).
 */
public final class RttyPolarity {

    public static final double BAUD = 45.45;

    /** Esantioane de decizie pe bit. */
    private static final int PE_BIT = 8;

    /** Latimea de banda a filtrelor de ton (Hz) - cat sa urmareasca bitii de 22 ms. */
    private static final double FILTRU_HZ = 35;

    /** Duratele: diferenta minima intre fractiile de rulari x.5 ale celor doua tonuri. */
    public static final double MARJA_DURATE = 0.20;

    /** Duratele: minimul de rulari (1.2-8 biti, ambele tonuri) ca sa decida. */
    public static final int MIN_RULARI = 16;

    /**
     * Duratele decid doar daca macar una dintre ipoteze incadreaza atatea caractere (0..1): altfel in
     * fereastra nu e semnal decodabil (pauza, alt semnal, QRN) si rularile sunt intamplatoare. Pe
     * inregistrarile reale (toate ferestrele de 5 s cu pas 1 s, fara filtrul de semnal al A-TUNE, NORM
     * + REV oglindit) fara ea: 14086 39 gresite din 4475, QRN 7086 91 din 2147; cu ea: 0 din 4426,
     * 1 din 1843 (RYRY 14089: 1 din 889).
     */
    public static final double MIN_INCADRARE_DURATE = 0.30;

    /** Incadrarea: minimul de caractere incadrate ca sa decida. */
    public static final int MIN_CADRE = 12;

    /** Incadrarea: diferenta minima intre scoruri ca sa decida. */
    public static final double MARJA_MIN = 0.30;

    /** Incadrarea: scorul minim al ipotezei castigatoare. */
    public static final double SCOR_MIN = 0.6;

    private RttyPolarity() { }

    /** Rezultatul detectiei. */
    public static final class Rezultat {
        /** true = Mark e tonul de jos (NORM), false = tonul de sus (REV), null = nu se poate spune. */
        public final Boolean markJos;
        /** Proportia de caractere bine incadrate cu ipoteza "Mark = tonul de jos" (0..1). */
        public final double scorJos;
        /** La fel, cu ipoteza "Mark = tonul de sus". */
        public final double scorSus;
        /** Cate caractere s-au incadrat (cu ipoteza castigatoare). */
        public final int cadre;
        /** Fractia rularilor tonului de jos / de sus cu lungime ~x.5 biti (Mark-ul are mai multe). */
        public final double fractieJos, fractieSus;

        Rezultat(Boolean markJos, double scorJos, double scorSus, int cadre, double fractieJos, double fractieSus) {
            this.markJos = markJos;
            this.scorJos = scorJos;
            this.scorSus = scorSus;
            this.cadre = cadre;
            this.fractieJos = fractieJos;
            this.fractieSus = fractieSus;
        }
    }

    /**
     * @param audio      ultimele secunde de semnal (PCM 16 bit mono), ideal 3-6 s
     * @param n          cate esantioane din audio se folosesc
     * @param sampleRate rata (Hz)
     * @param josHz      tonul de jos al perechii (Hz)
     * @param susHz      tonul de sus (Hz), de obicei josHz + 170
     */
    public static Rezultat detecteaza(short[] audio, int n, int sampleRate, double josHz, double susHz) {
        n = Math.min(n, audio.length);
        if (sampleRate <= 0 || n < sampleRate) return new Rezultat(null, 0, 0, 0, 0, 0);

        // 1. care ton e mai puternic, de PE_BIT ori pe bit: doua filtre complexe de ordinul 1
        //    (rezonatoare); oscilatoarele locale sunt fazori rotiti (fara cos/sin pe esantion)
        int pas = Math.max(1, (int) Math.round(sampleRate / (BAUD * PE_BIT)));
        double spb = sampleRate / BAUD / pas;   // esantioane de decizie pe bit (~8)
        double a = 1 - Math.exp(-2 * Math.PI * FILTRU_HZ / sampleRate);
        double wj = 2 * Math.PI * josHz / sampleRate, ws = 2 * Math.PI * susHz / sampleRate;
        double cj = Math.cos(wj), sj = Math.sin(wj), cs = Math.cos(ws), ss = Math.sin(ws);
        double oj = 1, qj = 0, os = 1, qs = 0;  // e^{-i w k}: (o, q) = (cos, -sin)
        double jr = 0, ji = 0, sr = 0, si = 0;
        int sari = (int) (0.05 * sampleRate);   // filtrele se stabilizeaza
        boolean[] josPornit = new boolean[n / pas + 1];
        int m = 0;
        for (int i = 0; i < n; i++) {
            double x = audio[i] / 32768.0;
            jr += a * (x * oj - jr);
            ji += a * (x * qj - ji);
            sr += a * (x * os - sr);
            si += a * (x * qs - si);
            if (i >= sari && i % pas == 0) josPornit[m++] = jr * jr + ji * ji > sr * sr + si * si;

            // fazorii avanseaza cu -w
            double t = oj * cj + qj * sj;
            qj = qj * cj - oj * sj;
            oj = t;
            t = os * cs + qs * ss;
            qs = qs * cs - os * ss;
            os = t;
            if ((i & 1023) == 1023) {           // renormalizare (erorile de rotunjire)
                double gj = 1 / Math.sqrt(oj * oj + qj * qj), gs = 1 / Math.sqrt(os * os + qs * qs);
                oj *= gj; qj *= gj; os *= gs; qs *= gs;
            }
        }

        // 2. duratele: rularile fractionare (bitul de stop de 1.5) sunt ale Mark-ului
        double[] dj = durate(josPornit, m, spb, true);
        double[] ds = durate(josPornit, m, spb, false);

        // 3. incadrarea caracterelor cu fiecare ipoteza
        double[] jos = scor(josPornit, m, spb, true);
        double[] sus = scor(josPornit, m, spb, false);

        if (dj[1] + ds[1] >= MIN_RULARI && Math.abs(dj[0] - ds[0]) >= MARJA_DURATE
                && Math.max(jos[0], sus[0]) >= MIN_INCADRARE_DURATE) {
            boolean markJos = dj[0] > ds[0];
            return new Rezultat(markJos, jos[0], sus[0], (int) (markJos ? jos[1] : sus[1]), dj[0], ds[0]);
        }
        boolean markJos = jos[0] >= sus[0];
        int cadre = (int) (markJos ? jos[1] : sus[1]);
        boolean sigur = cadre >= MIN_CADRE && Math.abs(jos[0] - sus[0]) >= MARJA_MIN
                && Math.max(jos[0], sus[0]) >= SCOR_MIN;
        return new Rezultat(sigur ? Boolean.valueOf(markJos) : null, jos[0], sus[0], cadre, dj[0], ds[0]);
    }

    /**
     * Rularile unui ton (fara prima si ultima, taiate de fereastra) cu lungimea intre 1.2 si 8 biti.
     *
     * @return {fractia celor cu partea fractionara intre 0.3 si 0.7, cate rulari}
     */
    private static double[] durate(boolean[] josPornit, int m, double spb, boolean tonJos) {
        int fractionare = 0, total = 0, i = 0;
        while (i < m) {
            int j = i;
            while (j < m && josPornit[j] == josPornit[i]) j++;
            if (josPornit[i] == tonJos && i > 0 && j < m) {
                double biti = (j - i) / spb;
                if (biti > 1.2 && biti < 8) {
                    total++;
                    double f = biti - Math.floor(biti);
                    if (f > 0.3 && f < 0.7) fractionare++;
                }
            }
            i = j;
        }
        return new double[]{total == 0 ? 0 : (double) fractionare / total, total};
    }

    /**
     * UART asincron: la fiecare trecere Mark -> Space (start), bitul de start trebuie sa fie Space
     * la mijloc, iar stopul Mark la 6.5 si 7.2 biti. Un caracter bun sare peste el; unul rau avanseaza
     * un esantion. Scorul = caractere bune / caractere incercate.
     *
     * @return {scor, caractere bune}
     */
    private static double[] scor(boolean[] josPornit, int m, double spb, boolean markJos) {
        int bune = 0, rele = 0;
        int nevoie = (int) Math.ceil(7.3 * spb);
        int i = 1;
        while (i + nevoie < m) {
            if (josPornit[i - 1] == markJos && josPornit[i] != markJos) {
                boolean ok = josPornit[i + (int) (0.5 * spb)] != markJos
                        && josPornit[i + (int) (6.5 * spb)] == markJos
                        && josPornit[i + (int) (7.2 * spb)] == markJos;
                if (ok) {
                    bune++;
                    i += (int) (7.0 * spb);   // in bitul de stop; urmatorul start vine dupa
                    continue;
                }
                rele++;
            }
            i++;
        }
        int total = bune + rele;
        return new double[]{total == 0 ? 0 : (double) bune / total, bune};
    }

    /**
     * Ultimele secunde de audio, scrise de firul de receptie si citite de A-TUNE. Sincronizat.
     */
    public static final class Istoric {
        private final short[] inel;
        private int poz;
        private int count;

        public Istoric(int sampleRate, double secunde) {
            inel = new short[Math.max(1, (int) (sampleRate * secunde))];
        }

        public synchronized void adauga(short[] x, int n) {
            for (int i = 0; i < n; i++) {
                inel[poz] = x[i];
                poz = poz + 1 == inel.length ? 0 : poz + 1;
            }
            count = Math.min(inel.length, count + n);
        }

        /** Ultimele maxim n esantioane, in ordine. */
        public synchronized short[] ultimele(int n) {
            n = Math.min(n, count);
            short[] r = new short[n];
            int start = (poz - n + inel.length) % inel.length;
            for (int i = 0; i < n; i++) r[i] = inel[(start + i) % inel.length];
            return r;
        }

        public synchronized void goleste() {
            poz = 0;
            count = 0;
        }
    }
}
