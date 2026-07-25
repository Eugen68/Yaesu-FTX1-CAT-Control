package com.example.yaesucatcontroladv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
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

        // Separator
        View separator = new View(context);
        separator.setBackgroundColor(Color.GRAY);
        LinearLayout.LayoutParams pSep = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 2);
        pSep.setMargins(0, 10, 0, 10);
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
            listaSpoturi.removeViewAt(listaSpoturi.getChildCount() - 1);
        }

        // Creăm view-ul pentru spot și-l adăugăm la început
        View spotView = creeazaSpotView(spot);
        listaSpoturi.addView(spotView, 0);
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
            String catCmd = String.format(java.util.Locale.US, "FA%09d;", freqHz);
            radioManager.trimiteComandaCAT(catCmd);
            Toast.makeText(context, "Tuning la " + String.format(java.util.Locale.US, "%.5f", spot.frequency) + " MHz",
                    Toast.LENGTH_SHORT).show();
        }

        // 2. Pregătește Logger-ul cu indicativul DX
        EcranRttyDroid.callsignBuffer = spot.dxCall;
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