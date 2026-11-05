package com.example.yaesucatcontroladv;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.After;
import org.junit.Test;

import java.util.Random;

/**
 * Detectia polaritatii de la A-TUNE (RttyPolarity), pe semnalul sintetic din RttyDemodulatorTest:
 * NORM (Space deasupra Mark-ului) si REV (Space sub Mark, cum se aude RTTY-U fara REV), curat si
 * cu zgomot, cu acordul de la A-TUNE (binuri FFT de 15.6 Hz, deci pana la ~8 Hz abatere), cu
 * tastare manuala; pe zgomot fara semnal - nesigur.
 */
public class RttyPolarityTest {

    private static final int FS = 48000;
    private static final String TEXT = "CQ CQ CQ DE YO9VA YO9VA YO9VA PSE K\n"
            + "UR RST 599 599 NAME EUGEN QTH PLOIESTI 73\n";

    @After
    public void reseteaza() {
        RttyDemodulatorTest.spaceSub = false;
        RttyDemodulatorTest.pauzeTastare = false;
    }

    /** Ultimele 5 s, ca la A-TUNE. */
    private static RttyPolarity.Rezultat detecteaza(short[] pcm, double josHz) {
        RttyPolarity.Istoric ist = new RttyPolarity.Istoric(FS, 6);
        ist.adauga(pcm, pcm.length);
        short[] a = ist.ultimele(5 * FS);
        return RttyPolarity.detecteaza(a, a.length, FS, josHz, josHz + 170);
    }

    private static short[] semnal(boolean rev, double snrDb, long seed, double offsetHz) {
        RttyDemodulatorTest.spaceSub = rev;
        return RttyDemodulatorTest.genereaza(TEXT, snrDb, seed, offsetHz, RttyPolarity.BAUD);
    }

    @Test
    public void normCurat() {
        RttyPolarity.Rezultat r = detecteaza(semnal(false, Double.POSITIVE_INFINITY, 1, 0), 2125);
        assertEquals(Boolean.TRUE, r.markJos);
    }

    @Test
    public void revCurat() {
        // Mark 2125, Space 1955: tonul de jos e Space
        RttyPolarity.Rezultat r = detecteaza(semnal(true, Double.POSITIVE_INFINITY, 1, 0), 1955);
        assertEquals(Boolean.FALSE, r.markJos);
    }

    @Test
    public void cuZgomotSiAcordulDeLaATune() {
        for (boolean rev : new boolean[]{false, true}) {
            for (double acord : new double[]{-8, 0, 8}) {
                for (long seed = 1; seed <= 3; seed++) {
                    double jos = rev ? 1955 : 2125;
                    RttyPolarity.Rezultat r = detecteaza(semnal(rev, 6.0, seed, 0), jos + acord);
                    assertEquals((rev ? "REV" : "NORM") + " acord " + acord + " seed " + seed
                                    + " scoruri " + r.scorJos + "/" + r.scorSus,
                            Boolean.valueOf(!rev), r.markJos);
                }
            }
        }
    }

    @Test
    public void tastareManuala() {
        RttyDemodulatorTest.pauzeTastare = true;
        for (boolean rev : new boolean[]{false, true}) {
            RttyPolarity.Rezultat r = detecteaza(semnal(rev, 10.0, 2, 0), rev ? 1955 : 2125);
            assertEquals(Boolean.valueOf(!rev), r.markJos);
        }
    }

    /**
     * RYRY (biti alternanti) se incadreaza aproape la fel de bine si cu polaritatea inversata - pe
     * inregistrarea reala 14089 incadrarea singura gresea ~6% din decizii. Duratele (stopul de 1.5
     * biti e pe Mark) trebuie sa decida corect, pe NORM si pe REV, curat si cu zgomot.
     */
    @Test
    public void ryry() {
        String ry = "RYRYRYRYRYRYRYRYRYRYRYRYRYRYRYRYRYRYRYRYRYRYRYRYRYRYRYRY\n"
                + "RYRYRYRYRYRYRYRYRYRYRYRYRYRYRYRYRYRYRYRYRYRYRYRYRYRYRYRY\n";
        for (boolean rev : new boolean[]{false, true}) {
            for (double snr : new double[]{Double.POSITIVE_INFINITY, 10.0, 6.0}) {
                for (long seed = 1; seed <= 3; seed++) {
                    RttyDemodulatorTest.spaceSub = rev;
                    short[] pcm = RttyDemodulatorTest.genereaza(ry, snr, seed, 0, RttyPolarity.BAUD);
                    RttyPolarity.Rezultat r = detecteaza(pcm, rev ? 1955 : 2125);
                    assertEquals((rev ? "REV" : "NORM") + " SNR " + snr + " seed " + seed
                                    + " fractii " + r.fractieJos + "/" + r.fractieSus,
                            Boolean.valueOf(!rev), r.markJos);
                }
            }
        }
    }

    @Test
    public void zgomotFaraSemnalNesigur() {
        for (long seed = 1; seed <= 50; seed++) {
            Random rnd = new Random(seed);
            short[] pcm = new short[6 * FS];
            for (int i = 0; i < pcm.length; i++) pcm[i] = (short) (rnd.nextGaussian() * 3000);
            assertNull("seed " + seed, detecteaza(pcm, 2125).markJos);
        }
    }

    @Test
    public void semnalPreaScurtNesigur() {
        short[] pcm = new short[FS / 2];
        assertNull(RttyPolarity.detecteaza(pcm, pcm.length, FS, 2125, 2295).markJos);
    }

    @Test
    public void istoricPastreazaUltimele() {
        RttyPolarity.Istoric ist = new RttyPolarity.Istoric(10, 0.5);   // 5 esantioane
        ist.adauga(new short[]{1, 2, 3}, 3);
        assertArrayEquals(new short[]{1, 2, 3}, ist.ultimele(10));
        ist.adauga(new short[]{4, 5, 6, 7}, 4);
        assertArrayEquals(new short[]{3, 4, 5, 6, 7}, ist.ultimele(10));
        assertArrayEquals(new short[]{6, 7}, ist.ultimele(2));
        ist.goleste();
        assertEquals(0, ist.ultimele(10).length);
    }
}
