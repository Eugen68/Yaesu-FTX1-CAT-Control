package com.example.yaesucatcontroladv;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class BaudotCodecTest {

    private static String dusIntors(String text, boolean usTtyTx, boolean usTtyRx) {
        BaudotCodec tx = new BaudotCodec();
        BaudotCodec rx = new BaudotCodec();
        tx.setUsTty(usTtyTx);
        rx.setUsTty(usTtyRx);
        StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            for (int cod : tx.encode(c)) {
                char d = rx.decode(cod);
                if (d != '\0') out.append(d);
            }
        }
        return out.toString();
    }

    @Test
    public void usTtyApostrofSiGhilimele() {
        assertEquals("DOG'S \"OK\" $5", dusIntors("DOG'S \"OK\" $5", true, true));
    }

    @Test
    public void ita2Neschimbat() {
        assertEquals("DOG'S 1+1", dusIntors("DOG'S 1+1", false, false));
    }

    /** Apostroful US-TTY e FIGS+J; citit cu tabelul ITA2 iese BELL - motivul optiunii. */
    @Test
    public void apostrofUsTtyCitit() {
        BaudotCodec rx = new BaudotCodec();
        rx.decode(0x1B);
        assertEquals('\u0007', rx.decode(0x0B));
        rx.setUsTty(true);
        assertEquals('\'', rx.decode(0x0B));
    }

    @Test
    public void ltrsDupaRandInCifre() {
        BaudotCodec tx = new BaudotCodec();
        tx.encode('5');
        assertArrayEquals(new int[]{2, 8}, tx.encode('\n'));
        assertArrayEquals(new int[]{0x1F, 0x07}, tx.encode('U'));
        assertEquals("599\nUR", dusIntors("599\nUR", false, false).replace("\n\n", "\n"));
    }
}
