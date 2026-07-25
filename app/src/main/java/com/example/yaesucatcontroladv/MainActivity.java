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

    // OPTIMIZARE (fix dp/px): valorile UI erau inainte pixeli bruti, deci apareau
    // cu marime diferita in functie de densitatea ecranului (minuscule pe telefoane
    // cu densitate mare). Le-am convertit in dp folosind helper-ul dp() de mai jos,
    // care foloseste TypedValue.applyDimension() - conversia oficiala Android,
    // identica cu ce foloseste automat un layout XML.
    // Valorile in dp de mai jos au fost deduse din vechile valori px impartite la
    // ~3 (densitate xxhdpi, foarte comuna la telefoanele Android actuale) - rezultat
    // confirmat de faptul ca ies in valori "rotunde" conforme cu Material Design
    // (meniu ~250dp, butoane ~43-50dp, aproape de touch target-ul standard 48dp).
    private static final float DP_TOOLBAR_PAD_H = 7f;
    private static final float DP_TOOLBAR_PAD_TOP = 27f;
    private static final float DP_HAMBURGER_SIZE = 50f;
    private static final float DP_TITLE_PAD_START = 10f;
    private static final float DP_MENU_PAD_H = 13f;
    private static final float DP_MENU_PAD_TOP = 33f;
    private static final float DP_MENU_LABEL_PAD_START = 3f;
    private static final float DP_MENU_LABEL_PAD_BOTTOM = 17f;
    private static final float DP_DRAWER_WIDTH = 250f;
    private static final float DP_MENU_BUTTON_HEIGHT = 43f;
    private static final float DP_MENU_BUTTON_MARGIN_TOP = 2f;
    private static final float DP_MENU_BUTTON_MARGIN_BOTTOM = 5f;
    private static final float DP_EXIT_BUTTON_MARGIN_TOP = 10f;
    private static final float DP_EXIT_BUTTON_MARGIN_BOTTOM = 5f;

    /** Converteste o valoare in dp (density-independent pixels) in pixeli reali,
     *  tinand cont de densitatea ecranului curent - exact ce face automat Android
     *  cand pui "dp" intr-un fisier XML. */
    private int dp(float valoareDp) {
        return Math.round(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, valoareDp, getResources().getDisplayMetrics()));
    }

    private RadioManager radioManager;
    private EcranComenzi managerEcranComenzi;
    private EcranRttyDroid managerEcranRtty;
    private EcranSetari managerEcranSetari;
    private EcranEditorMacro managerEcranEditorMacro;
    private EcranLogger managerEcranLogger;
    private EcranSetariProprii managerEcranSetariProprii;

    private DrawerLayout drawerLayout;
    private FrameLayout containerPrincipal;
    private Button btnMeniuCAT, btnMeniuSettings, btnMeniuRtty, btnMeniuRttyPy, btnMeniuLogger, btnMeniuMySettings, btnMeniuExit;

    private boolean isReceiverRegistered = false;

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
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                if (radioManager != null) {
                    radioManager.opresteToat();
                    Toast.makeText(context, "Radio disconnected (USB detached)", Toast.LENGTH_SHORT).show();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) getSupportActionBar().hide();

        verificaPermisiuneOverlay();
        ascundeButoaneNavigare();

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        isReceiverRegistered = true;

        radioManager = new RadioManager(this);

        radioManager.setCatDataListener(cmd -> {
            runOnUiThread(() -> {
                // 1. LOGARE CENTRALĂ: Trimitem orice comandă (TX sau RX) direct în Monitorul din Setări
                if (managerEcranSetari != null) {
                    // Dacă e RX, adăugăm manual punctul și virgula (pe care RadioManager le elimină la parsare)
                    String logText = cmd.startsWith("TX ->") ? cmd : (cmd + ";");
                    managerEcranSetari.raporteazaComandaInLog(logText);
                }

                // 2. Dacă e TX, nu mai procesăm parsarea de răspuns
                if (cmd.startsWith("TX ->")) return;

                // 3. PROCESARE RĂSPUNSURI (RX)
                if (cmd.length() >= 2) {
                    final String prefix = cmd.substring(0, 2);
                    if (prefix.equals("FA")) {
                        try {
                            String cifre = cmd.substring(2).replaceAll("[^0-9]", "");
                            if (!cifre.isEmpty()) {
                                StareRadio.setFrecventaHz(Long.parseLong(cifre));
                            }
                        } catch (Exception ignored) {}
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

        managerEcranComenzi = new EcranComenzi(this, radioManager);
        managerEcranRtty = new EcranRttyDroid(this, radioManager);
        managerEcranSetari = new EcranSetari(this, radioManager);
        managerEcranEditorMacro = new EcranEditorMacro(this);
        managerEcranLogger = new EcranLogger(this, radioManager);
        managerEcranSetariProprii = new EcranSetariProprii(this);

        // CONEXIUNE RTTY -> LOGGER (Listener păstrat pentru compatibilitate,
        // dar logica principală este acum în EcranRttyDroid.adaugaIndicativCuClick)
        if (managerEcranRtty != null) {
            managerEcranRtty.setOnCallsignTappedListener(callsign -> {
                // Listener-ul este acum redundant, dar îl păstrăm pentru eventuale extensii viitoare
                // Logica de populare buffer + toast + inserare FSK este deja în EcranRttyDroid
            });
        }

        drawerLayout = new DrawerLayout(this);
        drawerLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout panouCentral = new LinearLayout(this);
        panouCentral.setOrientation(LinearLayout.VERTICAL);
        panouCentral.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setBackgroundColor(Color.parseColor("#007ACC"));
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(DP_TOOLBAR_PAD_H), dp(DP_TOOLBAR_PAD_TOP), dp(DP_TOOLBAR_PAD_H), dp(DP_TOOLBAR_PAD_H));

        Button btnHamburger = new Button(this);
        btnHamburger.setText("≡");
        btnHamburger.setTextSize(30);
        btnHamburger.setTextColor(Color.WHITE);
        btnHamburger.setBackgroundColor(Color.TRANSPARENT);
        btnHamburger.setLayoutParams(new LinearLayout.LayoutParams(dp(DP_HAMBURGER_SIZE), dp(DP_HAMBURGER_SIZE)));
        btnHamburger.setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));

        TextView tvTitluApp = new TextView(this);
        tvTitluApp.setText("Yaesu ADV Control by YO9VA");
        tvTitluApp.setTextColor(Color.WHITE);
        tvTitluApp.setTextSize(18);
        tvTitluApp.setTypeface(null, Typeface.BOLD);
        tvTitluApp.setPadding(dp(DP_TITLE_PAD_START), 0, 0, 0);

        toolbar.addView(btnHamburger);
        toolbar.addView(tvTitluApp);
        panouCentral.addView(toolbar);

        containerPrincipal = new FrameLayout(this);
        containerPrincipal.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        panouCentral.addView(containerPrincipal);

        LinearLayout meniuLateral = new LinearLayout(this);
        meniuLateral.setOrientation(LinearLayout.VERTICAL);
        meniuLateral.setBackgroundColor(Color.parseColor("#F5F5F5"));
        meniuLateral.setPadding(dp(DP_MENU_PAD_H), dp(DP_MENU_PAD_TOP), dp(DP_MENU_PAD_H), dp(DP_MENU_PAD_H));

        DrawerLayout.LayoutParams paramsMeniu = new DrawerLayout.LayoutParams(
                dp(DP_DRAWER_WIDTH), DrawerLayout.LayoutParams.MATCH_PARENT);
        paramsMeniu.gravity = GravityCompat.START;
        meniuLateral.setLayoutParams(paramsMeniu);

        TextView tvEtichetaMeniu = new TextView(this);
        tvEtichetaMeniu.setText("YAESU MENU");
        tvEtichetaMeniu.setTextColor(Color.GRAY);
        tvEtichetaMeniu.setTextSize(13);
        tvEtichetaMeniu.setTypeface(null, Typeface.BOLD);
        tvEtichetaMeniu.setPadding(dp(DP_MENU_LABEL_PAD_START), 0, 0, dp(DP_MENU_LABEL_PAD_BOTTOM));
        meniuLateral.addView(tvEtichetaMeniu);

        btnMeniuCAT = creeazaButonMeniu("COMMANDS", v -> comutaEcranMaster(1));
        btnMeniuSettings = creeazaButonMeniu("STATION SETTINGS", v -> comutaEcranMaster(2));
        btnMeniuRtty = creeazaButonMeniu("RTTY CENTER", v -> comutaEcranMaster(3));
        btnMeniuRttyPy = creeazaButonMeniu("MACROS EDITOR", v -> comutaEcranMaster(4));
        btnMeniuLogger = creeazaButonMeniu("LOGGER", v -> comutaEcranMaster(5));
        btnMeniuMySettings = creeazaButonMeniu("MY SETTINGS", v -> comutaEcranMaster(6));

// BUTON EXIT - Închidere completă cu eliberare resurse
        btnMeniuExit = new Button(this);
        btnMeniuExit.setText("❌ EXIT APPLICATION");
        btnMeniuExit.setBackgroundColor(Color.parseColor("#B71C1C"));
        btnMeniuExit.setTextColor(Color.WHITE);
        btnMeniuExit.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams pExit = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(DP_MENU_BUTTON_HEIGHT));
        pExit.setMargins(0, dp(DP_EXIT_BUTTON_MARGIN_TOP), 0, dp(DP_EXIT_BUTTON_MARGIN_BOTTOM));
        btnMeniuExit.setLayoutParams(pExit);
        btnMeniuExit.setOnClickListener(v -> exitAplicatie());

        meniuLateral.addView(btnMeniuCAT);
        meniuLateral.addView(btnMeniuSettings);
        meniuLateral.addView(btnMeniuRtty);
        meniuLateral.addView(btnMeniuRttyPy);
        meniuLateral.addView(btnMeniuLogger);
        meniuLateral.addView(btnMeniuMySettings);
        meniuLateral.addView(btnMeniuExit); // ← Adăugat la final

        // AFISARE VERSIUNE SI DATA BUILD
        TextView tvVersionInfo = new TextView(this);
        String vInfo = "v" + BuildConfig.VERSION_NAME + " | Ref: " + BuildConfig.GIT_HASH + "\nBuild: " + BuildConfig.BUILD_TIME;
        tvVersionInfo.setText(vInfo);
        tvVersionInfo.setTextColor(Color.GRAY);
        tvVersionInfo.setTextSize(10);
        tvVersionInfo.setGravity(Gravity.CENTER);
        tvVersionInfo.setPadding(0, dp(15), 0, dp(10));
        meniuLateral.addView(tvVersionInfo);

        drawerLayout.addView(panouCentral);
        drawerLayout.addView(meniuLateral, paramsMeniu);

        comutaEcranMaster(1);
        setContentView(drawerLayout);

        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
    }

    private Button creeazaButonMeniu(String text, View.OnClickListener cl) {
        Button b = new Button(this);
        b.setText(text);
        b.setBackgroundColor(Color.parseColor("#E0E0E0"));
        b.setTextColor(Color.BLACK);
        b.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(DP_MENU_BUTTON_HEIGHT));
        p.setMargins(0, dp(DP_MENU_BUTTON_MARGIN_TOP), 0, dp(DP_MENU_BUTTON_MARGIN_BOTTOM));
        b.setLayoutParams(p);
        b.setOnClickListener(cl);
        return b;
    }

    private void comutaEcranMaster(int indexEcran) {
        if (containerPrincipal == null) return;
        containerPrincipal.removeAllViews();

        View viewDeAfisat = null;
        switch (indexEcran) {
            case 1: if (managerEcranComenzi != null) viewDeAfisat = managerEcranComenzi.getView(); break;
            case 2: if (managerEcranSetari != null) viewDeAfisat = managerEcranSetari.getView(); break;
            case 3: if (managerEcranRtty != null) viewDeAfisat = managerEcranRtty.getView(); break;
            case 4: if (managerEcranEditorMacro != null) viewDeAfisat = managerEcranEditorMacro.getView(); break;
            case 5: if (managerEcranLogger != null) viewDeAfisat = managerEcranLogger.getView(); break;
            case 6: if (managerEcranSetariProprii != null) viewDeAfisat = managerEcranSetariProprii.getView(); break;
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

    // ============================================================
    // EXIT APLICATIE - Eliberare completa resurse + inchidere fortata
    // ============================================================
// ============================================================
// EXIT APLICATIE - Eliberare completa resurse + inchidere graceful
// ============================================================
    private void exitAplicatie() {
        new AlertDialog.Builder(this)
                .setTitle("Exit Confirmation")
                .setMessage("Are you sure you want to close the application?\n\nAll USB and audio resources will be released.")
                .setPositiveButton("YES, CLOSE", (dialog, which) -> {
                    // 1. Oprim resursele RTTY (AudioRecord, thread decodare, bitmap-uri)
                    if (managerEcranRtty != null) {
                        managerEcranRtty.eliberareResurse();
                    }
                    // 2. Dezactivam receiver-ul USB
                    safeUnregisterReceiver();

                    // 3. Oprim porturile seriale si conexiunile USB
                    if (radioManager != null) {
                        radioManager.opresteToat();
                    }

                    // 4. Asteptam 200ms ca thread-urile USB sa se opreasca elegant
                    //    (Aceasta linie previne eroarea "Connection closed")
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ignored) {}

                    // 5. Inchidem Activity-ul curent (FARA System.exit!)
                    //    Android va gestiona cleanup-ul nativ in mod sigur.
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void safeUnregisterReceiver() {
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(usbReceiver);
            } catch (Exception e) {
                android.util.Log.e("MAIN", "Eroare la dezactivarea receiver-ului", e);
            }
            isReceiverRegistered = false;
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) ascundeButoaneNavigare();
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
                        .setTitle("Overlay Permission")
                        .setMessage("To work correctly and keep the screen active, this application needs permission to appear over other apps.\n\nYou will be redirected to the system settings to manually enable this option.")
                        .setPositiveButton("OK, GO TO SETTINGS", (dialog, which) -> {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        })
                        .setNegativeButton("LATER", null)
                        .setCancelable(false)
                        .show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Oprim resursele RTTY (dacă nu au fost deja oprite prin EXIT)
        if (managerEcranRtty != null) {
            managerEcranRtty.eliberareResurse();
        }

        // Dezactivam receiver-ul USB
        safeUnregisterReceiver();

        // Oprim porturile seriale
        if (radioManager != null) {
            radioManager.opresteToat();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (managerEcranSetariProprii != null) {
            managerEcranSetariProprii.handleActivityResult(requestCode, resultCode, data);
        }
    }
}