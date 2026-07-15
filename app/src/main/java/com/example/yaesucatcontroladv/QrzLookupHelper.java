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
 * Interfațare cu serviciul XML de căutare indicative de la QRZ.com.
 * <p>
 * Necesită un cont QRZ.com (utilizatorul introduce user/parolă o
 * singură dată, salvate local în SharedPreferences). Datele complete
 * (nume, QTH, grid) necesită un abonament XML activ pe QRZ - fără
 * abonament, unele câmpuri pot lipsi din răspuns (QRZ trimite un
 * mesaj de tip "A subscription is required...").
 * <p>
 * Toate operațiile de rețea rulează pe un thread separat (Android nu
 * permite rețea pe thread-ul UI); rezultatul e livrat prin callback,
 * mutat automat înapoi pe thread-ul UI.
 */
public class QrzLookupHelper {

    private static final String BASE_URL = "https://xmldata.qrz.com/xml/current/";
    private static final String AGENT = "YaesuCatControlADV-1.0";

    // Cache simplu de sesiune in memorie - evitam sa ne logam din nou
    // la fiecare cautare. Daca sesiunea a expirat, ne re-logam automat
    // o singura data si reincercam cautarea.
    private static volatile String sesiuneCache = null;

    private static final Handler handlerUi = new Handler(Looper.getMainLooper());

    public static class QrzRezultat {
        public String call = "";
        public String fname = "";
        public String name = "";
        public String addr2 = "";  // de obicei orașul (folosit ca QTH)
        public String state = "";
        public String country = "";
        public String grid = "";
    }

    public interface QrzCallback {
        void onSucces(QrzRezultat rezultat);
        void onEroare(String mesaj);
    }

    /** Cauta un indicativ pe QRZ.com. Rezultatul (sau eroarea) soseste prin callback, pe thread-ul UI. */
    public static void cautaIndicativ(String userQrz, String parolaQrz, String callsign, QrzCallback callback) {
        new Thread(() -> {
            try {
                String sesiune = sesiuneCache;
                if (sesiune == null) {
                    sesiune = login(userQrz, parolaQrz);
                    sesiuneCache = sesiune;
                }

                Map<String, String> raspuns = interogheazaIndicativ(sesiune, callsign);

                // Daca sesiunea a expirat (raspunsul nu contine Key), ne re-logam o data si reincercam
                if (!raspuns.containsKey("Key")) {
                    sesiune = login(userQrz, parolaQrz);
                    sesiuneCache = sesiune;
                    raspuns = interogheazaIndicativ(sesiune, callsign);
                }

                if (raspuns.containsKey("Error")) {
                    final String eroare = raspuns.get("Error");
                    handlerUi.post(() -> callback.onEroare(eroare));
                    return;
                }

                QrzRezultat rezultat = new QrzRezultat();
                rezultat.call = raspuns.getOrDefault("call", "");
                rezultat.fname = raspuns.getOrDefault("fname", "");
                rezultat.name = raspuns.getOrDefault("name", "");
                rezultat.addr2 = raspuns.getOrDefault("addr2", "");
                rezultat.state = raspuns.getOrDefault("state", "");
                rezultat.country = raspuns.getOrDefault("country", "");
                rezultat.grid = raspuns.getOrDefault("grid", "");

                handlerUi.post(() -> callback.onSucces(rezultat));
            } catch (Exception e) {
                final String mesaj = "Eroare rețea/QRZ: " + e.getMessage();
                handlerUi.post(() -> callback.onEroare(mesaj));
            }
        }, "QrzLookup").start();
    }

    /** Reseteaza sesiunea cache (util daca schimbi userul/parola din UI). */
    public static void resetSesiune() {
        sesiuneCache = null;
    }

    private static String login(String user, String pass) throws Exception {
        String url = BASE_URL + "?username=" + URLEncoder.encode(user, "UTF-8")
                + ";password=" + URLEncoder.encode(pass, "UTF-8")
                + ";agent=" + AGENT;
        Map<String, String> raspuns = executaCerereSiParseaza(url);

        if (raspuns.containsKey("Error")) {
            throw new Exception(raspuns.get("Error"));
        }
        String key = raspuns.get("Key");
        if (key == null) {
            throw new Exception("Login QRZ eșuat - verifică userul/parola.");
        }
        return key;
    }

    private static Map<String, String> interogheazaIndicativ(String sesiune, String callsign) throws Exception {
        String url = BASE_URL + "?s=" + URLEncoder.encode(sesiune, "UTF-8")
                + ";callsign=" + URLEncoder.encode(callsign, "UTF-8");
        return executaCerereSiParseaza(url);
    }

    /**
     * Execută cererea HTTP GET și parsează XML-ul de răspuns intr-o
     * mapă simplă (nume element -> text), suficient pentru structura
     * plată folosită de QRZ (fara elemente cu acelasi nume in contexte
     * diferite care ne intereseaza).
     */
    private static Map<String, String> executaCerereSiParseaza(String urlString) throws Exception {
        Map<String, String> rezultat = new HashMap<>();

        HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestMethod("GET");
        // Unele servicii restricționează cererile fără un User-Agent HTTP
        // recognoscibil (par a veni de la un bot). Setăm unul explicit,
        // pe lângă parametrul agent= cerut de QRZ (cele două sunt lucruri
        // diferite: agent= e parametrul aplicației, User-Agent e header-ul HTTP).
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; YaesuCatControlADV/1.0; +ham-radio-app)");

        int codRaspuns = conn.getResponseCode();
        if (codRaspuns != HttpURLConnection.HTTP_OK) {
            throw new Exception("Server HTTP " + codRaspuns + " (" + conn.getResponseMessage() + ")");
        }

        try (InputStream in = conn.getInputStream()) {
            // Citim tot raspunsul brut o data - ne ajuta la diagnostic
            // daca parsarea XML esueaza (ex: raspuns neasteptat, pagina
            // de eroare HTML in loc de XML, etc).
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] data = new byte[2048];
            int nRead;
            while ((nRead = in.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            String raspunsBrut = buffer.toString("UTF-8");

            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(new java.io.StringReader(raspunsBrut));

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

            // Daca nu am gasit NIMIC recognoscibil (nici Key, nici Error),
            // raspunsul e probabil neasteptat - aratam un fragment brut ca
            // sa se poata diagnostica exact ce a trimis serverul.
            if (!rezultat.containsKey("Key") && !rezultat.containsKey("Error") && !rezultat.containsKey("call")) {
                String fragment = raspunsBrut.length() > 200 ? raspunsBrut.substring(0, 200) + "..." : raspunsBrut;
                throw new Exception("Răspuns neașteptat de la server: " + fragment);
            }
        } finally {
            conn.disconnect();
        }

        return rezultat;
    }
}