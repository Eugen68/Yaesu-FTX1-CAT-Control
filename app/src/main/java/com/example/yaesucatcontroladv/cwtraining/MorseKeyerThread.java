package com.example.yaesucatcontroladv.cwtraining;

import java.util.LinkedList;

/**
 * Background keyer thread that processes Morse elements (Dits and Dahs) from a queue
 * and plays sound tones with accurate timing.
 */
public abstract class MorseKeyerThread extends MorseTimingEngine implements Runnable {
    private final LinkedList<CwElement> elementQueue = new LinkedList<>();
    private final MorseSymbol currentSymbol = new MorseSymbol();
    private final MorseDictionary dictionary = new MorseDictionary();

    protected SoundGenerator soundGenerator;
    protected MorseKeyerListener listener;

    private Thread keyerThread;
    private volatile boolean isRunning;
    private boolean repeatEnabled = true;

    public MorseKeyerThread(int wpm, int frequency, MorseKeyerListener listener) {
        super(wpm);
        this.listener = listener;
        this.soundGenerator = new SoundGenerator(wpm, frequency);
    }

    public void setRepeatEnabled(boolean enabled) {
        this.repeatEnabled = enabled;
    }

    public boolean isRepeatEnabled() {
        return repeatEnabled;
    }

    public void setSoundFrequency(int frequency) {
        if (soundGenerator != null) {
            soundGenerator.setFrequency(frequency);
        }
    }

    @Override
    public void setWpm(int wpm) {
        super.setWpm(wpm);
        if (soundGenerator != null) {
            soundGenerator.setWpm(wpm);
        }
    }

    public void startKeyer() {
        if (keyerThread == null) {
            isRunning = true;
            keyerThread = new Thread(this, "MorseKeyerThread");
            keyerThread.start();
            if (soundGenerator != null) {
                soundGenerator.start();
            }
        }
    }

    public void stopKeyer() {
        isRunning = false;
        super.stop();
        if (soundGenerator != null) {
            soundGenerator.stop();
        }
        synchronized (elementQueue) {
            elementQueue.notifyAll();
        }
        if (keyerThread != null) {
            keyerThread.interrupt();
            keyerThread = null;
        }
    }

    protected void enqueueElement(CwElement element) {
        synchronized (elementQueue) {
            if (elementQueue.isEmpty()) {
                elementQueue.add(element);
                elementQueue.notifyAll();
            }
        }
    }

    private void playDitElement() {
        currentSymbol.addDit();
        cancelTimers();
        long startTime = System.currentTimeMillis();
        if (soundGenerator != null) {
            soundGenerator.playDit();
        }
        if (listener != null) {
            listener.onDitPlayed();
        }

        waitElement(startTime, getDitDurationMs());
        scheduleCharacterSpace(this::onCharacterSpaceDone);
    }

    private void playDahElement() {
        currentSymbol.addDah();
        cancelTimers();
        long startTime = System.currentTimeMillis();
        if (soundGenerator != null) {
            soundGenerator.playDah();
        }
        if (listener != null) {
            listener.onDahPlayed();
        }

        waitElement(startTime, getDahDurationMs());
        scheduleCharacterSpace(this::onCharacterSpaceDone);
    }

    /**
     * Asteapta tonul + pauza dintre elemente, apeland onElementMidpoint() la jumatatea
     * tonului (punctul de decizie pentru squeeze in Iambic B).
     */
    private void waitElement(long startTime, int toneMs) {
        sleepUntil(startTime + toneMs / 2);
        onElementMidpoint();
        sleepUntil(startTime + toneMs + getElementSpaceMs());
    }

    private static void sleepUntil(long targetTime) {
        long remaining = targetTime - System.currentTimeMillis();
        if (remaining > 0) {
            try {
                Thread.sleep(remaining);
            } catch (InterruptedException ignored) {}
        }
    }

    private void onCharacterSpaceDone() {
        String decoded = dictionary.decode(currentSymbol);
        if (listener != null) {
            // "*" = combinatie de puncte/linii care nu e un caracter Morse valid.
            listener.onCharacterDecoded(decoded != null ? decoded : "*");
        }
        currentSymbol.reset();
        scheduleWordSpace(this::onWordSpaceDone);
    }

    private void onWordSpaceDone() {
        if (listener != null) {
            listener.onCharacterDecoded(" ");
        }
    }

    @Override
    public void run() {
        while (isRunning) {
            CwElement element;
            synchronized (elementQueue) {
                while (elementQueue.isEmpty()) {
                    if (!isRunning) return;
                    try {
                        elementQueue.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                element = elementQueue.removeFirst();
            }

            if (element == CwElement.CW_DIT) {
                playDitElement();
            } else if (element == CwElement.CW_DAH) {
                playDahElement();
            }
            onElementDone();
        }
    }

    protected abstract void onElementDone();

    /** Apelat pe firul keyer-ului la jumatatea tonului fiecarui element. */
    protected void onElementMidpoint() {}
}
