package com.example.yaesucatcontroladv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

public class WaterfallView extends View {
    private Bitmap bitmapcascada;
    private Canvas canvasMemorie;
    private Paint paintLinie;
    private int[] spectruCulori;
    private final int latimeBandaRtty = 512; // Rezoluția spectrală fixă
    private final int inaltimeCascada = 200; // Înălțimea fixă a benzii deasupra terminalului

    public WaterfallView(Context context) {
        super(context);
        init();
    }

    private void init() {
        paintLinie = new Paint();
        // Paletă cromatică pre-calculată pentru viteză: de la albastru (0) la roșu intens (255)
        spectruCulori = new int[256];
        for (int i = 0; i < 256; i++) {
            int r = (i > 128) ? (i - 128) * 2 : 0;
            int g = (i < 128) ? i * 2 : (255 - i) * 2;
            int b = (i < 128) ? 255 - (i * 2) : 0;
            spectruCulori[i] = Color.rgb(r, g, b);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Generăm un bitmap intern de desenare asincronă pentru stabilitate grafică
        bitmapcascada = Bitmap.createBitmap(w, inaltimeCascada, Bitmap.Config.ARGB_8888);
        canvasMemorie = new Canvas(bitmapcascada);
        canvasMemorie.drawColor(Color.BLACK);
    }

    // Funcție apelată din thread-ul audio pentru a injecta o nouă linie de frecvențe în cascadă
    public void adaugaLinieFrecvente(double[] magnitudini) {
        if (bitmapcascada == null || canvasMemorie == null) return;

        int w = getWidth();
        // Pasul A: Curgerea cascadei în jos cu 1 pixel
        canvasMemorie.drawBitmap(bitmapcascada, 0, 1, null);

        // --- REPARAT COMPLET: Extindem banda pentru rezoluție și reducem gain-ul pentru a stinge roșul ars ---
        int startBin = 15;  // Coborâm la ~700 Hz pentru a vedea banda mai largă
        int endBin = 85;    // Urcăm la ~4000 Hz pentru definiție înaltă
        int binsUtili = endBin - startBin;

        double factorScalaX = (double) w / binsUtili;

        for (int i = 0; i < binsUtili; i++) {
            // CALIBRARE AGRESIVĂ GAIN: Am coborât multiplicatorul de la 500.0 la 35.0 pentru a elimina saturația!
            double mag = magnitudini[startBin + i] * 35.0;

            // Limitare matematică dură în interiorul paletei de culori
            if (mag > 255) mag = 255;
            if (mag < 0) mag = 0;

            paintLinie.setColor(spectruCulori[(int) mag]);

            float xStart = (float) (i * factorScalaX);
            float xEnd = (float) ((i + 1) * factorScalaX);

            // Desenăm pixelul curat pe linia superioară (y = 0)
            canvasMemorie.drawRect(xStart, 0, xEnd, 1, paintLinie);
        }

        // Solicităm redesenarea ferestrei
        postInvalidate();
    }


    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmapcascada != null) {
            // Desenăm imaginea asamblată pe ecran
            canvas.drawBitmap(bitmapcascada, 0, 0, null);
        }
    }
}
