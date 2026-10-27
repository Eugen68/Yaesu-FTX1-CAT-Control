package com.example.yaesucatcontroladv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;

import com.example.yaesucatcontroladv.deepcw.audio.MorseAudioProcessor;
import com.example.yaesucatcontroladv.deepcw.audio.DecoderMode;
import com.example.yaesucatcontroladv.deepcw.audio.StreamingMethod;
import com.example.yaesucatcontroladv.deepcw.data.PileupTrack;
import com.example.yaesucatcontroladv.deepcw.dsp.Constants;
import com.example.yaesucatcontroladv.deepcw.dsp.ToneCandidate;
import com.example.yaesucatcontroladv.deepcw.dsp.ToneDetector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public class EcranCwDeepDecoder {
    private final Context context;
    private final RadioManager radioManager;
    private LinearLayout viewPrincipal;

    private CwWaterfall waterfall;
    private TextView tvStatus, tvFreqInfo, tvLevelVal, tvGainVal;
    private ScrollView svTerminal;
    private LinearLayout llMessagesContainer, llDenoiseControls;
    private Button btnToggle, btnModeToggle, btnDetectTone, btnDenoiseToggle, btnStreamMethod;

    /** id statie (track) -> frecventa ei in Hz, pentru modul PILEUP (actualizat pe thread-ul UI). */
    private Map<Integer, Integer> frecventaPeStatie = new java.util.HashMap<>();

    // Ultimul Hz pe care "Detecteaza tonul" a retunat - apasat din nou fara alta actiune
    // intre timp, trece la urmatorul candidat gasit (dupa putere), nu re-gaseste mereu pe
    // acelasi. -1 = inca nicio detectie in aceasta sesiune. Vezi detecteazaTonul().
    private int ultimulTonDetectatHz = -1;
    private long ultimaDetectareLaMs = 0L;

    private static final int TONE_SEARCH_MIN_HZ = 200;
    private static final int TONE_MIN_AUDIO_SAMPLES = Constants.SAMPLE_RATE; // 1 s
    private static final long TONE_CYCLE_WINDOW_MS = 4000L;
    private SeekBar sbLevel, sbGain;
    private CheckBox cbLockDenoiseControls;
    private Spinner spWidth;

    private final CallsignDetector callsignDetector;
    private MorseAudioProcessor processor;
    private boolean isRunning = false;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Handler statusRevertHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusRevertRunnable = this::revertStatusLabel;

    // Logică Auto-Hide și Long-Press pentru panoul de denoise
    private final Handler panelHandler = new Handler(Looper.getMainLooper());
    private final Runnable hidePanelRunnable = () -> {
        if (llDenoiseControls != null) llDenoiseControls.setVisibility(View.GONE);
    };

    private final Handler longPressHandler = new Handler(Looper.getMainLooper());
    private Runnable longPressRunnable;
    private boolean longPressTriggered = false;

    private static final String PREF_CW_AI_MODE = "cw_ai_mode";
    private static final String PREF_CW_AI_WIDTH = "cw_ai_width";
    private static final String PREF_CW_AI_DENOISE = "cw_ai_denoise";
    private static final String PREF_CW_AI_WET = "cw_ai_wet";
    private static final String PREF_CW_AI_GAIN = "cw_ai_gain";
    private static final String PREF_CW_AI_LOCK_CONTROLS = "cw_ai_lock_controls";
    private static final String PREF_CW_AI_STREAM_METHOD = "cw_ai_stream_method";

    /** Starea vizuala a butonului DENOISE (optimista la click, corectata de availabilityListener). */
    private boolean denoiseUiOn = false;

    public EcranCwDeepDecoder(Context context, RadioManager radioManager) {
        this.context = context;
        this.radioManager = radioManager;
        this.processor = new MorseAudioProcessor(context);
        this.callsignDetector = new CallsignDetector(context, null, null, null);
        construiesteInterfata();
        configurareObservatori();
        incarcaSetari();

        // Inregistrare pentru exclusivitate reciproca cu modul LISTEN (vezi Plan 2)
        AudioCaptureCoordinator.registerCwDecoder(() -> {
            if (isRunning) {
                uiHandler.post(this::stopDecoder);
            }
        });
    }

    private void construiesteInterfata() {
        viewPrincipal = new LinearLayout(context);
        viewPrincipal.setOrientation(LinearLayout.VERTICAL);
        viewPrincipal.setBackgroundColor(Color.parseColor("#121212"));
        viewPrincipal.setPadding(20, 10, 20, 10);

        tvStatus = new TextView(context);
        tvStatus.setText("CW AI DECODER - READY");
        tvStatus.setTextColor(Color.parseColor("#00E5FF"));
        tvStatus.setTextSize(14);
        tvStatus.setTypeface(null, Typeface.BOLD);
        tvStatus.setGravity(Gravity.CENTER);
        viewPrincipal.addView(tvStatus);

        tvFreqInfo = new TextView(context);
        tvFreqInfo.setText("Tune: 800 Hz");
        tvFreqInfo.setTextColor(Color.GRAY);
        tvFreqInfo.setTextSize(12);
        tvFreqInfo.setGravity(Gravity.CENTER);
        tvFreqInfo.setPadding(0, 0, 0, 10);
        viewPrincipal.addView(tvFreqInfo);

        waterfall = new CwWaterfall(context, Constants.SAMPLE_RATE);
        LinearLayout.LayoutParams pWater = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 300);
        pWater.setMargins(0, 0, 0, 10);
        waterfall.setLayoutParams(pWater);
        waterfall.setOnFrequencyTappedListener(freq -> {
            processor.setFilterFreq(freq);
            tvFreqInfo.setText("Tune: " + freq + " Hz");
            waterfall.setFilterBand(freq, processor.getFilterWidthHz());
        });
        viewPrincipal.addView(waterfall);

        // Label pentru Terminal
        TextView tvLabelTerm = new TextView(context);
        tvLabelTerm.setText("CW TRANSCRIPT / PILEUP LIST:");
        tvLabelTerm.setTextColor(Color.GRAY);
        tvLabelTerm.setTextSize(10);
        tvLabelTerm.setPadding(5, 5, 0, 5);
        viewPrincipal.addView(tvLabelTerm);

        // Terminal / Pileup List
        svTerminal = new ScrollView(context);
        LinearLayout.LayoutParams pSv = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        svTerminal.setLayoutParams(pSv);
        svTerminal.setBackgroundColor(Color.BLACK);
        
        llMessagesContainer = new LinearLayout(context);
        llMessagesContainer.setOrientation(LinearLayout.VERTICAL);
        llMessagesContainer.setPadding(15, 15, 15, 15);
        
        // Text de stare inițial
        TextView tvPlaceholder = new TextView(context);
        tvPlaceholder.setText("Terminal ready. Press START to begin decoding...");
        tvPlaceholder.setTextColor(Color.DKGRAY);
        tvPlaceholder.setTextSize(14);
        llMessagesContainer.addView(tvPlaceholder);

        svTerminal.addView(llMessagesContainer);
        viewPrincipal.addView(svTerminal);

        // Row 1: Mode Toggle & Width
        LinearLayout rControls = new LinearLayout(context);
        rControls.setOrientation(LinearLayout.HORIZONTAL);
        rControls.setGravity(Gravity.CENTER_VERTICAL);
        rControls.setPadding(0, 10, 0, 10);

        btnModeToggle = new Button(context);
        btnModeToggle.setText("MODE: NORMAL");
        btnModeToggle.setBackgroundColor(Color.parseColor("#37474F"));
        btnModeToggle.setTextColor(Color.WHITE);
        btnModeToggle.setTextSize(11);
        btnModeToggle.setLayoutParams(new LinearLayout.LayoutParams(0, 110, 1.2f));
        btnModeToggle.setOnClickListener(v -> toggleMode());
        // NORMAL: o statie, tunata manual. PILEUP: pana la 5 statii gasite automat in banda,
        // fiecare decodata separat (model_en_narrow_2026-10).
        rControls.addView(btnModeToggle);

        View spacerModeToggle = new View(context);
        spacerModeToggle.setLayoutParams(new LinearLayout.LayoutParams(15, 1));
        rControls.addView(spacerModeToggle);

        btnDetectTone = new Button(context);
        btnDetectTone.setText("DETECTEAZĂ TONUL");
        btnDetectTone.setBackgroundColor(Color.parseColor("#37474F"));
        btnDetectTone.setTextColor(Color.WHITE);
        btnDetectTone.setTextSize(11);
        btnDetectTone.setLayoutParams(new LinearLayout.LayoutParams(0, 110, 1.2f));
        btnDetectTone.setOnClickListener(v -> detecteazaTonul());
        rControls.addView(btnDetectTone);

        View spacerMode = new View(context);
        spacerMode.setLayoutParams(new LinearLayout.LayoutParams(15, 1));
        rControls.addView(spacerMode);

        btnDenoiseToggle = new Button(context);
        // Subliniat = are setari ascunse la apasare lunga (flag pe Paint, nu in text,
        // ca sa ramana si cand textul butonului se schimba).
        btnDenoiseToggle.setPaintFlags(btnDenoiseToggle.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        btnDenoiseToggle.setText("DENOISE: OFF");
        btnDenoiseToggle.setBackgroundColor(Color.parseColor("#37474F"));
        btnDenoiseToggle.setTextColor(Color.WHITE);
        btnDenoiseToggle.setTextSize(11);
        btnDenoiseToggle.setLayoutParams(new LinearLayout.LayoutParams(0, 110, 1.2f));
        btnDenoiseToggle.setOnClickListener(v -> toggleDenoise());
        rControls.addView(btnDenoiseToggle);

        View spacer = new View(context);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(20, 1));
        rControls.addView(spacer);

        spWidth = new Spinner(context);
        List<String> widths = new ArrayList<>();
        for (int w : Constants.INSTANCE.getFILTER_WIDTH_OPTIONS()) widths.add(w + " Hz");
        // Spinner-ul arata ca butoanele de pe rand: gri, text alb de 11 sp, "BW: 800 Hz ▾".
        // Fundalul gri inlocuieste fundalul standard, care continea si sageata listei - de
        // aceea "▾" e pus in text. Lista care se deschide ramane la marimea implicita, ca sa
        // fie usor de atins.
        ArrayAdapter<String> adapterW = new ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, widths) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                if (v instanceof TextView) {
                    TextView tv = (TextView) v;
                    tv.setText("BW: " + getItem(position) + " ▾");
                    tv.setTextSize(11);
                    tv.setTextColor(Color.WHITE);
                    tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                    tv.setGravity(Gravity.CENTER);
                }
                return v;
            }
        };
        adapterW.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spWidth.setAdapter(adapterW);
        spWidth.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int width = Constants.INSTANCE.getFILTER_WIDTH_OPTIONS()[position];
                processor.setFilterWidth(width);
                waterfall.setFilterBand(processor.getFilterFreqHz(), width);
                salveazaSetare(PREF_CW_AI_WIDTH, position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        // Aceeasi inaltime ca butoanele de pe rand (110 px) - implicit Spinner-ul are minim
        // 48 dp si iesea mai inalt.
        spWidth.setBackgroundColor(Color.parseColor("#37474F"));
        spWidth.setMinimumHeight(0);
        spWidth.setPadding(20, 0, 20, 0);
        rControls.addView(spWidth, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, 110));
        viewPrincipal.addView(rControls);

        // --- PANOU CONTROALE DENOISE (PLAN 3) ---
        llDenoiseControls = new LinearLayout(context);
        llDenoiseControls.setOrientation(LinearLayout.VERTICAL);
        llDenoiseControls.setBackgroundColor(Color.parseColor("#263238"));
        llDenoiseControls.setPadding(30, 20, 30, 20);
        llDenoiseControls.setVisibility(View.GONE);
        LinearLayout.LayoutParams pDenoisePanel = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pDenoisePanel.setMargins(0, 0, 0, 10);
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
                processor.setWetLevel(wet);
                if (fromUser) {
                    salveazaSetare(PREF_CW_AI_WET, progress);
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
                processor.setOutputGain(gain);
                if (fromUser) {
                    salveazaSetare(PREF_CW_AI_GAIN, progress);
                    resetAutoHideTimer();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        llDenoiseControls.addView(sbGain);

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
            salveazaSetare(PREF_CW_AI_LOCK_CONTROLS, isChecked ? 1 : 0);
        });
        llDenoiseControls.addView(cbLockDenoiseControls);

        viewPrincipal.addView(llDenoiseControls);

        // Row 2: Start/Stop & Clear
        LinearLayout rButtons = new LinearLayout(context);
        rButtons.setOrientation(LinearLayout.HORIZONTAL);
        
        btnToggle = new Button(context);
        btnToggle.setText("START AI DECODER");
        btnToggle.setBackgroundColor(Color.parseColor("#E65100"));
        btnToggle.setTextColor(Color.WHITE);
        btnToggle.setTextSize(11);
        btnToggle.setSingleLine(true);
        btnToggle.setOnClickListener(v -> toggleDecoder());
        rButtons.addView(btnToggle, new LinearLayout.LayoutParams(0, 120, 0.8f));

        Button btnClear = new Button(context);
        btnClear.setText("CLEAR");
        btnClear.setBackgroundColor(Color.parseColor("#424242"));
        btnClear.setTextColor(Color.WHITE);
        btnClear.setTextSize(11);
        btnClear.setSingleLine(true);
        btnClear.setOnClickListener(v -> clearTerminal());
        rButtons.addView(btnClear, new LinearLayout.LayoutParams(0, 120, 0.4f));

        // Metoda de confirmare a textului in modul NORMAL (vezi StreamingMethod): pauze de
        // cuvant (implicit) sau ferestre suprapuse - pentru comparatie pe banda.
        btnStreamMethod = new Button(context);
        btnStreamMethod.setBackgroundColor(Color.parseColor("#37474F"));
        btnStreamMethod.setTextColor(Color.WHITE);
        btnStreamMethod.setTextSize(11);
        // "STREAM: FERESTRE" pe un singur rand - de aceea butonul e la fel de lat ca START.
        btnStreamMethod.setSingleLine(true);
        btnStreamMethod.setOnClickListener(v -> toggleStreamingMethod());
        rButtons.addView(btnStreamMethod, new LinearLayout.LayoutParams(0, 120, 0.8f));
        syncStreamMethodButtonUi(StreamingMethod.WORD_GAP);

        viewPrincipal.addView(rButtons);

        // Row 3: CW Messages (M1-M5)
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
        viewPrincipal.addView(rCw);
    }

    private void configurareObservatori() {
        processor.setSpectrumListener(row -> uiHandler.post(() -> waterfall.pushMagnitudeRow(row)));

        processor.setWaterfallRangeListener((min, max) -> uiHandler.post(() -> waterfall.setDisplayFrequencyRange(min, max)));

        processor.setModeListener(mode -> uiHandler.post(() -> sincronizeazaUiMod(mode)));

        processor.setStreamingListener((segments, pending) -> uiHandler.post(() -> updateStreamingDisplay(segments, pending)));

        processor.setPileupListener(new MorseAudioProcessor.PileupListener() {
            @Override
            public void onTracksUpdated(List<PileupTrack> tracks) {
                List<Integer> freqs = tracks.stream().map(PileupTrack::getFrequency).collect(Collectors.toList());
                Map<Integer, Integer> frecvente = new java.util.HashMap<>();
                for (PileupTrack t : tracks) frecvente.put(t.getId(), t.getFrequency());
                uiHandler.post(() -> {
                    frecventaPeStatie = frecvente;
                    waterfall.setTrackFrequencies(freqs);
                });
            }

            @Override
            public void onTextsUpdated(Map<Integer, String> texts) {
                // Textele vin indexate dupa id-ul statiei (track id), nu dupa frecventa -
                // atat lista cat si waterfall-ul au nevoie de frecventa.
                uiHandler.post(() -> {
                    Map<Integer, String> peFrecventa = new java.util.TreeMap<>();
                    for (Map.Entry<Integer, String> e : texts.entrySet()) {
                        Integer freq = frecventaPeStatie.get(e.getKey());
                        if (freq != null) peFrecventa.put(freq, e.getValue());
                    }
                    waterfall.setTrackTexts(peFrecventa);
                    updatePileupList(peFrecventa);
                });
            }
        });

        processor.setAvailabilityListener(avail -> uiHandler.post(() -> {
            if (!avail.getPileupAvailable() && processor.getMode() == DecoderMode.PILEUP) {
                toggleMode(); // Fallback to normal
            }
            if (!avail.getDenoiseAvailable() && denoiseUiOn) {
                // Init-ul nativ a esuat asincron (dupa apasarea toggle-ului sau la pornirea
                // decodorului cu setarea incarcata din prefs). Processor-ul a revenit deja pe
                // denoiseEnabled=false, deci butonul si pref-ul trebuie sincronizate cu el.
                syncDenoiseButtonUi(false);
                salveazaSetare(PREF_CW_AI_DENOISE, 0);
                Toast.makeText(context, "Filtrul de reducere a zgomotului nu este disponibil pe acest device", Toast.LENGTH_LONG).show();
            }
        }));

        processor.setErrorListener(message -> uiHandler.post(() -> {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
            tvStatus.setText("ERROR: " + message);
            tvStatus.setTextColor(Color.RED);
            statusRevertHandler.removeCallbacks(statusRevertRunnable);
            stopDecoder();
        }));

        processor.setStatusListener(msg -> uiHandler.post(() -> showTemporaryStatus(msg)));
    }

    private void showTemporaryStatus(String msg) {
        tvStatus.setText(msg);
        tvStatus.setTextColor(Color.YELLOW);
        statusRevertHandler.removeCallbacks(statusRevertRunnable);
        statusRevertHandler.postDelayed(statusRevertRunnable, 2000);
    }

    private void revertStatusLabel() {
        if (!isRunning) {
            tvStatus.setText("CW AI DECODER - READY");
            tvStatus.setTextColor(Color.parseColor("#00E5FF"));
        } else {
            tvStatus.setText("CW AI DECODER - RUNNING");
            tvStatus.setTextColor(Color.GREEN);
        }
    }

    private void sincronizeazaUiMod(DecoderMode mode) {
        btnModeToggle.setText("MODE: " + mode.name());
        boolean isNormal = (mode == DecoderMode.NORMAL);
        waterfall.setShowBand(isNormal);
        spWidth.setEnabled(isNormal);
        tvFreqInfo.setVisibility(isNormal ? View.VISIBLE : View.GONE);
        // In PILEUP statiile se gasesc singure si textul vine pe statie, nu pe streaming -
        // butoanele care regleaza doar decodarea NORMAL nu au efect acolo.
        for (View v : new View[]{btnDetectTone, btnStreamMethod}) {
            v.setEnabled(isNormal);
            v.setAlpha(isNormal ? 1f : 0.4f);
        }
        spWidth.setAlpha(isNormal ? 1f : 0.4f);
    }

    private void toggleMode() {
        DecoderMode next = (processor.getMode() == DecoderMode.NORMAL) ? DecoderMode.PILEUP : DecoderMode.NORMAL;
        
        if (next == DecoderMode.PILEUP && !processor.getAvailability().getPileupAvailable()) {
            Toast.makeText(context, "Modelul Pileup nu este valid! Se foloseste modul Normal.", Toast.LENGTH_LONG).show();
            return;
        }

        processor.setMode(next);
        salveazaSetare(PREF_CW_AI_MODE, next.ordinal());
    }

    /**
     * Detecteaza un ton in bufferul curent de audio si retuneaza pe el - inlocuitor rapid
     * pentru cautarea manuala pe waterfall. Port al DetectToneButton_Click din aplicatia
     * Windows sora, simplificat: acolo trebuie evitate benzile ALTOR canale active
     * (multi-banda); aici exista un singur canal tunat, deci nu e nevoie de acea logica de
     * coliziune. Apasat din nou in TONE_CYCLE_WINDOW_MS (fara alta acordare intre timp), trece
     * la urmatorul candidat gasit dupa putere, revenind la primul cand lista se epuizeaza -
     * vezi ultimulTonDetectatHz. O apasare mai tarzie porneste iar de la cel mai puternic ton.
     */
    private void detecteazaTonul() {
        if (processor.getMode() != DecoderMode.NORMAL) return;

        // Istoricul audio al procesorului, nu bufferul "pending": acela e golit la fiecare
        // cuvant confirmat si la fiecare reacordare, deci ar contine adesea doar o pauza.
        float[] snapshot = processor.snapshotRecentAudio();
        if (snapshot.length < TONE_MIN_AUDIO_SAMPLES) {
            Toast.makeText(context, "Nu am destul audio inca pentru detectare - mai asteapta putin.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Toata zona afisata pe waterfall (minus 100-200 Hz, unde stau armonicele retelei).
        List<ToneCandidate> candidati = ToneDetector.detectAllTonesHz(
                snapshot,
                (double) Constants.SAMPLE_RATE,
                (double) TONE_SEARCH_MIN_HZ,
                (double) Constants.MAX_FREQ_HZ,
                Constants.FFT_LENGTH,
                Constants.HOP_LENGTH);

        if (candidati.isEmpty()) {
            Toast.makeText(context, "Nu am gasit niciun ton clar - acordeaza manual cu tap pe waterfall.", Toast.LENGTH_SHORT).show();
            return;
        }

        long acum = SystemClock.elapsedRealtime();
        boolean continuaCiclul = ultimulTonDetectatHz >= 0
                && ultimulTonDetectatHz == processor.getFilterFreqHz()
                && acum - ultimaDetectareLaMs < TONE_CYCLE_WINDOW_MS;
        ultimaDetectareLaMs = acum;

        int indexAnterior = -1;
        if (continuaCiclul) {
            for (int i = 0; i < candidati.size(); i++) {
                if (Math.abs(candidati.get(i).getFreqHz() - ultimulTonDetectatHz) < 1.0) {
                    indexAnterior = i;
                    break;
                }
            }
        }
        ToneCandidate ales = indexAnterior >= 0
                ? candidati.get((indexAnterior + 1) % candidati.size())
                : candidati.get(0);
        int freqHz = Math.round(ales.getFreqHz());
        ultimulTonDetectatHz = freqHz;

        processor.setFilterFreq(freqHz);
        tvFreqInfo.setText("Tune: " + freqHz + " Hz");
        waterfall.setFilterBand(freqHz, processor.getFilterWidthHz());
        showTemporaryStatus("Ton detectat la " + freqHz + " Hz.");
    }

    private void toggleStreamingMethod() {
        StreamingMethod next = processor.getStreamingMethod() == StreamingMethod.WORD_GAP
                ? StreamingMethod.OVERLAP : StreamingMethod.WORD_GAP;
        aplicaStreamingMethod(next);
        showTemporaryStatus(next == StreamingMethod.OVERLAP
                ? "Text confirmat din ferestre suprapuse (12 s / 8 s)"
                : "Text confirmat la pauzele dintre cuvinte");
    }

    private void aplicaStreamingMethod(StreamingMethod method) {
        processor.useStreamingMethod(method);
        syncStreamMethodButtonUi(method);
        salveazaSetare(PREF_CW_AI_STREAM_METHOD, method.ordinal());
    }

    private void syncStreamMethodButtonUi(StreamingMethod method) {
        boolean overlap = method == StreamingMethod.OVERLAP;
        btnStreamMethod.setText(overlap ? "STREAM: FERESTRE" : "STREAM: PAUZE");
        btnStreamMethod.setBackgroundColor(overlap ? Color.parseColor("#2E7D32") : Color.parseColor("#37474F"));
    }

    private void toggleDenoise() {
        boolean next = !processor.getDenoiseEnabled();
        processor.setDenoiseEnabled(next);
        syncDenoiseButtonUi(next);
        salveazaSetare(PREF_CW_AI_DENOISE, next ? 1 : 0);

        if (next) {
            showDenoiseControlsPanel();
        } else {
            panelHandler.removeCallbacks(hidePanelRunnable);
            llDenoiseControls.setVisibility(View.GONE);
        }
    }

    private void syncDenoiseButtonUi(boolean on) {
        denoiseUiOn = on;
        btnDenoiseToggle.setText("DENOISE: " + (on ? "ON" : "OFF"));
        btnDenoiseToggle.setBackgroundColor(on ? Color.parseColor("#2E7D32") : Color.parseColor("#37474F"));
        configurareLongPressDenoise(on);
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void configurareLongPressDenoise(boolean on) {
        if (!on) {
            btnDenoiseToggle.setOnTouchListener(null);
            return;
        }

        btnDenoiseToggle.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    longPressTriggered = false;
                    longPressRunnable = () -> {
                        if (processor.getDenoiseEnabled() && llDenoiseControls.getVisibility() != View.VISIBLE) {
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

    private void clearTerminal() {
        llMessagesContainer.removeAllViews();
        waterfall.clearOverlays();
        if (processor != null) {
            processor.clearData();
        }
    }

    /**
     * Grupurile de apel (ex. "CQ") marcheaza de obicei inceputul unei transmisii noi -
     * inseram o linie noua inaintea lor, ca transcriptul sa se citeasca pe "apeluri"
     * separate in loc de un singur bloc continuu. Repetitiile consecutive ("CQ CQ CQ")
     * raman pe aceeasi linie - doar prima aparitie a unei serii declanseaza linia noua.
     */
    private static final java.util.Set<String> LINE_BREAK_TRIGGER_WORDS =
            java.util.Collections.singleton("CQ");

    private static String formatCallGroups(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        String[] words = text.split(" ");
        StringBuilder out = new StringBuilder(text.length() + 8);
        String prevWord = null;
        for (String w : words) {
            if (w.isEmpty()) continue;
            boolean isTrigger = LINE_BREAK_TRIGGER_WORDS.contains(w);
            boolean prevWasTrigger = prevWord != null && LINE_BREAK_TRIGGER_WORDS.contains(prevWord);
            if (prevWord != null) {
                out.append(isTrigger && !prevWasTrigger ? '\n' : ' ');
            }
            out.append(w);
            prevWord = w;
        }
        return out.toString();
    }

    private void updateStreamingDisplay(List<String> segments, String pending) {
        if (processor.getMode() != DecoderMode.NORMAL) return;

        // Log de diagnostic vizual discret (doar dacă nu avem nimic de afișat)
        if (segments.isEmpty() && (pending == null || pending.isEmpty())) {
            return;
        }

        llMessagesContainer.removeAllViews();

        TextView tv = new TextView(context);
        tv.setTextSize(18);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(Color.WHITE);
        tv.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());

        StringBuilder confirmedRawBuilder = new StringBuilder();
        for (String s : segments) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) continue;
            if (confirmedRawBuilder.length() > 0) confirmedRawBuilder.append(' ');
            confirmedRawBuilder.append(trimmed);
        }
        String confirmedRaw = confirmedRawBuilder.toString();
        // Reparam blocurile lipite (model CTC care "uita" sa prezica spatiul intre cuvinte)
        // INAINTE de formatCallGroups, ca sa recunoasca si CQ/DE/etc. ascunse in blob-uri.
        String confirmedRepaired = CallsignDetector.repareazaCuvinteLipite(confirmedRaw);
        String confirmedFormatted = formatCallGroups(confirmedRepaired);

        android.text.SpannableStringBuilder builder = new android.text.SpannableStringBuilder();
        builder.append(callsignDetector.proceseazaTextComplet(confirmedFormatted));

        tv.setText(builder);

        // Adăugăm textul pending (gri) folosind SpannableString pentru a evita ClassCastException
        if (pending != null && !pending.isEmpty()) {
            // Formatam pending impreuna cu coada textului confirmat, ca sa stim daca
            // primul cuvant din pending incepe o serie noua de CQ (deci merita linie noua).
            String fullRaw = confirmedRaw.isEmpty() ? pending.trim() : confirmedRaw + " " + pending.trim();
            String fullRepaired = CallsignDetector.repareazaCuvinteLipite(fullRaw);
            String fullFormatted = formatCallGroups(fullRepaired);
            String pendingFormatted = fullFormatted.length() >= confirmedFormatted.length()
                    ? fullFormatted.substring(confirmedFormatted.length())
                    : " " + pending;

            android.text.SpannableString spannablePending = new android.text.SpannableString(pendingFormatted);
            spannablePending.setSpan(new android.text.style.ForegroundColorSpan(Color.GRAY),
                    0, pendingFormatted.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            tv.append(spannablePending);
        }

        llMessagesContainer.addView(tv);
        svTerminal.post(() -> svTerminal.fullScroll(View.FOCUS_DOWN));
    }

    /** Textele statiilor din pileup, indexate dupa frecventa (Hz), crescator. */
    private void updatePileupList(Map<Integer, String> texts) {
        if (processor.getMode() != DecoderMode.PILEUP) return;
        llMessagesContainer.removeAllViews();
        for (Map.Entry<Integer, String> entry : texts.entrySet()) {
            TextView tv = new TextView(context);
            tv.setTextColor(Color.GREEN);
            tv.setTextSize(16);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());

            android.text.SpannableStringBuilder linie = new android.text.SpannableStringBuilder();
            linie.append(String.format(Locale.US, "[%d Hz]: ", entry.getKey()));
            linie.append(callsignDetector.proceseazaTextComplet(
                    CallsignDetector.repareazaCuvinteLipite(entry.getValue())));
            tv.setText(linie);

            llMessagesContainer.addView(tv);
        }
    }

    private void salveazaSetare(String key, int val) {
        context.getSharedPreferences("CW_AI_PREFS", Context.MODE_PRIVATE).edit().putInt(key, val).apply();
    }

    private void incarcaSetari() {
        android.content.SharedPreferences prefs = context.getSharedPreferences("CW_AI_PREFS", Context.MODE_PRIVATE);
        int modeIdx = prefs.getInt(PREF_CW_AI_MODE, 0);
        int widthIdx = prefs.getInt(PREF_CW_AI_WIDTH, 4); // Default 800Hz
        int denoiseVal = prefs.getInt(PREF_CW_AI_DENOISE, 0);
        int wetVal = prefs.getInt(PREF_CW_AI_WET, 100);
        int gainVal = prefs.getInt(PREF_CW_AI_GAIN, 100);
        boolean locked = prefs.getInt(PREF_CW_AI_LOCK_CONTROLS, 0) == 1;
        int streamMethodIdx = prefs.getInt(PREF_CW_AI_STREAM_METHOD, 0);

        processor.setWetLevel(wetVal / 100f);
        processor.setOutputGain(gainVal / 100f);
        
        sbLevel.setProgress(wetVal);
        sbGain.setProgress(gainVal);
        cbLockDenoiseControls.setChecked(locked);

        if (modeIdx == 1) toggleMode();
        if (streamMethodIdx == StreamingMethod.OVERLAP.ordinal()) aplicaStreamingMethod(StreamingMethod.OVERLAP);
        if (denoiseVal == 1) toggleDenoise();
        spWidth.setSelection(widthIdx);
    }

    private void toggleDecoder() {
        if (!isRunning) {
            verificareSiPornireDecodor();
        } else {
            stopDecoder();
        }
    }

    private void verificareSiPornireDecodor() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) 
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions((android.app.Activity) context, 
                    new String[]{android.Manifest.permission.RECORD_AUDIO}, 123);
        } else {
            // Coordinator-ul asigura ca LISTEN se opreste inainte de a porni decodorul
            AudioCaptureCoordinator.beforeCwDecoderStart();
            startDecoder();
        }
    }

    private void startDecoder() {
        isRunning = true;
        btnToggle.setText("STOP AI DECODER");
        btnToggle.setBackgroundColor(Color.RED);
        tvStatus.setText("CW AI DECODER - RUNNING");
        tvStatus.setTextColor(Color.GREEN);
        processor.start();
    }

    private void stopDecoder() {
        isRunning = false;
        btnToggle.setText("START AI DECODER");
        btnToggle.setBackgroundColor(Color.parseColor("#E65100"));
        tvStatus.setText("CW AI DECODER - PAUSED");
        tvStatus.setTextColor(Color.parseColor("#00E5FF"));
        processor.stop();
        ultimulTonDetectatHz = -1;
    }

    public void startDecoderDupaPermisiune() {
        startDecoder();
    }

    public View getView() {
        return viewPrincipal;
    }

    public void eliberareResurse() {
        stopDecoder();
    }

    private LinearLayout creeazaRandButoane() {
        LinearLayout r = new LinearLayout(context);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 5, 0, 5);
        r.setLayoutParams(p);
        return r;
    }

    private void executaMesajPeCanal(int canal) {
        android.content.SharedPreferences prefs = context.getSharedPreferences("CW_MACROS", Context.MODE_PRIVATE);
        String content = prefs.getString("cw_content_" + (canal - 1), "");

        if (!content.isEmpty()) {
            content = EcranRttyDroid.inlocuiestePlaceholderIndicativ(content);
            radioManager.trimiteComandaCAT("KY " + content.toUpperCase() + ";");
        } else {
            // Dacă e gol, forțăm declanșarea memoriei interne prin KY0
            radioManager.trimiteComandaCAT("KY0" + canal + ";");
        }
    }
}
