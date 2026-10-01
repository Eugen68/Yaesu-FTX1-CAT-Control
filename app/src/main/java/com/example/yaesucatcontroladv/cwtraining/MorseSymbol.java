package com.example.yaesucatcontroladv.cwtraining;

import java.util.Arrays;

/**
 * Represents a sequence of dits and dahs forming a Morse symbol.
 */
public final class MorseSymbol {
    private final char[] pattern;
    private int length;

    public MorseSymbol() {
        this.pattern = new char[8];
        this.length = 0;
    }

    public MorseSymbol(String code) {
        this.pattern = code.toCharArray();
        this.length = code.length();
    }

    public void addDit() {
        if (length < pattern.length) {
            pattern[length++] = '.';
        }
    }

    public void addDah() {
        if (length < pattern.length) {
            pattern[length++] = '-';
        }
    }

    public void reset() {
        length = 0;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MorseSymbol)) return false;
        MorseSymbol other = (MorseSymbol) obj;
        if (this.length != other.length) return false;
        for (int i = 0; i < length; i++) {
            if (this.pattern[i] != other.pattern[i]) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int hash = 0;
        for (int i = 0; i < length; i++) {
            hash ^= pattern[i] + length;
        }
        return hash;
    }

    @Override
    public String toString() {
        return new String(pattern, 0, length);
    }
}
