package com.example.yaesucatcontroladv;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Model de date pentru un raport de recepție PSKReporter.
 * Reprezintă o stație care a auzit/decodat semnalul nostru (senderCallsign = MY CALL).
 */
public class PskSpot {
    public String receiverCallsign;
    public String receiverLocator;
    public String senderLocator;
    public double frequencyMhz;
    public String mode;
    public Integer snrDb;         // poate lipsi pentru unele moduri (ex. CW fără SNR raportat)
    public long timestampSeconds; // flowStartSeconds (epoch UTC)
    public String band;
    public Double distanceKm;     // null dacă locatoarele lipsesc/sunt invalide

    public String getOraUTC() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(timestampSeconds * 1000L));
    }
}
