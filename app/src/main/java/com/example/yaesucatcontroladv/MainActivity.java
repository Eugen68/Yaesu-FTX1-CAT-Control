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
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

public class MainActivity extends AppCompatActivity {
    private static final String ACTION_USB_PERMISSION = "com.example.yaesucatcontroladv.USB_PERMISSION";

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
    private EcranStatisticiLog managerEcranStatisticiLog;
    private EcranSetariProprii managerEcranSetariProprii;
    private EcranCluster managerEcranCluster;
    private EcranPskReporter managerEcranPskReporter;
    private EcranCwDeepDecoder managerEcranCwDeep;
    private EcranCwTrainer managerEcranCwTrainer;

    private DrawerLayout drawerLayout;
    private FrameLayout containerPrincipal;
    private Button btnMeniuCAT, btnMeniuSettings, btnMeniuRtty, btnMeniuRttyPy, btnMeniuLogger, btnMeniuLogStats, btnMeniuMySettings, btnMeniuCluster, btnMeniuPskReporter, btnMeniuCwDeep, btnMeniuCwTrainer, btnMeniuExit;

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

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) getSupportActionBar().hide();

        // Incarca (si, daca a trecut o saptamana, actualizeaza in fundal) baza
        // de indicative cunoscute (MASTER.SCP) folosita pentru evidentierea
        // "de incredere" in CW AI Decoder / RTTY.
        CallsignDatabase.getInstance().ensureLoadedAndMaybeUpdate(this);
        // Idem pentru cty.dat (prefix -> tara DXCC), folosit de LOG STATISTICS.
        CtyDatabase.ensureLoadedAndMaybeUpdate(this);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }
        isReceiverRegistered = true;

        radioManager = new RadioManager(this);

        radioManager.setCatDataListener(new RadioManager.CatDataListener() {
            @Override
            public void onCatCommandReceived(String cmd) {
                runOnUiThread(() -> {
                    if (managerEcranSetari != null) {
                        String logText = cmd.startsWith("TX ->") ? cmd : (cmd + ";");
                        managerEcranSetari.raporteazaComandaInLog(logText);
                    }
                    if (cmd.startsWith("TX ->")) return;
                    if (cmd.length() >= 2) {
                        final String prefix = cmd.substring(0, 2);
                        if (prefix.equals("FA") || prefix.equals("FD")) {
                            try {
                                String cifre = cmd.substring(2).replaceAll("[^0-9]", "");
                                if (!cifre.isEmpty()) {
                                    StareRadio.setFrecventaHz(Long.parseLong(cifre));
                                }
                            } catch (Exception ignored) {}
                        } else if (prefix.equals("MD")) {
                            StareRadio.setModText(cmd.substring(2));
                        } else if (prefix.equals("KP")) {
                            // KEY PITCH: KPxx, xx = 00..75 -> 300..1050 Hz in pasi de 10 Hz
                            try {
                                String cifre = cmd.substring(2).replaceAll("[^0-9]", "");
                                if (!cifre.isEmpty()) StareRadio.setCwPitchHz(300 + 10 * Integer.parseInt(cifre));
                            } catch (Exception ignored) {}
                        }
                        if (managerEcranComenzi != null) {
                            managerEcranComenzi.proceseazaDateCATInEcran(prefix, cmd);
                        }
                        if (managerEcranSetari != null) {
                            managerEcranSetari.proceseazaDateCATInSetari(prefix, cmd);
                        }
                    }
                });
            }

            @Override
            public void onConnectionStateChanged(boolean connected) {
                runOnUiThread(() -> {
                    if (managerEcranComenzi != null) {
                        managerEcranComenzi.setStareConexiune(connected);
                    }
                });
            }
        });

        managerEcranComenzi = new EcranComenzi(this, radioManager);
        managerEcranRtty = new EcranRttyDroid(this, radioManager);
        managerEcranSetari = new EcranSetari(this, radioManager);
        managerEcranEditorMacro = new EcranEditorMacro(this);
        managerEcranLogger = new EcranLogger(this, radioManager);
        managerEcranStatisticiLog = new EcranStatisticiLog(this);
        managerEcranSetariProprii = new EcranSetariProprii(this);
        managerEcranCluster = new EcranCluster(this, radioManager);
        managerEcranPskReporter = new EcranPskReporter(this, radioManager);
        managerEcranCwDeep = new EcranCwDeepDecoder(this, radioManager);
        managerEcranCwTrainer = new EcranCwTrainer(this);

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
        btnMeniuLogStats = creeazaButonMeniu("LOG STATISTICS", v -> comutaEcranMaster(10));
        btnMeniuCluster = creeazaButonMeniu("DX CLUSTER", v -> comutaEcranMaster(7));
        btnMeniuPskReporter = creeazaButonMeniu("PSK REPORTER", v -> comutaEcranMaster(9));
        btnMeniuCwDeep = creeazaButonMeniu("CW AI DECODER", v -> comutaEcranMaster(8));
        btnMeniuMySettings = creeazaButonMeniu("MY SETTINGS", v -> comutaEcranMaster(6));
        btnMeniuCwTrainer = creeazaButonMeniu("CW TRAINER", v -> comutaEcranMaster(11));

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
        meniuLateral.addView(btnMeniuCwDeep);
        meniuLateral.addView(btnMeniuRtty);
        meniuLateral.addView(btnMeniuLogger);
        meniuLateral.addView(btnMeniuLogStats);
        meniuLateral.addView(btnMeniuCluster);
        meniuLateral.addView(btnMeniuPskReporter);
        meniuLateral.addView(btnMeniuRttyPy);
        meniuLateral.addView(btnMeniuCwTrainer);
        meniuLateral.addView(btnMeniuSettings);
        meniuLateral.addView(btnMeniuMySettings);
        meniuLateral.addView(btnMeniuExit);

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

        FrameLayout rootFrame = new FrameLayout(this);
        rootFrame.addView(drawerLayout);
        View splashView = createSplashView();
        rootFrame.addView(splashView);
        setContentView(rootFrame);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            splashView.setVisibility(View.GONE);
            ascundeButoaneNavigare();
        }, 2500);

        verificaPermisiuneOverlay();
        verificaPermisiuneNotificari();
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
    }

    private View createSplashView() {
        LinearLayout splash = new LinearLayout(this);
        splash.setOrientation(LinearLayout.VERTICAL);
        splash.setBackgroundColor(Color.parseColor("#112855"));
        splash.setGravity(Gravity.CENTER);
        splash.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("Yaesu CAT Control ADV");
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(32);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setGravity(Gravity.CENTER);
        splash.addView(tvTitle);

        ImageView ivLogo = new ImageView(this);
        ivLogo.setImageResource(R.drawable.fileserver_logo);
        ivLogo.setAdjustViewBounds(true);
        ivLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams pLogo = new LinearLayout.LayoutParams(
                dp(260), ViewGroup.LayoutParams.WRAP_CONTENT);
        pLogo.setMargins(0, dp(24), 0, 0);
        ivLogo.setLayoutParams(pLogo);
        splash.addView(ivLogo);
        return splash;
    }

    private void verificaPermisiuneNotificari() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }
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
            case 7: if (managerEcranCluster != null) viewDeAfisat = managerEcranCluster.getView(); break;
            case 8: if (managerEcranCwDeep != null) viewDeAfisat = managerEcranCwDeep.getView(); break;
            case 9: if (managerEcranPskReporter != null) viewDeAfisat = managerEcranPskReporter.getView(); break;
            case 10: if (managerEcranStatisticiLog != null) viewDeAfisat = managerEcranStatisticiLog.getView(); break;
            case 11: if (managerEcranCwTrainer != null) viewDeAfisat = managerEcranCwTrainer.getView(); break;
        }
        if (viewDeAfisat != null) containerPrincipal.addView(viewDeAfisat);
        btnMeniuCAT.setBackgroundColor(Color.parseColor(indexEcran == 1 ? "#BBDEFB" : "#E0E0E0"));
        btnMeniuSettings.setBackgroundColor(Color.parseColor(indexEcran == 2 ? "#BBDEFB" : "#E0E0E0"));
        btnMeniuRtty.setBackgroundColor(Color.parseColor(indexEcran == 3 ? "#BBDEFB" : "#E0E0E0"));
        btnMeniuRttyPy.setBackgroundColor(Color.parseColor(indexEcran == 4 ? "#BBDEFB" : "#E0E0E0"));
        btnMeniuLogger.setBackgroundColor(Color.parseColor(indexEcran == 5 ? "#BBDEFB" : "#E0E0E0"));
        btnMeniuMySettings.setBackgroundColor(Color.parseColor(indexEcran == 6 ? "#BBDEFB" : "#E0E0E0"));
        btnMeniuCluster.setBackgroundColor(Color.parseColor(indexEcran == 7 ? "#BBDEFB" : "#E0E0E0"));
        btnMeniuCwDeep.setBackgroundColor(Color.parseColor(indexEcran == 8 ? "#BBDEFB" : "#E0E0E0"));
        btnMeniuPskReporter.setBackgroundColor(Color.parseColor(indexEcran == 9 ? "#BBDEFB" : "#E0E0E0"));
        btnMeniuLogStats.setBackgroundColor(Color.parseColor(indexEcran == 10 ? "#BBDEFB" : "#E0E0E0"));
        btnMeniuCwTrainer.setBackgroundColor(Color.parseColor(indexEcran == 11 ? "#BBDEFB" : "#E0E0E0"));
        drawerLayout.closeDrawer(GravityCompat.START);
    }

    private void exitAplicatie() {
        CheckBox cbShutdown = new CheckBox(this);
        cbShutdown.setText("Shut-down Yaesu FTX1");
        cbShutdown.setTextColor(Color.WHITE);
        cbShutdown.setTextSize(14);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(20), dp(10), dp(20), dp(5));
        container.addView(cbShutdown);
        new AlertDialog.Builder(this)
                .setTitle("Exit Confirmation")
                .setMessage("Are you sure you want to close the application?\n\nAll USB and audio resources will be released.")
                .setView(container)
                .setPositiveButton("YES, CLOSE", (dialog, which) -> {
                    if (cbShutdown.isChecked() && radioManager != null) {
                        radioManager.trimiteComandaCAT("PS0;");
                        try { Thread.sleep(150); } catch (InterruptedException ignored) {}
                    }
                    if (managerEcranRtty != null) managerEcranRtty.eliberareResurse();
                    if (managerEcranCwDeep != null) managerEcranCwDeep.eliberareResurse();
                    if (managerEcranCwTrainer != null) managerEcranCwTrainer.eliberareResurse();
                    if (managerEcranCluster != null) managerEcranCluster.eliberareResurse();
                    if (managerEcranPskReporter != null) managerEcranPskReporter.eliberareResurse();
                    safeUnregisterReceiver();
                    if (radioManager != null) radioManager.opresteToat();
                    new Handler(Looper.getMainLooper()).postDelayed(this::finish, 200);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void safeUnregisterReceiver() {
        if (isReceiverRegistered) {
            try { unregisterReceiver(usbReceiver); } catch (Exception e) {}
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
        decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    private void verificaPermisiuneOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                new AlertDialog.Builder(this)
                        .setTitle("Overlay Permission")
                        .setMessage("To work correctly and keep the screen active, this application needs permission to appear over other apps.")
                        .setPositiveButton("OK, GO TO SETTINGS", (dialog, which) -> {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        })
                        .setNegativeButton("LATER", null)
                        .setCancelable(false).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (managerEcranRtty != null) managerEcranRtty.eliberareResurse();
        if (managerEcranCwDeep != null) managerEcranCwDeep.eliberareResurse();
        if (managerEcranCwTrainer != null) managerEcranCwTrainer.eliberareResurse();
        if (isFinishing()) {
            if (managerEcranCluster != null) managerEcranCluster.eliberareResurse();
            if (managerEcranPskReporter != null) managerEcranPskReporter.eliberareResurse();
            stopService(new Intent(this, ClusterService.class));
        }
        if (managerEcranComenzi != null) managerEcranComenzi.eliberareResurse();
        if (managerEcranSetariProprii != null) managerEcranSetariProprii.eliberareResurse();
        safeUnregisterReceiver();
        if (radioManager != null) radioManager.opresteToat();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @androidx.annotation.NonNull String[] permissions, @androidx.annotation.NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 123 && grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (managerEcranCwDeep != null) managerEcranCwDeep.startDecoderDupaPermisiune();
        } else if (requestCode == 124 && grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (managerEcranComenzi != null) managerEcranComenzi.startListenDupaPermisiune();
        } else if (requestCode == 125 && grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (managerEcranComenzi != null) managerEcranComenzi.porneteScoDupaPermisiune();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (managerEcranSetariProprii != null) managerEcranSetariProprii.handleActivityResult(requestCode, resultCode, data);
    }
}
