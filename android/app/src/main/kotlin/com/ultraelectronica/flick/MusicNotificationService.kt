package com.ultraelectronica.flick

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.plugin.common.MethodChannel
import java.io.File

class MusicNotificationService : Service() {
    
    companion object {
        const val CHANNEL_ID = "flick_music_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_PLAY_PAUSE = "com.ultraelectronica.flick.PLAY_PAUSE"
        const val ACTION_NEXT = "com.ultraelectronica.flick.NEXT"
        const val ACTION_PREVIOUS = "com.ultraelectronica.flick.PREVIOUS"
        const val ACTION_STOP = "com.ultraelectronica.flick.STOP"
        const val ACTION_SHUFFLE = "com.ultraelectronica.flick.SHUFFLE"
        const val ACTION_FAVORITE = "com.ultraelectronica.flick.FAVORITE"
        
        private const val PLAYER_CHANNEL = "com.ultraelectronica.flick/player"
    }
    
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var notificationManager: NotificationManager
    private var methodChannel: MethodChannel? = null
    private var isForegroundServiceStarted = false
    
    private var currentTitle: String = "Unknown"
    private var currentArtist: String = "Unknown Artist"
    private var currentAlbumArtPath: String? = null
    private var isPlaying: Boolean = false
    private var currentDuration: Long = 0
    private var currentPosition: Long = 0
    private var isShuffleMode: Boolean = false
    private var isFavorite: Boolean = false
    
    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            android.util.Log.d("MusicNotification", "Received action: ${intent?.action}")
            when (intent?.action) {
                ACTION_PLAY_PAUSE -> {
                    android.util.Log.d("MusicNotification", "Play/Pause tapped. Current isPlaying=$isPlaying")
                    sendCommandToFlutter("togglePlayPause")
                    // Optimistic local update so UI reflects immediately without waiting for Flutter
                    isPlaying = !isPlaying
                    val notification = buildNotification()
                    notificationManager.cancel(NOTIFICATION_ID)
                    notificationManager.notify(NOTIFICATION_ID, notification)
                    android.util.Log.d("MusicNotification", "Optimistically updated to isPlaying=$isPlaying")
                }
                ACTION_NEXT -> {
                    android.util.Log.d("MusicNotification", "Next action triggered")
                    sendCommandToFlutter("next")
                }
                ACTION_PREVIOUS -> {
                    android.util.Log.d("MusicNotification", "Previous action triggered")
                    sendCommandToFlutter("previous")
                }
                ACTION_STOP -> {
                    android.util.Log.d("MusicNotification", "Stop action triggered")
                    sendCommandToFlutter("stop")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                ACTION_SHUFFLE -> {
                    android.util.Log.d("MusicNotification", "Shuffle action triggered")
                    sendCommandToFlutter("toggleShuffle")
                }
                ACTION_FAVORITE -> {
                    android.util.Log.d("MusicNotification", "Favorite action triggered")
                    sendCommandToFlutter("toggleFavorite")
                }
            }
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        setupMediaSession()
        
        // Register broadcast receiver for notification actions with proper flags
        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY_PAUSE)
            addAction(ACTION_NEXT)
            addAction(ACTION_PREVIOUS)
            addAction(ACTION_STOP)
            addAction(ACTION_SHUFFLE)
            addAction(ACTION_FAVORITE)
        }
        
        // For Android 12+, we need to be explicit about receiver export status
        // Since these are internal app broadcasts, use NOT_EXPORTED for security
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(actionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12 (API 31-32): Use NOT_EXPORTED since intents are explicit
            registerReceiver(actionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(actionReceiver, filter)
        }
        
        // Get method channel from cached Flutter engine
        FlutterEngineCache.getInstance().get("main_engine")?.let { engine ->
            methodChannel = MethodChannel(engine.dartExecutor.binaryMessenger, PLAYER_CHANNEL)
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            if (it.hasExtra("title")) currentTitle = it.getStringExtra("title") ?: "Unknown"
            if (it.hasExtra("artist")) currentArtist = it.getStringExtra("artist") ?: "Unknown Artist"
            if (it.hasExtra("albumArtPath")) currentAlbumArtPath = it.getStringExtra("albumArtPath")
            if (it.hasExtra("isPlaying")) isPlaying = it.getBooleanExtra("isPlaying", false)
            if (it.hasExtra("duration")) {
                val durationValue = it.extras?.get("duration")
                currentDuration = when (durationValue) {
                    is Long -> durationValue
                    is Int -> durationValue.toLong()
                    is Number -> durationValue.toLong()
                    else -> it.getLongExtra("duration", 0)
                }
            }
            if (it.hasExtra("position")) {
                val positionValue = it.extras?.get("position")
                currentPosition = when (positionValue) {
                    is Long -> positionValue
                    is Int -> positionValue.toLong()
                    is Number -> positionValue.toLong()
                    else -> it.getLongExtra("position", 0)
                }
            }
            if (it.hasExtra("isShuffle")) isShuffleMode = it.getBooleanExtra("isShuffle", false)
            if (it.hasExtra("isFavorite")) isFavorite = it.getBooleanExtra("isFavorite", false)
        }
        
        val notification = buildNotification()
        
        // Only call startForeground on initial start, otherwise cancel+notify to force full redraw
        if (!isForegroundServiceStarted) {
            android.util.Log.d("MusicNotification", "Starting foreground service with state: isPlaying=$isPlaying")
            startForeground(NOTIFICATION_ID, notification)
            isForegroundServiceStarted = true
        } else {
            android.util.Log.d("MusicNotification", "Updating notification with state: isPlaying=$isPlaying, position=$currentPosition, duration=$currentDuration")
            // Cancel + notify forces Android to fully redraw the notification
            notificationManager.cancel(NOTIFICATION_ID)
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
        
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // ensure service keeps running
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(actionReceiver)
        } catch (e: Exception) {
            // Receiver not registered
        }
        mediaSession.release()
        isForegroundServiceStarted = false
    }
    
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Music Playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows currently playing song with playback controls"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        notificationManager.createNotificationChannel(channel)
    }
    
    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "FlickMusicSession").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { sendCommandToFlutter("play") }
                override fun onPause() { sendCommandToFlutter("pause") }
                override fun onSkipToNext() { sendCommandToFlutter("next") }
                override fun onSkipToPrevious() { sendCommandToFlutter("previous") }
                override fun onStop() {
                    sendCommandToFlutter("stop")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                override fun onSeekTo(pos: Long) {
                    sendCommandToFlutter("seek", mapOf("position" to pos))
                }
                override fun onSetShuffleMode(shuffleMode: Int) {
                    sendCommandToFlutter("toggleShuffle")
                }
                override fun onCustomAction(action: String?, extras: android.os.Bundle?) {
                   when(action) {
                       ACTION_SHUFFLE -> sendCommandToFlutter("toggleShuffle")
                       ACTION_FAVORITE -> sendCommandToFlutter("toggleFavorite")
                   }
                }
            })
            
            isActive = true
        }
        
        updateMediaSessionMetadata()
        updatePlaybackState()
    }
    
    private fun updateMediaSessionMetadata() {
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentDuration)
        
        currentAlbumArtPath?.let { path ->
            try {
                val bitmap = BitmapFactory.decodeFile(path)
                if (bitmap != null) {
                    metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                }
            } catch (e: Exception) {
                // Failed to load bitmap
            }
        }
        
        mediaSession.setMetadata(metadata.build())
    }
    
    private fun updatePlaybackState() {
        val state = if (isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }
        
        // Playback speed: 1.0f when playing, 0.0f when paused (for progress bar animation)
        val playbackSpeed = if (isPlaying) 1.0f else 0.0f
        
        // Set shuffle mode on MediaSession
        mediaSession.setShuffleMode(
            if (isShuffleMode) {
                PlaybackStateCompat.SHUFFLE_MODE_ALL
            } else {
                PlaybackStateCompat.SHUFFLE_MODE_NONE
            }
        )
        
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE
            )
            .setState(state, currentPosition, playbackSpeed, android.os.SystemClock.elapsedRealtime())
            .build()
        
        mediaSession.setPlaybackState(playbackState)
    }
    
    private fun buildNotification(): Notification {
        updateMediaSessionMetadata()
        updatePlaybackState()
        
        // Intent to open the app (bring to front, don't create new instance)
        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        
        // Action intents - use FLAG_CANCEL_CURRENT to force fresh intents each time
        val playPauseIntent = PendingIntent.getBroadcast(
            this,
            100,
            Intent(ACTION_PLAY_PAUSE).apply {
                setPackage(packageName) // Explicit package for Android 12+
            },
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val prevIntent = PendingIntent.getBroadcast(
            this,
            101,
            Intent(ACTION_PREVIOUS).apply {
                setPackage(packageName)
            },
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nextIntent = PendingIntent.getBroadcast(
            this,
            102,
            Intent(ACTION_NEXT).apply {
                setPackage(packageName)
            },
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val favoriteIntent = PendingIntent.getBroadcast(
            this,
            103,
            Intent(ACTION_FAVORITE).apply {
                setPackage(packageName)
            },
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Load album art
        val albumArt: Bitmap? = currentAlbumArtPath?.let { path ->
            try {
                BitmapFactory.decodeFile(path)
            } catch (e: Exception) {
                null
            }
        }
        
        val playPauseIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        val playPauseText = if (isPlaying) "Pause" else "Play"
        
        val shuffleIcon = R.drawable.ic_shuffle
        val shuffleText = if(isShuffleMode) "Shuffle: On" else "Shuffle: Off"
        
        val favoriteIcon = if(isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border
        val favoriteText = if(isFavorite) "Unfavorite" else "Favorite"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle)
            .setContentText(currentArtist)
            .setSubText("${formatTime(currentPosition)} / ${formatTime(currentDuration)}")  // Show time in subtitle
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(albumArt)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(true)  // Always ongoing to prevent Android from treating play/pause as different types
        
        // Add actions in order: Prev, Play/Pause, Next, Shuffle, Favorite
        // Compact view shows first 3, expanded view shows all 5
        builder.addAction(R.drawable.ic_previous, "Previous", prevIntent)
        builder.addAction(playPauseIcon, playPauseText, playPauseIntent)
        builder.addAction(R.drawable.ic_next, "Next", nextIntent)
        
        // Create shuffle PendingIntent
        val shuffleIntent = PendingIntent.getBroadcast(
            this,
            104,
            Intent(ACTION_SHUFFLE).apply { setPackage(packageName) },
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(shuffleIcon, shuffleText, shuffleIntent)
        builder.addAction(favoriteIcon, favoriteText, favoriteIntent)
        
        // Configure MediaStyle - compact view always shows max 3 buttons
        // Expanded view automatically shows all 5 buttons
        val mediaStyle = MediaNotificationCompat.MediaStyle()
            .setMediaSession(mediaSession.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)  // Prev, Play/Pause, Next
            .setShowCancelButton(true)
        
        builder.setStyle(mediaStyle)
        
        // Add progress bar for Android 9 and below (MediaStyle handles it automatically on 10+)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && currentDuration > 0) {
            val progress = if (currentDuration > 0) {
                ((currentPosition.toFloat() / currentDuration.toFloat()) * 100).toInt()
            } else {
                0
            }
            builder.setProgress(100, progress, false)
        }
        
        return builder.build()
    }
    
    private fun formatTime(millis: Long): String {
        val seconds = (millis / 1000).toInt()
        val minutes = seconds / 60
        val secs = seconds % 60
        return String.format("%d:%02d", minutes, secs)
    }
    
    fun updateNotification(title: String?, artist: String?, albumArtPath: String?, playing: Boolean?, duration: Long?, position: Long?, shuffle: Boolean?, favorite: Boolean?) {
        title?.let { currentTitle = it }
        artist?.let { currentArtist = it }
        albumArtPath?.let { currentAlbumArtPath = it }
        playing?.let { isPlaying = it }
        duration?.let { currentDuration = it }
        position?.let { currentPosition = it }
        shuffle?.let { isShuffleMode = it }
        favorite?.let { isFavorite = it }
        
        val notification = buildNotification()
        // Cancel + notify forces Android to fully redraw the notification
        notificationManager.cancel(NOTIFICATION_ID)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun sendCommandToFlutter(command: String, args: Map<String, Any>? = null) {
        android.os.Handler(mainLooper).post {
            try {
                if (methodChannel == null) {
                    android.util.Log.w("MusicNotification", "Method channel is null, attempting to reconnect")
                    // Try to re-establish connection if channel is null
                    FlutterEngineCache.getInstance().get("main_engine")?.let { engine ->
                        methodChannel = MethodChannel(engine.dartExecutor.binaryMessenger, PLAYER_CHANNEL)
                        android.util.Log.d("MusicNotification", "Method channel reconnected")
                    } ?: android.util.Log.e("MusicNotification", "Failed to get Flutter engine from cache")
                }
                
                android.util.Log.d("MusicNotification", "Sending command to Flutter: $command")
                methodChannel?.invokeMethod(command, args, object : MethodChannel.Result {
                    override fun success(result: Any?) {
                        android.util.Log.d("MusicNotification", "Command $command succeeded")
                    }
                    override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                        android.util.Log.e("MusicNotification", "Command $command failed: $errorCode - $errorMessage")
                    }
                    override fun notImplemented() {
                        android.util.Log.e("MusicNotification", "Command $command not implemented")
                    }
                })
            } catch (e: Exception) {
                android.util.Log.e("MusicNotification", "Failed to send command to Flutter: $command, error: ${e.message}", e)
            }
        }
    }
}
