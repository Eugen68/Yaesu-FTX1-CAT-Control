package com.example.yaesucatcontroladv;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class EcranSetari {

    private final Context context;
    private final RadioManager radioManager;
    private ScrollView viewPrincipal;

    // Elemente UI cu culori corectate pentru vizibilitate maximă
    private TextView tvModelLabel, tvPowerLabel, tvSsLabel, tvDnrLabel, tvCwSpeedValue;
    private SeekBar skPower, skSpectrum, skDnr, sbCwSpeed;
    private Button btnDnrToggle, btnKeepScreenOn;

    // --- ELEMENTE MONITORIZARE CAT LIVE ---
    private ScrollView svLogScroll;
    private LinearLayout llLogContainer; // Schimbat din TextView în LinearLayout
    private Button btnScrollLock;
    private boolean autoScrollLogActiv = true;
    // Stări interne
    private boolean stareDnrOn = false;
    private int nivelDnrCurent = 1;
    private char prefixPutereStatie = '1';
    private boolean isScreenWakeLocked = false;

    // ============================================================
// BUFFER CIRCULAR PENTRU LOG CAT (previne OOM dupa ore de functionare)
// ============================================================
// 💡 POTI MODIFICA ACEASTA VALOARE: Numarul maxim de linii pastrate in log.
//    200 linii ≈ 10KB memorie, suficient pentru debugging in timp real.
//    Daca doresti un istoric mai lung, creste la 500 sau 1000.
    private static final int MAX_LINII_LOG = 200;

    public EcranSetari(Context context, RadioManager radioManager) {
        this.context = context;
        this.radioManager = radioManager;
        construiesteInterfata();
    }

    public ScrollView getView() {
        return viewPrincipal;
    }

    private void construiesteInterfata() {
        viewPrincipal = new ScrollView(context);
        viewPrincipal.setBackgroundColor(Color.parseColor("#121212")); // Fundal închis profesional

        LinearLayout llGen = new LinearLayout(context);
        llGen.setOrientation(LinearLayout.VERTICAL);
        llGen.setPadding(35, 35, 35, 35);

        // --- 1. MODEL TRANSCEIVER (TEXT ALB) ---
        tvModelLabel = new TextView(context);
        tvModelLabel.setText("Yaesu FTX1 (Querying...)");
        tvModelLabel.setTextColor(Color.WHITE);
        tvModelLabel.setTextSize(19);
        tvModelLabel.setTypeface(null, Typeface.BOLD);
        tvModelLabel.setGravity(Gravity.CENTER);
        tvModelLabel.setPadding(0, 10, 0, 30);
        llGen.addView(tvModelLabel);

        // --- 2. REGLAJ PUTERE ---
        tvPowerLabel = new TextView(context);
        tvPowerLabel.setText("Power Adjustment: -- W");
        tvPowerLabel.setTextColor(Color.parseColor("#E0E0E0"));
        tvPowerLabel.setTextSize(15);
        tvPowerLabel.setPadding(10, 0, 0, 10);
        llGen.addView(tvPowerLabel);

        skPower = new SeekBar(context);
        skPower.setMax(100);
        skPower.setProgress(50);
        skPower.setPadding(30, 20, 30, 35);
        skPower.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int pEfectiv = progress < 5 ? 5 : progress;
                if (prefixPutereStatie == '1') {
                    double wati = pEfectiv / 10.0;
                    if (pEfectiv % 10 == 0) {
                        tvPowerLabel.setText(String.format(java.util.Locale.US, "Power Adjustment: %d W", (int)wati));
                    } else {
                        tvPowerLabel.setText(String.format(java.util.Locale.US, "Power Adjustment: %.1f W", wati));
                    }
                } else {
                    tvPowerLabel.setText("Power Adjustment: " + pEfectiv + " W");
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                int p = sb.getProgress() < 5 ? 5 : sb.getProgress();
                String valoareCAT;
                if (prefixPutereStatie == '1') {
                    // Format hibrid Field: 3 caractere fixe (ex: "006" sau "5.9")
                    double wati = p / 10.0;
                    if (p % 10 == 0) {
                        valoareCAT = String.format(java.util.Locale.US, "%03d", (int)wati);
                    } else {
                        valoareCAT = String.format(java.util.Locale.US, "%.1f", wati);
                    }
                } else {
                    valoareCAT = String.format(java.util.Locale.US, "%03d", p);
                }
                
                radioManager.trimiteComandaCAT("PC" + prefixPutereStatie + valoareCAT + ";");
            }
        });
        llGen.addView(skPower);

        // --- 3. SPECTRUM SCOPE ---
        tvSsLabel = new TextView(context);
        tvSsLabel.setText("D-Level Spectrum Scope: --.- dB");
        tvSsLabel.setTextColor(Color.parseColor("#E0E0E0"));
        tvSsLabel.setTextSize(15);
        tvSsLabel.setPadding(10, 20, 0, 10);
        llGen.addView(tvSsLabel);

        skSpectrum = new SeekBar(context);
        skSpectrum.setMax(120);
        skSpectrum.setProgress(60);
        skSpectrum.setPadding(30, 20, 30, 35);
        skSpectrum.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                double valoareDb = (progress - 60) * 0.5;
                tvSsLabel.setText(String.format(java.util.Locale.US, "D-Level Spectrum Scope: %+.1f dB", valoareDb));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                double valoareDb = (sb.getProgress() - 60) * 0.5;
                String valoareFormatata = String.format(java.util.Locale.US, "%+05.1f", valoareDb);
                radioManager.trimiteComandaCAT("SS04" + valoareFormatata + ";");
                raporteazaComandaInLog("TX -> SS04" + valoareFormatata + ";");
            }
        });
        llGen.addView(skSpectrum);

        // --- 4. CONTROL DIGITAL NOISE REDUCTION (DNR) REPARAT ---
        tvDnrLabel = new TextView(context);
        tvDnrLabel.setText("DNR: OFF");
        tvDnrLabel.setTextColor(Color.parseColor("#E0E0E0"));
        tvDnrLabel.setTextSize(15);
        tvDnrLabel.setPadding(10, 20, 0, 10);
        llGen.addView(tvDnrLabel);

        LinearLayout randDnr = new LinearLayout(context);
        randDnr.setOrientation(LinearLayout.HORIZONTAL);
        randDnr.setGravity(Gravity.CENTER_VERTICAL);

        btnDnrToggle = new Button(context);
        btnDnrToggle.setText("DNR: OFF");
        btnDnrToggle.setBackgroundColor(Color.parseColor("#424242"));
        btnDnrToggle.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams pBtnDnr = new LinearLayout.LayoutParams(0, 130, 1.2f);
        pBtnDnr.setMargins(0, 0, 20, 0);
        btnDnrToggle.setLayoutParams(pBtnDnr);
        btnDnrToggle.setOnClickListener(v -> {
            if (stareDnrOn) {
                radioManager.trimiteComandaCAT("RL000;");
                raporteazaComandaInLog("TX -> RL000;");
                stareDnrOn = false;
                actualizeazaGraficaDnr(false, nivelDnrCurent);
            } else {
                if (nivelDnrCurent < 1) nivelDnrCurent = 1;
                // Trimitere formatata RL0XX;
                radioManager.trimiteComandaCAT("RL0" + String.format(java.util.Locale.US, "%02d", nivelDnrCurent) + ";");
                raporteazaComandaInLog("TX -> RL0" + String.format(java.util.Locale.US, "%02d", nivelDnrCurent) + ";");
                stareDnrOn = true;
                actualizeazaGraficaDnr(true, nivelDnrCurent);
            }
        });
        randDnr.addView(btnDnrToggle);

        skDnr = new SeekBar(context);
        skDnr.setMax(10); // Interval Yaesu DNR 1-10 conform cerintei
        skDnr.setProgress(1);
        skDnr.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2.8f));
        skDnr.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int nivelEfectiv = progress < 1 ? 1 : progress;
                if (fromUser) {
                    nivelDnrCurent = nivelEfectiv;
                    stareDnrOn = true;
                }
                tvDnrLabel.setText("DNR: " + (stareDnrOn ? "ON" : "OFF") + " (Level " + nivelEfectiv + ")");
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                int nivelFinal = sb.getProgress() < 1 ? 1 : sb.getProgress();
                nivelDnrCurent = nivelFinal;
                // Trimitere formatata: RL001; ... RL010;
                radioManager.trimiteComandaCAT("RL0" + String.format(java.util.Locale.US, "%02d", nivelFinal) + ";");
                raporteazaComandaInLog("TX -> RL0" + String.format(java.util.Locale.US, "%02d", nivelFinal) + ";");
                actualizeazaGraficaDnr(true, nivelFinal);
            }
        });
        randDnr.addView(skDnr);
        llGen.addView(randDnr);

        // --- 5. VITEZĂ CW KEYER ---
        TextView tvCwSpeedTitlu = new TextView(context);
        tvCwSpeedTitlu.setText("CW Keyer Speed (WPM):");
        tvCwSpeedTitlu.setTextColor(Color.parseColor("#E0E0E0"));
        tvCwSpeedTitlu.setTextSize(14);
        tvCwSpeedTitlu.setPadding(10, 20, 0, 5);
        llGen.addView(tvCwSpeedTitlu);

        LinearLayout randCwSpeed = new LinearLayout(context);
        randCwSpeed.setOrientation(LinearLayout.HORIZONTAL);
        randCwSpeed.setGravity(Gravity.CENTER_VERTICAL);

        sbCwSpeed = new SeekBar(context);
        sbCwSpeed.setMax(15);
        sbCwSpeed.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f));

        tvCwSpeedValue = new TextView(context);
        tvCwSpeedValue.setText("15 WPM");
        tvCwSpeedValue.setTextColor(Color.parseColor("#64B5F6"));
        tvCwSpeedValue.setTextSize(15);
        tvCwSpeedValue.setTypeface(null, Typeface.BOLD);
        tvCwSpeedValue.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.2f));
        tvCwSpeedValue.setGravity(Gravity.CENTER);

        sbCwSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int vitezaReala = 15 + progress;
                tvCwSpeedValue.setText(vitezaReala + " WPM");
                if (fromUser) {
                    radioManager.trimiteComandaCAT(String.format(java.util.Locale.US, "KS%03d;", vitezaReala));
                    raporteazaComandaInLog("TX -> KS" + String.format(java.util.Locale.US, "%03d", vitezaReala) + ";");
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        randCwSpeed.addView(sbCwSpeed);
        randCwSpeed.addView(tvCwSpeedValue);
        llGen.addView(randCwSpeed);

        // --- 6. BUTON MENȚINERE ECRAN APRINS ---
        btnKeepScreenOn = new Button(context);
        btnKeepScreenOn.setText("KEEP SCREEN ON: OFF");
        btnKeepScreenOn.setBackgroundColor(Color.parseColor("#37474F"));
        btnKeepScreenOn.setTextColor(Color.WHITE);
        btnKeepScreenOn.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams pWake = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 130);
        pWake.setMargins(0, 45, 0, 30);
        btnKeepScreenOn.setLayoutParams(pWake);
        btnKeepScreenOn.setOnClickListener(v -> {
            Activity act = (Activity) context;
            isScreenWakeLocked = !isScreenWakeLocked;
            if (isScreenWakeLocked) {
                act.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                btnKeepScreenOn.setText("KEEP SCREEN ON: ACTIVE");
                btnKeepScreenOn.setBackgroundColor(Color.parseColor("#007ACC"));
                Toast.makeText(context, "The screen will remain permanently on!", Toast.LENGTH_SHORT).show();
            } else {
                act.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                btnKeepScreenOn.setText("KEEP SCREEN ON: OFF");
                btnKeepScreenOn.setBackgroundColor(Color.parseColor("#37474F"));
                Toast.makeText(context, "Mechanism deactivated.", Toast.LENGTH_SHORT).show();
            }
        });
        llGen.addView(btnKeepScreenOn);

        // --- 7. FEREASTRĂ LIVE LOG CAT PLUGGED IN ---
        TextView tvLogTitlu = new TextView(context);
        tvLogTitlu.setText("LIVE CAT MONITOR (RX / TX JURNAL):");
        tvLogTitlu.setTextColor(Color.parseColor("#9E9E9E"));
        tvLogTitlu.setTextSize(13);
        tvLogTitlu.setPadding(10, 20, 0, 10);
        llGen.addView(tvLogTitlu);

        svLogScroll = new ScrollView(context);
        LinearLayout.LayoutParams pSvLog = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 400);
        svLogScroll.setLayoutParams(pSvLog);
        svLogScroll.setBackgroundColor(Color.BLACK);
        svLogScroll.setPadding(15, 15, 15, 15);

        llLogContainer = new LinearLayout(context);
        llLogContainer.setOrientation(LinearLayout.VERTICAL);
        svLogScroll.addView(llLogContainer);

        // Mesaj inițial
        TextView tvInit = new TextView(context);
        tvInit.setText("--- Monitor started. Waiting for serial traffic... ---");
        tvInit.setTextColor(Color.parseColor("#00FF00"));
        tvInit.setTextSize(13);
        tvInit.setTypeface(Typeface.MONOSPACE);
        llLogContainer.addView(tvInit);
        
        llGen.addView(svLogScroll);

        // Buton Scroll Lock compact dedesubt
        btnScrollLock = new Button(context);
        btnScrollLock.setText("SCROLL LOCK: OFF");
        btnScrollLock.setBackgroundColor(Color.parseColor("#424242"));
        btnScrollLock.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams pLock = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 110);
        pLock.setMargins(0, 15, 0, 10);
        btnScrollLock.setLayoutParams(pLock);
        btnScrollLock.setOnClickListener(v -> {
            autoScrollLogActiv = !autoScrollLogActiv;
            btnScrollLock.setText(autoScrollLogActiv ? "SCROLL LOCK: OFF" : "SCROLL LOCK: ACTIVE");
            btnScrollLock.setBackgroundColor(Color.parseColor(autoScrollLogActiv ? "#424242" : "#B71C1C"));
        });
        llGen.addView(btnScrollLock);

        // --- 8. MANUAL CAT COMMAND ENTRY ---
        LinearLayout randManualCat = new LinearLayout(context);
        randManualCat.setOrientation(LinearLayout.HORIZONTAL);
        randManualCat.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams pRandManual = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pRandManual.setMargins(0, 10, 0, 10);
        randManualCat.setLayoutParams(pRandManual);

        EditText etManualCmd = new EditText(context);
        etManualCmd.setHint("Enter CAT command...");
        etManualCmd.setHintTextColor(Color.GRAY);
        etManualCmd.setTextColor(Color.WHITE);
        etManualCmd.setTextSize(15);
        etManualCmd.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        etManualCmd.setFilters(new InputFilter[] {new InputFilter.AllCaps()});
        LinearLayout.LayoutParams pEtManual = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.75f);
        etManualCmd.setLayoutParams(pEtManual);

        Button btnSendManual = new Button(context);
        btnSendManual.setText("SEND");
        btnSendManual.setBackgroundColor(Color.parseColor("#0D47A1"));
        btnSendManual.setTextColor(Color.WHITE);
        btnSendManual.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams pBtnManual = new LinearLayout.LayoutParams(0, 110, 0.25f);
        pBtnManual.setMargins(10, 0, 0, 0);
        btnSendManual.setLayoutParams(pBtnManual);

        btnSendManual.setOnClickListener(v -> {
            String cmd = etManualCmd.getText().toString().trim();
            if (!cmd.isEmpty()) {
                radioManager.trimiteComandaCAT(cmd);
                etManualCmd.setText("");

                // Hide keyboard
                InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.hideSoftInputFromWindow(etManualCmd.getWindowToken(), 0);
                }
            }
        });

        randManualCat.addView(etManualCmd);
        randManualCat.addView(btnSendManual);
        llGen.addView(randManualCat);

        viewPrincipal.addView(llGen);
    }

    private void actualizeazaGraficaDnr(boolean parOn, int parNivel) {
        btnDnrToggle.setText(parOn ? "DNR: ON" : "DNR: OFF");
        btnDnrToggle.setBackgroundColor(Color.parseColor(parOn ? "#2E7D32" : "#424242"));
        tvDnrLabel.setText("DNR: " + (parOn ? "ON" : "OFF") + " (Level " + parNivel + ")");
        skDnr.setProgress(parNivel);
    }

    // Funcție publică apelată din exterior pentru a tipări datele trimise sau primite live
    // --- CORECTAT: Jurnal cromatic (TX în Albastru, RX în Verde) ---
    // --- CORECTAT DEFINITIV: Culori fixe explicite (TX = Albastru Cyan, RX = Verde Fosforos) ---
    public void raporteazaComandaInLog(String textLog) {
        android.app.Activity act = (android.app.Activity) context;
        act.runOnUiThread(() -> {
            if (llLogContainer != null) {
                String textCurat = textLog.trim();
                if (textCurat.isEmpty()) return;

                boolean isTx = textCurat.contains("TX ->");

                // 1. Creăm un TextView nou pentru linie
                TextView tvLinie = new TextView(context);
                tvLinie.setText(textCurat);
                tvLinie.setTextSize(13);
                tvLinie.setTypeface(Typeface.MONOSPACE);
                tvLinie.setPadding(0, 2, 0, 2);
                
                // Colorare
                if (isTx) {
                    tvLinie.setTextColor(Color.parseColor("#00B0FF")); // Albastru Cyan
                } else {
                    tvLinie.setTextColor(Color.parseColor("#00FF00")); // Verde Fosforos
                }

                // 2. ADĂUGĂM LONG-PRESS PENTRU COPIERE
                tvLinie.setOnLongClickListener(v -> {
                    ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText("CAT Command", textCurat);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(clip);
                        Toast.makeText(context, "Command copied to clipboard!", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                });

                // 3. Adăugăm în container
                llLogContainer.addView(tvLinie);

                // 4. Curățăm liniile vechi dacă depășim limita
                if (llLogContainer.getChildCount() > MAX_LINII_LOG) {
                    llLogContainer.removeViewAt(0);
                }

                // 5. Scroll automat la final
                if (autoScrollLogActiv && svLogScroll != null) {
                    svLogScroll.post(() -> svLogScroll.fullScroll(View.FOCUS_DOWN));
                }
            }
        });
    }




    // --- PARSER CENTRAL ASINCRON ---
    public void proceseazaDateCATInSetari(String prefix, String comandaScurta) {
        if (prefix.equals("PC")) {
            String datePC = comandaScurta.substring(2);
            if (!datePC.isEmpty()) {
                prefixPutereStatie = datePC.charAt(0);
                // Actualizăm eticheta modelului conform noii cerințe
                if (prefixPutereStatie == '1') {
                    String pwr = (skPower.getMax() == 60) ? "6W" : "10W";
                    tvModelLabel.setText("Yaesu FTX1 Field - " + pwr);
                } else {
                    tvModelLabel.setText("Yaesu FTX1 Optima - 100W");
                }
                
                if (datePC.length() >= 4) {
                    try {
                        String valStr = datePC.substring(1, 4); // Luăm fix cele 3 caractere de după prefix
                        double valoareWati;
                        if (valStr.contains(".")) {
                            valoareWati = Double.parseDouble(valStr);
                        } else {
                            // Dacă nu are punct, e formatul "005" sau "085"
                            valoareWati = Integer.parseInt(valStr.trim());
                        }
                        
                        if (prefixPutereStatie == '1') {
                            skPower.setProgress((int)(valoareWati * 10));
                            if ((int)(valoareWati * 10) % 10 == 0) {
                                tvPowerLabel.setText(String.format(java.util.Locale.US, "Power Adjustment: %d W", (int)valoareWati));
                            } else {
                                tvPowerLabel.setText(String.format(java.util.Locale.US, "Power Adjustment: %.1f W", valoareWati));
                            }
                        } else {
                            skPower.setProgress((int)valoareWati);
                            tvPowerLabel.setText("Power Adjustment: " + (int)valoareWati + " W");
                        }
                    } catch (Exception e) {
                        // Ignorăm erorile de format
                    }
                }
            }
        } else if (prefix.equals("RM")) {
            // RM8nnn000; -> nnn este tensiunea (ex: 138 = 13.8V)
            if (comandaScurta.length() >= 6 && comandaScurta.charAt(2) == '8') {
                try {
                    int tensiuneRaw = Integer.parseInt(comandaScurta.substring(3, 6));
                    if (prefixPutereStatie == '1') {
                        // Calibrare: 10.8V = 169. Pragul de 11.5V corespunde la ~180 unități.
                        boolean esteBaterie = tensiuneRaw < 180; 
                        skPower.setMax(esteBaterie ? 60 : 100);
                        
                        String pwr = esteBaterie ? "6W" : "10W";
                        tvModelLabel.setText("Yaesu FTX1 Field - " + pwr);
                    }
                } catch (Exception ignored) {}
            }
        } else if (prefix.equals("SS") && comandaScurta.startsWith("SS04")) {
            try {
                double valoareDb = Double.parseDouble(comandaScurta.substring(4, 9));
                int pozitie = (int) ((valoareDb / 0.5) + 60);
                skSpectrum.setProgress(pozitie);
                tvSsLabel.setText(String.format(java.util.Locale.US, "D-Level Spectrum Scope: %+.1f dB", valoareDb));
            } catch (Exception e) {}
        } else if (prefix.equals("RL")) {
            try {
                // Statia raspunde cu RL0XX; (unde 0=Main, XX=00 pentru OFF sau 01-10 nivel)
                // Exemplu: RL005; pentru Main nivel 5
                if (comandaScurta.length() >= 5) {
                    // Verificăm dacă este pentru Main (cifra 0 după RL)
                    if (comandaScurta.charAt(2) == '0') {
                        String nivelStr = comandaScurta.substring(3, 5); // Extragem XX (pozitiile 3 si 4)
                        int nivelCitit = Integer.parseInt(nivelStr);
                        
                        stareDnrOn = (nivelCitit > 0);
                        if (stareDnrOn) nivelDnrCurent = nivelCitit;
                        
                        actualizeazaGraficaDnr(stareDnrOn, nivelDnrCurent);
                    }
                }
            } catch (Exception e) {}
        } else if (prefix.equals("KS")) {
            try {
                int viteza = Integer.parseInt(comandaScurta.substring(2, 5));
                if (viteza >= 15 && viteza <= 30) {
                    sbCwSpeed.setProgress(viteza - 15);
                    tvCwSpeedValue.setText(viteza + " WPM");
                }
            } catch (Exception e) {}
        }
    }
} // Acoladă finală care închide clasa EcranSetari
