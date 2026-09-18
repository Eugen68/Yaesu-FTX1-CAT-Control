package com.example.yaesucatcontroladv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ClickableSpan;
import android.view.View;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedList;
import java.util.List;

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

    // Stare pentru detectie dublu-tap (Plan QRZ)
    private long lastTapTime = 0;
    private String lastTappedCallsign = null;
    private static final long DOUBLE_TAP_TIME_DELTA = 500; // milisecunde

    // FUNCTIONALITATE NOUA: pauza auto-scroll la interactiune manuala. Cand utilizatorul
    // atinge/deruleaza fereastra de terminal, oprim auto-scroll-ul (decodarea continua
    // normal, mesajele se acumuleaza in fundal) ca sa poata citi/derula liber mesaje mai
    // vechi. Daca nu mai atinge fereastra 3 secunde, reactivam auto-scroll-ul si sarim
    // direct la ultimul mesaj, aratand tot ce s-a acumulat cat timp a derulat manual.
    private final Handler handlerAutoScroll = new Handler(Looper.getMainLooper());
    private volatile boolean autoScrollPausat = false;
    private static final long INTARZIERE_REACTIVARE_AUTOSCROLL_MS = 3000;
    // CORECTIE COMPILARE: NU se initializeaza inline aici (ca inainte) - un initializator
    // de camp ruleaza INAINTE de corpul constructorului, deci lambda-ul ar referi svScroll
    // (camp final) inainte ca acesta sa fie garantat atribuit -> eroare "might not have
    // been initialized". Se atribuie in schimb in constructor, DUPA this.svScroll = svScroll.
    private final Runnable reactiveazaAutoScroll;

    /**
     * Apelata cand utilizatorul atinge/deruleaza manual fereastra de terminal - opreste
     * auto-scroll-ul si reporneste temporizatorul de 3 secunde de inactivitate.
     */
    public void inregistreazaInteractiuneManuala() {
        autoScrollPausat = true;
        handlerAutoScroll.removeCallbacks(reactiveazaAutoScroll);
        handlerAutoScroll.postDelayed(reactiveazaAutoScroll, INTARZIERE_REACTIVARE_AUTOSCROLL_MS);
    }

    public CallsignDetector(Context context, TextView tvTerminal, EditText etInputLive, ScrollView svScroll) {
        this.context = context;
        this.tvTerminal = tvTerminal;
        this.etInputLive = etInputLive;
        this.svScroll = svScroll;
        this.reactiveazaAutoScroll = () -> {
            autoScrollPausat = false;
            if (this.svScroll != null) {
                this.svScroll.post(() -> this.svScroll.fullScroll(View.FOCUS_DOWN));
            }
        };
    }

    public void setOnCallsignTappedListener(OnCallsignTappedListener listener) {
        this.listener = listener;
    }

    /** Goleste bufferul de cuvant curent - apelat la (re)pornirea decodarii/transmisiei. */
    public void resetBuffer() {
        bufferCuvantCurent.setLength(0);
    }

    private static boolean esteIndicativValid(String text) {
        if (text == null) return false;
        text = text.trim().toUpperCase();
        if (text.length() < 3 || text.length() > 10) return false;
        // Fara alternativa "litera+cifra" ca prefix - combinata cu cifra obligatorie de mai
        // jos, ar cere 2 cifre lipite (ex. "I00P"), ceea ce nu corespunde niciunui format
        // real de indicativ (fie prefixul e din litere, fie incepe cu cifra fixa de tara).
        return text.matches("^(?:[A-Z]{1,2}|[0-9][A-Z])[0-9][A-Z]{1,4}$");
    }

    // ============================================================
    // REPARARE CUVINTE LIPITE (decodorul CW e un model CTC care prezice el insusi
    // caracterul "spatiu" - la transmisii rapide/zgomotoase, "uita" sa prezica spatiul intre
    // doua cuvinte, rezultand blocuri lipite gen "CQCQDETM4GGRTM4GGR". Nu putem regla un prag
    // de timp (nu exista, modelul nu lucreaza cu durate), asa ca reparam la nivel de text:
    // recunoastem un set mic de prosemne/abrevieri CW fixe si folosim faptul ca dupa "DE"
    // urmeaza sigur un indicativ, plus conventia de a trimite indicativul de doua ori.
    // ============================================================

    // Prosemne/abrevieri CW fixe, cunoscute dinainte - baza vocabularului de segmentare.
    private static final java.util.Set<String> ANCORE_CUNOSCUTE = new java.util.HashSet<>(java.util.Arrays.asList(
            "CQ", "DE", "BK", "AR", "KN", "K", "QTH", "QRZ", "POTA", "QRT", "QRX", "CUAGN", "CALL", "DX", "73", "88"));

    // Scoruri PE CARACTER (nu fixe pe token!) - un token e in cele din urma o SUMA peste
    // caractere, deci daca am pune scoruri fixe, o ancora scurta (ex. "DX", 2 caractere) ar
    // avea o "densitate" de scor pe caracter mult mai mare decat un indicativ valid de 6
    // caractere - asta a produs exact bug-ul raportat: DP taia "OV1CDXOV1CDX" (doua copii ale
    // indicativului OV1CDX, lipite) in "OV1C | DX | OV1CDX", pentru ca scotea bonus-ul fix de
    // ancora din interiorul indicativului real. Cu scor proportional cu lungimea, a pastra
    // indicativul intreg (sau dublat) aduna mereu mai mult decat a rupe artificial o bucata
    // doar ca sa incasezi separat bonusul de ancora.
    // Ordinea ramane relevanta doar relativ: confirmat in MASTER.SCP > forma valida > ancora.
    private static final int SCOR_PE_CARACTER_ANCORA = 10;
    private static final int SCOR_PE_CARACTER_INDICATIV_CONFIRMAT = 15;
    private static final int SCOR_PE_CARACTER_INDICATIV_FORMAT = 12;
    // Penalizare FIXA pe bloc (nu proportionala cu lungimea) - astfel incat, daca nimic nu se
    // recunoaste, DP prefera UN singur bloc "necunoscut" mare in loc sa il taie in bucati mici
    // (fiecare bucata ar aduna din nou aceeasi penalizare, deci mai multe bucati = scor mai rau).
    private static final int PENALIZARE_NECUNOSCUT = -1000;

    private static int scorFragment(String fragment) {
        if (ANCORE_CUNOSCUTE.contains(fragment)) return fragment.length() * SCOR_PE_CARACTER_ANCORA;
        if (esteIndicativValid(fragment)) {
            int rataPeCaracter = CallsignDatabase.getInstance().isKnown(fragment)
                    ? SCOR_PE_CARACTER_INDICATIV_CONFIRMAT : SCOR_PE_CARACTER_INDICATIV_FORMAT;
            return fragment.length() * rataPeCaracter;
        }
        return PENALIZARE_NECUNOSCUT;
    }

    /**
     * Repara un text complet (cuvinte deja despartite prin spatii de model) cautand, in
     * cuvintele care nu sunt deja un indicativ valid de sine statator, prosemne/abrevieri CW
     * lipite de restul textului - si reintroduce spatiul care ar fi trebuit sa fie acolo.
     * Cuvintele care nu contin nimic recognoscibil raman neschimbate (fallback sigur).
     */
    public static String repareazaCuvinteLipite(String text) {
        if (text == null || text.isEmpty()) return text;
        String[] cuvinte = text.split(" ", -1);
        StringBuilder rezultat = new StringBuilder(text.length() + 16);
        for (int i = 0; i < cuvinte.length; i++) {
            if (i > 0) rezultat.append(' ');
            String cuv = cuvinte[i];
            if (cuv.isEmpty()) continue;
            if (cuv.length() <= 2 || esteIndicativValid(cuv)) {
                rezultat.append(cuv);
            } else {
                List<String> descompus = descompuneBlobLipit(cuv.toUpperCase());
                for (int j = 0; j < descompus.size(); j++) {
                    if (j > 0) rezultat.append(' ');
                    rezultat.append(descompus.get(j));
                }
            }
        }
        return rezultat.toString();
    }

    /**
     * Segmentare Viterbi (programare dinamica): alege taietura cu scorul total maxim, in loc
     * sa aplice reguli separate pentru inceput/mijloc/sfarsit de bloc. Un indicativ valid nu
     * se rupe in bucati decat daca bucatile rezultate scoreaza, la randul lor, la fel de bine
     * - ceea ce practic nu se intampla din intamplare, deci nu mai e nevoie sa restrictionam
     * manual pozitia ancorelor riscante (K/AR/KN).
     */
    private static List<String> descompuneBlobLipit(String blob) {
        int n = blob.length();
        int[] dp = new int[n + 1];
        int[] taiere = new int[n + 1];
        java.util.Arrays.fill(dp, Integer.MIN_VALUE);
        dp[0] = 0;

        for (int i = 1; i <= n; i++) {
            for (int start = 0; start < i; start++) {
                if (dp[start] == Integer.MIN_VALUE) continue;
                int scor = dp[start] + scorFragment(blob.substring(start, i));
                if (scor > dp[i]) {
                    dp[i] = scor;
                    taiere[i] = start;
                }
            }
        }

        LinkedList<String> rezultat = new LinkedList<>();
        int pos = n;
        while (pos > 0) {
            int start = taiere[pos];
            rezultat.addFirst(blob.substring(start, pos));
            pos = start;
        }
        return rezultat;
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

    // Auriu: format de indicativ valid (regex), dar negasit in MASTER.SCP.
    // Verde: format valid SI gasit exact in MASTER.SCP (semnal de incredere).
    // NU corectam niciodata automat textul - doar evidentiem diferit culoarea.
    private static final int CULOARE_INDICATIV_FORMAT_VALID = Color.parseColor("#FFD700");
    private static final int CULOARE_INDICATIV_CONFIRMAT = Color.parseColor("#69F0AE");

    private SpannableString creeazaSpanIndicativ(final String callFinal) {
        SpannableString spannable = new SpannableString(callFinal);
        final int culoare = CallsignDatabase.getInstance().isKnown(callFinal)
                ? CULOARE_INDICATIV_CONFIRMAT : CULOARE_INDICATIV_FORMAT_VALID;
        ClickableSpan clickableSpan = new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                // Cand dai tap pe un indicativ deja afisat, doar executi actiunea
                onIndicativClick(callFinal);
            }
            @Override
            public void updateDrawState(TextPaint ds) {
                super.updateDrawState(ds);
                ds.setColor(culoare);
                ds.setUnderlineText(true);
            }
        };
        spannable.setSpan(clickableSpan, 0, callFinal.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return spannable;
    }

    // ============================================================
    // 2. AFISARE IN TERMINAL (Doar creeaza textul auriu clicabil)
    // ============================================================
    private void afiseazaIndicativColorat(String callsign) {
        if (tvTerminal == null) return;
        tvTerminal.append(creeazaSpanIndicativ(callsign.toUpperCase().trim()));
    }

    // ============================================================
    // 3. HANDLER PENTRU CLICK PE INDICATIV EXISTENT
    // ============================================================
    private void onIndicativClick(String callsign) {
        final String callFinal = callsign.toUpperCase().trim();
        long tapTime = System.currentTimeMillis();

        if (callFinal.equals(lastTappedCallsign) && (tapTime - lastTapTime) < DOUBLE_TAP_TIME_DELTA) {
            // TAP DUBLU -> deschide QRZ.com in browser, nu repeta actiunea de tap simplu
            lastTapTime = 0;
            lastTappedCallsign = null;
            deschideQrzComInBrowser(callFinal);
        } else {
            // TAP SIMPLU (sau primul tap din serie)
            lastTapTime = tapTime;
            lastTappedCallsign = callFinal;
            executaActiuneCallsign(callFinal);
        }
    }

    private void deschideQrzComInBrowser(String callsign) {
        String url = "https://www.qrz.com/db/" + callsign;
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW);
        i.setData(android.net.Uri.parse(url));
        try {
            context.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(context, "Nu s-a putut deschide browser-ul", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Punct de intrare public STATELESS pentru ecrane care reconstruiesc complet textul afisat
     * la fiecare actualizare (ex: CW AI Decoder) - nu foloseste bufferCuvantCurent/tvTerminal.
     * Primeste un text complet (poate contine mai multe cuvinte), il tokenizeaza pe spatii/
     * newline, si intoarce un CharSequence cu indicativele valide evidentiate auriu/clicabile,
     * pastrand restul textului si separatorii nemodificati.
     */
    public CharSequence proceseazaTextComplet(String text) {
        if (text == null || text.isEmpty()) return "";
        android.text.SpannableStringBuilder builder = new android.text.SpannableStringBuilder();
        StringBuilder cuvant = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r') {
                adaugaCuvantInBuilder(builder, cuvant);
                builder.append(c);
            } else {
                cuvant.append(c);
            }
        }
        adaugaCuvantInBuilder(builder, cuvant);
        return builder;
    }

    private void adaugaCuvantInBuilder(android.text.SpannableStringBuilder builder, StringBuilder cuvant) {
        if (cuvant.length() == 0) return;
        String cuvantStr = cuvant.toString();
        cuvant.setLength(0);
        if (esteIndicativValid(cuvantStr)) {
            builder.append(creeazaSpanIndicativ(cuvantStr.toUpperCase().trim()));
        } else {
            builder.append(cuvantStr);
        }
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

        if (svScroll != null && !autoScrollPausat) {
            svScroll.post(() -> svScroll.fullScroll(View.FOCUS_DOWN));
        }
    }
}
