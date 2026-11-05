package com.example.yaesucatcontroladv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

/**
 * QSY-ul de la dublu tap pe A-TUNE (RttyQsy): frecventa statiei pentru emisie in RTTY-L / RTTY-U
 * (aceleasi cazuri ca HamRig RigMath.QsyDial in C#) si acordul fin al tonurilor.
 */
public class RttyQsyTest {

    private static final int FS = 48000;
    private static final String TEXT = "CQ CQ CQ DE YO9VA YO9VA YO9VA PSE K\n"
            + "UR RST 599 599 NAME EUGEN QTH PLOIESTI 73\n";

    @After
    public void reseteaza() {
        RttyDemodulatorTest.spaceSub = false;
        RttyDemodulatorTest.pauzeTastare = false;
    }

    @Test
    public void rttyLCaLsb() {
        // Mark auzit la 2175 (50 Hz mai sus): in RTTY-L semnalul e cu 50 Hz mai jos in RF
        assertEquals(Long.valueOf(3_500_950), RttyQsy.frecventaPentruEmisie(3_501_000, '6', 2175));
        assertEquals(Long.valueOf(3_501_050), RttyQsy.frecventaPentruEmisie(3_501_000, '6', 2075));
    }

    @Test
    public void rttyUCaUsb() {
        assertEquals(Long.valueOf(14_085_050), RttyQsy.frecventaPentruEmisie(14_085_000, '9', 2175));
        assertEquals(Long.valueOf(14_084_950), RttyQsy.frecventaPentruEmisie(14_085_000, '9', 2075));
    }

    @Test
    public void deJaPeFrecventa() {
        assertEquals(Long.valueOf(7_080_000), RttyQsy.frecventaPentruEmisie(7_080_000, '6', 2125));
    }

    @Test
    public void doarInRttySiCuFrecventaCunoscuta() {
        assertNull(RttyQsy.frecventaPentruEmisie(7_080_000, '2', 2175));   // USB
        assertNull(RttyQsy.frecventaPentruEmisie(7_080_000, 'C', 2175));   // DATA-U
        assertNull(RttyQsy.frecventaPentruEmisie(0, '6', 2175));
        assertNull(RttyQsy.frecventaPentruEmisie(7_080_000, ' ', 2175));
    }

    @Test
    public void codulModului() {
        assertEquals('6', RttyQsy.codMod("06"));
        assertEquals('9', RttyQsy.codMod("09;"));
        assertEquals('C', RttyQsy.codMod("0c"));
        assertEquals(' ', RttyQsy.codMod(""));
        assertEquals(' ', RttyQsy.codMod(null));
    }

    /** Binurile FFT de la A-TUNE au 15.6 Hz: tonul real e oriunde pana la ~8 Hz de estimare. */
    @Test
    public void acordFin() {
        for (boolean rev : new boolean[]{false, true}) {
            for (double offset : new double[]{-7.3, -2.0, 0, 4.6, 7.8}) {
                RttyDemodulatorTest.spaceSub = rev;
                short[] pcm = RttyDemodulatorTest.genereaza(TEXT, 6.0, 3, offset, RttyPolarity.BAUD);
                double josReal = (rev ? 1955 : 2125) + offset;
                double estimat = Math.round(josReal / 15.625) * 15.625;   // cum il da FFT-ul
                double fin = RttyQsy.rafineazaTonJos(pcm, pcm.length, FS, estimat, 170);
                assertTrue((rev ? "REV" : "NORM") + " offset " + offset + ": " + fin + " fata de " + josReal,
                        Math.abs(fin - josReal) <= 1.0);
            }
        }
    }

    @Test
    public void acordFinCuTastareManuala() {
        RttyDemodulatorTest.pauzeTastare = true;
        short[] pcm = RttyDemodulatorTest.genereaza(TEXT, 10.0, 2, 3.4, RttyPolarity.BAUD);
        double fin = RttyQsy.rafineazaTonJos(pcm, pcm.length, FS, 2125, 170);
        assertTrue("fin " + fin, Math.abs(fin - 2128.4) <= 1.0);
    }

    @Test
    public void audioPreaScurtRamaneEstimarea() {
        short[] pcm = new short[FS / 4];
        assertEquals(2125.0, RttyQsy.rafineazaTonJos(pcm, pcm.length, FS, 2125, 170), 0);
    }
}
