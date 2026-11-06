package com.gaston.spotifybridge

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

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
        refreshLogs()
    }

    private fun refreshLogs() {
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        val isGranted = enabledListeners.contains(packageName)

        val sb = StringBuilder()
        sb.append("=== ESTADO DEL PUENTE ===\n")
        sb.append("Permiso Notificaciones: ${if (isGranted) "ACTIVO (OK)" else "INACTIVO (Toca el boton 1)"}\n\n")
        sb.append("=== COMANDOS RECIBIDOS DEL WIDGET ===\n")
        if (SpotifyBridgeService.capturedLogs.isEmpty()) {
            sb.append("(Ninguno aún. Toca Play/Next en el widget Driving y vuelve aquí)\n")
        } else {
            SpotifyBridgeService.capturedLogs.forEach {
                sb.append("-> $it\n")
            }
        }
        logText.text = sb.toString()
    }
}
