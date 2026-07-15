package com.example.yaesucatcontroladv;

import android.os.Handler;
import android.os.Looper;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

/**
 * Interfațare cu HamQTH.com - serviciu GRATUIT care oferă grid-ul
 * pentru orice indicativ (fără abonament, spre deosebire de QRZ.com).
 * Folosit ca fallback când QRZ nu returnează grid-ul.
 */
public class HamQthLookupHelper {
    private static final String BASE_URL = "https://www.hamqth.com/xml.php";
    private static final String AGENT = "YaesuCatControlADV-1.0";

    private static volatile String sesiuneCache = null;
    private static final Handler handlerUi = new Handler(Looper.getMainLooper());

    public static class HamQthRezultat {
        public String call = "";
        public String nick = "";      // Prenume (echivalent fname)
        public String grid = "";      // Locator (ceea ce ne interesează!)
        public String qth = "";       // Oraș
        public String country = "";
        public String adr_name = "";  // Nume complet
    }

    public interface HamQthCallback {
        void onSucces(HamQthRezultat rezultat);
        void onEroare(String mesaj);
    }

    public static void cautaIndicativ(String user, String parola, String callsign, HamQthCallback callback) {
        new Thread(() -> {
            try {
                String sesiune = sesiuneCache;
                if (sesiune == null) {
                    sesiune = login(user, parola);
                    sesiuneCache = sesiune;
                }

                Map<String, String> raspuns = interogheazaIndicativ(sesiune, callsign);

                // Dacă sesiunea a expirat, ne re-logăm o dată
                if (raspuns.containsKey("Error") && raspuns.get("Error").toLowerCase().contains("session")) {
                    sesiune = login(user, parola);
                    sesiuneCache = sesiune;
                    raspuns = interogheazaIndicativ(sesiune, callsign);
                }

                if (raspuns.containsKey("Error")) {
                    final String eroare = raspuns.get("Error");
                    handlerUi.post(() -> callback.onEroare(eroare));
                    return;
                }

                HamQthRezultat rezultat = new HamQthRezultat();
                rezultat.call = raspuns.getOrDefault("callsign", "");
                rezultat.nick = raspuns.getOrDefault("nick", "");
                rezultat.grid = raspuns.getOrDefault("grid", "");
                rezultat.qth = raspuns.getOrDefault("qth", "");
                rezultat.country = raspuns.getOrDefault("country", "");
                rezultat.adr_name = raspuns.getOrDefault("adr_name", "");

                handlerUi.post(() -> callback.onSucces(rezultat));
            } catch (Exception e) {
                final String mesaj = "Eroare HamQTH: " + e.getMessage();
                handlerUi.post(() -> callback.onEroare(mesaj));
            }
        }, "HamQthLookup").start();
    }

    public static void resetSesiune() {
        sesiuneCache = null;
    }

    private static String login(String user, String pass) throws Exception {
        String url = BASE_URL + "?u=" + URLEncoder.encode(user, "UTF-8")
                + "&p=" + URLEncoder.encode(pass, "UTF-8")
                + "&prg=" + AGENT;
        Map<String, String> raspuns = executaCerere(url);
        if (raspuns.containsKey("Error")) {
            throw new Exception(raspuns.get("Error"));
        }
        String key = raspuns.get("session_id");
        if (key == null) {
            throw new Exception("Login HamQTH eșuat.");
        }
        return key;
    }

    private static Map<String, String> interogheazaIndicativ(String sesiune, String callsign) throws Exception {
        String url = BASE_URL + "?id=" + URLEncoder.encode(sesiune, "UTF-8")
                + "&callsign=" + URLEncoder.encode(callsign, "UTF-8")
                + "&prg=" + AGENT;
        return executaCerere(url);
    }

    private static Map<String, String> executaCerere(String urlString) throws Exception {
        Map<String, String> rezultat = new HashMap<>();
        HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; YaesuCatControlADV/1.0)");

        int codRaspuns = conn.getResponseCode();
        if (codRaspuns != HttpURLConnection.HTTP_OK) {
            throw new Exception("Server HTTP " + codRaspuns);
        }

        try (InputStream in = conn.getInputStream()) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(in, "UTF-8");
            int eventType = parser.getEventType();
            String tagCurent = null;
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    tagCurent = parser.getName();
                } else if (eventType == XmlPullParser.TEXT && tagCurent != null) {
                    String text = parser.getText();
                    if (text != null && !text.trim().isEmpty()) {
                        rezultat.put(tagCurent, text.trim());
                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    tagCurent = null;
                }
                eventType = parser.next();
            }
        } finally {
            conn.disconnect();
        }
        return rezultat;
    }
}