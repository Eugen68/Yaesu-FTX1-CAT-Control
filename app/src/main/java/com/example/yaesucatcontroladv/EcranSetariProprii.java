package com.example.yaesucatcontroladv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Filter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class EcranSetariProprii {
    // Chei SharedPreferences
    public static final String PREFS_NAME = "yaesu_prefs";
    public static final String PREF_MY_CALL = "my_call";
    public static final String PREF_MY_GRID = "my_grid";
    public static final String PREF_MY_QTH = "my_qth";
    public static final String PREF_MY_ITU_ZONE = "my_itu_zone";
    public static final String PREF_MY_CQ_ZONE = "my_cq_zone";
    public static final String PREF_QRZ_USER = "qrz_user";
    public static final String PREF_QRZ_PASS = "qrz_pass";
    public static final String PREF_HAMQTH_USER = "hamqth_user";
    public static final String PREF_HAMQTH_PASS = "hamqth_pass";
    public static final String PREF_CLUSTER_SERVER = "cluster_server"; // acum stochează "ADRESA:port"
    public static final String PREF_CLUSTER_PORT = "cluster_port"; // păstrat doar pentru compatibilitate/migrare
    public static final String PREF_CLUSTER_LIST = "cluster_list"; // lista (JSON) de servere, fiecare cu userul propriu
    public static final String PREF_CLUSTER_USER = "cluster_user"; // păstrat doar pentru compatibilitate/migrare (fostul user global)
    public static final String PREF_CLUSTER_PASS = "cluster_pass"; // păstrat doar pentru compatibilitate/migrare (fosta parolă globală)
    private static final String PREF_CLUSTER_CRED_MIGRATED = "cluster_cred_migrated_v2";

    // Chei Filtre Cluster
    public static final String PREF_CLUSTER_FILTER_BAND = "cluster_filter_band";
    public static final String PREF_CLUSTER_FILTER_MODE = "cluster_filter_mode";
    public static final String PREF_CLUSTER_FILTER_SEARCH = "cluster_filter_search";

    // Coduri cereri fișiere
    private static final int REQUEST_IMPORT_BACKUP = 501;
    private static final int REQUEST_IMPORT_ADIF = 601;

    private final Context context;
    private ScrollView viewPrincipal;

    // Câmpuri editabile
    private EditText etMyCall, etMyGrid, etMyQth, etMyItuZone, etMyCqZone;
    private EditText etQrzUser, etQrzPass;
    private EditText etHamQthUser, etHamQthPass;
    private AutoCompleteTextView etClusterServer;
    private ArrayAdapter<String> clusterAdapter;
    private final List<String> clusterServerListLive = new ArrayList<>();
    private EditText etClusterUser, etClusterPass;
    // Ține minte adresa serverului așa cum era încărcată/selectată, ca să
    // putem detecta dacă userul a EDITAT adresa (redenumire) sau a introdus
    // una complet nouă.
    private String clusterServerOriginal = "";

    public EcranSetariProprii(Context context) {
        this.context = context;
        construiesteInterfata();
        migreazaParoleLaSecureStorage();
        incarcaSetarile();
    }

    /**
     * Migrează parolele din SharedPreferences în clar către EncryptedSharedPreferences (Secure Storage).
     * După migrare, valorile în clar sunt șterse.
     */
    private void migreazaParoleLaSecureStorage() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        String[] keysToMigrate = {PREF_QRZ_PASS, PREF_HAMQTH_PASS, PREF_CLUSTER_PASS};
        boolean needsApply = false;
        SharedPreferences.Editor editor = prefs.edit();

        for (String key : keysToMigrate) {
            String plainTextPass = prefs.getString(key, null);
            if (plainTextPass != null && !plainTextPass.isEmpty()) {
                SecurePrefsHelper.savePassword(context, key, plainTextPass);
                editor.remove(key);
                needsApply = true;
            }
        }

        if (needsApply) {
            editor.apply();
        }
    }

    /**
     * O intrare din lista de clustere: adresa "ADRESA:port" + username-ul propriu
     * acelui server. Parola NU stă aici (rămâne doar în JSON-ul listei, care e
     * stocare simplă/neîncriptată) - ea e ținută separat, criptat, via
     * SecurePrefsHelper, cu o cheie unică derivată din adresa serverului.
     */
    public static class ClusterEntry {
        public String server;
        public String user;

        public ClusterEntry(String server, String user) {
            this.server = server == null ? "" : server;
            this.user = user == null ? "" : user;
        }
    }

    /**
     * Cheia folosită în Secure Storage pentru parola unui server anume.
     * Publică și statică, ca s-o poată folosi și ClusterManager / BackupHelper.
     */
    public static String parolaKeyPentruServer(String server) {
        return "cluster_pass::" + (server == null ? "" : server.trim().toLowerCase(Locale.US));
    }

    /**
     * Caută username-ul salvat pentru un anumit server din listă. Întoarce
     * șir gol dacă serverul nu e (încă) în listă.
     */
    public static String getUserPentruServer(Context context, String server) {
        if (server == null || server.trim().isEmpty()) return "";
        for (ClusterEntry e : getClusterEntries(context)) {
            if (e.server.equalsIgnoreCase(server.trim())) return e.user;
        }
        return "";
    }

    /**
     * Citește lista de clustere salvate (JSON) din SharedPreferences.
     * Acceptă și formatul vechi (array de string-uri simple, fără user asociat),
     * pentru compatibilitate cu instalările făcute înainte de credențialele
     * per-server. Dacă lista e goală, întoarce un default rezonabil.
     */
    public static List<ClusterEntry> getClusterEntries(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(PREF_CLUSTER_LIST, "");
        List<ClusterEntry> lista = new ArrayList<>();
        if (!json.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    Object item = arr.get(i);
                    if (item instanceof JSONObject) {
                        JSONObject o = (JSONObject) item;
                        lista.add(new ClusterEntry(o.optString("server", ""), o.optString("user", "")));
                    } else {
                        // Format vechi: doar adresa, fără user asociat
                        lista.add(new ClusterEntry(String.valueOf(item), ""));
                    }
                }
            } catch (Exception ignored) {}
        }
        if (lista.isEmpty()) {
            lista.add(new ClusterEntry("dx.w1nr.net:7300", ""));
        }
        return lista;
    }

    private static void saveClusterEntries(Context context, List<ClusterEntry> lista) {
        JSONArray arr = new JSONArray();
        for (ClusterEntry e : lista) {
            JSONObject o = new JSONObject();
            try {
                o.put("server", e.server);
                o.put("user", e.user);
            } catch (Exception ignored) {}
            arr.put(o);
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(PREF_CLUSTER_LIST, arr.toString())
                .apply();
    }

    /**
     * Migrează adresa serverului de cluster din formatul vechi (Server și Port
     * separate) în noul format unic "ADRESA:port", și migrează o singură dată
     * user/parolă globale (versiunea dinaintea credențialelor per-server) în
     * intrarea curentă din listă.
     */
    private void migreazaAdresaCluster() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // 1) Combinăm Server+Port dacă sunt încă separate (versiuni foarte vechi)
        String server = prefs.getString(PREF_CLUSTER_SERVER, "");
        if (!server.isEmpty() && !server.contains(":")) {
            String port = prefs.getString(PREF_CLUSTER_PORT, "7300");
            server = server + ":" + port;
            prefs.edit().putString(PREF_CLUSTER_SERVER, server).apply();
        }

        // 2) Migrăm user/pass globale vechi în intrarea curentă din listă (o singură dată)
        if (!prefs.getBoolean(PREF_CLUSTER_CRED_MIGRATED, false)) {
            String oldUser = prefs.getString(PREF_CLUSTER_USER, "");
            String oldPass = SecurePrefsHelper.getPassword(context, PREF_CLUSTER_PASS, "");

            if (!server.isEmpty() && (!oldUser.isEmpty() || !oldPass.isEmpty())) {
                List<ClusterEntry> entries = getClusterEntries(context);
                boolean gasit = false;
                for (ClusterEntry e : entries) {
                    if (e.server.equalsIgnoreCase(server)) {
                        gasit = true;
                        if (e.user.isEmpty() && !oldUser.isEmpty()) e.user = oldUser;
                        break;
                    }
                }
                if (!gasit) entries.add(0, new ClusterEntry(server, oldUser));
                saveClusterEntries(context, entries);

                if (!oldPass.isEmpty()) {
                    SecurePrefsHelper.savePassword(context, parolaKeyPentruServer(server), oldPass);
                }
            }
            // Curățăm cheile vechi globale ca să nu re-migrăm greșit la un restore ulterior
            prefs.edit().remove(PREF_CLUSTER_USER).putBoolean(PREF_CLUSTER_CRED_MIGRATED, true).apply();
            SecurePrefsHelper.savePassword(context, PREF_CLUSTER_PASS, "");
        }

        refreshClusterAdapter();
    }

    /**
     * Adaugă (sau actualizează, dacă adresa există deja) o intrare în lista de
     * clustere, cu username-ul propriu acelui server, și o mută în capul
     * listei. Așa se "populează de la tastatură" lista - orice adresă nouă
     * salvată rămâne disponibilă și data viitoare, împreună cu user-ul ei.
     */
    private void addOrUpdateClusterEntry(String server, String user) {
        if (server == null || server.trim().isEmpty()) return;
        List<ClusterEntry> lista = getClusterEntries(context);
        lista.removeIf(e -> e.server.equalsIgnoreCase(server));
        lista.add(0, new ClusterEntry(server, user));
        while (lista.size() > 15) {
            lista.remove(lista.size() - 1);
        }
        saveClusterEntries(context, lista);
        refreshClusterAdapter();
    }

    private void refreshClusterAdapter() {
        clusterServerListLive.clear();
        for (ClusterEntry e : getClusterEntries(context)) clusterServerListLive.add(e.server);

        if (clusterAdapter == null) {
            // Adapter custom care NU filtrează sugestiile după textul deja scris
            // în câmp (comportamentul default al AutoCompleteTextView) - vrem ca
            // săgeata să arate mereu lista COMPLETĂ, indiferent ce e în câmp.
            clusterAdapter = new ArrayAdapter<String>(
                    context, android.R.layout.simple_dropdown_item_1line, clusterServerListLive) {
                @Override
                public Filter getFilter() {
                    return new Filter() {
                        @Override
                        protected FilterResults performFiltering(CharSequence constraint) {
                            FilterResults results = new FilterResults();
                            results.values = clusterServerListLive;
                            results.count = clusterServerListLive.size();
                            return results;
                        }

                        @Override
                        protected void publishResults(CharSequence constraint, FilterResults results) {
                            notifyDataSetChanged();
                        }
                    };
                }
            };
            etClusterServer.setAdapter(clusterAdapter);
        } else {
            clusterAdapter.notifyDataSetChanged();
        }
    }

    /**
     * Completează câmpurile Username/Password cu credențialele salvate pentru
     * serverul dat (sau le golește, dacă serverul e nou și n-are încă nimic
     * salvat).
     */
    private void incarcaCredentialePentruServer(String server) {
        etClusterUser.setText(getUserPentruServer(context, server));
        etClusterPass.setText(SecurePrefsHelper.getPassword(context, parolaKeyPentruServer(server), ""));
    }

    /**
     * Șterge (cu confirmare) serverul aflat acum în câmp din lista dropdown,
     * împreună cu user/parola salvate pentru el. Dacă era chiar serverul activ,
     * golim și câmpurile, ca userul să aleagă/scrie altul.
     */
    private void stergeServerulCurent() {
        String server = etClusterServer.getText().toString().trim();
        if (server.isEmpty()) {
            Toast.makeText(context, "No server selected", Toast.LENGTH_SHORT).show();
            return;
        }

        List<ClusterEntry> lista = getClusterEntries(context);
        boolean exista = false;
        for (ClusterEntry e : lista) {
            if (e.server.equalsIgnoreCase(server)) { exista = true; break; }
        }
        if (!exista) {
            Toast.makeText(context, "\"" + server + "\" is not saved in the list", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(context)
                .setTitle("Remove Server")
                .setMessage("Remove \"" + server + "\" from the list?\nIts saved username/password for this server will also be deleted.")
                .setPositiveButton("REMOVE", (dialog, which) -> {
                    List<ClusterEntry> actualizata = getClusterEntries(context);
                    actualizata.removeIf(e -> e.server.equalsIgnoreCase(server));
                    saveClusterEntries(context, actualizata);
                    SecurePrefsHelper.savePassword(context, parolaKeyPentruServer(server), "");
                    refreshClusterAdapter();

                    String serverActiv = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .getString(PREF_CLUSTER_SERVER, "");
                    if (server.equalsIgnoreCase(serverActiv)) {
                        etClusterServer.setText("");
                        etClusterUser.setText("");
                        etClusterPass.setText("");
                        clusterServerOriginal = "";
                    }
                    Toast.makeText(context, "Server removed from list", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    public ScrollView getView() {
        return viewPrincipal;
    }

    private void construiesteInterfata() {
        viewPrincipal = new ScrollView(context);
        viewPrincipal.setBackgroundColor(Color.parseColor("#121212"));

        LinearLayout llMain = new LinearLayout(context);
        llMain.setOrientation(LinearLayout.VERTICAL);
        llMain.setPadding(30, 30, 30, 30);

        // Titlu secțiune
        TextView tvTitlu = new TextView(context);
        tvTitlu.setText("MY SETTINGS & DATA");
        tvTitlu.setTextColor(Color.WHITE);
        tvTitlu.setTextSize(18);
        tvTitlu.setTypeface(null, Typeface.BOLD);
        tvTitlu.setPadding(0, 0, 0, 20);
        llMain.addView(tvTitlu);

        // --- CÂMPURI SETĂRI PROPRII ---
        etMyCall = creeazaCampEditabil("My Callsign (MY CALL)", false);
        llMain.addView(etMyCall);

        etMyGrid = creeazaCampEditabil("My Locator (MY GRID)", true);
        llMain.addView(etMyGrid);

        etMyQth = creeazaCampEditabil("City / QTH", false);
        llMain.addView(etMyQth);

        LinearLayout rAndZone = new LinearLayout(context);
        rAndZone.setOrientation(LinearLayout.HORIZONTAL);
        etMyItuZone = creeazaCampEditabilPondere("ITU Zone", false);
        etMyCqZone = creeazaCampEditabilPondere("CQ Zone", false);
        rAndZone.addView(etMyItuZone);
        rAndZone.addView(creeazaSpatiuOrizontal());
        rAndZone.addView(etMyCqZone);
        llMain.addView(rAndZone);

        // Separator vizual
        View separator1 = new View(context);
        separator1.setBackgroundColor(Color.GRAY);
        LinearLayout.LayoutParams pSep1 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 2);
        pSep1.setMargins(0, 20, 0, 20);
        separator1.setLayoutParams(pSep1);
        llMain.addView(separator1);

        // --- SETĂRI QRZ.COM ---
        TextView tvQrzTitlu = new TextView(context);
        tvQrzTitlu.setText("QRZ.com Lookup Credentials");
        tvQrzTitlu.setTextColor(Color.parseColor("#AAAAAA"));
        tvQrzTitlu.setTextSize(14);
        tvQrzTitlu.setPadding(0, 0, 0, 10);
        llMain.addView(tvQrzTitlu);

        etQrzUser = creeazaCampEditabil("Username QRZ.com", false);
        llMain.addView(etQrzUser);

        etQrzPass = creeazaCampEditabil("Password QRZ.com", false);
        etQrzPass.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        llMain.addView(etQrzPass);

        // BUTON TESTARE QRZ.COM
        Button btnTestQrz = new Button(context);
        btnTestQrz.setText("🔍 TEST QRZ.COM CONNECTION");
        btnTestQrz.setBackgroundColor(Color.parseColor("#5D4037"));
        btnTestQrz.setTextColor(Color.WHITE);
        btnTestQrz.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams pBtnTest = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 110);
        pBtnTest.setMargins(0, 10, 0, 15);
        btnTestQrz.setLayoutParams(pBtnTest);
        btnTestQrz.setOnClickListener(v -> testeazaConexiuneQrz());
        llMain.addView(btnTestQrz);

        // Separator vizual + secțiune HamQTH
        View separatorHam = new View(context);
        separatorHam.setBackgroundColor(Color.GRAY);
        LinearLayout.LayoutParams pSepHam = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        pSepHam.setMargins(0, 15, 0, 15);
        separatorHam.setLayoutParams(pSepHam);
        llMain.addView(separatorHam);

        TextView tvHamTitlu = new TextView(context);
        tvHamTitlu.setText("HamQTH.com (FREE - for Grid)");
        tvHamTitlu.setTextColor(Color.parseColor("#AAAAAA"));
        tvHamTitlu.setTextSize(14);
        tvHamTitlu.setPadding(0, 0, 0, 10);
        llMain.addView(tvHamTitlu);

        etHamQthUser = creeazaCampEditabil("Username HamQTH.com", false);
        llMain.addView(etHamQthUser);

        etHamQthPass = creeazaCampEditabil("Password HamQTH.com", false);
        etHamQthPass.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        llMain.addView(etHamQthPass);

        Button btnTestHamQth = new Button(context);
        btnTestHamQth.setText("🔍 TEST HAMQTH CONNECTION");
        btnTestHamQth.setBackgroundColor(Color.parseColor("#0277BD"));
        btnTestHamQth.setTextColor(Color.WHITE);
        btnTestHamQth.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams pBtnTestHam = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 110);
        pBtnTestHam.setMargins(0, 10, 0, 15);
        btnTestHamQth.setLayoutParams(pBtnTestHam);
        btnTestHamQth.setOnClickListener(v -> testeazaConexiuneHamQth());
        llMain.addView(btnTestHamQth);

        // --- DX CLUSTER SETTINGS ---
        TextView tvClusterTitlu = new TextView(context);
        tvClusterTitlu.setText("DX Cluster Settings");
        tvClusterTitlu.setTextColor(Color.parseColor("#AAAAAA"));
        tvClusterTitlu.setTextSize(14);
        tvClusterTitlu.setPadding(0, 20, 0, 10);
        llMain.addView(tvClusterTitlu);

        // Câmp dropdown editabil: arată lista de clustere salvate, dar permite
        // și scrierea directă a unei adrese noi de la tastatură (format ADRESA:port).
        LinearLayout rowClusterServer = new LinearLayout(context);
        rowClusterServer.setOrientation(LinearLayout.HORIZONTAL);
        rowClusterServer.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams pRowClusterServer = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pRowClusterServer.setMargins(0, 0, 0, 12);
        rowClusterServer.setLayoutParams(pRowClusterServer);

        etClusterServer = new AutoCompleteTextView(context);
        etClusterServer.setHint("Server:Port (ex: dx.w1nr.net:7300)");
        etClusterServer.setHintTextColor(Color.GRAY);
        etClusterServer.setTextColor(Color.WHITE);
        etClusterServer.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        etClusterServer.setThreshold(0); // arată sugestii chiar dacă câmpul e gol
        LinearLayout.LayoutParams pClusterServer = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        etClusterServer.setLayoutParams(pClusterServer);
        etClusterServer.setOnClickListener(v -> etClusterServer.showDropDown());
        etClusterServer.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) etClusterServer.showDropDown();
        });
        // Când alegi un server din listă, îi încărcăm automat user/pass proprii
        etClusterServer.setOnItemClickListener((parent, view, position, id) -> {
            String server = (String) parent.getItemAtPosition(position);
            etClusterServer.setText(server);
            etClusterServer.setSelection(server.length());
            incarcaCredentialePentruServer(server);
            clusterServerOriginal = server;
        });
        rowClusterServer.addView(etClusterServer);

        // Săgeată vizibilă "▼" - clar indică faptul că e listă derulantă, la
        // fel ca un Spinner obișnuit; apăsată, deschide lista de clustere salvate.
        TextView tvSageataDropdown = new TextView(context);
        tvSageataDropdown.setText("▼");
        tvSageataDropdown.setTextColor(Color.parseColor("#90A4AE"));
        tvSageataDropdown.setTextSize(16);
        tvSageataDropdown.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams pSageata = new LinearLayout.LayoutParams(90, 90);
        pSageata.setMargins(8, 0, 0, 0);
        tvSageataDropdown.setLayoutParams(pSageata);
        tvSageataDropdown.setBackgroundColor(Color.parseColor("#2A2A2A"));
        tvSageataDropdown.setOnClickListener(v -> {
            etClusterServer.requestFocus();
            etClusterServer.showDropDown();
        });
        rowClusterServer.addView(tvSageataDropdown);

        llMain.addView(rowClusterServer);

        // Buton mic pentru ștergerea serverului curent din listă (cu confirmare)
        Button btnRemoveCluster = new Button(context);
        btnRemoveCluster.setText("🗑 REMOVE THIS SERVER FROM LIST");
        btnRemoveCluster.setBackgroundColor(Color.parseColor("#4E2020"));
        btnRemoveCluster.setTextColor(Color.parseColor("#EF9A9A"));
        LinearLayout.LayoutParams pBtnRemoveCluster = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 110);
        pBtnRemoveCluster.setMargins(0, 0, 0, 15);
        btnRemoveCluster.setLayoutParams(pBtnRemoveCluster);
        btnRemoveCluster.setOnClickListener(v -> stergeServerulCurent());
        llMain.addView(btnRemoveCluster);

        etClusterUser = creeazaCampEditabil("Username (optional)", false);
        llMain.addView(etClusterUser);

        etClusterPass = creeazaCampEditabil("Password (optional)", false);
        etClusterPass.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        llMain.addView(etClusterPass);

        Button btnSave = new Button(context);
        btnSave.setText("💾 SAVE SETTINGS");
        btnSave.setBackgroundColor(Color.parseColor("#FF6F00"));
        btnSave.setTextColor(Color.WHITE);
        btnSave.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams pBtnSave = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 130);
        pBtnSave.setMargins(0, 15, 0, 25);
        btnSave.setLayoutParams(pBtnSave);
        btnSave.setOnClickListener(v -> salveazaSetarile());
        llMain.addView(btnSave);

        // Separator vizual
        View separator2 = new View(context);
        separator2.setBackgroundColor(Color.GRAY);
        LinearLayout.LayoutParams pSep2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 2);
        pSep2.setMargins(0, 10, 0, 20);
        separator2.setLayoutParams(pSep2);
        llMain.addView(separator2);

        // ============================================================
        // LINIA 1: BACKUP / RESTORE SETĂRI + MACRO (JSON)
        // ============================================================
        TextView tvBackupLabel = new TextView(context);
        tvBackupLabel.setText("App & RTTY Macros");
        tvBackupLabel.setTextColor(Color.parseColor("#AAAAAA"));
        tvBackupLabel.setTextSize(12);
        tvBackupLabel.setPadding(0, 0, 0, 5);
        llMain.addView(tvBackupLabel);

        LinearLayout rAndBackup = new LinearLayout(context);
        rAndBackup.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams pBtnBackup = new LinearLayout.LayoutParams(0, 120, 1f);
        pBtnBackup.setMargins(4, 0, 4, 10);

        Button btnExportBackup = new Button(context);
        btnExportBackup.setText("EXPORT BACKUP");
        btnExportBackup.setBackgroundColor(Color.parseColor("#2E7D32"));
        btnExportBackup.setTextColor(Color.WHITE);
        btnExportBackup.setTextSize(11);
        btnExportBackup.setLayoutParams(pBtnBackup);
        btnExportBackup.setOnClickListener(v -> executaExportBackup());

        Button btnImportBackup = new Button(context);
        btnImportBackup.setText("IMPORT BACKUP");
        btnImportBackup.setBackgroundColor(Color.parseColor("#00695C"));
        btnImportBackup.setTextColor(Color.WHITE);
        btnImportBackup.setTextSize(11);
        btnImportBackup.setLayoutParams(pBtnBackup);
        btnImportBackup.setOnClickListener(v -> executaImportBackup());

        rAndBackup.addView(btnExportBackup);
        rAndBackup.addView(btnImportBackup);
        llMain.addView(rAndBackup);

        // ============================================================
        // LINIA 2: EXPORT / IMPORT LOG (ADIF)
        // ============================================================
        TextView tvAdifLabel = new TextView(context);
        tvAdifLabel.setText("QSO Log (ADIF Format)");
        tvAdifLabel.setTextColor(Color.parseColor("#AAAAAA"));
        tvAdifLabel.setTextSize(12);
        tvAdifLabel.setPadding(0, 5, 0, 5);
        llMain.addView(tvAdifLabel);

        LinearLayout rAndAdif = new LinearLayout(context);
        rAndAdif.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams pBtnAdif = new LinearLayout.LayoutParams(0, 120, 1f);
        pBtnAdif.setMargins(4, 0, 4, 15);

        Button btnExportAdif = new Button(context);
        btnExportAdif.setText("EXPORT ADIF LOG");
        btnExportAdif.setBackgroundColor(Color.parseColor("#1565C0"));
        btnExportAdif.setTextColor(Color.WHITE);
        btnExportAdif.setTextSize(11);
        btnExportAdif.setLayoutParams(pBtnAdif);
        btnExportAdif.setOnClickListener(v -> executaExportAdif());

        Button btnImportAdif = new Button(context);
        btnImportAdif.setText("IMPORT ADIF LOG");
        btnImportAdif.setBackgroundColor(Color.parseColor("#4527A0"));
        btnImportAdif.setTextColor(Color.WHITE);
        btnImportAdif.setTextSize(11);
        btnImportAdif.setLayoutParams(pBtnAdif);
        btnImportAdif.setOnClickListener(v -> executaImportAdif());

        rAndAdif.addView(btnExportAdif);
        rAndAdif.addView(btnImportAdif);
        llMain.addView(rAndAdif);

        // ============================================================
        // LINIA 3: SHARE ADIF LOG (EMAIL / CLOUD)
        // ============================================================
        Button btnShareAdif = new Button(context);
        btnShareAdif.setText("✉️ SHARE ADIF LOG (EMAIL/CLOUD)");
        btnShareAdif.setBackgroundColor(Color.parseColor("#1565C0"));
        btnShareAdif.setTextColor(Color.WHITE);
        btnShareAdif.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams pBtnShare = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 130);
        pBtnShare.setMargins(4, 5, 4, 10);
        btnShareAdif.setLayoutParams(pBtnShare);
        btnShareAdif.setOnClickListener(v -> executaShareAdif());
        llMain.addView(btnShareAdif);

        viewPrincipal.addView(llMain);
    }

    // ============================================================
    // SHARE ADIF LOG LOGIC
    // ============================================================
    private void executaShareAdif() {
        new Thread(() -> {
            try {
                Uri uri = AdifExporter.getShareableAdifUri(context, new QsoDbHelper(context));

                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_STREAM, uri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                ((Activity) context).runOnUiThread(() -> {
                    context.startActivity(Intent.createChooser(intent, "Share Log via..."));
                });
            } catch (Exception e) {
                ((Activity) context).runOnUiThread(() ->
                        Toast.makeText(context, "Error sharing log: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ============================================================
    // METODE HELPER UI
    // ============================================================
    private EditText creeazaCampEditabil(String hint, boolean uppercase) {
        EditText et = new EditText(context);
        et.setHint(hint);
        et.setHintTextColor(Color.GRAY);
        et.setTextColor(Color.WHITE);
        if (uppercase) {
            et.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        } else {
            et.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        }
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, 0, 12);
        et.setLayoutParams(p);
        return et;
    }

    private EditText creeazaCampEditabilPondere(String hint, boolean uppercase) {
        EditText et = new EditText(context);
        et.setHint(hint);
        et.setHintTextColor(Color.GRAY);
        et.setTextColor(Color.WHITE);
        if (uppercase) {
            et.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        } else {
            et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        }
        et.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return et;
    }

    private View creeazaSpatiuOrizontal() {
        View v = new View(context);
        v.setLayoutParams(new LinearLayout.LayoutParams(15, 1));
        return v;
    }

    // ============================================================
    // SALVARE / ÎNCĂRCARE SETĂRI
    // ============================================================
    private void salveazaSetarile() {
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putString(PREF_MY_CALL, etMyCall.getText().toString().trim().toUpperCase());
        editor.putString(PREF_MY_GRID, etMyGrid.getText().toString().trim().toUpperCase());
        editor.putString(PREF_MY_QTH, etMyQth.getText().toString().trim());
        editor.putString(PREF_MY_ITU_ZONE, etMyItuZone.getText().toString().trim());
        editor.putString(PREF_MY_CQ_ZONE, etMyCqZone.getText().toString().trim());
        editor.putString(PREF_QRZ_USER, etQrzUser.getText().toString().trim());

        // Cifrare parole
        SecurePrefsHelper.savePassword(context, PREF_QRZ_PASS, etQrzPass.getText().toString().trim());
        SecurePrefsHelper.savePassword(context, PREF_HAMQTH_PASS, etHamQthPass.getText().toString().trim());

        editor.putString(PREF_HAMQTH_USER, etHamQthUser.getText().toString().trim());
        editor.apply();

        String clusterEntry = etClusterServer.getText().toString().trim();
        String clusterUser = etClusterUser.getText().toString().trim();
        String clusterPass = etClusterPass.getText().toString().trim();
        proceseazaSalvareCluster(clusterEntry, clusterUser, clusterPass);

        QrzLookupHelper.resetSesiune();
        HamQthLookupHelper.resetSesiune();
        Toast.makeText(context, "Settings saved!", Toast.LENGTH_SHORT).show();
    }

    /**
     * Decide dacă adresa din câmp e o EDITARE a serverului care era încărcat
     * (ex: userul a corectat un typo în adresă/port) sau pur și simplu un
     * server nou introdus de la tastatură. Dacă e o editare, întreabă userul
     * ce vrea: să actualizeze intrarea existentă (păstrând user/parola) sau
     * s-o salveze ca server separat, nou.
     */
    private void proceseazaSalvareCluster(String clusterEntry, String clusterUser, String clusterPass) {
        SharedPreferences prefsCluster = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String serverVechi = prefsCluster.getString(PREF_CLUSTER_SERVER, "");

        prefsCluster.edit().putString(PREF_CLUSTER_SERVER, clusterEntry).apply();

        // Daca eram conectati la un alt server si tocmai am schimbat serverul
        // activ, reconectam automat la noul server, ca userul sa nu trebuiasca
        // sa apese manual Disconnect + Connect.
        if (!clusterEntry.isEmpty()
                && !clusterEntry.equalsIgnoreCase(serverVechi)
                && ClusterRepository.getInstance().isConnected()) {
            reconecteazaLaServerulNou();
        }

        boolean esteEditareAdresa = !clusterServerOriginal.isEmpty()
                && !clusterServerOriginal.equalsIgnoreCase(clusterEntry)
                && existaInLista(clusterServerOriginal);

        if (esteEditareAdresa) {
            new AlertDialog.Builder(context)
                    .setTitle("Server address changed")
                    .setMessage("You changed \"" + clusterServerOriginal + "\" to \"" + clusterEntry
                            + "\".\n\nUpdate this saved server (keeps its username/password), "
                            + "or save it as a new, separate server?")
                    .setPositiveButton("UPDATE THIS SERVER", (d, w) ->
                            redenumesteServer(clusterServerOriginal, clusterEntry, clusterUser, clusterPass))
                    .setNegativeButton("SAVE AS NEW", (d, w) ->
                            salveazaCaServerNou(clusterEntry, clusterUser, clusterPass))
                    .setCancelable(false)
                    .show();
        } else {
            salveazaCaServerNou(clusterEntry, clusterUser, clusterPass);
        }
    }

    /**
     * Opreste ClusterService (deconectare de la serverul vechi) si il
     * repornește imediat (conectare la noul server, ale carui setari au
     * fost deja salvate mai sus). ClusterService.onCreate() creeaza un
     * ClusterManager nou, care reincarca setarile din SharedPreferences la
     * connect() - deci va folosi automat noul server.
     *
     * Folosim un delay scurt intre stop si start, ca sa dam timp lui
     * onDestroy()/performFullCleanup() sa proceseze deconectarea inainte sa
     * pornim din nou serviciul (evitam suprapunerea a doua cicluri de
     * viata ale serviciului).
     */
    private void reconecteazaLaServerulNou() {
        Intent intent = new Intent(context, ClusterService.class);
        context.stopService(intent);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        }, 400);

        Toast.makeText(context, "Reconnecting to new cluster server\u2026", Toast.LENGTH_SHORT).show();
    }

    private boolean existaInLista(String server) {
        for (ClusterEntry e : getClusterEntries(context)) {
            if (e.server.equalsIgnoreCase(server)) return true;
        }
        return false;
    }

    private void salveazaCaServerNou(String clusterEntry, String clusterUser, String clusterPass) {
        addOrUpdateClusterEntry(clusterEntry, clusterUser);
        SecurePrefsHelper.savePassword(context, parolaKeyPentruServer(clusterEntry), clusterPass);
        clusterServerOriginal = clusterEntry;
    }

    /**
     * Redenumește intrarea din listă: scoate adresa veche, adaugă adresa nouă
     * cu același user, și mută parola de pe cheia veche pe cea nouă.
     */
    private void redenumesteServer(String vechi, String nou, String user, String pass) {
        List<ClusterEntry> lista = getClusterEntries(context);
        lista.removeIf(e -> e.server.equalsIgnoreCase(vechi));
        lista.removeIf(e -> e.server.equalsIgnoreCase(nou)); // în caz că "nou" exista deja ca intrare separată
        lista.add(0, new ClusterEntry(nou, user));
        while (lista.size() > 15) {
            lista.remove(lista.size() - 1);
        }
        saveClusterEntries(context, lista);

        SecurePrefsHelper.savePassword(context, parolaKeyPentruServer(nou), pass);
        SecurePrefsHelper.savePassword(context, parolaKeyPentruServer(vechi), "");

        refreshClusterAdapter();
        clusterServerOriginal = nou;
        Toast.makeText(context, "Server renamed to \"" + nou + "\"", Toast.LENGTH_SHORT).show();
    }

    private void incarcaSetarile() {
        migreazaAdresaCluster(); // rulează și la import de backup vechi, nu doar la prima pornire
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        etMyCall.setText(prefs.getString(PREF_MY_CALL, ""));
        etMyGrid.setText(prefs.getString(PREF_MY_GRID, ""));
        etMyQth.setText(prefs.getString(PREF_MY_QTH, ""));
        etMyItuZone.setText(prefs.getString(PREF_MY_ITU_ZONE, ""));
        etMyCqZone.setText(prefs.getString(PREF_MY_CQ_ZONE, ""));
        etQrzUser.setText(prefs.getString(PREF_QRZ_USER, ""));

        // Citire din Secure Storage
        etQrzPass.setText(SecurePrefsHelper.getPassword(context, PREF_QRZ_PASS, ""));
        etHamQthPass.setText(SecurePrefsHelper.getPassword(context, PREF_HAMQTH_PASS, ""));

        etHamQthUser.setText(prefs.getString(PREF_HAMQTH_USER, ""));
        refreshClusterAdapter();
        String serverCurent = prefs.getString(PREF_CLUSTER_SERVER, "dx.w1nr.net:7300");
        etClusterServer.setText(serverCurent);
        // Fiecare server din listă are propriul user/pass
        incarcaCredentialePentruServer(serverCurent);
        clusterServerOriginal = serverCurent;
    }

    // ============================================================
    // TESTARE CONEXIUNE QRZ.COM
    // ============================================================
    private void testeazaConexiuneQrz() {
        String user = etQrzUser.getText().toString().trim();
        String pass = etQrzPass.getText().toString().trim();

        if (user.isEmpty() || pass.isEmpty()) {
            Toast.makeText(context, "⚠️ Complete the username and password first!", Toast.LENGTH_SHORT).show();
            return;
        }

        String tempCall = etMyCall.getText().toString().trim().toUpperCase();
        final String callsignDeTest = tempCall.isEmpty() ? "W1AW" : tempCall;

        Toast.makeText(context, "🔄 Testing connection...\n(Search: " + callsignDeTest + ")", Toast.LENGTH_SHORT).show();

        QrzLookupHelper.cautaIndicativ(user, pass, callsignDeTest, new QrzLookupHelper.QrzCallback() {
            @Override
            public void onSucces(QrzLookupHelper.QrzRezultat r) {
                ((Activity) context).runOnUiThread(() -> {
                    String msg = "✅ QRZ.com connection successful!\n";
                    if (r != null && r.call != null) {
                        msg += "Data retrieved for: " + r.call;
                        if (r.name != null && !r.name.isEmpty()) {
                            msg += " (" + r.name + ")";
                        }
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onEroare(String mesaj) {
                ((Activity) context).runOnUiThread(() -> {
                    String mesajAfisat;
                    String mesajMic = (mesaj != null) ? mesaj.toLowerCase() : "";

                    if (mesajMic.contains("invalid") || mesajMic.contains("password") || mesajMic.contains("session") || mesajMic.contains("subscriber")) {
                        mesajAfisat = "❌ Invalid credentials or missing XML subscription!\n" + mesaj;
                    }
                    else if (mesajMic.contains("no match") || mesajMic.contains("not found")) {
                        mesajAfisat = "✅ QRZ.com connection successful!\n(Valid credentials, although the test callsign was not found in the database).";
                    }
                    else {
                        mesajAfisat = "⚠️ Network or server error:\n" + mesaj;
                    }
                    Toast.makeText(context, mesajAfisat, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // ============================================================
    // TESTARE CONEXIUNE HAMQTH
    // ============================================================
    private void testeazaConexiuneHamQth() {
        String user = etHamQthUser.getText().toString().trim();
        String pass = etHamQthPass.getText().toString().trim();

        if (user.isEmpty() || pass.isEmpty()) {
            Toast.makeText(context, "⚠️ Complete HamQTH user/password!", Toast.LENGTH_SHORT).show();
            return;
        }

        String tempCall = etMyCall.getText().toString().trim().toUpperCase();
        final String callsignDeTest = tempCall.isEmpty() ? "W1AW" : tempCall;

        Toast.makeText(context, "🔄 Testing HamQTH.com...", Toast.LENGTH_SHORT).show();

        HamQthLookupHelper.cautaIndicativ(user, pass, callsignDeTest, new HamQthLookupHelper.HamQthCallback() {
            @Override
            public void onSucces(HamQthLookupHelper.HamQthRezultat r) {
                ((Activity) context).runOnUiThread(() -> {
                    String msg = "✅ HamQTH connection successful!\n";
                    if (r != null && r.grid != null && !r.grid.isEmpty()) {
                        msg += "Grid " + callsignDeTest + ": " + r.grid;
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onEroare(String mesaj) {
                ((Activity) context).runOnUiThread(() ->
                        Toast.makeText(context, "❌ HamQTH error:\n" + mesaj, Toast.LENGTH_LONG).show());
            }
        });
    }

    // ============================================================
    // EXPORT / IMPORT BACKUP (JSON)
    // ============================================================
    private void executaExportBackup() {
        final EditText etPass = new EditText(context);
        etPass.setHint("Backup Password (Optional)");
        etPass.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);

        new AlertDialog.Builder(context)
                .setTitle("Secure Backup")
                .setMessage("Choose a password to protect your credentials in the backup file. If left empty, passwords will NOT be included.")
                .setView(etPass)
                .setPositiveButton("EXPORT", (dialog, which) -> {
                    String password = etPass.getText().toString().trim();
                    iniciaExport(password);
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void iniciaExport(String backupPassword) {
        new Thread(() -> {
            try {
                String nume = BackupHelper.exporta(context, new QsoDbHelper(context), backupPassword);
                ((Activity) context).runOnUiThread(() ->
                        Toast.makeText(context, "Backup exported:\n" + nume, Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                ((Activity) context).runOnUiThread(() ->
                        Toast.makeText(context, "Error exporting backup:\n" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void executaImportBackup() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        ((Activity) context).startActivityForResult(intent, REQUEST_IMPORT_BACKUP);
    }

    // ============================================================
    // EXPORT / IMPORT ADIF
    // ============================================================
    private void executaExportAdif() {
        new Thread(() -> {
            try {
                String nume = AdifExporter.exportaAdif(context, new QsoDbHelper(context));
                ((Activity) context).runOnUiThread(() ->
                        Toast.makeText(context, "ADIF Log exported:\n" + nume, Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                ((Activity) context).runOnUiThread(() ->
                        Toast.makeText(context, "Error exporting ADIF:\n" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void executaImportAdif() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        ((Activity) context).startActivityForResult(intent, REQUEST_IMPORT_ADIF);
    }

    // ============================================================
    // HANDLER REZULTATE FIȘIERE (Apelat din Activity principală)
    // ============================================================
    public void handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;

        if (requestCode == REQUEST_IMPORT_BACKUP) {
            // Verificăm dacă fișierul este cifrat citind doar o parte din el sau tot header-ul
            try {
                String continut = citesteContinutUri(uri);
                JSONObject radacina = new JSONObject(continut);
                boolean isCifrat = radacina.optBoolean("cifrat", false);

                if (isCifrat) {
                    solicitaParolaImport(uri);
                } else {
                    iniciaImport(uri, null);
                }
            } catch (Exception e) {
                Toast.makeText(context, "Error parsing backup file", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQUEST_IMPORT_ADIF) {
            new Thread(() -> {
                try {
                    AdifExporter.RezultatImportAdif rez = AdifExporter.importaAdif(context, new QsoDbHelper(context), uri);
                    ((Activity) context).runOnUiThread(() -> {
                        if (!rez.validFormat) {
                            Toast.makeText(context,
                                    "⚠️ Invalid file!\nImport only accepts ADIF files exported by YaesuCatControlADV.",
                                    Toast.LENGTH_LONG).show();
                        } else {
                            String msg = String.format("ADIF Import completed:\n✅ %d new QSOs\n⏭️ %d duplicates ignored",
                                    rez.qsoImportate, rez.qsoDuplicate);
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (Exception e) {
                    ((Activity) context).runOnUiThread(() ->
                            Toast.makeText(context, "Error importing ADIF:\n" + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
        }
    }

    private void solicitaParolaImport(Uri uri) {
        final EditText etPass = new EditText(context);
        etPass.setHint("Backup Password");
        etPass.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);

        new AlertDialog.Builder(context)
                .setTitle("Encrypted Backup Detected")
                .setMessage("Please enter the password used to protect this backup file.")
                .setView(etPass)
                .setPositiveButton("RESTORE", (dialog, which) -> {
                    String password = etPass.getText().toString().trim();
                    iniciaImport(uri, password);
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void iniciaImport(Uri uri, String password) {
        new Thread(() -> {
            try {
                BackupHelper.RezultatImport rez = BackupHelper.importa(context, new QsoDbHelper(context), uri, password);
                ((Activity) context).runOnUiThread(() -> {
                    String msg = String.format("Import completed:\n%d settings\n%d macros\n%d QSOs",
                            rez.setariRestaurate ? 1 : 0, rez.macroRestaurate, rez.qsoImportate);
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
                    if (rez.setariRestaurate) incarcaSetarile();
                });
            } catch (Exception e) {
                ((Activity) context).runOnUiThread(() ->
                        Toast.makeText(context, "Error importing backup:\n" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private String citesteContinutUri(Uri uri) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return "";
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) {
                sb.append(new String(buffer, 0, n, java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return sb.toString();
    }
}