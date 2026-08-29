package com.example.yaesucatcontroladv;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Ecranul PSK Reporter: arată ce stații au auzit/decodat semnalul propriu
 * (interoghează retrieve.pskreporter.info cu senderCallsign = indicativul căutat).
 *
 * Nimic din starea acestui ecran (indicativ căutat, filtre bandă/mod, fereastră
 * de timp, AUTO) nu se salvează pe disc - totul e păstrat doar în memorie, cât
 * timp aplicația rulează (deci rămâne neschimbat cât comuți între tab-uri în
 * aceeași sesiune), dar repornește mereu de la valorile implicite la un nou
 * start al aplicației (indicativul revine la MY CALL din MY SETTINGS).
 */
public class EcranPskReporter {
    private static final int[] LOOKBACK_OPTIONS_MIN = {15, 30, 60};
    // PSKReporter cere explicit min. 5 minute între interogări automate (fair use,
    // altfel răspunde cu HTTP 429 / 503). Se aplică oricărei interogări NEcerute
    // explicit de user (intrare pe tab, auto-refresh) - nu scădea sub această valoare.
    private static final int REFRESH_INTERVAL_MS = 5 * 60_000;
    // Acțiuni explicite ale userului (buton REFRESH, Enter, schimbare fereastră)
    // pot folosi un cooldown mai scurt - userul știe ce cere și vede toast-ul
    // de așteptare, deci riscul de spam e mult mai mic decât la auto-refresh.
    private static final long MIN_USER_QUERY_GAP_MS = 20_000;
    private static final long DOUBLE_TAP_TIME_DELTA = 500; // milisecunde

    private static final String[] ALL_BANDS = {"160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "6m", "2m"};
    private static final String[] ALL_MODES = {"CW", "SSB", "FT8", "FT4", "RTTY", "PSK"};

    private final Context context;
    private final RadioManager radioManager;
    private final PskReporterManager manager = new PskReporterManager();

    private String lastTappedCallsign;
    private long lastTapTime;

    private LinearLayout viewPrincipal;
    private TextView tvStatus;
    private EditText etCallsign;
    private Button btnRefresh, btnWindow, btnFilters, btnBand, btnMode;
    private CheckBox cbAutoRefresh;
    private RecyclerView rvReports;
    private PskReporterAdapter adapter;
    private LinearLayout panelFiltre;

    private int lookbackMinutes = 30;
    private boolean isFetching = false;
    private long lastFetchAtMillis = 0L;

    private boolean filtersVisible = false;
    private final Set<String> selectedBands = new HashSet<>();
    private final Set<String> selectedModes = new HashSet<>();

    private final Handler autoRefreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoRefreshRunnable = this::autoRefreshTick;

    private final Handler hideFiltersHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideFiltersRunnable = () -> toggleFilters(false);

    public EcranPskReporter(Context context, RadioManager radioManager) {
        this.context = context;
        this.radioManager = radioManager;
        this.adapter = new PskReporterAdapter(this::onReportClicked);
        construiesteInterfata();
    }

    public View getView() {
        // Intrarea pe tab NU declanșează nicio interogare - totul e manual
        // (REFRESH/Enter) în afară de AUTO, care doar pornește/repornește
        // timer-ul de reinterogare la 5 minute cât timp rămâi pe acest ecran
        // (fără să interogheze imediat la intrare).
        if (cbAutoRefresh.isChecked()) scheduleNextRefresh();

        viewPrincipal.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {}

            @Override
            public void onViewDetachedFromWindow(View v) {
                autoRefreshHandler.removeCallbacks(autoRefreshRunnable);
                viewPrincipal.removeOnAttachStateChangeListener(this);
            }
        });
        return viewPrincipal;
    }

    private void construiesteInterfata() {
        viewPrincipal = new LinearLayout(context);
        viewPrincipal.setOrientation(LinearLayout.VERTICAL);
        viewPrincipal.setBackgroundColor(Color.parseColor("#121212"));
        viewPrincipal.setPadding(20, 10, 20, 10);

        TextView tvTitlu = new TextView(context);
        tvTitlu.setText("PSK REPORTER");
        tvTitlu.setTextColor(Color.WHITE);
        tvTitlu.setTextSize(18);
        tvTitlu.setTypeface(null, Typeface.BOLD);
        tvTitlu.setGravity(Gravity.CENTER);
        tvTitlu.setPadding(0, 5, 0, 5);
        viewPrincipal.addView(tvTitlu);

        LinearLayout rowCallsign = new LinearLayout(context);
        rowCallsign.setOrientation(LinearLayout.HORIZONTAL);
        rowCallsign.setGravity(Gravity.CENTER_VERTICAL);
        rowCallsign.setPadding(0, 0, 0, 4);

        TextView tvWhoHears = new TextView(context);
        tvWhoHears.setText("Who hears:");
        tvWhoHears.setTextColor(Color.parseColor("#00E676"));
        tvWhoHears.setTextSize(13);
        tvWhoHears.setTypeface(null, Typeface.BOLD);
        rowCallsign.addView(tvWhoHears);

        etCallsign = new EditText(context);
        etCallsign.setTextColor(Color.parseColor("#00E676"));
        etCallsign.setTextSize(13);
        etCallsign.setTypeface(null, Typeface.BOLD);
        etCallsign.setSingleLine(true);
        etCallsign.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        etCallsign.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        etCallsign.setGravity(Gravity.CENTER);
        etCallsign.setCompoundDrawablePadding(8);
        LinearLayout.LayoutParams pCallsign = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        pCallsign.setMarginStart(10);
        etCallsign.setLayoutParams(pCallsign);
        etCallsign.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                fetchNow(true);
                return true;
            }
            return false;
        });
        etCallsign.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                etCallsign.setCompoundDrawablesWithIntrinsicBounds(null, null, s.length() > 0 ? createXIcon() : null, null);
            }
        });
        etCallsign.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                if (etCallsign.getCompoundDrawables()[2] != null
                        && event.getRawX() >= (etCallsign.getRight() - etCallsign.getCompoundPaddingRight())) {
                    etCallsign.setText("");
                    return true;
                }
            }
            return false;
        });
        etCallsign.setText(defaultCallsignDinSetari());
        rowCallsign.addView(etCallsign);

        btnFilters = new Button(context);
        btnFilters.setText("FILTERS ▼");
        btnFilters.setBackgroundColor(Color.parseColor("#37474F"));
        btnFilters.setTextColor(Color.WHITE);
        btnFilters.setTextSize(11);
        LinearLayout.LayoutParams pFilters = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 110);
        pFilters.setMarginStart(10);
        btnFilters.setLayoutParams(pFilters);
        btnFilters.setOnClickListener(v -> toggleFilters(!filtersVisible));
        rowCallsign.addView(btnFilters);

        viewPrincipal.addView(rowCallsign);

        // Panel Filtre (Bandă / Mod) - identic ca la DX Cluster
        panelFiltre = new LinearLayout(context);
        panelFiltre.setOrientation(LinearLayout.HORIZONTAL);
        panelFiltre.setVisibility(View.GONE);
        panelFiltre.setPadding(0, 5, 0, 5);

        LinearLayout.LayoutParams pBtnF = new LinearLayout.LayoutParams(0, 110, 1f);
        pBtnF.setMargins(5, 0, 5, 0);

        btnBand = new Button(context);
        btnBand.setText("ALL BANDS");
        btnBand.setTextSize(10);
        btnBand.setBackgroundColor(Color.parseColor("#333333"));
        btnBand.setTextColor(Color.WHITE);
        btnBand.setLayoutParams(pBtnF);
        btnBand.setOnClickListener(v -> showMultiSelectDialog("Bands", ALL_BANDS, selectedBands));

        LinearLayout.LayoutParams pBtnF2 = new LinearLayout.LayoutParams(0, 110, 1f);
        pBtnF2.setMargins(5, 0, 5, 0);
        btnMode = new Button(context);
        btnMode.setText("ALL MODES");
        btnMode.setTextSize(10);
        btnMode.setBackgroundColor(Color.parseColor("#333333"));
        btnMode.setTextColor(Color.WHITE);
        btnMode.setLayoutParams(pBtnF2);
        btnMode.setOnClickListener(v -> showMultiSelectDialog("Modes", ALL_MODES, selectedModes));

        panelFiltre.addView(btnBand);
        panelFiltre.addView(btnMode);
        viewPrincipal.addView(panelFiltre);

        tvStatus = new TextView(context);
        tvStatus.setText("Idle");
        tvStatus.setTextColor(Color.GRAY);
        tvStatus.setTextSize(11);
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setPadding(0, 0, 0, 10);
        viewPrincipal.addView(tvStatus);

        LinearLayout rowControls = new LinearLayout(context);
        rowControls.setOrientation(LinearLayout.HORIZONTAL);
        rowControls.setGravity(Gravity.CENTER_VERTICAL);

        btnRefresh = new Button(context);
        btnRefresh.setText("REFRESH");
        btnRefresh.setBackgroundColor(Color.parseColor("#0D47A1"));
        btnRefresh.setTextColor(Color.WHITE);
        btnRefresh.setTextSize(11);
        btnRefresh.setLayoutParams(new LinearLayout.LayoutParams(0, 120, 1f));
        btnRefresh.setOnClickListener(v -> fetchNow(true));
        rowControls.addView(btnRefresh);

        btnWindow = new Button(context);
        btnWindow.setText(lookbackMinutes + " MIN");
        btnWindow.setBackgroundColor(Color.parseColor("#37474F"));
        btnWindow.setTextColor(Color.WHITE);
        btnWindow.setTextSize(11);
        LinearLayout.LayoutParams pWindow = new LinearLayout.LayoutParams(0, 120, 1f);
        pWindow.setMargins(10, 0, 0, 0);
        btnWindow.setLayoutParams(pWindow);
        btnWindow.setOnClickListener(v -> cycleLookback());
        rowControls.addView(btnWindow);

        cbAutoRefresh = new CheckBox(context);
        cbAutoRefresh.setText("AUTO");
        cbAutoRefresh.setTextColor(Color.YELLOW);
        cbAutoRefresh.setTextSize(11);
        cbAutoRefresh.setChecked(true);
        LinearLayout.LayoutParams pCb = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.7f);
        pCb.setMargins(10, 0, 0, 0);
        cbAutoRefresh.setLayoutParams(pCb);
        cbAutoRefresh.setOnCheckedChangeListener((v, isChecked) -> {
            if (isChecked) {
                scheduleNextRefresh();
            } else {
                autoRefreshHandler.removeCallbacks(autoRefreshRunnable);
            }
        });
        rowControls.addView(cbAutoRefresh);
        viewPrincipal.addView(rowControls);

        rvReports = new RecyclerView(context);
        rvReports.setLayoutManager(new LinearLayoutManager(context));
        rvReports.setAdapter(adapter);
        LinearLayout.LayoutParams pRv = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        pRv.setMargins(0, 10, 0, 0);
        rvReports.setLayoutParams(pRv);
        viewPrincipal.addView(rvReports);
    }

    private void toggleFilters(boolean visible) {
        filtersVisible = visible;
        panelFiltre.setVisibility(visible ? View.VISIBLE : View.GONE);
        resetFiltersTimer(5000);
    }

    private void resetFiltersTimer(int ms) {
        hideFiltersHandler.removeCallbacks(hideFiltersRunnable);
        if (filtersVisible) {
            hideFiltersHandler.postDelayed(hideFiltersRunnable, ms);
        }
    }

    private void showMultiSelectDialog(String title, String[] items, Set<String> selection) {
        boolean[] checked = new boolean[items.length];
        for (int i = 0; i < items.length; i++) checked[i] = selection.contains(items[i]);
        new AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setMultiChoiceItems(items, checked, (d, w, isC) -> {
                    checked[w] = isC;
                    resetFiltersTimer(8000);
                })
                .setPositiveButton("OK", (d, w) -> {
                    selection.clear();
                    for (int i = 0; i < items.length; i++) if (checked[i]) selection.add(items[i]);
                    btnBand.setText(selectedBands.isEmpty() ? "ALL BANDS" : String.join(",", selectedBands));
                    btnMode.setText(selectedModes.isEmpty() ? "ALL MODES" : String.join(",", selectedModes));
                    adapter.applyFilter(selectedBands, selectedModes);
                    resetFiltersTimer(5000);
                })
                .show();
    }

    private void cycleLookback() {
        int idx = 0;
        for (int i = 0; i < LOOKBACK_OPTIONS_MIN.length; i++) {
            if (LOOKBACK_OPTIONS_MIN[i] == lookbackMinutes) { idx = i; break; }
        }
        lookbackMinutes = LOOKBACK_OPTIONS_MIN[(idx + 1) % LOOKBACK_OPTIONS_MIN.length];
        btnWindow.setText(lookbackMinutes + " MIN");
        fetchNow(true);
    }

    private void autoRefreshTick() {
        fetchNow(false);
        scheduleNextRefresh();
    }

    private void scheduleNextRefresh() {
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable);
        autoRefreshHandler.postDelayed(autoRefreshRunnable, REFRESH_INTERVAL_MS);
    }

    /**
     * @param userInitiated true dacă vine dintr-o acțiune explicită (buton REFRESH,
     *                      Enter în câmpul de indicativ, schimbare fereastră de timp) -
     *                      foloseste cooldown scurt (20s) și arată Toast de așteptare.
     *                      La intrarea pe tab sau la tick-ul automat, se aplică
     *                      cooldown-ul lung (5 min, cerut de PSKReporter), fără Toast.
     */
    private void fetchNow(boolean userInitiated) {
        String targetCall = etCallsign.getText().toString().trim().toUpperCase(Locale.US);
        if (targetCall.isEmpty()) {
            tvStatus.setText("Enter a callsign");
            tvStatus.setTextColor(Color.parseColor("#FF8A65"));
            return;
        }
        if (isFetching) return;
        long minGap = userInitiated ? MIN_USER_QUERY_GAP_MS : REFRESH_INTERVAL_MS;
        long elapsed = System.currentTimeMillis() - lastFetchAtMillis;
        if (elapsed < minGap) {
            if (userInitiated) {
                long waitSec = (minGap - elapsed) / 1000 + 1;
                Toast.makeText(context, "Please wait " + waitSec + "s (PSKReporter rate limit)", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        lastFetchAtMillis = System.currentTimeMillis();
        isFetching = true;
        tvStatus.setText("Querying PSKReporter...");
        tvStatus.setTextColor(Color.GRAY);

        // Orice interogare reușită (manuală sau automată) resincronizează
        // timer-ul AUTO la 5 minute de-acum - altfel un REFRESH manual dat
        // la mijlocul intervalului ar lăsa AUTO să încerce inutil la vechiul
        // termen (fiind încă în cooldown, n-ar face nimic) și ar întârzia
        // următoarea interogare reală cu până la 5 minute în plus.
        if (cbAutoRefresh.isChecked()) scheduleNextRefresh();

        manager.fetchHeardMe(targetCall, lookbackMinutes * 60, new PskReporterManager.Listener() {
            @Override
            public void onReportsReceived(List<PskSpot> spots) {
                isFetching = false;
                adapter.updateList(spots);
                String ora = new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date());
                tvStatus.setText(spots.size() + " reports · updated " + ora);
                tvStatus.setTextColor(Color.parseColor("#00E676"));
            }

            @Override
            public void onError(String message) {
                isFetching = false;
                tvStatus.setText("Error: " + message);
                tvStatus.setTextColor(Color.parseColor("#FF5252"));
            }
        });
    }

    /**
     * Valoarea implicită a câmpului editabil la PORNIREA aplicației: MY CALL
     * din MY SETTINGS. Nu se salvează nicăieri - dacă userul îl schimbă,
     * valoarea nouă rămâne activă doar cât ține sesiunea curentă (procesul
     * aplicației), fără să persiste între reporniri.
     */
    private String defaultCallsignDinSetari() {
        return context.getSharedPreferences(EcranSetariProprii.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(EcranSetariProprii.PREF_MY_CALL, "").trim().toUpperCase(Locale.US);
    }

    /**
     * Creează iconița X dinamic (fără resurse externe) - identică cu cea din EcranCluster.
     */
    private android.graphics.drawable.Drawable createXIcon() {
        int size = 48;
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);

        android.graphics.Paint bgPaint = new android.graphics.Paint();
        bgPaint.setColor(Color.parseColor("#B71C1C"));
        bgPaint.setAntiAlias(true);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bgPaint);

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

    /**
     * Tap simplu -> acordează stația pe frecvența/modul din raport (ca la DX Cluster).
     * Tap dublu (pe același raport, în DOUBLE_TAP_TIME_DELTA ms) -> deschide QRZ.com
     * pentru indicativul receptorului (stația care ne-a auzit).
     */
    private void onReportClicked(PskSpot spot) {
        String callFinal = spot.receiverCallsign != null ? spot.receiverCallsign.toUpperCase(Locale.US) : "";
        long tapTime = System.currentTimeMillis();

        if (!callFinal.isEmpty() && callFinal.equals(lastTappedCallsign) && (tapTime - lastTapTime) < DOUBLE_TAP_TIME_DELTA) {
            lastTapTime = 0;
            lastTappedCallsign = null;
            deschideQrzComInBrowser(callFinal);
        } else {
            lastTapTime = tapTime;
            lastTappedCallsign = callFinal;
            tuneazaStatia(spot);
        }
    }

    private void tuneazaStatia(PskSpot spot) {
        if (radioManager != null) {
            long freqHz = Math.round(spot.frequencyMhz * 1_000_000);
            radioManager.trimiteComandaCAT(String.format(Locale.US, "FA%09d;", freqHz));

            String catMode = spot.frequencyMhz < 10.0 ? "01" : "02";
            if (spot.mode != null) {
                if (spot.mode.equalsIgnoreCase("CW")) catMode = "03";
                if (spot.mode.equalsIgnoreCase("RTTY")) catMode = "06";
            }
            radioManager.trimiteComandaCAT("MD" + catMode + ";");
        }
        Toast.makeText(context, String.format(Locale.US, "Tuned to %.5f MHz", spot.frequencyMhz), Toast.LENGTH_SHORT).show();
    }

    private void deschideQrzComInBrowser(String callsign) {
        String url = "https://www.qrz.com/db/" + callsign;
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(context, "Could not open browser", Toast.LENGTH_SHORT).show();
        }
    }

    public void eliberareResurse() {
        autoRefreshHandler.removeCallbacksAndMessages(null);
        hideFiltersHandler.removeCallbacksAndMessages(null);
        manager.shutdown();
    }
}
