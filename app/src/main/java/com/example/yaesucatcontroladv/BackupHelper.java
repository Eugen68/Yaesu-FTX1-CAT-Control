package com.example.yaesucatcontroladv;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

public class BackupHelper {
    // NOU: Am adăugat PREF_HAMQTH_USER și PREF_HAMQTH_PASS
    private static final String[] PREF_KEYS = {
            EcranSetariProprii.PREF_MY_CALL,
            EcranSetariProprii.PREF_MY_GRID,
            EcranSetariProprii.PREF_MY_QTH,
            EcranSetariProprii.PREF_MY_ITU_ZONE,
            EcranSetariProprii.PREF_MY_CQ_ZONE,
            EcranSetariProprii.PREF_QRZ_USER,
            EcranSetariProprii.PREF_QRZ_PASS,
            EcranSetariProprii.PREF_HAMQTH_USER,   // ← NOU
            EcranSetariProprii.PREF_HAMQTH_PASS    // ← NOU
    };

    public static class RezultatImport {
        public int qsoImportate = 0;
        public boolean setariRestaurate = false;
        public int macroRestaurate = 0;
    }

    public static String exporta(Context context, QsoDbHelper dbHelper) throws Exception {
        JSONObject radacina = new JSONObject();
        radacina.put("versiune_backup", 3);

        SharedPreferences prefs = context.getSharedPreferences(EcranSetariProprii.PREFS_NAME, Context.MODE_PRIVATE);
        JSONObject setari = new JSONObject();
        for (String cheie : PREF_KEYS) {
            setari.put(cheie, prefs.getString(cheie, ""));
        }
        radacina.put("setari", setari);

        // Backup macro-uri RTTY
        SharedPreferences prefsMacros = context.getSharedPreferences("RTTY_MACROS", Context.MODE_PRIVATE);
        JSONObject macros = new JSONObject();
        for (int i = 0; i < 8; i++) {
            String titlu = prefsMacros.getString("rtty_title_" + i, "");
            String continut = prefsMacros.getString("rtty_content_" + i, "");
            if (!titlu.isEmpty() || !continut.isEmpty()) {
                JSONObject macro = new JSONObject();
                macro.put("titlu", titlu);
                macro.put("continut", continut);
                macros.put(String.valueOf(i), macro);
            }
        }
        radacina.put("rtty_macros", macros);

        JSONArray qsoArray = new JSONArray();
        List<QsoDbHelper.Qso> toate = dbHelper.getToateQsourile();
        for (QsoDbHelper.Qso q : toate) {
            JSONObject o = new JSONObject();
            o.put("call", nullSafe(q.call));
            o.put("qso_date", nullSafe(q.qsoDate));
            o.put("time_on", nullSafe(q.timeOn));
            o.put("band", nullSafe(q.band));
            o.put("freq", nullSafe(q.freq));
            o.put("mode", nullSafe(q.mode));
            o.put("rst_sent", nullSafe(q.rstSent));
            o.put("rst_rcvd", nullSafe(q.rstRcvd));
            o.put("name", nullSafe(q.name));
            o.put("qth", nullSafe(q.qth));
            o.put("gridsquare", nullSafe(q.gridsquare));
            o.put("comment", nullSafe(q.comment));
            o.put("power", nullSafe(q.power));
            o.put("my_call", nullSafe(q.myCall));
            o.put("my_gridsquare", nullSafe(q.myGridsquare));
            qsoArray.put(o);
        }
        radacina.put("qso_log", qsoArray);

        String continut = radacina.toString(2);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
        String numeFisier = "YaesuCat_backup_" + sdf.format(new java.util.Date()) + ".json";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            salveazaViaMediaStore(context, numeFisier, continut);
        } else {
            salveazaLegacy(numeFisier, continut);
        }
        return numeFisier;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.Q)
    private static void salveazaViaMediaStore(Context context, String numeFisier, String continut) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, numeFisier);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/YaesuCatControlADV");
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new Exception("Could not create file in Downloads.");
        try (OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) throw new Exception("Could not open file for writing.");
            out.write(continut.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void salveazaLegacy(String numeFisier, String continut) throws Exception {
        File dirDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File dirAplicatie = new File(dirDownloads, "YaesuCatControlADV");
        if (!dirAplicatie.exists()) dirAplicatie.mkdirs();
        File fisier = new File(dirAplicatie, numeFisier);
        try (FileOutputStream fos = new FileOutputStream(fisier)) {
            fos.write(continut.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static RezultatImport importa(Context context, QsoDbHelper dbHelper, Uri uri) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("Could not read the selected file.");
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) {
                sb.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
            }
        }
        JSONObject radacina = new JSONObject(sb.toString());
        RezultatImport rezultat = new RezultatImport();

        if (radacina.has("setari")) {
            JSONObject setari = radacina.getJSONObject("setari");
            SharedPreferences.Editor editor = context
                    .getSharedPreferences(EcranSetariProprii.PREFS_NAME, Context.MODE_PRIVATE).edit();
            for (String cheie : PREF_KEYS) {
                if (setari.has(cheie)) editor.putString(cheie, setari.getString(cheie));
            }
            editor.apply();
            rezultat.setariRestaurate = true;
        }

        // Restaurare macro-uri RTTY
        if (radacina.has("rtty_macros")) {
            JSONObject macros = radacina.getJSONObject("rtty_macros");
            SharedPreferences.Editor editorMacros = context
                    .getSharedPreferences("RTTY_MACROS", Context.MODE_PRIVATE).edit();

            java.util.Iterator<String> keys = macros.keys();
            while (keys.hasNext()) {
                String index = keys.next();
                JSONObject macro = macros.getJSONObject(index);
                String titlu = macro.optString("titlu", "");
                String continut = macro.optString("continut", "");
                editorMacros.putString("rtty_title_" + index, titlu);
                editorMacros.putString("rtty_content_" + index, continut);
                rezultat.macroRestaurate++;
            }
            editorMacros.apply();
        }

        if (radacina.has("qso_log")) {
            JSONArray qsoArray = radacina.getJSONArray("qso_log");
            for (int i = 0; i < qsoArray.length(); i++) {
                JSONObject o = qsoArray.getJSONObject(i);
                QsoDbHelper.Qso q = new QsoDbHelper.Qso();
                q.call = citesteCampCuFallback(o, "call");
                q.qsoDate = citesteCampCuFallback(o, "qso_date");
                q.timeOn = citesteCampCuFallback(o, "time_on");
                q.band = citesteCampCuFallback(o, "band");
                q.freq = citesteCampCuFallback(o, "freq");
                q.mode = citesteCampCuFallback(o, "mode");
                q.rstSent = citesteCampCuFallback(o, "rst_sent");
                q.rstRcvd = citesteCampCuFallback(o, "rst_rcvd");
                q.name = citesteCampCuFallback(o, "name");
                q.qth = citesteCampCuFallback(o, "qth");
                q.gridsquare = citesteCampCuFallback(o, "gridsquare");
                q.comment = citesteCampCuFallback(o, "comment");
                q.power = citesteCampCuFallback(o, "power");
                q.myCall = citesteCampCuFallback(o, "my_call");
                q.myGridsquare = citesteCampCuFallback(o, "my_gridsquare");

                if (q.call != null && !q.call.trim().isEmpty()) {
                    dbHelper.insereazaQso(q);
                    rezultat.qsoImportate++;
                }
            }
        }
        return rezultat;
    }

    private static String citesteCampCuFallback(JSONObject o, String cheie) {
        try {
            if (o.has(cheie)) return o.optString(cheie, "");
            return o.optString(cheie + " ", "");
        } catch (Exception e) {
            return "";
        }
    }
}