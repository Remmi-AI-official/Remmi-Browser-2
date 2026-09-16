package com.remmi.browser.security

import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings

/**
 * Remmi Anti-Fingerprinting Engine
 * Provides GeckoView RFP (Resist Fingerprinting) & FPP (Fingerprinting Protection)
 * preference sets and user agent spoofing.
 */
object AntiFingerprint {

  const val TOR_USER_AGENT =
    "Mozilla/5.0 (Android; Linux x86_64; rv:125.0) Gecko/20100101 Firefox/125.0"

  const val SHIELD_USER_AGENT =
    "Mozilla/5.0 (Android 14; Mobile; rv:125.0) Gecko/125.0 Firefox/125.0 Remmi/1.0.2"

  /**
   * Applies privacy profile and security level settings to a GeckoSession
   */
  fun configureGeckoSession(
    session: GeckoSession,
    profile: PrivacyProfile,
    securityLevel: SecurityLevel = SecurityLevel.STANDARD
  ) {
    val settings = session.settings
    settings.useTrackingProtection = true
    settings.allowJavascript = securityLevel.javascriptEnabled
    settings.suspendMediaWhenInactive = true

    if (profile == PrivacyProfile.GHOST) {
      settings.userAgentMode = GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
    } else {
      settings.userAgentMode = GeckoSessionSettings.USER_AGENT_MODE_MOBILE
    }
  }

  fun getPreferencesMap(
    profile: PrivacyProfile,
    securityLevel: SecurityLevel = SecurityLevel.STANDARD,
    socksPort: Int? = CurrentTorRoute.currentSocksPort,
  ): Map<String, Any> {
    val activePort = socksPort ?: CurrentTorRoute.currentSocksPort ?: 0
    if (profile == PrivacyProfile.GHOST && activePort <= 0) {
      throw IllegalStateException("Ghost preferences require a verified Tor SOCKS port; refusing stale/default port fallback")
    }
    val baseMap = if (profile == PrivacyProfile.SHIELD || profile == PrivacyProfile.INCOGNITO) {
      mutableMapOf<String, Any>(
        "dom.disable_open_during_load" to true,
        "dom.block_multiple_popups" to true,
        "dom.popup_allowed_events" to "click dblclick",
        "privacy.popups.showBrowserMessage" to false,
        "network.trr.mode" to 2,
        "network.trr.uri" to "https://cloudflare-dns.com/dns-query",
        "network.dns.echconfig.enabled" to true,
        "security.tls.ech.grease_probability" to 100,
        "network.dns.use_https_rr_as_alpn" to true,
        "media.peerconnection.enabled" to false,
        "network.proxy.type" to 0,
        "network.http.referer.trimmingPolicy" to 2,
        "network.http.referer.XOriginTrimmingPolicy" to 2,
        "network.http.referer.defaultPolicy" to 2,
        "network.http.referer.XOriginPolicy" to 2,
        "privacy.donottrackheader.enabled" to true,
        "privacy.globalprivacycontrol.enabled" to true,
        "privacy.reduceTimerPrecision" to true,
        "privacy.reduceTimerPrecision.microseconds" to 2000,
        "dom.webaudio.enabled" to true,
        "privacy.resistFingerprinting" to false,
        "privacy.resistFingerprinting.letterboxing" to false,
        "privacy.resistFingerprinting.randomDataOnCanvasExtract" to false,
        "privacy.resistFingerprinting.autoDeclineNoUserInputCanvasPrompts" to true,
        "privacy.resistFingerprinting.block_mozAddonManager" to true,
        "privacy.resistFingerprinting.randomization.canvas.use_siphash" to false,
        "privacy.resistFingerprinting.randomization.daily_reset.enabled" to false,
        "privacy.resistFingerprinting.randomization.daily_reset.private.enabled" to false,
        "dom.maxHardwareConcurrency" to 2,
        "privacy.fingerprintingProtection" to true,
        "privacy.fingerprintingProtection.overrides" to "+AllTargets,-FrameRate",
        "privacy.firstparty.isolate" to true,
        "network.cookie.cookieBehavior" to 5,
        "privacy.trackingprotection.enabled" to true,
        "privacy.trackingprotection.socialtracking.enabled" to true,
        "privacy.trackingprotection.cryptomining.enabled" to true,
        "privacy.trackingprotection.fingerprinting.enabled" to true,
        "general.smoothScroll" to true,
        "general.smoothScroll.lines" to true,
        "general.smoothScroll.pages" to true,
        "general.smoothScroll.scrollbars" to true,
        "general.smoothScroll.other" to true,
        "general.smoothScroll.pixels" to true,
        "general.smoothScroll.mouseWheel" to true,
        "general.smoothScroll.msdPhysics.enabled" to false,
        "general.smoothScroll.msdPhysics.continuousMode" to false,
        "apz.overscroll.enabled" to true,
        "apz.allow_zooming" to true,
        "apz.paint_skipping.enabled" to false,
        "apz.peek_messages.enabled" to false,
        "apz.velocity_bias" to "1.0",
        "layout.css.touch_action.enabled" to true,
        "layout.css.scroll-behavior.enabled" to true,
      )
    } else {
      mutableMapOf<String, Any>(
        "dom.disable_open_during_load" to true,
        "dom.block_multiple_popups" to true,
        "dom.popup_allowed_events" to "click dblclick",
        "privacy.popups.showBrowserMessage" to false,
        "network.proxy.type" to 1,
        "network.proxy.socks" to "127.0.0.1",
        "network.proxy.socks_port" to activePort,
        "network.proxy.socks_version" to 5,
        "network.proxy.socks_remote_dns" to true,
        "network.proxy.socks5_remote_dns" to true,
        "network.proxy.failover_direct" to false,
        "network.proxy.allow_bypass" to false,
        "network.proxy.no_proxies_on" to "",
        "network.proxy.http" to "",
        "network.proxy.http_port" to 0,
        "network.proxy.ssl" to "",
        "network.proxy.ssl_port" to 0,
        "network.proxy.share_proxy_settings" to false,
        "network.trr.mode" to 0,
        "network.dns.echconfig.enabled" to false,
        "privacy.resistFingerprinting" to true,
        "privacy.resistFingerprinting.letterboxing" to true,
        "privacy.resistFingerprinting.letterboxing.dimensions" to "360x640, 400x700, 480x800, 800x600",
        "privacy.resistFingerprinting.randomDataOnCanvasExtract" to false,
        "privacy.resistFingerprinting.autoDeclineNoUserInputCanvasPrompts" to true,
        "privacy.resistFingerprinting.block_mozAddonManager" to true,
        "privacy.resistFingerprinting.randomization.canvas.use_siphash" to false,
        "privacy.resistFingerprinting.randomization.daily_reset.enabled" to false,
        "privacy.resistFingerprinting.randomization.daily_reset.private.enabled" to false,
        "dom.webaudio.enabled" to false,
        "dom.maxHardwareConcurrency" to 2,
        "media.peerconnection.enabled" to false,
        "media.navigator.enabled" to false,
        "network.http.referer.trimmingPolicy" to 2,
        "network.http.referer.XOriginTrimmingPolicy" to 2,
        "network.http.referer.defaultPolicy" to 2,
        "network.http.referer.XOriginPolicy" to 2,
        "privacy.reduceTimerPrecision" to true,
        "privacy.reduceTimerPrecision.microseconds" to 16666,
        "privacy.resistFingerprinting.reduceTimerPrecision.microseconds" to 16666,
        "general.platform.override" to "Linux aarch64",
        "general.oscpu.override" to "Linux aarch64",
        "privacy.partition.network_state" to true,
        "privacy.fingerprintingProtection" to false,
        "privacy.spoof_english" to 2,
        "javascript.use_us_english_locale" to true,
        "general.useragent.override" to TOR_USER_AGENT,
        "dom.battery.enabled" to false,
        "dom.gamepad.enabled" to false,
        "dom.vibrator.enabled" to false,
        "device.sensors.enabled" to false,
        "network.captive-portal-service.enabled" to false,
        "network.dns.disablePrefetch" to true,
        "network.dns.disablePrefetchFromHTTPS" to true,
        "network.http.speculative-parallel-limit" to 0,
        "general.smoothScroll" to true,
        "general.smoothScroll.lines" to true,
        "general.smoothScroll.pages" to true,
        "general.smoothScroll.scrollbars" to true,
        "general.smoothScroll.other" to true,
        "general.smoothScroll.pixels" to true,
        "general.smoothScroll.mouseWheel" to true,
        "general.smoothScroll.msdPhysics.enabled" to false,
        "general.smoothScroll.msdPhysics.continuousMode" to false,
        "apz.overscroll.enabled" to true,
        "apz.allow_zooming" to true,
        "apz.paint_skipping.enabled" to false,
        "apz.peek_messages.enabled" to false,
        "apz.velocity_bias" to "1.0",
        "layout.css.touch_action.enabled" to true,
        "layout.css.scroll-behavior.enabled" to true,
      )
    }

    // Apply SecurityLevel specific hardening
    when (securityLevel) {
      SecurityLevel.STANDARD -> {
        baseMap["javascript.enabled"] = true
        baseMap["media.autoplay.default"] = 0 // Allow autoplay
      }
      SecurityLevel.SAFER -> {
        baseMap["javascript.enabled"] = true
        baseMap["media.autoplay.default"] = 5 // Block autoplay
        baseMap["dom.audiochannel.mutedByDefault"] = true
        baseMap["security.mixed_content.block_active_content"] = true
        baseMap["security.mixed_content.block_display_content"] = true
        baseMap["svg.disabled"] = false
      }
      SecurityLevel.SAFEST -> {
        baseMap["javascript.enabled"] = false
        baseMap["media.autoplay.default"] = 5
        baseMap["media.play-stand-alone"] = false
        baseMap["security.mixed_content.block_active_content"] = true
        baseMap["security.mixed_content.block_display_content"] = true
        baseMap["svg.disabled"] = true
        baseMap["webgl.disabled"] = true
      }
    }

    return baseMap
  }
}
