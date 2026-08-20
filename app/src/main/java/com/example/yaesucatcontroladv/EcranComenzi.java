package com.example.yaesucatcontroladv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.example.yaesucatcontroladv.deepcw.audio.AudioListenPlayer;

import android.annotation.SuppressLint;
import android.widget.Toast;

import java.util.Locale;

public class EcranComenzi {

    private final Context context;
    private final RadioManager radioManager;
    private LinearLayout viewPrincipal;
    private CwKeyer cwKeyer;

    // Elemente grafice care trebuie actualizate din exterior (prin mesaje CAT)
    private TextView tvSMeterValue;
    private ProgressBar pbSMeter;
    private Button btnLSB, btnUSB, btnCW, btnRTTYL;
    private Button btnBkInToggle, btnApfToggle, btnWidthToggle, btnConnect;
    private Button btnListenToggle, btnListenDenoiseToggle;
    private LinearLayout llDenoiseControls;
    private TextView tvLevelVal, tvGainVal, tvBufferVal;
    private SeekBar sbLevel, sbGain, sbBuffer;
    private CheckBox cbLockDenoiseControls;

    private Button btnPaddleLeft, btnPaddleRight;
    private LinearLayout containerButoaneCwExtra;
    private LinearLayout containerCwKeyer;

    // Listen Player & Stare
    private AudioListenPlayer listenPlayer;
    private boolean listenActive = false;

    // Logică Auto-Hide și Long-Press pentru panoul de denoise
    private final Handler panelHandler = new Handler(Looper.getMainLooper());
    private final Runnable hidePanelRunnable = () -> {
        if (llDenoiseControls != null) llDenoiseControls.setVisibility(View.GONE);
    };

    private final Handler longPressHandler = new Handler(Looper.getMainLooper());
    private Runnable longPressRunnable;
    private boolean longPressTriggered = false;

    // Variabile de stare internă pentru butoane
    private boolean stareBkInOn = false;
    private boolean stareApfOn = false;
    private boolean stareWidth200On = false;
    private int modLucruCurent = 1; // 1 = Voce, 2 = CW

    // Chei SharedPreferences pentru Keyer
    private static final String PREF_CW_WPM = "cw_keyer_wpm";
    private static final String PREF_CW_REV = "cw_keyer_rev";
    private static final String PREF_CW_STR = "cw_keyer_str";
    private static final String PREF_CW_IAMBIC_B = "cw_keyer_iambic_b";
    private static final String PREF_CW_RTS = "cw_keyer_rts";

    private static final String LISTEN_PREFS = "LISTEN_PREFS";
    private static final String PREF_LISTEN_DENOISE = "listen_denoise";
    private static final String PREF_LISTEN_WET = "listen_wet";
    private static final String PREF_LISTEN_GAIN = "listen_gain";
    private static final String PREF_LISTEN_BUFFER_MS = "listen_buffer_ms";
    private static final String PREF_LISTEN_LOCK_CONTROLS = "listen_lock_controls";

    private boolean isInitialLoading = false;

    public EcranComenzi(Context context, RadioManager radioManager) {
        this.context = context;
        this.radioManager = radioManager;
        this.cwKeyer = new CwKeyer(radioManager);
        this.listenPlayer = new AudioListenPlayer(context);
        construiesteInterfata();
        incarcaSetariKeyer();
        incarcaSetariListen();

        // Inregistrare pentru exclusivitate reciproca cu decodorul CW AI
        AudioCaptureCoordinator.registerListen(() -> {
            if (listenActive) stopListen();
        });
    }

    public View getView() {
        return viewPrincipal;
    }

    private void construiesteInterfata() {
        viewPrincipal = new LinearLayout(context);
        viewPrincipal.setOrientation(LinearLayout.VERTICAL);
        viewPrincipal.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        ScrollView scrollArea = new ScrollView(context);
        scrollArea.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout llCAT = new LinearLayout(context);
        llCAT.setOrientation(LinearLayout.VERTICAL);
        llCAT.setPadding(30, 30, 30, 30);
        scrollArea.addView(llCAT);
        viewPrincipal.addView(scrollArea);

        // --- BUTTON STATIE CONECTARE ---
        btnConnect = new Button(context);
        btnConnect.setText("CONNECT RADIO STATION");
        btnConnect.setBackgroundColor(Color.parseColor("#007ACC"));
        btnConnect.setTextColor(Color.WHITE);
        btnConnect.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams pConn = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 140);
        pConn.setMargins(0, 0, 0, 30);
        btnConnect.setLayoutParams(pConn);
        btnConnect.setOnClickListener(v -> {
            if (radioManager != null) {
                radioManager.conecteazaStatie();
            }
        });
        llCAT.addView(btnConnect);

        // --- BUTOANE LISTEN & DENOISE (PLAN 2) ---
        LinearLayout rListen = new LinearLayout(context);
        rListen.setOrientation(LinearLayout.HORIZONTAL);
        rListen.setPadding(0, 0, 0, 30);

        btnListenToggle = new Button(context);
        btnListenToggle.setText("🔊 LISTEN: OFF");
        btnListenToggle.setBackgroundColor(Color.parseColor("#37474F"));
        btnListenToggle.setTextColor(Color.WHITE);
        btnListenToggle.setLayoutParams(new LinearLayout.LayoutParams(0, 130, 1.2f));
        btnListenToggle.setOnClickListener(v -> toggleListen());
        rListen.addView(btnListenToggle);

        View spacerListen = new View(context);
        spacerListen.setLayoutParams(new LinearLayout.LayoutParams(20, 1));
        rListen.addView(spacerListen);

        btnListenDenoiseToggle = new Button(context);
        btnListenDenoiseToggle.setText("DENOISE: OFF");
        btnListenDenoiseToggle.setBackgroundColor(Color.parseColor("#37474F"));
        btnListenDenoiseToggle.setTextColor(Color.WHITE);
        btnListenDenoiseToggle.setLayoutParams(new LinearLayout.LayoutParams(0, 130, 1f));
        btnListenDenoiseToggle.setOnClickListener(v -> toggleListenDenoise());
        rListen.addView(btnListenDenoiseToggle);
        
        llCAT.addView(rListen);

        // --- PANOU CONTROALE DENOISE (PLAN 3) ---
        llDenoiseControls = new LinearLayout(context);
        llDenoiseControls.setOrientation(LinearLayout.VERTICAL);
        llDenoiseControls.setBackgroundColor(Color.parseColor("#263238"));
        llDenoiseControls.setPadding(30, 20, 30, 20);
        llDenoiseControls.setVisibility(View.GONE);
        LinearLayout.LayoutParams pDenoisePanel = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pDenoisePanel.setMargins(0, 0, 0, 30);
        llDenoiseControls.setLayoutParams(pDenoisePanel);

        // LEVEL (Mix Wet/Dry)
        tvLevelVal = creeazaLabelControl("LEVEL: 100%");
        llDenoiseControls.addView(tvLevelVal);
        sbLevel = new SeekBar(context);
        sbLevel.setMax(100); // 0-100%
        sbLevel.setProgress(100);
        sbLevel.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float wet = progress / 100f;
                tvLevelVal.setText("LEVEL: " + progress + "%");
                listenPlayer.setWetLevel(wet);
                if (fromUser) {
                    salveazaSetareListen(PREF_LISTEN_WET, wet);
                    resetAutoHideTimer();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        llDenoiseControls.addView(sbLevel);

        // OUTPUT GAIN
        tvGainVal = creeazaLabelControl("GAIN: 1.00×");
        llDenoiseControls.addView(tvGainVal);
        sbGain = new SeekBar(context);
        sbGain.setMax(200);
        sbGain.setProgress(100);
        sbGain.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float gain = progress / 100f;
                tvGainVal.setText(String.format(Locale.US, "GAIN: %.2f×", gain));
                listenPlayer.setOutputGain(gain);
                if (fromUser) {
                    salveazaSetareListen(PREF_LISTEN_GAIN, gain);
                    resetAutoHideTimer();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        llDenoiseControls.addView(sbGain);

        // BUFFER (Latency)
        tvBufferVal = creeazaLabelControl("BUFFER: 90 ms");
        llDenoiseControls.addView(tvBufferVal);
        sbBuffer = new SeekBar(context);
        sbBuffer.setMax(4); // 40, 60, 90, 120, 160
        sbBuffer.setProgress(2);
        final int[] bufferVals = {40, 60, 90, 120, 160};
        sbBuffer.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int ms = bufferVals[progress];
                tvBufferVal.setText("BUFFER: " + ms + " ms");
                listenPlayer.setBufferMs(ms);
                if (fromUser) {
                    salveazaSetareListen(PREF_LISTEN_BUFFER_MS, ms);
                    resetAutoHideTimer();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        llDenoiseControls.addView(sbBuffer);

        // LOCK CONTROLS (Plan 4)
        cbLockDenoiseControls = new CheckBox(context);
        cbLockDenoiseControls.setText("KEEP CONTROLS VISIBLE");
        cbLockDenoiseControls.setTextColor(Color.YELLOW);
        cbLockDenoiseControls.setTextSize(11);
        LinearLayout.LayoutParams pLock = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pLock.setMargins(0, 20, 0, 0);
        cbLockDenoiseControls.setLayoutParams(pLock);
        cbLockDenoiseControls.setOnCheckedChangeListener((v, isChecked) -> {
            if (isChecked) {
                panelHandler.removeCallbacks(hidePanelRunnable);
            } else {
                resetAutoHideTimer();
            }
            if (!isInitialLoading) {
                salveazaSetareListen(PREF_LISTEN_LOCK_CONTROLS, isChecked);
            }
        });
        llDenoiseControls.addView(cbLockDenoiseControls);

        llCAT.addView(llDenoiseControls);

        // --- S-METRU GRAFIC ---
        LinearLayout randSMeter = new LinearLayout(context);
        randSMeter.setOrientation(LinearLayout.HORIZONTAL);
        randSMeter.setGravity(Gravity.CENTER_VERTICAL);
        randSMeter.setPadding(10, 15, 10, 15);

        tvSMeterValue = new TextView(context);
        tvSMeterValue.setText("S: 0");
        tvSMeterValue.setTextColor(Color.parseColor("#007ACC"));
        tvSMeterValue.setTextSize(15);
        tvSMeterValue.setTypeface(null, Typeface.BOLD);
        tvSMeterValue.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.2f));
        tvSMeterValue.setGravity(Gravity.CENTER);

        pbSMeter = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        pbSMeter.setScaleY(3.0f); // Îngroșare scală Y conform cerințelor tale
        pbSMeter.setMax(255);
        pbSMeter.setProgress(0);
        pbSMeter.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#FFB300"))); // Chihlimbar
        pbSMeter.setLayoutParams(new LinearLayout.LayoutParams(0, 65, 0.8f));

        randSMeter.addView(tvSMeterValue);
        randSMeter.addView(pbSMeter);
        llCAT.addView(randSMeter);

        // --- BUTOANE SCHIMBARE MODURI DE LUCRU ---
        LinearLayout rMod1 = creeazaRandButoane();
        btnLSB = creeazaButonMod("LSB", "#757575", v -> {
            radioManager.trimiteComandaCAT("MD01;");
            actualizeazaInterfataModuriInstant("01");
        });
        btnUSB = creeazaButonMod("USB", "#757575", v -> {
            radioManager.trimiteComandaCAT("MD02;");
            actualizeazaInterfataModuriInstant("02");
        });
        rMod1.addView(btnLSB);
        rMod1.addView(btnUSB);
        llCAT.addView(rMod1);

        LinearLayout rMod2 = creeazaRandButoane();
        btnCW = creeazaButonMod("CW", "#757575", v -> {
            radioManager.trimiteComandaCAT("MD03;");
            actualizeazaInterfataModuriInstant("03");
        });
        btnRTTYL = creeazaButonMod("RTTY-L", "#757575", v -> {
            radioManager.trimiteComandaCAT("MD06;");
            actualizeazaInterfataModuriInstant("06");
        });
        rMod2.addView(btnCW);
        rMod2.addView(btnRTTYL);
        llCAT.addView(rMod2);

        // --- ETICHETĂ MESAJE CANALE ---
        TextView tvL = new TextView(context);
        tvL.setText("CW MESSAGES / DVS VOICE");
        tvL.setTextColor(Color.WHITE);
        tvL.setTypeface(null, Typeface.BOLD);
        tvL.setGravity(Gravity.CENTER);
        tvL.setPadding(0, 30, 0, 15);
        llCAT.addView(tvL);

        // --- CASUTE MEMORII M1 - M5 ---
        LinearLayout rCw = creeazaRandButoane();
        for (int i = 1; i <= 5; i++) {
            final int canal = i;
            Button btnM = new Button(context);
            btnM.setText("M" + i);
            btnM.setBackgroundColor(Color.parseColor("#607D8B"));
            btnM.setTextColor(Color.WHITE);
            btnM.setLayoutParams(new LinearLayout.LayoutParams(0, 130, 1f));
            btnM.setOnClickListener(v -> executaMesajPeCanal(canal));
            rCw.addView(btnM);
        }
        llCAT.addView(rCw);

        // --- BUTON OPRIRE TRANSMISIE (STOP) ---
        Button btnStop = new Button(context);
        btnStop.setText("STOP TRANSMISSION");
        btnStop.setBackgroundColor(Color.parseColor("#B71C1C"));
        btnStop.setTextColor(Color.WHITE);
        btnStop.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams pStop = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 130);
        pStop.setMargins(0, 40, 0, 0);
        btnStop.setLayoutParams(pStop);
        btnStop.setOnClickListener(v -> radioManager.trimiteComandaCAT(modLucruCurent == 1 ? "PB00;" : "KY00;"));
        llCAT.addView(btnStop);

        // --- CONTAINER DINAMIC: MATRICE EXTRA REPARATĂ PENTRU MODUL CW ---
        containerButoaneCwExtra = new LinearLayout(context);
        containerButoaneCwExtra.setOrientation(LinearLayout.VERTICAL);
        containerButoaneCwExtra.setVisibility(View.GONE);
        LinearLayout.LayoutParams pCwExtra = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pCwExtra.setMargins(0, 30, 0, 0);
        containerButoaneCwExtra.setLayoutParams(pCwExtra);

        LinearLayout.LayoutParams pButonOrizontal = new LinearLayout.LayoutParams(0, 130, 1f);
        pButonOrizontal.setMargins(5, 0, 5, 15);

        // Rândul 1 CW (Zero-In | BK-IN)
        LinearLayout randCw1 = new LinearLayout(context);
        randCw1.setOrientation(LinearLayout.HORIZONTAL);
        Button btnZi1 = new Button(context);
        btnZi1.setText("ZI1 (Zero-In)");
        btnZi1.setBackgroundColor(Color.parseColor("#455A64"));
        btnZi1.setTextColor(Color.WHITE);
        btnZi1.setLayoutParams(pButonOrizontal);
        btnZi1.setOnClickListener(v -> radioManager.trimiteComandaCAT("ZI0;"));
        randCw1.addView(btnZi1);

        btnBkInToggle = new Button(context);
        btnBkInToggle.setText("BK-IN: OFF");
        btnBkInToggle.setBackgroundColor(Color.parseColor("#757575"));
        btnBkInToggle.setTextColor(Color.WHITE);
        btnBkInToggle.setLayoutParams(pButonOrizontal);
        btnBkInToggle.setOnClickListener(v -> radioManager.trimiteComandaCAT(stareBkInOn ? "BI0;" : "BI1;"));
        randCw1.addView(btnBkInToggle);
        containerButoaneCwExtra.addView(randCw1);

        // Rândul 2 CW (APF | WIDTH)
        LinearLayout randCw2 = new LinearLayout(context);
        randCw2.setOrientation(LinearLayout.HORIZONTAL);
        btnApfToggle = new Button(context);
        btnApfToggle.setText("APF: OFF");
        btnApfToggle.setBackgroundColor(Color.parseColor("#757575"));
        btnApfToggle.setTextColor(Color.WHITE);
        btnApfToggle.setLayoutParams(pButonOrizontal);
        btnApfToggle.setOnClickListener(v -> radioManager.trimiteComandaCAT(stareApfOn ? "CO020000;" : "CO020001;"));
        randCw2.addView(btnApfToggle);

        btnWidthToggle = new Button(context);
        btnWidthToggle.setText("WIDTH: STND");
        btnWidthToggle.setBackgroundColor(Color.parseColor("#757575"));
        btnWidthToggle.setTextColor(Color.WHITE);
        btnWidthToggle.setLayoutParams(pButonOrizontal);
        btnWidthToggle.setOnClickListener(v -> radioManager.trimiteComandaCAT(stareWidth200On ? "SH0000;" : "SH0004;"));
        randCw2.addView(btnWidthToggle);
        containerButoaneCwExtra.addView(randCw2);

        // Etichetă CW KEYER
        TextView tvKeyerLabel = new TextView(context);
        tvKeyerLabel.setText("CW KEYER");
        tvKeyerLabel.setTextColor(Color.WHITE);
        tvKeyerLabel.setTypeface(null, Typeface.BOLD);
        tvKeyerLabel.setGravity(Gravity.CENTER);
        tvKeyerLabel.setPadding(0, 10, 0, 10);
        containerButoaneCwExtra.addView(tvKeyerLabel);

        llCAT.addView(containerButoaneCwExtra);

        // --- CONSTRUIRE KEYER CW (FIXED AT BOTTOM) ---
        construiesteInterfataKeyer();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void construiesteInterfataKeyer() {
        containerCwKeyer = new LinearLayout(context);
        containerCwKeyer.setOrientation(LinearLayout.VERTICAL);
        containerCwKeyer.setBackgroundColor(Color.parseColor("#1A1A1A"));
        containerCwKeyer.setPadding(20, 20, 20, 20);
        containerCwKeyer.setVisibility(View.GONE); // Vizibil doar în modul CW
        
        // Rândul 1: Setări (REV, STR, LINE)
        LinearLayout rSettings = new LinearLayout(context);
        rSettings.setOrientation(LinearLayout.HORIZONTAL);
        rSettings.setGravity(Gravity.CENTER_VERTICAL);
        
        CheckBox cbRev = new CheckBox(context);
        cbRev.setText("REV");
        cbRev.setTextColor(Color.YELLOW);
        cbRev.setOnCheckedChangeListener((v, isChecked) -> {
            cwKeyer.setReversed(isChecked);
            actualizeazaEtichetePadele(isChecked);
            if (!isInitialLoading) salveazaSetareKeyer(PREF_CW_REV, isChecked);
        });
        
        CheckBox cbStr = new CheckBox(context);
        cbStr.setText("STR");
        cbStr.setTextColor(Color.CYAN);
        cbStr.setOnCheckedChangeListener((v, isChecked) -> {
            cwKeyer.setStraightKeyMode(isChecked);
            if (!isInitialLoading) salveazaSetareKeyer(PREF_CW_STR, isChecked);
        });

        Button btnIambicMode = new Button(context);
        btnIambicMode.setText("IAMBIC B");
        btnIambicMode.setBackgroundColor(Color.parseColor("#424242"));
        btnIambicMode.setTextColor(Color.WHITE);
        btnIambicMode.setTextSize(10);
        btnIambicMode.setLayoutParams(new LinearLayout.LayoutParams(0, 100, 1f));
        btnIambicMode.setOnClickListener(v -> {
            boolean isNowB = btnIambicMode.getText().toString().equals("IAMBIC B");
            if (isNowB) {
                btnIambicMode.setText("IAMBIC A");
                cwKeyer.setIambicModeB(false);
                salveazaSetareKeyer(PREF_CW_IAMBIC_B, false);
            } else {
                btnIambicMode.setText("IAMBIC B");
                cwKeyer.setIambicModeB(true);
                salveazaSetareKeyer(PREF_CW_IAMBIC_B, true);
            }
        });
        
        Button btnLine = new Button(context);
        btnLine.setText("USE: RTS");
        btnLine.setBackgroundColor(Color.parseColor("#424242"));
        btnLine.setTextColor(Color.WHITE);
        btnLine.setTextSize(10);
        btnLine.setLayoutParams(new LinearLayout.LayoutParams(0, 100, 1.2f));
        btnLine.setOnClickListener(v -> {
            boolean useRtsNow = btnLine.getText().toString().contains("RTS");
            if (useRtsNow) {
                btnLine.setText("USE: DTR");
                cwKeyer.setUseRts(false);
                if (!isInitialLoading) salveazaSetareKeyer(PREF_CW_RTS, false);
            } else {
                btnLine.setText("USE: RTS");
                cwKeyer.setUseRts(true);
                if (!isInitialLoading) salveazaSetareKeyer(PREF_CW_RTS, true);
            }
        });

        rSettings.addView(cbRev);
        rSettings.addView(creeazaSpatiuOrizontal());
        rSettings.addView(cbStr);
        rSettings.addView(creeazaSpatiuOrizontal());
        rSettings.addView(btnIambicMode);
        rSettings.addView(creeazaSpatiuOrizontal());
        rSettings.addView(btnLine);
        containerCwKeyer.addView(rSettings);

        // Rândul 2: Viteză (WPM Slider)
        LinearLayout rWpm = new LinearLayout(context);
        rWpm.setOrientation(LinearLayout.HORIZONTAL);
        rWpm.setGravity(Gravity.CENTER_VERTICAL);
        rWpm.setPadding(0, 10, 0, 10);

        TextView tvWpm = new TextView(context);
        tvWpm.setText("20 WPM");
        tvWpm.setTextColor(Color.WHITE);
        tvWpm.setTextSize(14);
        tvWpm.setTypeface(null, Typeface.BOLD);
        tvWpm.setLayoutParams(new LinearLayout.LayoutParams(180, LinearLayout.LayoutParams.WRAP_CONTENT));

        SeekBar sbWpm = new SeekBar(context);
        sbWpm.setMax(25); // 35 - 10 = 25
        sbWpm.setProgress(10); // Implicit 20 (20 - 10 = 10)
        sbWpm.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        sbWpm.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int wpm = progress + 10;
                tvWpm.setText(wpm + " WPM");
                cwKeyer.setWpm(wpm);
                if (fromUser) salveazaSetareKeyer(PREF_CW_WPM, wpm);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        rWpm.addView(tvWpm);
        rWpm.addView(sbWpm);
        containerCwKeyer.addView(rWpm);

        // Rândul 3: Padelele (Paddles)
        LinearLayout rPaddles = new LinearLayout(context);
        rPaddles.setOrientation(LinearLayout.HORIZONTAL);
        
        btnPaddleLeft = new Button(context);
        btnPaddleLeft.setText("DIT");
        btnPaddleLeft.setBackgroundColor(Color.parseColor("#37474F"));
        btnPaddleLeft.setTextColor(Color.WHITE);
        btnPaddleLeft.setLayoutParams(new LinearLayout.LayoutParams(0, 300, 1f));
        btnPaddleLeft.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                btnPaddleLeft.setBackgroundColor(Color.parseColor("#00E676"));
                cwKeyer.setLeftPaddle(true);
                return true;
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                btnPaddleLeft.setBackgroundColor(Color.parseColor("#37474F"));
                cwKeyer.setLeftPaddle(false);
                return true;
            }
            return false;
        });

        btnPaddleRight = new Button(context);
        btnPaddleRight.setText("DAH");
        btnPaddleRight.setBackgroundColor(Color.parseColor("#37474F"));
        btnPaddleRight.setTextColor(Color.WHITE);
        btnPaddleRight.setLayoutParams(new LinearLayout.LayoutParams(0, 300, 1f));
        btnPaddleRight.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                btnPaddleRight.setBackgroundColor(Color.parseColor("#00E676"));
                cwKeyer.setRightPaddle(true);
                return true;
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                btnPaddleRight.setBackgroundColor(Color.parseColor("#37474F"));
                cwKeyer.setRightPaddle(false);
                return true;
            }
            return false;
        });

        rPaddles.addView(btnPaddleLeft);
        View pSpacer = new View(context);
        pSpacer.setLayoutParams(new LinearLayout.LayoutParams(10, 1));
        rPaddles.addView(pSpacer);
        rPaddles.addView(btnPaddleRight);
        containerCwKeyer.addView(rPaddles);

        viewPrincipal.addView(containerCwKeyer);
    }

    private View creeazaSpatiuOrizontal() {
        View v = new View(context);
        v.setLayoutParams(new LinearLayout.LayoutParams(20, 1));
        return v;
    }

    private LinearLayout creeazaRandButoane() {
        LinearLayout r = new LinearLayout(context);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 5, 0, 15);
        r.setLayoutParams(p);
        return r;
    }

    private Button creeazaButonMod(String t, String c, View.OnClickListener cl) {
        Button b = new Button(context);
        b.setText(t);
        b.setBackgroundColor(Color.parseColor(c));
        b.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 130, 1f);
        p.setMargins(5, 0, 5, 0);
        b.setLayoutParams(p);
        b.setOnClickListener(cl);
        return b;
    }

    private void executaMesajPeCanal(int canal) {
        // --- CONFIGURAT CONFORM TESTULUI LIVE: Trimitem DOAR comanda unică KY0 pentru CW ---
        android.content.SharedPreferences prefs = context.getSharedPreferences("CW_MACROS", Context.MODE_PRIVATE);
        String content = prefs.getString("cw_content_" + (canal - 1), "");

        if (modLucruCurent == 1) {
            // 1. MOD VOCE (LSB/USB): Rămâne neschimbat pe PB0
            radioManager.trimiteComandaCAT("PB0" + canal + ";");
        } else {
            // 2. MOD TELEGRAFIE (CW)
            if (!content.isEmpty()) {
                radioManager.trimiteComandaCAT("KY " + content.toUpperCase() + ";");
            } else {
                // Dacă e gol, forțăm strict declanșarea memoriei prin KY0, eliminând complet formatul KY1 parazit!
                radioManager.trimiteComandaCAT("KY0" + canal + ";");
            }
        }
    }


    // --- PARSER LOCAL PENTRU COORDONAREA LIVE A INTERFEȚEI DUPĂ RĂSPUNSURILE CAT ---
    public void proceseazaDateCATInEcran(String prefix, String comandaScurta) {
        if (comandaScurta == null) return; // FIX: protecție null

        if (prefix.equals("MD")) {
            if (comandaScurta.length() >= 2) actualizeazaInterfataModuriInstant(comandaScurta.substring(2));
        } else if (prefix.equals("BI")) {
            if (comandaScurta.length() >= 3) {
                stareBkInOn = comandaScurta.substring(2).startsWith("1");
                btnBkInToggle.setText(stareBkInOn ? "BK-IN: ON" : "BK-IN: OFF");
                btnBkInToggle.setBackgroundColor(Color.parseColor(stareBkInOn ? "#2E7D32" : "#757575"));
            }
        } else if (prefix.equals("CO") && comandaScurta.startsWith("CO02")) {
            if (comandaScurta.length() >= 6) {
                stareApfOn = comandaScurta.endsWith("01");
                btnApfToggle.setText(stareApfOn ? "APF: ON" : "APF: OFF");
                btnApfToggle.setBackgroundColor(Color.parseColor(stareApfOn ? "#2E7D32" : "#757575"));
            }
        } else if (prefix.equals("SH") && comandaScurta.startsWith("SH0")) {
            if (comandaScurta.length() >= 5) {
                stareWidth200On = comandaScurta.endsWith("04");
                btnWidthToggle.setText(stareWidth200On ? "WIDTH: 200Hz" : "WIDTH: STND");
                btnWidthToggle.setBackgroundColor(Color.parseColor(stareWidth200On ? "#2E7D32" : "#757575"));
            }
        } else if (prefix.equals("RM")) {
            try {
                if (comandaScurta.length() < 6) return; // FIX: verificare lungime
                // Verificăm dacă este pentru S-Meter (RM1); RM8 (tensiune) este ignorat aici
                if (comandaScurta.charAt(2) != '1') return;

                int nivelBrut = Integer.parseInt(comandaScurta.substring(3, 6));
                pbSMeter.setProgress(nivelBrut);
                String label = "S " + (nivelBrut / 15);
                if (nivelBrut > 110) label = "S9+";
                tvSMeterValue.setText(label);
                pbSMeter.setProgressTintList(android.content.res.ColorStateList.valueOf(
                        Color.parseColor(nivelBrut > 110 ? "#D32F2F" : "#FFB300")));
            } catch (Exception e) { /* parsing failed, ignore */ }
        } else if (prefix.equals("PC")) {
            if (comandaScurta.length() >= 3) {
                char pfx = comandaScurta.charAt(2);
                String model = (pfx == '1') ? "FTX1 FIELD" : "FTX1 OPTIMA";
                btnConnect.setText("CONNECTED TO " + model);
                btnConnect.setBackgroundColor(Color.parseColor("#2E7D32")); // Verde succes
            }
        }
    }
    private void actualizeazaInterfataModuriInstant(String codMod) {
        final boolean esteCW = codMod.startsWith("03") || codMod.startsWith("07");
        modLucruCurent = esteCW ? 2 : 1;

        btnLSB.setBackgroundColor(Color.parseColor("#757575"));
        btnUSB.setBackgroundColor(Color.parseColor("#757575"));
        btnCW.setBackgroundColor(Color.parseColor("#757575"));
        btnRTTYL.setBackgroundColor(Color.parseColor("#757575"));

        if (codMod.startsWith("01")) btnLSB.setBackgroundColor(Color.parseColor("#007ACC"));
        else if (codMod.startsWith("02")) btnUSB.setBackgroundColor(Color.parseColor("#007ACC"));
        else if (codMod.startsWith("03") || codMod.startsWith("07")) btnCW.setBackgroundColor(Color.parseColor("#2E7D32"));
        else if (codMod.startsWith("06") || codMod.startsWith("09") || codMod.startsWith("0C")) btnRTTYL.setBackgroundColor(Color.parseColor("#E65100"));

        containerButoaneCwExtra.setVisibility(esteCW ? View.VISIBLE : View.GONE);
        if (containerCwKeyer != null) {
            containerCwKeyer.setVisibility(esteCW ? View.VISIBLE : View.GONE);
        }
        
        // Activăm ieșirea hardware a Keyer-ului DOAR dacă suntem în CW
        if (cwKeyer != null) {
            cwKeyer.setHardwareOutputEnabled(esteCW);
        }
    }

    /**
     * Actualizează starea vizuală a butonului de conectare în funcție de link-ul fizic USB.
     */
    private void salveazaSetareKeyer(String cheie, Object valoare) {
        android.content.SharedPreferences.Editor editor = context.getSharedPreferences(EcranSetariProprii.PREFS_NAME, Context.MODE_PRIVATE).edit();
        if (valoare instanceof Integer) editor.putInt(cheie, (Integer) valoare);
        else if (valoare instanceof Boolean) editor.putBoolean(cheie, (Boolean) valoare);
        editor.apply();
    }

    private void incarcaSetariKeyer() {
        isInitialLoading = true;
        android.content.SharedPreferences prefs = context.getSharedPreferences(EcranSetariProprii.PREFS_NAME, Context.MODE_PRIVATE);
        
        int wpm = prefs.getInt(PREF_CW_WPM, 20);
        boolean rev = prefs.getBoolean(PREF_CW_REV, false);
        boolean str = prefs.getBoolean(PREF_CW_STR, false);
        boolean iambicB = prefs.getBoolean(PREF_CW_IAMBIC_B, true);
        boolean rts = prefs.getBoolean(PREF_CW_RTS, true);

        cwKeyer.setWpm(wpm);
        cwKeyer.setReversed(rev);
        cwKeyer.setStraightKeyMode(str);
        cwKeyer.setIambicModeB(iambicB);
        cwKeyer.setUseRts(rts);

        // Actualizăm etichetele padelelor
        actualizeazaEtichetePadele(rev);

        // Actualizăm interfața grafică pentru a reflecta setările încărcate
        if (containerCwKeyer != null) {
            for (int i = 0; i < containerCwKeyer.getChildCount(); i++) {
                View v = containerCwKeyer.getChildAt(i);
                if (v instanceof LinearLayout) {
                    LinearLayout row = (LinearLayout) v;
                    for (int j = 0; j < row.getChildCount(); j++) {
                        View child = row.getChildAt(j);
                        if (child instanceof CheckBox) {
                            CheckBox cb = (CheckBox) child;
                            if (cb.getText().equals("REV")) cb.setChecked(rev);
                            else if (cb.getText().equals("STR")) cb.setChecked(str);
                        } else if (child instanceof Button) {
                            Button b = (Button) child;
                            if (b.getText().toString().contains("IAMBIC")) {
                                b.setText(iambicB ? "IAMBIC B" : "IAMBIC A");
                            } else if (b.getText().toString().startsWith("USE:")) {
                                b.setText(rts ? "USE: RTS" : "USE: DTR");
                            }
                        } else if (child instanceof SeekBar) {
                            ((SeekBar) child).setProgress(wpm - 10);
                        }
                    }
                }
            }
        }
        isInitialLoading = false;
    }

    private void actualizeazaEtichetePadele(boolean reversed) {
        if (btnPaddleLeft != null && btnPaddleRight != null) {
            if (reversed) {
                btnPaddleLeft.setText("DAH");
                btnPaddleRight.setText("DIT");
            } else {
                btnPaddleLeft.setText("DIT");
                btnPaddleRight.setText("DAH");
            }
        }
    }

    public void setStareConexiune(boolean connected) {
        if (!connected) {
            btnConnect.setText("CONNECT RADIO STATION");
            btnConnect.setBackgroundColor(Color.parseColor("#007ACC"));
            containerButoaneCwExtra.setVisibility(View.GONE);
            if (containerCwKeyer != null) containerCwKeyer.setVisibility(View.GONE);
            if (cwKeyer != null) cwKeyer.setHardwareOutputEnabled(false);
            if (listenActive) stopListen();
        }
    }

    public void eliberareResurse() {
        if (cwKeyer != null) {
            cwKeyer.shutdown();
        }
        if (listenPlayer != null) {
            listenPlayer.stop();
        }
    }

    // --- LOGICA LISTEN (PLAN 2) ---

    private void toggleListen() {
        if (!listenActive) {
            verificareSiPornireListen();
        } else {
            stopListen();
        }
    }

    private void verificareSiPornireListen() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions((android.app.Activity) context,
                    new String[]{android.Manifest.permission.RECORD_AUDIO}, 124);
        } else {
            startListen();
        }
    }

    private void startListen() {
        AudioCaptureCoordinator.beforeListenStart();
        AudioListenPlayer.StartResult result = listenPlayer.start();
        if (result == AudioListenPlayer.StartResult.OK) {
            listenActive = true;
            btnListenToggle.setText("🔊 LISTEN: ON");
            btnListenToggle.setBackgroundColor(Color.parseColor("#2E7D32"));
        } else if (result == AudioListenPlayer.StartResult.NO_USB_AUDIO) {
            Toast.makeText(context, "Conectati interfata audio USB a statiei pentru a asculta", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(context, "Nu s-a putut porni ascultarea audio", Toast.LENGTH_LONG).show();
        }
    }

    private void stopListen() {
        listenActive = false;
        listenPlayer.stop();
        btnListenToggle.setText("🔊 LISTEN: OFF");
        btnListenToggle.setBackgroundColor(Color.parseColor("#37474F"));
    }

    public void startListenDupaPermisiune() {
        startListen();
    }

    private void toggleListenDenoise() {
        boolean next = !listenPlayer.getDenoiseEnabled();
        boolean ok = listenPlayer.setDenoiseEnabled(next);
        if (ok) {
            syncListenDenoiseButtonUi(next);
            salveazaSetareListen(PREF_LISTEN_DENOISE, next ? 1 : 0);
            
            if (next) {
                showDenoiseControlsPanel();
            } else {
                panelHandler.removeCallbacks(hidePanelRunnable);
                llDenoiseControls.setVisibility(View.GONE);
            }
        } else {
            Toast.makeText(context, "Filtrul de reducere a zgomotului nu este disponibil pe acest device", Toast.LENGTH_LONG).show();
        }
    }

    private void syncListenDenoiseButtonUi(boolean on) {
        btnListenDenoiseToggle.setText("DENOISE: " + (on ? "ON" : "OFF"));
        btnListenDenoiseToggle.setBackgroundColor(on ? Color.parseColor("#2E7D32") : Color.parseColor("#37474F"));
        configurareLongPressDenoise(on);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void configurareLongPressDenoise(boolean on) {
        if (!on) {
            btnListenDenoiseToggle.setOnTouchListener(null);
            return;
        }

        btnListenDenoiseToggle.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    longPressTriggered = false;
                    longPressRunnable = () -> {
                        if (listenPlayer.getDenoiseEnabled() && llDenoiseControls.getVisibility() != View.VISIBLE) {
                            longPressTriggered = true;
                            showDenoiseControlsPanel();
                        }
                    };
                    longPressHandler.postDelayed(longPressRunnable, 3000);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    longPressHandler.removeCallbacks(longPressRunnable);
                    return longPressTriggered; // Consumă evenimentul dacă long-press a fost declanșat
            }
            return false;
        });
    }

    private void showDenoiseControlsPanel() {
        if (llDenoiseControls != null) {
            llDenoiseControls.setVisibility(View.VISIBLE);
            resetAutoHideTimer();
        }
    }

    private void resetAutoHideTimer() {
        panelHandler.removeCallbacks(hidePanelRunnable);
        if (cbLockDenoiseControls != null && cbLockDenoiseControls.isChecked()) return;
        panelHandler.postDelayed(hidePanelRunnable, 5000);
    }

    private TextView creeazaLabelControl(String text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(12);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, 10, 0, 0);
        return tv;
    }

    private void salveazaSetareListen(String cheie, Object valoare) {
        android.content.SharedPreferences.Editor editor = context.getSharedPreferences(LISTEN_PREFS, Context.MODE_PRIVATE).edit();
        if (valoare instanceof Integer) editor.putInt(cheie, (Integer) valoare);
        else if (valoare instanceof Boolean) editor.putBoolean(cheie, (Boolean) valoare);
        else if (valoare instanceof Float) editor.putFloat(cheie, (Float) valoare);
        editor.apply();
    }

    private void incarcaSetariListen() {
        isInitialLoading = true;
        android.content.SharedPreferences prefs = context.getSharedPreferences(LISTEN_PREFS, Context.MODE_PRIVATE);
        
        int denoiseVal = prefs.getInt(PREF_LISTEN_DENOISE, 0);
        float wet = prefs.getFloat(PREF_LISTEN_WET, 1.0f);
        float gain = prefs.getFloat(PREF_LISTEN_GAIN, 1.0f);
        int bufferMs = prefs.getInt(PREF_LISTEN_BUFFER_MS, 90);
        boolean locked = prefs.getBoolean(PREF_LISTEN_LOCK_CONTROLS, false);

        listenPlayer.setWetLevel(wet);
        listenPlayer.setOutputGain(gain);
        listenPlayer.setBufferMs(bufferMs);

        // Actualizare UI slidere
        sbLevel.setProgress((int)(wet * 100));
        sbGain.setProgress((int)(gain * 100));
        
        int bufferIdx = 2; // default 90
        int[] bufferVals = {40, 60, 90, 120, 160};
        for (int i = 0; i < bufferVals.length; i++) if (bufferVals[i] == bufferMs) bufferIdx = i;
        sbBuffer.setProgress(bufferIdx);

        cbLockDenoiseControls.setChecked(locked);

        if (denoiseVal == 1) {
            listenPlayer.setDenoiseEnabled(true);
            syncListenDenoiseButtonUi(true);
        }
        isInitialLoading = false;
    }
} // Ultima acoladă din fișier, cea care închide corect toată clasa EcranComenzi!
