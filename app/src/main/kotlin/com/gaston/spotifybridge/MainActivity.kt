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
        val targetPackages = mutableSetOf<String>()

        // 1. Detectar el paquete real detrás de "Launcher" (Versión 10)
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveList = packageManager.queryIntentActivities(homeIntent, 0)
        sb.append("=== APPS DE INICIO (LAUNCHERS) ===\n")
        for (res in resolveList) {
            val pkg = res.activityInfo.packageName
            val name = res.activityInfo.name
            sb.append("* $pkg\n  ($name)\n")
            targetPackages.add(pkg)
        }

        // 2. Buscar qué paquetes tienen BroadcastReceivers de música en su Manifest
        sb.append("\n=== RECEPTORES DE MUSICA DETECTADOS ===\n")
        val testActions = listOf(
            "com.spotify.music.metadatachanged",
            "com.spotify.music.playbackstatechanged",
            "com.android.music.metachanged",
            "com.android.music.playstatechanged",
            "com.ts.music.metachanged",
            "com.ts.main.music.metachanged"
        )
        for (act in testActions) {
            val receivers = packageManager.queryBroadcastReceivers(Intent(act), 0)
            if (receivers.isNotEmpty()) {
                sb.append("[$act]:\n")
                for (r in receivers) {
                    sb.append(" -> ${r.activityInfo.packageName} (${r.activityInfo.name})\n")
                    targetPackages.add(r.activityInfo.packageName)
                }
            } else {
                sb.append("[$act]: 0 estáticos\n")
            }
        }

        // 3. Listar todos los paquetes internos de Topway (com.ts.*, launcher, mainui)
        sb.append("\n=== PAQUETES DEL SISTEMA TOPWAY ===\n")
        val pkgs = packageManager.getInstalledPackages(0)
        for (p in pkgs) {
            val name = p.packageName
            if (name.contains("ts.", ignoreCase = true) ||
                name.contains("topway", ignoreCase = true) ||
                name.contains("launcher", ignoreCase = true) ||
                name.contains("music", ignoreCase = true) ||
                name.contains("mainui", ignoreCase = true) ||
                name.contains("widget", ignoreCase = true) ||
                name.contains("driving", ignoreCase = true)
            ) {
                sb.append("- $name\n")
                targetPackages.add(name)
            }
        }

        logText.text = sb.toString()

        val btnTest = Button(this).apply {
            text = "2. Forzar Broadcast a los ${targetPackages.size} paquetes"
            setOnClickListener {
                for (act in testActions) {
                    val base = Intent(act).apply {
                        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or 0x01000000)
                        putExtra("id", 1L)
                        putExtra("artist", "Artista Spotify")
                        putExtra("album", "Album Spotify")
                        putExtra("track", "Prueba Spotify Activo")
                        putExtra("playing", true)
                        putExtra("playstate", true)
                        putExtra("isPlaying", true)
                        putExtra("package", "com.spotify.music")
                    }
                    sendBroadcast(base)
                    for (pkg in targetPackages) {
                        val exp = Intent(base).apply { setPackage(pkg) }
                        sendBroadcast(exp)
                    }
                }
                Toast.makeText(this@MainActivity, "Broadcast enviado a ${targetPackages.size} paquetes", Toast.LENGTH_SHORT).show()
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
