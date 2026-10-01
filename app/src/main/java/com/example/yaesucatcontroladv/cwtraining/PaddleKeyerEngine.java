package com.example.yaesucatcontroladv.cwtraining;

/**
 * Main Iambic Paddle Keyer Engine.
 * Handles Dit and Dah paddle presses and releases, Iambic mode A/B alternating squeeze,
 * latching, and automatic repeat timing.
 */
public class PaddleKeyerEngine extends MorseKeyerThread {
    private boolean iambicMode = true;
    private volatile boolean iambicModeB = true;
    private boolean ditDown;
    private boolean dahDown;
    private boolean ditLatch;
    private boolean dahLatch;
    private boolean busy;
    private CwElement lastElement = CwElement.CW_NONE;

    public PaddleKeyerEngine(int wpm, int frequency, MorseKeyerListener listener) {
        super(wpm, frequency, listener);
    }

    public void setIambicMode(boolean enabled) {
        this.iambicMode = enabled;
    }

    public boolean isIambicMode() {
        return iambicMode;
    }

    public void setIambicModeB(boolean enabled) {
        this.iambicModeB = enabled;
    }

    /**
     * Called when the Dit paddle (left) is pressed.
     */
    public synchronized void pressDit() {
        if (!ditDown) {
            ditDown = true;
            ditLatch = true;
        }
        startNext(true);
    }

    /**
     * Called when the Dah paddle (right) is pressed.
     */
    public synchronized void pressDah() {
        if (!dahDown) {
            dahDown = true;
            dahLatch = true;
        }
        startNext(true);
    }

    /**
     * Called when the Dit paddle (left) is released.
     */
    public synchronized void releaseDit() {
        ditDown = false;
    }

    /**
     * Called when the Dah paddle (right) is released.
     */
    public synchronized void releaseDah() {
        dahDown = false;
    }

    private synchronized void startNext(boolean useHeld) {
        if (busy) {
            return;
        }
        boolean dit = ditLatch || (useHeld && ditDown);
        boolean dah = dahLatch || (useHeld && dahDown);
        CwElement next;

        if (dit && dah) {
            if (iambicMode) {
                next = (lastElement == CwElement.CW_DIT) ? CwElement.CW_DAH : CwElement.CW_DIT;
            } else {
                next = (lastElement == CwElement.CW_NONE) ? CwElement.CW_DIT : lastElement;
            }
        } else if (dit) {
            next = CwElement.CW_DIT;
        } else if (dah) {
            next = CwElement.CW_DAH;
        } else {
            lastElement = CwElement.CW_NONE;
            return;
        }

        ditLatch = false;
        dahLatch = false;
        lastElement = next;
        busy = true;

        enqueueElement(next);
    }

    /**
     * Iambic B: daca paleta opusa e inca tinuta la jumatatea elementului (squeeze), se mai
     * trimite un element opus chiar daca ambele palete sunt eliberate inainte de final.
     * In Iambic A ramane doar memoria pentru o apasare NOUA a paletei opuse (pressDit/pressDah).
     * Pragul de 50% (ca "paddle memory switchpoint" la WinKeyer) evita ca un A tastat cu
     * atingeri pe ecran sa iasa R - degetul de pe paleta anterioara se ridica tarziu.
     */
    @Override
    protected synchronized void onElementMidpoint() {
        if (!iambicModeB) return;
        if (lastElement == CwElement.CW_DIT && dahDown) {
            dahLatch = true;
        } else if (lastElement == CwElement.CW_DAH && ditDown) {
            ditLatch = true;
        }
    }

    @Override
    protected synchronized void onElementDone() {
        busy = false;
        startNext(isRepeatEnabled());
    }
}
