package com.gaston.spotifybridge

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.KeyEvent

class SpotifyBridgeService : NotificationListenerService() {

    companion object {
        private const val TAG = "SpotifyBridge"
        private const val SPOTIFY_PACKAGE = "com.spotify.music"
        
        // Paquetes del sistema Topway que escuchan las fuentes de música
        private val TOPWAY_PACKAGES = listOf(
            "com.ts.MainUI",
            "com.android.launcher3",
            "com.ts.launcher",
            "com.android.systemui"
        )
    }

    private val handler = Handler(Looper.getMainLooper())

    private val topwayControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.ts.music.next", "action.media.next", "com.android.music.musicservicecommand.next" -> {
                    handler.postDelayed({ simulateMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }, 150)
                }
                "com.ts.music.prev", "action.media.prev", "com.android.music.musicservicecommand.previous" -> {
                    handler.postDelayed({ simulateMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }, 150)
                }
                "com.ts.music.play", "action.media.play", "com.android.music.musicservicecommand.togglepause" -> {
                    handler.postDelayed({ simulateMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }, 150)
                }
                "com.ts.music.pause", "action.media.pause" -> {
                    simulateMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Listener conectado. Revisando notificaciones activas...")
        // Apenas le das permiso o arranca el estéreo, revisa si Spotify ya está abierto
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
            addAction("com.ts.music.next")
            addAction("com.ts.music.prev")
            addAction("com.ts.music.play")
            addAction("com.ts.music.pause")
            addAction("action.media.next")
            addAction("action.media.prev")
            addAction("action.media.play")
            addAction("action.media.pause")
            addAction("com.android.music.musicservicecommand")
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
        val title = extras.getCharSequence("android.title")?.toString() ?: "Spotify"
        val artist = extras.getCharSequence("android.text")?.toString() ?: ""
        val subText = extras.getCharSequence("android.subText")?.toString() ?: ""

        emitAllBroadcasts(title, artist, subText, true)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn?.packageName == SPOTIFY_PACKAGE) {
            emitAllBroadcasts("", "", "", false)
        }
    }

    private fun emitAllBroadcasts(track: String, artist: String, album: String, isPlaying: Boolean) {
        val actions = listOf(
            "com.spotify.music.metadatachanged",
            "com.spotify.music.playbackstatechanged",
            "com.android.music.metachanged",
            "com.android.music.playstatechanged",
            "com.ts.music.metachanged"
        )

        for (action in actions) {
            val baseIntent = Intent(action).apply {
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                // En Android 8+ esta bandera permite que receptores en Manifest reciban el broadcast en background
                addFlags(0x01000000) // Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND
                putExtra("id", 1L)
                putExtra("artist", artist)
                putExtra("album", album)
                putExtra("track", track)
                putExtra("playing", isPlaying)
                putExtra("playstate", isPlaying)
                putExtra("isPlaying", isPlaying)
                putExtra("package", SPOTIFY_PACKAGE)
                putExtra("player", "Spotify")
                putExtra("position", 0L)
                putExtra("duration", 180000L)
            }

            // 1. Envío global implícito con bandera de background
            sendBroadcast(baseIntent)

            // 2. Envío explícito dirigido directamente a los paquetes del launcher Topway
            for (pkg in TOPWAY_PACKAGES) {
                val explicitIntent = Intent(baseIntent).apply {
                    setPackage(pkg)
                }
                sendBroadcast(explicitIntent)
            }
        }
    }
}
