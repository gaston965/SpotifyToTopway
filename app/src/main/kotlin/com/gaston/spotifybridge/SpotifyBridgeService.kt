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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(topwayControlReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(topwayControlReceiver, filter)
        }
    }
}
