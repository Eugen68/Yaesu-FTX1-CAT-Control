package com.example.yaesucatcontroladv.cwtraining;

import com.ddsoftware.cw.util.SoundLowLatency;

/**
 * Public Sound Generator for Morse CW audio feedback.
 * Uses low-latency native C/C++ audio (libsound.so) when available, or Java AudioTrack synthesis as fallback.
 */
public class SoundGenerator {
    private SoundLowLatency nativeSound;
    private JavaAudioTrackSynthesizer javaSynthesizer;
    private final boolean useNative;

    public SoundGenerator(int wpm, int frequency) {
        if (SoundLowLatency.isLibraryLoaded()) {
            nativeSound = new SoundLowLatency(wpm, frequency);
            useNative = true;
        } else {
            javaSynthesizer = new JavaAudioTrackSynthesizer(wpm, frequency);
            useNative = false;
        }
    }

    public void setWpm(int wpm) {
        if (useNative && nativeSound != null) {
            nativeSound.setWpm(wpm);
        } else if (javaSynthesizer != null) {
            javaSynthesizer.setWpm(wpm);
        }
    }

    public void setFrequency(int frequency) {
        if (useNative && nativeSound != null) {
            nativeSound.setFrequency(frequency);
        } else if (javaSynthesizer != null) {
            javaSynthesizer.setFrequency(frequency);
        }
    }

    public void setEnvelopePercentage(long percentage) {
        if (useNative && nativeSound != null) {
            nativeSound.setEnvelopePercentage(percentage);
        } else if (javaSynthesizer != null) {
            javaSynthesizer.setEnvelopePercentage(percentage);
        }
    }

    public void playDit() {
        if (useNative && nativeSound != null) {
            nativeSound.playDit();
        } else if (javaSynthesizer != null) {
            javaSynthesizer.playDit();
        }
    }

    public void playDah() {
        if (useNative && nativeSound != null) {
            nativeSound.playDah();
        } else if (javaSynthesizer != null) {
            javaSynthesizer.playDah();
        }
    }

    public void start() {
        if (useNative && nativeSound != null) {
            nativeSound.start();
        }
    }

    public void stop() {
        if (useNative && nativeSound != null) {
            nativeSound.stop();
        } else if (javaSynthesizer != null) {
            javaSynthesizer.releaseTracks();
        }
    }

    public void clear() {
        if (useNative && nativeSound != null) {
            nativeSound.clear();
        }
    }
}
