package com.example.yaesucatcontroladv;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Baza de date de indicative cunoscute (MASTER.SCP - "Super Check Partial",
 * lista publică menținută de comunitatea de contest pe supercheckpartial.com).
 *
 * Folosită DOAR ca semnal de încredere pentru evidențiere (CallsignDetector) -
 * NICIODATĂ pentru a corecta automat textul decodat. Motiv: ~72% din
 * indicativele de 5 caractere din listă au un alt indicativ real la exact
 * 1 literă distanță, deci o "corecție" automată ar fi ambiguă foarte des.
 *
 * Actualizare: la fiecare pornire a aplicației se verifică dacă a trecut
 * intervalul de reîmprospătare; dacă da, se încearcă în fundal o redescărcare
 * de pe supercheckpartial.com. Fără internet sau la eșec, se continuă cu
 * fișierul din cache (sau cu seed-ul inclus în APK, la prima instalare) -
 * nu se afișează nicio eroare userului, e strict o mentenanță silențioasă.
 */
final class CallsignDatabase {

    private static final String TAG = "CallsignDB";
    private static final String REMOTE_URL = "https://www.supercheckpartial.com/downloads/MASTER.SCP";
    private static final String SEED_ASSET = "master_scp_seed.txt";
    private static final String CACHE_FILENAME = "master_scp_cache.txt";
    private static final String PREFS_NAME = "callsign_db_prefs";
    private static final String PREF_LAST_UPDATE = "last_update_millis";
    private static final long UPDATE_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000; // 7 zile
    private static final int MIN_VALID_LINES = 10000; // sanity check anti fisier gol/corupt

    private static final CallsignDatabase INSTANCE = new CallsignDatabase();

    private final Set<String> callsigns = Collections.synchronizedSet(new HashSet<>());
    private final AtomicBoolean updateInProgress = new AtomicBoolean(false);
    private volatile boolean loaded = false;

    static CallsignDatabase getInstance() {
        return INSTANCE;
    }

    private CallsignDatabase() {}

    boolean isKnown(String callsign) {
        if (callsign == null) return false;
        return callsigns.contains(callsign.trim().toUpperCase());
    }

    /**
     * Încarcă baza (din cache local sau, la prima rulare, din seed-ul din APK)
     * și, dacă a trecut destul timp, încearcă în fundal o actualizare de pe
     * supercheckpartial.com. Sigur de apelat de pe UI thread - toată munca
     * grea (citit fișiere, rețea) se face pe un thread separat.
     */
    void ensureLoadedAndMaybeUpdate(Context context) {
        final Context appContext = context.getApplicationContext();
        new Thread(() -> {
            if (!loaded) {
                incarcaInitial(appContext);
            }
            verificaSiActualizeaza(appContext);
        }, "CallsignDbInit").start();
    }

    private void incarcaInitial(Context context) {
        File cacheFile = new File(context.getFilesDir(), CACHE_FILENAME);
        if (cacheFile.exists()) {
            Set<String> parsed = parseazaFisier(cacheFile);
            if (parsed.size() >= MIN_VALID_LINES) {
                inlocuiesteDate(parsed);
                Log.i(TAG, "Loaded " + parsed.size() + " callsigns from local cache (" + cacheFile.getAbsolutePath() + ")");
                return;
            }
            Log.w(TAG, "Cache file exists but only had " + parsed.size() + " valid lines - falling back to bundled seed");
        }
        // Fallback: seed-ul inclus în APK (funcționează offline din prima instalare)
        try (InputStream in = context.getAssets().open(SEED_ASSET)) {
            Set<String> parsed = parseazaStream(in);
            inlocuiesteDate(parsed);
            Log.i(TAG, "Loaded " + parsed.size() + " callsigns from bundled seed asset (" + SEED_ASSET + ")");
        } catch (IOException e) {
            // Fără seed și fără cache - rămânem cu setul gol, validarea devine no-op.
            Log.e(TAG, "Could not load bundled seed asset - callsign validation disabled", e);
        }
    }

    private void verificaSiActualizeaza(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long lastUpdate = prefs.getLong(PREF_LAST_UPDATE, 0);
        boolean ePrimaData = lastUpdate == 0;
        if (!ePrimaData && System.currentTimeMillis() - lastUpdate < UPDATE_INTERVAL_MS) {
            long zileRamase = (UPDATE_INTERVAL_MS - (System.currentTimeMillis() - lastUpdate)) / (24 * 60 * 60 * 1000);
            Log.d(TAG, "Skipping update check - last successful update was recent (next check in ~" + zileRamase + " day(s))");
            return;
        }
        if (!updateInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Update already in progress, skipping duplicate trigger");
            return;
        }

        Log.i(TAG, "Checking for a fresh MASTER.SCP from " + REMOTE_URL + " ...");
        try {
            Set<String> proaspat = descarcaDePeInternet();
            if (proaspat != null && proaspat.size() >= MIN_VALID_LINES) {
                salveazaInCache(context, proaspat);
                inlocuiesteDate(proaspat);
                prefs.edit().putLong(PREF_LAST_UPDATE, System.currentTimeMillis()).apply();
                Log.i(TAG, "Update successful: " + proaspat.size() + " callsigns loaded and cached");
            } else if (proaspat != null) {
                Log.w(TAG, "Download returned only " + proaspat.size() + " valid lines (< " + MIN_VALID_LINES
                        + ") - looks suspicious, ignoring and keeping previous data");
            } else {
                Log.w(TAG, "Download failed (no network, timeout, or non-200 response) - keeping previous data");
            }
        } finally {
            updateInProgress.set(false);
        }
    }

    private Set<String> descarcaDePeInternet() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(REMOTE_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP " + code + " while downloading MASTER.SCP");
                return null;
            }
            try (InputStream in = conn.getInputStream()) {
                return parseazaStream(in);
            }
        } catch (Exception e) {
            Log.w(TAG, "Exception while downloading MASTER.SCP: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void salveazaInCache(Context context, Set<String> date) {
        File cacheFile = new File(context.getFilesDir(), CACHE_FILENAME);
        File tmpFile = new File(context.getFilesDir(), CACHE_FILENAME + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
            StringBuilder sb = new StringBuilder();
            for (String c : date) sb.append(c).append('\n');
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            return;
        }
        // Scriere "atomica" - inlocuim cache-ul vechi doar dupa ce noul fisier e complet pe disc.
        tmpFile.renameTo(cacheFile);
    }

    private Set<String> parseazaFisier(File f) {
        try (InputStream in = new FileInputStream(f)) {
            return parseazaStream(in);
        } catch (IOException e) {
            return new HashSet<>();
        }
    }

    private Set<String> parseazaStream(InputStream in) throws IOException {
        Set<String> rezultat = new HashSet<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String linie;
            while ((linie = br.readLine()) != null) {
                linie = linie.trim().toUpperCase();
                if (linie.isEmpty() || linie.startsWith("!") || linie.startsWith("#")) continue;
                rezultat.add(linie);
            }
        }
        return rezultat;
    }

    private void inlocuiesteDate(Set<String> nou) {
        synchronized (callsigns) {
            callsigns.clear();
            callsigns.addAll(nou);
        }
        loaded = true;
    }
}
