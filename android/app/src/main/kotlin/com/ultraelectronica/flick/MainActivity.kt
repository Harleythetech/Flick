package com.ultraelectronica.flick

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.MediaMetadataRetriever
import android.media.audiofx.Equalizer
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

class MainActivity: FlutterActivity() {
    private val CHANNEL = "com.ultraelectronica.flick/storage"
    private val PLAYER_CHANNEL = "com.ultraelectronica.flick/player"
    private val UAC2_CHANNEL = "com.ultraelectronica.flick/uac2"
    private val EQUALIZER_CHANNEL = "com.ultraelectronica.flick/equalizer"
    // private val CONVERTER_CHANNEL = "com.ultraelectronica.flick/converter"
    private val REQUEST_OPEN_DOCUMENT_TREE = 1001
    private val REQUEST_USB_PERMISSION = 1002

    private var pendingResult: MethodChannel.Result? = null
    private var pendingUac2PermissionResult: MethodChannel.Result? = null
    private var usbPermissionReceiver: BroadcastReceiver? = null
    private var usbHotplugReceiver: BroadcastReceiver? = null
    private var uac2DeviceCache: List<Map<String, Any?>>? = null
    private var uac2Channel: MethodChannel? = null
    private var equalizer: Equalizer? = null
    // private var audioConverter: AudioConverter? = null
    // Coroutine scope for background tasks
    private val mainScope = CoroutineScope(Dispatchers.Main)

    // Initialize Android NDK context for cpal/oboe before Dart loads the library
    init {
        System.loadLibrary("rust_lib_flick_player")
    }

    override fun provideFlutterEngine(context: android.content.Context): FlutterEngine? {
        var engine = FlutterEngineCache.getInstance().get("main_engine")
        if (engine == null) {
            engine = FlutterEngine(context)
            engine.dartExecutor.executeDartEntrypoint(
                DartExecutor.DartEntrypoint.createDefault()
            )
            FlutterEngineCache.getInstance().put("main_engine", engine)
        }
        return engine
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "openDocumentTree" -> {
                    pendingResult = result
                    openDocumentTree()
                }
                "takePersistableUriPermission" -> {
                    val uri = call.argument<String>("uri")
                    if (uri != null) {
                        val success = takePersistableUriPermission(uri)
                        result.success(success)
                    } else {
                        result.error("INVALID_ARGUMENT", "URI is required", null)
                    }
                }
                "releasePersistableUriPermission" -> {
                    val uri = call.argument<String>("uri")
                    if (uri != null) {
                        releasePersistableUriPermission(uri)
                        result.success(null)
                    } else {
                        result.error("INVALID_ARGUMENT", "URI is required", null)
                    }
                }
                "getPersistedUriPermissions" -> {
                    val uris = getPersistedUriPermissions()
                    result.success(uris)
                }
                "listAudioFiles" -> {
                    val uri = call.argument<String>("uri")
                    if (uri != null) {
                        // Launch in background to avoid blocking UI
                        mainScope.launch {
                            try {
                                val files = withContext(Dispatchers.IO) {
                                    fastScanAudioFiles(uri)
                                }
                                result.success(files)
                            } catch (e: Exception) {
                                result.error("SCAN_ERROR", "Failed to scan folder: ${e.message}", null)
                            }
                        }
                    } else {
                        result.error("INVALID_ARGUMENT", "URI is required", null)
                    }
                }
                "fetchAudioMetadata" -> {
                    val uris = call.argument<List<String>>("uris")
                    if (uris != null) {
                        mainScope.launch {
                            try {
                                val metadata = withContext(Dispatchers.IO) {
                                    extractMetadataForFiles(uris)
                                }
                                result.success(metadata)
                            } catch (e: Exception) {
                                result.error("METADATA_ERROR", "Failed to fetch metadata: ${e.message}", null)
                            }
                        }
                    } else {
                        result.error("INVALID_ARGUMENT", "URIs list is required", null)
                    }
                }
                "cacheUriForPlayback" -> {
                    val uri = call.argument<String>("uri")
                    val extensionHint = call.argument<String>("extensionHint")
                    if (uri != null) {
                        mainScope.launch {
                            try {
                                val stagedPath = withContext(Dispatchers.IO) {
                                    cacheUriForPlayback(uri, extensionHint)
                                }
                                result.success(stagedPath)
                            } catch (e: Exception) {
                                result.error("CACHE_URI_ERROR", "Failed to stage audio URI: ${e.message}", null)
                            }
                        }
                    } else {
                        result.error("INVALID_ARGUMENT", "URI is required", null)
                    }
                }
                "readSiblingLyrics" -> {
                    val audioUri = call.argument<String>("audioUri")
                    if (audioUri != null) {
                        mainScope.launch {
                            try {
                                val lyrics = withContext(Dispatchers.IO) {
                                    readSiblingLyrics(audioUri)
                                }
                                result.success(lyrics)
                            } catch (e: Exception) {
                                result.error("LYRICS_READ_ERROR", "Failed to read lyrics: ${e.message}", null)
                            }
                        }
                    } else {
                        result.error("INVALID_ARGUMENT", "audioUri is required", null)
                    }
                }
                "readEmbeddedLyrics" -> {
                    val audioUri = call.argument<String>("audioUri")
                    if (audioUri != null) {
                        mainScope.launch {
                            try {
                                val lyrics = withContext(Dispatchers.IO) {
                                    readEmbeddedLyrics(audioUri)
                                }
                                result.success(lyrics)
                            } catch (e: Exception) {
                                result.error("LYRICS_EMBEDDED_ERROR", "Failed to read embedded lyrics: ${e.message}", null)
                            }
                        }
                    } else {
                        result.error("INVALID_ARGUMENT", "audioUri is required", null)
                    }
                }
                "getDocumentDisplayName" -> {
                    val uri = call.argument<String>("uri")
                    if (uri != null) {
                        val displayName = getDocumentDisplayName(uri)
                        result.success(displayName)
                    } else {
                        result.error("INVALID_ARGUMENT", "URI is required", null)
                    }
                }
                else -> result.notImplemented()
            }
        }
        
        // Cache the Flutter engine for notification service to use
        // Engine is already cached in provideFlutterEngine
        
        // Player channel for notification control
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, PLAYER_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "showNotification" -> {
                    val title = call.argument<String>("title")
                    val artist = call.argument<String>("artist")
                    val albumArtPath = call.argument<String>("albumArtPath")
                    val isPlaying = call.argument<Boolean>("isPlaying") ?: true
                    // Handle both Integer and Long types from Flutter
                    val duration = when (val d = call.argument<Any>("duration")) {
                        is Long -> d
                        is Int -> d.toLong()
                        else -> 0L
                    }
                    val position = when (val p = call.argument<Any>("position")) {
                        is Long -> p
                        is Int -> p.toLong()
                        else -> 0L
                    }
                    val isShuffle = call.argument<Boolean>("isShuffle") ?: false
                    val isFavorite = call.argument<Boolean>("isFavorite") ?: false
                    
                    val intent = Intent(this, MusicNotificationService::class.java).apply {
                        putExtra("title", title)
                        putExtra("artist", artist)
                        putExtra("albumArtPath", albumArtPath)
                        putExtra("isPlaying", isPlaying)
                        putExtra("duration", duration)
                        putExtra("position", position)
                        putExtra("isShuffle", isShuffle)
                        putExtra("isFavorite", isFavorite)
                    }
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    result.success(null)
                }
                "updateNotification" -> {
                    val title = call.argument<String>("title")
                    val artist = call.argument<String>("artist")
                    val albumArtPath = call.argument<String>("albumArtPath")
                    val isPlaying = call.argument<Boolean>("isPlaying")
                    // Handle both Integer and Long types from Flutter
                    val duration = call.argument<Any>("duration")?.let { d ->
                        when (d) {
                            is Long -> d
                            is Int -> d.toLong()
                            else -> null
                        }
                    }
                    val position = call.argument<Any>("position")?.let { p ->
                        when (p) {
                            is Long -> p
                            is Int -> p.toLong()
                            else -> null
                        }
                    }
                    val isShuffle = call.argument<Boolean>("isShuffle")
                    val isFavorite = call.argument<Boolean>("isFavorite")
                    
                    val intent = Intent(this, MusicNotificationService::class.java).apply {
                        title?.let { putExtra("title", it) }
                        artist?.let { putExtra("artist", it) }
                        albumArtPath?.let { putExtra("albumArtPath", it) }
                        isPlaying?.let { putExtra("isPlaying", it) }
                        duration?.let { putExtra("duration", it) }
                        position?.let { putExtra("position", it) }
                        isShuffle?.let { putExtra("isShuffle", it) }
                        isFavorite?.let { putExtra("isFavorite", it) }
                    }
                    startService(intent)
                    result.success(null)
                }
                "hideNotification" -> {
                    stopService(Intent(this, MusicNotificationService::class.java))
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }

        // UAC 2.0 USB Host API (Android): list devices and request permission
        uac2Channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, UAC2_CHANNEL)
        uac2Channel?.setMethodCallHandler { call, result ->
            when (call.method) {
                "listDevices" -> {
                    val refresh = call.argument<Boolean>("refresh") ?: false
                    val devices = listUac2Devices(refresh)
                    result.success(devices)
                }
                "requestPermission" -> {
                    val deviceName = call.argument<String>("deviceName")
                    if (deviceName != null) {
                        requestUac2Permission(deviceName, result)
                    } else {
                        result.error("INVALID_ARGUMENT", "deviceName is required", null)
                    }
                }
                "hasPermission" -> {
                    val deviceName = call.argument<String>("deviceName")
                    if (deviceName != null) {
                        val has = hasUac2Permission(deviceName)
                        result.success(has)
                    } else {
                        result.error("INVALID_ARGUMENT", "deviceName is required", null)
                    }
                }
                else -> result.notImplemented()
            }
        }

        // Equalizer channel for Android native AudioEffect API
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, EQUALIZER_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "setEqualizer" -> {
                    val enabled = call.argument<Boolean>("enabled") ?: false
                    val gainsDb = call.argument<List<Double>>("gainsDb")
                    @Suppress("UNCHECKED_CAST")
                    val audioSessionId = (call.arguments as? Map<String, Any?>)?.get("audioSessionId")?.let {
                        when (it) {
                            is Number -> it.toInt()
                            else -> null
                        }
                    }
                    if (gainsDb != null && gainsDb.size == 10) {
                        setEqualizer(enabled, gainsDb, audioSessionId, result)
                    } else {
                        result.error("INVALID_ARGUMENT", "gainsDb must be a list of 10 doubles", null)
                    }
                }
                else -> result.notImplemented()
            }
        }

        // Audio converter channel
        // NOTE: FLAC to ALAC and M4A to MP3 conversion features are disabled for now
        /*
        audioConverter = AudioConverterFactory.create()
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CONVERTER_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "convertToFlac" -> {
                    val uri = call.argument<String>("uri")
                    if (uri != null) {
                        mainScope.launch {
                            val conversionResult = audioConverter?.convertToFlac(Uri.parse(uri), context) as? ConversionResult
                            handleConversionResult(conversionResult, result)
                        }
                    } else {
                        result.error("INVALID_ARGUMENT", "URI is required", null)
                    }
                }
                "convertToMp3" -> {
                    val uri = call.argument<String>("uri")
                    if (uri != null) {
                        mainScope.launch {
                            val conversionResult = audioConverter?.convertToMp3(Uri.parse(uri), context) as? ConversionResult
                            handleConversionResult(conversionResult, result)
                        }
                    } else {
                        result.error("INVALID_ARGUMENT", "URI is required", null)
                    }
                }
                "isSupported" -> {
                    val extension = call.argument<String>("extension")
                    if (extension != null) {
                        result.success(audioConverter?.isSupported(extension) ?: false)
                    } else {
                        result.error("INVALID_ARGUMENT", "extension is required", null)
                    }
                }
                "cancel" -> {
                    audioConverter?.cancel()
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
        */
        
        // Register USB hot-plug receiver
        registerUsbHotplugReceiver()
    }

    // private fun handleConversionResult(conversionResult: ConversionResult?, result: MethodChannel.Result) {
//         when (conversionResult) {
//             is ConversionResult.Success -> result.success(mapOf(
//                 "success" to true,
//                 "outputUri" to conversionResult.outputUri,
//                 "outputPath" to conversionResult.outputPath
//             ))
//             is ConversionResult.Error -> result.error("CONVERSION_ERROR", conversionResult.message, null)
//             else -> result.error("CONVERSION_ERROR", "Unknown error", null)
//         }
//     }

    private fun setEqualizer(enabled: Boolean, gainsDb: List<Double>, audioSessionId: Int?, result: MethodChannel.Result) {
        try {
            // Release existing equalizer if any
            equalizer?.release()
            equalizer = null

            if (!enabled) {
                result.success(null)
                return
            }

            // Must have audio session ID from just_audio (playback must have started at least once)
            val sessionId = audioSessionId ?: run {
                result.error("EQUALIZER_ERROR", "Audio session not ready. Start playback first.", null)
                return
            }

            // Create equalizer effect attached to the same session as the player
            equalizer = try {
                Equalizer(0, sessionId)
            } catch (e: Exception) {
                result.error("EQUALIZER_ERROR", "Equalizer not available: ${e.message}", null)
                return
            }

            val eq = equalizer ?: run {
                result.error("EQUALIZER_ERROR", "Failed to create equalizer", null)
                return
            }

            // Enable the equalizer
            eq.enabled = true

            // Map 10-band graphic EQ to Android's equalizer bands
            // Android Equalizer typically has 5 bands, so we'll map our 10 bands to 5
            val numBands = eq.numberOfBands
            val bandLevelRange = eq.bandLevelRange
            val minLevel = bandLevelRange[0] / 100.0 // Convert from mB to dB
            val maxLevel = bandLevelRange[1] / 100.0

            // Map 10 bands to available bands (simple averaging)
            for (i in 0 until numBands) {
                val startIdx = (i * 10) / numBands
                val endIdx = ((i + 1) * 10) / numBands
                var avgGain = 0.0
                for (j in startIdx until endIdx) {
                    avgGain += gainsDb[j]
                }
                avgGain /= (endIdx - startIdx)

                // Clamp gain to Android's range
                val clampedGain = avgGain.coerceIn(minLevel, maxLevel)
                val levelInMillibels = (clampedGain * 100).toInt()
                eq.setBandLevel(i.toShort(), levelInMillibels.toShort())
            }

            result.success(null)
        } catch (e: Exception) {
            result.error("EQUALIZER_ERROR", "Failed to set equalizer: ${e.message}", null)
        }
    }

    private fun openDocumentTree() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_OPEN_DOCUMENT_TREE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_OPEN_DOCUMENT_TREE) {
            if (resultCode == RESULT_OK && data?.data != null) {
                val uri = data.data!!
                pendingResult?.success(uri.toString())
            } else {
                pendingResult?.success(null)
            }
            pendingResult = null
        }
    }

    private fun takePersistableUriPermission(uriString: String): Boolean {
        return try {
            val uri = Uri.parse(uriString)
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun releasePersistableUriPermission(uriString: String) {
        try {
            val uri = Uri.parse(uriString)
            contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            // Ignore if permission wasn't held
        }
    }

    private fun getPersistedUriPermissions(): List<String> {
        return contentResolver.persistedUriPermissions.map { it.uri.toString() }
    }

    private fun getDocumentDisplayName(uriString: String): String? {
        return try {
            val uri = Uri.parse(uriString)
            val documentFile = DocumentFile.fromTreeUri(this, uri)
            documentFile?.name
        } catch (e: Exception) {
            null
        }
    }

    // Phase 1: Fast Scan (Filesystem only)
    private fun fastScanAudioFiles(uriString: String): List<Map<String, Any?>> {
        val uri = Uri.parse(uriString)
        val documentFile = DocumentFile.fromTreeUri(this, uri) ?: return emptyList()
        
        val audioExtensions = setOf("mp3", "flac", "wav", "aac", "m4a", "ogg", "opus", "wma", "alac", "aif", "aiff")
        val result = mutableListOf<Map<String, Any?>>()

        fun scanDirectory(dir: DocumentFile) {
            for (file in dir.listFiles()) {
                if (file.isDirectory) {
                    scanDirectory(file)
                } else if (file.isFile) {
                    val name = file.name ?: continue
                    val extension = name.substringAfterLast('.', "").lowercase()
                    if (extension in audioExtensions) {
                        result.add(mapOf(
                            "uri" to file.uri.toString(),
                            "name" to name,
                            "size" to file.length(),
                            "lastModified" to file.lastModified(),
                            "mimeType" to file.type,
                            "extension" to extension
                        ))
                    }
                }
            }
        }

        scanDirectory(documentFile)
        return result
    }

    // Phase 2: Metadata Extraction (Targeted)
    private fun extractMetadataForFiles(uris: List<String>): List<Map<String, Any?>> {
        val retriever = MediaMetadataRetriever()
        val result = mutableListOf<Map<String, Any?>>()
        // Use filesDir instead of cacheDir for persistent album art storage
        // cacheDir can be cleared by Android at any time when storage is low
        val albumArtDir = java.io.File(filesDir, "album_art").apply { mkdirs() }

        for (uriString in uris) {
            try {
                val uri = Uri.parse(uriString)
                retriever.setDataSource(context, uri)
                
                val metadata = mutableMapOf<String, Any?>("uri" to uriString)
                
                metadata["title"] = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                metadata["artist"] = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                metadata["album"] = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                metadata["bitrate"] = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                metadata["mimeType"] = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val sampleRateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                    if (sampleRateStr != null) {
                        metadata["sampleRate"] = sampleRateStr.toIntOrNull()
                    }
                    
                    val bitDepthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
                    if (bitDepthStr != null) {
                        metadata["bitDepth"] = bitDepthStr.toIntOrNull()
                    }
                }
                
                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                if (durationStr != null) {
                    metadata["duration"] = durationStr.toLongOrNull()
                }

                // Extract Album Art
                val embeddedArt = retriever.embeddedPicture
                if (embeddedArt != null) {
                    try {
                        // Use MD5 of URI as filename to avoid collisions and invalid chars
                        val filename = java.math.BigInteger(1, java.security.MessageDigest.getInstance("MD5").digest(uriString.toByteArray())).toString(16) + ".jpg"
                        val file = java.io.File(albumArtDir, filename)
                        
                        // Only write if not exists or maybe overwrite? 
                        // For performance, check existence. 
                        // But what if art changed? (Unlikely for same URI without modified time change, but we assume immutable for now)
                        if (!file.exists()) {
                            file.writeBytes(embeddedArt)
                        }
                        metadata["albumArtPath"] = file.absolutePath
                    } catch (e: Exception) {
                        // Failed to save art
                    }
                }

                result.add(metadata)
            } catch (e: Exception) {
                // Return just the URI if metadata fails, so Dart knows we tried
                result.add(mapOf("uri" to uriString))
            }
        }

        try {
            retriever.release()
        } catch (e: Exception) {
            // Ignore
        }

        return result
    }

    private fun cacheUriForPlayback(uriString: String, extensionHint: String?): String? {
        val uri = Uri.parse(uriString)
        val normalizedExt = normalizeAudioExtension(extensionHint)
        val stagingDir = java.io.File(cacheDir, "playback_staging").apply { mkdirs() }
        val fileHash = md5(uriString)
        val stagedFile = java.io.File(stagingDir, "$fileHash.$normalizedExt")
        val tempFile = java.io.File(stagingDir, "$fileHash.$normalizedExt.tmp")

        try {
            // Reuse cached file only when non-empty and likely complete.
            val expectedLength = try {
                contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    if (afd.length > 0L) afd.length else null
                }
            } catch (_: Exception) {
                null
            }
            if (stagedFile.exists() && stagedFile.length() > 0L) {
                if (expectedLength == null || stagedFile.length() == expectedLength) {
                    return stagedFile.absolutePath
                }
            }

            if (tempFile.exists()) {
                tempFile.delete()
            }

            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream == null) {
                // If provider temporarily fails, keep using last known-good staged file.
                if (stagedFile.exists() && stagedFile.length() > 0L) {
                    return stagedFile.absolutePath
                }
                return null
            }

            inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            if (!tempFile.exists() || tempFile.length() <= 0L) {
                tempFile.delete()
                return null
            }

            if (stagedFile.exists()) {
                stagedFile.delete()
            }
            if (!tempFile.renameTo(stagedFile)) {
                // Fallback: explicit copy and cleanup if rename fails.
                tempFile.inputStream().use { input ->
                    stagedFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                tempFile.delete()
            }

            if (stagedFile.length() <= 0L) return null
            return stagedFile.absolutePath
        } catch (e: Exception) {
            android.util.Log.e("FlickPlayback", "cacheUriForPlayback failed for $uriString: ${e.message}", e)
            return null
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private fun readSiblingLyrics(audioUriString: String): Map<String, String>? {
        return try {
            val audioUri = Uri.parse(audioUriString)
            when (audioUri.scheme) {
                "content" -> readSiblingLyricsFromContentUri(audioUri)
                "file" -> readSiblingLyricsFromFilePath(audioUri.path)
                null, "" -> readSiblingLyricsFromFilePath(audioUriString)
                else -> null
            }
        } catch (e: Exception) {
            android.util.Log.w("FlickLyrics", "readSiblingLyrics failed for $audioUriString: ${e.message}")
            null
        }
    }

    private fun readEmbeddedLyrics(audioUriString: String): Map<String, String>? {
        val retriever = MediaMetadataRetriever()
        return try {
            val uri = Uri.parse(audioUriString)

            val handledByUri = when (uri.scheme) {
                "content", "file" -> {
                    retriever.setDataSource(this, uri)
                    true
                }
                else -> false
            }

            if (!handledByUri) {
                retriever.setDataSource(audioUriString)
            }

            val lyricKey = try {
                MediaMetadataRetriever::class.java.getField("METADATA_KEY_LYRIC").getInt(null)
            } catch (_: Exception) {
                null
            }

            val lyricText = lyricKey?.let { retriever.extractMetadata(it) }
            if (lyricText.isNullOrBlank()) {
                null
            } else {
                mapOf(
                    "content" to lyricText,
                    "source" to "embedded",
                )
            }
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun readSiblingLyricsFromContentUri(audioUri: Uri): Map<String, String>? {
        val audioName = DocumentFile.fromSingleUri(this, audioUri)?.name ?: return null
        val stem = audioName.substringBeforeLast('.', audioName)
        val candidateNames = listOf("$stem.lrc", "$stem.txt")
        val candidateSet = candidateNames.map { it.lowercase() }.toSet()

        val authority = audioUri.authority ?: return null
        val documentId = try {
            DocumentsContract.getDocumentId(audioUri)
        } catch (e: Exception) {
            return null
        }
        val slashIndex = documentId.lastIndexOf('/')
        if (slashIndex <= 0) return null
        val parentDocumentId = documentId.substring(0, slashIndex)

        // Fast path: build candidate URIs directly using parent document id.
        for (candidateName in candidateNames) {
            val candidateDocumentId = "$parentDocumentId/$candidateName"
            val directUri = DocumentsContract.buildDocumentUri(authority, candidateDocumentId)
            val directContent = readTextFromUri(directUri)
            if (!directContent.isNullOrBlank()) {
                return mapOf(
                    "content" to directContent,
                    "uri" to directUri.toString(),
                    "name" to candidateName,
                )
            }

            val treeUri = try {
                DocumentsContract.buildDocumentUriUsingTree(audioUri, candidateDocumentId)
            } catch (_: Exception) {
                null
            }
            if (treeUri != null) {
                val treeContent = readTextFromUri(treeUri)
                if (!treeContent.isNullOrBlank()) {
                    return mapOf(
                        "content" to treeContent,
                        "uri" to treeUri.toString(),
                        "name" to candidateName,
                    )
                }
            }
        }

        // Fallback: list siblings from parent and match candidate names case-insensitively.
        val childrenUri = try {
            DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId)
        } catch (e: Exception) {
            return null
        }

        contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (idIndex == -1 || nameIndex == -1) {
                return null
            }

            while (cursor.moveToNext()) {
                val displayName = cursor.getString(nameIndex) ?: continue
                if (!candidateSet.contains(displayName.lowercase())) continue

                val childDocumentId = cursor.getString(idIndex) ?: continue
                val childUri = DocumentsContract.buildDocumentUri(authority, childDocumentId)
                val content = readTextFromUri(childUri)
                if (!content.isNullOrBlank()) {
                    return mapOf(
                        "content" to content,
                        "uri" to childUri.toString(),
                        "name" to displayName,
                    )
                }
            }
        }

        return null
    }

    private fun readSiblingLyricsFromFilePath(audioPath: String?): Map<String, String>? {
        if (audioPath.isNullOrBlank()) return null

        val audioFile = java.io.File(audioPath)
        val parent = audioFile.parentFile ?: return null
        val stem = audioFile.name.substringBeforeLast('.', audioFile.name)
        val candidateNames = listOf("$stem.lrc", "$stem.txt", "$stem.LRC", "$stem.TXT")

        for (candidateName in candidateNames) {
            val candidateFile = java.io.File(parent, candidateName)
            if (!candidateFile.exists() || !candidateFile.isFile) continue

            val text = try {
                candidateFile.readText(Charsets.UTF_8)
            } catch (_: Exception) {
                try {
                    candidateFile.readText(Charsets.ISO_8859_1)
                } catch (_: Exception) {
                    null
                }
            }

            if (!text.isNullOrBlank()) {
                return mapOf(
                    "content" to text,
                    "uri" to candidateFile.absolutePath,
                    "name" to candidateFile.name,
                )
            }
        }

        return null
    }

    private fun readTextFromUri(uri: Uri): String? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeAudioExtension(extensionHint: String?): String {
        val ext = extensionHint
            ?.trim()
            ?.lowercase()
            ?.removePrefix(".")
            ?.ifEmpty { null } ?: return "m4a"

        return when (ext) {
            "aif", "aiff" -> "aiff"
            "m4a", "alac" -> "m4a"
            else -> ext
        }
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ========== UAC 2.0 USB Host (Android) ==========

    private fun listUac2Devices(refresh: Boolean = false): List<Map<String, Any?>> {
        if (!refresh && uac2DeviceCache != null) {
            return uac2DeviceCache!!
        }
        
        val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usbManager == null) {
            android.util.Log.e("UAC2", "USB service unavailable")
            return emptyList()
        }
        
        val deviceList = usbManager.deviceList ?: return emptyList()
        val result = mutableListOf<Map<String, Any?>>()
        
        for (device in deviceList.values) {
            try {
                if (!isUac2Device(device)) continue
                
                val hasPermission = usbManager.hasPermission(device)
                
                // Extract strings (available without opening device on API 21+)
                val productName = device.productName ?: "USB Audio Device"
                val manufacturer = device.manufacturerName ?: ""
                val serial = device.serialNumber ?: device.deviceName
                
                result.add(mapOf(
                    "deviceName" to device.deviceName,
                    "vendorId" to device.vendorId,
                    "productId" to device.productId,
                    "productName" to productName,
                    "manufacturer" to manufacturer,
                    "serial" to serial,
                ))
            } catch (e: Exception) {
                android.util.Log.w("UAC2", "Failed to process device ${device.deviceName}: ${e.message}")
                // Continue with other devices
            }
        }
        
        uac2DeviceCache = result
        return result
    }

    private fun isUac2Device(device: UsbDevice): Boolean {
        return try {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                // UAC 2.0: Class 0x01 (Audio), Subclass 0x02 (UAC2), Protocol 0x20 (UAC2)
                if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                    iface.interfaceSubclass == 0x02 &&
                    iface.interfaceProtocol == 0x20
                ) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            android.util.Log.w("UAC2", "Error checking device: ${e.message}")
            false
        }
    }

    private fun hasUac2Permission(deviceName: String): Boolean {
        return try {
            val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
            val device = usbManager.deviceList?.get(deviceName) ?: return false
            usbManager.hasPermission(device)
        } catch (e: Exception) {
            android.util.Log.e("UAC2", "Error checking permission: ${e.message}")
            false
        }
    }

    private fun requestUac2Permission(deviceName: String, result: MethodChannel.Result) {
        try {
            val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager
            if (usbManager == null) {
                result.error("UAC2_ERROR", "USB service unavailable", null)
                return
            }
            
            val device = usbManager.deviceList?.get(deviceName)
            if (device == null) {
                result.error("NOT_FOUND", "USB device not found: $deviceName", null)
                return
            }
            
            if (usbManager.hasPermission(device)) {
                result.success(true)
                return
            }
            
            // Device might be busy if already opened elsewhere
            pendingUac2PermissionResult = result
            val permissionIntent = PendingIntent.getBroadcast(
                this,
                REQUEST_USB_PERMISSION,
                Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            usbPermissionReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == ACTION_USB_PERMISSION) {
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                        unregisterReceiver(usbPermissionReceiver)
                        usbPermissionReceiver = null
                        
                        if (granted && device != null) {
                            // Invalidate cache when permission granted
                            uac2DeviceCache = null
                            pendingUac2PermissionResult?.success(true)
                        } else {
                            pendingUac2PermissionResult?.error(
                                "PERMISSION_DENIED",
                                "Permission denied for device: ${device?.deviceName ?: deviceName}",
                                null
                            )
                        }
                        pendingUac2PermissionResult = null
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION))
            }
            usbManager.requestPermission(device, permissionIntent)
        } catch (e: Exception) {
            android.util.Log.e("UAC2", "Error requesting permission: ${e.message}")
            result.error("UAC2_ERROR", "Failed to request permission: ${e.message}", null)
        }
    }

    private fun registerUsbHotplugReceiver() {
        usbHotplugReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        // Invalidate cache on device attach
                        uac2DeviceCache = null
                        // Notify Flutter if channel is available
                        uac2Channel?.invokeMethod("onDeviceAttached", null)
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        // Invalidate cache on device detach
                        uac2DeviceCache = null
                        // Notify Flutter if channel is available
                        uac2Channel?.invokeMethod("onDeviceDetached", null)
                    }
                }
            }
        }
        
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbHotplugReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbHotplugReceiver, filter)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        usbHotplugReceiver?.let { unregisterReceiver(it) }
        usbPermissionReceiver?.let { unregisterReceiver(it) }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.ultraelectronica.flick.USB_PERMISSION"
    }
}
