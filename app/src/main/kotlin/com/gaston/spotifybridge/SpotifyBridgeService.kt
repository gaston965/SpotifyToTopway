package com.gaston.spotifybridge

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
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

        // Carátula mínima (cuadrado gris) para cuando no hay imagen:
        // TWSpotify siempre manda un byte[] en msg_Albumart.
        private fun placeholderArt(): ByteArray {
            val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.DKGRAY)
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 100, bos)
            return bos.toByteArray()
        }

        // Manda a la home EXACTAMENTE lo que manda TWSpotify, con los mismos
        // nombres y tipos (sacados de com.tw.spotify, sendMusicInfoReceiver):
        //   musicType -> String, playState -> Boolean, msg_Artist -> String,
        //   msg_Song -> String, msg_Albumart -> byte[]
        // Un solo broadcast por evento.
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

    private val topwayControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val act = intent?.action ?: return
            val extrasStr = intent.extras?.keySet()?.joinToString { k -> "$k=${intent.extras?.get(k)}" } ?: ""
            val entry = "$act [$extrasStr]"
            if (capturedLogs.size > 20) capturedLogs.removeAt(0)
            capturedLogs.add(entry)

            if (act == "com.tw.music.spotify.control") {
                // Numeración deducida del código de TWSpotify (no es texto explícito):
                // 1 = play, 2 = anterior, 3 = siguiente, otro = pausa.
                // El número real queda guardado en el registro de la pantalla.
                when (intent.getIntExtra("controlID", -1)) {
                    1 -> sendControl(KeyEvent.KEYCODE_MEDIA_PLAY)
                    2 -> sendControl(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                    3 -> sendControl(KeyEvent.KEYCODE_MEDIA_NEXT)
                    else -> sendControl(KeyEvent.KEYCODE_MEDIA_PAUSE)
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

    // Busca la sesión de medios de Spotify (permitido porque este servicio
    // es un listener de notificaciones con acceso concedido).
    private fun spotifyController(): MediaController? {
        return try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            msm.getActiveSessions(ComponentName(this, SpotifyBridgeService::class.java))
                .firstOrNull { it.packageName == SPOTIFY_PACKAGE }
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo obtener la sesión de Spotify", e)
            null
        }
    }

    private fun isSpotifyPlaying(): Boolean {
        val c = spotifyController() ?: return true
        return c.playbackState?.state == PlaybackState.STATE_PLAYING
    }

    // Control preferido: la sesión de medios. Si no se puede, tecla multimedia.
    private fun sendControl(keyCode: Int) {
        val c = spotifyController()
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
        simulateMediaKey(keyCode)
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

        // Carátula de la notificación, convertida a bytes JPEG
        var artBytes: ByteArray? = null
        try {
            val largeIcon = sbn.notification.getLargeIcon()
            val drawable = largeIcon?.loadDrawable(this)
            val bmp = (drawable as? BitmapDrawable)?.bitmap
                ?: extras.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON)
            if (bmp != null) {
                val scaled = Bitmap.createScaledBitmap(bmp, 150, 150, true)
                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 75, baos)
                artBytes = baos.toByteArray()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        sendTopwayBroadcasts(this, title, artist, subText, isSpotifyPlaying(), artBytes, null)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn?.packageName == SPOTIFY_PACKAGE) {
            sendTopwayBroadcasts(this, "", "", "", false, null, null)
        }
    }
}
