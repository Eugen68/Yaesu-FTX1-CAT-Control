package com.example.yaesucatcontroladv;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Model de date pentru un spot DX din cluster.
 * Conține toate informațiile relevante: frecvență, indicativ DX, comentariu,
 * stația care a spotat, timpul spotului, banda și modul deduse automat.
 */
public class Spot {
    public double frequency;      // Frecvența în MHz (ex: 14.250)
    public String dxCall;         // Indicativul stației DX (ex: "W1AW")
    public String comment;        // Comentariul spotter-ului (ex: "CQ NA")
    public String spotter;        // Indicativul celui care a spotat (ex: "YO9VA")
    public long timestamp;        // Timestamp-ul spotului (millis de la epoch)
    public String band;           // Banda dedusă (ex: "20m")
    public String mode;           // Modul dedus (ex: "SSB", "CW", "RTTY")

    public Spot() {
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * Deduce banda din frecvență conform standardului IARU/ARRL.
     */
    public static String deduceBanda(double freqMhz) {
        if (freqMhz >= 1.8 && freqMhz < 2.0) return "160m";
        if (freqMhz >= 3.5 && freqMhz < 4.0) return "80m";
        if (freqMhz >= 5.2 && freqMhz < 5.5) return "60m";
        if (freqMhz >= 7.0 && freqMhz < 7.3) return "40m";
        if (freqMhz >= 10.1 && freqMhz < 10.15) return "30m";
        if (freqMhz >= 14.0 && freqMhz < 14.35) return "20m";
        if (freqMhz >= 18.068 && freqMhz < 18.168) return "17m";
        if (freqMhz >= 21.0 && freqMhz < 21.45) return "15m";
        if (freqMhz >= 24.89 && freqMhz < 24.99) return "12m";
        if (freqMhz >= 28.0 && freqMhz < 29.7) return "10m";
        if (freqMhz >= 50.0 && freqMhz < 54.0) return "6m";
        if (freqMhz >= 144.0 && freqMhz < 148.0) return "2m";
        if (freqMhz >= 430.0 && freqMhz < 450.0) return "70cm";
        return "??";
    }

    /**
     * Deduce modul din comentariu sau din frecvență (fallback inteligent).
     */
    public static String deduceMod(String comment, double freqMhz) {
        if (comment == null) comment = "";
        String upper = comment.toUpperCase();

        // 1. Prioritate: Detectare explicită din comentariu (cea mai sigură)
        if (upper.contains("FT8")) return "FT8";
        if (upper.contains("FT4")) return "FT4";
        if (upper.contains("CW") || upper.contains("MORSE") || upper.contains("TEST")) {
             // TEST poate fi și SSB, dar de obicei pe cluster apare la CW contest.
             // Dacă e în segment de SSB, ignorăm "TEST".
             if (!esteInSegmentSSB(freqMhz)) return "CW";
        }
        if (upper.contains("RTTY") || upper.contains("TELETYPE")) return "RTTY";
        if (upper.contains("JS8")) return "JS8";
        if (upper.contains("PSK") || upper.contains("PSK31")) return "PSK";
        if (upper.contains("SSB") || upper.contains("LSB") || upper.contains("USB") || upper.contains("PHONE")) return "SSB";
        if (upper.contains("AM")) return "AM";
        if (upper.contains("FM")) return "FM";

        // 2. Fallback: Deducere din segmentele de bandă (IARU Region 1)
        int freqKhzInt = (int)((freqMhz * 1000) % 1000);

        // FT8/FT4 au frecvențe fixe "standard"
        if (freqKhzInt == 74 || freqKhzInt == 73 || freqKhzInt == 76) return "FT8"; // 14.074, 7.074, 21.074, 28.074...
        if (freqKhzInt == 80 || freqKhzInt == 91) return "FT4";

        if (esteInSegmentCW(freqMhz)) return "CW";
        if (esteInSegmentSSB(freqMhz)) return "SSB";

        return "DATA";
    }

    private static boolean esteInSegmentCW(double f) {
        if (f >= 1.800 && f < 1.838) return true;
        if (f >= 3.500 && f < 3.570) return true;
        if (f >= 7.000 && f < 7.040) return true;
        if (f >= 10.100 && f < 10.130) return true;
        if (f >= 14.000 && f < 14.070) return true;
        if (f >= 18.068 && f < 18.095) return true;
        if (f >= 21.000 && f < 21.070) return true;
        if (f >= 24.890 && f < 24.915) return true;
        if (f >= 28.000 && f < 28.070) return true;
        return false;
    }

    private static boolean esteInSegmentSSB(double f) {
        if (f >= 1.840 && f < 2.000) return true;
        if (f >= 3.600 && f < 3.800) return true;
        if (f >= 7.060 && f < 7.200) return true;
        if (f >= 14.101 && f < 14.350) return true;
        if (f >= 18.111 && f < 18.168) return true;
        if (f >= 21.151 && f < 21.450) return true;
        if (f >= 24.931 && f < 24.990) return true;
        if (f >= 28.300 && f < 29.700) return true;
        return false;
    }

    /**
     * Formatează timestamp-ul ca oră UTC (HH:MM).
     */
    public String getOraUTC() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(timestamp));
    }

    /**
     * Verifică dacă spotul este mai vechi decât X minute.
     */
    public boolean esteMaiVechiDe(int minute) {
        long diffMillis = System.currentTimeMillis() - timestamp;
        long diffMinute = diffMillis / (60 * 1000);
        return diffMinute > minute;
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "%.3f %s %s %s %s",
                frequency, dxCall, comment, spotter, getOraUTC());
    }
}