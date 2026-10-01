package com.example.yaesucatcontroladv.cwtraining;

import java.util.Hashtable;

/**
 * Dictionary mapping Morse dot/dash patterns to text characters.
 */
public final class MorseDictionary {
    private final Hashtable<MorseSymbol, String> table = new Hashtable<>(60);

    public MorseDictionary() {
        // Letters
        table.put(new MorseSymbol(".-"), "a");
        table.put(new MorseSymbol("-..."), "b");
        table.put(new MorseSymbol("-.-."), "c");
        table.put(new MorseSymbol("-.."), "d");
        table.put(new MorseSymbol("."), "e");
        table.put(new MorseSymbol("..-."), "f");
        table.put(new MorseSymbol("--."), "g");
        table.put(new MorseSymbol("...."), "h");
        table.put(new MorseSymbol(".."), "i");
        table.put(new MorseSymbol(".---"), "j");
        table.put(new MorseSymbol("-.-"), "k");
        table.put(new MorseSymbol(".-.."), "l");
        table.put(new MorseSymbol("--"), "m");
        table.put(new MorseSymbol("-."), "n");
        table.put(new MorseSymbol("---"), "o");
        table.put(new MorseSymbol(".--."), "p");
        table.put(new MorseSymbol("--.-"), "q");
        table.put(new MorseSymbol(".-."), "r");
        table.put(new MorseSymbol("..."), "s");
        table.put(new MorseSymbol("-"), "t");
        table.put(new MorseSymbol("..-"), "u");
        table.put(new MorseSymbol("...-"), "v");
        table.put(new MorseSymbol(".--"), "w");
        table.put(new MorseSymbol("-..-"), "x");
        table.put(new MorseSymbol("-.--"), "y");
        table.put(new MorseSymbol("--.."), "z");

        // Numbers
        table.put(new MorseSymbol(".----"), "1");
        table.put(new MorseSymbol("..---"), "2");
        table.put(new MorseSymbol("...--"), "3");
        table.put(new MorseSymbol("....-"), "4");
        table.put(new MorseSymbol("....."), "5");
        table.put(new MorseSymbol("-...."), "6");
        table.put(new MorseSymbol("--..."), "7");
        table.put(new MorseSymbol("---.."), "8");
        table.put(new MorseSymbol("----."), "9");
        table.put(new MorseSymbol("-----"), "0");

        // Punctuation
        table.put(new MorseSymbol(".-.-.-"), ".");
        table.put(new MorseSymbol("--..--"), ",");
        table.put(new MorseSymbol("-....-"), "-");
        table.put(new MorseSymbol("..--.."), "?");
        table.put(new MorseSymbol(".----."), "'");
        table.put(new MorseSymbol("-.-.--"), "!");
        table.put(new MorseSymbol("-..-."), "/");
        table.put(new MorseSymbol("-.--."), "(");
        table.put(new MorseSymbol("-.--.-"), ")");
        table.put(new MorseSymbol(".-..-."), "\"");
        table.put(new MorseSymbol("---..."), ":");
        table.put(new MorseSymbol("-.-.-."), ";");
        table.put(new MorseSymbol(".--.-."), "@");
        table.put(new MorseSymbol("...-..-"), "$");
        table.put(new MorseSymbol("-...-"), "=");
        table.put(new MorseSymbol(".-.-."), "+");
        table.put(new MorseSymbol(".-..."), "&");
    }

    public String decode(MorseSymbol symbol) {
        return table.get(symbol);
    }
}
