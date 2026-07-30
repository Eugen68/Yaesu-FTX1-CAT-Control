package com.example.yaesucatcontroladv;

import android.os.Process;
import android.util.Log;

import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.io.IOException;

/**
 * Motor de manipulare CW (Morse) cu suport Iambic Mode A/B și Straight Key.
 * Utilizează liniile RTS sau DTR de pe portul de date (Enhanced) pentru keying.
 * Implementat sub formă de Mașină de Stare pentru precizie și responsivitate.
 */
public class CwKeyer {
    private static final String TAG = "CwKeyer";

    private final RadioManager radioManager;
    private int wpm = 20;
    private boolean isReversed = false;
    private boolean isStraightKeyMode = false;
    private boolean isIambicModeB = true; 
    private boolean useRts = true;

    private volatile boolean paddleLeftPressed = false;
    private volatile boolean paddleRightPressed = false;
    
    // Flag-uri de intenție (Sticky Triggers) - pentru a nu rata nicio atingere scurtă
    private volatile boolean dotIntent = false;
    private volatile boolean dashIntent = false;

    // Stări Mașină de Stare
    private int lastSentElement = 2; // 1=Dot, 2=Dash.
    private int memoryElement = 0;   // 0=none, 1=Dot, 2=Dash
    private volatile boolean isRunning = true;

    private Thread keyerThread;

    public CwKeyer(RadioManager radioManager) {
        this.radioManager = radioManager;
        startKeyerThread();
    }

    public void setWpm(int wpm) {
        this.wpm = Math.max(10, Math.min(35, wpm));
    }

    public void setReversed(boolean reversed) {
        this.isReversed = reversed;
    }

    public void setStraightKeyMode(boolean straightKeyMode) {
        this.isStraightKeyMode = straightKeyMode;
    }

    public void setIambicModeB(boolean iambicModeB) {
        this.isIambicModeB = iambicModeB;
    }

    public void setUseRts(boolean useRts) {
        this.useRts = useRts;
        setHardwareKey(false);
    }

    public void setLeftPaddle(boolean pressed) {
        this.paddleLeftPressed = pressed;
        if (pressed) {
            if (isReversed) dashIntent = true;
            else dotIntent = true;
        }
    }

    public void setRightPaddle(boolean pressed) {
        this.paddleRightPressed = pressed;
        if (pressed) {
            if (isReversed) dotIntent = true;
            else dashIntent = true;
        }
    }

    private void startKeyerThread() {
        keyerThread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            
            while (isRunning) {
                try {
                    if (isStraightKeyMode) {
                        setHardwareKey(paddleRightPressed);
                        Thread.sleep(2);
                    } else {
                        // Logica Iambic
                        boolean dotActive = (isReversed ? paddleRightPressed : paddleLeftPressed) || dotIntent;
                        boolean dashActive = (isReversed ? paddleLeftPressed : paddleRightPressed) || dashIntent;
                        
                        int elementToSend = 0;

                        if (dotActive && dashActive) {
                            // Squeeze: trimitem opusul ultimului element
                            elementToSend = (lastSentElement == 1) ? 2 : 1;
                        } else if (dotActive) {
                            elementToSend = 1;
                        } else if (dashActive) {
                            elementToSend = 2;
                        } else if (memoryElement != 0) {
                            elementToSend = memoryElement;
                        }

                        if (elementToSend != 0) {
                            // Consumăm intențiile și memoria
                            dotIntent = false;
                            dashIntent = false;
                            memoryElement = 0;
                            
                            sendMorseElement(elementToSend == 1);
                            lastSentElement = elementToSend;
                            
                            // Mode A specific cleanup
                            if (!isIambicModeB) {
                                boolean stillDot = isReversed ? paddleRightPressed : paddleLeftPressed;
                                boolean stillDash = isReversed ? paddleLeftPressed : paddleRightPressed;
                                if (!stillDot && !stillDash) {
                                    memoryElement = 0;
                                }
                            }
                        } else {
                            Thread.sleep(2);
                        }
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "CwKeyerEngine");
        keyerThread.start();
    }

    private void sendMorseElement(boolean isDot) throws InterruptedException {
        int dotDuration = 1200 / wpm;
        int duration = isDot ? dotDuration : dotDuration * 3;

        // --- EMISIE ELEMENT ---
        setHardwareKey(true);
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < duration) {
            samplePaddlesForMemory(isDot);
            Thread.sleep(2);
        }

        // --- PAUZĂ DINTRE ELEMENTE ---
        setHardwareKey(false);
        start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < dotDuration) {
            samplePaddlesForMemory(isDot);
            Thread.sleep(2);
        }
    }

    /**
     * În timp ce un element este ocupat, verificăm dacă cealaltă padelă
     * este atinsă pentru a o pune în memorie (necesar pt litere ca 'C', 'R', 'L').
     */
    private void samplePaddlesForMemory(boolean currentlySendingDot) {
        boolean dotPressed = isReversed ? paddleRightPressed : paddleLeftPressed;
        boolean dashPressed = isReversed ? paddleLeftPressed : paddleRightPressed;

        if (currentlySendingDot && dashPressed) {
            memoryElement = 2; // Memorie Dash
        } else if (!currentlySendingDot && dotPressed) {
            memoryElement = 1; // Memorie Dot
        }
    }

    private void setHardwareKey(boolean active) {
        if (radioManager == null) return;
        UsbSerialPort port = radioManager.getDataPort();
        if (port == null || !port.isOpen()) return;

        try {
            if (useRts) {
                port.setRTS(active);
            } else {
                port.setDTR(active);
            }
        } catch (IOException e) {
            Log.e(TAG, "Err setting hardware key: " + (useRts ? "RTS" : "DTR"), e);
        }
    }

    public void shutdown() {
        isRunning = false;
        if (keyerThread != null) {
            keyerThread.interrupt();
        }
        setHardwareKey(false);
    }
}