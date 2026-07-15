package com.example.yaesucatcontroladv;

/**
 * Stare partajată, simplă, cu ultima frecvență/mod primite prin CAT
 * de la stație. Nu e o soluție "elegantă" (ar putea fi un listener
 * dedicat), dar e simplă, robustă și potrivită cu restul stilului
 * acestui proiect.
 * <p>
 * MainActivity actualizează aceste valori direct din listener-ul CAT
 * central, de fiecare dată când vede un răspuns "FA" (frecvență VFO-A)
 * sau "MD" (mod). EcranLogger le citește când deschizi formularul de
 * QSO nou, după ce a cerut explicit frecvența curentă (comanda "FA;").
 */
public class StareRadio {

    private static volatile long ultimaFrecventaHz = 0L;
    private static volatile String ultimulModText = "";

    public static void setFrecventaHz(long hz) {
        ultimaFrecventaHz = hz;
    }

    public static long getFrecventaHz() {
        return ultimaFrecventaHz;
    }

    public static void setModText(String mod) {
        ultimulModText = mod;
    }

    public static String getModText() {
        return ultimulModText;
    }

    /** Deriva denumirea benzii radioamator (ex: "20m") dintr-o frecventa in Hz. Aproximativ, pe benzile HF/VHF uzuale. */
    public static String deriveazaBanda(long hz) {
        double mhz = hz / 1_000_000.0;
        if (mhz >= 1.8 && mhz <= 2.0) return "160m";
        if (mhz >= 3.5 && mhz <= 4.0) return "80m";
        if (mhz >= 5.3 && mhz <= 5.4) return "60m";
        if (mhz >= 7.0 && mhz <= 7.3) return "40m";
        if (mhz >= 10.1 && mhz <= 10.15) return "30m";
        if (mhz >= 14.0 && mhz <= 14.35) return "20m";
        if (mhz >= 18.068 && mhz <= 18.168) return "17m";
        if (mhz >= 21.0 && mhz <= 21.45) return "15m";
        if (mhz >= 24.89 && mhz <= 24.99) return "12m";
        if (mhz >= 28.0 && mhz <= 29.7) return "10m";
        if (mhz >= 50.0 && mhz <= 54.0) return "6m";
        if (mhz >= 144.0 && mhz <= 148.0) return "2m";
        if (mhz >= 420.0 && mhz <= 450.0) return "70cm";
        return "";
    }
}
