package com.example.yaesucatcontroladv.cwtraining;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Handles Morse code timing calculations based on WPM (Words Per Minute) speed
 * and schedules character / word space timers.
 */
public class MorseTimingEngine {
    private int wpm;
    private int ditDurationMs;
    private int dahDurationMs;
    private int elementSpaceMs;
    private int characterSpaceMs;
    private long wordSpaceMs;

    private ScheduledExecutorService executorService;
    private ScheduledFuture<?> characterTimerFuture;
    private ScheduledFuture<?> wordTimerFuture;

    public MorseTimingEngine(int wpm) {
        setWpm(wpm);
    }

    public void setWpm(int wpm) {
        synchronized (this) {
            if (executorService == null) {
                executorService = Executors.newScheduledThreadPool(3);
            }
        }
        if (wpm < 5) wpm = 5;
        if (wpm > 50) wpm = 50;
        this.wpm = wpm;

        this.ditDurationMs = 1200 / wpm;
        this.dahDurationMs = 3600 / wpm;
        this.elementSpaceMs = 1200 / wpm;
        this.characterSpaceMs = 2400 / wpm;
        this.wordSpaceMs = 7200 / wpm;
    }

    public int getWpm() {
        return wpm;
    }

    public int getDitDurationMs() {
        return ditDurationMs;
    }

    public int getDahDurationMs() {
        return dahDurationMs;
    }

    public int getElementSpaceMs() {
        return elementSpaceMs;
    }

    public int getCharacterSpaceMs() {
        return characterSpaceMs;
    }

    public long getWordSpaceMs() {
        return wordSpaceMs;
    }

    public synchronized void cancelTimers() {
        if (characterTimerFuture != null) {
            characterTimerFuture.cancel(true);
            characterTimerFuture = null;
        }
        if (wordTimerFuture != null) {
            wordTimerFuture.cancel(true);
            wordTimerFuture = null;
        }
    }

    public synchronized void scheduleCharacterSpace(Runnable onCharacterSpaceDone) {
        cancelTimers();
        if (executorService != null) {
            characterTimerFuture = executorService.schedule(onCharacterSpaceDone, characterSpaceMs, TimeUnit.MILLISECONDS);
        }
    }

    public synchronized void scheduleWordSpace(Runnable onWordSpaceDone) {
        if (wordSpaceMs > 0 && executorService != null) {
            wordTimerFuture = executorService.schedule(onWordSpaceDone, wordSpaceMs, TimeUnit.MILLISECONDS);
        }
    }

    public synchronized void stop() {
        cancelTimers();
        if (executorService != null) {
            executorService.shutdown();
            executorService = null;
        }
    }

    // Callbacks to be overridden by subclasses
    protected void onElementStart() {
        cancelTimers();
    }

    protected void onElementFinish() {
        // Overridden in subclass
    }

    protected void onCharacterSpaceComplete() {
        // Overridden in subclass
    }

    protected void onWordSpaceComplete() {
        // Overridden in subclass
    }
}
