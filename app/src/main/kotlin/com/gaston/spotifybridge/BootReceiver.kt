package com.gaston.spotifybridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Se dispara cuando el sistema avisa que el equipo encendió (o que la app se
// instaló/actualizó). Anota la hora y el tipo de aviso para el diagnóstico y
// le pide al sistema que vuelva a conectar el servicio de la app.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        try {
            context.getSharedPreferences(SpotifyBridgeService.PREFS_DIAG, Context.MODE_PRIVATE)
                .edit()
                .putLong("boot_receiver", System.currentTimeMillis())
                .putString("boot_action", intent?.action ?: "")
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        KeepAliveService.start(context)
        SpotifyBridgeService.forceRebind(context)
    }
}
