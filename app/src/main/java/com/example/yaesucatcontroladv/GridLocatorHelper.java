package com.example.yaesucatcontroladv;

import java.util.Locale;

/**
 * Calculează locatorul Maidenhead (grid square, ex: "KN34xj") dintr-o
 * pereche de coordonate GPS (latitudine/longitudine).
 * <p>
 * Formula standard folosită de toți radioamatorii - un locator de 6
 * caractere (2 litere + 2 cifre + 2 litere), suficient de precis
 * pentru raportare POTA/SOTA (aprox. 5km x 5km rezoluție pe ultimele
 * 2 caractere).
 */
public class GridLocatorHelper {

    public static String calculeazaGrid(double latitudine, double longitudine) {
        // Maidenhead necesită longitudine intre -180 si 180 grade, si latitudine intre -90 si 90 grade
        double lon = longitudine + 180.0;
        double lat = latitudine + 90.0;

        int fieldLon = (int) (lon / 20.0);
        int fieldLat = (int) (lat / 10.0);
        char field1 = (char) ('A' + fieldLon);
        char field2 = (char) ('A' + fieldLat);

        double lonResto1 = lon - (fieldLon * 20.0);
        double latResto1 = lat - (fieldLat * 10.0);
        int squareLon = (int) (lonResto1 / 2.0);
        int squareLat = (int) (latResto1 / 1.0);

        double lonResto2 = lonResto1 - (squareLon * 2.0);
        double latResto2 = latResto1 - (squareLat * 1.0);
        int subsquareLon = (int) (lonResto2 / (2.0 / 24.0));
        int subsquareLat = (int) (latResto2 / (1.0 / 24.0));
        char subField1 = (char) ('a' + subsquareLon);
        char subField2 = (char) ('a' + subsquareLat);

        return String.format(Locale.US, "%c%c%d%d%c%c",
                field1, field2, squareLon, squareLat, subField1, subField2);
    }
}
