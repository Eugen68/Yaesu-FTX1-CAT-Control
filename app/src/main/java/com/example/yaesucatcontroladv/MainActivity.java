package com.example.yaesucatcontroladv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

public class MainActivity extends AppCompatActivity {

    private static final String ACTION_USB_PERMISSION = "com.example.yaesucatcontroladv.USB_PERMISSION";

    // --- LOGICA DE BACKEND ȘI COMANDE MODULARĂ ---
    private RadioManager radioManager;
    private EcranComenzi managerEcranComenzi;
    private EcranRttyDroid managerEcranRtty;
    private EcranSetari managerEcranSetari;
    private EcranEditorMacro managerEcranEditorMacro;
    private EcranLogger managerEcranLogger;
    private EcranSetariProprii managerEcranSetariProprii;

    // --- ELEMENTE GRAFICE INITIALE ---
    private DrawerLayout drawerLayout;
    private FrameLayout containerPrincipal;
    private Button btnMeniuCAT, btnMeniuSettings, btnMeniuRtty, btnMeniuRttyPy, btnMeniuLogger, btnMeniuMySettings;

    // --- BROADCAST RECEIVER PENTRU PERMISIUNEA USB FĂRĂ CRASH ---
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null && radioManager != null) {
                            radioManager.conecteazaStatie();
                        }
                    } else {
                        Toast.makeText(context, "Permission denied for USB device", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) getSupportActionBar().hide();

        // 0. Cerem permisiunea de afișare peste alte aplicații și activăm modul Fullscreen
        verificaPermisiuneOverlay();
        ascundeButoaneNavigare();

        // 1. Înregistrăm receptorul USB în sistem
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);

        // 2. Instanțiem creierul de comunicație serială (RadioManager)
        radioManager = new RadioManager(this);

        // 3. Conectăm parserul global CAT asincron în MainActivity.java
        radioManager.setCatDataListener(cmd -> {
            runOnUiThread(() -> {
                if (cmd.startsWith("TX ->")) {
                    if (managerEcranSetari != null) {
                        managerEcranSetari.raporteazaComandaInLog(cmd);
                    }
                    return;
                }
                if (cmd.length() >= 2) {
                    final String prefix = cmd.substring(0, 2);
                    if (prefix.equals("FA")) {
                        try {
                            String cifre = cmd.substring(2).replaceAll("[^0-9]", "");
                            if (!cifre.isEmpty()) {
                                StareRadio.setFrecventaHz(Long.parseLong(cifre));
                            }
                        } catch (Exception ignored) {
                        }
                    } else if (prefix.equals("MD")) {
                        StareRadio.setModText(cmd.substring(2));
                    }
                    if (managerEcranComenzi != null) {
                        managerEcranComenzi.proceseazaDateCATInEcran(prefix, cmd);
                    }
                    if (managerEcranSetari != null) {
                        managerEcranSetari.proceseazaDateCATInSetari(prefix, cmd);
                    }
                }
            });
        });

        // 4. Pre-încărcăm modulele grafice
        managerEcranComenzi = new EcranComenzi(this, radioManager);
        managerEcranRtty = new EcranRttyDroid(this, radioManager);
        managerEcranSetari = new EcranSetari(this, radioManager);
        managerEcranEditorMacro = new EcranEditorMacro(this);
        managerEcranLogger = new EcranLogger(this, radioManager);
        managerEcranSetariProprii = new EcranSetariProprii(this);

        // =============================================================
        // NOU: CONECTARE RTTY → LOGGER (Click pe indicativ)
        // =============================================================
        if (managerEcranRtty != null) {
            managerEcranRtty.setOnCallsignTappedListener(callsign -> {
                // Stocam indicativul in variabila statica
                EcranRttyDroid.callsignBuffer = callsign;
                // Confirmam vizual utilizatorului ca s-a memorat
                runOnUiThread(() -> Toast.makeText(this, "📋 " + callsign + " memorat", Toast.LENGTH_SHORT).show());
            });
        }

        // =============================================================
        // CONSTRUIRE STRUCTURĂ GRAFICĂ CU MENIU HAMBURGER (≡)
        // =============================================================
        drawerLayout = new DrawerLayout(this);
        drawerLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout panouCentral = new LinearLayout(this);
        panouCentral.setOrientation(LinearLayout.VERTICAL);
        panouCentral.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        // TOOLBAR
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setBackgroundColor(Color.parseColor("#007ACC"));
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(20, 80, 20, 20);

        Button btnHamburger = new Button(this);
        btnHamburger.setText("≡");
        btnHamburger.setTextSize(30);
        btnHamburger.setTextColor(Color.WHITE);
        btnHamburger.setBackgroundColor(Color.TRANSPARENT);
        btnHamburger.setLayoutParams(new LinearLayout.LayoutParams(150, 150));
        btnHamburger.setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));

        TextView tvTitluApp = new TextView(this);
        tvTitluApp.setText("Yaesu ADV Control by YO9VA");
        tvTitluApp.setTextColor(Color.WHITE);
        tvTitluApp.setTextSize(18);
        tvTitluApp.setTypeface(null, Typeface.BOLD);
        tvTitluApp.setPadding(30, 0, 0, 0);

        toolbar.addView(btnHamburger);
        toolbar.addView(tvTitluApp);
        panouCentral.addView(toolbar);

        // CONTAINER CENTRAL FLUID
        containerPrincipal = new FrameLayout(this);
        containerPrincipal.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        panouCentral.addView(containerPrincipal);

        // MENIUL LATERAL NAVIGATION DRAWER
        LinearLayout meniuLateral = new LinearLayout(this);
        meniuLateral.setOrientation(LinearLayout.VERTICAL);
        meniuLateral.setBackgroundColor(Color.parseColor("#F5F5F5"));
        meniuLateral.setPadding(40, 100, 40, 40);

        DrawerLayout.LayoutParams paramsMeniu = new DrawerLayout.LayoutParams(
                750, DrawerLayout.LayoutParams.MATCH_PARENT);
        paramsMeniu.gravity = GravityCompat.START;
        meniuLateral.setLayoutParams(paramsMeniu);

        TextView tvEtichetaMeniu = new TextView(this);
        tvEtichetaMeniu.setText("YAESU MENU");
        tvEtichetaMeniu.setTextColor(Color.GRAY);
        tvEtichetaMeniu.setTextSize(13);
        tvEtichetaMeniu.setTypeface(null, Typeface.BOLD);
        tvEtichetaMeniu.setPadding(10, 0, 0, 50);
        meniuLateral.addView(tvEtichetaMeniu);

        btnMeniuCAT = creeazăButonMeniu("COMMANDS", v -> comutăEcranMaster(1));
        btnMeniuSettings = creeazăButonMeniu("STATION SETTINGS", v -> comutăEcranMaster(2));
        btnMeniuRtty = creeazăButonMeniu("RTTY DROID", v -> comutăEcranMaster(3));
        btnMeniuRttyPy = creeazăButonMeniu("MACROS EDITOR", v -> comutăEcranMaster(4));
        btnMeniuLogger = creeazăButonMeniu("LOGGER", v -> comutăEcranMaster(5));
        btnMeniuMySettings = creeazăButonMeniu("MY SETTINGS", v -> comutăEcranMaster(6));

        meniuLateral.addView(btnMeniuCAT);
        meniuLateral.addView(btnMeniuSettings);
        meniuLateral.addView(btnMeniuRtty);
        meniuLateral.addView(btnMeniuRttyPy);
        meniuLateral.addView(btnMeniuLogger);
        meniuLateral.addView(btnMeniuMySettings);

        drawerLayout.addView(panouCentral);
        drawerLayout.addView(meniuLateral, paramsMeniu);

        // Pornim aplicația direct pe ecranul principal (Commands)
        comutăEcranMaster(1);
        setContentView(drawerLayout);
    }

    private Button creeazăButonMeniu(String text, View.OnClickListener cl) {
        Button b = new Button(this);
        b.setText(text);
        b.setBackgroundColor(Color.parseColor("#E0E0E0"));
        b.setTextColor(Color.BLACK);
        b.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 130);
        p.setMargins(0, 5, 0, 15);
        b.setLayoutParams(p);
        b.setOnClickListener(cl);
        return b;
    }

    private void comutăEcranMaster(int indexEcran) {
        if (containerPrincipal == null) return;
        containerPrincipal.removeAllViews();

        View viewDeAfisat = null;
        switch (indexEcran) {
            case 1:
                if (managerEcranComenzi != null) viewDeAfisat = managerEcranComenzi.getView();
                break;
            case 2:
                if (managerEcranSetari != null) viewDeAfisat = managerEcranSetari.getView();
                break;
            case 3:
                if (managerEcranRtty != null) viewDeAfisat = managerEcranRtty.getView();
                break;
            case 4:
                if (managerEcranEditorMacro != null) viewDeAfisat = managerEcranEditorMacro.getView();
                break;
            case 5:
                if (managerEcranLogger != null) viewDeAfisat = managerEcranLogger.getView();
                break;
            case 6:
                if (managerEcranSetariProprii != null) viewDeAfisat = managerEcranSetariProprii.getView();
                break;
        }

        if (viewDeAfisat != null) {
            containerPrincipal.addView(viewDeAfisat);
        }

        btnMeniuCAT.setBackgroundColor(Color.parseColor(indexEcran == 1 ? "#BBDEFB" : "#E0E0E0"));
        btnMeniuSettings.setBackgroundColor(Color.parseColor(indexEcran == 2 ? "#BBDEFB" : "#E0E0E0"));
        btnMeniuRtty.setBackgroundColor(Color.parseColor(indexEcran == 3 ? "#BBDEFB" : "#E0E0E0"));
        btnMeniuRttyPy.setBackgroundColor(Color.parseColor(indexEcran == 4 ? "#BBDEFB" : "#E0E0E0"));
        btnMeniuLogger.setBackgroundColor(Color.parseColor(indexEcran == 5 ? "#BBDEFB" : "#E0E0E0"));
        btnMeniuMySettings.setBackgroundColor(Color.parseColor(indexEcran == 6 ? "#BBDEFB" : "#E0E0E0"));

        drawerLayout.closeDrawer(GravityCompat.START);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            ascundeButoaneNavigare();
        }
    }

    private void ascundeButoaneNavigare() {
        View decorView = getWindow().getDecorView();
        int uiOptions = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN;
        decorView.setSystemUiVisibility(uiOptions);
    }

    private void verificaPermisiuneOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                new AlertDialog.Builder(this)
                        .setTitle("Permisiune Afișare Peste Alte Aplicații")
                        .setMessage("Pentru a funcționa corect și a menține ecranul activ, această aplicație are nevoie de permisiunea de a apărea peste alte aplicații.\n\nVeți fi redirecționat către setările sistemului pentru a activa manual această opțiune.")
                        .setPositiveButton("OK, MERGI LA SETĂRI", (dialog, which) -> {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        })
                        .setNegativeButton("MAI TÂRZIU", null)
                        .setCancelable(false)
                        .show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception e) {
            android.util.Log.e("MAIN", "Receiver deja dezactivat", e);
        }
        if (radioManager != null) {
            radioManager.opresteToat();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // Pasăm orice rezultat către managerul de setări, care decide intern ce să facă
        if (managerEcranSetariProprii != null) {
            managerEcranSetariProprii.handleActivityResult(requestCode, resultCode, data);
        }
    }
}