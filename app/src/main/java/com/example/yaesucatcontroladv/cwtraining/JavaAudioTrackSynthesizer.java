package com.example.yaesucatcontroladv.cwtraining;

import android.media.AudioTrack;
import android.util.Log;

/**
 * Pure Java audio synthesizer using Android AudioTrack for generating Morse CW sidetone sine waves.
 */
public class JavaAudioTrackSynthesizer {
    private static final String TAG = "MorseAudioTrack";
    private static final int SAMPLE_RATE = 11050;

    private int frequency = 600;
    private int wpm = 25;
    private long envelopePercentage = 20;

    private short[] ditBuffer;
    private short[] dahBuffer;
    private AudioTrack ditAudioTrack;
    private AudioTrack dahAudioTrack;

    public JavaAudioTrackSynthesizer(int wpm, int frequency) {
        this.wpm = wpm;
        this.frequency = frequency;
        rebuildBuffers();
    }

    public synchronized void setWpm(int wpm) {
        this.wpm = wpm;
        rebuildBuffers();
    }

    public synchronized void setFrequency(int frequency) {
        this.frequency = frequency;
        rebuildBuffers();
    }

    public synchronized void setEnvelopePercentage(long percentage) {
        this.envelopePercentage = percentage;
        rebuildBuffers();
    }

    private synchronized void rebuildBuffers() {
        int ditDurationMs = 1200 / wpm;
        int dahDurationMs = 3600 / wpm;

        ditBuffer = generateSineWave(ditDurationMs, frequency);
        dahBuffer = generateSineWave(dahDurationMs, frequency);

        releaseTracks();
        ditAudioTrack = createAudioTrack(ditBuffer);
        dahAudioTrack = createAudioTrack(dahBuffer);
    }

    private AudioTrack createAudioTrack(short[] pcmData) {
        if (pcmData == null || pcmData.length == 0) return null;
        int bufferSize = pcmData.length * 2;
        try {
            AudioTrack track = new AudioTrack(3, SAMPLE_RATE, 4, 2, bufferSize, 0);
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                track.release();
                return null;
            }
            track.write(pcmData, 0, pcmData.length);
            return track;
        } catch (Exception e) {
            Log.e(TAG, "Error creating AudioTrack: " + e.getMessage());
            return null;
        }
    }

    private short[] generateSineWave(int durationMs, int freq) {
        int numSamples = (SAMPLE_RATE * durationMs) / 1000;
        short[] samples = new short[numSamples];
        long envelopeSamples = (numSamples * envelopePercentage) / 100;

        for (int i = 0; i < numSamples; i++) {
            double angle = (2.0 * Math.PI * i * freq) / SAMPLE_RATE;
            double sampleValue = Math.sin(angle) * 32000.0;

            // Apply envelope rise and fall
            if (i < envelopeSamples && envelopeSamples > 0) {
                double ramp = Math.sin((i / (double) (envelopeSamples * 2)) * Math.PI);
                sampleValue *= ramp;
            } else if (i > numSamples - envelopeSamples && envelopeSamples > 0) {
                double remaining = numSamples - i;
                double ramp = Math.sin((remaining / (double) (envelopeSamples * 2)) * Math.PI);
                sampleValue *= ramp;
            }
            samples[i] = (short) sampleValue;
        }
        return samples;
    }

    public synchronized void playDit() {
        if (ditAudioTrack != null) {
            if (ditAudioTrack.getPlayState() != AudioTrack.PLAYSTATE_STOPPED) {
                ditAudioTrack.stop();
            }
            ditAudioTrack.reloadStaticData();
            ditAudioTrack.play();
        }
    }

    public synchronized void playDah() {
        if (dahAudioTrack != null) {
            if (dahAudioTrack.getPlayState() != AudioTrack.PLAYSTATE_STOPPED) {
                dahAudioTrack.stop();
            }
            dahAudioTrack.reloadStaticData();
            dahAudioTrack.play();
        }
    }

    public synchronized void releaseTracks() {
        if (ditAudioTrack != null) {
            ditAudioTrack.stop();
            ditAudioTrack.release();
            ditAudioTrack = null;
        }
        if (dahAudioTrack != null) {
            dahAudioTrack.stop();
            dahAudioTrack.release();
            dahAudioTrack = null;
        }
    }
}
