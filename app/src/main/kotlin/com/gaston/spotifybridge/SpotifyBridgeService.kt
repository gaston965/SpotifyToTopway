package com.gaston.spotifybridge

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.KeyEvent
import java.io.ByteArrayOutputStream

class SpotifyBridgeService : NotificationListenerService() {

    companion object {
        private const val TAG = "SpotifyBridge"
        const val PREFS_DIAG = "bridge_diag"

        // Apps cuya música se manda a la home. Para agregar otra, sumá su paquete acá.
        val allowedPackages = setOf(
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "com.google.android.youtube",
            "com.aimp.player"
        )

        val capturedLogs = mutableListOf<String>()
        val detectedSessions = mutableListOf<String>()
        var lastPush: String = "(ninguno)"
        var listenerConnected: Boolean = false

        // Pide al sistema que vuelva a conectar el servicio (equivale a apagar y
        // prender el permiso, pero sin tocar los ajustes). Sirve desde la app o
        // desde el propio servicio.
        fun rebind(context: Context) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    NotificationListenerService.requestRebind(
                        ComponentName(context, SpotifyBridgeService::class.java)
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo pedir la reconexión", e)
            }
        }

        // Reconexión "a la fuerza": apaga y vuelve a prender el componente del
        // servicio (lo mismo que dispara el sistema cuando cambia el paquete) y
        // después pide la reconexión. Es el equivalente de apagar y prender el
        // permiso de notificaciones a mano.
        fun forceRebind(context: Context) {
            try {
                val pm = context.packageManager
                val cn = ComponentName(context, SpotifyBridgeService::class.java)
                pm.setComponentEnabledSetting(
                    cn,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
                pm.setComponentEnabledSetting(
                    cn,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo reiniciar el componente", e)
            }
            rebind(context)
        }

        // Carátula mínima (cuadrado gris) para cuando no hay imagen:
        // TWSpotify siempre manda un byte[] en msg_Albumart.
        private fun placeholderArt(): ByteArray {
            val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.DKGRAY)
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 100, bos)
            return bos.toByteArray()
        }

        // Manda a la home lo mismo que mandaba TWSpotify, con los mismos nombres y tipos:
        //   musicType -> String, playState -> Boolean, msg_Artist -> String,
        //   msg_Song -> String, msg_Albumart -> byte[]
        // Se usa musicType = "spotify" para TODAS las apps: es el único valor que
        // sabemos que la home acepta (no conocemos otros valores).
        fun sendTopwayBroadcasts(
            context: Context,
            track: String,
            artist: String,
            album: String,
            isPlaying: Boolean,
            albumArtBytes: ByteArray? = null,
            albumArtBitmap: Bitmap? = null
        ) {
            val intent = Intent("com.tw.launcher.musicinfo")
            intent.putExtra("musicType", "spotify")
            intent.putExtra("playState", isPlaying)
            intent.putExtra("msg_Artist", artist)
            intent.putExtra("msg_Song", track)
            intent.putExtra("msg_Albumart", albumArtBytes ?: placeholderArt())
            context.sendBroadcast(intent)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var sessionManager: MediaSessionManager? = null
    private var lastActivePackage: String? = null
    private val tracked = HashMap<String, Pair<MediaController, MediaController.Callback>>()
    private val artCache = HashMap<String, ByteArray>()

    private val pushRunnable = Runnable { pushState() }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        refreshSessions(list)
        schedulePush()
    }

    private val topwayControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val act = intent?.action ?: return
            val extrasStr = intent.extras?.keySet()?.joinToString { k -> "$k=${intent.extras?.get(k)}" } ?: ""
            val entry = "$act [$extrasStr]"
            if (capturedLogs.size > 20) capturedLogs.removeAt(0)
            capturedLogs.add(entry)

            if (act == "com.tw.music.spotify.control") {
                // Numeración deducida del código de TWSpotify:
                // 1 = play, 2 = anterior, 3 = siguiente, otro = pausa.
                when (intent.getIntExtra("controlID", -1)) {
                    1 -> sendControl(KeyEvent.KEYCODE_MEDIA_PLAY)
                    2 -> sendControl(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                    3 -> sendControl(KeyEvent.KEYCODE_MEDIA_NEXT)
                    else -> sendControl(KeyEvent.KEYCODE_MEDIA_PAUSE)
                }
            }
        }
    }

    // Guarda la hora de cada evento en el almacenamiento de la app, para que
    // sobreviva a los apagados y se pueda ver después qué pasó.
    private fun mark(key: String) {
        try {
            getSharedPreferences(PREFS_DIAG, Context.MODE_PRIVATE)
                .edit()
                .putLong(key, System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onCreate() {
        super.onCreate()
        mark("create")
        val filter = IntentFilter().apply {
            addAction("com.tw.music.spotify.control")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(topwayControlReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(topwayControlReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mark("destroy")
        listenerConnected = false
        try {
            unregisterReceiver(topwayControlReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        listenerConnected = true
        mark("connected")
        KeepAliveService.start(this)
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            sessionManager = msm
            val comp = ComponentName(this, SpotifyBridgeService::class.java)
            msm.addOnActiveSessionsChangedListener(sessionsListener, comp)
            refreshSessions(msm.getActiveSessions(comp))
            activeNotifications?.forEach { sbn ->
                if (sbn.packageName in allowedPackages) cacheArt(sbn)
            }
            schedulePush()
        } catch (e: Exception) {
            Log.e(TAG, "Error al conectar el listener", e)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        listenerConnected = false
        mark("disconnected")
        try {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // Si el sistema nos suelta, pedimos que nos vuelva a conectar.
        rebind(this)
    }

    private fun stateName(s: Int?): String = when (s) {
        PlaybackState.STATE_PLAYING -> "PLAYING"
        PlaybackState.STATE_PAUSED -> "PAUSED"
        PlaybackState.STATE_STOPPED -> "STOPPED"
        PlaybackState.STATE_BUFFERING -> "BUFFERING"
        null -> "sin estado"
        else -> "estado $s"
    }

    // Registra callbacks solo en las sesiones de las apps permitidas
    // y deja la lista completa de sesiones para mostrarla en pantalla.
    private fun refreshSessions(list: List<MediaController>?) {
        for ((_, pair) in tracked) {
            try {
                pair.first.unregisterCallback(pair.second)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        tracked.clear()

        val info = mutableListOf<String>()
        list?.forEach { c ->
            val pkg = c.packageName
            val permitida = pkg in allowedPackages
            info.add("$pkg  [${stateName(c.playbackState?.state)}]  ${if (permitida) "(permitida)" else "(ignorada)"}")
            if (permitida) {
                val cb = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        if (state?.state == PlaybackState.STATE_PLAYING) lastActivePackage = pkg
                        schedulePush()
                    }

                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        schedulePush()
                    }
                }
                c.registerCallback(cb, handler)
                tracked[pkg] = Pair(c, cb)
            }
        }
        detectedSessions.clear()
        detectedSessions.addAll(info)
    }

    // Elige qué app mostrar: la que está sonando; si ninguna, la última que sonó.
    private fun pickController(): MediaController? {
        var fallback: MediaController? = null
        for ((pkg, pair) in tracked) {
            val c = pair.first
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) return c
            if (pkg == lastActivePackage) fallback = c
        }
        return fallback ?: tracked.values.firstOrNull()?.first
    }

    // Junta varios eventos seguidos en un solo envío.
    private fun schedulePush() {
        handler.removeCallbacks(pushRunnable)
        handler.postDelayed(pushRunnable, 300)
    }

    private fun toJpeg(bmp: Bitmap): ByteArray? {
        return try {
            val scaled = Bitmap.createScaledBitmap(bmp, 150, 150, true)
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 75, baos)
            baos.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    private fun pushState() {
        try {
            val c = pickController()
            if (c == null) {
                sendTopwayBroadcasts(this, "", "", "", false, null, null)
                lastPush = "(sin reproductor activo)"
                return
            }
            val md = c.metadata
            val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
            val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: ""
            val album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
            val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING

            val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            val art: ByteArray? = (if (bmp != null) toJpeg(bmp) else null) ?: artCache[c.packageName]

            sendTopwayBroadcasts(this, title, artist, album, playing, art, null)
            lastPush = "${c.packageName} | $title | $artist | ${if (playing) "sonando" else "pausa"}"
            mark("push")
        } catch (e: Exception) {
            Log.e(TAG, "Error al enviar estado a la home", e)
        }
    }

    // Control preferido: la sesión de medios de la app elegida. Si falla, tecla multimedia.
    private fun sendControl(keyCode: Int) {
        val c = pickController()
        if (c != null) {
            try {
                val t = c.transportControls
                when (keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY -> t.play()
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> t.pause()
                    KeyEvent.KEYCODE_MEDIA_NEXT -> t.skipToNext()
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> t.skipToPrevious()
                }
                return
            } catch (e: Exception) {
                Log.e(TAG, "Fallo transportControls, uso tecla multimedia", e)
            }
        }
        simulateMediaKey(keyCode, c?.packageName ?: "com.spotify.music")
    }

    private fun simulateMediaKey(keyCode: Int, pkg: String) {
        try {
            val downIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                setPackage(pkg)
            }
            sendOrderedBroadcast(downIntent, null)

            val upIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, keyCode))
                setPackage(pkg)
            }
            sendOrderedBroadcast(upIntent, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error simulando tecla multimedia", e)
        }
    }

    // Carátula de respaldo: la imagen grande de la notificación de la app.
    private fun cacheArt(sbn: StatusBarNotification) {
        try {
            val extras = sbn.notification.extras
            val largeIcon = sbn.notification.getLargeIcon()
            val drawable = largeIcon?.loadDrawable(this)
            val bmp = (drawable as? BitmapDrawable)?.bitmap
                ?: extras.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON)
            if (bmp != null) {
                val bytes = toJpeg(bmp)
                if (bytes != null) artCache[sbn.packageName] = bytes
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn != null && sbn.packageName in allowedPackages) {
            cacheArt(sbn)
            schedulePush()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn != null && sbn.packageName in allowedPackages) {
            artCache.remove(sbn.packageName)
            schedulePush()
        }
    }
}
