package com.gaston.spotifybridge

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
                SpotifyBridgeService.rebind(this@MainActivity)
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
        if (hasPermission() && !SpotifyBridgeService.listenerConnected) {
            SpotifyBridgeService.rebind(this)
            logText.postDelayed({ refreshLogs() }, 1500)
        }
        refreshLogs()
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
        sb.append("=== ESTADO DEL PUENTE (version v3.2) ===\n")
        sb.append("Permiso Notificaciones: ${if (isGranted) "ACTIVO (OK)" else "INACTIVO (Toca el boton 1)"}\n")
        sb.append("Servicio conectado ahora: ${if (SpotifyBridgeService.listenerConnected) "SI" else "NO"}\n\n")

        sb.append("=== DIAGNOSTICO (para entender los cortes) ===\n")
        sb.append("Equipo encendido desde: ${fmt(bootTime)}\n")
        sb.append("Servicio creado:        ${fmt(prefs.getLong("create", 0L))}\n")
        sb.append("Servicio conectado:     ${fmt(prefs.getLong("connected", 0L))}\n")
        sb.append("Servicio desconectado:  ${fmt(prefs.getLong("disconnected", 0L))}\n")
        sb.append("Servicio cerrado:       ${fmt(prefs.getLong("destroy", 0L))}\n")
        sb.append("Aviso de encendido:     ${fmt(prefs.getLong("boot_receiver", 0L))}  ${prefs.getString("boot_action", "")}\n")
        sb.append("Ultimo envio a la home: ${fmt(prefs.getLong("push", 0L))}\n\n")

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
