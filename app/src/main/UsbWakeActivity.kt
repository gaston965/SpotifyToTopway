package com.gaston.spotifybridge

import android.app.Activity
import android.content.Context
import android.os.Bundle

// Pantalla INVISIBLE. El sistema la abre cuando se conecta (o se vuelve a
// conectar) un dispositivo USB. Arranca los servicios de la app y se cierra
// enseguida. Las pantallas (activities) se pueden abrir aunque la app haya
// sido cerrada a la fuerza, a diferencia de los avisos (broadcasts) y las alarmas.
class UsbWakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            getSharedPreferences(SpotifyBridgeService.PREFS_DIAG, Context.MODE_PRIVATE)
                .edit()
                .putLong("usb_wake", System.currentTimeMillis())
                .apply()
            KeepAliveService.start(this)
            AlarmReceiver.schedule(this)
            SpotifyBridgeService.forceRebind(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        finish()
    }
}
