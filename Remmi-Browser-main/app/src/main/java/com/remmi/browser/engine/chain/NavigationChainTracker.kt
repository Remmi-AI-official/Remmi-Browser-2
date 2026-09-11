package com.remmi.browser.engine.chain

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

enum class HopType {
  USER_GESTURE,
  HTTP_REDIRECT,
  NEW_WINDOW_BLANK,
  JS_WINDOW_OPEN,
  JS_NAVIGATION,
  SAME_DOCUMENT_SPA,
  LOCATION_CHANGE,
  DOWNLOAD_ATTACHMENT,
  BLOCKED_POPUP,
  INITIAL_LOAD
}

enum class ChainStatus {
  IN_FLIGHT,
  COMPLETED_PAGE,
  COMPLETED_DOWNLOAD,
  BLOCKED_SECURITY,
  LOOP_DETECTED,
  DEPTH_EXCEEDED,
  ABORTED
}

data class NavigationHop(
  val hopIndex: Int,
  val uri: String,
  val triggerUri: String? = null,
  val hopType: HopType,
  val target: Int = 0,
  val isRedirect: Boolean = false,
  val hasUserGesture: Boolean = false,
  val isDirectNavigation: Boolean = false,
  val rejectionReason: String? = null,
  val timestamp: Long = System.currentTimeMillis()
)

data class NavigationChain(
  val chainNavId: Long,
  val tabId: String,
  val generation: Long,
  val sourceUrl: String,
  val currentUrl: String,
  val hops: List<NavigationHop>,
  val status: ChainStatus,
  val terminalDownloadUrl: String? = null,
  val visiblePageUrl: String? = null,
  val parentTabId: String? = null,
  val parentNavId: Long? = null,
  val startTime: Long = System.currentTimeMillis(),
  val updatedTime: Long = System.currentTimeMillis()
)

object NavigationChainTracker {
  private const val TAG = "NavChainTracker"
  const val MAX_HOPS = 32

  // Active chain per tabId
  private val activeChains = ConcurrentHashMap<String, NavigationChain>()

  // Historical chains per tabId (bounded list, e.g. last 15 per tab)
  private val chainHistories = ConcurrentHashMap<String, MutableList<NavigationChain>>()

  // UI state projection: StateFlow of active chain per tab or currently focused tab
  private val _activeChainFlow = MutableStateFlow<NavigationChain?>(null)
  val activeChainFlow: StateFlow<NavigationChain?> = _activeChainFlow.asStateFlow()

  // Track focused tab
  @Volatile
  private var currentActiveTabId: String? = null

  fun setActiveTab(tabId: String?) {
    currentActiveTabId = tabId
    _activeChainFlow.value = tabId?.let { activeChains[it] }
  }

  fun getActiveChain(tabId: String): NavigationChain? = activeChains[tabId]

  fun getChainHistory(tabId: String): List<NavigationChain> {
    return synchronized(chainHistories) {
      chainHistories[tabId]?.toList() ?: emptyList()
    }
  }

  fun clearAll() {
    activeChains.clear()
    chainHistories.clear()
    _activeChainFlow.value = null
  }

  /**
   * Smart URL canonicalization:
   * scheme & host lowercased, default ports stripped, trailing slashes normalized,
   * but query parameters PRESERVED because tokens / destination targets are in query params.
   */
  fun canonicalizeUrl(url: String): String {
    if (url.isBlank()) return ""
    return try {
      val uri = Uri.parse(url)
      val scheme = uri.scheme?.lowercase() ?: "https"
      val host = uri.host?.lowercase() ?: ""
      val port = if (uri.port != -1 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
      val rawPath = uri.path ?: ""
      val path = rawPath.trimEnd('/')
      val query = uri.query?.let { "?$it" } ?: ""
      "$scheme://$host$port$path$query"
    } catch (e: Exception) {
      url.trim().lowercase().trimEnd('/')
    }
  }

  fun startChain(
    tabId: String,
    navId: Long,
    generation: Long,
    sourceUrl: String,
    hopType: HopType = HopType.USER_GESTURE,
    hasUserGesture: Boolean = true,
    triggerUri: String? = null,
    target: Int = 0,
    parentTabId: String? = null,
    parentNavId: Long? = null
  ): NavigationChain {
    // Archive previous active chain if existing
    activeChains[tabId]?.let { prev ->
      archiveChain(tabId, prev)
    }

    val firstHop = NavigationHop(
      hopIndex = 0,
      uri = sourceUrl,
      triggerUri = triggerUri,
      hopType = hopType,
      target = target,
      isRedirect = false,
      hasUserGesture = hasUserGesture,
      isDirectNavigation = true,
      timestamp = System.currentTimeMillis()
    )

    val chain = NavigationChain(
      chainNavId = navId,
      tabId = tabId,
      generation = generation,
      sourceUrl = sourceUrl,
      currentUrl = sourceUrl,
      hops = listOf(firstHop),
      status = ChainStatus.IN_FLIGHT,
      parentTabId = parentTabId,
      parentNavId = parentNavId,
      startTime = System.currentTimeMillis(),
      updatedTime = System.currentTimeMillis()
    )

    activeChains[tabId] = chain
    if (tabId == currentActiveTabId) {
      _activeChainFlow.value = chain
    }
    logChainEvent(chain, "START", "navId=$navId sourceUrl=$sourceUrl hopType=$hopType")
    return chain
  }

  fun recordHop(
    tabId: String,
    uri: String,
    hopType: HopType,
    isRedirect: Boolean = false,
    hasUserGesture: Boolean = false,
    isDirectNavigation: Boolean = false,
    triggerUri: String? = null,
    target: Int = 0,
    rejectionReason: String? = null
  ): NavigationChain? {
    val current = activeChains[tabId] ?: return null

    // Check depth limit
    if (current.hops.size >= MAX_HOPS) {
      val updated = current.copy(
        status = ChainStatus.DEPTH_EXCEEDED,
        updatedTime = System.currentTimeMillis()
      )
      activeChains[tabId] = updated
      if (tabId == currentActiveTabId) _activeChainFlow.value = updated
      logChainEvent(updated, "DEPTH_EXCEEDED", "max hops $MAX_HOPS reached at $uri")
      return updated
    }

    // Check loop detection
    val canonicalTarget = canonicalizeUrl(uri)
    val rawTarget = uri.trim()
    val visitedRaw = current.hops.map { it.uri.trim() }.toSet()
    val visitedCanonical = current.hops.map { canonicalizeUrl(it.uri) }.toSet()

    // A valid HTTP redirect / normalizer (such as trailing slash addition /path -> /path/ or scheme upgrade)
    // from the immediate previous hop should NOT trigger false LOOP_DETECTED
    val isDirectTrailingSlashOrSchemeRedirect = (hopType == HopType.HTTP_REDIRECT || isRedirect) &&
      current.hops.isNotEmpty() &&
      rawTarget != current.hops.last().uri.trim() &&
      canonicalTarget == canonicalizeUrl(current.hops.last().uri)

    val isLoop = if (isDirectTrailingSlashOrSchemeRedirect) {
      false
    } else {
      visitedRaw.contains(rawTarget) || (visitedCanonical.contains(canonicalTarget) && current.hops.size > 1)
    }

    val newHop = NavigationHop(
      hopIndex = current.hops.size,
      uri = uri,
      triggerUri = triggerUri ?: current.currentUrl,
      hopType = hopType,
      target = target,
      isRedirect = isRedirect,
      hasUserGesture = hasUserGesture,
      isDirectNavigation = isDirectNavigation,
      rejectionReason = rejectionReason,
      timestamp = System.currentTimeMillis()
    )

    val newStatus = if (isLoop) ChainStatus.LOOP_DETECTED else current.status
    val updated = current.copy(
      currentUrl = uri,
      hops = current.hops + newHop,
      status = newStatus,
      updatedTime = System.currentTimeMillis()
    )

    activeChains[tabId] = updated
    if (tabId == currentActiveTabId) {
      _activeChainFlow.value = updated
    }
    logChainEvent(updated, "HOP_${hopType.name}", "hopIndex=${newHop.hopIndex} uri=$uri isLoop=$isLoop")
    return updated
  }

  fun markPageCompleted(tabId: String, visibleUrl: String): NavigationChain? {
    val current = activeChains[tabId] ?: return null
    val updated = current.copy(
      currentUrl = visibleUrl,
      visiblePageUrl = visibleUrl,
      status = if (current.status == ChainStatus.IN_FLIGHT) ChainStatus.COMPLETED_PAGE else current.status,
      updatedTime = System.currentTimeMillis()
    )
    activeChains[tabId] = updated
    if (tabId == currentActiveTabId) {
      _activeChainFlow.value = updated
    }
    logChainEvent(updated, "COMPLETED_PAGE", "visibleUrl=$visibleUrl")
    return updated
  }

  fun markDownloadCompleted(tabId: String, downloadUrl: String, visiblePageUrl: String?): NavigationChain? {
    val current = activeChains[tabId] ?: return null
    val downloadHop = NavigationHop(
      hopIndex = current.hops.size,
      uri = downloadUrl,
      triggerUri = current.currentUrl,
      hopType = HopType.DOWNLOAD_ATTACHMENT,
      timestamp = System.currentTimeMillis()
    )
    val updated = current.copy(
      terminalDownloadUrl = downloadUrl,
      visiblePageUrl = visiblePageUrl ?: current.visiblePageUrl ?: current.sourceUrl,
      hops = current.hops + downloadHop,
      status = ChainStatus.COMPLETED_DOWNLOAD,
      updatedTime = System.currentTimeMillis()
    )
    activeChains[tabId] = updated
    if (tabId == currentActiveTabId) {
      _activeChainFlow.value = updated
    }
    val logDetails = com.remmi.browser.util.UrlSanitizer.formatCompletedDownloadLog(downloadUrl, visiblePageUrl)
    logChainEvent(updated, "COMPLETED_DOWNLOAD", logDetails)
    return updated
  }

  fun markSecurityBlocked(tabId: String, blockedUrl: String, reason: String): NavigationChain? {
    val current = activeChains[tabId] ?: return null
    val blockedHop = NavigationHop(
      hopIndex = current.hops.size,
      uri = blockedUrl,
      triggerUri = current.currentUrl,
      hopType = HopType.BLOCKED_POPUP,
      rejectionReason = reason,
      timestamp = System.currentTimeMillis()
    )
    val updated = current.copy(
      hops = current.hops + blockedHop,
      status = ChainStatus.BLOCKED_SECURITY,
      updatedTime = System.currentTimeMillis()
    )
    activeChains[tabId] = updated
    if (tabId == currentActiveTabId) {
      _activeChainFlow.value = updated
    }
    logChainEvent(updated, "BLOCKED_SECURITY", "blockedUrl=$blockedUrl reason=$reason")
    return updated
  }

  fun linkParentChain(childTabId: String, parentTabId: String, parentNavId: Long?) {
    val current = activeChains[childTabId] ?: return
    val updated = current.copy(
      parentTabId = parentTabId,
      parentNavId = parentNavId,
      updatedTime = System.currentTimeMillis()
    )
    activeChains[childTabId] = updated
    if (childTabId == currentActiveTabId) {
      _activeChainFlow.value = updated
    }
    logChainEvent(updated, "PARENT_LINK", "parentTabId=$parentTabId parentNavId=$parentNavId")
  }

  fun clearTab(tabId: String) {
    activeChains.remove(tabId)
    synchronized(chainHistories) {
      chainHistories.remove(tabId)
    }
    if (tabId == currentActiveTabId) {
      _activeChainFlow.value = null
    }
  }

  private fun archiveChain(tabId: String, chain: NavigationChain) {
    synchronized(chainHistories) {
      val list = chainHistories.getOrPut(tabId) { mutableListOf() }
      list.add(chain)
      if (list.size > 15) {
        list.removeAt(0)
      }
    }
  }

  private fun logChainEvent(chain: NavigationChain, event: String, details: String) {
    val msg = "[NAV_CHAIN][$event] tabId=${chain.tabId} navId=${chain.chainNavId} hops=${chain.hops.size} status=${chain.status} $details"
    Log.i(TAG, msg)
    com.remmi.browser.util.DebugLogManager.log(msg)
  }
}
