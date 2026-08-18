package com.example.yaesucatcontroladv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Spinner;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;

import com.example.yaesucatcontroladv.deepcw.audio.MorseAudioProcessor;
import com.example.yaesucatcontroladv.deepcw.audio.DecoderMode;
import com.example.yaesucatcontroladv.deepcw.data.PileupTrack;
import com.example.yaesucatcontroladv.deepcw.dsp.Constants;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class EcranCwDeepDecoder {
    private final Context context;
    private final RadioManager radioManager;
    private LinearLayout viewPrincipal;

    private CwWaterfall waterfall;
    private TextView tvStatus, tvFreqInfo;
    private ScrollView svTerminal;
    private LinearLayout llMessagesContainer;
    private Button btnToggle, btnModeToggle;
    private Spinner spWidth;

    private MorseAudioProcessor processor;
    private boolean isRunning = false;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Handler statusRevertHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusRevertRunnable = this::revertStatusLabel;

    private static final String PREF_CW_AI_MODE = "cw_ai_mode";
    private static final String PREF_CW_AI_WIDTH = "cw_ai_width";

    public EcranCwDeepDecoder(Context context, RadioManager radioManager) {
        this.context = context;
        this.radioManager = radioManager;
        this.processor = new MorseAudioProcessor(context);
        construiesteInterfata();
        configurareObservatori();
        incarcaSetari();
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
        btnModeToggle.setLayoutParams(new LinearLayout.LayoutParams(0, 110, 1f));
        btnModeToggle.setOnClickListener(v -> toggleMode());
        rControls.addView(btnModeToggle);

        View spacer = new View(context);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(20, 1));
        rControls.addView(spacer);

        TextView tvW = new TextView(context);
        tvW.setText("BW:");
        tvW.setTextColor(Color.WHITE);
        tvW.setPadding(10, 0, 10, 0);
        rControls.addView(tvW);

        spWidth = new Spinner(context);
        List<String> widths = new ArrayList<>();
        for (int w : Constants.INSTANCE.getFILTER_WIDTH_OPTIONS()) widths.add(w + " Hz");
        ArrayAdapter<String> adapterW = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, widths);
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
        rControls.addView(spWidth);
        viewPrincipal.addView(rControls);

        // Row 2: Start/Stop & Clear
        LinearLayout rButtons = new LinearLayout(context);
        rButtons.setOrientation(LinearLayout.HORIZONTAL);
        
        btnToggle = new Button(context);
        btnToggle.setText("START AI DECODER");
        btnToggle.setBackgroundColor(Color.parseColor("#E65100"));
        btnToggle.setTextColor(Color.WHITE);
        btnToggle.setOnClickListener(v -> toggleDecoder());
        rButtons.addView(btnToggle, new LinearLayout.LayoutParams(0, 120, 1f));

        Button btnClear = new Button(context);
        btnClear.setText("CLEAR");
        btnClear.setBackgroundColor(Color.parseColor("#424242"));
        btnClear.setTextColor(Color.WHITE);
        btnClear.setOnClickListener(v -> clearTerminal());
        rButtons.addView(btnClear, new LinearLayout.LayoutParams(0, 120, 0.4f));
        
        viewPrincipal.addView(rButtons);
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
                uiHandler.post(() -> waterfall.setTrackFrequencies(freqs));
            }

            @Override
            public void onTextsUpdated(Map<Integer, String> texts) {
                uiHandler.post(() -> {
                    waterfall.setTrackTexts(texts);
                    updatePileupList(texts);
                });
            }
        });

        processor.setAvailabilityListener(avail -> uiHandler.post(() -> {
            if (!avail.getPileupAvailable() && processor.getMode() == DecoderMode.PILEUP) {
                toggleMode(); // Fallback to normal
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

    private void clearTerminal() {
        llMessagesContainer.removeAllViews();
        waterfall.clearOverlays();
        if (processor != null) {
            processor.clearData();
        }
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
        
        // Alăturăm segmentele confirmate
        StringBuilder sb = new StringBuilder();
        for (String s : segments) {
            sb.append(s);
            if (!s.endsWith(" ")) sb.append(" ");
        }
        
        tv.setText(sb.toString());
        
        // Adăugăm textul pending (gri) folosind SpannableString pentru a evita ClassCastException
        if (pending != null && !pending.isEmpty()) {
            android.text.SpannableString spannablePending = new android.text.SpannableString(pending);
            spannablePending.setSpan(new android.text.style.ForegroundColorSpan(Color.GRAY), 
                    0, pending.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            tv.append(spannablePending);
        }
        
        llMessagesContainer.addView(tv);
        svTerminal.post(() -> svTerminal.fullScroll(View.FOCUS_DOWN));
    }

    private void updatePileupList(Map<Integer, String> texts) {
        if (processor.getMode() != DecoderMode.PILEUP) return;
        llMessagesContainer.removeAllViews();
        for (Map.Entry<Integer, String> entry : texts.entrySet()) {
            TextView tv = new TextView(context);
            tv.setText(String.format("[%d Hz]: %s", entry.getKey(), entry.getValue()));
            tv.setTextColor(Color.GREEN);
            tv.setTextSize(16);
            tv.setTypeface(Typeface.MONOSPACE);
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

        if (modeIdx == 1) toggleMode();
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
}
