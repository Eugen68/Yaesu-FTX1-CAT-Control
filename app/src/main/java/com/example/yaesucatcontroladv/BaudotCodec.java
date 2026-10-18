package com.example.yaesucatcontroladv;

/**
 * Codec ITA2 (Baudot) pentru RTTY - encodare/decodare caractere <-> coduri de 5 biti,
 * inclusiv gestiunea starii LTRS/FIGS (shift).
 *
 * REFACTORIZARE: extras din EcranRttyDroid.java (care avea 1200+ linii, amestecand UI,
 * DSP audio si logica de encodare Baudot in aceeasi clasa). Aceasta clasa nu are nicio
 * dependenta de Android - e testabila independent, cu simple teste unitare Java.
 *
 * Fiecare directie de comunicare (receptie si transmisie) are propria stare LTRS/FIGS,
 * deci foloseste propria instanta de BaudotCodec (nu e stare statica/globala).
 */
public class BaudotCodec {

    public static final char[] ITA2_LTRS_MAP = {
            '\0', 'E', '\n', 'A', ' ', 'S', 'I', 'U', '\n', 'D', 'R', 'J', 'N', 'F', 'C', 'K',
            'T', 'Z', 'L', 'W', 'H', 'Y', 'P', 'Q', 'O', 'B', 'G', '\0', 'M', 'X', 'V', '\0'
    };
    public static final char[] ITA2_FIGS_MAP = {
            '\0', '3', '\n', '-', ' ', '\'', '8', '7', '\n', '4', '4', '\u0007', ',', '!', ':', '(',
            '5', '+', ')', '2', '#', '6', '0', '1', '9', '?', '&', '\0', '.', '/', ';', '\0'
    };
    /**
     * Varianta US-TTY (folosita de multe statii americane, ex. W6IWI): fata de tabelul de mai
     * sus difera doar in FIGS - S = BELL, J = apostrof, Z = ghilimele, D = $.
     */
    public static final char[] US_TTY_FIGS_MAP = {
            '\0', '3', '\n', '-', ' ', '\u0007', '8', '7', '\n', '$', '4', '\'', ',', '!', ':', '(',
            '5', '"', ')', '2', '#', '6', '0', '1', '9', '?', '&', '\0', '.', '/', ';', '\0'
    };

    private static final int[] CHAR_TO_BAUDOT_CODE = new int[256];
    private static final boolean[] CHAR_IS_FIGS = new boolean[256];
    private static final int[] CHAR_TO_BAUDOT_CODE_US = new int[256];
    private static final boolean[] CHAR_IS_FIGS_US = new boolean[256];

    static {
        construiesteTabelEmisie(ITA2_FIGS_MAP, CHAR_TO_BAUDOT_CODE, CHAR_IS_FIGS);
        construiesteTabelEmisie(US_TTY_FIGS_MAP, CHAR_TO_BAUDOT_CODE_US, CHAR_IS_FIGS_US);
    }

    private static void construiesteTabelEmisie(char[] figsMap, int[] coduri, boolean[] esteFigs) {
        for (int i = 0; i < 32; i++) {
            char l = ITA2_LTRS_MAP[i];
            if (l != '\0' && l != '\n') { coduri[l] = i; esteFigs[l] = false; }
            char f = figsMap[i];
            if (f != '\0' && f != '\n' && f != '\u0007') { coduri[f] = i; esteFigs[f] = true; }
        }
        coduri[' '] = 4;  esteFigs[' '] = false;
        coduri['\n'] = 2; esteFigs['\n'] = false;
        coduri['\r'] = 8; esteFigs['\r'] = false;
    }

    /** Starea curenta LTRS('L')/FIGS('F') a acestei instante (RX sau TX, independente). */
    private char shift = 'L';

    /** false = tabelul ITA2 de mai sus, true = US-TTY. */
    private volatile boolean usTty = false;

    public boolean isUsTty() {
        return usTty;
    }

    public void setUsTty(boolean usTty) {
        this.usTty = usTty;
    }

    public char getShift() {
        return shift;
    }

    /** Reseteaza starea la LTRS - apelata de obicei la inceputul unei transmisii/receptii noi. */
    public void resetShift() {
        shift = 'L';
    }

    private boolean esteFigs(char c) {
        return c < 256 && (usTty ? CHAR_IS_FIGS_US : CHAR_IS_FIGS)[c];
    }

    private int codBaudotPentru(char c) {
        return c < 256 ? (usTty ? CHAR_TO_BAUDOT_CODE_US : CHAR_TO_BAUDOT_CODE)[c] : 0;
    }

    /**
     * Decodeaza un cod Baudot de 5 biti (0-31), actualizand starea interna LTRS/FIGS.
     * @return caracterul decodat, sau '\0' daca e un cod de control (shift) fara
     *         caracter vizibil de afisat.
     */
    public char decode(int code) {
        if (code == 0x1F) { shift = 'L'; return '\0'; }
        if (code == 0x1B) { shift = 'F'; return '\0'; }
        if (code == 0x04) { shift = 'L'; } // spatiul reseteaza mereu la LTRS (comportament original pastrat)
        return (shift == 'L') ? ITA2_LTRS_MAP[code] : (usTty ? US_TTY_FIGS_MAP : ITA2_FIGS_MAP)[code];
    }

    /**
     * Encodeaza un singur caracter (deja normalizat uppercase de apelant) in unul sau mai multe
     * coduri Baudot de trimis. Actualizeaza starea interna LTRS/FIGS.
     */
    public int[] encode(char c) {
        if (c == '\n' || c == '\r') {
            // Standard RTTY: Carriage Return (2) + Line Feed (8). Starea de shift NU se
            // schimba: receptoarele revin pe LTRS doar la spatiu (USOS), nu la CR/LF, deci
            // dupa un rand terminat in FIGS urmatoarea litera trebuie sa trimita explicit
            // LTRS - altfel "1234\nUR" ajunge la corespondent ca "1234\n74".
            return new int[]{2, 8};
        }

        if (c == ' ') {
            shift = 'L';
            return new int[]{4};
        }

        boolean cereFigs = esteFigs(c);
        if (cereFigs && shift == 'L') {
            shift = 'F';
            return new int[]{0x1B, codBaudotPentru(c)};
        } else if (!cereFigs && shift == 'F') {
            shift = 'L';
            return new int[]{0x1F, codBaudotPentru(c)};
        }
        
        return new int[]{codBaudotPentru(c)};
    }
}
