package com.example.yaesucatcontroladv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Waterfall pentru decodorul CW AI.
 *
 * Diferente fata de waterfall-ul RTTY:
 * - fara markerii roșii dubli mark/space (nu exista shift in CW);
 * - in modul Normal deseneaza banda de decodare (filterFreq +/- filterWidth/2) ca un
 *   dreptunghi semitransparent, plus o linie pe frecventa centrala tunata;
 * - in modul Pileup deseneaza cate o linie verde pentru fiecare stație urmarita, cu
 *   eticheta de frecventa si textul decodat (echivalentul `PileupOverlay.tsx`, adaptat la
 *   orientarea Android: axa X = frecventa).
 */
public class CwWaterfall extends Waterfall {

    private final Paint bandPaint = new Paint();
    private final Paint centerPaint = new Paint();
    private final Paint stationPaint = new Paint();
    private final Paint labelPaint = new Paint();

    private int filterFreqHz = -1;
    private int filterWidthHz = 0;
    private boolean showBand = true;

    private List<Integer> trackFrequencies = new ArrayList<>();
    private Map<Integer, String> trackTexts = new LinkedHashMap<>();

    public CwWaterfall(Context context, int sampleRate) {
        super(context, sampleRate);
        // Markerii RTTY nu au sens aici (waterfall-ul CW arata altfel decat cel RTTY).
        this.showRttyMarkers = false;

        bandPaint.setColor(Color.argb(60, 255, 64, 64));
        bandPaint.setStyle(Paint.Style.FILL);

        centerPaint.setColor(Color.argb(200, 255, 64, 64));
        centerPaint.setStrokeWidth(2f);

        stationPaint.setColor(Color.GREEN);
        stationPaint.setStrokeWidth(2f);
        stationPaint.setStyle(Paint.Style.STROKE);

        labelPaint.setColor(Color.GREEN);
        labelPaint.setTextSize(28f);
        labelPaint.setAntiAlias(true);
    }

    /** Banda de decodare din modul Normal. */
    public void setFilterBand(int freqHz, int widthHz) {
        this.filterFreqHz = freqHz;
        this.filterWidthHz = widthHz;
        postInvalidate();
    }

    public void setShowBand(boolean showBand) {
        this.showBand = showBand;
        postInvalidate();
    }

    /** Stațiile confirmate din modul Pileup (frecvente in Hz). */
    public void setTrackFrequencies(List<Integer> frequencies) {
        this.trackFrequencies = frequencies != null ? frequencies : new ArrayList<>();
        postInvalidate();
    }

    /** Textul decodat pentru fiecare frecventa urmarita. */
    public void setTrackTexts(Map<Integer, String> texts) {
        this.trackTexts = texts != null ? texts : new LinkedHashMap<>();
        postInvalidate();
    }

    public void clearOverlays() {
        this.trackFrequencies = new ArrayList<>();
        this.trackTexts = new LinkedHashMap<>();
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int height = getHeight();

        if (showBand && filterFreqHz > 0 && filterWidthHz > 0) {
            float left = frequencyToX(filterFreqHz - filterWidthHz / 2f);
            float right = frequencyToX(filterFreqHz + filterWidthHz / 2f);
            canvas.drawRect(left, 0, right, height, bandPaint);

            float centerX = frequencyToX(filterFreqHz);
            canvas.drawLine(centerX, 0, centerX, height, centerPaint);
        }

        for (int index = 0; index < trackFrequencies.size(); index++) {
            int freq = trackFrequencies.get(index);
            float x = frequencyToX(freq);
            canvas.drawLine(x, 0, x, height, stationPaint);
            canvas.drawCircle(x, height - 10, 5, stationPaint);

            String text = trackTexts.get(freq);
            String label = text != null && !text.trim().isEmpty()
                    ? freq + " Hz: " + text.trim()
                    : freq + " Hz";
            // Etichetele se escaloneaza vertical, ca sa nu se suprapuna intre stații.
            float y = 30f + (index % 4) * 34f;
            canvas.drawText(label, x + 6f, y, labelPaint);
        }
    }
}
