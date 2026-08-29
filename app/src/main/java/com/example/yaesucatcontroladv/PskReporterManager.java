package com.example.yaesucatcontroladv;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Interoghează API-ul public de query al PSKReporter (retrieve.pskreporter.info)
 * pentru a afla ce stații au auzit/decodat semnalul nostru (senderCallsign = MY CALL).
 * Documentație: https://pskreporter.info/pskdev.html
 */
public class PskReporterManager {
    private static final String TAG = "PskReporterManager";
    private static final String QUERY_URL = "https://retrieve.pskreporter.info/query";
    private static final int TIMEOUT_MS = 15000;

    public interface Listener {
        void onReportsReceived(List<PskSpot> spots);
        void onError(String message);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Interoghează PSKReporter pentru rapoartele de recepție ale indicativului dat.
     * @param callsign        indicativul propriu (senderCallsign în interogare)
     * @param lookbackSeconds fereastra de timp (ex. 1800 pentru ultimele 30 minute)
     */
    public void fetchHeardMe(String callsign, int lookbackSeconds, Listener listener) {
        executor.execute(() -> {
            try {
                List<PskSpot> spots = doFetch(callsign, lookbackSeconds);
                mainHandler.post(() -> listener.onReportsReceived(spots));
            } catch (Exception e) {
                Log.e(TAG, "Eroare la interogarea PSKReporter", e);
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                mainHandler.post(() -> listener.onError(msg));
            }
        });
    }

    private List<PskSpot> doFetch(String callsign, int lookbackSeconds) throws Exception {
        String urlStr = QUERY_URL
                + "?senderCallsign=" + URLEncoder.encode(callsign, "UTF-8")
                + "&flowStartSeconds=-" + lookbackSeconds
                + "&rronly=1&noactive=1&appcontact=" + URLEncoder.encode("yaesucatcontroladv-app", "UTF-8");

        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestMethod("GET");
        // PSKReporter cere clienților frecvenți să activeze compresia, pentru a reduce
        // sarcina pe server (vezi pskreporter.info/pskdev.html).
        conn.setRequestProperty("Accept-Encoding", "gzip");
        try {
            int code = conn.getResponseCode();
            if (code == 429) {
                throw new Exception("Rate limited (429) - interoghezi prea des, așteaptă câteva minute");
            }
            if (code != 200) {
                throw new Exception("HTTP " + code + " de la PSKReporter");
            }
            InputStream raw = conn.getInputStream();
            InputStream in = "gzip".equalsIgnoreCase(conn.getContentEncoding())
                    ? new java.util.zip.GZIPInputStream(raw) : raw;
            try {
                return parseResponse(in);
            } finally {
                in.close();
            }
        } finally {
            conn.disconnect();
        }
    }

    private List<PskSpot> parseResponse(InputStream in) throws Exception {
        List<PskSpot> result = new ArrayList<>();

        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(in, null);

        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && "receptionReport".equals(parser.getName())) {
                PskSpot spot = parseReceptionReport(parser);
                if (spot != null) result.add(spot);
            }
            eventType = parser.next();
        }

        Collections.sort(result, (a, b) -> Long.compare(b.timestampSeconds, a.timestampSeconds));
        return result;
    }

    private PskSpot parseReceptionReport(XmlPullParser parser) {
        try {
            PskSpot spot = new PskSpot();
            spot.receiverCallsign = parser.getAttributeValue(null, "receiverCallsign");
            spot.receiverLocator = parser.getAttributeValue(null, "receiverLocator");
            spot.senderLocator = parser.getAttributeValue(null, "senderLocator");
            spot.mode = parser.getAttributeValue(null, "mode");

            String freqStr = parser.getAttributeValue(null, "frequency");
            if (freqStr != null) {
                spot.frequencyMhz = Double.parseDouble(freqStr) / 1_000_000.0;
            }

            String flowStr = parser.getAttributeValue(null, "flowStartSeconds");
            spot.timestampSeconds = flowStr != null ? Long.parseLong(flowStr) : (System.currentTimeMillis() / 1000L);

            String snrStr = parser.getAttributeValue(null, "sNR");
            if (snrStr != null) {
                try {
                    spot.snrDb = (int) Math.round(Double.parseDouble(snrStr));
                } catch (NumberFormatException ignored) {}
            }

            if (spot.receiverCallsign == null) return null;

            spot.band = Spot.deduceBanda(spot.frequencyMhz);
            spot.distanceKm = MaidenheadUtil.distanceKm(spot.senderLocator, spot.receiverLocator);
            return spot;
        } catch (Exception e) {
            Log.e(TAG, "Eroare la parsarea unui receptionReport", e);
            return null;
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
