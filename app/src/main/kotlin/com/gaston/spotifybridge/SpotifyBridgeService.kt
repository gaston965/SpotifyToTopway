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
            isPlaying: Boolean
        ) {
            val actions = listOf(
                "com.spotify.music.metadatachanged",
                "com.spotify.music.playbackstatechanged",
                "com.android.music.metachanged",
                "com.android.music.playstatechanged",
                "com.tw.music.metadatachanged",
                "com.tw.music.playstatechanged",
                "com.tw.tdservice.music.metadatachanged",
                "com.tw.driving.metadatachanged"
            )

            val targetPackages = listOf(
                "com.tw.driving",
                "com.tw.music",
                "com.tw.tdservice",
                "com.android.launcher"
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
                }

                context.sendBroadcast(baseIntent)

                for (pkg in targetPackages) {
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
                "com.tw.music.next", "com.tw.driving.next", "action.media.next",
                "com.android.music.musicservicecommand.next", "com.tw.tdservice.next" -> {
                    handler.postDelayed({ simulateMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }, 150)
                }
                "com.tw.music.prev", "com.tw.driving.prev", "action.media.prev",
                "com.android.music.musicservicecommand.previous", "com.tw.tdservice.prev" -> {
                    handler.postDelayed({ simulateMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }, 150)
                }
                "com.tw.music.play", "com.tw.driving.play", "action.media.play",
                "com.android.music.musicservicecommand.togglepause" -> {
                    handler.postDelayed({ simulateMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }, 150)
                }
                "com.tw.music.pause", "com.tw.driving.pause", "action.media.pause" -> {
                    simulateMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
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
            addAction("com.tw.tdservice.next")
            addAction("com.tw.tdservice.prev")
            addAction("com.tw.tdservice.play")
            addAction("com.tw.tdservice.pause")
            addAction("action.media.next")
            addAction("action.media.prev")
            addAction("action.media.play")
            addAction("action.media.pause")
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

        sendTopwayBroadcasts(this, title, artist, subText, true)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn?.packageName == SPOTIFY_PACKAGE) {
            sendTopwayBroadcasts(this, "", "", "", false)
        }
    }
}
