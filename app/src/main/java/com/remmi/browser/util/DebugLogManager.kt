package com.remmi.browser.util

import android.content.Context
import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unified Thread-Safe Diagnostic Log Store & Persistent Breadcrumbs for Remmi Browser.
 * Non-blocking in-memory ring buffer with coalesced, debounced background I/O writer.
 * Strictly binds every event to sessionId, processPid, timestamp, and thread.
 */
object DebugLogManager {
  private const val TAG = "RemmiDebugLog"
  private const val MAX_LOGS = 300
  private const val MAX_BREADCRUMBS = 200
  private const val BREADCRUMBS_FILE = "remmi_breadcrumbs.log"
  private const val DEBOUNCE_DELAY_MS = 1000L

  private val _logs = MutableStateFlow<List<String>>(emptyList())
  val logs: StateFlow<List<String>> = _logs.asStateFlow()

  @Volatile
  private var appContext: Context? = null
  private val persistentBreadcrumbs = ConcurrentLinkedDeque<String>()
  private val previousSessionBreadcrumbs = ConcurrentLinkedDeque<String>()
  private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
  private val isDirty = AtomicBoolean(false)

  private val backgroundWriter = Executors.newSingleThreadScheduledExecutor { r ->
    Thread(r, "DebugLogWriter").apply {
      isDaemon = true
      priority = Thread.MIN_PRIORITY
    }
  }

  @Volatile
  private var pendingFlushTask: ScheduledFuture<*>? = null

  fun init(context: Context) {
    appContext = context.applicationContext
    backgroundWriter.execute {
      loadPersistedBreadcrumbs()
    }
  }

  fun logDebug(message: String) {
    if (com.remmi.browser.BuildConfig.DEBUG) {
      log(message)
    }
  }

  inline fun logDebug(messageSupplier: () -> String) {
    if (com.remmi.browser.BuildConfig.DEBUG) {
      log(messageSupplier())
    }
  }

  fun log(message: String) {
    // In release builds, suppress high-frequency verbose view updates and intermediate progress to minimize main-thread overhead
    if (!com.remmi.browser.BuildConfig.DEBUG) {
      if (message.startsWith("[FORENSIC][VIEW_UPDATE]") || 
          message.startsWith("[FORENSIC][DECISION_DIAG]") ||
          (message.startsWith("[FORENSIC] [NAV_PROGRESS]") && !message.contains("state=start") && !message.contains("progress=100") && !message.contains("progress=0"))) {
        return
      }
    }

    val timestamp = synchronized(timeFormat) { timeFormat.format(Date()) }
    val sanitized = sanitize(message)
    val thread = Thread.currentThread()
    val sess = CrashHandlerHelper.currentSessionId
    val pid = Process.myPid()
    val entry = "[$timestamp][session=$sess][pid=$pid][thread=${thread.name}(${thread.id})] $sanitized"

    if (com.remmi.browser.BuildConfig.DEBUG) {
      Log.d(TAG, sanitized)
    }

    // 1. Update in-memory StateFlow for UI (newest first)
    synchronized(this) {
      val current = _logs.value.toMutableList()
      current.add(0, entry)
      if (current.size > MAX_LOGS) {
        current.removeAt(current.size - 1)
      }
      _logs.value = current
    }

    // 2. Update thread-safe in-memory ring buffer (chronological order)
    persistentBreadcrumbs.addLast(entry)
    while (persistentBreadcrumbs.size > MAX_BREADCRUMBS) {
      persistentBreadcrumbs.pollFirst()
    }

    // 3. Debounced, coalescing background write (Zero blocking on UI or worker threads)
    scheduleDebouncedFlush()
  }

  private fun scheduleDebouncedFlush() {
    isDirty.set(true)
    synchronized(isDirty) {
      if (pendingFlushTask == null || pendingFlushTask?.isDone == true) {
        pendingFlushTask = backgroundWriter.schedule({
          if (isDirty.compareAndSet(true, false)) {
            flushInternal()
          }
        }, DEBOUNCE_DELAY_MS, TimeUnit.MILLISECONDS)
      }
    }
  }

  fun sanitize(message: String): String {
    // Fast path: if the string does not contain URLs, headers, or credential markers, skip all regex work
    val hasUrl = message.contains("://") || message.contains(".onion")
    val hasCredentialOrHeader = message.contains(":") || message.contains("=")

    if (!hasUrl && !hasCredentialOrHeader) {
      return message
    }

    var sanitized = message

    // 1. Redact downloadUrl=... in any log message to strictly hide signed URLs
    if (sanitized.contains("downloadUrl=")) {
      sanitized = sanitized.replace(Regex("""downloadUrl=(https?://[^\s]+)""")) { mr ->
        val rawUrl = mr.groupValues[1]
        val host = UrlSanitizer.extractHost(rawUrl)
        val hash = UrlSanitizer.sha256(rawUrl)
        "downloadUrl=<REDACTED> downloadHost=$host urlHash=$hash"
      }
    }

    // 2. Redact signed storage URLs pointing to cloud buckets
    if (sanitized.contains(".com/") || sanitized.contains(".net/")) {
      sanitized = sanitized.replace(Regex("""https?://([a-zA-Z0-9.\-]+(?:\.googleusercontent\.com|\.amazonaws\.com|\.storage\.googleapis\.com|\.core\.windows\.net))/[^\s]*""")) { mr ->
        val host = mr.groupValues[1]
        val hash = UrlSanitizer.sha256(mr.value)
        "https://$host/[REDACTED_STORAGE_URL]?urlHash=$hash"
      }
    }

    // 3. Redact query parameters in standard URLs
    if (sanitized.contains("?")) {
      sanitized = sanitized.replace(Regex("""(https?://[^\s?#]+)\?[^\s]*""")) { mr ->
        "${mr.groupValues[1]}?[REDACTED_QUERY]"
      }
    }

    // 4. Redact private file:// and content:// URLs
    if (sanitized.contains("file://") || sanitized.contains("content://")) {
      sanitized = sanitized.replace(Regex("""file:///(?:data/user/\d+|data/data)/[^\s/]+/([^\s]+)""")) { mr ->
        "file://[REDACTED_PRIVATE_APP_DIR]/${mr.groupValues[1]}"
      }
      sanitized = sanitized.replace(Regex("""content://[^\s]+""")) {
        "content://[REDACTED_CONTENT_URI]"
      }
    }

    // 5. Redact authorization headers, bearer tokens, passwords, cookies, secrets, signatures
    val lower = sanitized.lowercase(Locale.ROOT)
    if (lower.contains("authorization") || lower.contains("bearer") || lower.contains("token") ||
        lower.contains("password") || lower.contains("passwd") || lower.contains("secret") ||
        lower.contains("cookie") || lower.contains("key") || lower.contains("pin") ||
        lower.contains("signature") || lower.contains("sig") || lower.contains("auth")) {
      // 5. Redact standalone Bearer tokens
      if (lower.contains("bearer")) {
        sanitized = sanitized.replace(Regex("""(?i)\bBearer\s+[A-Za-z0-9\-._~+/=]+"""), "Bearer [REDACTED]")
      }

      // 6. Redact Basic auth blobs
      if (lower.contains("basic")) {
        sanitized = sanitized.replace(Regex("""(?i)Basic\s+[A-Za-z0-9+/=]+"""), "Basic [REDACTED]")
      }

      // 7. Redact authorization headers, bearer tokens, passwords, cookies, secrets, signatures
      sanitized = sanitized.replace(
        Regex("""(?i)\b(authorization|bearer|token|password|passwd|secret|cookie|set-cookie|key|pin|passphrase|signature|sig|access_token|refresh_token|id_token|session_token|auth_token)\s*[:=]\s*([^\s,;]+)""")
      ) { mr ->
        "${mr.groupValues[1]}: [REDACTED]"
      }
    }

    // 8. Redact Onion addresses query params or paths
    if (sanitized.contains(".onion")) {
      sanitized = sanitized.replace(Regex("""([a-z2-7]{56}\.onion)/[^\s?#]*\?[^\s]*""")) { mr ->
        "${mr.groupValues[1]}/[REDACTED_PATH]?[REDACTED_QUERY]"
      }
    }

    return sanitized
  }

  /**
   * Explicit synchronous flush strictly for fatal crash handler hand-off or test teardown.
   */
  fun flushSynchronously() {
    HangWatchdog.recordUiThreadBlockingOpIfNeeded("diagnostic flush") {
      synchronized(persistentBreadcrumbs) {
        val ctx = appContext ?: return@recordUiThreadBlockingOpIfNeeded
        try {
          val file = File(ctx.filesDir, BREADCRUMBS_FILE)
          val tempFile = File(ctx.filesDir, "$BREADCRUMBS_FILE.tmp")
          val content = persistentBreadcrumbs.joinToString("\n")
          tempFile.writeText(content)
          if (tempFile.exists()) {
            tempFile.renameTo(file)
          }
          isDirty.set(false)
        } catch (_: Throwable) {}
      }
    }
  }

  private fun flushInternal() {
    synchronized(persistentBreadcrumbs) {
      val ctx = appContext ?: return
      try {
        val file = File(ctx.filesDir, BREADCRUMBS_FILE)
        val tempFile = File(ctx.filesDir, "$BREADCRUMBS_FILE.tmp")
        val content = persistentBreadcrumbs.joinToString("\n")
        tempFile.writeText(content)
        if (tempFile.exists()) {
          tempFile.renameTo(file)
        }
      } catch (_: Throwable) {}
    }
  }

  private fun loadPersistedBreadcrumbs() {
    val ctx = appContext ?: return
    try {
      val file = File(ctx.filesDir, BREADCRUMBS_FILE)
      if (file.exists()) {
        val lines = file.readLines().takeLast(MAX_BREADCRUMBS)
        previousSessionBreadcrumbs.clear()
        previousSessionBreadcrumbs.addAll(lines)
      }
    } catch (_: Throwable) {}
  }

  fun getCurrentSessionEvents(limit: Int = MAX_BREADCRUMBS): List<String> {
    return persistentBreadcrumbs.toList().takeLast(limit)
  }

  fun getPreviousSessionEvents(limit: Int = MAX_BREADCRUMBS): List<String> {
    return previousSessionBreadcrumbs.toList().takeLast(limit)
  }

  fun getRecentEvents(limit: Int = MAX_BREADCRUMBS): List<String> {
    val list = persistentBreadcrumbs.toList()
    return if (list.isNotEmpty()) {
      list.takeLast(limit)
    } else {
      previousSessionBreadcrumbs.toList().takeLast(limit)
    }
  }

  fun getLastCurrentSessionEvent(): String {
    val current = persistentBreadcrumbs.peekLast()
    return current ?: "NONE"
  }

  fun getLastPreviousSessionEvent(): String {
    val prev = previousSessionBreadcrumbs.peekLast()
    return prev ?: "NONE"
  }

  fun clear() {
    synchronized(this) {
      _logs.value = emptyList()
      persistentBreadcrumbs.clear()
      previousSessionBreadcrumbs.clear()
      isDirty.set(false)
      backgroundWriter.execute {
        val ctx = appContext
        if (ctx != null) {
          try {
            File(ctx.filesDir, BREADCRUMBS_FILE).delete()
          } catch (_: Throwable) {}
        }
      }
    }
  }
}
