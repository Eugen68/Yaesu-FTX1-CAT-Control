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

    private static final int SAMPLE_RATE = 8000;
    private static final int MAX_DISPLAY_FREQUENCY = SAMPLE_RATE / 2;

    private Bitmap waterfallBitmap;
    private Canvas bitmapCanvas;
    // OPTIMIZARE: bitmap "scratch" preAlocat o singura data (in onSizeChanged), refolosit
    // la fiecare cadru prin swap de referinte, in loc sa alocam+reciclam un Bitmap nou
    // la fiecare rand nou de waterfall (aloca presiune GC pe threadul UI, concurand cu
    // threadul audio de decodare RTTY pentru CPU).
    private Bitmap scratchBitmap;
    private Canvas scratchCanvas;
    private final Paint pixelPaint = new Paint();
    private final Paint markerPaint = new Paint();

    private int tunedFrequencyHz = 2125;
    private int shiftHz = 170;
    private OnFrequencyTappedListener listener;

    public Waterfall(Context context) {
        super(context);
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

    public void pushSamples(short[] samples) {
        if (waterfallBitmap == null) return;
        float[] magnitude = SimpleFFT.magnitudeSpectrum(samples);
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
        float range = Math.max(maxVal - minVal, 1f);

        for (int x = 0; x < width; x++) {
            int bin = (int) (((float) x / width) * magnitude.length);
            bin = Math.max(0, Math.min(bin, magnitude.length - 1));
            pixelPaint.setColor(magnitudeToColor(magnitude[bin], minVal, range));
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
        normalized = Math.sqrt(normalized);

        int blue = (int) (255 * Math.min(1.0, normalized * 3));
        int green = (int) (255 * Math.max(0.0, Math.min(1.0, (normalized - 0.33) * 3)));
        int red = (int) (255 * Math.max(0.0, Math.min(1.0, (normalized - 0.66) * 3)));
        return Color.rgb(red, green, blue);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (waterfallBitmap != null) {
            canvas.drawBitmap(waterfallBitmap, 0, 0, null);
        }
        int width = getWidth();
        float markX = ((float) tunedFrequencyHz / MAX_DISPLAY_FREQUENCY) * width;
        canvas.drawLine(markX, 0, markX, getHeight(), markerPaint);

        float spaceX = (((float) tunedFrequencyHz + shiftHz) / MAX_DISPLAY_FREQUENCY) * width;
        canvas.drawLine(spaceX, 0, spaceX, getHeight(), markerPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getAction();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            int width = getWidth();
            if (width <= 0) return true;
            float x = event.getX();
            int frequencyHz = (int) ((x / width) * MAX_DISPLAY_FREQUENCY);
            frequencyHz = Math.max(0, Math.min(frequencyHz, MAX_DISPLAY_FREQUENCY));
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