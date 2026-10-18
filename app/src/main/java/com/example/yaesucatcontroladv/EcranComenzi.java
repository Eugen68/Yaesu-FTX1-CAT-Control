package com.example.yaesucatcontroladv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
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
import com.example.yaesucatcontroladv.deepcw.audio.MicUplinkPlayer;
import com.example.yaesucatcontroladv.deepcw.audio.CwTrainingKeyer;
import com.hoho.android.usbserial.driver.UsbSerialPort;

import android.annotation.SuppressLint;
import android.widget.Toast;

import java.io.IOException;
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

    // PTT (voce, LSB/USB) - hold-to-talk prin linia hardware RTS de pe portul de date
    // (acelasi mecanism ca CwKeyer.setHardwareKey/EcranRttyDroid.setHardwarePtt - RTS/DTR
    // cablate direct pe intrarea KEY/PTT a statiei, nu comanda CAT).
    private Button btnPtt;
    private boolean pttActive = false;
    private LinearLayout llPttSettings;
    private CheckBox cbPttToggleMode;
    private Button btnPttLineToggle;
    private Button btnCascaBtToggle;
    private boolean pttToggleMode = false; // false = hold-to-talk, true = apasa o data pentru TX/RX
    private boolean pttUseRts = true;      // linia hardware folosita pentru PTT (ca la CW Keyer)
    // Alegere manuala a sursei de microfon (in loc de detectie automata, nesigura la
    // conectare/deconectare live a castii - vezi aplicaSetareCascaBt()). true = incearca SCO
    // catre casca BT, false = ramane mereu pe microfonul telefonului, fara sa atinga SCO deloc.
    private boolean cascaBtActivata = true;
    private boolean pttSettingsAfisatePrinHold = false;
    private final Handler handlerPttSettings2 = new Handler(Looper.getMainLooper());
    private final Handler handlerReconnectSco = new Handler(Looper.getMainLooper());
    // Reincearca la 2s cat timp suntem in mod voce, dar verifica intai daca exista efectiv o
    // casca conectata (existaCascaBluetoothConectata()) inainte sa apeleze startBluetoothSco()
    // - fara acest gard, daca legatura Bluetooth de baza cade (nu doar SCO), am bate la usa in
    // gol la nesfarsit, tinand MODE_IN_COMMUNICATION intr-un ciclu conectare/eșec care distrugea
    // si emisia TX in curs (vezi receiver-ul SCO). Daca nu exista casca, ne re-programam singuri
    // peste 2s (spre deosebire de porneteScoInterna(), care nu are de unde sa stie sa o faca -
    // fara asta, bucla se opreste definitiv la prima verificare esuata, in loc sa astepte
    // revenirea castii).
    private final Runnable runnableReconnectSco = this::reincearcaSco;
    private final Runnable runnableAscundeSetariPtt2 = () -> {
        if (llPttSettings != null) llPttSettings.setVisibility(View.GONE);
    };
    private final Runnable runnableAratareSetariPtt = () -> {
        pttSettingsAfisatePrinHold = true;
        if (llPttSettings != null) {
            llPttSettings.setVisibility(View.VISIBLE);
            reseteazaTemporizatorSetariPtt();
        }
    };

    // Legatura Bluetooth SCO (casca cu microfon) - pornita o singura data la intrarea in
    // mod voce (nu la fiecare PTT, ca sa evitam intarzierea de conectare BT la inceputul
    // fiecarei emisii), oprita la iesirea din LSB/USB.
    private boolean scoActive = false;
    private boolean modVoceCurent = false; // LSB/USB (nu si RTTY, desi si acela e modLucruCurent==1)

    // Listen Player & Stare
    private AudioListenPlayer listenPlayer;
    private boolean listenActive = false;
    private MicUplinkPlayer micUplink;

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

    // --- MOD ANTRENAMENT CW (paletele suna in difuzor/casca, nu ating statia) ---
    // Comutat prin dublu-tap pe BK-IN (vezi handler-ul butonului). Un singur tap in fereastra
    // de asteptare executa comportamentul normal (toggle BK-IN pe statie); doua tap-uri
    // consecutive rapide intra/ies din antrenament, fara sa mai trimita CAT-ul de BK-IN.
    private boolean modAntrenamentCw = false;
    // Keyer separat, doar audio (vezi CwTrainingKeyer) - CwKeyer (emisia reala) nu e atins.
    private final CwTrainingKeyer trainingKeyer = new CwTrainingKeyer();
    private long ultimulTapBkIn = 0;
    private static final long FEREASTRA_DUBLU_TAP_MS = 350;
    private final Handler handlerBkInTap = new Handler(Looper.getMainLooper());
    private Runnable singurTapBkIn;

    // Chei SharedPreferences pentru Keyer
    private static final String PREF_CW_WPM = "cw_keyer_wpm";
    private static final String PREF_CW_REV = "cw_keyer_rev";
    private static final String PREF_CW_TON = "cw_training_ton_hz";
    private static final String PREF_CW_IAMBIC_B = "cw_keyer_iambic_b";
    private static final String PREF_CW_RTS = "cw_keyer_rts";

    private static final String LISTEN_PREFS = "LISTEN_PREFS";
    private static final String PREF_LISTEN_DENOISE = "listen_denoise";
    private static final String PREF_LISTEN_WET = "listen_wet";
    private static final String PREF_LISTEN_GAIN = "listen_gain";
    private static final String PREF_LISTEN_BUFFER_MS = "listen_buffer_ms";
    private static final String PREF_LISTEN_LOCK_CONTROLS = "listen_lock_controls";
    private static final String PREF_PTT_TOGGLE_MODE = "ptt_toggle_mode";
    private static final String PREF_PTT_USE_RTS = "ptt_use_rts";
    private static final String PREF_CASCA_BT_ACTIVATA = "casca_bt_activata";

    private boolean isInitialLoading = false;

    public EcranComenzi(Context context, RadioManager radioManager) {
        this.context = context;
        this.radioManager = radioManager;
        this.cwKeyer = new CwKeyer(radioManager);
        this.listenPlayer = new AudioListenPlayer(context);
        this.micUplink = new MicUplinkPlayer(context);
        inregistreazaReceiverSco();
        inregistreazaReceiverProfilHeadset();
        construiesteInterfata();
        incarcaSetariKeyer();
        incarcaSetariListen();

        // Inregistrare pentru exclusivitate reciproca cu decodorul CW AI
        AudioCaptureCoordinator.registerListen(() -> {
            if (listenActive) stopListen();
        });
    }

    public View getView() {
        // WPM/TON/REV/IAMBIC pot fi schimbate si din CW TRAINER (aceleasi preferinte).
        incarcaSetariKeyer();
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
        // Subliniat = are setari ascunse la apasare lunga (flag pe Paint, nu in text,
        // ca sa ramana si cand textul butonului se schimba).
        btnListenDenoiseToggle.setPaintFlags(btnListenDenoiseToggle.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
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

        // --- BUTON PTT (doar mod voce LSB/USB, hold-to-talk prin RTS/DTR) ---
        btnPtt = new Button(context);
        // Subliniat = are setari ascunse la apasare lunga (flag pe Paint, nu in text,
        // ca sa ramana si cand textul butonului se schimba).
        btnPtt.setPaintFlags(btnPtt.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        btnPtt.setText("PTT");
        btnPtt.setBackgroundColor(Color.parseColor("#757575"));
        btnPtt.setTextColor(Color.WHITE);
        btnPtt.setTypeface(null, Typeface.BOLD);
        btnPtt.setTextSize(16);
        LinearLayout.LayoutParams pPtt = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 190);
        pPtt.setMargins(5, 15, 5, 15);
        btnPtt.setLayoutParams(pPtt);
        btnPtt.setVisibility(View.GONE);
        llCAT.addView(btnPtt);

        // --- PANOU SETARI PTT (long-press 3s pe PTT il arata, ca la celelalte butoane subliniate, se ascunde singur dupa 3s) ---
        llPttSettings = new LinearLayout(context);
        llPttSettings.setOrientation(LinearLayout.HORIZONTAL);
        llPttSettings.setGravity(Gravity.CENTER_VERTICAL);
        llPttSettings.setVisibility(View.GONE);
        LinearLayout.LayoutParams pPttSettings = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pPttSettings.setMargins(5, 0, 5, 15);
        llPttSettings.setLayoutParams(pPttSettings);

        cbPttToggleMode = new CheckBox(context);
        cbPttToggleMode.setText("TOGGLE MODE");
        cbPttToggleMode.setTextColor(Color.YELLOW);
        cbPttToggleMode.setTextSize(11);
        LinearLayout.LayoutParams pCbToggle = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        cbPttToggleMode.setLayoutParams(pCbToggle);
        cbPttToggleMode.setOnCheckedChangeListener((v, isChecked) -> {
            pttToggleMode = isChecked;
            if (isInitialLoading) return;
            salveazaSetareListen(PREF_PTT_TOGGLE_MODE, isChecked);
            if (pttActive) setPttActive(false); // schimbarea modului cat era in TX ar fi ambigua
            reseteazaTemporizatorSetariPtt();
        });
        llPttSettings.addView(cbPttToggleMode);

        btnPttLineToggle = new Button(context);
        btnPttLineToggle.setText(pttUseRts ? "USE: RTS" : "USE: DTR");
        btnPttLineToggle.setBackgroundColor(Color.parseColor("#455A64"));
        btnPttLineToggle.setTextColor(Color.WHITE);
        btnPttLineToggle.setTextSize(11);
        LinearLayout.LayoutParams pBtnLinePtt = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        pBtnLinePtt.setMargins(15, 0, 0, 0);
        btnPttLineToggle.setLayoutParams(pBtnLinePtt);
        btnPttLineToggle.setOnClickListener(v -> {
            pttUseRts = !pttUseRts;
            btnPttLineToggle.setText(pttUseRts ? "USE: RTS" : "USE: DTR");
            salveazaSetareListen(PREF_PTT_USE_RTS, pttUseRts);
            reseteazaTemporizatorSetariPtt();
        });
        llPttSettings.addView(btnPttLineToggle);

        btnCascaBtToggle = new Button(context);
        btnCascaBtToggle.setText(cascaBtActivata ? "MIC: CASCA BT" : "MIC: TELEFON");
        btnCascaBtToggle.setBackgroundColor(Color.parseColor("#455A64"));
        btnCascaBtToggle.setTextColor(Color.WHITE);
        btnCascaBtToggle.setTextSize(11);
        LinearLayout.LayoutParams pBtnCascaBt = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        pBtnCascaBt.setMargins(15, 0, 0, 0);
        btnCascaBtToggle.setLayoutParams(pBtnCascaBt);
        btnCascaBtToggle.setOnClickListener(v -> {
            cascaBtActivata = !cascaBtActivata;
            btnCascaBtToggle.setText(cascaBtActivata ? "MIC: CASCA BT" : "MIC: TELEFON");
            salveazaSetareListen(PREF_CASCA_BT_ACTIVATA, cascaBtActivata);
            aplicaSetareCascaBt();
            reseteazaTemporizatorSetariPtt();
        });
        llPttSettings.addView(btnCascaBtToggle);

        llCAT.addView(llPttSettings);

        btnPtt.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    pttSettingsAfisatePrinHold = false;
                    handlerPttSettings2.postDelayed(runnableAratareSetariPtt, 3000);
                    if (!pttToggleMode) setPttActive(true);
                    return true;
                case MotionEvent.ACTION_UP:
                    handlerPttSettings2.removeCallbacks(runnableAratareSetariPtt);
                    if (pttToggleMode) {
                        if (!pttSettingsAfisatePrinHold) setPttActive(!pttActive);
                    } else {
                        setPttActive(false);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    handlerPttSettings2.removeCallbacks(runnableAratareSetariPtt);
                    if (!pttToggleMode) setPttActive(false);
                    return true;
                default:
                    return false;
            }
        });

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
        singurTapBkIn = () -> radioManager.trimiteComandaCAT(stareBkInOn ? "BI0;" : "BI1;");
        btnBkInToggle.setOnClickListener(v -> {
            long acum = System.currentTimeMillis();
            if (acum - ultimulTapBkIn < FEREASTRA_DUBLU_TAP_MS) {
                // Dublu-tap: anulam CAT-ul de BK-IN programat de primul tap si comutam antrenamentul.
                handlerBkInTap.removeCallbacks(singurTapBkIn);
                ultimulTapBkIn = 0;
                toggleModAntrenamentCw();
            } else {
                ultimulTapBkIn = acum;
                handlerBkInTap.postDelayed(singurTapBkIn, FEREASTRA_DUBLU_TAP_MS);
            }
        });
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
        
        // Rândul 1: Setări (REV, TON, IAMBIC, LINE)
        LinearLayout rSettings = new LinearLayout(context);
        rSettings.setOrientation(LinearLayout.HORIZONTAL);
        rSettings.setGravity(Gravity.CENTER_VERTICAL);
        
        CheckBox cbRev = new CheckBox(context);
        cbRev.setText("REV");
        cbRev.setTextColor(Color.YELLOW);
        cbRev.setOnCheckedChangeListener((v, isChecked) -> {
            cwKeyer.setReversed(isChecked);
            trainingKeyer.setReversed(isChecked);
            actualizeazaEtichetePadele(isChecked);
            if (!isInitialLoading) salveazaSetareKeyer(PREF_CW_REV, isChecked);
        });
        
        // Frecventa tonului din modul antrenament: 400 -> 500 -> 600 -> 700 -> 400 Hz.
        Button btnTon = new Button(context);
        btnTon.setText("TON: 600Hz");
        btnTon.setBackgroundColor(Color.parseColor("#424242"));
        btnTon.setTextColor(Color.WHITE);
        btnTon.setTextSize(10);
        btnTon.setLayoutParams(new LinearLayout.LayoutParams(0, 100, 1f));
        btnTon.setOnClickListener(v -> {
            int ton = trainingKeyer.getToneHz() >= 700 ? 400 : trainingKeyer.getToneHz() + 100;
            trainingKeyer.setToneHz(ton);
            btnTon.setText("TON: " + ton + "Hz");
            salveazaSetareKeyer(PREF_CW_TON, ton);
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
                trainingKeyer.setIambicModeB(false);
                salveazaSetareKeyer(PREF_CW_IAMBIC_B, false);
            } else {
                btnIambicMode.setText("IAMBIC B");
                cwKeyer.setIambicModeB(true);
                trainingKeyer.setIambicModeB(true);
                salveazaSetareKeyer(PREF_CW_IAMBIC_B, true);
            }
        });
        
        Button btnLine = new Button(context);
        btnLine.setText("USE: RTS");
        btnLine.setBackgroundColor(Color.parseColor("#424242"));
        btnLine.setTextColor(Color.WHITE);
        btnLine.setTextSize(10);
        btnLine.setLayoutParams(new LinearLayout.LayoutParams(0, 100, 1f));
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
        rSettings.addView(btnTon);
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
        // Latime dupa text si un singur rand - cu latime fixa "20 WPM" se rupea pe doua randuri.
        tvWpm.setSingleLine(true);
        tvWpm.setPadding(0, 0, 20, 0);
        tvWpm.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

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
                trainingKeyer.setWpm(wpm);
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
                if (modAntrenamentCw) trainingKeyer.setLeftPaddle(true);
                else cwKeyer.setLeftPaddle(true);
                return true;
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                btnPaddleLeft.setBackgroundColor(Color.parseColor("#37474F"));
                if (modAntrenamentCw) trainingKeyer.setLeftPaddle(false);
                else cwKeyer.setLeftPaddle(false);
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
                if (modAntrenamentCw) trainingKeyer.setRightPaddle(true);
                else cwKeyer.setRightPaddle(true);
                return true;
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                btnPaddleRight.setBackgroundColor(Color.parseColor("#37474F"));
                if (modAntrenamentCw) trainingKeyer.setRightPaddle(false);
                else cwKeyer.setRightPaddle(false);
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

    /**
     * Asculta ACTION_SCO_AUDIO_STATE_UPDATED - startBluetoothSco() e asincron (doar initiaza
     * conectarea), deci starea reala "e gata de folosit microfonul cascii" vine abia din acest
     * broadcast, nu instant dupa apelul de start. MicUplinkPlayer citeste acest flag ca sa
     * decida daca tinteste SCO sau cade pe microfonul intern.
     */
    private void inregistreazaReceiverSco() {
        android.content.IntentFilter filter =
                new android.content.IntentFilter(android.media.AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
        android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context ctx, android.content.Intent intent) {
                int state = intent.getIntExtra(android.media.AudioManager.EXTRA_SCO_AUDIO_STATE,
                        android.media.AudioManager.SCO_AUDIO_STATE_ERROR);
                boolean conectat = state == android.media.AudioManager.SCO_AUDIO_STATE_CONNECTED;
                com.example.yaesucatcontroladv.deepcw.audio.BluetoothScoState.INSTANCE.setConnected(conectat);
                android.util.Log.i("EcranComenzi", "SCO audio state=" + state + " conectat=" + conectat);

                // Stiva BT/HFP poate opri singura legatura SCO dupa o perioada de inactivitate
                // (observat empiric ~50-70s intre PTT-uri) - daca suntem inca in mod voce si o
                // voiam conectata, o reluam dupa un scurt delay, ca sa nu blocam stiva BT.
                if (state == android.media.AudioManager.SCO_AUDIO_STATE_DISCONNECTED && modVoceCurent) {
                    trateazaCascaDisparuta("SCO audio");
                } else if (state == android.media.AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                    handlerReconnectSco.removeCallbacks(runnableReconnectSco);
                    reevalueazaCapturaPeBt();
                }
            }
        };
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
    }

    /**
     * Asculta schimbarea starii profilului HEADSET (conectat/deconectat la nivel Bluetooth,
     * nu doar la nivelul legaturii audio SCO). ACTION_SCO_AUDIO_STATE_UPDATED nu se declanseaza
     * intotdeauna cand casca dispare fizic - daca nu exista niciun AudioRecord/AudioTrack activ
     * in acel moment (ex. captura era "calda" dar PTT nu era apasat), oprirea castii poate trece
     * neobservata, iar MicUplinkPlayer ramane convins ca tinteste inca BT SCO pana la restart de
     * aplicatie. Acest broadcast e legat de starea reala a profilului Bluetooth, deci mult mai
     * fiabil pentru a detecta disparitia castii.
     */
    private void inregistreazaReceiverProfilHeadset() {
        android.content.IntentFilter filter =
                new android.content.IntentFilter(android.bluetooth.BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context ctx, android.content.Intent intent) {
                int state = intent.getIntExtra(android.bluetooth.BluetoothProfile.EXTRA_STATE,
                        android.bluetooth.BluetoothProfile.STATE_DISCONNECTED);
                android.util.Log.i("EcranComenzi", "Profil HEADSET state=" + state);
                if (state == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                    trateazaCascaDisparuta("profil HEADSET");
                } else if (state == android.bluetooth.BluetoothProfile.STATE_CONNECTED
                        && modVoceCurent && cascaBtActivata) {
                    handlerReconnectSco.removeCallbacks(runnableReconnectSco);
                    // NU chemam reevalueazaCapturaPeBt() aici - porneteScoDacaExistaCasca() de
                    // mai jos doar INITIAZA conectarea SCO (asincrona); BluetoothScoState.connected
                    // e inca fals in acest moment, deci am cadea pe microfonul telefonului si am
                    // ramane blocati acolo (fara captureOwnedByTransmit, care se seteaza doar din
                    // startTransmit()). Asteptam confirmarea reala prin SCO_AUDIO_STATE_CONNECTED
                    // (vezi inregistreazaReceiverSco), acolo unde BluetoothScoState.connected e
                    // deja actualizat corect inainte de reevaluare.
                    porneteScoDacaExistaCasca();
                }
            }
        };
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
    }

    /** Reactie comuna la disparitia castii BT, indiferent daca a fost detectata prin
     * ACTION_SCO_AUDIO_STATE_UPDATED sau prin schimbarea starii profilului HEADSET (vezi
     * [inregistreazaReceiverProfilHeadset]). Idempotenta - nu are efect daca stiam deja ca SCO
     * nu mai e activ, ca sa nu reprogrameze inutil reconectarea de doua ori la fiecare disparitie. */
    private void trateazaCascaDisparuta(String motiv) {
        if (!scoActive) return;
        android.util.Log.i("EcranComenzi", "Casca BT disparuta (" + motiv + ") - reincerc conectarea peste 2s");
        scoActive = false;
        com.example.yaesucatcontroladv.deepcw.audio.BluetoothScoState.INSTANCE.setConnected(false);
        micUplink.stopCapture(); // sursa a disparut - nu mai are rost sa o tinem "calda"
        // stopCapture() de mai sus omoara si emisia curenta (daca era in curs), fara sa atinga
        // starea de UI a butonului PTT - fara asta statia ar ramane cheiata (aer mort) pana la
        // urmatoarea apasare manuala, desi microfonul e deja mort.
        if (pttActive) setPttActive(false);
        handlerReconnectSco.removeCallbacks(runnableReconnectSco);
        if (modVoceCurent) handlerReconnectSco.postDelayed(runnableReconnectSco, 2000);
    }

    /**
     * Apelata cand primim confirmarea (SCO audio sau profil HEADSET) ca legatura catre casca
     * e disponibila acum. Daca operatorul a apasat PTT INAINTE ca aceasta confirmare sa fi
     * ajuns (cat BluetoothScoState.connected era inca fals), MicUplinkPlayer.startCapture() a
     * cazut deja pe microfonul telefonului si a ramas "captureOwnedByTransmit" - iar un simplu
     * startCapture() de la confirmarea de mai sus ar intoarce ALREADY_RUNNING fara sa faca
     * nimic, ramanand blocat pe telefon pana la un ciclu PTT care nimereste din intamplare
     * fereastra buna (uneori zeci de secunde). O repornim explicit aici ca sa treaca pe casca
     * de indata ce stim ca e disponibila. Nu intrerupem o emisie in curs.
     */
    private void reevalueazaCapturaPeBt() {
        if (!modVoceCurent || !cascaBtActivata) return;
        if (!micUplink.isTransmitting() && micUplink.isCapturing() && !micUplink.getActiveMicIsBluetooth()) {
            micUplink.stopCapture();
        }
        if (!micUplink.isCapturing()) micUplink.startCapture();
        scuturaRutaUsbCuListen();
    }

    /**
     * Reproduce cu LISTEN real "scuturarea" descoperita empiric de operator (LISTEN ON 2-3s,
     * apoi OFF, dupa care urmatoarea emisie pe casca BT ajunge efectiv la statie, in loc sa
     * ramana pe sidetone). O incercare anterioara de a reproduce efectul cu un AudioRecord
     * minimal de test (fara LISTEN real) NU a avut acelasi efect empiric - probabil conteaza
     * rata/sursa aleasa de AudioRecorder sau faptul ca LISTEN chiar citeste date, nu doar
     * deschide streamul (vezi istoricul din MicUplinkPlayer.kt). Nu facem nimic daca operatorul
     * are deja LISTEN pornit (ruta e deja "calda" prin folosire reala) sau daca PTT e activ chiar
     * acum (nu are rost sa perturbam o emisie in curs, si LISTEN oricum nu porneste peste TX -
     * vezi setPttActive). Blocant ~2s pe thread-ul apelant.
     */
    private void scuturaRutaUsbCuListen() {
        if (listenActive || pttActive) return;
        AudioListenPlayer.StartResult r = listenPlayer.start();
        if (r == AudioListenPlayer.StartResult.OK) {
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            listenPlayer.stop();
        }
    }

    /**
     * Porneste/opreste legatura Bluetooth SCO odata cu intrarea/iesirea din modul voce.
     * Best-effort: daca nu exista nicio casca HFP conectata, startBluetoothSco() pur si
     * simplu nu are efect - MicUplinkPlayer verifica separat, la fiecare pornire de PTT,
     * starea reala din BluetoothScoState si cade automat pe microfonul intern daca SCO nu
     * s-a conectat. Nu incearca nimic daca operatorul a dezactivat manual casca BT din
     * panoul de setari PTT (vezi [cascaBtActivata], [aplicaSetareCascaBt]).
     */
    private void actualizeazaBluetoothScoPentruMod(boolean esteVoce) {
        if (esteVoce) {
            if (!cascaBtActivata) return;
            if (scoActive) return;
            if (android.os.Build.VERSION.SDK_INT >= 31 &&
                    androidx.core.content.ContextCompat.checkSelfPermission(context,
                            android.Manifest.permission.BLUETOOTH_CONNECT)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                // Cererea e asincrona - rezultatul vine in MainActivity.onRequestPermissionsResult
                // (cod 125), care trebuie sa apeleze inapoi pornesteScoDupaPermisiune().
                androidx.core.app.ActivityCompat.requestPermissions((android.app.Activity) context,
                        new String[]{android.Manifest.permission.BLUETOOTH_CONNECT}, 125);
                return;
            }
            porneteScoDacaExistaCasca();
            return;
        }

        opresteScoCompletDacaActiv();
    }

    /** Opreste complet legatura SCO si revine la MODE_NORMAL, daca era activa. Extras din
     * [actualizeazaBluetoothScoPentruMod] ca sa fie reutilizabil si de [aplicaSetareCascaBt]
     * cand operatorul dezactiveaza manual casca BT in timp ce e deja in mod voce. */
    private void opresteScoCompletDacaActiv() {
        android.media.AudioManager audioManager =
                (android.media.AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null || !scoActive) return;
        try {
            audioManager.setBluetoothScoOn(false);
            audioManager.stopBluetoothSco();
            audioManager.setMode(android.media.AudioManager.MODE_NORMAL);
        } catch (Exception e) {
            android.util.Log.e("EcranComenzi", "Eroare oprire Bluetooth SCO", e);
        } finally {
            scoActive = false;
            com.example.yaesucatcontroladv.deepcw.audio.BluetoothScoState.INSTANCE.setConnected(false);
            micUplink.stopCapture();
        }
    }

    /**
     * Aplica manual alegerea sursei de microfon (toggle-ul MIC: CASCA BT / MIC: TELEFON din
     * panoul de setari PTT) - apelata la schimbarea toggle-ului. Comutarea pe CASCA BT
     * forteaza o reevaluare curata de la zero (scoActive=false inainte de a reincerca), deci
     * serveste si ca resetare manuala daca detectia auto ar ramane vreodata desincronizata -
     * alternativa mai rapida la restartul complet al aplicatiei.
     */
    private void aplicaSetareCascaBt() {
        if (!modVoceCurent) return;
        handlerReconnectSco.removeCallbacks(runnableReconnectSco);
        if (cascaBtActivata) {
            if (android.os.Build.VERSION.SDK_INT >= 31 &&
                    androidx.core.content.ContextCompat.checkSelfPermission(context,
                            android.Manifest.permission.BLUETOOTH_CONNECT)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions((android.app.Activity) context,
                        new String[]{android.Manifest.permission.BLUETOOTH_CONNECT}, 125);
                return;
            }
            scoActive = false;
            porneteScoDacaExistaCasca();
            // NU facem aici primeUsbRoute() - incercat, dar deschiderea unui AudioRecord pe USB
            // chiar cat SCO negociaza inca pe fundal (imediat dupa startBluetoothSco()) pare sa
            // perturbe/saboteze negocierea insasi (aceeasi fragilitate observata cu orice
            // schimbare concurenta de stream in timpul MODE_IN_COMMUNICATION) - dupa acest
            // experiment, toggle-ul repetat nu mai producea deloc semnal la statie. Scuturarea
            // ramane doar in MicUplinkPlayer.startCapture(), care ruleaza abia DUPA ce SCO s-a
            // confirmat efectiv conectat (broadcast), nu concurent cu negocierea.
        } else {
            if (pttActive) setPttActive(false); // siguranta - nu ramanem cu emisia agatata de o sursa tocmai dezactivata
            opresteScoCompletDacaActiv();
        }
    }

    /** Reia pornirea SCO dupa ce utilizatorul a acordat BLUETOOTH_CONNECT (apelat din
     * MainActivity.onRequestPermissionsResult, cod 125). Fara asta, SCO nu pornea niciodata
     * dupa primul dialog de permisiune - trebuia sa iesi si sa reintri in LSB/USB manual. */
    public void porneteScoDupaPermisiune() {
        if (modVoceCurent && cascaBtActivata) porneteScoDacaExistaCasca();
    }

    /**
     * Porneste SCO (si implicit MODE_IN_COMMUNICATION, vezi porneteScoInterna) doar daca
     * exista efectiv o casca Bluetooth HFP conectata acum - altfel ramanem in MODE_NORMAL.
     * MODE_IN_COMMUNICATION activeaza procesarea audio de "apel" a sistemului (AGC/noise
     * suppression/echo cancellation) chiar si pe microfonul intern, care s-a dovedit ca
     * suprima microfonul dupa 1-2s cand nu exista de fapt niciun apel real de arbitrat
     * (semnalul aparea scurt, apoi disparea complet - fara nicio casca conectata).
     */
    private void porneteScoDacaExistaCasca() {
        if (!existaCascaBluetoothConectata()) return;
        porneteScoInterna();
    }

    /** Apelata de [runnableReconnectSco] la fiecare 2s. Spre deosebire de
     * [porneteScoDacaExistaCasca], daca nu exista inca o casca conectata nu renunta - se
     * re-programeaza singura peste inca 2s, cat timp suntem in mod voce, ca sa astepte revenirea
     * castii (fara asta bucla s-ar opri definitiv la prima verificare esuata). */
    private void reincearcaSco() {
        if (!modVoceCurent || !cascaBtActivata) return;
        if (existaCascaBluetoothConectata()) {
            porneteScoInterna();
        } else {
            handlerReconnectSco.postDelayed(runnableReconnectSco, 2000);
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private boolean existaCascaBluetoothConectata() {
        android.bluetooth.BluetoothAdapter adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return false;
        try {
            return adapter.getProfileConnectionState(android.bluetooth.BluetoothProfile.HEADSET)
                    == android.bluetooth.BluetoothProfile.STATE_CONNECTED;
        } catch (SecurityException e) {
            return false;
        }
    }

    private void porneteScoInterna() {
        android.media.AudioManager audioManager =
                (android.media.AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null || scoActive) return;
        try {
            // Esential pentru ca politica audio sa ruteze fluxurile VOICE_COMMUNICATION spre
            // SCO - fara asta legatura SCO poate fi "conectata" la nivel Bluetooth dar
            // AudioRecord/AudioTrack tot merg pe traseul implicit.
            audioManager.setMode(android.media.AudioManager.MODE_IN_COMMUNICATION);
            audioManager.startBluetoothSco();
            audioManager.setBluetoothScoOn(true);
            scoActive = true;
        } catch (Exception e) {
            android.util.Log.e("EcranComenzi", "Eroare pornire Bluetooth SCO", e);
        }
    }

    private void reseteazaTemporizatorSetariPtt() {
        handlerPttSettings2.removeCallbacks(runnableAscundeSetariPtt2);
        handlerPttSettings2.postDelayed(runnableAscundeSetariPtt2, 3000);
    }

    private void setPttActive(boolean active) {
        if (pttActive == active) return;

        if (active) {
            // Eliberam intai USB-ul de la RX (daca Listen era pornit) - multe interfete audio
            // USB de statie nu sunt cu adevarat full-duplex; IN si OUT simultan pe acelasi
            // port pot bloca reciproc traseele, iar emisia nu mai ajunge la statie.
            if (listenActive) {
                listenPlayer.stop();
            }
            // Pornim traseul audio (mic -> USB) - abia dupa ce e gata cheiem statia, ca sa nu
            // emitem aer mort in timp ce se deschide captura microfonului.
            MicUplinkPlayer.StartResult r = micUplink.startTransmit();
            if (r != MicUplinkPlayer.StartResult.OK) {
                Toast.makeText(context, "PTT: emisie audio indisponibila (" + r + ")", Toast.LENGTH_SHORT).show();
                if (listenActive) listenPlayer.start(); // revenim la starea dinainte, TX a esuat
                return;
            }
        }

        pttActive = active;

        UsbSerialPort port = radioManager != null ? radioManager.getDataPort() : null;
        if (port != null && port.isOpen()) {
            try {
                if (pttUseRts) port.setRTS(active); else port.setDTR(active);
            } catch (IOException e) {
                android.util.Log.e("EcranComenzi", "Err setting PTT (" + (pttUseRts ? "RTS" : "DTR") + ")", e);
            }
        }

        if (!active) {
            micUplink.stopTransmit();
            if (listenActive) {
                listenPlayer.start(); // reluam ascultarea dupa ce am eliberat traseul de TX
            }
        }

        btnPtt.setBackgroundColor(Color.parseColor(active ? "#D32F2F" : "#757575"));
        if (active) {
            btnPtt.setText(micUplink.getActiveMicIsBluetooth() ? "TX (MIC CASCA BT)" : "TX (MIC TELEFON)");
        } else {
            btnPtt.setText("PTT");
        }
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
                content = EcranRttyDroid.inlocuiestePlaceholderIndicativ(content);
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

        if (listenPlayer != null && listenPlayer.setRadioMode(codMod)) {
            // Modul nou nu are model de denoise dedicat - a fost oprit automat pe bypass.
            syncListenDenoiseButtonUi(false);
            salveazaSetareListen(PREF_LISTEN_DENOISE, 0);
        }

        final boolean esteVoce = codMod.startsWith("01") || codMod.startsWith("02");
        modVoceCurent = esteVoce;
        if (btnPtt != null) {
            if (!esteVoce && pttActive) setPttActive(false); // siguranta: nu ramane blocat in TX
            btnPtt.setVisibility(esteVoce ? View.VISIBLE : View.GONE);
        }
        actualizeazaBluetoothScoPentruMod(esteVoce);

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

        // Daca parasim modul CW, iesim si din antrenament (paletele/UI-ul lui dispar oricum).
        if (!esteCW && modAntrenamentCw) {
            toggleModAntrenamentCw();
        }

        // Activăm ieșirea hardware a Keyer-ului DOAR dacă suntem în CW ȘI nu suntem în antrenament.
        if (cwKeyer != null) {
            cwKeyer.setHardwareOutputEnabled(esteCW && !modAntrenamentCw);
        }
    }

    /**
     * Comuta modul antrenament CW (dublu-tap pe BK-IN): cand e activ, paletele nu mai
     * manipuleaza linia RTS/DTR catre statie - genereaza doar un ton audibil (sidetone) in
     * difuzorul telefonului sau in casca Bluetooth conectata (rutare automata Android, nu e
     * nevoie de SCO fiindca nu captam microfonul). Cat timp e activ, paletele de pe ecran sunt
     * rutate spre CwTrainingKeyer (keyer separat, temporizat de fluxul audio); CwKeyer ramane
     * neatins. Setarile (WPM, REV, IAMBIC A/B) sunt aplicate ambelor keyere;
     * TON (frecventa sunetului) doar celui de antrenament.
     */
    private void toggleModAntrenamentCw() {
        modAntrenamentCw = !modAntrenamentCw;
        if (modAntrenamentCw) {
            cwKeyer.setLeftPaddle(false);
            cwKeyer.setRightPaddle(false);
            cwKeyer.setHardwareOutputEnabled(false);
            trainingKeyer.start();
            btnBkInToggle.setText("TRAINING");
            btnBkInToggle.setBackgroundColor(Color.parseColor("#6A1B9A"));
            Toast.makeText(context, "CW TRAINING MODE ON - paddles no longer key the radio", Toast.LENGTH_SHORT).show();
        } else {
            trainingKeyer.stop();
            cwKeyer.setHardwareOutputEnabled(modLucruCurent == 2);
            btnBkInToggle.setText(stareBkInOn ? "BK-IN: ON" : "BK-IN: OFF");
            btnBkInToggle.setBackgroundColor(Color.parseColor(stareBkInOn ? "#2E7D32" : "#757575"));
            Toast.makeText(context, "CW TRAINING MODE OFF", Toast.LENGTH_SHORT).show();
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
        int ton = prefs.getInt(PREF_CW_TON, 600);
        boolean iambicB = prefs.getBoolean(PREF_CW_IAMBIC_B, true);
        boolean rts = prefs.getBoolean(PREF_CW_RTS, true);

        cwKeyer.setWpm(wpm);
        cwKeyer.setReversed(rev);
        // Cheia dreapta (STR) a fost scoasa din UI - fortam iambic, ca o setare STR salvata
        // anterior sa nu ramana activa fara buton de oprire.
        cwKeyer.setStraightKeyMode(false);
        cwKeyer.setIambicModeB(iambicB);
        cwKeyer.setUseRts(rts);

        trainingKeyer.setWpm(wpm);
        trainingKeyer.setReversed(rev);
        trainingKeyer.setToneHz(ton);
        trainingKeyer.setIambicModeB(iambicB);

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
                        } else if (child instanceof Button) {
                            Button b = (Button) child;
                            if (b.getText().toString().startsWith("TON:")) {
                                b.setText("TON: " + ton + "Hz");
                            } else if (b.getText().toString().contains("IAMBIC")) {
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
            if (modAntrenamentCw) toggleModAntrenamentCw();
            if (cwKeyer != null) cwKeyer.setHardwareOutputEnabled(false);
            if (listenActive) stopListen();
        }
    }

    public void eliberareResurse() {
        if (cwKeyer != null) {
            cwKeyer.shutdown();
        }
        trainingKeyer.stop();
        if (listenPlayer != null) {
            listenPlayer.stop();
        }
        if (micUplink != null) {
            micUplink.stopCapture(); // opreste si emisia, daca era activa
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
        pttToggleMode = prefs.getBoolean(PREF_PTT_TOGGLE_MODE, false);
        pttUseRts = prefs.getBoolean(PREF_PTT_USE_RTS, true);
        cascaBtActivata = prefs.getBoolean(PREF_CASCA_BT_ACTIVATA, true);
        if (cbPttToggleMode != null) cbPttToggleMode.setChecked(pttToggleMode);
        if (btnPttLineToggle != null) btnPttLineToggle.setText(pttUseRts ? "USE: RTS" : "USE: DTR");
        if (btnCascaBtToggle != null) {
            btnCascaBtToggle.setText(cascaBtActivata ? "MIC: CASCA BT" : "MIC: TELEFON");
        }

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
