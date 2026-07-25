package com.example.yaesucatcontroladv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
    public static final String PREF_CLUSTER_SERVER = "cluster_server";
    public static final String PREF_CLUSTER_PORT = "cluster_port";
    public static final String PREF_CLUSTER_USER = "cluster_user";
    public static final String PREF_CLUSTER_PASS = "cluster_pass";

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
    private EditText etClusterServer, etClusterPort, etClusterUser, etClusterPass;

    public EcranSetariProprii(Context context) {
        this.context = context;
        construiesteInterfata();
        incarcaSetarile();
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

        etClusterServer = creeazaCampEditabil("Server (ex: dx.w1nr.net)", false);
        llMain.addView(etClusterServer);

        etClusterPort = creeazaCampEditabil("Port (ex: 7300 or 23)", false);
        etClusterPort.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        llMain.addView(etClusterPort);

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

        viewPrincipal.addView(llMain);
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
        editor.putString(PREF_QRZ_PASS, etQrzPass.getText().toString().trim());
        editor.putString(PREF_HAMQTH_USER, etHamQthUser.getText().toString().trim());
        editor.putString(PREF_HAMQTH_PASS, etHamQthPass.getText().toString().trim());
        editor.putString(PREF_CLUSTER_SERVER, etClusterServer.getText().toString().trim());
        editor.putString(PREF_CLUSTER_PORT, etClusterPort.getText().toString().trim());
        editor.putString(PREF_CLUSTER_USER, etClusterUser.getText().toString().trim());
        editor.putString(PREF_CLUSTER_PASS, etClusterPass.getText().toString().trim());
        editor.apply();
        QrzLookupHelper.resetSesiune();
        HamQthLookupHelper.resetSesiune();
        Toast.makeText(context, "Settings saved!", Toast.LENGTH_SHORT).show();
    }

    private void incarcaSetarile() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        etMyCall.setText(prefs.getString(PREF_MY_CALL, ""));
        etMyGrid.setText(prefs.getString(PREF_MY_GRID, ""));
        etMyQth.setText(prefs.getString(PREF_MY_QTH, ""));
        etMyItuZone.setText(prefs.getString(PREF_MY_ITU_ZONE, ""));
        etMyCqZone.setText(prefs.getString(PREF_MY_CQ_ZONE, ""));
        etQrzUser.setText(prefs.getString(PREF_QRZ_USER, ""));
        etQrzPass.setText(prefs.getString(PREF_QRZ_PASS, ""));
        etHamQthUser.setText(prefs.getString(PREF_HAMQTH_USER, ""));
        etHamQthPass.setText(prefs.getString(PREF_HAMQTH_PASS, ""));
        etClusterServer.setText(prefs.getString(PREF_CLUSTER_SERVER, "dx.w1nr.net"));
        etClusterPort.setText(prefs.getString(PREF_CLUSTER_PORT, "7300"));
        etClusterUser.setText(prefs.getString(PREF_CLUSTER_USER, ""));
        etClusterPass.setText(prefs.getString(PREF_CLUSTER_PASS, ""));
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
        new Thread(() -> {
            try {
                String nume = BackupHelper.exporta(context, new QsoDbHelper(context));
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
            new Thread(() -> {
                try {
                    BackupHelper.RezultatImport rez = BackupHelper.importa(context, new QsoDbHelper(context), uri);
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
}