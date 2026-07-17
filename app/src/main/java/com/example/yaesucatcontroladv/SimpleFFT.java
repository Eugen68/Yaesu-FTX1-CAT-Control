package com.example.yaesucatcontroladv;

public class SimpleFFT {
    private final int n;
    private final int log2n;
    private final double[] cos;
    private final double[] sin;
    private final double[] window;
    private final double[] x;
    private final double[] y;

    public SimpleFFT(int n) {
        this.n = n;
        this.log2n = (int) Math.round(Math.log(n) / Math.log(2));
        this.cos = new double[n / 2];
        this.sin = new double[n / 2];
        this.x = new double[n];
        this.y = new double[n];

        for (int i = 0; i < n / 2; i++) {
            cos[i] = Math.cos(-2 * Math.PI * i / n);
            sin[i] = Math.sin(-2 * Math.PI * i / n);
        }

        this.window = new double[n];
        for (int i = 0; i < n; i++) {
            window[i] = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (n - 1));
        }
    }

    public void calculeazaMagnitudine(short[] audioIn, int startPoz, double[] magnitudineOut) {
        int limit = Math.max(0, Math.min(n, audioIn.length - startPoz));

        for (int i = 0; i < limit; i++) {
            x[i] = (audioIn[startPoz + i] / 32768.0) * window[i];
            y[i] = 0;
        }
        for (int i = limit; i < n; i++) {
            x[i] = 0;
            y[i] = 0;
        }

        int i, j, k, n1, n2, a;
        double c, s, t1, t2;
        j = 0;
        n2 = n / 2;
        for (i = 1; i < n - 1; i++) {
            n1 = n2;
            while (j >= n1) {
                j -= n1;
                n1 /= 2;
            }
            j += n1;
            if (i < j) {
                t1 = x[i]; x[i] = x[j]; x[j] = t1;
                t1 = y[i]; y[i] = y[j]; y[j] = t1;
            }
        }

        n1 = 0;
        n2 = 1;
        for (i = 0; i < log2n; i++) {
            n1 = n2;
            n2 = n2 * 2;
            a = 0;
            int step = 1 << (log2n - i - 1);
            for (j = 0; j < n1; j++) {
                c = cos[a];
                s = sin[a];
                a += step;
                for (k = j; k < n; k += n2) {
                    t1 = c * x[k + n1] - s * y[k + n1];
                    t2 = s * x[k + n1] + c * y[k + n1];
                    x[k + n1] = x[k] - t1;
                    y[k + n1] = y[k] - t2;
                    x[k] += t1;
                    y[k] += t2;
                }
            }
        }

        for (int m = 0; m < n / 2; m++) {
            magnitudineOut[m] = Math.sqrt(x[m] * x[m] + y[m] * y[m]);
        }
    }

    private static SimpleFFT instantaStatica;

    public static float[] magnitudeSpectrum(short[] audioIn) {
        if (instantaStatica == null) {
            instantaStatica = new SimpleFFT(512);
        }

        double[] magOut = new double[256];
        instantaStatica.calculeazaMagnitudine(audioIn, 0, magOut);

        float[] rezultat = new float[256];
        for (int i = 0; i < 256; i++) {
            rezultat[i] = (float) magOut[i];
        }
        return rezultat;
    }
}