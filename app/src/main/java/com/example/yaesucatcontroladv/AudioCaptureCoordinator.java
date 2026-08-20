package com.example.yaesucatcontroladv;

/**
 * Arbitru simplu de exclusivitate reciproca intre LISTEN si CW AI DECODER.
 * Aceeasi sursa audio USB nu poate fi deschisa de doua ori in paralel pe multe device-uri.
 */
public class AudioCaptureCoordinator {
    public interface StopHandler {
        void stopIfRunning();
    }

    private static volatile StopHandler cwDecoderStopHandler;
    private static volatile StopHandler listenStopHandler;

    public static void registerCwDecoder(StopHandler handler) {
        cwDecoderStopHandler = handler;
    }

    public static void registerListen(StopHandler handler) {
        listenStopHandler = handler;
    }

    /** Apelat de EcranCwDeepDecoder chiar inainte de a porni decodorul. */
    public static void beforeCwDecoderStart() {
        StopHandler h = listenStopHandler;
        if (h != null) {
            h.stopIfRunning();
        }
    }

    /** Apelat de EcranComenzi chiar inainte de a porni Listen. */
    public static void beforeListenStart() {
        StopHandler h = cwDecoderStopHandler;
        if (h != null) {
            h.stopIfRunning();
        }
    }
}
