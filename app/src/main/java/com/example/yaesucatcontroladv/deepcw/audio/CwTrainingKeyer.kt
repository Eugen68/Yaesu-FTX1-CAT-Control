package com.example.yaesucatcontroladv.deepcw.audio

import com.example.yaesucatcontroladv.cwtraining.MorseKeyerListener
import com.example.yaesucatcontroladv.cwtraining.PaddleKeyerEngine

/**
 * Keyer de antrenament CW pentru padele - complet separat de CwKeyer (care manipuleaza statia
 * si ramane neatins). Nu atinge RTS/DTR, doar genereaza sunet.
 *
 * Adaptor peste motorul portat din proiectul morse-keyer-sound (pachetul cwtraining):
 * PaddleKeyerEngine + SoundGenerator (libsound.so nativ low-latency, cu fallback pe
 * AudioTrack in Java). Pastreaza API-ul folosit de EcranComenzi.
 *
 * La fiecare start() se creeaza un motor nou: MorseTimingEngine.stop() isi inchide executorul
 * si SoundGenerator.stop() isi elibereaza track-urile, deci o instanta oprita nu mai poate fi
 * repornita.
 */
class CwTrainingKeyer {

    @Volatile var wpm: Int = 20
        set(value) {
            field = value.coerceIn(5, 50)
            engine?.setWpm(field)
        }
    @Volatile var reversed: Boolean = false
    @Volatile var iambicModeB: Boolean = true
        set(value) {
            field = value
            engine?.setIambicModeB(value)
        }
    @Volatile var toneHz: Int = 600
        set(value) {
            field = value.coerceIn(400, 1000)
            engine?.setSoundFrequency(field)
        }

    /** Primeste caracterele decodate din palete (pe firul keyer-ului); setat inainte de start(). */
    @Volatile var listener: MorseKeyerListener? = null

    @Volatile var isRunning: Boolean = false
        private set

    @Volatile private var engine: PaddleKeyerEngine? = null

    fun setLeftPaddle(pressed: Boolean) = paddle(dit = !reversed, pressed = pressed)

    fun setRightPaddle(pressed: Boolean) = paddle(dit = reversed, pressed = pressed)

    private fun paddle(dit: Boolean, pressed: Boolean) {
        val e = engine ?: return
        when {
            dit && pressed -> e.pressDit()
            dit -> e.releaseDit()
            pressed -> e.pressDah()
            else -> e.releaseDah()
        }
    }

    fun start() {
        if (isRunning) return
        engine = PaddleKeyerEngine(wpm, toneHz, listener).also {
            it.setIambicModeB(iambicModeB)
            it.startKeyer()
        }
        isRunning = true
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        engine?.let {
            it.releaseDit()
            it.releaseDah()
            it.stopKeyer()
        }
        engine = null
    }
}
