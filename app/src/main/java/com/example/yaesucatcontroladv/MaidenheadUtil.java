package com.example.yaesucatcontroladv;

/**
 * Conversie locator Maidenhead (grid square) în coordonate și calcul de distanță (Haversine).
 */
public class MaidenheadUtil {
    private static final double EARTH_RADIUS_KM = 6371.0;

    /**
     * Convertește un locator Maidenhead (4 sau 6 caractere, ex. "KN34" sau "KN34AB")
     * în {latitudine, longitudine} - punctul central al pătratului/sub-pătratului.
     * Returnează null dacă locatorul e invalid.
     */
    public static double[] locatorToLatLon(String locator) {
        if (locator == null) return null;
        String loc = locator.trim().toUpperCase(java.util.Locale.US);
        if (loc.length() < 4) return null;

        try {
            double lon = (loc.charAt(0) - 'A') * 20.0 - 180.0;
            double lat = (loc.charAt(1) - 'A') * 10.0 - 90.0;
            lon += (loc.charAt(2) - '0') * 2.0;
            lat += (loc.charAt(3) - '0') * 1.0;

            if (loc.length() >= 6) {
                lon += (loc.charAt(4) - 'A') * (2.0 / 24.0);
                lat += (loc.charAt(5) - 'A') * (1.0 / 24.0);
                lon += 1.0 / 24.0; // centrul sub-pătratului
                lat += 0.5 / 24.0;
            } else {
                lon += 1.0; // centrul pătratului de 2° x 1°
                lat += 0.5;
            }

            return new double[]{lat, lon};
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Distanța în km între două locatoare Maidenhead. Null dacă oricare e invalid.
     */
    public static Double distanceKm(String locatorA, String locatorB) {
        double[] a = locatorToLatLon(locatorA);
        double[] b = locatorToLatLon(locatorB);
        if (a == null || b == null) return null;

        double lat1 = Math.toRadians(a[0]);
        double lat2 = Math.toRadians(b[0]);
        double dLat = Math.toRadians(b[0] - a[0]);
        double dLon = Math.toRadians(b[1] - a[1]);

        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
        return EARTH_RADIUS_KM * c;
    }
}
