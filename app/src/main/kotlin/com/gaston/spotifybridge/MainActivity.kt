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
                   
                )
                Toast.makeText(
                    this@MainActivity,
                    "Enviado a ${twPackages.size} paquetes com.tw.*",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        layout.addView(btnTest)

          val btnReporte = Button(this).apply {
    text = "3. Generar reporte y copiar APK de com.tw*"
    setOnClickListener {
        val destino = getExternalFilesDir(null)
        val r = StringBuilder()
        r.append("=== SISTEMA ===\n")
        r.append("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})\n")
        r.append("Modelo: ${android.os.Build.MODEL} / ${android.os.Build.DEVICE}\n")
        r.append("Build: ${android.os.Build.DISPLAY}\n\n")
        r.append("=== ACCESO A NOTIFICACIONES (activos) ===\n")
        r.append((Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "(ninguno)") + "\n\n")
        r.append("=== PAQUETES com.tw* ===\n")
        val flags = PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS
        for (p in packageManager.getInstalledPackages(flags)) {
            if (!p.packageName.startsWith("com.tw")) continue
            r.append("* ${p.packageName}  v${p.versionName}  uid=${p.sharedUserId}\n")
            r.append("   apk: ${p.applicationInfo.sourceDir}\n")
            p.services?.forEach { s -> r.append("   [S] ${s.name} exported=${s.exported} perm=${s.permission}\n") }
            p.receivers?.forEach { x -> r.append("   [R] ${x.name} exported=${x.exported} perm=${x.permission}\n") }
            p.providers?.forEach { x -> r.append("   [P] ${x.name} auth=${x.authority} exported=${x.exported}\n") }
            try {
                java.io.File(p.applicationInfo.sourceDir)
                    .copyTo(java.io.File(destino, p.packageName + ".apk"), overwrite = true)
                r.append("   copia: OK\n")
            } catch (e: Exception) {
                r.append("   copia: ERROR ${e.message}\n")
            }
        }
        java.io.File(destino, "reporte.txt").writeText(r.toString())
        Toast.makeText(this@MainActivity, "Listo: $destino/reporte.txt", Toast.LENGTH_LONG).show()
    }
}
layout.addView(btnReporte)



         
        val scroll = ScrollView(this).apply {
            addView(logText)
        }
        layout.addView(scroll)

        setContentView(layout)
    }
}
