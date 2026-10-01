package com.example.yaesucatcontroladv.cwtraining;

/**
 * Listener interface for keyer events (decoded characters, dit/dah playback).
 */
public interface MorseKeyerListener {
    /**
     * Called when a full Morse character sequence has been recognized.
     */
    void onCharacterDecoded(String character);

    /**
     * Called when a Dah (dash) element starts playback.
     */
    void onDahPlayed();

    /**
     * Called when a Dit (dot) element starts playback.
     */
    void onDitPlayed();
}
