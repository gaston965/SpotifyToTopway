package com.gaston.spotifybridge

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

// Alarma repetitiva "sin despertar": NO enciende el procesador del stereo.
// Si el stereo se duerme, la alarma queda pendiente y se dispara apenas el
// stereo despierta. Como la alarma la guarda el sistema (no la app), puede
// volver a arrancar la app aunque el proceso haya sido cerrado mientras
// dormía. Si el stereo cierra la app "a la fuerza", Android cancela sus
// alarmas y esto no alcanza: el panel lo va a mostrar.
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmReceiver"
        private const val INTERVAL_MS = 60000L

        private fun pending(context: Context): PendingIntent {
            val i = Intent(context, AlarmReceiver::class.java)
            return PendingIntent.getBroadcast(
                context,
                0,
                i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        // Se puede llamar muchas veces: reemplaza la alarma anterior.
        fun schedule(context: Context) {
            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.setRepeating(
                    AlarmManager.RTC,
                    System.currentTimeMillis() + INTERVAL_MS,
                    INTERVAL_MS,
                    pending(context)
                )
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo programar la alarma", e)
            }
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        val now = System.currentTimeMillis()
        try {
            val ed = context.getSharedPreferences(SpotifyBridgeService.PREFS_DIAG, Context.MODE_PRIVATE).edit()
            ed.putLong("alarm", now)
            // Si el servicio no está conectado (por ejemplo, proceso nuevo tras
            // haber sido cerrado), se lo revive.
            if (!SpotifyBridgeService.listenerConnected) {
                ed.putLong("alarm_revive", now)
                ed.apply()
                KeepAliveService.start(context)
                SpotifyBridgeService.forceRebind(context)
            } else {
                ed.apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en la alarma", e)
        }
    }
}
