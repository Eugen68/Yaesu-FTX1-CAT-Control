package com.example.yaesucatcontroladv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Ecranul principal pentru DX Cluster (Versiune Pro).
 * Utilizează un Foreground Service pentru persistență și RecyclerView pentru performanță.
 */
public class EcranCluster {
    private final Context context;
    private final RadioManager radioManager;

    private LinearLayout viewPrincipal;
    private RecyclerView rvSpoturi;
    private SpotAdapter adapter;
    private TextView tvStatus;
    private Button btnConnect, btnBand, btnMode;
    private CheckBox cbKeepLive;
    private LinearLayout panelFiltre;
    private EditText etSearch;
    private ClusterRepository.RepoListener repoListener;

    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable debounceRunnable;

    private final Handler hideFiltersHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideFiltersRunnable = () -> toggleFilters(false);

    private boolean filtersVisible = false;
    private final Set<String> selectedBands = new HashSet<>();
    private final Set<String> selectedModes = new HashSet<>();
    private String filterSearch = "";

    private static final String[] ALL_BANDS = {"160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "6m", "2m", "70cm"};
    private static final String[] ALL_MODES = {"CW", "SSB", "FT8", "FT4", "RTTY", "PSK", "JS8", "AM", "FM", "DATA"};

    public EcranCluster(Context context, RadioManager radioManager) {
        this.context = context;
        this.radioManager = radioManager;
        this.adapter = new SpotAdapter(this::onSpotClicked);
        construiesteInterfata();
        setupRepositoryListener();
    }

    public View getView() {
        // La fiecare deschidere a tab-ului, sincronizăm lista completă din Repository
        adapter.updateList(ClusterRepository.getInstance().getAllSpots());
        return viewPrincipal;
    }

    private void construiesteInterfata() {
        viewPrincipal = new LinearLayout(context);
        viewPrincipal.setOrientation(LinearLayout.VERTICAL);
        viewPrincipal.setBackgroundColor(Color.parseColor("#121212"));
        viewPrincipal.setPadding(20, 10, 20, 10);

        // Titlu
        TextView tvTitlu = new TextView(context);
        tvTitlu.setText("DX CLUSTER PRO");
        tvTitlu.setTextColor(Color.WHITE);
        tvTitlu.setTextSize(18);
        tvTitlu.setTypeface(null, Typeface.BOLD);
        tvTitlu.setGravity(Gravity.CENTER);
        tvTitlu.setPadding(0, 5, 0, 5);
        viewPrincipal.addView(tvTitlu);

        tvStatus = new TextView(context);
        tvStatus.setText("Disconnected");
        tvStatus.setTextColor(Color.GRAY);
        tvStatus.setTextSize(11);
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setPadding(0, 0, 0, 10);
        viewPrincipal.addView(tvStatus);

        // Rand Connect
        LinearLayout rowConnect = new LinearLayout(context);
        rowConnect.setOrientation(LinearLayout.HORIZONTAL);
        rowConnect.setGravity(Gravity.CENTER_VERTICAL);

        btnConnect = new Button(context);
        btnConnect.setText("CONNECT");
        btnConnect.setBackgroundColor(Color.parseColor("#2E7D32"));
        btnConnect.setTextColor(Color.WHITE);
        btnConnect.setTextSize(11);
        btnConnect.setLayoutParams(new LinearLayout.LayoutParams(0, 120, 1.2f));
        btnConnect.setOnClickListener(v -> toggleConnection());
        rowConnect.addView(btnConnect);

        cbKeepLive = new CheckBox(context);
        cbKeepLive.setText("KEEP LIVE");
        cbKeepLive.setTextColor(Color.YELLOW);
        cbKeepLive.setTextSize(11);
        cbKeepLive.setChecked(context.getSharedPreferences(EcranSetariProprii.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean("cluster_keep_live", false));
        cbKeepLive.setOnCheckedChangeListener((v, isChecked) -> {
            context.getSharedPreferences(EcranSetariProprii.PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putBoolean("cluster_keep_live", isChecked).apply();
        });
        LinearLayout.LayoutParams pCb = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f);
        pCb.setMargins(15, 0, 0, 0);
        cbKeepLive.setLayoutParams(pCb);
        rowConnect.addView(cbKeepLive);
        viewPrincipal.addView(rowConnect);

        // Rand Filtre Toggle + Clear (75/25 split)
        LinearLayout rowFiltersClear = new LinearLayout(context);
        rowFiltersClear.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams pRowFC = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pRowFC.setMargins(0, 5, 0, 5);
        rowFiltersClear.setLayoutParams(pRowFC);

        Button btnToggleFilters = new Button(context);
        btnToggleFilters.setText("FILTERS ▼");
        btnToggleFilters.setBackgroundColor(Color.parseColor("#37474F"));
        btnToggleFilters.setTextColor(Color.WHITE);
        btnToggleFilters.setTextSize(11);
        btnToggleFilters.setLayoutParams(new LinearLayout.LayoutParams(0, 110, 0.75f));
        btnToggleFilters.setOnClickListener(v -> toggleFilters(!filtersVisible));

        Button btnClear = new Button(context);
        btnClear.setText("CLEAR");
        btnClear.setBackgroundColor(Color.parseColor("#455A64"));
        btnClear.setTextColor(Color.WHITE);
        btnClear.setTextSize(11);
        LinearLayout.LayoutParams pBtnClear = new LinearLayout.LayoutParams(0, 110, 0.25f);
        pBtnClear.setMargins(10, 0, 0, 0);
        btnClear.setLayoutParams(pBtnClear);
        btnClear.setOnClickListener(v -> {
            ClusterRepository.getInstance().clearSpots();
            adapter.updateList(new java.util.ArrayList<>());
            Toast.makeText(context, "List cleared", Toast.LENGTH_SHORT).show();
        });

        rowFiltersClear.addView(btnToggleFilters);
        rowFiltersClear.addView(btnClear);
        viewPrincipal.addView(rowFiltersClear);

        // Panel Filtre
        panelFiltre = new LinearLayout(context);
        panelFiltre.setOrientation(LinearLayout.VERTICAL);
        panelFiltre.setVisibility(View.GONE);
        panelFiltre.setPadding(10, 5, 10, 5);

        LinearLayout rowF1 = new LinearLayout(context);
        LinearLayout.LayoutParams pBtnF = new LinearLayout.LayoutParams(0, 110, 1f);
        pBtnF.setMargins(5, 0, 5, 0);

        btnBand = new Button(context);
        btnBand.setText("ALL BANDS");
        btnBand.setTextSize(10);
        btnBand.setBackgroundColor(Color.parseColor("#333333"));
        btnBand.setTextColor(Color.WHITE);
        btnBand.setLayoutParams(pBtnF);
        btnBand.setOnClickListener(v -> showMultiSelectDialog("Bands", ALL_BANDS, selectedBands));

        btnMode = new Button(context);
        btnMode.setText("ALL MODES");
        btnMode.setTextSize(10);
        btnMode.setBackgroundColor(Color.parseColor("#333333"));
        btnMode.setTextColor(Color.WHITE);
        btnMode.setLayoutParams(pBtnF);
        btnMode.setOnClickListener(v -> showMultiSelectDialog("Modes", ALL_MODES, selectedModes));

        rowF1.addView(btnBand);
        rowF1.addView(btnMode);
        panelFiltre.addView(rowF1);

        etSearch = new EditText(context);
        etSearch.setHint("Search Callsign...");
        etSearch.setTextColor(Color.WHITE);
        etSearch.setTextSize(14);
        etSearch.setCompoundDrawablePadding(10);
        etSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                if (s.length() > 0) {
                    etSearch.setCompoundDrawablesWithIntrinsicBounds(null, null, createXIcon(), null);
                } else {
                    etSearch.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
                }
                filterSearch = s.toString().trim().toUpperCase();
                resetFiltersTimer(8000);
                debounceFilters();
            }
        });
        etSearch.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                if (etSearch.getCompoundDrawables()[2] != null) {
                    if (event.getRawX() >= (etSearch.getRight() - etSearch.getCompoundPaddingRight())) {
                        etSearch.setText("");
                        return true;
                    }
                }
            }
            return false;
        });
        panelFiltre.addView(etSearch);
        viewPrincipal.addView(panelFiltre);

        // --- LINIE COMANDĂ MANUALĂ ---
        LinearLayout rowCmd = new LinearLayout(context);
        rowCmd.setOrientation(LinearLayout.HORIZONTAL);
        rowCmd.setGravity(Gravity.CENTER_VERTICAL);
        EditText etCmd = new EditText(context);
        etCmd.setHint("Cluster Command...");
        etCmd.setTextColor(Color.WHITE);
        etCmd.setTextSize(13);
        etCmd.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button btnSend = new Button(context);
        btnSend.setText("SEND");
        btnSend.setBackgroundColor(Color.parseColor("#0D47A1"));
        btnSend.setTextColor(Color.WHITE);
        btnSend.setLayoutParams(new LinearLayout.LayoutParams(160, 110));
        btnSend.setOnClickListener(v -> {
            String cmd = etCmd.getText().toString().trim();
            if (!cmd.isEmpty()) {
                trimiteComandaLaServiciu(cmd);
                etCmd.setText("");
            }
        });
        rowCmd.addView(etCmd);
        rowCmd.addView(btnSend);
        viewPrincipal.addView(rowCmd);

        // --- QUICK COMMANDS ---
        LinearLayout rowQuick = new LinearLayout(context);
        rowQuick.setOrientation(LinearLayout.HORIZONTAL);
        rowQuick.addView(createQuickBtn("SH/DX", "SH/DX"));
        rowQuick.addView(createQuickBtn("SH/DX/50", "SH/DX/50"));
        rowQuick.addView(createQuickBtn("SET/NOBC", "SET/NOBC"));
        viewPrincipal.addView(rowQuick);

        // RecyclerView
        rvSpoturi = new RecyclerView(context);
        rvSpoturi.setLayoutManager(new LinearLayoutManager(context));
        rvSpoturi.setAdapter(adapter);
        LinearLayout.LayoutParams pRv = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        pRv.setMargins(0, 10, 0, 0);
        rvSpoturi.setLayoutParams(pRv);
        viewPrincipal.addView(rvSpoturi);

        loadFilters();
    }

    private Button createQuickBtn(String text, String cmd) {
        Button b = new Button(context);
        b.setText(text);
        b.setBackgroundColor(Color.parseColor("#424242"));
        b.setTextColor(Color.WHITE);
        b.setTextSize(10);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 100, 1f);
        p.setMargins(5, 5, 5, 5);
        b.setLayoutParams(p);
        b.setOnClickListener(v -> trimiteComandaLaServiciu(cmd));
        return b;
    }

    private void resetFiltersTimer(int ms) {
        hideFiltersHandler.removeCallbacks(hideFiltersRunnable);
        if (filtersVisible) {
            hideFiltersHandler.postDelayed(hideFiltersRunnable, ms);
        }
    }

    private void toggleFilters(boolean visible) {
        filtersVisible = visible;
        panelFiltre.setVisibility(visible ? View.VISIBLE : View.GONE);
        resetFiltersTimer(5000);
    }

    private void trimiteComandaLaServiciu(String cmd) {
        Intent intent = new Intent(context, ClusterService.class);
        intent.setAction("SEND_COMMAND");
        intent.putExtra("command", cmd);
        context.startService(intent);
    }

    private void debounceFilters() {
        debounceHandler.removeCallbacks(debounceRunnable);
        debounceRunnable = () -> {
            adapter.applyFilter(filterSearch, selectedBands, selectedModes);
            saveFilters();
        };
        debounceHandler.postDelayed(debounceRunnable, 300);
    }

    private void setupRepositoryListener() {
        repoListener = new ClusterRepository.RepoListener() {
            @Override
            public void onNewSpot(Spot spot) {
                if (context instanceof Activity) {
                    Activity activity = (Activity) context;
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        activity.runOnUiThread(() -> {
                            adapter.updateList(ClusterRepository.getInstance().getAllSpots());
                        });
                    }
                }
            }

            @Override
            public void onStatusChanged(boolean connected, String message) {
                if (context instanceof Activity) {
                    Activity activity = (Activity) context;
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        activity.runOnUiThread(() -> actualizeazaStatus(connected, message));
                    }
                }
            }

            @Override
            public void onSpotsCleared() {
                // Se declanșează când ClusterService golește lista la oprire
                // (ex: userul închide conexiunea din switch, cât timp ecranul
                // e încă deschis). Sincronizăm RecyclerView-ul imediat, ca la
                // butonul manual CLEAR.
                if (context instanceof Activity) {
                    Activity activity = (Activity) context;
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        activity.runOnUiThread(() -> adapter.updateList(new java.util.ArrayList<>()));
                    }
                }
            }
        };
        ClusterRepository.getInstance().setListener(repoListener);
    }

    private void toggleConnection() {
        Intent intent = new Intent(context, ClusterService.class);
        if (ClusterRepository.getInstance().isConnected()) {
            context.stopService(intent);
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        }
    }

    private void actualizeazaStatus(boolean connected, String message) {
        tvStatus.setText(message);
        tvStatus.setTextColor(connected ? Color.parseColor("#00E676") : Color.GRAY);
        btnConnect.setText(connected ? "DISCONNECT" : "CONNECT");
        btnConnect.setBackgroundColor(Color.parseColor(connected ? "#B71C1C" : "#2E7D32"));
    }

    private void onSpotClicked(Spot spot) {
        if (radioManager != null) {
            long freqHz = (long) (spot.frequency * 1_000_000);
            radioManager.trimiteComandaCAT(String.format(Locale.US, "FA%09d;", freqHz));
            // Sincronizare mod (simplificată)
            String catMode = spot.frequency < 10.0 ? "01" : "02";
            if (spot.mode.equals("CW")) catMode = "03";
            if (spot.mode.equals("RTTY")) catMode = "06";
            radioManager.trimiteComandaCAT("MD" + catMode + ";");
        }
        EcranRttyDroid.callsignBuffer = spot.dxCall;
        Toast.makeText(context, "Tuned to " + spot.dxCall, Toast.LENGTH_SHORT).show();
    }



    private void showMultiSelectDialog(String title, String[] items, Set<String> selection) {
        boolean[] checked = new boolean[items.length];
        for(int i=0; i<items.length; i++) checked[i] = selection.contains(items[i]);
        new AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setMultiChoiceItems(items, checked, (d, w, isC) -> {
                    checked[w] = isC;
                    resetFiltersTimer(8000);
                })
                .setPositiveButton("OK", (d, w) -> {
                    selection.clear();
                    for(int i=0; i<items.length; i++) if(checked[i]) selection.add(items[i]);
                    btnBand.setText(selectedBands.isEmpty() ? "ALL BANDS" : String.join(",", selectedBands));
                    btnMode.setText(selectedModes.isEmpty() ? "ALL MODES" : String.join(",", selectedModes));
                    resetFiltersTimer(5000);
                    debounceFilters();
                })
                .show();
    }

    private void saveFilters() {
        context.getSharedPreferences(EcranSetariProprii.PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(EcranSetariProprii.PREF_CLUSTER_FILTER_BAND, String.join(",", selectedBands))
                .putString(EcranSetariProprii.PREF_CLUSTER_FILTER_MODE, String.join(",", selectedModes))
                .putString(EcranSetariProprii.PREF_CLUSTER_FILTER_SEARCH, filterSearch)
                .apply();
    }

    private void loadFilters() {
        android.content.SharedPreferences p = context.getSharedPreferences(EcranSetariProprii.PREFS_NAME, Context.MODE_PRIVATE);
        String b = p.getString(EcranSetariProprii.PREF_CLUSTER_FILTER_BAND, "");
        if(!b.isEmpty()) selectedBands.addAll(Arrays.asList(b.split(",")));
        String m = p.getString(EcranSetariProprii.PREF_CLUSTER_FILTER_MODE, "");
        if(!m.isEmpty()) selectedModes.addAll(Arrays.asList(m.split(",")));
        filterSearch = p.getString(EcranSetariProprii.PREF_CLUSTER_FILTER_SEARCH, "");
        btnBand.setText(selectedBands.isEmpty() ? "ALL BANDS" : String.join(",", selectedBands));
        btnMode.setText(selectedModes.isEmpty() ? "ALL MODES" : String.join(",", selectedModes));
        // Bug reparat: caseta de căutare rămânea vizual goală chiar dacă
        // filterSearch (reîncărcat din prefs) nu era gol - filtrul se aplica
        // "pe ascuns", eliminând spoturi fără ca userul să vadă vreun text
        // în câmpul de căutare.
        etSearch.setText(filterSearch);

        // Sincronizăm adaptorul imediat ce s-au încărcat filtrele
        adapter.applyFilter(filterSearch, selectedBands, selectedModes);
    }

    /**
     * Creează iconița X dinamic (fără resurse externe) - Stil RTTY Center.
     */
    private android.graphics.drawable.Drawable createXIcon() {
        int size = 48; // dimensiune în pixeli
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);

        // Fundal roșu circular
        android.graphics.Paint bgPaint = new android.graphics.Paint();
        bgPaint.setColor(Color.parseColor("#B71C1C"));
        bgPaint.setAntiAlias(true);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bgPaint);

        // X alb în centru
        android.graphics.Paint xPaint = new android.graphics.Paint();
        xPaint.setColor(Color.WHITE);
        xPaint.setStrokeWidth(4f);
        xPaint.setAntiAlias(true);
        xPaint.setStrokeCap(android.graphics.Paint.Cap.ROUND);

        float margin = size * 0.3f;
        canvas.drawLine(margin, margin, size - margin, size - margin, xPaint);
        canvas.drawLine(size - margin, margin, margin, size - margin, xPaint);

        return new android.graphics.drawable.BitmapDrawable(context.getResources(), bitmap);
    }

    public void eliberareResurse() {
        debounceHandler.removeCallbacksAndMessages(null);
        hideFiltersHandler.removeCallbacksAndMessages(null);

        // Ne dezabonăm de la Repository pentru a nu primi spoturi pe un ecran distrus.
        // Folosim Repository-ul cu noul mecanism de siguranță la rotație.
        ClusterRepository.getInstance().removeListener(repoListener);

        // Oprim serviciul de cluster pentru a închide conexiunea și a elimina notificarea
        try {
            Intent intent = new Intent(context, ClusterService.class);
            context.stopService(intent);
        } catch (Exception ignored) {}
    }
}