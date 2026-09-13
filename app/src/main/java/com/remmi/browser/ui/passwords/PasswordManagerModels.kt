package com.remmi.browser.ui.passwords

import androidx.compose.ui.graphics.Color
import com.remmi.browser.security.crypto.DecryptedPasswordEntry
import java.net.URI
import java.security.SecureRandom
import kotlin.math.log2
import kotlin.math.roundToInt

data class WebsiteFolder(
  val domain: String,
  val displayName: String,
  val entries: List<DecryptedPasswordEntry>,
  val healthScore: Int,
  val weakCount: Int,
  val reusedCount: Int,
)

enum class PasswordStrength(
  val label: String,
  val color: Color,
  val scoreFraction: Float,
) {
  CRITICAL("Very Weak", Color(0xFFFF1744), 0.2f),
  WEAK("Weak", Color(0xFFFF5252), 0.4f),
  MEDIUM("Fair", Color(0xFFFFB300), 0.65f),
  STRONG("Military Grade (Strong)", Color(0xFF00E676), 0.85f),
  QUANTUM("Maximum (Quantum-Safe)", Color(0xFF00F2FE), 1.0f),
}

data class VaultStats(
  val totalFolders: Int,
  val totalCredentials: Int,
  val overallHealth: Int,
  val weakPasswordsCount: Int,
  val reusedPasswordsCount: Int,
)

object PasswordManagerUtils {

  fun extractDomain(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) return "general"
    return try {
      val withScheme = if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
        "https://$trimmed"
      } else trimmed
      val uri = URI(withScheme)
      var host = uri.host?.lowercase() ?: ""
      if (host.startsWith("www.")) {
        host = host.substring(4)
      }
      if (host.isNotBlank()) host else "general"
    } catch (_: Exception) {
      val clean = trimmed.lowercase()
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")
        .substringBefore('/')
        .substringBefore(':')
      if (clean.isNotBlank()) clean else "general"
    }
  }

  fun getDisplayNameForDomain(domain: String): String {
    val parts = domain.split('.')
    if (parts.size >= 2) {
      val name = if (parts.size == 2) parts[0] else parts[parts.size - 2]
      return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
    return domain.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
  }

  fun calculateEntropyBits(password: String): Int {
    if (password.isEmpty()) return 0
    var poolSize = 0
    if (password.any { it.isLowerCase() }) poolSize += 26
    if (password.any { it.isUpperCase() }) poolSize += 26
    if (password.any { it.isDigit() }) poolSize += 10
    if (password.any { !it.isLetterOrDigit() }) poolSize += 32
    if (poolSize == 0) poolSize = 1
    val entropy = password.length * log2(poolSize.toDouble())
    return entropy.roundToInt()
  }

  fun evaluateStrength(password: String): PasswordStrength {
    val len = password.length
    val entropy = calculateEntropyBits(password)
    return when {
      len < 8 || entropy < 30 -> PasswordStrength.CRITICAL
      len < 12 || entropy < 50 -> PasswordStrength.WEAK
      len < 16 || entropy < 75 -> PasswordStrength.MEDIUM
      len < 24 || entropy < 100 -> PasswordStrength.STRONG
      else -> PasswordStrength.QUANTUM
    }
  }

  fun groupEntriesIntoFolders(entries: List<DecryptedPasswordEntry>): List<WebsiteFolder> {
    if (entries.isEmpty()) return emptyList()

    // 1. Identify reused passwords across different websites
    val passwordUsageMap = mutableMapOf<String, MutableSet<String>>()
    for (e in entries) {
      val dom = extractDomain(e.url)
      passwordUsageMap.getOrPut(e.password) { mutableSetOf() }.add(dom)
    }

    val grouped = entries.groupBy { extractDomain(it.url) }
    val folders = mutableListOf<WebsiteFolder>()

    for ((domain, domainEntries) in grouped) {
      var weakCount = 0
      var reusedCount = 0
      var scoreSum = 0

      for (e in domainEntries) {
        val str = evaluateStrength(e.password)
        if (str == PasswordStrength.CRITICAL || str == PasswordStrength.WEAK) {
          weakCount++
        }
        val domainsUsingThis = passwordUsageMap[e.password]
        if (domainsUsingThis != null && domainsUsingThis.size > 1) {
          reusedCount++
        }
        scoreSum += (str.scoreFraction * 100).roundToInt()
      }

      val avgScore = if (domainEntries.isNotEmpty()) (scoreSum / domainEntries.size) else 100
      folders.add(
        WebsiteFolder(
          domain = domain,
          displayName = getDisplayNameForDomain(domain),
          entries = domainEntries.sortedByDescending { it.updatedAt },
          healthScore = avgScore,
          weakCount = weakCount,
          reusedCount = reusedCount,
        )
      )
    }

    return folders.sortedBy { it.displayName.lowercase() }
  }

  fun computeVaultStats(entries: List<DecryptedPasswordEntry>): VaultStats {
    val folders = groupEntriesIntoFolders(entries)
    if (entries.isEmpty()) {
      return VaultStats(
        totalFolders = 0,
        totalCredentials = 0,
        overallHealth = 100,
        weakPasswordsCount = 0,
        reusedPasswordsCount = 0,
      )
    }

    var totalWeak = 0
    var totalReused = 0
    val passwordUsage = mutableMapOf<String, MutableSet<String>>()

    for (e in entries) {
      val str = evaluateStrength(e.password)
      if (str == PasswordStrength.CRITICAL || str == PasswordStrength.WEAK) {
        totalWeak++
      }
      val dom = extractDomain(e.url)
      passwordUsage.getOrPut(e.password) { mutableSetOf() }.add(dom)
    }

    for ((_, doms) in passwordUsage) {
      if (doms.size > 1) {
        totalReused += doms.size
      }
    }

    val avgHealth = if (folders.isNotEmpty()) {
      folders.sumOf { it.healthScore } / folders.size
    } else 100

    return VaultStats(
      totalFolders = folders.size,
      totalCredentials = entries.size,
      overallHealth = avgHealth.coerceIn(10, 100),
      weakPasswordsCount = totalWeak,
      reusedPasswordsCount = totalReused,
    )
  }
}
