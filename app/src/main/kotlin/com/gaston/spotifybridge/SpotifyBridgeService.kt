package com.gaston.spotifybridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
        val capturedLogs = mutableListOf<String>()

        fun sendTopwayBroadcasts(
            context: Context,
            track: String,
            artist: String,
            album: String,
            isPlaying: Boolean,
            targets: Set<String>
        ) {
            val actions = listOf(
                "com.spotify.music.metadatachanged",
                "com.spotify.music.playbackstatechanged",
                "com.android.music.metachanged",
                "com.android.music.playstatechanged",
                "com.android.music.playbackcomplete",
                "com.tw.music.metachanged",
                "com.tw.music.playstatechanged",
                "com.tw.driving.metachanged",
                "com.tw.action.MEDIA_INFO"
            )

            for (action in actions) {
                val baseIntent = Intent(action).apply {
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or 0x01000000)
                    putExtra("id", 1L)
                    putExtra("artist", artist)
                    putExtra("album", album)
                    putExtra("track", track)
                    putExtra("title", track)
                    putExtra("playing", isPlaying)
                    putExtra("playstate", isPlaying)
                    putExtra("isPlaying", isPlaying)
                    putExtra("package", SPOTIFY_PACKAGE)
                    putExtra("player", "Spotify")
                    putExtra("position", 1000L)
                    putExtra("duration", 200000L)
                }

                // 1. Envío global sin paquete (para receptores dinámicos en RAM de com.tw.driving)
                context.sendBroadcast(baseIntent)

                // 2. Envío explícito a cada paquete com.tw.* y com.android.launcher
                for (pkg in targets) {
                    val explicitIntent = Intent(baseIntent).apply {
                        setPackage(pkg)
                    }
                    context.sendBroadcast(explicitIntent)
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
            if (capturedLogs.size > 15) capturedLogs.removeAt(0)
            capturedLogs.add(entry)

            when (act) {
                "com.tw.music.next", "com.ts.music.next", "action.media.next",
                "com.android.music.musicservicecommand.next", "com.tw.driving.next" -> {
                    handler.postDelayed({ simulateMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }, 150)
                }
                "com.tw.music.prev", "com.ts.music.prev", "action.media.prev",
                "com.android.music.musicservicecommand.previous", "com.tw.driving.prev" -> {
                    handler.postDelayed({ simulateMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }, 150)
                }
                "com.tw.music.play", "com.ts.music.play", "action.media.play",
                "com.android.music.musicservicecommand.togglepause", "com.tw.driving.play" -> {
                    handler.postDelayed({ simulateMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }, 150)
                }
                "com.tw.music.pause", "com.ts.music.pause", "action.media.pause", "com.tw.driving.pause" -> {
                    simulateMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                }
                "com.android.music.musicservicecommand" -> {
                    when (intent.getStringExtra("command")) {
                        "next" -> handler.postDelayed({ simulateMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }, 150)
                        "previous" -> handler.postDelayed({ simulateMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }, 150)
                        "togglepause", "play" -> handler.postDelayed({ simulateMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }, 150)
                        "pause" -> simulateMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
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
            addAction("com.tw.music.next")
            addAction("com.tw.music.prev")
            addAction("com.tw.music.play")
            addAction("com.tw.music.pause")
            addAction("com.tw.driving.next")
            addAction("com.tw.driving.prev")
            addAction("com.tw.driving.play")
            addAction("com.tw.driving.pause")
            addAction("com.ts.music.next")
            addAction("com.ts.music.prev")
            addAction("com.ts.music.play")
            addAction("com.ts.music.pause")
            addAction("action.media.next")
            addAction("action.media.prev")
            addAction("action.media.play")
            addAction("action.media.pause")
            addAction("com.android.music.musicservicecommand")
            addAction("com.android.music.musicservicecommand.next")
            addAction("com.android.music.musicservicecommand.previous")
            addAction("com.android.music.musicservicecommand.togglepause")
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

        sendTopwayBroadcasts(this, title, artist, subText, true, getTwTargetPackages())
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn?.packageName == SPOTIFY_PACKAGE) {
            sendTopwayBroadcasts(this, "", "", "", false, getTwTargetPackages())
        }
    }

    private fun getTwTargetPackages(): Set<String> {
        val targets = mutableSetOf("com.tw.driving", "com.android.launcher", "com.tw.music")
        try {
            packageManager.getInstalledPackages(0).forEach { p ->
                if (p.packageName.startsWith("com.tw") || p.packageName.contains("launcher")) {
                    targets.add(p.packageName)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return targets
    }
}
