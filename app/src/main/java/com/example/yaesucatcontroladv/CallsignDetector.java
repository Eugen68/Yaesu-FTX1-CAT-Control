package com.example.yaesucatcontroladv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ClickableSpan;
import android.view.View;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * REFACTORIZARE: extras din EcranRttyDroid.java - detectarea indicativelor in fluxul de
 * caractere decodate, afisarea lor colorata/clicabila in terminal, si actiunea la tap
 * (memorare pentru Logger + insertie automata "CALL DE MYCALL KN" in campul FSK).
 *
 * Nu e complet independenta de Android (are nevoie de Context/View-uri pentru UI), dar e
 * o singura responsabilitate clara, separata de DSP-ul audio si de constructia restului UI.
 */
public class CallsignDetector {

    public interface OnCallsignTappedListener {
        void onCallsignTapped(String callsign);
    }

    private final Context context;
    private final TextView tvTerminal;
    private final EditText etInputLive;
    private final ScrollView svScroll;
    private OnCallsignTappedListener listener;

    private final StringBuilder bufferCuvantCurent = new StringBuilder();

    public CallsignDetector(Context context, TextView tvTerminal, EditText etInputLive, ScrollView svScroll) {
        this.context = context;
        this.tvTerminal = tvTerminal;
        this.etInputLive = etInputLive;
        this.svScroll = svScroll;
    }

    public void setOnCallsignTappedListener(OnCallsignTappedListener listener) {
        this.listener = listener;
    }

    /** Goleste bufferul de cuvant curent - apelat la (re)pornirea decodarii/transmisiei. */
    public void resetBuffer() {
        bufferCuvantCurent.setLength(0);
    }

    private boolean esteIndicativValid(String text) {
        if (text == null) return false;
        text = text.trim().toUpperCase();
        if (text.length() < 3 || text.length() > 10) return false;
        return text.matches("^(?:[A-Z]{1,2}|[0-9][A-Z]|[A-Z][0-9])[0-9][A-Z]{1,4}$");
    }

    // ============================================================
    // 1. LOGICA COMUNA (Fara cozi de asteptare, executie IMEDIATA)
    // ============================================================
    private void executaActiuneCallsign(String callsign) {
        final String callFinal = callsign.toUpperCase().trim();

        // PASUL 1: Stocam MEREU in buffer-ul static pentru Logger
        EcranRttyDroid.callsignBuffer = callFinal;

        // Toast IMEDIAT (fara runOnUiThread)
        Toast.makeText(context, "\ud83d\udccb " + callFinal + " memorat", Toast.LENGTH_SHORT).show();

        // PASUL 2: Verificam focusul in FSK
        if (etInputLive != null && etInputLive.hasFocus()) {
            SharedPreferences prefs = context.getSharedPreferences(
                    EcranSetariProprii.PREFS_NAME, Context.MODE_PRIVATE);
            String myCall = prefs.getString(EcranSetariProprii.PREF_MY_CALL, "").trim().toUpperCase();

            if (!myCall.isEmpty()) {
                String textDeInserat = callFinal + " DE " + myCall + " KN";
                int cursorPos = etInputLive.getSelectionStart();
                if (cursorPos < 0) cursorPos = etInputLive.getText().length();

                // Inserare IMEDIATA la cursor
                etInputLive.getText().insert(cursorPos, textDeInserat);
                etInputLive.setSelection(cursorPos + textDeInserat.length());
            } else {
                Toast.makeText(context, "\u26a0\ufe0f Configureaza MY CALL in MY SETTINGS",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // ============================================================
    // 2. AFISARE IN TERMINAL (Doar creeaza textul auriu clicabil)
    // ============================================================
    private void afiseazaIndicativColorat(String callsign) {
        if (tvTerminal == null) return;
        final String callFinal = callsign.toUpperCase().trim();

        SpannableString spannable = new SpannableString(callFinal);
        ClickableSpan clickableSpan = new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                // Cand dai tap pe un indicativ deja afisat, doar executi actiunea
                // (NU il mai afiseaza a doua oara in terminal)
                onIndicativClick(callFinal);
            }
            @Override
            public void updateDrawState(TextPaint ds) {
                super.updateDrawState(ds);
                ds.setColor(Color.parseColor("#FFD700"));
                ds.setUnderlineText(true);
            }
        };
        spannable.setSpan(clickableSpan, 0, callFinal.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tvTerminal.append(spannable);
    }

    // ============================================================
    // 3. HANDLER PENTRU CLICK PE INDICATIV EXISTENT
    // ============================================================
    private void onIndicativClick(String callsign) {
        executaActiuneCallsign(callsign);
    }

    /**
     * Punct de intrare public - apelat cu fiecare caracter decodat din fluxul RTTY.
     * Acumuleaza cuvantul curent, si la separator (spatiu/newline) verifica daca
     * cuvantul e un indicativ valid, afisandu-l colorat/clicabil daca da.
     */
    public void proceseazaCaracterPentruClick(char c) {
        if (tvTerminal == null) return;

        if (c == ' ' || c == '\n' || c == '\r') {
            String cuvant = bufferCuvantCurent.toString().trim().toUpperCase();
            bufferCuvantCurent.setLength(0);

            if (!cuvant.isEmpty()) {
                if (esteIndicativValid(cuvant)) {
                    // DOAR afisare colorata (fara buffer, fara toast, fara FSK)
                    afiseazaIndicativColorat(cuvant);
                } else {
                    tvTerminal.append(cuvant);
                }
            }

            if (c == '\n' || c == '\r') tvTerminal.append("\n");
            else tvTerminal.append(" ");
        } else {
            bufferCuvantCurent.append(c);
        }

        if (svScroll != null) {
            svScroll.post(() -> svScroll.fullScroll(View.FOCUS_DOWN));
        }
    }
}
