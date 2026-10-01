package com.ddsoftware.cw.util;

import android.util.Log;

/**
 * Internal JNI Bridge class for native libsound.so library.
 * Keeps exact class and package name required by precompiled JNI method signatures.
 */
public class SoundLowLatency {
    private static boolean libraryLoaded = false;

    static {
        try {
            System.loadLibrary("sound");
            libraryLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            Log.e("SoundLowLatency", "Could not load libsound.so: " + e.getMessage());
        }
    }

    public SoundLowLatency(int wpm, int frequency) {
        if (libraryLoaded) {
            try {
                initNativeSound(wpm, frequency);
            } catch (Throwable t) {
                Log.e("SoundLowLatency", "Error initializing native sound: " + t.getMessage());
            }
        }
    }

    public static boolean isLibraryLoaded() {
        return libraryLoaded;
    }

    private native void initNativeSound(int wpm, int frequency);
    private native void play_dit();
    private native void play_dah();
    private native void doSetWpm(int wpm);
    private native void doSetFrequency(int frequency);
    private native void doSetEnvelopePercentage(long percentage);
    private native void doClear();
    private native void doStart();
    private native void doStop();

    public void setWpm(int wpm) {
        if (libraryLoaded) doSetWpm(wpm);
    }

    public void setFrequency(int frequency) {
        if (libraryLoaded) doSetFrequency(frequency);
    }

    public void setEnvelopePercentage(long percentage) {
        if (libraryLoaded) doSetEnvelopePercentage(percentage);
    }

    public void playDit() {
        if (libraryLoaded) play_dit();
    }

    public void playDah() {
        if (libraryLoaded) play_dah();
    }

    public void clear() {
        if (libraryLoaded) doClear();
    }

    public void start() {
        if (libraryLoaded) doStart();
    }

    public void stop() {
        if (libraryLoaded) doStop();
    }
}
