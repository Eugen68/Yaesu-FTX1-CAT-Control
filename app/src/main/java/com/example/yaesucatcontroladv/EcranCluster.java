package com.example.yaesucatcontroladv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Ecranul principal pentru DX Cluster.
 * Afișează lista de spot-uri colorate pe benzi, cu filtre și buton de conectare.
 */
public class EcranCluster {
    private final Context context;
    private final RadioManager radioManager;
    private final ClusterManager clusterManager;

    private LinearLayout viewPrincipal;
    private LinearLayout listaSpoturi;
    private TextView tvStatus;
    private Button btnConnect;
    private CheckBox cbKeepLive;
    private final Handler reconnectHandler = new Handler(Looper.getMainLooper());
    private boolean isReconnecting = false;

    // Elemente Filtre
    private LinearLayout panelFiltre;
    private Spinner spBand, spMode;
    private EditText etSearch;
    private boolean filtersVisible = false;

    // Filtre Curente
    private String filterBand = "All";
    private String filterMode = "All";
    private String filterSearch = "";

    private final List<Spot> spoturi = new ArrayList<>();
    private static final int MAX_SPOTURI = 100; // Limită pentru a nu umple memoria

    public EcranCluster(Context context, RadioManager radioManager) {
        this.context = context;
        this.radioManager = radioManager;
        this.clusterManager = new ClusterManager(context);
        construiesteInterfata();
        setupClusterListener();
    }

    public LinearLayout getView() {
        return viewPrincipal;
    }

    private void construiesteInterfata() {
        viewPrincipal = new LinearLayout(context);
        viewPrincipal.setOrientation(LinearLayout.VERTICAL);
        viewPrincipal.setBackgroundColor(Color.parseColor("#121212"));
        viewPrincipal.setPadding(20, 20, 20, 20);

        // Titlu
        TextView tvTitlu = new TextView(context);
        tvTitlu.setText("DX CLUSTER");
        tvTitlu.setTextColor(Color.WHITE);
        tvTitlu.setTextSize(20);
        tvTitlu.setTypeface(null, Typeface.BOLD);
        tvTitlu.setGravity(Gravity.CENTER);
        tvTitlu.setPadding(0, 0, 0, 20);
        viewPrincipal.addView(tvTitlu);

        // Status conexiune (permitem mai multe linii pentru erori lungi)
        tvStatus = new TextView(context);
        tvStatus.setText("Disconnected");
        tvStatus.setTextColor(Color.GRAY);
        tvStatus.setTextSize(11); // Font mai mic pentru a vedea detaliile
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setPadding(10, 0, 10, 10);
        tvStatus.setMaxLines(6); // Mai multe linii
        viewPrincipal.addView(tvStatus);

        // Linie Buton Connect + Checkbox Keep Live
        LinearLayout rowConnect = new LinearLayout(context);
        rowConnect.setOrientation(LinearLayout.HORIZONTAL);
        rowConnect.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams pRow = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pRow.setMargins(0, 0, 0, 15);
        rowConnect.setLayoutParams(pRow);

        // Buton conectare/deconectare
        btnConnect = new Button(context);
        btnConnect.setText("CONNECT");
        btnConnect.setBackgroundColor(Color.parseColor("#2E7D32"));
        btnConnect.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams pBtn = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f);
        btnConnect.setLayoutParams(pBtn);
        btnConnect.setOnClickListener(v -> toggleConnection());
        rowConnect.addView(btnConnect);

        // Checkbox Keep Live
        cbKeepLive = new CheckBox(context);
        cbKeepLive.setText("KEEP LIVE");
        cbKeepLive.setTextColor(Color.YELLOW);
        cbKeepLive.setTextSize(12);
        cbKeepLive.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams pCb = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f);
        pCb.setMargins(20, 0, 0, 0);
        cbKeepLive.setLayoutParams(pCb);
        rowConnect.addView(cbKeepLive);

        viewPrincipal.addView(rowConnect);

        // --- LINIE INSTRUMENTE (CLEAR & FILTERS) ---
        LinearLayout rowTools = new LinearLayout(context);
        rowTools.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams pRowTools = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pRowTools.setMargins(0, 0, 0, 10);
        rowTools.setLayoutParams(pRowTools);

        Button btnClear = new Button(context);
        btnClear.setText("CLEAR LIST");
        btnClear.setBackgroundColor(Color.parseColor("#455A64"));
        btnClear.setTextColor(Color.WHITE);
        btnClear.setTextSize(11);
        btnClear.setLayoutParams(new LinearLayout.LayoutParams(0, 110, 1f));
        btnClear.setOnClickListener(v -> clearList());
        rowTools.addView(btnClear);

        View spacerTools = new View(context);
        spacerTools.setLayoutParams(new LinearLayout.LayoutParams(15, 1));
        rowTools.addView(spacerTools);

        Button btnToggleFilters = new Button(context);
        btnToggleFilters.setText("FILTERS ▼");
        btnToggleFilters.setBackgroundColor(Color.parseColor("#37474F"));
        btnToggleFilters.setTextColor(Color.WHITE);
        btnToggleFilters.setTextSize(11);
        btnToggleFilters.setLayoutParams(new LinearLayout.LayoutParams(0, 110, 1f));
        btnToggleFilters.setOnClickListener(v -> toggleFilters());
        rowTools.addView(btnToggleFilters);

        viewPrincipal.addView(rowTools);

        // --- PANOU FILTRE (HIDDEN BY DEFAULT) ---
        panelFiltre = new LinearLayout(context);
        panelFiltre.setOrientation(LinearLayout.VERTICAL);
        panelFiltre.setBackgroundColor(Color.parseColor("#1E1E1E"));
        panelFiltre.setPadding(20, 20, 20, 20);
        panelFiltre.setVisibility(View.GONE);
        LinearLayout.LayoutParams pPanel = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pPanel.setMargins(0, 0, 0, 15);
        panelFiltre.setLayoutParams(pPanel);

        // Row 1: Band & Mode
        LinearLayout rowF1 = new LinearLayout(context);
        rowF1.setOrientation(LinearLayout.HORIZONTAL);
        
        spBand = createFilterSpinner(Arrays.asList("All", "160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "6m", "2m"));
        spMode = createFilterSpinner(Arrays.asList("All", "CW", "SSB", "FT8", "FT4", "RTTY", "PSK"));
        
        rowF1.addView(spBand);
        View spacerF = new View(context);
        spacerF.setLayoutParams(new LinearLayout.LayoutParams(20, 1));
        rowF1.addView(spacerF);
        rowF1.addView(spMode);
        panelFiltre.addView(rowF1);

        // Row 2: Search
        etSearch = new EditText(context);
        etSearch.setHint("Search Callsign / Prefix...");
        etSearch.setHintTextColor(Color.GRAY);
        etSearch.setTextColor(Color.WHITE);
        etSearch.setTextSize(14);
        etSearch.setSingleLine(true);
        LinearLayout.LayoutParams pSearch = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pSearch.setMargins(0, 10, 0, 0);
        etSearch.setLayoutParams(pSearch);
        etSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                filterSearch = s.toString().trim().toUpperCase();
                saveFilters();
                refacereLista();
            }
        });
        panelFiltre.addView(etSearch);

        viewPrincipal.addView(panelFiltre);

        // --- LINIE COMANDĂ CLUSTER ---
        LinearLayout rowCmd = new LinearLayout(context);
        rowCmd.setOrientation(LinearLayout.HORIZONTAL);
        rowCmd.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams pRowCmd = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pRowCmd.setMargins(0, 0, 0, 10);
        rowCmd.setLayoutParams(pRowCmd);

        EditText etCmd = new EditText(context);
        etCmd.setHint("Cluster Command...");
        etCmd.setHintTextColor(Color.GRAY);
        etCmd.setTextColor(Color.WHITE);
        etCmd.setTextSize(13);
        etCmd.setSingleLine(true);
        etCmd.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button btnSend = new Button(context);
        btnSend.setText("SEND");
        btnSend.setBackgroundColor(Color.parseColor("#0D47A1"));
        btnSend.setTextColor(Color.WHITE);
        btnSend.setTextSize(11);
        btnSend.setLayoutParams(new LinearLayout.LayoutParams(160, 100));
        btnSend.setOnClickListener(v -> {
            String cmd = etCmd.getText().toString().trim();
            if (!cmd.isEmpty()) {
                clusterManager.sendCommand(cmd);
                etCmd.setText("");
            }
        });

        rowCmd.addView(etCmd);
        rowCmd.addView(btnSend);
        viewPrincipal.addView(rowCmd);

        // --- QUICK COMMANDS ---
        LinearLayout rowQuick = new LinearLayout(context);
        rowQuick.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams pQuick = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pQuick.setMargins(0, 0, 0, 10);
        rowQuick.setLayoutParams(pQuick);

        rowQuick.addView(createQuickBtn("SH/DX", "SH/DX"));
        rowQuick.addView(createQuickBtn("SH/DX/50", "SH/DX/50"));
        rowQuick.addView(createQuickBtn("SET/NOBC", "SET/NOBC"));

        viewPrincipal.addView(rowQuick);

        // Separator
        View separator = new View(context);
        separator.setBackgroundColor(Color.GRAY);
        LinearLayout.LayoutParams pSep = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 2);
        pSep.setMargins(0, 5, 0, 10);
        separator.setLayoutParams(pSep);
        viewPrincipal.addView(separator);

        // Lista spoturi (în ScrollView)
        ScrollView sv = new ScrollView(context);
        LinearLayout.LayoutParams pSv = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        sv.setLayoutParams(pSv);

        listaSpoturi = new LinearLayout(context);
        listaSpoturi.setOrientation(LinearLayout.VERTICAL);
        listaSpoturi.setPadding(10, 10, 10, 10);
        sv.addView(listaSpoturi);

        viewPrincipal.addView(sv);

        loadFilters(); // Încarcă filtrele salvate
    }

    private Spinner createFilterSpinner(List<String> items) {
        Spinner s = new Spinner(context);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, items) {
            @androidx.annotation.NonNull
            @Override
            public View getView(int position, View convertView, @androidx.annotation.NonNull android.view.ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                ((TextView) v).setTextColor(Color.WHITE);
                ((TextView) v).setTextSize(13);
                return v;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(adapter);
        s.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        s.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (parent == spBand) filterBand = items.get(position);
                else if (parent == spMode) filterMode = items.get(position);
                saveFilters();
                refacereLista();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        return s;
    }

    private Button createQuickBtn(String text, String cmd) {
        Button b = new Button(context);
        b.setText(text);
        b.setBackgroundColor(Color.parseColor("#424242"));
        b.setTextColor(Color.WHITE);
        b.setTextSize(10);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 90, 1f);
        p.setMargins(5, 0, 5, 0);
        b.setLayoutParams(p);
        b.setOnClickListener(v -> clusterManager.sendCommand(cmd));
        return b;
    }

    private void toggleFilters() {
        filtersVisible = !filtersVisible;
        panelFiltre.setVisibility(filtersVisible ? View.VISIBLE : View.GONE);
    }

    private void clearList() {
        spoturi.clear();
        listaSpoturi.removeAllViews();
    }

    private void saveFilters() {
        context.getSharedPreferences(EcranSetariProprii.PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(EcranSetariProprii.PREF_CLUSTER_FILTER_BAND, filterBand)
                .putString(EcranSetariProprii.PREF_CLUSTER_FILTER_MODE, filterMode)
                .putString(EcranSetariProprii.PREF_CLUSTER_FILTER_SEARCH, filterSearch)
                .apply();
    }

    private void loadFilters() {
        android.content.SharedPreferences prefs = context.getSharedPreferences(EcranSetariProprii.PREFS_NAME, Context.MODE_PRIVATE);
        filterBand = prefs.getString(EcranSetariProprii.PREF_CLUSTER_FILTER_BAND, "All");
        filterMode = prefs.getString(EcranSetariProprii.PREF_CLUSTER_FILTER_MODE, "All");
        filterSearch = prefs.getString(EcranSetariProprii.PREF_CLUSTER_FILTER_SEARCH, "");

        setSpinnerValue(spBand, filterBand);
        setSpinnerValue(spMode, filterMode);
        etSearch.setText(filterSearch);
    }

    private void setSpinnerValue(Spinner s, String value) {
        if (value == null) return;
        @SuppressWarnings("unchecked")
        ArrayAdapter<String> adapter = (ArrayAdapter<String>) s.getAdapter();
        for (int i = 0; i < adapter.getCount(); i++) {
            String item = adapter.getItem(i);
            if (item != null && item.equals(value)) {
                s.setSelection(i);
                break;
            }
        }
    }

    private void refacereLista() {
        listaSpoturi.removeAllViews();
        for (Spot s : spoturi) {
            if (respectaFiltrele(s)) {
                listaSpoturi.addView(creeazaSpotView(s));
            }
        }
    }

    private boolean respectaFiltrele(Spot s) {
        if (!filterBand.equals("All") && !filterBand.equalsIgnoreCase(s.band)) return false;
        if (!filterMode.equals("All") && !filterMode.equalsIgnoreCase(s.mode)) return false;
        if (!filterSearch.isEmpty() && !s.dxCall.toUpperCase().startsWith(filterSearch)) return false;
        return true;
    }

    private void setupClusterListener() {
        clusterManager.setListener(new ClusterManager.ClusterListener() {
            @Override
            public void onSpotReceived(Spot spot) {
                adaugaSpot(spot);
            }

            @Override
            public void onConnectionStatusChanged(boolean connected, String message) {
                actualizeazaStatus(connected, message);
            }

            @Override
            public void onError(String error) {
                Toast.makeText(context, "Cluster Error: " + error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void toggleConnection() {
        if (clusterManager.isConnected()) {
            isReconnecting = false; // Oprim orice tentativă de reconectare
            reconnectHandler.removeCallbacksAndMessages(null);
            clusterManager.disconnect();
            btnConnect.setText("CONNECT");
            btnConnect.setBackgroundColor(Color.parseColor("#2E7D32"));
        } else {
            // Verificăm dacă avem un indicativ setat (fie în Cluster Settings, fie în My Settings)
            android.content.SharedPreferences prefs = context.getSharedPreferences(EcranSetariProprii.PREFS_NAME, Context.MODE_PRIVATE);
            String user = prefs.getString(EcranSetariProprii.PREF_CLUSTER_USER, "");
            String myCall = prefs.getString(EcranSetariProprii.PREF_MY_CALL, "");

            if (user.isEmpty() && myCall.isEmpty()) {
                Toast.makeText(context, "Please set your Callsign in Settings first!", Toast.LENGTH_LONG).show();
                return;
            }
            
            clusterManager.connect();
            btnConnect.setText("CONNECTING...");
            btnConnect.setBackgroundColor(Color.parseColor("#FF6F00"));
        }
    }

    private void actualizeazaStatus(boolean connected, String message) {
        tvStatus.setText(message);
        tvStatus.setTextColor(connected ? Color.parseColor("#00E676") : Color.GRAY);

        if (connected) {
            isReconnecting = false;
            reconnectHandler.removeCallbacksAndMessages(null);
            btnConnect.setText("DISCONNECT");
            btnConnect.setBackgroundColor(Color.parseColor("#B71C1C"));
        } else {
            btnConnect.setText("CONNECT");
            btnConnect.setBackgroundColor(Color.parseColor("#2E7D32"));

            // Auto-reconnect logic
            if (cbKeepLive.isChecked() && !isReconnecting) {
                scheduleReconnect();
            }
        }
    }

    private void scheduleReconnect() {
        isReconnecting = true;
        tvStatus.setText("Connection lost. Reconnecting in 5s...");
        tvStatus.setTextColor(Color.YELLOW);
        
        reconnectHandler.postDelayed(() -> {
            if (cbKeepLive.isChecked() && !clusterManager.isConnected()) {
                clusterManager.connect();
            } else {
                isReconnecting = false;
            }
        }, 5000);
    }

    private void adaugaSpot(Spot spot) {
        // Adăugăm în listă (la început, ca să fie cele mai noi sus)
        spoturi.add(0, spot);

        // Limităm numărul de spoturi
        if (spoturi.size() > MAX_SPOTURI) {
            spoturi.remove(spoturi.size() - 1);
        }

        // Adăugăm în UI doar dacă respectă filtrele
        if (respectaFiltrele(spot)) {
            View spotView = creeazaSpotView(spot);
            listaSpoturi.addView(spotView, 0);

            // Limităm și numărul de View-uri din UI
            if (listaSpoturi.getChildCount() > MAX_SPOTURI) {
                listaSpoturi.removeViewAt(listaSpoturi.getChildCount() - 1);
            }
        }
    }

    private View creeazaSpotView(Spot spot) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setPadding(10, 8, 10, 8);
        layout.setBackgroundColor(getCuloareBanda(spot.band));

        LinearLayout.LayoutParams pLayout = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pLayout.setMargins(0, 2, 0, 2);
        layout.setLayoutParams(pLayout);

        // Ora
        TextView tvOra = new TextView(context);
        tvOra.setText(spot.getOraUTC());
        tvOra.setTextColor(Color.WHITE);
        tvOra.setTextSize(12);
        tvOra.setTypeface(Typeface.MONOSPACE);
        layout.addView(tvOra);

        // Spațiu
        View spacer1 = new View(context);
        spacer1.setLayoutParams(new LinearLayout.LayoutParams(15, 1));
        layout.addView(spacer1);

        // Frecvență
        TextView tvFreq = new TextView(context);
        tvFreq.setText(String.format(java.util.Locale.US, "%.5f", spot.frequency));
        tvFreq.setTextColor(Color.WHITE);
        tvFreq.setTextSize(13);
        tvFreq.setTypeface(null, Typeface.BOLD);
        layout.addView(tvFreq);

        // Spațiu
        View spacer2 = new View(context);
        spacer2.setLayoutParams(new LinearLayout.LayoutParams(15, 1));
        layout.addView(spacer2);

        // Indicativ DX
        TextView tvDx = new TextView(context);
        tvDx.setText(spot.dxCall);
        tvDx.setTextColor(Color.YELLOW);
        tvDx.setTextSize(14);
        tvDx.setTypeface(null, Typeface.BOLD);
        layout.addView(tvDx);

        // Spațiu
        View spacer3 = new View(context);
        spacer3.setLayoutParams(new LinearLayout.LayoutParams(15, 1));
        layout.addView(spacer3);

        // Comentariu
        TextView tvComment = new TextView(context);
        tvComment.setText(spot.comment);
        tvComment.setTextColor(Color.WHITE);
        tvComment.setTextSize(12);
        LinearLayout.LayoutParams pComment = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvComment.setLayoutParams(pComment);
        layout.addView(tvComment);

        // Click pe spot → tunează radio + pregătește Logger
        layout.setOnClickListener(v -> {
            onSpotClicked(spot);
        });

        return layout;
    }

    private void onSpotClicked(Spot spot) {
        // 1. Tunează radio-ul pe frecvența spot-ului
        if (radioManager != null) {
            long freqHz = (long) (spot.frequency * 1_000_000);
            // Folosim strict prefixul FA conform manualului și cerinței
            // Yaesu FA command requires fixed 9 digits (e.g., FA014205700;)
            String catCmdFreq = String.format(java.util.Locale.US, "FA%09d;", freqHz);
            radioManager.trimiteComandaCAT(catCmdFreq);

            // 1b. Sincronizăm modul de lucru
            CatModeInfo modeInfo = mapSpotToCatMode(spot);
            if (modeInfo != null) {
                radioManager.trimiteComandaCAT("MD" + modeInfo.code + ";");
            }

            String msg = String.format(java.util.Locale.US, "Tuning la %.5f MHz", spot.frequency);
            if (modeInfo != null) msg += " (" + modeInfo.label + ")";
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
        }

        // 2. Pregătește Logger-ul cu indicativul DX
        EcranRttyDroid.callsignBuffer = spot.dxCall;
    }

    private static class CatModeInfo {
        final String code;
        final String label;
        CatModeInfo(String code, String label) {
            this.code = code;
            this.label = label;
        }
    }

    private CatModeInfo mapSpotToCatMode(Spot spot) {
        if (spot.mode == null) return null;
        String m = spot.mode.toUpperCase();

        // FTX-1: 03 = CW-U, 07 = CW-L
        if (m.equals("CW")) return new CatModeInfo("03", "CW-U");
        if (m.equals("RTTY")) return new CatModeInfo("06", "RTTY-L");
        
        // Moduri Digitale -> DATA-U
        if (m.equals("FT8") || m.equals("FT4") || m.equals("JS8") || m.equals("PSK")) {
            return new CatModeInfo("0C", "DATA-U");
        }

        // SSB (LSB/USB) - alegem în funcție de frecvență
        if (m.equals("SSB")) {
            if (spot.frequency < 10.0) return new CatModeInfo("01", "LSB");
            else return new CatModeInfo("02", "USB");
        }

        return null;
    }

    /**
     * Returnează culoarea de fundal pentru o bandă.
     */
    private int getCuloareBanda(String banda) {
        switch (banda) {
            case "160m": return Color.parseColor("#4A148C"); // Mov închis
            case "80m": return Color.parseColor("#1A237E");  // Albastru închis
            case "40m": return Color.parseColor("#004D40");  // Verde închis
            case "20m": return Color.parseColor("#E65100");  // Portocaliu
            case "15m": return Color.parseColor("#BF360C");  // Roșu-portocaliu
            case "10m": return Color.parseColor("#B71C1C");  // Roșu
            case "6m": return Color.parseColor("#880E4F");   // Roz închis
            case "2m": return Color.parseColor("#311B92");   // Violet
            default: return Color.parseColor("#424242");     // Gri
        }
    }

    /**
     * Curăță resursele la închiderea aplicației.
     */
    public void eliberareResurse() {
        clusterManager.disconnect();
        spoturi.clear();
    }
}