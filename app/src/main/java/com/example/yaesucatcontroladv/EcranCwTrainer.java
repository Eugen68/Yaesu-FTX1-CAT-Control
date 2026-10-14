package com.example.yaesucatcontroladv;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.UnderlineSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.example.yaesucatcontroladv.cwtraining.MorseKeyerListener;
import com.example.yaesucatcontroladv.deepcw.audio.CwTrainingKeyer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Pagina CW TRAINER - antrenament de EMISIE (manipulare) cu paletele de pe ecran.
 * <p>
 * Aplicatia afiseaza un text tinta (grupe aleatorii, indicative, QSO-uri reale din assets/qso_fraze.txt,
 * parcurse pas cu pas); fiecare caracter
 * decodat din palete e verificat imediat: corect -> verde si se trece la urmatorul, gresit ->
 * rosu si se ramane pe acelasi caracter pana e dat corect. Se verifica si spatiul dintre cuvinte
 * (pauza de cel putin ~5 puncte): cuvinte lipite sau pauze lungi in mijlocul cuvantului
 * conteaza ca greseli de spatiere. Modul LIBER doar afiseaza ce se manipuleaza.
 * <p>
 * Foloseste doar sunetul de antrenament (CwTrainingKeyer) - nu atinge statia (RTS/DTR/CAT).
 * WPM/TON/REV/IAMBIC sunt aceleasi preferinte ca modul antrenament din COMMANDS.
 */
public class EcranCwTrainer {

    private static final String CULOARE_FUNDAL = "#1A1A1A";
    private static final String CULOARE_BUTON = "#424242";
    private static final String CULOARE_ACTIV = "#1565C0";
    private static final int VERDE = Color.parseColor("#00E676");
    private static final int PORTOCALIU = Color.parseColor("#FFB300");
    private static final int ROSU = Color.parseColor("#FF5252");
    private static final int GRI = Color.parseColor("#9E9E9E");
    private static final int GALBEN_FUNDAL = Color.parseColor("#5D4037");

    // Aceleasi chei ca in EcranComenzi (preferinte EcranSetariProprii.PREFS_NAME).
    private static final String PREF_CW_WPM = "cw_keyer_wpm";
    private static final String PREF_CW_REV = "cw_keyer_rev";
    private static final String PREF_CW_TON = "cw_training_ton_hz";
    private static final String PREF_CW_IAMBIC_B = "cw_keyer_iambic_b";
    private static final String PREF_TRAINER_MOD = "cw_trainer_mod";
    private static final String PREF_TRAINER_SET = "cw_trainer_set";
    private static final String PREF_TRAINER_LUNGIME = "cw_trainer_lungime";

    private static final int MOD_GRUPE = 0, MOD_INDICATIVE = 1, MOD_QSO = 2, MOD_LIBER = 3;
    private static final String[] NUME_MODURI = {"GRUPE", "INDICATIVE", "QSO", "LIBER"};

    private static final int SET_LITERE = 1, SET_CIFRE = 2, SET_SEMNE = 4;
    private static final String LITERE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String CIFRE = "0123456789";
    private static final String SEMNE = ".,?/=";

    // Starea fiecarui caracter din tinta.
    private static final int ST_ASTEAPTA = 0, ST_OK = 1, ST_OK_DUPA_GRESEALA = 2, ST_SPATIU_GRESIT = 3;

    private static final String[] NUME = {"ION", "JOHN", "PETER", "MIKE", "ANNA", "HANS", "PAUL", "MARIA", "TOM", "DAN"};
    private static final String[] QTH = {"BUCHAREST", "BERLIN", "PARIS", "ROMA", "LONDON", "VIENNA", "MADRID", "PRAGUE", "CLUJ", "IASI"};
    private static final String[] RST = {"599", "579", "559", "449", "5NN"};
    private static final String[] SABLOANE_QSO = {
            "CQ CQ CQ DE {MY} {MY} K",
            "QRZ DE {MY} K",
            "{CALL} DE {MY} GM ES TNX FER CALL",
            "UR RST {RST} {RST} NAME {NAME} {NAME}",
            "QTH {QTH} {QTH} HW CPY",
            "{CALL} DE {MY} R TNX FER RPRT",
            "RIG FTX1 PWR 10W ANT DIPOLE",
            "WX SUNNY TEMP 20C",
            "TNX FER QSO 73 ES GL",
            "{CALL} 5NN 14",
            "TU {MY} TEST",
            "{CALL} DE {MY} 73 SK",
    };

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final CwTrainingKeyer keyer = new CwTrainingKeyer();

    private LinearLayout viewPrincipal;
    private LinearLayout rOptiuniGrupe;
    private final Button[] butoaneMod = new Button[4];
    private Button btnLitere, btnCifre, btnSemne, btnLungime, btnTon, btnIambic;
    private CheckBox cbRev;
    private SeekBar sbWpm;
    private TextView tvWpm, tvEtichetaTinta, tvTinta, tvEtichetaDat, tvDat, tvMesaj, tvScor;
    private ScrollView scrollDat;
    private Button btnPaletaStanga, btnPaletaDreapta;

    private int mod = MOD_GRUPE;
    private int seturi = SET_LITERE;
    private int lungimeGrupa = 5;
    private boolean seIncarca = false;

    // Exercitiul curent
    private String tinta = "";
    private int[] stare = new int[0];
    private int pozitie = 0;
    private boolean greseala = false;
    private final SpannableStringBuilder textDat = new SpannableStringBuilder();
    private Runnable exercitiuUrmator;

    // Scorul sesiunii
    private int caractereTerminate = 0;
    private int corecteDinPrima = 0;
    private int greseliSpatiere = 0;
    private final Map<Character, Integer> greseliPeCaracter = new HashMap<>();

    private List<String> indicative;

    // Modul QSO: QSO-uri reale din assets/qso_fraze.txt, manipulate pas cu pas (antet, bucati din mesaj, final).
    private static final String FISIER_QSO = "qso_fraze.txt";
    private static final int PAS_MIN = 15, PAS_MAX = 40;
    private List<String[]> qsoLista;
    private int qsoIndex = -1;
    private final List<String> qsoPasi = new ArrayList<>();
    private int qsoPas = 0;

    public EcranCwTrainer(Context context) {
        this.context = context;
        keyer.setListener(new MorseKeyerListener() {
            @Override public void onCharacterDecoded(String c) { handler.post(() -> laCaracterDecodat(c)); }
            @Override public void onDahPlayed() {}
            @Override public void onDitPlayed() {}
        });
        construiesteInterfata();
        incarcaSetari();
        exercitiuNou();
    }

    public View getView() {
        return viewPrincipal;
    }

    public void eliberareResurse() {
        handler.removeCallbacksAndMessages(null);
        keyer.stop();
    }

    // ------------------------------------------------------------------ interfata

    @SuppressLint("ClickableViewAccessibility")
    private void construiesteInterfata() {
        viewPrincipal = new LinearLayout(context);
        viewPrincipal.setOrientation(LinearLayout.VERTICAL);
        viewPrincipal.setBackgroundColor(Color.parseColor(CULOARE_FUNDAL));
        viewPrincipal.setPadding(dp(10), dp(10), dp(10), dp(10));
        viewPrincipal.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        // Sunetul ruleaza doar cat timp pagina e pe ecran.
        viewPrincipal.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {
                incarcaSetari();
                keyer.start();
            }
            @Override public void onViewDetachedFromWindow(View v) {
                keyer.stop();
                btnPaletaStanga.setBackgroundColor(Color.parseColor("#37474F"));
                btnPaletaDreapta.setBackgroundColor(Color.parseColor("#37474F"));
            }
        });

        TextView tvTitlu = new TextView(context);
        tvTitlu.setText("CW TRAINER - EMISIE");
        tvTitlu.setTextColor(Color.parseColor("#FFC107"));
        tvTitlu.setTextSize(16);
        tvTitlu.setTypeface(null, Typeface.BOLD);
        tvTitlu.setGravity(Gravity.CENTER);
        tvTitlu.setPadding(0, 0, 0, dp(6));
        viewPrincipal.addView(tvTitlu);

        // Randul cu tipul exercitiului
        LinearLayout rModuri = randOrizontal();
        for (int i = 0; i < NUME_MODURI.length; i++) {
            final int m = i;
            butoaneMod[i] = buton(NUME_MODURI[i], v -> {
                mod = m;
                salveazaInt(PREF_TRAINER_MOD, m);
                actualizeazaButoaneMod();
                exercitiuNou();
            });
            adaugaCuSpatiu(rModuri, butoaneMod[i], i > 0);
        }
        viewPrincipal.addView(rModuri);

        // Optiuni GRUPE: seturi de caractere + lungime
        rOptiuniGrupe = randOrizontal();
        rOptiuniGrupe.setPadding(0, dp(4), 0, 0);
        btnLitere = buton("A-Z", v -> comutaSet(SET_LITERE));
        btnCifre = buton("0-9", v -> comutaSet(SET_CIFRE));
        btnSemne = buton(".,?/=", v -> comutaSet(SET_SEMNE));
        btnLungime = buton("LUNG: 5", v -> {
            lungimeGrupa = lungimeGrupa >= 6 ? 2 : lungimeGrupa + 1;
            salveazaInt(PREF_TRAINER_LUNGIME, lungimeGrupa);
            actualizeazaButoaneGrupe();
            exercitiuNou();
        });
        adaugaCuSpatiu(rOptiuniGrupe, btnLitere, false);
        adaugaCuSpatiu(rOptiuniGrupe, btnCifre, true);
        adaugaCuSpatiu(rOptiuniGrupe, btnSemne, true);
        adaugaCuSpatiu(rOptiuniGrupe, btnLungime, true);
        viewPrincipal.addView(rOptiuniGrupe);

        // Zona de exercitiu (ocupa spatiul ramas)
        LinearLayout zona = new LinearLayout(context);
        zona.setOrientation(LinearLayout.VERTICAL);
        zona.setPadding(dp(6), dp(8), dp(6), dp(4));
        zona.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        tvEtichetaTinta = eticheta("ȚINTĂ");
        zona.addView(tvEtichetaTinta);
        tvTinta = new TextView(context);
        tvTinta.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvTinta.setTextSize(26);
        tvTinta.setTextColor(Color.WHITE);
        tvTinta.setPadding(0, dp(2), 0, dp(8));
        zona.addView(tvTinta);

        tvEtichetaDat = eticheta("AI DAT");
        zona.addView(tvEtichetaDat);
        scrollDat = new ScrollView(context);
        scrollDat.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        tvDat = new TextView(context);
        tvDat.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvDat.setTextSize(22);
        tvDat.setTextColor(Color.WHITE);
        scrollDat.addView(tvDat);
        zona.addView(scrollDat);

        tvMesaj = new TextView(context);
        tvMesaj.setTextSize(14);
        tvMesaj.setTypeface(null, Typeface.BOLD);
        tvMesaj.setPadding(0, dp(4), 0, dp(2));
        zona.addView(tvMesaj);

        tvScor = new TextView(context);
        tvScor.setTextSize(13);
        tvScor.setTextColor(Color.parseColor("#B0C4DE"));
        zona.addView(tvScor);
        viewPrincipal.addView(zona);

        // Viteza
        LinearLayout rWpm = randOrizontal();
        rWpm.setGravity(Gravity.CENTER_VERTICAL);
        tvWpm = new TextView(context);
        tvWpm.setText("20 WPM");
        tvWpm.setTextColor(Color.WHITE);
        tvWpm.setTextSize(14);
        tvWpm.setTypeface(null, Typeface.BOLD);
        tvWpm.setSingleLine(true);
        tvWpm.setPadding(0, 0, dp(8), 0);
        sbWpm = new SeekBar(context);
        sbWpm.setMax(25); // 10..35 WPM, ca in COMMANDS
        sbWpm.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        sbWpm.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                int wpm = progress + 10;
                tvWpm.setText(wpm + " WPM");
                keyer.setWpm(wpm);
                if (fromUser) salveazaInt(PREF_CW_WPM, wpm);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        rWpm.addView(tvWpm);
        rWpm.addView(sbWpm);
        viewPrincipal.addView(rWpm);

        // REV, TON, IAMBIC
        LinearLayout rSetari = randOrizontal();
        rSetari.setGravity(Gravity.CENTER_VERTICAL);
        cbRev = new CheckBox(context);
        cbRev.setText("REV");
        cbRev.setTextColor(Color.YELLOW);
        cbRev.setOnCheckedChangeListener((v, checked) -> {
            keyer.setReversed(checked);
            actualizeazaEtichetePalete();
            if (!seIncarca) salveazaBool(PREF_CW_REV, checked);
        });
        btnTon = buton("TON: 600Hz", v -> {
            int ton = keyer.getToneHz() >= 700 ? 400 : keyer.getToneHz() + 100;
            keyer.setToneHz(ton);
            btnTon.setText("TON: " + ton + "Hz");
            salveazaInt(PREF_CW_TON, ton);
        });
        btnIambic = buton("IAMBIC B", v -> {
            boolean b = !keyer.getIambicModeB();
            keyer.setIambicModeB(b);
            btnIambic.setText(b ? "IAMBIC B" : "IAMBIC A");
            salveazaBool(PREF_CW_IAMBIC_B, b);
        });
        rSetari.addView(cbRev);
        adaugaCuSpatiu(rSetari, btnTon, true);
        adaugaCuSpatiu(rSetari, btnIambic, true);
        viewPrincipal.addView(rSetari);

        // NOU, REPETA, RESET SCOR
        LinearLayout rActiuni = randOrizontal();
        rActiuni.setPadding(0, dp(4), 0, dp(6));
        adaugaCuSpatiu(rActiuni, buton("NOU", v -> exercitiuNou()), false);
        adaugaCuSpatiu(rActiuni, buton("REPETĂ", v -> pornesteExercitiu(tinta)), true);
        adaugaCuSpatiu(rActiuni, buton("RESET SCOR", v -> {
            caractereTerminate = 0;
            corecteDinPrima = 0;
            greseliSpatiere = 0;
            greseliPeCaracter.clear();
            actualizeazaScor();
        }), true);
        viewPrincipal.addView(rActiuni);

        // Paletele
        LinearLayout rPalete = randOrizontal();
        btnPaletaStanga = paleta(true);
        btnPaletaDreapta = paleta(false);
        rPalete.addView(btnPaletaStanga);
        View spatiu = new View(context);
        spatiu.setLayoutParams(new LinearLayout.LayoutParams(dp(16), 1));
        rPalete.addView(spatiu);
        rPalete.addView(btnPaletaDreapta);
        viewPrincipal.addView(rPalete);
    }

    @SuppressLint("ClickableViewAccessibility")
    private Button paleta(boolean stanga) {
        Button b = new Button(context);
        b.setBackgroundColor(Color.parseColor("#37474F"));
        b.setTextColor(Color.WHITE);
        b.setTextSize(18);
        b.setTypeface(null, Typeface.BOLD);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, dp(110), 1f));
        b.setOnTouchListener((v, event) -> {
            int a = event.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                b.setBackgroundColor(Color.parseColor("#00E676"));
                if (stanga) keyer.setLeftPaddle(true); else keyer.setRightPaddle(true);
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                b.setBackgroundColor(Color.parseColor("#37474F"));
                if (stanga) keyer.setLeftPaddle(false); else keyer.setRightPaddle(false);
            }
            return true;
        });
        return b;
    }

    private Button buton(String text, View.OnClickListener cl) {
        Button b = new Button(context);
        b.setText(text);
        b.setAllCaps(false);
        b.setBackgroundColor(Color.parseColor(CULOARE_BUTON));
        b.setTextColor(Color.WHITE);
        b.setTextSize(11);
        b.setTypeface(null, Typeface.BOLD);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, dp(40), 1f));
        b.setOnClickListener(cl);
        return b;
    }

    private TextView eticheta(String text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(GRI);
        tv.setTextSize(11);
        tv.setTypeface(null, Typeface.BOLD);
        return tv;
    }

    private LinearLayout randOrizontal() {
        LinearLayout r = new LinearLayout(context);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private void adaugaCuSpatiu(LinearLayout rand, View v, boolean cuSpatiu) {
        if (cuSpatiu) {
            View s = new View(context);
            s.setLayoutParams(new LinearLayout.LayoutParams(dp(6), 1));
            rand.addView(s);
        }
        rand.addView(v);
    }

    private void actualizeazaButoaneMod() {
        for (int i = 0; i < butoaneMod.length; i++) {
            butoaneMod[i].setBackgroundColor(Color.parseColor(i == mod ? CULOARE_ACTIV : CULOARE_BUTON));
        }
        rOptiuniGrupe.setVisibility(mod == MOD_GRUPE ? View.VISIBLE : View.GONE);
        boolean liber = mod == MOD_LIBER;
        tvEtichetaTinta.setVisibility(liber ? View.GONE : View.VISIBLE);
        tvTinta.setVisibility(liber ? View.GONE : View.VISIBLE);
        tvEtichetaDat.setText(liber ? "MANIPULARE LIBERĂ" : "AI DAT");
    }

    private void actualizeazaButoaneGrupe() {
        btnLitere.setBackgroundColor(Color.parseColor((seturi & SET_LITERE) != 0 ? CULOARE_ACTIV : CULOARE_BUTON));
        btnCifre.setBackgroundColor(Color.parseColor((seturi & SET_CIFRE) != 0 ? CULOARE_ACTIV : CULOARE_BUTON));
        btnSemne.setBackgroundColor(Color.parseColor((seturi & SET_SEMNE) != 0 ? CULOARE_ACTIV : CULOARE_BUTON));
        btnLungime.setText("LUNG: " + lungimeGrupa);
    }

    private void actualizeazaEtichetePalete() {
        boolean rev = keyer.getReversed();
        btnPaletaStanga.setText(rev ? "DAH" : "DIT");
        btnPaletaDreapta.setText(rev ? "DIT" : "DAH");
    }

    private void comutaSet(int set) {
        int nou = seturi ^ set;
        if (nou == 0) return; // macar un set activ
        seturi = nou;
        salveazaInt(PREF_TRAINER_SET, seturi);
        actualizeazaButoaneGrupe();
        exercitiuNou();
    }

    // ------------------------------------------------------------------ exercitii

    private void exercitiuNou() {
        switch (mod) {
            case MOD_GRUPE: pornesteExercitiu(genereazaGrupe()); break;
            case MOD_INDICATIVE: pornesteExercitiu(genereazaIndicative(3)); break;
            case MOD_QSO: qsoNou(); break;
            default: pornesteExercitiu(""); break;
        }
    }

    /** Dupa un exercitiu terminat: in modul QSO continua QSO-ul curent, altfel text nou. */
    private void continuaExercitiu() {
        if (mod == MOD_QSO && qsoIndex >= 0 && qsoPas + 1 < qsoPasi.size()) {
            qsoPas++;
            pornesteExercitiu(qsoPasi.get(qsoPas));
        } else {
            exercitiuNou();
        }
    }

    private void qsoNou() {
        List<String[]> lista = incarcaQso();
        if (lista.isEmpty()) {
            // Fisierul lipseste/e gol: fraze generate din sabloane.
            qsoIndex = -1;
            qsoPasi.clear();
            pornesteExercitiu(genereazaFrazaQso());
            return;
        }
        int nou = random.nextInt(lista.size());
        if (lista.size() > 1 && nou == qsoIndex) nou = (nou + 1) % lista.size();
        qsoIndex = nou;
        qsoPasi.clear();
        qsoPasi.addAll(imparteQso(lista.get(nou)));
        qsoPas = 0;
        pornesteExercitiu(qsoPasi.get(0));
    }

    /** Citeste QSO-urile din assets (o singura data): blocuri de 3 linii antet / mesaj / final. */
    private List<String[]> incarcaQso() {
        if (qsoLista != null) return qsoLista;
        qsoLista = new ArrayList<>();
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(
                context.getAssets().open(FISIER_QSO), java.nio.charset.StandardCharsets.UTF_8))) {
            List<String> bloc = new ArrayList<>();
            String linie;
            while (true) {
                linie = br.readLine();
                if (linie != null && linie.trim().startsWith("#")) continue;
                if (linie == null || linie.trim().isEmpty()) {
                    if (bloc.size() == 3) qsoLista.add(bloc.toArray(new String[0]));
                    bloc.clear();
                    if (linie == null) break;
                    continue;
                }
                bloc.add(linie.trim().toUpperCase().replaceAll("\\s+", " "));
            }
        } catch (java.io.IOException ignored) {
        }
        return qsoLista;
    }

    /**
     * Imparte un QSO in pasi manipulabili: antetul, mesajul taiat la "=" (bucatile foarte scurte se
     * lipesc de urmatoarea, cele lungi se taie intre cuvinte) si finalul.
     */
    private static List<String> imparteQso(String[] qso) {
        List<String> pasi = new ArrayList<>();
        pasi.add(qso[0]);
        String acum = "";
        for (String bucata : qso[1].split("(?<==) ")) {
            bucata = bucata.trim();
            if (bucata.isEmpty()) continue;
            acum = acum.isEmpty() ? bucata : acum + " " + bucata;
            if (acum.length() >= PAS_MIN) {
                taieLaCuvinte(acum, pasi);
                acum = "";
            }
        }
        if (!acum.isEmpty()) {
            if (pasi.size() > 1) pasi.set(pasi.size() - 1, pasi.get(pasi.size() - 1) + " " + acum);
            else pasi.add(acum);
        }
        pasi.add(qso[2]);
        return pasi;
    }

    private static void taieLaCuvinte(String text, List<String> pasi) {
        if (text.length() <= PAS_MAX + 5) {
            pasi.add(text);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String cuvant : text.split(" ")) {
            if (sb.length() > 0 && sb.length() + 1 + cuvant.length() > PAS_MAX) {
                pasi.add(sb.toString());
                sb.setLength(0);
            }
            if (sb.length() > 0) sb.append(' ');
            sb.append(cuvant);
        }
        if (sb.length() > 0) pasi.add(sb.toString());
    }

    private void pornesteExercitiu(String text) {
        if (exercitiuUrmator != null) handler.removeCallbacks(exercitiuUrmator);
        tinta = mod == MOD_LIBER ? "" : text;
        stare = new int[tinta.length()];
        pozitie = 0;
        greseala = false;
        textDat.clear();
        tvDat.setText("");
        afiseazaMesaj(mod == MOD_LIBER ? "Manipulează ce vrei - textul decodat apare mai sus." : "", GRI);
        tvEtichetaTinta.setText(mod == MOD_QSO && qsoIndex >= 0
                ? "ȚINTĂ · QSO " + (qsoIndex + 1) + "/" + qsoLista.size() + " · pas " + (qsoPas + 1) + "/" + qsoPasi.size()
                : "ȚINTĂ");
        afiseazaTinta();
        actualizeazaScor();
    }

    private String genereazaGrupe() {
        StringBuilder alfabet = new StringBuilder();
        if ((seturi & SET_LITERE) != 0) alfabet.append(LITERE);
        if ((seturi & SET_CIFRE) != 0) alfabet.append(CIFRE);
        if ((seturi & SET_SEMNE) != 0) alfabet.append(SEMNE);
        StringBuilder sb = new StringBuilder();
        int numarGrupe = Math.max(2, 20 / lungimeGrupa);
        for (int g = 0; g < numarGrupe; g++) {
            if (g > 0) sb.append(' ');
            for (int i = 0; i < lungimeGrupa; i++) {
                sb.append(alfabet.charAt(random.nextInt(alfabet.length())));
            }
        }
        return sb.toString();
    }

    private String genereazaIndicative(int numar) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < numar; i++) {
            if (i > 0) sb.append(' ');
            sb.append(indicativAleator());
        }
        return sb.toString();
    }

    /** Indicativ real din MASTER.SCP; daca baza nu e (inca) incarcata, unul generat. */
    private String indicativAleator() {
        if (indicative == null || indicative.isEmpty()) {
            List<String> toate = CallsignDatabase.getInstance().copieIndicative();
            List<String> filtrate = new ArrayList<>();
            for (String c : toate) {
                if (c.length() >= 3 && c.length() <= 8 && c.matches("[A-Z0-9/]+")) filtrate.add(c);
            }
            indicative = filtrate;
        }
        if (!indicative.isEmpty()) return indicative.get(random.nextInt(indicative.size()));
        String[] prefixe = {"YO", "DL", "F", "G", "I", "K", "W", "OK", "SP", "EA", "HA", "LZ", "UR", "JA", "VK"};
        StringBuilder sb = new StringBuilder(prefixe[random.nextInt(prefixe.length)]);
        sb.append(random.nextInt(10));
        int sufix = 1 + random.nextInt(3);
        for (int i = 0; i < sufix; i++) sb.append(LITERE.charAt(random.nextInt(LITERE.length())));
        return sb.toString();
    }

    private String genereazaFrazaQso() {
        String my = context.getSharedPreferences(EcranSetariProprii.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(EcranSetariProprii.PREF_MY_CALL, "");
        my = my == null ? "" : my.trim().toUpperCase();
        if (my.isEmpty()) my = indicativAleator();
        return SABLOANE_QSO[random.nextInt(SABLOANE_QSO.length)]
                .replace("{MY}", my)
                .replace("{CALL}", indicativAleator())
                .replace("{RST}", RST[random.nextInt(RST.length)])
                .replace("{NAME}", NUME[random.nextInt(NUME.length)])
                .replace("{QTH}", QTH[random.nextInt(QTH.length)]);
    }

    // ------------------------------------------------------------------ evaluare live

    private void laCaracterDecodat(String decodat) {
        if (decodat == null || decodat.isEmpty()) return;
        char c = Character.toUpperCase(decodat.charAt(0));

        if (mod == MOD_LIBER) {
            if (c == ' ' && (textDat.length() == 0 || textDat.charAt(textDat.length() - 1) == ' ')) return;
            adaugaDat(c, c == '*' ? ROSU : VERDE);
            return;
        }
        if (pozitie >= tinta.length()) return; // exercitiu terminat, asteptam urmatorul

        char asteptat = tinta.charAt(pozitie);

        if (c == ' ') {
            if (asteptat == ' ') {
                stare[pozitie] = ST_OK;
                pozitie++;
                adaugaDat(' ', VERDE);
            } else if (pozitie > 0 && tinta.charAt(pozitie - 1) != ' ' && !greseala) {
                // Pauza prea lunga in mijlocul cuvantului.
                greseliSpatiere++;
                adaugaDatSpatiuGresit();
                afiseazaMesaj("⚠ Pauză prea lungă în mijlocul cuvântului", PORTOCALIU);
            }
            afiseazaTinta();
            actualizeazaScor();
            return;
        }

        if (asteptat == ' ') {
            // Urmatorul cuvant a inceput fara pauza de cuvant: cuvinte lipite.
            stare[pozitie] = ST_SPATIU_GRESIT;
            pozitie++;
            greseliSpatiere++;
            adaugaDatSpatiuGresit();
            afiseazaMesaj("⚠ Cuvinte lipite - fă o pauză mai lungă între cuvinte", PORTOCALIU);
            asteptat = tinta.charAt(pozitie);
        }

        if (c == asteptat) {
            stare[pozitie] = greseala ? ST_OK_DUPA_GRESEALA : ST_OK;
            if (!greseala) corecteDinPrima++;
            caractereTerminate++;
            greseala = false;
            pozitie++;
            adaugaDat(c, VERDE);
            if (tvMesaj.getCurrentTextColor() == ROSU) afiseazaMesaj("", GRI);
        } else {
            if (!greseala) {
                Integer n = greseliPeCaracter.get(asteptat);
                greseliPeCaracter.put(asteptat, n == null ? 1 : n + 1);
            }
            greseala = true;
            adaugaDat(c, ROSU);
            afiseazaMesaj(c == '*'
                    ? "✗ Combinație necunoscută - așteptam " + asteptat + " (" + morse(asteptat) + ")"
                    : "✗ Ai dat " + c + " (" + morse(c) + ") în loc de " + asteptat + " (" + morse(asteptat) + ")", ROSU);
        }

        afiseazaTinta();
        actualizeazaScor();
        if (pozitie >= tinta.length()) exercitiuTerminat();
    }

    private void exercitiuTerminat() {
        int total = 0, ok = 0;
        for (int i = 0; i < tinta.length(); i++) {
            if (tinta.charAt(i) == ' ') continue;
            total++;
            if (stare[i] == ST_OK) ok++;
        }
        String urmeaza;
        if (mod == MOD_QSO && qsoIndex >= 0) {
            urmeaza = qsoPas + 1 < qsoPasi.size() ? "Urmează continuarea QSO-ului..." : "QSO terminat! Urmează alt QSO...";
        } else {
            urmeaza = "Urmează un text nou...";
        }
        afiseazaMesaj("✓ Terminat: " + ok + "/" + total + " corecte din prima. " + urmeaza, VERDE);
        exercitiuUrmator = this::continuaExercitiu;
        handler.postDelayed(exercitiuUrmator, 2500);
    }

    // ------------------------------------------------------------------ afisare

    private void afiseazaTinta() {
        SpannableStringBuilder sb = new SpannableStringBuilder(tinta);
        for (int i = 0; i < tinta.length(); i++) {
            int culoare;
            if (i == pozitie) {
                culoare = greseala ? ROSU : Color.YELLOW;
                sb.setSpan(new BackgroundColorSpan(GALBEN_FUNDAL), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new UnderlineSpan(), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (i > pozitie) {
                culoare = Color.WHITE;
            } else if (stare[i] == ST_OK) {
                culoare = VERDE;
            } else if (stare[i] == ST_SPATIU_GRESIT) {
                culoare = PORTOCALIU;
                sb.setSpan(new BackgroundColorSpan(PORTOCALIU), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                culoare = PORTOCALIU;
            }
            sb.setSpan(new ForegroundColorSpan(culoare), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        tvTinta.setText(sb);
    }

    private void adaugaDat(char c, int culoare) {
        int start = textDat.length();
        textDat.append(c);
        textDat.setSpan(new ForegroundColorSpan(culoare), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        afiseazaDat();
    }

    private void adaugaDatSpatiuGresit() {
        int start = textDat.length();
        textDat.append('_');
        textDat.setSpan(new ForegroundColorSpan(PORTOCALIU), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        afiseazaDat();
    }

    private void afiseazaDat() {
        tvDat.setText(textDat);
        scrollDat.post(() -> scrollDat.fullScroll(View.FOCUS_DOWN));
    }

    private void afiseazaMesaj(String text, int culoare) {
        tvMesaj.setText(text);
        tvMesaj.setTextColor(culoare);
    }

    private void actualizeazaScor() {
        if (mod == MOD_LIBER) {
            tvScor.setText("");
            return;
        }
        int procent = caractereTerminate == 0 ? 0 : Math.round(100f * corecteDinPrima / caractereTerminate);
        StringBuilder sb = new StringBuilder();
        sb.append("Corecte din prima: ").append(corecteDinPrima).append('/').append(caractereTerminate)
                .append(" · ").append(procent).append('%')
                .append("   Spațiere: ").append(greseliSpatiere);
        if (!greseliPeCaracter.isEmpty()) {
            List<Map.Entry<Character, Integer>> lista = new ArrayList<>(greseliPeCaracter.entrySet());
            lista.sort((a, b) -> b.getValue() - a.getValue());
            sb.append("\nGreșite des: ");
            for (int i = 0; i < Math.min(6, lista.size()); i++) {
                if (i > 0) sb.append("  ");
                sb.append(lista.get(i).getKey()).append('×').append(lista.get(i).getValue());
            }
        }
        tvScor.setText(sb);
    }

    private static String morse(char c) {
        switch (c) {
            case 'A': return ".-";    case 'B': return "-...";  case 'C': return "-.-.";
            case 'D': return "-..";   case 'E': return ".";     case 'F': return "..-.";
            case 'G': return "--.";   case 'H': return "....";  case 'I': return "..";
            case 'J': return ".---";  case 'K': return "-.-";   case 'L': return ".-..";
            case 'M': return "--";    case 'N': return "-.";    case 'O': return "---";
            case 'P': return ".--.";  case 'Q': return "--.-";  case 'R': return ".-.";
            case 'S': return "...";   case 'T': return "-";     case 'U': return "..-";
            case 'V': return "...-";  case 'W': return ".--";   case 'X': return "-..-";
            case 'Y': return "-.--";  case 'Z': return "--..";
            case '0': return "-----"; case '1': return ".----"; case '2': return "..---";
            case '3': return "...--"; case '4': return "....-"; case '5': return ".....";
            case '6': return "-...."; case '7': return "--..."; case '8': return "---..";
            case '9': return "----.";
            case '.': return ".-.-.-"; case ',': return "--..--"; case '?': return "..--..";
            case '/': return "-..-.";  case '=': return "-...-";
            default: return "?";
        }
    }

    // ------------------------------------------------------------------ preferinte

    private SharedPreferences prefs() {
        return context.getSharedPreferences(EcranSetariProprii.PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void incarcaSetari() {
        seIncarca = true;
        SharedPreferences p = prefs();
        int wpm = p.getInt(PREF_CW_WPM, 20);
        boolean rev = p.getBoolean(PREF_CW_REV, false);
        int ton = p.getInt(PREF_CW_TON, 600);
        boolean iambicB = p.getBoolean(PREF_CW_IAMBIC_B, true);

        keyer.setWpm(wpm);
        keyer.setReversed(rev);
        keyer.setToneHz(ton);
        keyer.setIambicModeB(iambicB);
        sbWpm.setProgress(Math.max(0, Math.min(25, wpm - 10)));
        tvWpm.setText(wpm + " WPM");
        cbRev.setChecked(rev);
        btnTon.setText("TON: " + ton + "Hz");
        btnIambic.setText(iambicB ? "IAMBIC B" : "IAMBIC A");
        actualizeazaEtichetePalete();

        mod = p.getInt(PREF_TRAINER_MOD, MOD_GRUPE);
        seturi = p.getInt(PREF_TRAINER_SET, SET_LITERE);
        if (seturi == 0) seturi = SET_LITERE;
        lungimeGrupa = p.getInt(PREF_TRAINER_LUNGIME, 5);
        actualizeazaButoaneMod();
        actualizeazaButoaneGrupe();
        seIncarca = false;
    }

    private void salveazaInt(String cheie, int valoare) {
        prefs().edit().putInt(cheie, valoare).apply();
    }

    private void salveazaBool(String cheie, boolean valoare) {
        prefs().edit().putBoolean(cheie, valoare).apply();
    }

    private int dp(int valoare) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, valoare,
                context.getResources().getDisplayMetrics());
    }
}
