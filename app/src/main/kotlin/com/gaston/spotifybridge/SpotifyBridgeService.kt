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
    }

    private val handler = Handler(Looper.getMainLooper())

    private val topwayControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.ts.music.next", "action.media.next" -> {
                    Log.d(TAG, "Topway pidió: NEXT")
                    handler.postDelayed({ simulateMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }, 200)
                }
                "com.ts.music.prev", "action.media.prev" -> {
                    Log.d(TAG, "Topway pidió: PREV")
                    handler.postDelayed({ simulateMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }, 200)
                }
                "com.ts.music.play", "action.media.play" -> {
                    Log.d(TAG, "Topway pidió: PLAY")
                    handler.postDelayed({ simulateMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY) }, 200)
                }
                "com.ts.music.pause", "action.media.pause" -> {
                    Log.d(TAG, "Topway pidió: PAUSE")
                    simulateMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                }
            }
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
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(topwayControlReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(topwayControlReceiver, filter)
        }
        Log.d(TAG, "Servicio puente iniciado y escuchando comandos de Topway.")
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
            Log.d(TAG, "Comando multimedia enviado a Spotify: $keyCode")
        } catch (e: Exception) {
            Log.e(TAG, "Error simulando tecla multimedia", e)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn?.packageName == SPOTIFY_PACKAGE) {
            val extras = sbn.notification.extras
            val title = extras.getString("android.title") ?: ""
            val artist = extras.getString("android.text") ?: ""

            val intentMeta = Intent("com.spotify.music.metadatachanged").apply {
                putExtra("id", "")
                putExtra("artist", artist)
                putExtra("album", "")
                putExtra("track", title)
                putExtra("playing", true)
            }
            sendBroadcast(intentMeta)

            val intentPlayback = Intent("com.spotify.music.playbackstatechanged").apply {
                putExtra("playing", true)
                putExtra("position", 0L)
            }
            sendBroadcast(intentPlayback)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn?.packageName == SPOTIFY_PACKAGE) {
            val intentPlayback = Intent("com.spotify.music.playbackstatechanged").apply {
                putExtra("playing", false)
            }
            sendBroadcast(intentPlayback)
        }
    }
}