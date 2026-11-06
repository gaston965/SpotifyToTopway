package com.gaston.spotifybridge

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
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
        private const val SPOTIFY_PACKAGE = "com.spotify.music"
        val capturedLogs = mutableListOf<String>()

        fun sendTopwayBroadcasts(
            context: Context,
            track: String,
            artist: String,
            album: String,
            isPlaying: Boolean,
            albumArtBytes: ByteArray? = null,
            albumArtBitmap: Bitmap? = null
        ) {
            val targetPackages = listOf(
                "com.tw.driving",
                "com.android.launcher",
                "com.tw.coreservice",
                "com.tw.core",
                "com.tw.music",
                "com.tw.spotify"
            )

            // 1. Avisar al sistema que Spotify está activo (extraído de TWSpotify.apk)
            val activeIntent = Intent("com.spotify.music.active").apply {
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or 0x01000000)
                putExtra("active", isPlaying)
                putExtra("playing", isPlaying)
                putExtra("package", SPOTIFY_PACKAGE)
            }
            context.sendBroadcast(activeIntent)
            for (pkg in targetPackages) {
                context.sendBroadcast(Intent(activeIntent).apply { setPackage(pkg) })
            }

            // 2. Broadcast exacto de TWSpotify: com.tw.launcher.musicinfo
            // Enviamos variante con playState Int (1/0) y variante Boolean por compatibilidad
            val musicInfoIntent = Intent("com.tw.launcher.musicinfo").apply {
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or 0x01000000)
                putExtra("msg_Song", track)
                putExtra("msg_Artist", artist)
                putExtra("track", track)
                putExtra("artist", artist)
                putExtra("album", album)
                putExtra("playState", if (isPlaying) 1 else 0)
                putExtra("playing", isPlaying)
                putExtra("isPaused", !isPlaying)
                putExtra("musicType", 1)
                putExtra("position", 1000L)
                putExtra("duration", 200000L)
                putExtra("package", SPOTIFY_PACKAGE)
                if (albumArtBytes != null) {
                    putExtra("msg_Albumart", albumArtBytes)
                }
            }

            context.sendBroadcast(musicInfoIntent)
            for (pkg in targetPackages) {
                context.sendBroadcast(Intent(musicInfoIntent).apply { setPackage(pkg) })
            }

            // Si el receptor espera Bitmap directo en vez de ByteArray en msg_Albumart
            if (albumArtBitmap != null) {
                try {
                    val bmpIntent = Intent(musicInfoIntent).apply {
                        removeExtra("msg_Albumart")
                        putExtra("msg_Albumart", albumArtBitmap)
                    }
                    context.sendBroadcast(bmpIntent)
                    context.sendBroadcast(Intent(bmpIntent).apply { setPackage("com.tw.driving") })
                    context.sendBroadcast(Intent(bmpIntent).apply { setPackage("com.android.launcher") })
                } catch (e: Exception) {
                    Log.e(TAG, "Bitmap demasiado grande para Binder", e)
                }
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    private val topwayControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val act = intent?.action ?: return
            val extrasStr = intent.extras?.keySet()?.joinToString { k -> "$k=${intent.extras?.get(k)}" } ?: ""
            val entry = "$act [$extrasStr]"
            if (capturedLogs.size > 20) capturedLogs.removeAt(0)
            capturedLogs.add(entry)

            if (act == "com.tw.music.spotify.control") {
                val controlId = intent.getIntExtra("controlID", -1)
                val cmdStr = intent.getStringExtra("controlID") ?: intent.getStringExtra("command") ?: ""

                // Mapeo de controlID de Topway + registro en pantalla para ver el número exacto
                when {
                    controlId == 1 || cmdStr.contains("play", true) || cmdStr.contains("resume", true) -> {
                        simulateMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    }
                    controlId == 2 || cmdStr.contains("pause", true) -> {
                        simulateMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    }
                    controlId == 3 || cmdStr.contains("prev", true) -> {
                        handler.postDelayed({ simulateMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }, 100)
                    }
                    controlId == 4 || controlId == 0 || cmdStr.contains("next", true) -> {
                        handler.postDelayed({ simulateMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }, 100)
                    }
                    else -> {
                        simulateMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    }
                }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            activeNotifications?.forEach { sbn ->
                if (sbn.packageName == SPOTIFY_PACKAGE) {
                    processSpotifyNotification(sbn)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction("com.tw.music.spotify.control")
            addAction("com.spotify.music.active")
            addAction("com.tw.launcher.musicinfo")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(topwayControlReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(topwayControlReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(topwayControlReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun simulateMediaKey(keyCode: Int) {
        try {
            val downIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                setPackage(SPOTIFY_PACKAGE)
            }
            sendOrderedBroadcast(downIntent, null)

            val upIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, keyCode))
                setPackage(SPOTIFY_PACKAGE)
            }
            sendOrderedBroadcast(upIntent, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error simulando tecla multimedia", e)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn?.packageName == SPOTIFY_PACKAGE) {
            processSpotifyNotification(sbn)
        }
    }

    private fun processSpotifyNotification(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "Spotify"
        val artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""

        // Extraer carátula de la notificación para msg_Albumart
        var artBytes: ByteArray? = null
        var scaledBmp: Bitmap? = null
        try {
            val largeIcon = sbn.notification.getLargeIcon()
            val drawable = largeIcon?.loadDrawable(this)
            val bmp = (drawable as? BitmapDrawable)?.bitmap
                ?: extras.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON)
            if (bmp != null) {
                scaledBmp = Bitmap.createScaledBitmap(bmp, 150, 150, true)
                val baos = ByteArrayOutputStream()
                scaledBmp.compress(Bitmap.CompressFormat.JPEG, 75, baos)
                artBytes = baos.toByteArray()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        sendTopwayBroadcasts(this, title, artist, subText, true, artBytes, scaledBmp)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn?.packageName == SPOTIFY_PACKAGE) {
            sendTopwayBroadcasts(this, "", "", "", false, null, null)
        }
    }
}
