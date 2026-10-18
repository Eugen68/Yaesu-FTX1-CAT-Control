package com.example.yaesucatcontroladv;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

/**
 * Banc de test pentru RttyDemodulator cu semnal RTTY sintetic (AFSK cu faza continua,
 * 45.45 baud, shift 170 Hz, 1.5 biti de stop) + zgomot gaussian limitat la banda unui
 * filtru SSB (~300-3000 Hz), cu seed fix - rezultatele sunt reproductibile, deci se pot
 * compara obiectiv variante ale demodulatorului.
 *
 * SNR-ul e raportat la puterea zgomotului din banda filtrului (~2.7 kHz), conventia
 * uzuala pentru moduri digitale (ex. WSJT-X foloseste 2.5 kHz).
 *
 * Testele cu assert verifica doar ca demodulatorul decodeaza corect un semnal curat;
 * baleiajul de SNR (raportSnr) doar afiseaza CER-ul, ca punct de comparatie.
 */
public class RttyDemodulatorTest {

    private static final int FS = 48000;
    private static final double MARK_HZ = 2125.0;
    private static final double BAUD = RttyDemodulator.BAUD_RTTY;
    private static final String TEXT =
            "CQ CQ CQ DE YO9VA YO9VA YO9VA PSE K\n"
                    + "THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG 1234567890\n"
                    + "UR RST 599 599 NAME EUGEN QTH PLOIESTI KN 35 73 SK\n";

    @Test
    public void semnalCuratChebyshev() {
        double cer = cer(decodeaza(TEXT, Double.POSITIVE_INFINITY, false, 1));
        assertTrue("CER Chebyshev pe semnal curat = " + cer, cer < 0.02);
    }

    @Test
    public void semnalCuratMatchedFilter() {
        double cer = cer(decodeaza(TEXT, Double.POSITIVE_INFINITY, true, 1));
        assertTrue("CER Matched Filter pe semnal curat = " + cer, cer < 0.02);
    }

    @Test
    public void semnalPuternicCuZgomot() {
        double cer = cer(decodeaza(TEXT, 20.0, false, 1));
        assertTrue("CER Chebyshev la +20 dB = " + cer, cer < 0.05);
    }

    /**
     * Regresie pentru filtrul de decizie (lpfDecizieHz): fara el, Chebyshev avea ~67% CER
     * la -3 dB; cu el ~0%. Medie pe 3 seed-uri, ca un seed norocos sa nu ascunda o regresie.
     */
    @Test
    public void semnalSlabChebyshev() {
        double cer = 0;
        for (int seed = 1; seed <= 3; seed++) cer += cer(decodeaza(TEXT, -3.0, false, seed)) / 3;
        assertTrue("CER Chebyshev la -3 dB = " + cer, cer < 0.05);
    }

    @Test
    public void semnalSlabMatchedFilter() {
        double cer = 0;
        for (int seed = 1; seed <= 3; seed++) cer += cer(decodeaza(TEXT, -3.0, true, seed)) / 3;
        assertTrue("CER Matched Filter la -3 dB = " + cer, cer < 0.05);
    }

    /** Acord imperfect (tap pe waterfall) si viteza de manipulare usor diferita, la 0 dB. */
    @Test
    public void acordSiVitezaImperfecte() {
        double[][] cazuri = {{15, BAUD}, {-15, BAUD}, {0, 45.0}, {0, 45.9}};
        for (double[] cz : cazuri) {
            for (boolean mf : new boolean[]{false, true}) {
                double cer = cer(decodeaza(genereaza(TEXT, 0.0, 1, cz[0], cz[1]), mf, d -> { }));
                assertTrue(String.format("CER %s offset %+.0f Hz baud %.2f = %.3f",
                        mf ? "MF" : "Chebyshev", cz[0], cz[1], cer), cer < 0.05);
            }
        }
    }

    /** Baleiaj de SNR: afiseaza CER-ul (medie pe 3 seed-uri) pentru ambii detectori. */
    @Test
    public void raportSnr() {
        double[] snrs = {20, 10, 6, 3, 0, -3, -6};
        StringBuilder sb = new StringBuilder("\nSNR (dB/2.7kHz) | CER Chebyshev | CER Matched Filter\n");
        for (double snr : snrs) {
            double cheb = 0, mf = 0;
            for (int seed = 1; seed <= 3; seed++) {
                cheb += cer(decodeaza(TEXT, snr, false, seed));
                mf += cer(decodeaza(TEXT, snr, true, seed));
            }
            sb.append(String.format("%15.0f | %12.1f%% | %17.1f%%\n", snr, cheb / 3 * 100, mf / 3 * 100));
        }
        System.out.println(sb);
    }

    // ============================================================

    private double cer(String decodat) {
        return (double) levenshtein(normalizeaza(TEXT), normalizeaza(decodat)) / normalizeaza(TEXT).length();
    }

    private static String normalizeaza(String s) {
        return s.replace('\r', '\n').replaceAll("\\s+", " ").trim();
    }

    static String decodeaza(String text, double snrDb, boolean matchedFilter, long seed) {
        return decodeaza(text, snrDb, matchedFilter, seed, d -> { });
    }

    static String decodeaza(String text, double snrDb, boolean matchedFilter, long seed,
                            java.util.function.Consumer<RttyDemodulator> config) {
        return decodeaza(genereaza(text, snrDb, seed, 0, BAUD), matchedFilter, config);
    }

    static String decodeaza(short[] audio, boolean matchedFilter,
                            java.util.function.Consumer<RttyDemodulator> config) {
        RttyDemodulator demod = new RttyDemodulator();
        demod.folosesteMatchedFilter = matchedFilter;
        config.accept(demod);
        BaudotCodec rx = new BaudotCodec();
        StringBuilder out = new StringBuilder();
        demod.setListener(new RttyDemodulator.Listener() {
            @Override
            public void onCodBaudot(int cod) {
                char c = rx.decode(cod);
                if (c != '\0') out.append(c);
            }

            @Override
            public void onStare(String snrText, int frecventaMarkHz) {
            }
        });
        demod.porneste(FS);

        int bloc = 3840;
        short[] buf = new short[bloc];
        for (int pos = 0; pos < audio.length; pos += bloc) {
            int n = Math.min(bloc, audio.length - pos);
            System.arraycopy(audio, pos, buf, 0, n);
            demod.proceseazaBloc(buf, n);
        }
        return out.toString();
    }

    /** AFSK RTTY cu faza continua: 0.5 s idle Mark, cateva LTRS, textul, 0.5 s idle Mark. */
    static short[] genereaza(String text, double snrDb, long seed, double offsetHz, double baud) {
        StringBuilder biti = new StringBuilder(); // '1' = Mark, '0' = Space, fiecare = 1/2 bit
        BaudotCodec tx = new BaudotCodec();
        for (int i = 0; i < 46; i++) biti.append("11"); // ~0.5 s idle Mark
        int[] preambul = {0x1F, 0x1F, 0x1F};
        for (int c : preambul) adaugaCaracter(biti, c);
        for (char ch : text.toCharArray()) {
            for (int c : tx.encode(ch)) adaugaCaracter(biti, c);
        }
        for (int i = 0; i < 46; i++) biti.append("11");

        double samplesPerHalfBit = FS / baud / 2.0;
        int n = (int) Math.ceil(biti.length() * samplesPerHalfBit);
        double amplitudine = 0.3;
        double[] semnal = new double[n];
        double faza = 0;
        for (int i = 0; i < n; i++) {
            int idx = Math.min(biti.length() - 1, (int) (i / samplesPerHalfBit));
            double f = (biti.charAt(idx) == '1' ? MARK_HZ : MARK_HZ + RttyDemodulator.SHIFT_HZ) + offsetHz;
            faza += 2 * Math.PI * f / FS;
            semnal[i] = amplitudine * Math.sin(faza);
        }

        if (!Double.isInfinite(snrDb)) {
            // Zgomot alb filtrat la ~300-3000 Hz (HP + LP Butterworth de ordin 2, in cascada x2),
            // apoi scalat ca puterea lui sa dea SNR-ul cerut fata de puterea semnalului.
            Random rnd = new Random(seed);
            double[] zgomot = new double[n];
            for (int i = 0; i < n; i++) zgomot[i] = rnd.nextGaussian();
            Biquad hp1 = Biquad.highpass(300, FS), hp2 = Biquad.highpass(300, FS);
            Biquad lp1 = Biquad.lowpass(3000, FS), lp2 = Biquad.lowpass(3000, FS);
            double putereZgomot = 0;
            for (int i = 0; i < n; i++) {
                zgomot[i] = lp2.proceseaza(lp1.proceseaza(hp2.proceseaza(hp1.proceseaza(zgomot[i]))));
                putereZgomot += zgomot[i] * zgomot[i];
            }
            putereZgomot /= n;
            double putereSemnal = amplitudine * amplitudine / 2.0;
            double scala = Math.sqrt(putereSemnal / Math.pow(10, snrDb / 10.0) / putereZgomot);
            for (int i = 0; i < n; i++) semnal[i] += zgomot[i] * scala;
        }

        short[] pcm = new short[n];
        for (int i = 0; i < n; i++) {
            pcm[i] = (short) Math.max(-32768, Math.min(32767, Math.round(semnal[i] * 32767)));
        }
        return pcm;
    }

    /** Start (1 bit Space), 5 biti de date LSB-first, stop 1.5 biti Mark - in jumatati de bit. */
    private static void adaugaCaracter(StringBuilder biti, int cod) {
        biti.append("00");
        for (int b = 0; b < 5; b++) biti.append(((cod >> b) & 1) == 1 ? "11" : "00");
        biti.append("111");
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    /** Biquad RBJ de ordin 2 (Q = 1/sqrt(2)). */
    private static final class Biquad {
        private final double b0, b1, b2, a1, a2;
        private double x1, x2, y1, y2;

        private Biquad(double b0, double b1, double b2, double a0, double a1, double a2) {
            this.b0 = b0 / a0; this.b1 = b1 / a0; this.b2 = b2 / a0;
            this.a1 = a1 / a0; this.a2 = a2 / a0;
        }

        static Biquad lowpass(double fc, double fs) {
            double w = 2 * Math.PI * fc / fs, alpha = Math.sin(w) / Math.sqrt(2), c = Math.cos(w);
            return new Biquad((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha);
        }

        static Biquad highpass(double fc, double fs) {
            double w = 2 * Math.PI * fc / fs, alpha = Math.sin(w) / Math.sqrt(2), c = Math.cos(w);
            return new Biquad((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha);
        }

        double proceseaza(double x) {
            double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = x;
            y2 = y1; y1 = y;
            return y;
        }
    }
}
