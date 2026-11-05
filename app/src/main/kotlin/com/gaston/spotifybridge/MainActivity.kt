package com.gaston.spotifybridge

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.BLACK)
        }

        val btnPerm = Button(this).apply {
            text = "1. Abrir Permiso de Notificaciones"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        layout.addView(btnPerm)

        val logText = TextView(this).apply {
            setTextColor(Color.GREEN)
            textSize = 13f
        }

        val sb = StringBuilder()
        val twPackages = mutableSetOf(
            "com.tw.driving",
            "com.android.launcher",
            "com.tw.music"
        )

        // 1. Listar TODOS los paquetes com.tw.* del sistema
        sb.append("=== TODOS LOS PAQUETES COM.TW.* ===\n")
        val pkgs = packageManager.getInstalledPackages(
            PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS
        )
        for (p in pkgs) {
            val name = p.packageName
            if (name.startsWith("com.tw") || name == "com.android.launcher") {
                twPackages.add(name)
                sb.append("* $name\n")
                p.services?.forEach { s -> sb.append("   [S] ${s.name}\n") }
                p.receivers?.forEach { r -> sb.append("   [R] ${r.name}\n") }
                p.activities?.forEach { a -> sb.append("   [A] ${a.name}\n") }
            }
        }

        // 2. Mostrar los últimos comandos capturados por el servicio en vivo
        sb.append("\n=== ULTIMOS COMANDOS CAPTURADOS ===\n")
        if (SpotifyBridgeService.capturedLogs.isEmpty()) {
            sb.append("(Ninguno aún - toca botones del widget y vuelve a abrir esta app)\n")
        } else {
            SpotifyBridgeService.capturedLogs.forEach { log ->
                sb.append("-> $log\n")
            }
        }

        logText.text = sb.toString()

        val btnTest = Button(this).apply {
            text = "2. Inyectar Broadcast directo a com.tw.driving"
            setOnClickListener {
                SpotifyBridgeService.sendTopwayBroadcasts(
                    this@MainActivity,
                    "Spotify Conectado",
                    "Topway TS10",
                    "Spotify",
                    true,
                    twPackages
                )
                Toast.makeText(
                    this@MainActivity,
                    "Enviado a ${twPackages.size} paquetes com.tw.*",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        layout.addView(btnTest)

        val scroll = ScrollView(this).apply {
            addView(logText)
        }
        layout.addView(scroll)

        setContentView(layout)
    }
}
