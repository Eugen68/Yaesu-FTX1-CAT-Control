package com.example.yaesucatcontroladv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

public class Waterfall extends View {

    public interface OnFrequencyTappedListener {
        void onFrequencyTapped(int frequencyHz);
    }

    // CIZELARE: rata de esantionare efectiva a feed-ului catre acest waterfall nu mai e
    // o constanta hardcodata local (fosta "SAMPLE_RATE = 8000", presupusa "din ochi"
    // sincronizata cu decimarea audio facuta in EcranRttyDroid). Acum e primita explicit
    // la constructie, ca sa existe o singura sursa de adevar pentru rata efectiva -
    // altfel o modificare a factorului de decimare intr-un singur loc ar fi dezaliniat
    // tacit axa de frecventa afisata fata de datele reale primite.
    private final int sampleRate;
    private final int maxDisplayFrequency;

    // CORECTIE (bug de concurenta): fosta metoda statica SimpleFFT.magnitudeSpectrum()
    // partaja o singura instanta SimpleFFT (cu buffere interne mutabile) intre acest
    // Waterfall si thread-ul separat de Auto-Tune din EcranRttyDroid - cele doua puteau
    // scrie concurent in aceleasi buffere, coruperea silentios calculul FFT pe unul sau
    // ambele. Acum fiecare apelant are propria instanta - vezi si comentariul din
    // SimpleFFT.java.
    private final SimpleFFT fft = new SimpleFFT(512);
    private final double[] magBuffer = new double[256];

    // CORECTIE (vizibilitate cross-thread): pushSamples() e apelat de pe thread-ul de
    // decodare audio, dar citea (si scrie, la swap) bitmap-urile de mai jos, scrise in
    // rest exclusiv pe UI thread (onSizeChanged, drawNewRow). Fara "volatile", JMM nu
    // garanteaza ca thread-ul audio vede vreodata actualizarile facute de UI thread -
    // risc de cursa de date (waterfall "inghetat" pe null vechi sau cadre pierdute
    // silentios), fara nicio exceptie care sa dea de gol problema.
    private volatile Bitmap waterfallBitmap;
    private volatile Bitmap scratchBitmap;
    private Canvas bitmapCanvas;
    // OPTIMIZARE: bitmap "scratch" preAlocat o singura data (in onSizeChanged), refolosit
    // la fiecare cadru prin swap de referinte, in loc sa alocam+reciclam un Bitmap nou
    // la fiecare rand nou de waterfall (aloca presiune GC pe threadul UI, concurand cu
    // threadul audio de decodare RTTY pentru CPU).
    private Canvas scratchCanvas;
    private final Paint pixelPaint = new Paint();
    private final Paint markerPaint = new Paint();

    // OPTIMIZARE: paleta de culori precalculata o singura data intr-un LUT de 256 de
    // intrari, in loc sa recalculam sqrt() + 3 ramuri min/max PENTRU FIECARE PIXEL DE
    // LATIME, la fiecare rand nou (mii de calcule redundante/secunda pentru aceeasi
    // magnitudine normalizata). Acelasi principiu ca la swap-ul de bitmap-uri: zero
    // munca repetitiva inutila in bucla fierbinte de desenare.
    private static final int[] COLOR_LUT = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            double normalized = Math.sqrt(i / 255.0);
            int blue = (int) (255 * Math.min(1.0, normalized * 3));
            int green = (int) (255 * Math.max(0.0, Math.min(1.0, (normalized - 0.33) * 3)));
            int red = (int) (255 * Math.max(0.0, Math.min(1.0, (normalized - 0.66) * 3)));
            COLOR_LUT[i] = Color.rgb(red, green, blue);
        }
    }

    // OPTIMIZARE: min/max pentru scalarea culorii nu se mai calculeaza brut, doar din
    // randul curent (ceea ce dadea o "pâlpâire" vizibila a culorilor intre randuri pe
    // zgomot variabil) - acum se tine o medie exponentiala (EMA) intre randuri, la fel
    // cum se face si pentru nivelul de zgomot in decodorul RTTY, pentru o scalare mult
    // mai stabila vizual.
    private float emaMaxVal = 1f;
    private float emaMinVal = 0f;
    private boolean emaInitializata = false;
    private static final float EMA_ALPHA_WATERFALL = 0.1f;

    private int tunedFrequencyHz = 2125;
    private int shiftHz = 170;
    private OnFrequencyTappedListener listener;

    public Waterfall(Context context, int sampleRate) {
        super(context);
        this.sampleRate = sampleRate;
        this.maxDisplayFrequency = sampleRate / 2;
        markerPaint.setColor(Color.RED);
        markerPaint.setStrokeWidth(3f);
    }

    public void setOnFrequencyTappedListener(OnFrequencyTappedListener l) {
        this.listener = l;
    }

    public void setShiftHz(int shiftHz) {
        this.shiftHz = shiftHz;
        postInvalidate();
    }

    public void setTunedFrequency(int frequencyHz) {
        this.tunedFrequencyHz = frequencyHz;
        postInvalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            waterfallBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bitmapCanvas = new Canvas(waterfallBitmap);
            bitmapCanvas.drawColor(Color.BLACK);

            scratchBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            scratchCanvas = new Canvas(scratchBitmap);
            scratchCanvas.drawColor(Color.BLACK);
        }
    }

    /**
     * Apelata de pe thread-ul de decodare audio RTTY. Calculul FFT ruleaza aici, pe
     * acelasi thread (instanta proprie fft/magBuffer, fara partajare cu alte thread-uri -
     * vezi comentariul de la declararea campurilor). Doar desenarea efectiva (drawNewRow)
     * e mutata pe UI thread prin post().
     */
    public void pushSamples(short[] samples) {
        if (waterfallBitmap == null) return;
        fft.calculeazaMagnitudine(samples, 0, magBuffer);
        float[] magnitude = new float[magBuffer.length];
        for (int i = 0; i < magnitude.length; i++) magnitude[i] = (float) magBuffer[i];
        post(() -> drawNewRow(magnitude));
    }

    private void drawNewRow(float[] magnitude) {
        if (waterfallBitmap == null || scratchBitmap == null) return;
        int width = waterfallBitmap.getWidth();
        int height = waterfallBitmap.getHeight();
        if (height < 2) return;

        // OPTIMIZARE: in loc sa cream un Bitmap nou (crop) la fiecare rand si sa-l reciclam
        // imediat (alocare+GC pe fiecare cadru), desenam continutul vechi, deplasat cu un
        // pixel in sus, direct in bitmap-ul "scratch" preAlocat, apoi facem swap de referinte.
        // Zero alocari noi in bucla fierbinte.
        scratchCanvas.drawColor(Color.BLACK);
        scratchCanvas.drawBitmap(waterfallBitmap, 0, -1, null);

        float maxVal = 0f;
        float minVal = Float.MAX_VALUE;
        for (float m : magnitude) {
            if (m > maxVal) maxVal = m;
            if (m < minVal) minVal = m;
        }

        if (!emaInitializata) {
            emaMaxVal = maxVal;
            emaMinVal = minVal;
            emaInitializata = true;
        } else {
            emaMaxVal = (EMA_ALPHA_WATERFALL * maxVal) + ((1f - EMA_ALPHA_WATERFALL) * emaMaxVal);
            emaMinVal = (EMA_ALPHA_WATERFALL * minVal) + ((1f - EMA_ALPHA_WATERFALL) * emaMinVal);
        }
        float range = Math.max(emaMaxVal - emaMinVal, 1f);

        for (int x = 0; x < width; x++) {
            int bin = (int) (((float) x / width) * magnitude.length);
            bin = Math.max(0, Math.min(bin, magnitude.length - 1));
            pixelPaint.setColor(magnitudeToColor(magnitude[bin], emaMinVal, range));
            scratchCanvas.drawPoint(x, height - 1, pixelPaint);
        }

        // Swap: bitmap-ul proaspat desenat (scratch) devine bitmap-ul activ afisat,
        // iar cel vechi devine noul "scratch" pentru cadrul urmator.
        Bitmap tmpBmp = waterfallBitmap;
        waterfallBitmap = scratchBitmap;
        scratchBitmap = tmpBmp;

        Canvas tmpCanvas = bitmapCanvas;
        bitmapCanvas = scratchCanvas;
        scratchCanvas = tmpCanvas;

        invalidate();
    }

    private int magnitudeToColor(float magnitude, float minVal, float range) {
        double normalized = (magnitude - minVal) / range;
        normalized = Math.max(0.0, Math.min(1.0, normalized));
        int idx = (int) Math.round(normalized * 255.0);
        idx = Math.max(0, Math.min(255, idx));
        return COLOR_LUT[idx];
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (waterfallBitmap != null) {
            canvas.drawBitmap(waterfallBitmap, 0, 0, null);
        }
        int width = getWidth();
        float markX = ((float) tunedFrequencyHz / maxDisplayFrequency) * width;
        canvas.drawLine(markX, 0, markX, getHeight(), markerPaint);

        float spaceX = (((float) tunedFrequencyHz + shiftHz) / maxDisplayFrequency) * width;
        canvas.drawLine(spaceX, 0, spaceX, getHeight(), markerPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getAction();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            int width = getWidth();
            if (width <= 0) return true;
            float x = event.getX();
            int frequencyHz = (int) ((x / width) * maxDisplayFrequency);
            frequencyHz = Math.max(0, Math.min(frequencyHz, maxDisplayFrequency));
            tunedFrequencyHz = frequencyHz;
            invalidate();
            if (listener != null) {
                listener.onFrequencyTapped(frequencyHz);
            }
            return true;
        }
        return super.onTouchEvent(event);
    }

    // ============================================================
    // ELIBERARE RESURSE LA INCHIDEREA APLICATIEI
    // Apelata din EcranRttyDroid.eliberareResurse()
    // ============================================================
    public void eliberareBitmap() {
        if (waterfallBitmap != null && !waterfallBitmap.isRecycled()) {
            waterfallBitmap.recycle();
            waterfallBitmap = null;
            bitmapCanvas = null;
        }
        if (scratchBitmap != null && !scratchBitmap.isRecycled()) {
            scratchBitmap.recycle();
            scratchBitmap = null;
            scratchCanvas = null;
        }
    }
}