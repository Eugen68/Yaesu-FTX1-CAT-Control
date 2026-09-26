package com.example.yaesucatcontroladv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Pagina LOG STATISTICS: statistici calculate pe tot logul (QsoDbHelper).
 * <p>
 * Ordinea secțiunilor: interval (primul -> ultimul QSO, ora locală), total,
 * naționale (defalcate pe districte), internaționale (defalcate pe țări DXCC,
 * via cty.dat), bandă × mod, distribuția pe ore (locală), grid-uri și distanța maximă.
 * <p>
 * Calculul se face pe un thread separat (încărcarea cty.dat + parcurgerea
 * logului), rezultatul se afișează pe UI thread.
 */
public class EcranStatisticiLog {

    private static final String CULOARE_FUNDAL = "#112855";      // ca splash-ul de pornire
    private static final String CULOARE_SECTIUNE = "#1C3A73";
    private static final String CULOARE_TITLU = "#FFC107";
    private static final String CULOARE_TEXT = "#FFFFFF";
    private static final String CULOARE_TEXT_SECUNDAR = "#B0C4DE";
    private static final String CULOARE_BARA = "#4FC3F7";

    private static final List<String> ORDINE_BENZI = Arrays.asList(
            "2190M", "630M", "160M", "80M", "60M", "40M", "30M", "20M", "17M", "15M",
            "12M", "10M", "6M", "4M", "2M", "1.25M", "70CM", "23CM");

    private static final Pattern GRID = Pattern.compile("^[A-R]{2}[0-9]{2}([A-X]{2})?.*");

    private final Context context;
    private final QsoDbHelper dbHelper;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ScrollView viewPrincipal;
    private LinearLayout continut;

    public EcranStatisticiLog(Context context) {
        this.context = context;
        this.dbHelper = new QsoDbHelper(context);
        construiesteInterfata();
    }

    public ScrollView getView() {
        reincarca();
        return viewPrincipal;
    }

    private void construiesteInterfata() {
        viewPrincipal = new ScrollView(context);
        viewPrincipal.setBackgroundColor(Color.parseColor(CULOARE_FUNDAL));
        viewPrincipal.setFillViewport(true);

        continut = new LinearLayout(context);
        continut.setOrientation(LinearLayout.VERTICAL);
        continut.setPadding(dp(14), dp(14), dp(14), dp(24));
        viewPrincipal.addView(continut);
    }

    private void reincarca() {
        continut.removeAllViews();
        continut.addView(text("Calculating...", 15, CULOARE_TEXT_SECUNDAR, false));

        new Thread(() -> {
            Statistici s = calculeaza();
            handler.post(() -> afiseaza(s));
        }, "LogStats").start();
    }

    // ------------------------------------------------------------------
    // Calcul
    // ------------------------------------------------------------------

    private static final class Statistici {
        int total;
        Date primul, ultimul;
        String numeTaraMea = "";
        String prefixTaraMea = "";
        int nationale, internationale, necunoscute;
        final Map<String, Integer> districte = new TreeMap<>();
        final Map<CtyDatabase.Entitate, Integer> tari = new HashMap<>();
        final Map<String, Map<String, Integer>> bandaMod = new HashMap<>();
        final Set<String> moduri = new LinkedHashSet<>();
        final int[] ore = new int[24];
        final Set<String> griduri = new HashSet<>();
        double distantaMax = -1;
        String callDistantaMax, gridDistantaMax;
        int cuGrid;
        // Estimare pentru QSO-urile internaționale fără grid: distanța până în centrul țării (cty.dat)
        double distantaEstimataMax = -1;
        String callEstimat, taraEstimata;
    }

    private Statistici calculeaza() {
        Statistici s = new Statistici();
        List<QsoDbHelper.Qso> qsouri = dbHelper.getToateQsourile();
        s.total = qsouri.size();
        if (s.total == 0) return s;

        CtyDatabase cty = CtyDatabase.getInstance(context);
        String prefsMyCall = context.getSharedPreferences(EcranSetariProprii.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(EcranSetariProprii.PREF_MY_CALL, "");
        String prefsMyGrid = context.getSharedPreferences(EcranSetariProprii.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(EcranSetariProprii.PREF_MY_GRID, "");

        CtyDatabase.Entitate taraMea = cty.cauta(prefsMyCall);
        if (taraMea == null) {
            for (QsoDbHelper.Qso q : qsouri) {
                taraMea = cty.cauta(q.myCall);
                if (taraMea != null) break;
            }
        }
        if (taraMea == null) taraMea = cty.cauta("YO");
        if (taraMea != null) {
            s.numeTaraMea = taraMea.nume;
            s.prefixTaraMea = taraMea.prefix;
        }

        SimpleDateFormat sdfUtc = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
        sdfUtc.setTimeZone(TimeZone.getTimeZone("UTC"));
        java.util.Calendar calLocal = java.util.Calendar.getInstance();

        for (QsoDbHelper.Qso q : qsouri) {
            // Interval + distribuția pe ore (ora locală)
            Date d = parseazaDataUtc(sdfUtc, q.qsoDate, q.timeOn);
            if (d != null) {
                if (s.primul == null || d.before(s.primul)) s.primul = d;
                if (s.ultimul == null || d.after(s.ultimul)) s.ultimul = d;
                calLocal.setTime(d);
                s.ore[calLocal.get(java.util.Calendar.HOUR_OF_DAY)]++;
            }

            // Național / internațional
            CtyDatabase.Entitate e = cty.cauta(q.call);
            if (e == null) {
                s.necunoscute++;
            } else if (taraMea != null && e.nume.equals(taraMea.nume)) {
                s.nationale++;
                Character cifra = CtyDatabase.districtul(q.call);
                String cheie = cifra == null ? "?" : String.valueOf(cifra);
                s.districte.merge(cheie, 1, Integer::sum);
            } else {
                s.internationale++;
                s.tari.merge(e, 1, Integer::sum);
            }

            // Bandă × mod
            String banda = normalizeazaBanda(q.band);
            String mod = normalizeazaMod(q.mode);
            s.moduri.add(mod);
            s.bandaMod.computeIfAbsent(banda, k -> new HashMap<>()).merge(mod, 1, Integer::sum);

            // Grid-uri + distanța maximă
            String grid = q.gridsquare == null ? "" : q.gridsquare.trim().toUpperCase(Locale.US);
            String gridulMeu = q.myGridsquare != null && !q.myGridsquare.trim().isEmpty()
                    ? q.myGridsquare.trim().toUpperCase(Locale.US)
                    : prefsMyGrid.trim().toUpperCase(Locale.US);
            boolean amGridulMeu = GRID.matcher(gridulMeu).matches();
            if (!GRID.matcher(grid).matches()) {
                // Fără grid: pentru DX estimăm după centrul țării. Pentru stațiile naționale
                // centrul propriei țări n-are sens, așa că nu le estimăm.
                boolean esteNational = e != null && taraMea != null && e.nume.equals(taraMea.nume);
                if (amGridulMeu && e != null && !esteNational) {
                    double km = distantaKm(gridLaLatLon(gridulMeu), new double[]{e.lat, e.lon});
                    if (km > s.distantaEstimataMax) {
                        s.distantaEstimataMax = km;
                        s.callEstimat = q.call;
                        s.taraEstimata = e.nume;
                    }
                }
            } else {
                s.cuGrid++;
                s.griduri.add(grid.substring(0, 4));
                if (amGridulMeu) {
                    double km = distantaKm(gridulMeu, grid);
                    if (km > s.distantaMax) {
                        s.distantaMax = km;
                        s.callDistantaMax = q.call;
                        s.gridDistantaMax = grid;
                    }
                }
            }
        }
        return s;
    }

    private static Date parseazaDataUtc(SimpleDateFormat sdf, String data, String ora) {
        if (data == null || data.length() != 8) return null;
        String o = ora == null ? "" : ora.replace(":", "").trim();
        if (o.length() < 4) return null;
        while (o.length() < 6) o += "0";
        try {
            return sdf.parse(data + o.substring(0, 6));
        } catch (ParseException e) {
            return null;
        }
    }

    private static String normalizeazaBanda(String banda) {
        if (banda == null || banda.trim().isEmpty()) return "?";
        return banda.trim().toUpperCase(Locale.US);
    }

    private static String normalizeazaMod(String mod) {
        if (mod == null || mod.trim().isEmpty()) return "?";
        String m = mod.trim().toUpperCase(Locale.US);
        if (m.equals("USB") || m.equals("LSB")) return "SSB";
        return m;
    }

    /** Centrul unui locator Maidenhead (4 sau 6 caractere) -> {lat, lon}. */
    private static double[] gridLaLatLon(String g) {
        double lon = (g.charAt(0) - 'A') * 20 - 180 + (g.charAt(2) - '0') * 2;
        double lat = (g.charAt(1) - 'A') * 10 - 90 + (g.charAt(3) - '0');
        if (g.length() >= 6 && g.charAt(4) >= 'A' && g.charAt(4) <= 'X'
                && g.charAt(5) >= 'A' && g.charAt(5) <= 'X') {
            lon += (g.charAt(4) - 'A') * (5.0 / 60) + 2.5 / 60;
            lat += (g.charAt(5) - 'A') * (2.5 / 60) + 1.25 / 60;
        } else {
            lon += 1;
            lat += 0.5;
        }
        return new double[]{lat, lon};
    }

    private static double distantaKm(String g1, String g2) {
        return distantaKm(gridLaLatLon(g1), gridLaLatLon(g2));
    }

    /** Distanța ortodromică (haversine) între două puncte {lat, lon}, în km. */
    private static double distantaKm(double[] a, double[] b) {
        double lat1 = Math.toRadians(a[0]), lat2 = Math.toRadians(b[0]);
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(b[1] - a[1]);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
    }

    // ------------------------------------------------------------------
    // Afișare
    // ------------------------------------------------------------------

    private void afiseaza(Statistici s) {
        continut.removeAllViews();
        continut.addView(text("LOG STATISTICS", 20, CULOARE_TITLU, true));

        if (s.total == 0) {
            continut.addView(spatiu(12));
            continut.addView(text("The log contains no QSOs.", 16, CULOARE_TEXT, false));
            return;
        }

        // 1. Interval
        SimpleDateFormat sdfLocal = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US);
        String interval = (s.primul != null && s.ultimul != null)
                ? sdfLocal.format(s.primul) + "  →  " + sdfLocal.format(s.ultimul)
                : "—";
        continut.addView(spatiu(6));
        continut.addView(text(interval + "  (local time)", 15, CULOARE_TEXT_SECUNDAR, false));

        // 2-4. Totaluri
        LinearLayout sectTotal = sectiune();
        sectTotal.addView(linieMono(String.format(Locale.US, "%-24s%5d", "QSOs:", s.total), true));
        sectTotal.addView(linieMono(String.format(Locale.US, "%-24s%5d",
                "National QSOs" + (s.prefixTaraMea.isEmpty() ? "" : " (" + s.prefixTaraMea + ")") + ":",
                s.nationale), true));
        // Toate districtele apar, inclusiv cele nelucrate (cu 0). Pentru YO districtele
        // uzuale sunt 2..9; pentru alte țări 0..9. Orice alt district lucrat efectiv
        // (ex. indicativ special YO0/YO1) și "?" (necunoscut) se adaugă după caz.
        Map<String, Integer> districteAfisate = new TreeMap<>();
        char primaCifra = "YO".equals(s.prefixTaraMea) ? '2' : '0';
        for (char c = primaCifra; c <= '9'; c++) districteAfisate.put(String.valueOf(c), 0);
        districteAfisate.putAll(s.districte);
        for (Map.Entry<String, Integer> d : districteAfisate.entrySet()) {
            String eticheta = d.getKey().equals("?") ? "unknown district" : s.prefixTaraMea + d.getKey();
            sectTotal.addView(linieMono(String.format(Locale.US, "   %-21s%5d", eticheta, d.getValue()), false));
        }
        sectTotal.addView(linieMono(String.format(Locale.US, "%-24s%5d", "International QSOs:", s.internationale), true));
        if (s.necunoscute > 0) {
            sectTotal.addView(linieMono(String.format(Locale.US, "%-24s%5d", "Unknown country:", s.necunoscute), false));
        }
        continut.addView(sectTotal);

        // 5. Țări
        if (!s.tari.isEmpty()) {
            LinearLayout sectTari = sectiune();
            sectTari.addView(text("Countries (" + s.tari.size() + " DXCC)", 16, CULOARE_TITLU, true));
            List<Map.Entry<CtyDatabase.Entitate, Integer>> lista = new ArrayList<>(s.tari.entrySet());
            lista.sort((a, b) -> {
                int c = Integer.compare(b.getValue(), a.getValue());
                return c != 0 ? c : a.getKey().nume.compareTo(b.getKey().nume);
            });
            for (Map.Entry<CtyDatabase.Entitate, Integer> t : lista) {
                sectTari.addView(linieMono(String.format(Locale.US, "%4d  %-5s %s",
                        t.getValue(), t.getKey().prefix, t.getKey().nume), false));
            }
            continut.addView(sectTari);
        }

        // 6. Bandă × mod
        LinearLayout sectBm = sectiune();
        sectBm.addView(text("Band × Mode", 16, CULOARE_TITLU, true));
        sectBm.addView(tabelBandaMod(s));
        continut.addView(sectBm);

        // 7. Distribuția pe ore
        LinearLayout sectOre = sectiune();
        sectOre.addView(text("QSOs by hour (local time)", 16, CULOARE_TITLU, true));
        adaugaHistogramaOre(sectOre, s.ore);
        continut.addView(sectOre);

        // 8. Grid-uri + distanța maximă
        LinearLayout sectGrid = sectiune();
        sectGrid.addView(text("Grid squares", 16, CULOARE_TITLU, true));
        sectGrid.addView(linieMono(String.format(Locale.US, "%-24s%s", "Grid known:",
                s.cuGrid + " / " + s.total + " QSOs"), false));
        sectGrid.addView(linieMono(String.format(Locale.US, "%-24s%5d", "Unique grids:", s.griduri.size()), false));
        if (s.distantaMax >= 0) {
            sectGrid.addView(linieMono(String.format(Locale.US, "%-24s%5.0f km", "Max distance:", s.distantaMax), false));
            sectGrid.addView(linieMono("   " + s.callDistantaMax + " (" + s.gridDistantaMax + ")", false));
        } else {
            sectGrid.addView(linieMono("Max distance: — (no grids on both ends)", false));
        }
        if (s.distantaEstimataMax >= 0) {
            TextView tvEst = linieMono(String.format(Locale.US, "%-24s~%4.0f km", "Max est. (no grid):",
                    s.distantaEstimataMax), false);
            tvEst.setTextColor(Color.parseColor(CULOARE_TEXT_SECUNDAR));
            sectGrid.addView(tvEst);
            TextView tvEstCall = linieMono("   " + s.callEstimat + " (center of " + s.taraEstimata + ")", false);
            tvEstCall.setTextColor(Color.parseColor(CULOARE_TEXT_SECUNDAR));
            sectGrid.addView(tvEstCall);
        }
        if (s.cuGrid < s.total) adaugaButonCompletareGriduri(sectGrid);
        continut.addView(sectGrid);
    }

    // ------------------------------------------------------------------
    // "Fill missing grids": caută pe rând (QRZ -> HamQTH) QSO-urile fără grid
    // ------------------------------------------------------------------

    private boolean completareInCurs = false;
    // Eticheta de progres curentă (pagina se reconstruiește la fiecare intrare)
    private TextView tvProgresCompletare;

    private void adaugaButonCompletareGriduri(LinearLayout parinte) {
        TextView tvProgres = linieMono("", false);
        tvProgres.setTextColor(Color.parseColor(CULOARE_TEXT_SECUNDAR));
        tvProgres.setVisibility(completareInCurs ? View.VISIBLE : View.GONE);
        if (completareInCurs) tvProgres.setText("Looking up grids...");
        tvProgresCompletare = tvProgres;

        android.widget.Button btn = new android.widget.Button(context);
        btn.setText("FILL MISSING GRIDS (QRZ / HamQTH)");
        btn.setTextColor(Color.WHITE);
        btn.setBackgroundColor(Color.parseColor("#00695C"));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, dp(10), 0, dp(4));
        btn.setLayoutParams(p);
        btn.setEnabled(!completareInCurs);
        btn.setOnClickListener(v -> {
            if (!GridLookup.areCont(context)) {
                android.widget.Toast.makeText(context,
                        "Configure HamQTH.com (free) or QRZ.com in MY SETTINGS first.",
                        android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            btn.setEnabled(false);
            tvProgres.setVisibility(View.VISIBLE);
            pornesteCompletareGriduri();
        });
        parinte.addView(btn);
        parinte.addView(tvProgres);
    }

    private void pornesteCompletareGriduri() {
        if (completareInCurs) return;
        completareInCurs = true;
        tvProgresCompletare.setText("Preparing...");
        new Thread(() -> {
            // Indicativ -> id-urile QSO-urilor fără grid (același indicativ se caută o singură dată)
            Map<String, List<Long>> deCautat = new java.util.LinkedHashMap<>();
            for (QsoDbHelper.Qso q : dbHelper.getToateQsourile()) {
                if (q.gridsquare != null && !q.gridsquare.trim().isEmpty()) continue;
                String call = q.call == null ? "" : q.call.trim().toUpperCase(Locale.US);
                if (call.isEmpty()) continue;
                deCautat.computeIfAbsent(call, k -> new ArrayList<>()).add(q.id);
            }
            List<String> indicative = new ArrayList<>(deCautat.keySet());
            handler.post(() -> cautaUrmatorul(indicative, deCautat, 0, 0));
        }, "FillGridsPrep").start();
    }

    /** Lanț secvențial: câte o căutare pe rând, ca să nu bombardăm serviciile. */
    private void cautaUrmatorul(List<String> indicative, Map<String, List<Long>> deCautat,
                                int index, int gasite) {
        if (index >= indicative.size()) {
            completareInCurs = false;
            int negasite = indicative.size() - gasite;
            android.widget.Toast.makeText(context, "Grids found: " + gasite + " callsign(s), not found: "
                    + negasite, android.widget.Toast.LENGTH_LONG).show();
            reincarca();
            return;
        }
        String call = indicative.get(index);
        tvProgresCompletare.setText(String.format(Locale.US, "Looking up %d / %d: %s   (found %d)",
                index + 1, indicative.size(), call, gasite));
        GridLookup.cauta(context, call, grid -> {
            int gasiteNou = gasite;
            if (grid != null) {
                gasiteNou++;
                for (Long id : deCautat.get(call)) dbHelper.completeazaGridDacaLipseste(id, grid);
            }
            final int g = gasiteNou;
            // mică pauză între cereri
            handler.postDelayed(() -> cautaUrmatorul(indicative, deCautat, index + 1, g), 300);
        });
    }

    private View tabelBandaMod(Statistici s) {
        List<String> benzi = new ArrayList<>(s.bandaMod.keySet());
        benzi.sort((a, b) -> {
            int ia = ORDINE_BENZI.indexOf(a), ib = ORDINE_BENZI.indexOf(b);
            if (ia < 0) ia = 1000;
            if (ib < 0) ib = 1000;
            return ia != ib ? Integer.compare(ia, ib) : a.compareTo(b);
        });
        List<String> moduri = new ArrayList<>(s.moduri);
        moduri.sort(String::compareTo);

        int latime = 5;
        for (String m : moduri) latime = Math.max(latime, m.length() + 1);
        String fmtCel = "%" + latime + "s";

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "%-6s", ""));
        for (String m : moduri) sb.append(String.format(Locale.US, fmtCel, m));
        sb.append(String.format(Locale.US, fmtCel, "ALL"));

        int[] totalPeMod = new int[moduri.size()];
        int totalGeneral = 0;
        for (String b : benzi) {
            sb.append('\n').append(String.format(Locale.US, "%-6s", b.toLowerCase(Locale.US)));
            int totalBanda = 0;
            Map<String, Integer> rand = s.bandaMod.get(b);
            for (int i = 0; i < moduri.size(); i++) {
                Integer v = rand.get(moduri.get(i));
                sb.append(String.format(Locale.US, fmtCel, v == null ? "·" : String.valueOf(v)));
                if (v != null) {
                    totalBanda += v;
                    totalPeMod[i] += v;
                }
            }
            sb.append(String.format(Locale.US, fmtCel, totalBanda));
            totalGeneral += totalBanda;
        }
        sb.append('\n').append(String.format(Locale.US, "%-6s", "ALL"));
        for (int t : totalPeMod) sb.append(String.format(Locale.US, fmtCel, t));
        sb.append(String.format(Locale.US, fmtCel, totalGeneral));

        TextView tv = linieMono(sb.toString(), false);
        tv.setHorizontallyScrolling(true);
        android.widget.HorizontalScrollView hsv = new android.widget.HorizontalScrollView(context);
        hsv.addView(tv);
        return hsv;
    }

    private void adaugaHistogramaOre(LinearLayout parinte, int[] ore) {
        int min = -1, max = -1, varf = 0;
        for (int h = 0; h < 24; h++) {
            if (ore[h] > 0) {
                if (min < 0) min = h;
                max = h;
                varf = Math.max(varf, ore[h]);
            }
        }
        if (min < 0) {
            parinte.addView(linieMono("—", false));
            return;
        }
        for (int h = min; h <= max; h++) {
            LinearLayout rand = new LinearLayout(context);
            rand.setOrientation(LinearLayout.HORIZONTAL);
            rand.setGravity(Gravity.CENTER_VERTICAL);
            rand.setPadding(0, dp(1), 0, dp(1));

            TextView tvOra = linieMono(String.format(Locale.US, "%02dh", h), false);
            tvOra.setLayoutParams(new LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT));
            rand.addView(tvOra);

            LinearLayout zonaBara = new LinearLayout(context);
            zonaBara.setOrientation(LinearLayout.HORIZONTAL);
            zonaBara.setWeightSum(varf);
            zonaBara.setLayoutParams(new LinearLayout.LayoutParams(0, dp(14), 1f));
            if (ore[h] > 0) {
                View bara = new View(context);
                bara.setBackgroundColor(Color.parseColor(CULOARE_BARA));
                bara.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, ore[h]));
                zonaBara.addView(bara);
            }
            rand.addView(zonaBara);

            TextView tvNr = linieMono(String.format(Locale.US, "%5d", ore[h]), false);
            rand.addView(tvNr);
            parinte.addView(rand);
        }
    }

    // ------------------------------------------------------------------
    // Helperi UI
    // ------------------------------------------------------------------

    private LinearLayout sectiune() {
        LinearLayout l = new LinearLayout(context);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundColor(Color.parseColor(CULOARE_SECTIUNE));
        l.setPadding(dp(10), dp(8), dp(10), dp(10));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, dp(12), 0, 0);
        l.setLayoutParams(p);
        return l;
    }

    private TextView text(String s, float sp, String culoare, boolean bold) {
        TextView tv = new TextView(context);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(Color.parseColor(culoare));
        if (bold) tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, 0, 0, dp(4));
        return tv;
    }

    private TextView linieMono(String s, boolean bold) {
        TextView tv = new TextView(context);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTextColor(Color.parseColor(CULOARE_TEXT));
        tv.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        return tv;
    }

    private View spatiu(int dpInaltime) {
        View v = new View(context);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(dpInaltime)));
        return v;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                context.getResources().getDisplayMetrics());
    }
}
