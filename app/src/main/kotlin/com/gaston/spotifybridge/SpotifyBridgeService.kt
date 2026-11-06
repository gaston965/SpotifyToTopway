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

            // 0. Forzar la detención temporal del reproductor nativo local para que no robe el foco
            if (isPlaying) {
                val killLocalMusic = Intent("com.tw.music.pause").apply {
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or 0x01000000)
                }
                context.sendBroadcast(killLocalMusic)
                context.sendBroadcast(Intent(killLocalMusic).action = "com.ts.music.pause")
            }

            // 1. Avisar al sistema que Spotify está activo
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

            // 2. Broadcast de metadatos para el Launcher (Driving)
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
        }
