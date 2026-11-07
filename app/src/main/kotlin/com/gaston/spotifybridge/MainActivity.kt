package com.gaston.spotifybridge

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var logText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.BLACK)
        }

        val btnPerm = Button(this).apply {
            text = "1. Activar Permiso de Notificaciones"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        layout.addView(btnPerm)

        val btnTest = Button(this).apply {
            text = "2. Probar envío com.tw.launcher.musicinfo"
            setOnClickListener {
                SpotifyBridgeService.sendTopwayBroadcasts(
                    this@MainActivity,
                    "Cancion de Prueba",
                    "Spotify Bridge",
                    "Topway TS10",
                    true,
                    null,
                    null
                )
                Toast.makeText(this@MainActivity, "Enviado com.tw.launcher.musicinfo", Toast.LENGTH_SHORT).show()
                refreshLogs()
            }
        }
        layout.addView(btnTest)

        val btnRebind = Button(this).apply {
            text = "3. Reconectar servicio ahora"
            setOnClickListener {
                SpotifyBridgeService.forceRebind(this@MainActivity)
                Toast.makeText(this@MainActivity, "Pedida la reconexion...", Toast.LENGTH_SHORT).show()
                postDelayed({ refreshLogs() }, 1500)
            }
        }
        layout.addView(btnRebind)

        val btnRefresh = Button(this).apply {
            text = "4. Actualizar pantalla"
            setOnClickListener { refreshLogs() }
        }
        layout.addView(btnRefresh)

        val btnBattery = Button(this).apply {
            text = "5. Excluir de ahorro de bateria"
            setOnClickListener { requestBatteryExemption() }
        }
        layout.addView(btnBattery)

        val btnClear = Button(this).apply {
            text = "6. Borrar registro de avisos"
            setOnClickListener {
                this@MainActivity.getSharedPreferences(SpotifyBridgeService.PREFS_DIAG, Context.MODE_PRIVATE)
                    .edit()
                    .remove(KeepAliveService.WAKE_LOG_KEY)
                    .apply()
                refreshLogs()
            }
        }
        layout.addView(btnClear)

        val btnReceivers = Button(this).apply {
            text = "7. Ver quien escucha avisos de encendido (genera receptores.txt)"
            setOnClickListener { generateReceiversReport() }
        }
        layout.addView(btnReceivers)

        logText = TextView(this).apply {
            setTextColor(Color.GREEN)
            textSize = 14f
        }

        val scroll = ScrollView(this).apply {
            addView(logText)
        }
        layout.addView(scroll)

        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        // Si el permiso está activo pero el servicio no está conectado,
        // al abrir la app se pide la reconexión automáticamente.
        KeepAliveService.start(this)
        AlarmReceiver.schedule(this)
        if (hasPermission() && !SpotifyBridgeService.listenerConnected) {
            SpotifyBridgeService.forceRebind(this)
            logText.postDelayed({ refreshLogs() }, 1500)
        }
        refreshLogs()
    }

    // Genera receptores.txt con: qué apps NO del sistema (y qué apps com.tw del
    // sistema) tienen declarado en su manifiesto un receptor para cada aviso de
    // encendido/apagado conocido, y la lista de apps instaladas por el usuario.
    // Sirve para descubrir con qué señal se despierta el administrador de archivos.
    private fun generateReceiversReport() {
        try {
            val pm = packageManager
            val actions = (listOf(
                "android.intent.action.BOOT_COMPLETED",
                "android.intent.action.LOCKED_BOOT_COMPLETED",
                "android.intent.action.QUICKBOOT_POWERON",
                "com.htc.intent.action.QUICKBOOT_POWERON",
                "android.intent.action.MY_PACKAGE_REPLACED",
                "android.hardware.usb.action.USB_DEVICE_ATTACHED",
                "android.intent.action.MEDIA_MOUNTED"
            ) + KeepAliveService.WAKE_ACTIONS).distinct()

            val sb = StringBuilder()
            sb.append("=== QUIEN ESCUCHA AVISOS DE ENCENDIDO (receptores declarados) ===\n")
            sb.append("Se muestran las apps NO del sistema y las del sistema que empiezan con com.tw\n\n")
            for (act in actions) {
                val receivers: List<ResolveInfo> = try {
                    pm.queryBroadcastReceivers(Intent(act), 0)
                } catch (e: Exception) {
                    emptyList()
                }
                val lines = mutableListOf<String>()
                var otrosSistema = 0
                for (ri in receivers) {
                    val ai = ri.activityInfo ?: continue
                    val esSistema = (ai.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    if (!esSistema || ai.packageName.startsWith("com.tw")) {
                        lines.add("   ${ai.packageName} -> ${ai.name}" + (if (esSistema) "  [sistema]" else ""))
                    } else {
                        otrosSistema++
                    }
                }
                sb.append(act + "\n")
                sb.append("   (otras apps del sistema: $otrosSistema)\n")
                lines.forEach { sb.append(it + "\n") }
                sb.append("\n")
            }

            sb.append("=== APPS INSTALADAS (no del sistema) ===\n")
            for (info in pm.getInstalledApplications(0)) {
                if ((info.flags and ApplicationInfo.FLAG_SYSTEM) == 0) {
                    sb.append("${pm.getApplicationLabel(info)}  |  ${info.packageName}\n")
                }
            }

            val destino = getExternalFilesDir(null)
            File(destino, "receptores.txt").writeText(sb.toString())
            Toast.makeText(this, "Listo: $destino/receptores.txt", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun isIgnoringBattery(): Boolean {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (e: Exception) {
            false
        }
    }

    private fun requestBatteryExemption() {
        if (isIgnoringBattery()) {
            Toast.makeText(this, "Ya esta excluida de la optimizacion de bateria", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            i.data = Uri.parse("package:$packageName")
            startActivity(i)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, "Tu stereo no tiene esa pantalla de ajustes", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun hasPermission(): Boolean {
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return enabledListeners.contains(packageName)
    }

    private fun fmt(ms: Long): String {
        if (ms == 0L) return "nunca"
        return SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date(ms))
    }

    private fun refreshLogs() {
        val isGranted = hasPermission()
        val prefs = getSharedPreferences(SpotifyBridgeService.PREFS_DIAG, MODE_PRIVATE)
        val bootTime = System.currentTimeMillis() - SystemClock.elapsedRealtime()

        val sb = StringBuilder()
        sb.append("=== ESTADO DEL PUENTE (version v3.6) ===\n")
        sb.append("Permiso Notificaciones: ${if (isGranted) "ACTIVO (OK)" else "INACTIVO (Toca el boton 1)"}\n")
        sb.append("Servicio conectado ahora: ${if (SpotifyBridgeService.listenerConnected) "SI" else "NO"}\n")
        sb.append("Excluida de optimizacion de bateria: ${if (isIgnoringBattery()) "SI" else "NO"}\n\n")

        sb.append("=== DIAGNOSTICO (para entender los cortes) ===\n")
        sb.append("Equipo encendido desde: ${fmt(bootTime)}\n")
        sb.append("Servicio creado:        ${fmt(prefs.getLong("create", 0L))}\n")
        sb.append("Servicio conectado:     ${fmt(prefs.getLong("connected", 0L))}\n")
        sb.append("Servicio desconectado:  ${fmt(prefs.getLong("disconnected", 0L))}\n")
        sb.append("Servicio cerrado:       ${fmt(prefs.getLong("destroy", 0L))}\n")
        sb.append("Aviso de encendido:     ${fmt(prefs.getLong("boot_receiver", 0L))}  ${prefs.getString("boot_action", "")}\n")
        sb.append("Proceso actual iniciado: ${fmt(prefs.getLong("process_start", 0L))}\n")
        sb.append("Latido proceso ANTERIOR: ${fmt(prefs.getLong("prev_heartbeat", 0L))}\n")
        sb.append("Latido proceso actual:   ${fmt(prefs.getLong("heartbeat", 0L))}\n")
        sb.append("Reconexion automatica:   ${fmt(prefs.getLong("watchdog_rebind", 0L))}\n")
        sb.append("Alarma (ultimo disparo):  ${fmt(prefs.getLong("alarm", 0L))}\n")
        sb.append("Alarma que revivio:      ${fmt(prefs.getLong("alarm_revive", 0L))}\n")
        sb.append("Ultimo envio a la home: ${fmt(prefs.getLong("push", 0L))}\n\n")

        sb.append("=== AVISOS DE ENCENDIDO/APAGADO RECIBIDOS ===\n")
        val wake = prefs.getString(KeepAliveService.WAKE_LOG_KEY, "") ?: ""
        if (wake.isBlank()) {
            sb.append("(Ninguno todavia. Solo se anotan los que llegan mientras la app esta viva)\n")
        } else {
            sb.append(wake + "\n")
        }
        sb.append("\n")

        sb.append("=== APPS PERMITIDAS ===\n")
        SpotifyBridgeService.allowedPackages.forEach { sb.append("* $it\n") }

        sb.append("\n=== SESIONES DE MEDIOS DETECTADAS ===\n")
        if (SpotifyBridgeService.detectedSessions.isEmpty()) {
            sb.append("(Ninguna. Abre una app de musica, ponele play y toca 'Actualizar pantalla')\n")
        } else {
            SpotifyBridgeService.detectedSessions.forEach { sb.append("-> $it\n") }
        }

        sb.append("\n=== ULTIMO ENVIO A LA HOME ===\n")
        sb.append(SpotifyBridgeService.lastPush + "\n")

        sb.append("\n=== COMANDOS RECIBIDOS DE LA HOME ===\n")
        if (SpotifyBridgeService.capturedLogs.isEmpty()) {
            sb.append("(Ninguno aun. Toca Play/Next en la home Driving y vuelve aqui)\n")
        } else {
            SpotifyBridgeService.capturedLogs.forEach { sb.append("-> $it\n") }
        }
        logText.text = sb.toString()
    }
}
