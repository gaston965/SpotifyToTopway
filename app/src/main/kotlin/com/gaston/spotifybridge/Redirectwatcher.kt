package com.gaston.spotifybridge

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Process
import android.provider.Settings
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Redirección al reproductor activo.
// Problema: la home de Driving cree que todo es Spotify, así que al tocar el
// widget de música abre Spotify aunque esté sonando AIMP o YouTube Music.
// Solución: si se abre Spotify mientras suena OTRA app (y Spotify no suena),
// se abre la app que está sonando.
// Necesita dos permisos que da el usuario: "acceso a datos de uso" (para saber
// qué app se abrió) y "mostrar sobre otras apps" (para poder abrir otra app
// desde segundo plano en Android 10). Sin ellos, no hace nada.
class RedirectWatcher(private val context: Context, private val handler: Handler) {

    companion object {
        private const val TAG = "RedirectWatcher"
        private const val SPOTIFY = "com.spotify.music"
        private const val TICK_MS = 800L
        private const val MIN_GAP_MS = 3000L
        const val PREF_ENABLED = "redirect_enabled"
        const val PREF_LAST = "redirect_last"

        fun isEnabled(context: Context): Boolean {
            return context.getSharedPreferences(SpotifyBridgeService.PREFS_DIAG, Context.MODE_PRIVATE)
                .getBoolean(PREF_ENABLED, true)
        }

        fun setEnabled(context: Context, value: Boolean) {
            context.getSharedPreferences(SpotifyBridgeService.PREFS_DIAG, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_ENABLED, value)
                .apply()
        }

        fun hasUsageAccess(context: Context): Boolean {
            return try {
                val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
                val mode = appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
                mode == AppOpsManager.MODE_ALLOWED
            } catch (e: Exception) {
                false
            }
        }

        fun hasOverlay(context: Context): Boolean {
            return try {
                Settings.canDrawOverlays(context)
            } catch (e: Exception) {
                false
            }
        }
    }

    private var lastEventTime = System.currentTimeMillis()
    private var lastRedirect = 0L

    private val tick = object : Runnable {
        override fun run() {
            check()
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun start() {
        lastEventTime = System.currentTimeMillis()
        handler.postDelayed(tick, TICK_MS)
    }

    fun stop() {
        handler.removeCallbacks(tick)
    }

    private fun check() {
        try {
            val now = System.currentTimeMillis()
            val playing = SpotifyBridgeService.playingNow
            val others = playing - SPOTIFY
            // Solo actúa si suena exactamente UNA app que no es Spotify, Spotify
            // no está sonando, la función está activada y tiene ambos permisos.
            if (playing.contains(SPOTIFY) ||
                others.size != 1 ||
                !isEnabled(context) ||
                !hasUsageAccess(context) ||
                !hasOverlay(context)
            ) {
                lastEventTime = now
                return
            }

            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val events = usm.queryEvents(lastEventTime, now)
            val e = UsageEvents.Event()
            var spotifyOpened = false
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && e.packageName == SPOTIFY) {
                    spotifyOpened = true
                }
            }
            lastEventTime = now
            if (spotifyOpened) redirect(others.first())
        } catch (e: Exception) {
            Log.e(TAG, "Error en la redirección", e)
        }
    }

    private fun redirect(target: String) {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastRedirect < MIN_GAP_MS) return
        lastRedirect = nowMs
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(target) ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            val stamp = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date(nowMs))
            context.getSharedPreferences(SpotifyBridgeService.PREFS_DIAG, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_LAST, "$stamp  spotify -> $target")
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir $target", e)
        }
    }
}
