package com.example.yaesucatcontroladv.deepcw.audio

/**
 * Stare partajata: e legatura Bluetooth SCO chiar conectata acum (nu doar "am cerut-o")?
 * `startBluetoothSco()` e asincron - actualizata de EcranComenzi din broadcast-ul
 * ACTION_SCO_AUDIO_STATE_UPDATED, citita de MicUplinkPlayer la fiecare pornire de PTT ca sa
 * decida daca tinteste microfonul cascii sau cade pe cel intern.
 */
object BluetoothScoState {
    @Volatile
    var connected: Boolean = false
}
