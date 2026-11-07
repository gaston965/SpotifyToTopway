package com.gaston.spotifybridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Servicio en primer plano (con una notificación fija) cuyo trabajo es:
//  1. hacerle más difícil al sistema cerrar la app,
//  2. anotar un "latido" cada minuto (para saber cuándo muere el proceso),
//  3. vigilar que el servicio de notificaciones esté conectado y, si no lo
//     está (con el permiso activo), forzar la reconexión.
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAlive"
        private const val CHANNEL_ID = "spotifybridge_keepalive"
        private const val NOTIF_ID = 4711
        private const val FIRST_TICK_MS = 20000L
        private const val TICK_MS = 60000L
        private const val MIN_REBIND_GAP_MS = 120000L
        private const val MAX_LOG_LINES = 30

        // Clave donde se guarda el registro de avisos (sobrevive a los apagados).
        const val WAKE_LOG_KEY = "wake_log"

        // Avisos de encendido / apagado / suspensión que usan distintos fabricantes
        // de stereos, más los estándar de Android. Se escuchan mientras la app
        // está viva y se anota la hora de cada uno que llegue.
        val WAKE_ACTIONS = listOf(
            "com.glsx.boot.ACCON",
            "xy.android.acc.on",
            "com.cayboy.action.ACC_ON",
            "com.cayboy.action.ACC_OFF",
            "com.carboy.action.ACC_ON",
            "com.carboy.action.ACC_OFF",
            "autochips.intent.action.QB_POWERON",
            "android.intent.action.ACTION_MT_COMMAND_SLEEP_OUT",
            "android.intent.action.ACTION_SHUTDOWN",
            "android.intent.action.QUICKBOOT_POWERON",
            "com.mediatek.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.SCREEN_ON",
            "android.intent.action.SCREEN_OFF",
            "android.intent.action.USER_PRESENT",
            "android.intent.action.ACTION_POWER_CONNECTED",
            "android.intent.action.ACTION_POWER_DISCONNECTED"
        )

        fun start(context: Context) {
            try {
                val i = Intent(context, KeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo iniciar KeepAliveService", e)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastRebind = 0L

    private val tick = object : Runnable {
        override fun run() {
            doTick()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            // Guarda el último latido del proceso anterior (si lo hubo):
            // es, con un minuto de margen, la hora en que murió.
            val prefs = getSharedPreferences(SpotifyBridgeService.PREFS_DIAG, Context.MODE_PRIVATE)
            val old = prefs.getLong("heartbeat", 0L)
            if (old != 0L) prefs.edit().putLong("prev_heartbeat", old).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            startForeground(NOTIF_ID, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo pasar a primer plano", e)
        }
        try {
            val filter = IntentFilter()
            for (act in WAKE_ACTIONS) filter.addAction(act)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(wakeReceiver, filter, RECEIVER_EXPORTED)
            } else {
                registerReceiver(wakeReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo registrar el receptor de avisos", e)
        }
        AlarmReceiver.schedule(this)
        writeHeartbeat()
        handler.postDelayed(tick, FIRST_TICK_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        try {
            unregisterReceiver(wakeReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            getSharedPreferences(SpotifyBridgeService.PREFS_DIAG, Context.MODE_PRIVATE)
                .edit()
                .putLong("keepalive_destroy", System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Anota cada aviso recibido (hora + nombre) y revisa la conexión poco después.
    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            logWake(action)
            handler.postDelayed({ doTick() }, 5000)
        }
    }

    private fun logWake(action: String) {
        try {
            val prefs = getSharedPreferences(SpotifyBridgeService.PREFS_DIAG, Context.MODE_PRIVATE)
            val stamp = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date())
            val old = prefs.getString(WAKE_LOG_KEY, "") ?: ""
            val lines = old.split("\n").filter { it.isNotBlank() }.toMutableList()
            lines.add("$stamp  $action")
            while (lines.size > MAX_LOG_LINES) lines.removeAt(0)
            prefs.edit().putString(WAKE_LOG_KEY, lines.joinToString("\n")).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "SpotifyBridge", NotificationManager.IMPORTANCE_LOW)
            )
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("SpotifyBridge activo")
            .setContentText("Enviando la musica a la home")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private fun writeHeartbeat() {
        try {
            val now = System.currentTimeMillis()
            val ed = getSharedPreferences(SpotifyBridgeService.PREFS_DIAG, Context.MODE_PRIVATE).edit()
            ed.putLong("heartbeat", now)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ed.putLong(
                    "process_start",
                    now - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
                )
            }
            ed.apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun hasListenerPermission(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return enabled.contains(packageName)
    }

    private fun doTick() {
        writeHeartbeat()
        val now = System.currentTimeMillis()
        if (!SpotifyBridgeService.listenerConnected &&
            hasListenerPermission() &&
            now - lastRebind > MIN_REBIND_GAP_MS
        ) {
            lastRebind = now
            try {
                getSharedPreferences(SpotifyBridgeService.PREFS_DIAG, Context.MODE_PRIVATE)
                    .edit()
                    .putLong("watchdog_rebind", now)
                    .apply()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            SpotifyBridgeService.forceRebind(this)
        }
    }
}
