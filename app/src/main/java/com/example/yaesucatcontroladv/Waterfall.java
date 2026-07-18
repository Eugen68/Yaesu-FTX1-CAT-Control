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
        }
    }

    public void pushSamples(short[] samples) {
        if (waterfallBitmap == null) return;
        float[] magnitude = SimpleFFT.magnitudeSpectrum(samples);
        post(() -> drawNewRow(magnitude));
    }

    private void drawNewRow(float[] magnitude) {
        if (waterfallBitmap == null) return;
        int width = waterfallBitmap.getWidth();
        int height = waterfallBitmap.getHeight();
        if (height < 2) return;

        Bitmap shifted = Bitmap.createBitmap(waterfallBitmap, 0, 1, width, height - 1);
        bitmapCanvas.drawColor(Color.BLACK);
        bitmapCanvas.drawBitmap(shifted, 0, 0, null);
        shifted.recycle();

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
            bitmapCanvas.drawPoint(x, height - 1, pixelPaint);
        }
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
    }
}