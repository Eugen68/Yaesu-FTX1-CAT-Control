package com.example.yaesucatcontroladv;

public class Peak {
    private int mLastSample;
    private final float[] mPeakSingle;
    private final float[] mSpectrum;
    private final int[][] mSpectrumPeakArray;
    private int counter = 0;
    private final int[] mPeaks = new int[101];

    public Peak(int sampleRate, int fftSize, int maxFreq) {
        this.mLastSample = 640;
        double d = maxFreq;
        double d2 = sampleRate;
        double d3 = fftSize * 2;

        int i4 = (int) (d / (d2 / d3));
        this.mLastSample = i4;

        // REPARAT: Instanțiere standard clean pentru matricea de spectrum peak
        this.mSpectrumPeakArray = new int[i4][100];
        int i5 = this.mLastSample;
        this.mSpectrum = new float[i5];
        this.mPeakSingle = new float[i5];
    }

    public int[] calculateSpectrum(double[] dArr, int i) {
        int i2, i3, i4, i5;
        if (i == 0) {
            i2 = 10; i3 = 12; i4 = 15; i5 = 8;
        } else {
            i2 = 20; i3 = 32; i4 = 32; i5 = 16;
        }

        for (int i6 = 0; i6 < 100; i6++) {
            this.mPeaks[i6] = 0;
        }

        for (int i7 = 0; i7 < this.mLastSample; i7++) {
            this.mSpectrumPeakArray[i7][this.counter] = 0;
            for (int i8 = 0; i8 < i5; i8++) {
                if ((i7 + i8) < dArr.length) {
                    int[] iArr = this.mSpectrumPeakArray[i7];
                    int i9 = this.counter;
                    iArr[i9] = (int) (iArr[i9] + Math.abs(dArr[i7 + i8]));
                }
            }
            int[][] iArr2 = this.mSpectrumPeakArray;
            int[] iArr3 = iArr2[i7];
            int i10 = this.counter;
            iArr3[i10] = iArr2[i7][i10] / 8;
        }

        int i11 = this.counter;
        this.counter = i11 + 1;
        if (i11 >= 99) {
            this.counter = 0;
        }

        for (int i12 = 0; i12 < this.mLastSample; i12++) {
            int i13 = 0;
            for (int i14 = 0; i14 < 100; i14++) {
                i13 += this.mSpectrumPeakArray[i12][i14];
            }
            this.mSpectrum[i12] = i13;
        }

        for (int i15 = i2; i15 < this.mLastSample - i2; i15++) {
            float[] fArr = this.mPeakSingle;
            float[] fArr2 = this.mSpectrum;
            if ((i15 - i2) >= 0 && (i15 + i2) < fArr2.length && fArr2[i15 - i2] != 0 && fArr2[i15 + i2] != 0) {
                fArr[i15] = (fArr2[i15] / fArr2[i15 - i2]) * (fArr2[i15] / fArr2[i15 + i2]);
            } else {
                fArr[i15] = 0;
            }
        }

        int i16 = 5;
        int i17 = 0;
        while (i16 < this.mLastSample - i3) {
            if (this.mPeakSingle[i16] > 3.0f) {
                float f = 0.0f;
                for (int i18 = 0; i18 < i3; i18++) {
                    int i19 = i16 + i18;
                    if (i19 < this.mPeakSingle.length && this.mPeakSingle[i19] > f) {
                        f = this.mPeakSingle[i19];
                        // Mapează bin-ul FFT în frecvență Hz reală (Nyquist: bin * (Fs / N))
                        // unde N este de 2 ori mLastSample (deoarece FFT lucrează pe blocuri de 2*N pentru 8k)
                        this.mPeaks[i17] = (int) (i19 * (8000.0 / (this.mLastSample * 2.0)));
                    }
                }
                i16 += i4;
                i17++;
            }
            i16++;
        }

        int[] iArr4 = this.mPeaks;
        if (i17 < iArr4.length) {
            iArr4[i17] = 0;
        }
        return iArr4;
    }
}
