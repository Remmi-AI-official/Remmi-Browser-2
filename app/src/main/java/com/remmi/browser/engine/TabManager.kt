package com.remmi.browser.engine

import android.util.Log
import com.remmi.browser.reader.ReaderArticle
import com.remmi.browser.security.ContainerType
import com.remmi.browser.security.PrivacyProfile
import com.remmi.browser.security.NavigationDecision
import com.remmi.browser.security.NavigationSecurityAuthority
import com.remmi.browser.security.SecurityLevel
import com.remmi.browser.util.DebugLogManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import com.remmi.browser.storage.SessionTabEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class TabTrackerStats(
  val blockedTrackersCount: Int = 0,
  val adsBlockedCount: Int = 0,
  val analyticsBlockedCount: Int = 0,
  val socialBlockedCount: Int = 0,
  val cryptomineBlockedCount: Int = 0,
  val fingerprintBlockedCount: Int = 0,
  val blockedLog: List<String> = emptyList(),
  val isDirty: Boolean = false,
  val lastUpdated: Long = System.currentTimeMillis()
)

class TabManager {

  private val _tabs = MutableStateFlow<List<BrowserTab>>(
    listOf(
      BrowserTab(
        id = UUID.randomUUID().toString(),
        url = "about:blank",
        title = "New Tab",
        profile = PrivacyProfile.SHIELD,
        lastAccessedAt = System.currentTimeMillis(),
      )
    )
  )
  val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()

  private val trackerStatsMap = ConcurrentHashMap<String, TabTrackerStats>()
  private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
  @Volatile
  var isAutoFlushEnabled: Boolean = true

  init {
    startTrackerFlushLoop()
  }

  private fun startTrackerFlushLoop() {
    scope.launch {
      while (isActive) {
        delay(1500L)
        if (isAutoFlushEnabled) {
          try {
            flushPendingTrackerStats()
          } catch (_: Exception) {}
        }
      }
    }
  }

  private fun applyTrackerStats(tab: BrowserTab): BrowserTab {
    val stats = trackerStatsMap[tab.id] ?: return tab
    return tab.copy(
      blockedTrackersCount = stats.blockedTrackersCount,
      adsBlockedCount = stats.adsBlockedCount,
      analyticsBlockedCount = stats.analyticsBlockedCount,
      socialBlockedCount = stats.socialBlockedCount,
      cryptomineBlockedCount = stats.cryptomineBlockedCount,
      fingerprintBlockedCount = stats.fingerprintBlockedCount,
      blockedLog = stats.blockedLog
    )
  }

  // Forensic Churn Rate Trackers
  val trackerEventCounter = java.util.concurrent.atomic.AtomicInteger(0)
  val tabUpdateCounter = java.util.concurrent.atomic.AtomicInteger(0)
  val stateEmissionCounter = java.util.concurrent.atomic.AtomicInteger(0)
  val recompositionCounter = java.util.concurrent.atomic.AtomicInteger(0)
  private val lastRateLogTimestamp = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())

  fun recordRecomposition(tabId: String? = null) {
    recompositionCounter.incrementAndGet()
    checkAndEmitTabStateRate(tabId)
  }

  fun checkAndEmitTabStateRate(tabId: String? = null) {
    if (!isAutoFlushEnabled) return
    val now = System.currentTimeMillis()
    val last = lastRateLogTimestamp.get()
    if (now - last >= 1000L && lastRateLogTimestamp.compareAndSet(last, now)) {
      val trackers = trackerEventCounter.getAndSet(0)
      val updates = tabUpdateCounter.getAndSet(0)
      val emissions = stateEmissionCounter.getAndSet(0)
      val recomps = recompositionCounter.getAndSet(0)
      val targetTab = tabId ?: activeTab?.id ?: "unknown"
      val msg = "[FORENSIC][TAB_STATE_RATE] tabId=$targetTab trackerEvents=$trackers tabUpdates=$updates stateEmissions=$emissions recompositions=$recomps"
      Log.i("TabManager", msg)
      DebugLogManager.log(msg)
    }
  }

  private val _tabGroups = MutableStateFlow<List<TabGroup>>(emptyList())
  val tabGroups: StateFlow<List<TabGroup>> = _tabGroups.asStateFlow()

  private val _activeTabIndex = MutableStateFlow(0)
  val activeTabIndex: StateFlow<Int> = _activeTabIndex.asStateFlow()

  val activeTab: BrowserTab?
    get() {
      val currentTabs = _tabs.value
      val index = _activeTabIndex.value
      val tab = if (index in currentTabs.indices) currentTabs[index] else null
      return tab?.let { applyTrackerStats(it) }
    }

  fun getTab(tabId: String): BrowserTab? {
    val tab = _tabs.value.find { it.id == tabId }
    return tab?.let { applyTrackerStats(it) }
  }

  fun createTab(
    url: String = "about:blank",
    profile: PrivacyProfile = PrivacyProfile.SHIELD,
    isDesktop: Boolean = false,
    containerType: ContainerType = ContainerType.fromProfile(profile),
    securityLevel: SecurityLevel = SecurityLevel.STANDARD,
    groupId: String? = null,
    parentTabId: String? = null,
    openedFromLink: Boolean = false,
  ): BrowserTab {
    val hasTargetUrl = url.isNotBlank() && url != "about:blank" && url != "about:home"
    val newTab = BrowserTab(
      id = UUID.randomUUID().toString(),
      url = url,
      title = if (hasTargetUrl) "Loading..." else "New Tab",
      profile = profile,
      containerType = containerType,
      securityLevel = securityLevel,
      isLoading = hasTargetUrl,
      progress = if (hasTargetUrl) 15 else 0,
      isDesktopMode = isDesktop,
      groupId = groupId,
      lastAccessedAt = System.currentTimeMillis(),
      isInactive = false,
      parentTabId = parentTabId,
      openedFromLink = openedFromLink,
    )
    _tabs.value = _tabs.value + newTab
    _activeTabIndex.value = _tabs.value.lastIndex
    return newTab
  }

  fun openTab(
    url: String = "about:blank",
    profile: PrivacyProfile = PrivacyProfile.SHIELD,
    isDesktop: Boolean = false,
    containerType: ContainerType = ContainerType.fromProfile(profile),
    securityLevel: SecurityLevel = SecurityLevel.STANDARD,
    groupId: String? = null,
    parentTabId: String? = null,
    openedFromLink: Boolean = false,
  ) {
    val hasTargetUrl = url.isNotBlank() && url != "about:blank" && url != "about:home"
    val newTab = BrowserTab(
      id = UUID.randomUUID().toString(),
      url = url,
      title = if (hasTargetUrl) "Loading..." else "New Tab",
      profile = profile,
      containerType = containerType,
      securityLevel = securityLevel,
      isLoading = hasTargetUrl,
      progress = if (hasTargetUrl) 15 else 0,
      isDesktopMode = isDesktop,
      groupId = groupId,
      lastAccessedAt = System.currentTimeMillis(),
      isInactive = false,
      parentTabId = parentTabId,
      openedFromLink = openedFromLink,
    )
    _tabs.value = _tabs.value + newTab
    _activeTabIndex.value = _tabs.value.lastIndex
  }

  fun openTabInBackground(
    url: String,
    profile: PrivacyProfile = PrivacyProfile.SHIELD,
    isDesktop: Boolean = false,
    containerType: ContainerType = ContainerType.fromProfile(profile),
    securityLevel: SecurityLevel = SecurityLevel.STANDARD,
    groupId: String? = null,
    parentTabId: String? = null,
    openedFromLink: Boolean = false,
  ) {
    val hasTargetUrl = url.isNotBlank() && url != "about:blank" && url != "about:home"
    val newTab = BrowserTab(
      id = UUID.randomUUID().toString(),
      url = url,
      title = if (hasTargetUrl) "Loading..." else "New Tab",
      profile = profile,
      containerType = containerType,
      securityLevel = securityLevel,
      isLoading = hasTargetUrl,
      progress = if (hasTargetUrl) 15 else 0,
      isDesktopMode = isDesktop,
      groupId = groupId,
      lastAccessedAt = System.currentTimeMillis(),
      isInactive = false,
      parentTabId = parentTabId,
      openedFromLink = openedFromLink,
    )
    _tabs.value = _tabs.value + newTab
  }

  fun openOrNavigateTab(
    url: String,
    profile: PrivacyProfile = PrivacyProfile.SHIELD,
    isDesktop: Boolean = false,
    containerType: ContainerType = ContainerType.fromProfile(profile),
    securityLevel: SecurityLevel = SecurityLevel.STANDARD,
  ) {
    val isGhost = profile == PrivacyProfile.GHOST
    val navigationCheck = NavigationSecurityAuthority.validateAndSanitizeNavigation(url, isGhost)
    if (navigationCheck.decision == NavigationDecision.BLOCK) {
      Log.w("TabManager", "Blocked openOrNavigateTab request: reason=${navigationCheck.reason}")
      return
    }
    val safeUrl = navigationCheck.sanitizedUrl ?: url

    val currentTab = activeTab
    if (currentTab != null && (currentTab.url == "about:blank" || currentTab.url.isEmpty())) {
      updateTab(currentTab.id) {
        it.copy(
          url = safeUrl,
          title = "Loading...",
          profile = profile,
          isDesktopMode = isDesktop,
          containerType = containerType,
          securityLevel = securityLevel,
          lastAccessedAt = System.currentTimeMillis(),
          isInactive = false,
        )
      }
    } else {
      openTab(
        url = safeUrl,
        profile = profile,
        isDesktop = isDesktop,
        containerType = containerType,
        securityLevel = securityLevel
      )
    }
  }

  fun setTabSecurityLevel(tabId: String, level: SecurityLevel) {
    updateTab(tabId) { it.copy(securityLevel = level) }
  }

  fun setTabContainerType(tabId: String, containerType: ContainerType) {
    updateTab(tabId) { it.copy(containerType = containerType) }
  }

  fun updateTab(tabId: String, update: (BrowserTab) -> BrowserTab) {
    tabUpdateCounter.incrementAndGet()
    var changed = false
    val currentTabs = _tabs.value
    val updatedTabs = currentTabs.map { tab ->
      if (tab.id == tabId) {
        val withStats = applyTrackerStats(tab)
        val updated = update(withStats)
        if (updated != tab) {
          changed = true
          trackerStatsMap.computeIfPresent(tabId) { _, s -> s.copy(isDirty = false) }
          if (updated.url != tab.url) {
            val caller = if (com.remmi.browser.BuildConfig.DEBUG) {
              try {
                Thread.currentThread().stackTrace.drop(2).take(4)
                  .joinToString(" -> ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
              } catch (_: Exception) { "unknown" }
            } else {
              "updateTab"
            }
            val msg = "[FORENSIC][TAB_URL_WRITE] tabId=$tabId oldUrl=${tab.url} newUrl=${updated.url} caller=$caller"
            if (com.remmi.browser.BuildConfig.DEBUG) {
              Log.d(TAG, msg)
            }
            DebugLogManager.log(msg)
          }
        }
        updated
      } else {
        tab
      }
    }

    // StateFlow emissions are expensive here because every emission can recompose the whole
    // BrowserScreen. Do not emit when a callback produced an identical BrowserTab.
    if (changed && updatedTabs != currentTabs) {
      _tabs.value = updatedTabs
      stateEmissionCounter.incrementAndGet()
    }
    checkAndEmitTabStateRate(tabId)
  }

  fun flushPendingTrackerStats(targetTabId: String? = null) {
    val hasDirty = if (targetTabId != null) {
      trackerStatsMap[targetTabId]?.isDirty == true
    } else {
      trackerStatsMap.values.any { it.isDirty }
    }
    if (!hasDirty) return

    val currentTabs = _tabs.value
    var changed = false
    val updatedTabs = currentTabs.map { tab ->
      val stats = trackerStatsMap[tab.id]
      if (stats != null && stats.isDirty && (targetTabId == null || tab.id == targetTabId)) {
        changed = true
        trackerStatsMap.computeIfPresent(tab.id) { _, s -> s.copy(isDirty = false) }
        tab.copy(
          blockedTrackersCount = stats.blockedTrackersCount,
          adsBlockedCount = stats.adsBlockedCount,
          analyticsBlockedCount = stats.analyticsBlockedCount,
          socialBlockedCount = stats.socialBlockedCount,
          cryptomineBlockedCount = stats.cryptomineBlockedCount,
          fingerprintBlockedCount = stats.fingerprintBlockedCount,
          blockedLog = stats.blockedLog
        )
      } else {
        tab
      }
    }

    if (changed && updatedTabs != currentTabs) {
      _tabs.value = updatedTabs
      stateEmissionCounter.incrementAndGet()
      checkAndEmitTabStateRate(targetTabId)
    }
  }

  fun incrementInTabNavigation(tabId: String) {
    updateTab(tabId) { it.copy(inTabNavigationCount = it.inTabNavigationCount + 1) }
  }

  fun switchTab(index: Int) {
    if (index in _tabs.value.indices) {
      val beforeTabId = activeTab?.id ?: "none"
      _activeTabIndex.value = index
      val tab = _tabs.value[index]
      val afterTabId = tab.id
      val msg = "[FORENSIC][TAB_SWITCH] selectedTabIdBefore=$beforeTabId selectedTabIdAfter=$afterTabId activeIndexBefore=$_activeTabIndex activeIndexAfter=$index"
      Log.i(TAG, msg)
      DebugLogManager.log(msg)
      updateTab(tab.id) {
        it.copy(lastAccessedAt = System.currentTimeMillis(), isInactive = false)
      }
    }
  }

  fun switchTab(tabId: String) {
    val index = _tabs.value.indexOfFirst { it.id == tabId }
    if (index >= 0) {
      switchTab(index)
    }
  }

  fun switchToTab(tabId: String) {
    switchTab(tabId)
  }

  fun closeTab(tabId: String, switchToParent: Boolean = true) {
    val currentTabs = _tabs.value
    val tabToCloseIndex = currentTabs.indexOfFirst { it.id == tabId }
    if (tabToCloseIndex < 0) return

    val tabToClose = currentTabs[tabToCloseIndex]
    val parentId = tabToClose.parentTabId
    val beforeTabId = activeTab?.id ?: "none"
    val sizeBefore = currentTabs.size
    val newTabs = currentTabs.filter { it.id != tabId }
    if (newTabs.isEmpty()) {
      _tabs.value = listOf(
        BrowserTab(
          id = UUID.randomUUID().toString(),
          url = "about:blank",
          title = "New Tab",
          profile = PrivacyProfile.SHIELD,
          lastAccessedAt = System.currentTimeMillis(),
        )
      )
      _activeTabIndex.value = 0
    } else {
      _tabs.value = newTabs
      val parentIndex = if (switchToParent && parentId != null) newTabs.indexOfFirst { it.id == parentId } else -1
      if (parentIndex >= 0) {
        _activeTabIndex.value = parentIndex
      } else if (_activeTabIndex.value >= newTabs.size) {
        _activeTabIndex.value = newTabs.lastIndex
      } else if (tabToCloseIndex < _activeTabIndex.value) {
        _activeTabIndex.value -= 1
      }
    }
    val afterTabId = activeTab?.id ?: "none"
    val sizeAfter = _tabs.value.size
    val closeMsg = "[FORENSIC][TAB_CLOSE] closedTabId=$tabId parentTabId=$parentId selectedTabIdBefore=$beforeTabId selectedTabIdAfter=$afterTabId tabCountBefore=$sizeBefore tabCountAfter=$sizeAfter"
    Log.i(TAG, closeMsg)
    DebugLogManager.log(closeMsg)
    trackerStatsMap.remove(tabId)
    com.remmi.browser.engine.chain.NavigationChainTracker.clearTab(tabId)
  }

  fun duplicateTab(tabId: String) {
    val tab = _tabs.value.find { it.id == tabId } ?: return
    val newTab = tab.copy(
      id = UUID.randomUUID().toString(),
      createdAt = System.currentTimeMillis(),
      lastAccessedAt = System.currentTimeMillis(),
    )
    _tabs.value = _tabs.value + newTab
    _activeTabIndex.value = _tabs.value.lastIndex
  }

  fun togglePinTab(tabId: String) {
    updateTab(tabId) { it.copy(isPinned = !it.isPinned) }
  }

  fun toggleLockTab(tabId: String) {
    updateTab(tabId) { it.copy(isLocked = !it.isLocked) }
  }

  fun lockTabs(tabIds: List<String>, lock: Boolean) {
    _tabs.value = _tabs.value.map { tab ->
      if (tabIds.contains(tab.id)) tab.copy(isLocked = lock) else tab
    }
  }

  fun setTabsInactive(tabIds: List<String>, inactive: Boolean) {
    _tabs.value = _tabs.value.map { tab ->
      if (tabIds.contains(tab.id)) tab.copy(isInactive = inactive) else tab
    }
  }

  fun moveTabsToGroup(tabIds: List<String>, groupId: String?) {
    _tabs.value = _tabs.value.map { tab ->
      if (tabIds.contains(tab.id)) tab.copy(groupId = groupId) else tab
    }
  }

  fun closeMultipleTabs(tabIds: List<String>, forceLocked: Boolean = false) {
    tabIds.forEach { id ->
      val tab = _tabs.value.find { it.id == id }
      if (tab != null && (!tab.isLocked || forceLocked)) {
        closeTab(id)
      }
    }
  }

  // --- TAB GROUP MANAGEMENT ---

  fun createGroup(title: String, colorHex: Long, initialTabIds: List<String> = emptyList()): TabGroup {
    val newGroup = TabGroup(
      id = UUID.randomUUID().toString(),
      title = title.ifBlank { "Group" },
      colorHex = colorHex,
    )
    _tabGroups.value = _tabGroups.value + newGroup
    if (initialTabIds.isNotEmpty()) {
      _tabs.value = _tabs.value.map { tab ->
        if (initialTabIds.contains(tab.id)) tab.copy(groupId = newGroup.id) else tab
      }
    }
    return newGroup
  }

  fun addTabToGroup(tabId: String, groupId: String) {
    updateTab(tabId) { it.copy(groupId = groupId) }
  }

  fun removeTabFromGroup(tabId: String) {
    updateTab(tabId) { it.copy(groupId = null) }
  }

  fun updateGroup(groupId: String, title: String, colorHex: Long) {
    _tabGroups.value = _tabGroups.value.map {
      if (it.id == groupId) it.copy(title = title, colorHex = colorHex) else it
    }
  }

  fun toggleGroupCollapse(groupId: String) {
    _tabGroups.value = _tabGroups.value.map {
      if (it.id == groupId) it.copy(isCollapsed = !it.isCollapsed) else it
    }
  }

  fun deleteGroup(groupId: String, closeTabs: Boolean = false) {
    _tabGroups.value = _tabGroups.value.filter { it.id != groupId }
    if (closeTabs) {
      val tabsToClose = _tabs.value.filter { it.groupId == groupId }.map { it.id }
      tabsToClose.forEach { closeTab(it) }
    } else {
      _tabs.value = _tabs.value.map {
        if (it.groupId == groupId) it.copy(groupId = null) else it
      }
    }
  }

  fun closeAllTabsInGroup(groupId: String) {
    val tabsToClose = _tabs.value.filter { it.groupId == groupId }.map { it.id }
    tabsToClose.forEach { closeTab(it) }
  }

  // --- INACTIVE / DORMANT TABS MANAGEMENT ---

  fun setTabInactive(tabId: String, isInactive: Boolean) {
    updateTab(tabId) { it.copy(isInactive = isInactive) }
  }

  fun setGroupInactive(groupId: String, isInactive: Boolean) {
    _tabGroups.value = _tabGroups.value.map {
      if (it.id == groupId) it.copy(isInactive = isInactive) else it
    }
    _tabs.value = _tabs.value.map {
      if (it.groupId == groupId) it.copy(isInactive = isInactive) else it
    }
  }

  fun checkAndMarkInactiveTabs(thresholdHours: Long = 24) {
    val thresholdMs = thresholdHours * 60 * 60 * 1000L
    val now = System.currentTimeMillis()
    _tabs.value = _tabs.value.mapIndexed { index, tab ->
      val isCurrent = index == _activeTabIndex.value
      if (!isCurrent && !tab.isPinned && (now - tab.lastAccessedAt) > thresholdMs) {
        tab.copy(isInactive = true)
      } else {
        tab
      }
    }
  }

  fun closeAllInactiveTabs() {
    val inactiveIds = _tabs.value.filter { it.isInactive }.map { it.id }
    inactiveIds.forEach { closeTab(it) }
  }

  fun restoreAllInactiveTabs() {
    _tabs.value = _tabs.value.map { it.copy(isInactive = false, lastAccessedAt = System.currentTimeMillis()) }
    _tabGroups.value = _tabGroups.value.map { it.copy(isInactive = false) }
  }

  // --- PRIVACY & PROFILE ---

  fun togglePrivacyProfile() {
    val currentTab = activeTab ?: return
    val newProfile = if (currentTab.profile == PrivacyProfile.SHIELD) {
      PrivacyProfile.GHOST
    } else {
      PrivacyProfile.SHIELD
    }
    updateTab(currentTab.id) { it.copy(profile = newProfile) }
  }

  fun setAllTabsProfile(profile: PrivacyProfile) {
    _tabs.value = _tabs.value.map { it.copy(profile = profile) }
  }

  fun toggleReaderMode(tabId: String) {
    updateTab(tabId) { it.copy(isReaderMode = !it.isReaderMode) }
  }

  fun toggleDesktopMode(tabId: String) {
    updateTab(tabId) { it.copy(isDesktopMode = !it.isDesktopMode) }
  }

  fun incrementTrackerCount(tabId: String, blockedDomain: String) {
    trackerEventCounter.incrementAndGet()
    val category = com.remmi.browser.security.TrackerClassifier.classify(blockedDomain)
    val displayHost = try {
      val uri = java.net.URI(if (blockedDomain.contains("://")) blockedDomain else "https://$blockedDomain")
      uri.host?.ifEmpty { blockedDomain } ?: blockedDomain
    } catch (_: Exception) {
      blockedDomain
    }

    trackerStatsMap.compute(tabId) { _, existing ->
      val base = existing ?: run {
        val tab = _tabs.value.find { it.id == tabId }
        if (tab != null) {
          TabTrackerStats(
            blockedTrackersCount = tab.blockedTrackersCount,
            adsBlockedCount = tab.adsBlockedCount,
            analyticsBlockedCount = tab.analyticsBlockedCount,
            socialBlockedCount = tab.socialBlockedCount,
            cryptomineBlockedCount = tab.cryptomineBlockedCount,
            fingerprintBlockedCount = tab.fingerprintBlockedCount,
            blockedLog = tab.blockedLog,
          )
        } else {
          TabTrackerStats()
        }
      }

      val newLog = if (base.blockedLog.size >= 100) base.blockedLog.drop(1) + displayHost else base.blockedLog + displayHost
      val newTotal = base.blockedTrackersCount + 1

      when (category) {
        com.remmi.browser.security.TrackerCategory.ADVERTISING -> base.copy(
          blockedTrackersCount = newTotal,
          adsBlockedCount = base.adsBlockedCount + 1,
          blockedLog = newLog,
          isDirty = true,
          lastUpdated = System.currentTimeMillis()
        )
        com.remmi.browser.security.TrackerCategory.ANALYTICS -> base.copy(
          blockedTrackersCount = newTotal,
          analyticsBlockedCount = base.analyticsBlockedCount + 1,
          blockedLog = newLog,
          isDirty = true,
          lastUpdated = System.currentTimeMillis()
        )
        com.remmi.browser.security.TrackerCategory.SOCIAL -> base.copy(
          blockedTrackersCount = newTotal,
          socialBlockedCount = base.socialBlockedCount + 1,
          blockedLog = newLog,
          isDirty = true,
          lastUpdated = System.currentTimeMillis()
        )
        com.remmi.browser.security.TrackerCategory.CRYPTOMINING -> base.copy(
          blockedTrackersCount = newTotal,
          cryptomineBlockedCount = base.cryptomineBlockedCount + 1,
          blockedLog = newLog,
          isDirty = true,
          lastUpdated = System.currentTimeMillis()
        )
        com.remmi.browser.security.TrackerCategory.FINGERPRINTING -> base.copy(
          blockedTrackersCount = newTotal,
          fingerprintBlockedCount = base.fingerprintBlockedCount + 1,
          blockedLog = newLog,
          isDirty = true,
          lastUpdated = System.currentTimeMillis()
        )
        else -> base.copy(
          blockedTrackersCount = newTotal,
          adsBlockedCount = base.adsBlockedCount + 1,
          blockedLog = newLog,
          isDirty = true,
          lastUpdated = System.currentTimeMillis()
        )
      }
    }
  }

  fun setReaderArticle(tabId: String, article: ReaderArticle?) {
    updateTab(tabId) { it.copy(readerArticle = article) }
  }

  fun restoreSavedTabs(savedTabs: List<SessionTabEntity>) {
    if (savedTabs.isEmpty()) return
    val currentTabs = _tabs.value
    if (currentTabs.size > 1 || (currentTabs.size == 1 && currentTabs[0].url != "about:blank" && currentTabs[0].url.isNotBlank())) {
      val skipMsg = "[FORENSIC][RESTORE_SKIPPED] Active session already in progress with ${currentTabs.size} tabs"
      Log.i(TAG, skipMsg)
      DebugLogManager.log(skipMsg)
      return
    }
    val restored = savedTabs.map { entity ->
      val p = try {
        PrivacyProfile.valueOf(entity.profile)
      } catch (e: Exception) {
        PrivacyProfile.SHIELD
      }
      BrowserTab(
        id = entity.id,
        url = entity.url,
        title = entity.title,
        profile = p,
        isDesktopMode = entity.isDesktopMode,
        lastAccessedAt = entity.timestamp,
      )
    }
    val beforeTabId = activeTab?.id ?: "none"
    _tabs.value = restored
    _activeTabIndex.value = 0
    val afterTabId = activeTab?.id ?: "none"
    val resMsg = "[FORENSIC][RESTORE_TABS] selectedTabIdBefore=$beforeTabId selectedTabIdAfter=$afterTabId tabCountBefore=${currentTabs.size} tabCountAfter=${restored.size}"
    Log.i(TAG, resMsg)
    DebugLogManager.log(resMsg)
  }

  fun purgePrivateTabs() {
    val nonPrivateTabs = _tabs.value.filter { it.profile != PrivacyProfile.GHOST && it.profile != PrivacyProfile.INCOGNITO }
    val removedIds = _tabs.value.filter { it.profile == PrivacyProfile.GHOST || it.profile == PrivacyProfile.INCOGNITO }.map { it.id }
    removedIds.forEach { trackerStatsMap.remove(it) }
    if (nonPrivateTabs.isEmpty()) {
      resetToSingleBlankTab(PrivacyProfile.SHIELD)
    } else {
      _tabs.value = nonPrivateTabs
      _activeTabIndex.value = _activeTabIndex.value.coerceIn(0, nonPrivateTabs.lastIndex)
    }
    Log.i(TAG, "[PRIVATE_TABS_PURGED] Remaining active tabs: ${_tabs.value.size}")
  }

  fun resetToSingleBlankTab(defaultProfile: PrivacyProfile = PrivacyProfile.SHIELD) {
    trackerStatsMap.clear()
    val blankTab = BrowserTab(
      id = UUID.randomUUID().toString(),
      url = "about:blank",
      title = "New Tab",
      profile = defaultProfile,
      lastAccessedAt = System.currentTimeMillis(),
    )
    _tabs.value = listOf(blankTab)
    _tabGroups.value = emptyList()
    _activeTabIndex.value = 0
    Log.i(TAG, "[TAB_RESET_BLANK] id=${blankTab.id} profile=$defaultProfile")
    DebugLogManager.log("[TAB_RESET_BLANK] id=${blankTab.id} profile=$defaultProfile")
  }

  fun closeAllTabs(defaultProfile: PrivacyProfile = PrivacyProfile.SHIELD) {
    resetToSingleBlankTab(defaultProfile)
  }

  fun updateInitialTabProfile(defaultProfile: PrivacyProfile) {
    if (_tabs.value.size == 1 && _tabs.value[0].url == "about:blank") {
      _tabs.value = listOf(_tabs.value[0].copy(profile = defaultProfile))
    }
  }

  companion object {
    private const val TAG = "TabManager"
    @Volatile
    private var INSTANCE: TabManager? = null

    fun getInstance(): TabManager {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: TabManager().also { INSTANCE = it }
      }
    }
  }
}
