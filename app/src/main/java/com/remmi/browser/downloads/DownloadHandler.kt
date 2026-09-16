package com.remmi.browser.downloads
import kotlinx.coroutines.cancelAndJoin

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.remmi.browser.R
import com.remmi.browser.storage.DownloadItem
import com.remmi.browser.storage.RemmiDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

sealed class DownloadEvent {
  data class Started(
    val downloadId: Long,
    val fileName: String,
    val url: String,
    val fileSize: Long,
    val mimeType: String,
    val isGhost: Boolean,
    val fileType: DownloadFileType
  ) : DownloadEvent()

  data class Completed(
    val downloadId: Long,
    val fileName: String,
    val fileSize: Long,
    val filePath: String,
    val fileType: DownloadFileType
  ) : DownloadEvent()

  data class Failed(
    val downloadId: Long,
    val fileName: String,
    val error: String
  ) : DownloadEvent()
}

data class DownloadProgressInfo(
  val downloadId: Long,
  val fileName: String,
  val url: String,
  val bytesDownloaded: Long,
  val totalBytes: Long,
  val isGhost: Boolean,
  val status: String, // "DOWNLOADING", "COMPLETED", "FAILED", "CANCELLED"
  val filePath: String? = null,
  val mimeType: String? = null,
  val speedBytesPerSec: Long = 0L
) {
  val progressPercent: Int
    get() = if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100) else 0

  val isIndeterminate: Boolean
    get() = totalBytes <= 0

  val fileType: DownloadFileType
    get() = DownloadFileType.fromMimeOrFilename(mimeType, fileName)
}

class DownloadHandler(private val context: Context) {

  private val downloadManager =
    context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
  private val scope = CoroutineScope(Dispatchers.IO)

  private val _activeDownloads = MutableStateFlow<Map<Long, DownloadProgressInfo>>(emptyMap())
  val activeDownloads = _activeDownloads.asStateFlow()

  private val _downloadEvents = MutableSharedFlow<DownloadEvent>(replay = 0, extraBufferCapacity = 64)
  val downloadEvents = _downloadEvents.asSharedFlow()

  private val activeJobs = ConcurrentHashMap<Long, Job>()
  private val downloadSessions = ConcurrentHashMap<Long, DownloadSession>()

  data class DownloadSession(
    val downloadId: Long,
    val url: String,
    val suggestedFilename: String?,
    val mimeType: String?,
    val contentLength: Long,
    val isGhost: Boolean,
    var bytesDownloaded: Long = 0L,
    var targetUri: Uri? = null,
    var fileName: String = "",
    @Volatile var isPaused: Boolean = false,
    @Volatile var isCancelled: Boolean = false
  )

  init {
    createNotificationChannels()
  }

  private fun createNotificationChannels() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
      
      // 1. Channel for active live download progress (low priority, silent)
      val progressChannel = NotificationChannel(
        CHANNEL_ID_PROGRESS,
        "Active Downloads",
        NotificationManager.IMPORTANCE_LOW
      ).apply {
        description = "Live progress and status of active file downloads"
        setShowBadge(false)
        enableVibration(false)
        enableLights(false)
      }
      
      // 2. Channel for download completion alerts (default priority, alert user)
      val completeChannel = NotificationChannel(
        CHANNEL_ID_COMPLETE,
        "Completed Downloads",
        NotificationManager.IMPORTANCE_DEFAULT
      ).apply {
        description = "Alerts and notifications when downloads complete"
        setShowBadge(true)
      }

      notificationManager?.createNotificationChannel(progressChannel)
      notificationManager?.createNotificationChannel(completeChannel)
    }
  }

  fun enqueueDownload(
    url: String,
    suggestedFilename: String? = null,
    mimeType: String? = null,
    contentLength: Long = 0L,
    isGhost: Boolean = false,
    webResponse: WebResponse? = null
  ) {
    val downloadId = (url.hashCode().toLong() and 0xFFFFFFF) + System.currentTimeMillis() % 100000
    val session = DownloadSession(
      downloadId = downloadId,
      url = url,
      suggestedFilename = suggestedFilename,
      mimeType = mimeType,
      contentLength = contentLength,
      isGhost = isGhost
    )
    downloadSessions[downloadId] = session

    val job = scope.launch {
      performManagedDownload(downloadId, url, suggestedFilename, mimeType, contentLength, isGhost, webResponse)
    }
    activeJobs[downloadId] = job
  }

  suspend fun pauseDownload(downloadId: Long) {
    val session = downloadSessions[downloadId]
    if (session != null) {
      session.isPaused = true
    }
    activeJobs[downloadId]?.cancel()
    activeJobs.remove(downloadId)

    // Update active state to PAUSED
    val current = _activeDownloads.value[downloadId]
    if (current != null) {
      val pausedInfo = current.copy(status = "PAUSED", speedBytesPerSec = 0L)
      _activeDownloads.value = _activeDownloads.value + (downloadId to pausedInfo)
    }

    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    val notifId = (downloadId % Int.MAX_VALUE).toInt()
    val name = session?.fileName ?: current?.fileName ?: "File"
    val bytes = session?.bytesDownloaded ?: current?.bytesDownloaded ?: 0L
    val total = session?.contentLength ?: current?.totalBytes ?: 0L
    val statsText = if (total > 0) "${formatBytes(bytes)} / ${formatBytes(total)}" else formatBytes(bytes)

    val pausedNotif = NotificationCompat.Builder(context, CHANNEL_ID_PROGRESS)
      .setContentTitle("Download Paused: $name")
      .setContentText("Paused • $statsText")
      .setSmallIcon(R.drawable.ic_download)
      .setProgress(100, if (total > 0) ((bytes * 100) / total).toInt() else 0, total <= 0)
      .setOngoing(false)
      .setAutoCancel(false)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .build()
    notificationManager?.notify(notifId, pausedNotif)

    val db = RemmiDatabase.getDatabaseAsync(context)
    db.downloadDao().updateStatus(downloadId, "PAUSED")

    withContext(Dispatchers.Main) {
      Toast.makeText(context, "Download paused", Toast.LENGTH_SHORT).show()
    }
  }

  suspend fun resumeDownload(downloadId: Long) {
    val session = downloadSessions[downloadId]
    val current = _activeDownloads.value[downloadId]

    val url = session?.url ?: current?.url
    if (url.isNullOrBlank()) {
      withContext(Dispatchers.Main) {
        Toast.makeText(context, "Cannot resume: missing source URL", Toast.LENGTH_SHORT).show()
      }
      return
    }

    val resumeSession = session ?: DownloadSession(
      downloadId = downloadId,
      url = url,
      suggestedFilename = current?.fileName,
      mimeType = current?.mimeType,
      contentLength = current?.totalBytes ?: 0L,
      isGhost = current?.isGhost ?: false,
      bytesDownloaded = current?.bytesDownloaded ?: 0L,
      fileName = current?.fileName ?: ""
    ).also { downloadSessions[downloadId] = it }

    resumeSession.isPaused = false
    resumeSession.isCancelled = false

    // Update active state to DOWNLOADING
    if (current != null) {
      _activeDownloads.value = _activeDownloads.value + (downloadId to current.copy(status = "DOWNLOADING"))
    }

    val job = scope.launch {
      performManagedDownload(
        downloadId = downloadId,
        url = resumeSession.url,
        suggestedFilename = resumeSession.suggestedFilename,
        mimeType = resumeSession.mimeType,
        contentLength = resumeSession.contentLength,
        isGhost = resumeSession.isGhost,
        webResponse = null,
        startOffset = resumeSession.bytesDownloaded,
        existingUri = resumeSession.targetUri
      )
    }
    activeJobs[downloadId] = job

    withContext(Dispatchers.Main) {
      Toast.makeText(context, "Resuming download...", Toast.LENGTH_SHORT).show()
    }
  }

  suspend fun cancelDownload(downloadId: Long) {
    val session = downloadSessions[downloadId]
    if (session != null) {
      session.isCancelled = true
    }
    
    activeJobs[downloadId]?.cancel()
    activeJobs.remove(downloadId)
    _activeDownloads.value = _activeDownloads.value - downloadId

    val targetUri = session?.targetUri
    if (targetUri != null) {
      try {
        context.contentResolver.delete(targetUri, null, null)
      } catch (_: Throwable) {}
    }
    downloadSessions.remove(downloadId)

    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    notificationManager?.cancel((downloadId % Int.MAX_VALUE).toInt())

    val db = RemmiDatabase.getDatabaseAsync(context)
    db.downloadDao().updateStatus(downloadId, "CANCELLED")
    
    withContext(Dispatchers.Main) {
      Toast.makeText(context, "Download cancelled", Toast.LENGTH_SHORT).show()
    }
  }

  private suspend fun performManagedDownload(
    downloadId: Long,
    url: String,
    suggestedFilename: String?,
    mimeType: String?,
    contentLength: Long,
    isGhost: Boolean,
    webResponse: WebResponse?,
    startOffset: Long = 0L,
    existingUri: Uri? = null
  ) {
    val session = downloadSessions.getOrPut(downloadId) {
      DownloadSession(
        downloadId = downloadId,
        url = url,
        suggestedFilename = suggestedFilename,
        mimeType = mimeType,
        contentLength = contentLength,
        isGhost = isGhost,
        bytesDownloaded = startOffset,
        targetUri = existingUri
      )
    }
    session.isPaused = false
    session.isCancelled = false

    if (isGhost) {
      if (!com.remmi.browser.security.CurrentTorRoute.isReady || com.remmi.browser.security.CurrentTorRoute.currentGeneration <= 0L) {
        val errorNotif = NotificationCompat.Builder(context, CHANNEL_ID_COMPLETE)
          .setContentTitle("Download failed: Security Alert")
          .setContentText("Ghost route unavailable or unverified. Download aborted to prevent leaks.")
          .setSmallIcon(android.R.drawable.stat_sys_warning)
          .setAutoCancel(true)
          .build()
        val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifManager.notify((downloadId % Int.MAX_VALUE).toInt(), errorNotif)
        _downloadEvents.emit(DownloadEvent.Failed(downloadId, suggestedFilename ?: "file", "Ghost route unavailable"))
        return
      }
    }

    val uriStr = try { Uri.parse(url) } catch (_: Throwable) { Uri.EMPTY }
    val isDataUri = url.startsWith("data:", ignoreCase = true)
    val isBlobUri = url.startsWith("blob:", ignoreCase = true)
    val rawName = suggestedFilename ?: if (isDataUri || isBlobUri) {
      "download_${System.currentTimeMillis()}"
    } else {
      uriStr.lastPathSegment ?: "remmi_download"
    }
    val mime = if (!mimeType.isNullOrBlank() && mimeType != "application/octet-stream") {
      mimeType
    } else if (isDataUri) {
      val dataMime = url.substringAfter("data:").substringBefore(";").substringBefore(",")
      if (dataMime.isNotBlank() && dataMime.contains("/")) dataMime else guessMimeType(rawName, url)
    } else {
      guessMimeType(rawName, url)
    }
    val fileName = if (session.fileName.isNotBlank()) session.fileName else sanitizeFileName(rawName, url, mime)
    session.fileName = fileName

    val fileType = DownloadFileType.fromMimeOrFilename(mime, fileName)
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val notifId = (downloadId % Int.MAX_VALUE).toInt()

    val MAX_DOWNLOAD_SIZE_BYTES = 5L * 1024L * 1024L * 1024L // 5GB Hard cap
    if (contentLength > MAX_DOWNLOAD_SIZE_BYTES) {
      val notif = NotificationCompat.Builder(context, CHANNEL_ID_COMPLETE)
          .setContentTitle("Download rejected: $fileName")
          .setContentText("File exceeds 5GB safety limit.")
          .setSmallIcon(android.R.drawable.stat_sys_warning)
          .setAutoCancel(true).build()
      notificationManager.notify(notifId, notif)
      _downloadEvents.emit(DownloadEvent.Failed(downloadId, fileName, "File exceeds 5GB limit"))
      return
    }

    // Register active download info
    val initialInfo = DownloadProgressInfo(
      downloadId = downloadId,
      fileName = fileName,
      url = url,
      bytesDownloaded = startOffset,
      totalBytes = contentLength,
      isGhost = isGhost,
      status = "DOWNLOADING",
      mimeType = mime
    )
    _activeDownloads.value = _activeDownloads.value + (downloadId to initialInfo)

    if (startOffset == 0L) {
      // Emit live DownloadEvent.Started for in-app UI banner on initial start
      _downloadEvents.emit(
        DownloadEvent.Started(
          downloadId = downloadId,
          fileName = fileName,
          url = url,
          fileSize = contentLength,
          mimeType = mime,
          isGhost = isGhost,
          fileType = fileType
        )
      )
    }

    // Display ongoing system notification
    val initialText = if (isGhost) {
      "Downloading securely via Tor air-gap..."
    } else if (contentLength > 0) {
      val pct = if (contentLength > 0) ((startOffset * 100) / contentLength).toInt() else 0
      "Downloading ($pct% • ${formatBytes(startOffset)} / ${formatBytes(contentLength)})..."
    } else {
      "Downloading ${formatBytes(startOffset)}..."
    }

    val notifBuilder = NotificationCompat.Builder(context, CHANNEL_ID_PROGRESS)
      .setContentTitle("Downloading $fileName")
      .setContentText(initialText)
      .setSmallIcon(R.drawable.ic_download)
      .setProgress(100, if (contentLength > 0) ((startOffset * 100) / contentLength).toInt() else 0, contentLength <= 0)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setCategory(NotificationCompat.CATEGORY_PROGRESS)

    notificationManager.notify(notifId, notifBuilder.build())

    val db = RemmiDatabase.getDatabaseAsync(context)
    var allocatedUri: android.net.Uri? = existingUri
    try {
      db.downloadDao().insert(
        DownloadItem(
          downloadId = downloadId,
          fileName = fileName,
          url = url,
          mimeType = mime,
          fileSize = contentLength,
          status = "DOWNLOADING",
          filePath = allocatedUri?.toString() ?: ""
        )
      )

      val inputStream: InputStream = withContext(Dispatchers.IO) {
        if (webResponse != null && webResponse.body != null && startOffset == 0L) {
          webResponse.body!!
        } else if (url.startsWith("data:", ignoreCase = true)) {
          val commaIndex = url.indexOf(',')
          if (commaIndex != -1) {
            val metadata = url.substring(0, commaIndex)
            val dataPart = url.substring(commaIndex + 1)
            val isBase64 = metadata.contains(";base64", ignoreCase = true)
            val bytes = if (isBase64) {
              android.util.Base64.decode(dataPart, android.util.Base64.DEFAULT)
            } else {
              java.net.URLDecoder.decode(dataPart, "UTF-8").toByteArray(Charsets.UTF_8)
            }
            java.io.ByteArrayInputStream(bytes)
          } else {
            throw Exception("Malformed data: URI")
          }
        } else {
          val runtime = com.remmi.browser.engine.GeckoEngineManager.getInstance(context).runtime
            ?: throw Exception("Gecko runtime unavailable")
          val executor = GeckoWebExecutor(runtime)
          val reqBuilder = WebRequest.Builder(url)
          if (startOffset > 0L) {
            reqBuilder.addHeader("Range", "bytes=$startOffset-")
          }
          val request = reqBuilder.build()
          val result = executor.fetch(request).poll(45000) ?: throw Exception("Download connection timed out")
          result.body ?: throw Exception("Response stream is empty")
        }
      }

      val resolver = context.contentResolver
      val targetUri = if (allocatedUri != null) {
        allocatedUri
      } else {
        val contentValues = ContentValues().apply {
          put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
          put(MediaStore.MediaColumns.MIME_TYPE, mime)
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
          }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
          val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
          Uri.fromFile(File(dir, fileName))
        }
        val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          resolver.insert(collection, contentValues) ?: throw Exception("Failed to allocate MediaStore entry")
        } else {
          collection
        }
        allocatedUri = created
        session.targetUri = created
        created
      }

      var downloadedBytes = startOffset
      val outStreamMode = if (startOffset > 0L) "wa" else "w"
      val outStream: OutputStream = resolver.openOutputStream(targetUri, outStreamMode)
        ?: resolver.openOutputStream(targetUri)
        ?: throw Exception("Failed to open output write stream")

      withContext(Dispatchers.IO) {
        inputStream.use { input ->
          outStream.use { output ->
            val buffer = ByteArray(32 * 1024)
            var bytesRead = input.read(buffer)
            var lastUpdateMs = System.currentTimeMillis()
            var bytesSinceLastUpdate = 0L
            var calculatedSpeed = 0L

            val MAX_DOWNLOAD_SIZE_BYTES = 5L * 1024L * 1024L * 1024L // 5GB Hard cap
            while (bytesRead >= 0) {
              if (session.isCancelled || session.isPaused) {
                break
              }

              if (downloadedBytes + bytesRead > MAX_DOWNLOAD_SIZE_BYTES) {
                throw java.io.IOException("Download exceeded hard cap of 5GB. Terminated to prevent storage exhaustion.")
              }
              output.write(buffer, 0, bytesRead)
              downloadedBytes += bytesRead
              bytesSinceLastUpdate += bytesRead
              session.bytesDownloaded = downloadedBytes

              val now = System.currentTimeMillis()
              val elapsed = now - lastUpdateMs
              if (elapsed >= 350) {
                if (elapsed > 0) {
                  calculatedSpeed = (bytesSinceLastUpdate * 1000L) / elapsed
                }
                lastUpdateMs = now
                bytesSinceLastUpdate = 0L

                val currentProgress = if (contentLength > 0) {
                  ((downloadedBytes * 100) / contentLength).toInt().coerceIn(0, 100)
                } else 0

                val progressInfo = DownloadProgressInfo(
                  downloadId = downloadId,
                  fileName = fileName,
                  url = url,
                  bytesDownloaded = downloadedBytes,
                  totalBytes = contentLength,
                  isGhost = isGhost,
                  status = "DOWNLOADING",
                  mimeType = mime,
                  speedBytesPerSec = calculatedSpeed
                )
                _activeDownloads.value = _activeDownloads.value + (downloadId to progressInfo)

                val speedText = if (calculatedSpeed > 0) " • ${formatBytes(calculatedSpeed)}/s" else ""
                val progressText = if (contentLength > 0) {
                  "$currentProgress% • ${formatBytes(downloadedBytes)} / ${formatBytes(contentLength)}$speedText"
                } else {
                  "${formatBytes(downloadedBytes)} downloaded$speedText"
                }

                notifBuilder
                  .setContentText(progressText)
                  .setProgress(100, currentProgress, contentLength <= 0)

                notificationManager.notify(notifId, notifBuilder.build())
              }

              bytesRead = input.read(buffer)
            }
          }
        }
      }

      if (session.isCancelled) {
        try {
          context.contentResolver.delete(targetUri, null, null)
        } catch (_: Throwable) {}
        _activeDownloads.value = _activeDownloads.value - downloadId
        downloadSessions.remove(downloadId)
        notificationManager.cancel(notifId)
        db.downloadDao().updateStatus(downloadId, "CANCELLED")
        return
      }

      if (session.isPaused) {
        db.downloadDao().updateStatus(downloadId, "PAUSED")
        return
      }

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val finalizeValues = ContentValues().apply {
          put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        resolver.update(targetUri, finalizeValues, null, null)
      }

      // Build completion Open Intent
      val viewUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        targetUri
      } else {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        FileProvider.getUriForFile(
          context,
          "${context.packageName}.fileprovider",
          File(dir, fileName)
        )
      }

      val openIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(viewUri, mime)
        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
      }
      val pendingIntent = PendingIntent.getActivity(
        context,
        notifId,
        openIntent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
      )

      // Cancel ongoing progress notification
      notificationManager.cancel(notifId)

      // Post complete notification to Completed channel
      val completionNotif = NotificationCompat.Builder(context, CHANNEL_ID_COMPLETE)
        .setContentTitle("Download Complete")
        .setContentText("$fileName (${formatBytes(downloadedBytes)}) • Tap to open")
        .setSmallIcon(R.drawable.ic_check)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setProgress(0, 0, false)
        .setOngoing(false)
        .setAutoCancel(true)
        .setContentIntent(pendingIntent)
        .build()

      notificationManager.notify(notifId, completionNotif)

      _activeDownloads.value = _activeDownloads.value - downloadId
      activeJobs.remove(downloadId)
      downloadSessions.remove(downloadId)

      db.downloadDao().insert(
        DownloadItem(
          downloadId = downloadId,
          fileName = fileName,
          url = url,
          mimeType = mime,
          fileSize = downloadedBytes,
          status = "COMPLETED",
          filePath = targetUri.toString(),
          timestamp = System.currentTimeMillis()
        )
      )

      _downloadEvents.emit(
        DownloadEvent.Completed(
          downloadId = downloadId,
          fileName = fileName,
          fileSize = downloadedBytes,
          filePath = targetUri.toString(),
          fileType = fileType
        )
      )

    } catch (e: Exception) {
      if (session.isCancelled) {
        try {
          allocatedUri?.let { uri -> context.contentResolver.delete(uri, null, null) }
        } catch (_: Throwable) {}
        _activeDownloads.value = _activeDownloads.value - downloadId
        downloadSessions.remove(downloadId)
        notificationManager.cancel(notifId)
        db.downloadDao().updateStatus(downloadId, "CANCELLED")
        return
      }

      if (session.isPaused) {
        db.downloadDao().updateStatus(downloadId, "PAUSED")
        return
      }

      Log.e(TAG, "Download failed for $fileName", e)
      
      // Cleanup partial/failed download from MediaStore
      try {
        allocatedUri?.let { uri ->
          context.contentResolver.delete(uri, null, null)
        }
      } catch (cleanupEx: Exception) {
        Log.e(TAG, "Failed to clean up partial download", cleanupEx)
      }
      
      activeJobs.remove(downloadId)
      downloadSessions.remove(downloadId)
      notificationManager.cancel(notifId)

      val errorNotif = NotificationCompat.Builder(context, CHANNEL_ID_COMPLETE)
        .setContentTitle("Download Failed: $fileName")
        .setContentText(e.message ?: "Transfer error occurred")
        .setSmallIcon(android.R.drawable.stat_sys_warning)
        .setProgress(0, 0, false)
        .setOngoing(false)
        .setAutoCancel(true)
        .build()

      notificationManager.notify(notifId, errorNotif)

      _activeDownloads.value = _activeDownloads.value - downloadId

      db.downloadDao().insert(
        DownloadItem(
          downloadId = downloadId,
          fileName = fileName,
          url = url,
          mimeType = mime,
          fileSize = 0L,
          status = "FAILED",
          filePath = "",
          timestamp = System.currentTimeMillis()
        )
      )

      _downloadEvents.emit(
        DownloadEvent.Failed(
          downloadId = downloadId,
          fileName = fileName,
          error = e.message ?: "Download failed"
        )
      )
    }
  }

  private fun sanitizeFileName(name: String, url: String? = null, mimeType: String? = null): String {
    var clean = name.replace(Regex("[^a-zA-Z0-9._-]"), "_").trim('_')
    if (clean.isEmpty()) clean = "download_${System.currentTimeMillis()}"

    val dotIndex = clean.lastIndexOf('.')
    val hasExtension = dotIndex > 0 && (clean.length - dotIndex - 1) in 1..6

    if (!hasExtension) {
      val extFromUrl = url?.substringBefore('?')?.substringBefore('#')?.substringAfterLast('.', "")?.lowercase()
      val validUrlExt = if (!extFromUrl.isNullOrBlank() && extFromUrl.length in 2..5 && extFromUrl.all { it.isLetterOrDigit() } && extFromUrl != "bin") {
        extFromUrl
      } else {
        null
      }

      val extFromMime = mimeType?.let {
        when (it.lowercase()) {
          "text/html" -> "html"
          "text/plain" -> "txt"
          "application/pdf" -> "pdf"
          "image/png" -> "png"
          "image/jpeg", "image/jpg" -> "jpg"
          "image/webp" -> "webp"
          "image/gif" -> "gif"
          "image/svg+xml" -> "svg"
          "video/mp4" -> "mp4"
          "video/webm" -> "webm"
          "audio/mpeg" -> "mp3"
          "audio/ogg" -> "ogg"
          "application/zip" -> "zip"
          else -> android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it)
        }
      }

      val chosenExt = when {
        !validUrlExt.isNullOrBlank() -> validUrlExt
        !extFromMime.isNullOrBlank() && extFromMime != "bin" -> extFromMime
        url?.startsWith("data:image/", ignoreCase = true) == true -> "png"
        url?.startsWith("http", ignoreCase = true) == true -> "html"
        else -> "bin"
      }
      clean = "$clean.$chosenExt"
    }
    return clean
  }

  fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
      gb >= 1.0 -> String.format(java.util.Locale.US, "%.1f GB", gb)
      mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB", mb)
      kb >= 1.0 -> String.format(java.util.Locale.US, "%.1f KB", kb)
      else -> "$bytes B"
    }
  }

  private fun guessMimeType(fileName: String, url: String? = null): String {
    val clean = fileName.substringBefore('?').substringBefore('#')
    val ext = clean.substringAfterLast('.', "").lowercase()
    if (ext.isNotBlank()) {
      val mapped = when (ext) {
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js" -> "application/javascript"
        "json" -> "application/json"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "tar", "gz", "tgz" -> "application/gzip"
        "apk" -> "application/vnd.android.package-archive"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        "txt" -> "text/plain"
        "md" -> "text/markdown"
        "doc", "docx" -> "application/msword"
        else -> android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
      }
      if (mapped != null) return mapped
    }

    if (url != null) {
      val urlClean = url.substringBefore('?').substringBefore('#')
      val urlExt = urlClean.substringAfterLast('.', "").lowercase()
      val fromUrl = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(urlExt)
      if (fromUrl != null) return fromUrl
    }

    return "text/html"
  }

  suspend fun cancelAllDownloads() {
    val ids = activeJobs.keys.toList() + _activeDownloads.value.keys.toList()
    ids.distinct().forEach { id ->
      cancelDownload(id)
    }
  }

  companion object {
    private const val TAG = "DownloadHandler"
    private const val CHANNEL_ID_PROGRESS = "remmi_downloads_progress"
    private const val CHANNEL_ID_COMPLETE = "remmi_downloads_complete"

    @Volatile
    private var INSTANCE: DownloadHandler? = null

    fun getInstance(context: Context): DownloadHandler {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: DownloadHandler(context.applicationContext).also { INSTANCE = it }
      }
    }
  }
}

