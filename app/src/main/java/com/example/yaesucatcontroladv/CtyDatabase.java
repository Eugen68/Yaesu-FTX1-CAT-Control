package com.example.yaesucatcontroladv;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Maparea indicativ -> entitate DXCC, pe baza fișierului standard cty.dat
 * (country-files.com, AD1C) - același folosit de N1MM, Log4OM, DXKeeper.
 * <p>
 * Format cty.dat: o linie de antet per entitate
 * ("Nume: CQ: ITU: Cont: Lat: Lon: TZ: PrefixPrincipal:"), urmată de lista
 * de prefixe/alias-uri separate prin virgulă, terminată cu ';'. Un alias
 * care începe cu '=' e un indicativ exact (ex. =YO9VA), nu un prefix.
 * Modificatorii (n) [n] &lt;lat/lon&gt; {cont} ~tz~ sunt ignorați aici.
 * <p>
 * Entitățile marcate cu '*' (Sicilia, Shetland etc.) sunt doar WAE, nu DXCC -
 * le sărim, astfel încât prefixele lor cad pe entitatea DXCC părinte (IT9 -> Italia).
 * <p>
 * Actualizare (ca la CallsignDatabase / MASTER.SCP): la pornirea aplicației,
 * dacă au trecut 7 zile de la ultima actualizare reușită, se redescarcă
 * silențios fișierul de pe country-files.com. Noua versiune se folosește doar
 * dacă trece de un sanity-check (număr minim de entități); altfel rămânem pe
 * cache-ul local sau pe copia inclusă în APK.
 */
final class CtyDatabase {

    private static final String TAG = "CtyDB";
    private static final String ASSET = "cty.dat";
    private static final String REMOTE_URL = "https://www.country-files.com/cty/cty.dat";
    private static final String CACHE_FILENAME = "cty_cache.dat";
    private static final String PREFS_NAME = "cty_db_prefs";
    private static final String PREF_LAST_UPDATE = "last_update_millis";
    private static final long UPDATE_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000; // 7 zile
    private static final int MIN_ENTITATI_VALIDE = 300; // DXCC are ~340; sanity check anti fisier corupt
    private static final AtomicBoolean updateInProgress = new AtomicBoolean(false);

    /** O entitate DXCC: nume + prefixul principal (ex. "Italy", "I"). */
    static final class Entitate {
        final String nume;
        final String prefix;
        final String continent;
        /** Coordonatele "centrului" entității, în convenția uzuală (est = longitudine pozitivă). */
        final double lat, lon;

        Entitate(String nume, String prefix, String continent, double lat, double lon) {
            this.nume = nume;
            this.prefix = prefix;
            this.continent = continent;
            this.lat = lat;
            this.lon = lon;
        }
    }

    private static volatile CtyDatabase instance;

    private final Map<String, Entitate> prefixe = new HashMap<>();
    private final Map<String, Entitate> indicativeExacte = new HashMap<>();
    private int lungimeMaxPrefix = 0;
    private int numarEntitati = 0;

    /** Instanța curentă: din cache-ul local (dacă e valid), altfel din copia din APK. */
    static CtyDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (CtyDatabase.class) {
                if (instance == null) {
                    instance = incarcaInitial(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private static CtyDatabase incarcaInitial(Context context) {
        File cacheFile = new File(context.getFilesDir(), CACHE_FILENAME);
        if (cacheFile.exists()) {
            CtyDatabase db = new CtyDatabase();
            try (Reader r = new InputStreamReader(new FileInputStream(cacheFile), StandardCharsets.UTF_8)) {
                db.incarca(r);
                if (db.numarEntitati >= MIN_ENTITATI_VALIDE) {
                    Log.i(TAG, "Loaded cty.dat from local cache: " + db.numarEntitati + " entities, "
                            + db.prefixe.size() + " prefixes");
                    return db;
                }
                Log.w(TAG, "Cache had only " + db.numarEntitati + " entities - falling back to bundled asset");
            } catch (IOException e) {
                Log.w(TAG, "Could not read cty.dat cache - falling back to bundled asset", e);
            }
        }
        CtyDatabase db = new CtyDatabase();
        try (Reader r = new InputStreamReader(context.getAssets().open(ASSET), StandardCharsets.UTF_8)) {
            db.incarca(r);
            Log.i(TAG, "Loaded cty.dat from bundled asset: " + db.numarEntitati + " entities, "
                    + db.prefixe.size() + " prefixes");
        } catch (IOException e) {
            Log.e(TAG, "Failed to load bundled cty.dat - country lookup disabled", e);
        }
        return db;
    }

    /**
     * Încarcă baza și, dacă a trecut intervalul de reîmprospătare, încearcă în
     * fundal o actualizare de pe country-files.com. Sigur de apelat de pe UI thread.
     */
    static void ensureLoadedAndMaybeUpdate(Context context) {
        final Context appContext = context.getApplicationContext();
        new Thread(() -> {
            getInstance(appContext);
            verificaSiActualizeaza(appContext);
        }, "CtyDbInit").start();
    }

    private static void verificaSiActualizeaza(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long lastUpdate = prefs.getLong(PREF_LAST_UPDATE, 0);
        if (lastUpdate != 0 && System.currentTimeMillis() - lastUpdate < UPDATE_INTERVAL_MS) {
            Log.d(TAG, "Skipping cty.dat update check - last successful update was recent");
            return;
        }
        if (!updateInProgress.compareAndSet(false, true)) return;

        Log.i(TAG, "Checking for a fresh cty.dat from " + REMOTE_URL + " ...");
        try {
            String text = descarca();
            if (text == null) {
                Log.w(TAG, "cty.dat download failed (no network, timeout, or non-200) - keeping previous data");
                return;
            }
            CtyDatabase nou = new CtyDatabase();
            nou.incarca(new StringReader(text));
            if (nou.numarEntitati < MIN_ENTITATI_VALIDE) {
                Log.w(TAG, "Downloaded cty.dat has only " + nou.numarEntitati
                        + " entities - looks suspicious, ignoring");
                return;
            }
            if (salveazaInCache(context, text)) {
                instance = nou;
                prefs.edit().putLong(PREF_LAST_UPDATE, System.currentTimeMillis()).apply();
                Log.i(TAG, "cty.dat update successful: " + nou.numarEntitati + " entities loaded and cached");
            }
        } catch (IOException e) {
            Log.w(TAG, "Exception while parsing downloaded cty.dat: " + e.getMessage());
        } finally {
            updateInProgress.set(false);
        }
    }

    private static String descarca() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(REMOTE_URL).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP " + code + " while downloading cty.dat");
                return null;
            }
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            Log.w(TAG, "Exception while downloading cty.dat: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static boolean salveazaInCache(Context context, String text) {
        File cacheFile = new File(context.getFilesDir(), CACHE_FILENAME);
        File tmpFile = new File(context.getFilesDir(), CACHE_FILENAME + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
            fos.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        }
        // Scriere "atomica" - inlocuim cache-ul vechi doar dupa ce noul fisier e complet pe disc.
        return tmpFile.renameTo(cacheFile);
    }

    /** Parsează conținutul cty.dat. Separat de Android ca să poată fi testat standalone. */
    void incarca(Reader reader) throws IOException {
        BufferedReader br = new BufferedReader(reader);
        String linie;
        Entitate curenta = null;
        boolean sareEntitatea = false;
        StringBuilder alias = new StringBuilder();

        while ((linie = br.readLine()) != null) {
            if (linie.isEmpty()) continue;
            if (!Character.isWhitespace(linie.charAt(0))) {
                // Linie de antet entitate
                String[] c = linie.split(":");
                if (c.length < 8) continue;
                String prefixPrincipal = c[7].trim();
                sareEntitatea = prefixPrincipal.startsWith("*");
                if (!sareEntitatea) numarEntitati++;
                double lat = 0, lon = 0;
                try {
                    lat = Double.parseDouble(c[4].trim());
                    lon = -Double.parseDouble(c[5].trim()); // cty.dat: vest = pozitiv -> inversăm
                } catch (NumberFormatException ignored) {}
                curenta = new Entitate(c[0].trim(), prefixPrincipal, c[3].trim(), lat, lon);
                alias.setLength(0);
                continue;
            }
            if (curenta == null) continue;
            alias.append(linie.trim());
            if (linie.trim().endsWith(";")) {
                if (!sareEntitatea) inregistreazaAliasuri(curenta, alias.toString());
                alias.setLength(0);
            }
        }
    }

    private void inregistreazaAliasuri(Entitate e, String lista) {
        for (String brut : lista.replace(";", "").split(",")) {
            String a = curataModificatori(brut.trim());
            if (a.isEmpty()) continue;
            if (a.startsWith("=")) {
                indicativeExacte.put(a.substring(1), e);
            } else {
                prefixe.put(a, e);
                if (a.length() > lungimeMaxPrefix) lungimeMaxPrefix = a.length();
            }
        }
    }

    private static String curataModificatori(String s) {
        int i = 0;
        while (i < s.length() && "()[]<>{}~".indexOf(s.charAt(i)) < 0) i++;
        return s.substring(0, i).toUpperCase(Locale.US);
    }

    /** Returnează entitatea DXCC a indicativului, sau null dacă nu se poate determina (ex. /MM). */
    Entitate cauta(String indicativ) {
        if (indicativ == null) return null;
        String call = indicativ.trim().toUpperCase(Locale.US);
        if (call.isEmpty()) return null;

        Entitate exact = indicativeExacte.get(call);
        if (exact != null) return exact;

        String deCautat = indicativPentruPrefix(call);
        if (deCautat == null) return null;

        exact = indicativeExacte.get(deCautat);
        if (exact != null) return exact;
        return cautaPrefixLung(deCautat);
    }

    private Entitate cautaPrefixLung(String s) {
        for (int len = Math.min(lungimeMaxPrefix, s.length()); len > 0; len--) {
            Entitate e = prefixe.get(s.substring(0, len));
            if (e != null) return e;
        }
        return null;
    }

    /**
     * Reduce un indicativ cu '/' la șirul pe care se face căutarea de prefix:
     * - sufixe ca /P /M /QRP /A /B se ignoră;
     * - /MM și /AM (maritim/aeronautic mobil) nu au entitate -> null;
     * - /cifră schimbă districtul: YO3ABC/8 -> YO8ABC;
     * - altfel partea mai scurtă e prefixul de țară: YO/DL1ABC -> YO, DL1ABC/EA8 -> EA8.
     */
    static String indicativPentruPrefix(String call) {
        if (call.indexOf('/') < 0) return call;
        String[] parti = call.split("/");
        String baza = null;
        String prefixTara = null;
        String cifra = null;

        for (String p : parti) {
            if (p.isEmpty()) continue;
            if (p.equals("MM") || p.equals("AM")) return null;
            if (esteSufixIgnorat(p)) continue;
            if (p.length() == 1 && Character.isDigit(p.charAt(0))) {
                cifra = p;
                continue;
            }
            if (baza == null) {
                baza = p;
            } else if (p.length() < baza.length()) {
                prefixTara = p;
            } else {
                prefixTara = baza;
                baza = p;
            }
        }
        if (baza == null) return null;
        if (prefixTara != null) return prefixTara;
        if (cifra != null) return schimbaCifra(baza, cifra);
        return baza;
    }

    private static boolean esteSufixIgnorat(String p) {
        switch (p) {
            case "P": case "M": case "QRP": case "A": case "B": case "LH": case "J": case "R":
                return true;
            default:
                return false;
        }
    }

    /** Înlocuiește prima cifră din indicativ (cea care dă districtul) cu cifra dată. */
    private static String schimbaCifra(String call, String cifra) {
        for (int i = 0; i < call.length(); i++) {
            if (i > 0 && Character.isDigit(call.charAt(i))) {
                return call.substring(0, i) + cifra + call.substring(i + 1);
            }
        }
        return call;
    }

    /**
     * Districtul (cifra) unui indicativ național, după aceleași reguli de '/':
     * YO3ABC -> '3', YR8A -> '8', YO3ABC/8 -> '8', YO/DL1ABC -> null (necunoscut).
     */
    static Character districtul(String indicativ) {
        if (indicativ == null) return null;
        String call = indicativ.trim().toUpperCase(Locale.US);
        String s = indicativPentruPrefix(call);
        if (s == null) return null;
        // Prefix de țară fără cifră (ex. "YO" din YO/DL1ABC) -> district necunoscut
        for (int i = 1; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) return s.charAt(i);
        }
        return null;
    }
}
