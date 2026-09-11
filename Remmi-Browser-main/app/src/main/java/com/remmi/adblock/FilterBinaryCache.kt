package com.remmi.adblock

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * FilterBinaryCache
 *
 * Provides high-speed binary serialization and deserialization for parsed adblock rules,
 * eliminating the need to parse ~185,000 plain-text rules on cold startup.
 *
 * Format schema:
 * - MAGIC: 0x52454D41 ("REMA")
 * - VERSION: 1
 * - HASH: String (SHA-256 hash of raw filter text)
 * - RULE COUNT: Int
 * - BLOCKED HOSTNAMES: Set<String>
 * - BLOCKED SUBSTRINGS: List<String>
 * - ALLOW LIST: Set<String>
 * - FALLBACK NETWORK RULES: List<FallbackNetworkRule>
 * - COSMETIC RULES: List<Pair<String?, String>>
 * - ADDITIONAL COSMETIC RULES: List<Pair<String?, String>>
 * - PROCEDURAL FILTERS: List<String>
 * - COSMETIC EXCEPTIONS: Set<String>
 * - GENERATION: Long
 */
object FilterBinaryCache {
  private const val TAG = "FilterBinaryCache"
  private const val MAGIC = 0x52454D41 // "REMA"
  private const val VERSION = 1
  private const val CACHE_FILE_NAME = "compiled_filters.dat"

  private fun getCacheFile(context: Context): File {
    val dir = File(context.filesDir, "adblock_filters")
    if (!dir.exists()) {
      dir.mkdirs()
    }
    return File(dir, CACHE_FILE_NAME)
  }

  fun computeRulesHash(defaultRulesText: String, additionalRulesText: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(defaultRulesText.toByteArray(Charsets.UTF_8))
    md.update(additionalRulesText.toByteArray(Charsets.UTF_8))
    return md.digest().joinToString("") { "%02x".format(it) }
  }

  fun saveToCache(
    context: Context,
    hash: String,
    ruleCount: Int,
    engine: FallbackEngineSet
  ): Boolean {
    val startTime = android.os.SystemClock.elapsedRealtime()
    val cacheFile = getCacheFile(context)
    val tmpFile = File(cacheFile.parentFile, "${cacheFile.name}.tmp_${System.currentTimeMillis()}")

    try {
      DataOutputStream(BufferedOutputStream(FileOutputStream(tmpFile), 65536)).use { out ->
        out.writeInt(MAGIC)
        out.writeInt(VERSION)
        out.writeUTF(hash)
        out.writeInt(ruleCount)
        out.writeLong(engine.generation)

        // 1. Blocked Hostnames
        out.writeInt(engine.blockedHostnames.size)
        for (hostname in engine.blockedHostnames) {
          out.writeUTF(hostname)
        }

        // 2. Blocked Substrings
        out.writeInt(engine.blockedSubstrings.size)
        for (sub in engine.blockedSubstrings) {
          out.writeUTF(sub)
        }

        // 3. Allow List
        out.writeInt(engine.allowList.size)
        for (allow in engine.allowList) {
          out.writeUTF(allow)
        }

        // 4. Fallback Network Rules
        out.writeInt(engine.fallbackNetworkRules.size)
        for (rule in engine.fallbackNetworkRules) {
          out.writeUTF(rule.raw)
          out.writeBoolean(rule.isException)
          out.writeBoolean(rule.isImportant)
          
          out.writeBoolean(rule.domainPattern != null)
          if (rule.domainPattern != null) out.writeUTF(rule.domainPattern)
          
          out.writeBoolean(rule.substringPattern != null)
          if (rule.substringPattern != null) out.writeUTF(rule.substringPattern)

          out.writeInt(rule.resourceTypes.size)
          for (rt in rule.resourceTypes) out.writeUTF(rt)

          out.writeInt(rule.excludedResourceTypes.size)
          for (ert in rule.excludedResourceTypes) out.writeUTF(ert)

          out.writeInt(rule.methods.size)
          for (m in rule.methods) out.writeUTF(m)

          out.writeInt(if (rule.thirdParty == null) 0 else if (rule.thirdParty) 1 else -1)

          out.writeInt(rule.includedSourceDomains.size)
          for (inc in rule.includedSourceDomains) out.writeUTF(inc)

          out.writeInt(rule.excludedSourceDomains.size)
          for (exc in rule.excludedSourceDomains) out.writeUTF(exc)
        }

        // 5. Cosmetic Rules (Pair<String?, String>)
        out.writeInt(engine.fallbackCosmeticRules.size)
        for ((dom, sel) in engine.fallbackCosmeticRules) {
          out.writeBoolean(dom != null)
          if (dom != null) out.writeUTF(dom)
          out.writeUTF(sel)
        }

        // 6. Additional Cosmetic Rules
        out.writeInt(engine.fallbackAdditionalCosmeticRules.size)
        for ((dom, sel) in engine.fallbackAdditionalCosmeticRules) {
          out.writeBoolean(dom != null)
          if (dom != null) out.writeUTF(dom)
          out.writeUTF(sel)
        }

        // 7. Procedural Filters
        out.writeInt(engine.fallbackProceduralFilters.size)
        for (proc in engine.fallbackProceduralFilters) {
          out.writeUTF(proc)
        }

        // 8. Cosmetic Exceptions
        out.writeInt(engine.fallbackCosmeticExceptions.size)
        for (exc in engine.fallbackCosmeticExceptions) {
          out.writeUTF(exc)
        }

        out.flush()
      }

      if (cacheFile.exists()) {
        cacheFile.delete()
      }
      val renamed = tmpFile.renameTo(cacheFile)
      if (!renamed) {
        Log.w(TAG, "Failed renaming binary cache temp file")
        return false
      }

      val elapsed = android.os.SystemClock.elapsedRealtime() - startTime
      Log.i(TAG, "[FILTER_BINARY_CACHE_SAVED] rules=$ruleCount size=${cacheFile.length()} bytes elapsedMs=${elapsed}ms")
      return true
    } catch (e: Exception) {
      Log.e(TAG, "Failed writing binary filter cache: ${e.message}", e)
      if (tmpFile.exists()) tmpFile.delete()
      return false
    }
  }

  fun loadFromCache(
    context: Context,
    expectedHash: String? = null
  ): Pair<Int, FallbackEngineSet>? {
    val cacheFile = getCacheFile(context)
    if (!cacheFile.exists() || cacheFile.length() < 16) {
      return null
    }

    val startTime = android.os.SystemClock.elapsedRealtime()
    try {
      DataInputStream(BufferedInputStream(FileInputStream(cacheFile), 65536)).use { inp ->
        val magic = inp.readInt()
        if (magic != MAGIC) {
          Log.w(TAG, "Invalid magic number in filter binary cache: $magic")
          return null
        }
        val version = inp.readInt()
        if (version != VERSION) {
          Log.w(TAG, "Version mismatch in filter binary cache: $version vs $VERSION")
          return null
        }
        val cachedHash = inp.readUTF()
        if (expectedHash != null && cachedHash != expectedHash) {
          Log.i(TAG, "Binary cache hash mismatch (cache is stale)")
          return null
        }
        val ruleCount = inp.readInt()
        val generation = inp.readLong()

        // 1. Blocked Hostnames
        val hostnamesCount = inp.readInt()
        val blockedHostnames = HashSet<String>(hostnamesCount)
        for (i in 0 until hostnamesCount) {
          blockedHostnames.add(inp.readUTF())
        }

        // 2. Blocked Substrings
        val substringsCount = inp.readInt()
        val blockedSubstrings = ArrayList<String>(substringsCount)
        for (i in 0 until substringsCount) {
          blockedSubstrings.add(inp.readUTF())
        }

        // 3. Allow List
        val allowCount = inp.readInt()
        val allowList = HashSet<String>(allowCount)
        for (i in 0 until allowCount) {
          allowList.add(inp.readUTF())
        }

        // 4. Fallback Network Rules
        val netRulesCount = inp.readInt()
        val fallbackNetworkRules = ArrayList<FallbackNetworkRule>(netRulesCount)
        for (i in 0 until netRulesCount) {
          val raw = inp.readUTF()
          val isException = inp.readBoolean()
          val isImportant = inp.readBoolean()

          val hasDomain = inp.readBoolean()
          val domainPattern = if (hasDomain) inp.readUTF() else null

          val hasSubstring = inp.readBoolean()
          val substringPattern = if (hasSubstring) inp.readUTF() else null

          val rtCount = inp.readInt()
          val resourceTypes = HashSet<String>(rtCount)
          for (j in 0 until rtCount) resourceTypes.add(inp.readUTF())

          val ertCount = inp.readInt()
          val excludedResourceTypes = HashSet<String>(ertCount)
          for (j in 0 until ertCount) excludedResourceTypes.add(inp.readUTF())

          val mCount = inp.readInt()
          val methods = HashSet<String>(mCount)
          for (j in 0 until mCount) methods.add(inp.readUTF())

          val tpCode = inp.readInt()
          val thirdParty: Boolean? = when (tpCode) {
            1 -> true
            -1 -> false
            else -> null
          }

          val incCount = inp.readInt()
          val includedSourceDomains = HashSet<String>(incCount)
          for (j in 0 until incCount) includedSourceDomains.add(inp.readUTF())

          val excCount = inp.readInt()
          val excludedSourceDomains = HashSet<String>(excCount)
          for (j in 0 until excCount) excludedSourceDomains.add(inp.readUTF())

          fallbackNetworkRules.add(
            FallbackNetworkRule(
              raw = raw,
              isException = isException,
              isImportant = isImportant,
              domainPattern = domainPattern,
              substringPattern = substringPattern,
              resourceTypes = resourceTypes,
              excludedResourceTypes = excludedResourceTypes,
              methods = methods,
              thirdParty = thirdParty,
              includedSourceDomains = includedSourceDomains,
              excludedSourceDomains = excludedSourceDomains
            )
          )
        }

        // 5. Cosmetic Rules
        val cosCount = inp.readInt()
        val fallbackCosmeticRules = ArrayList<Pair<String?, String>>(cosCount)
        for (i in 0 until cosCount) {
          val hasDom = inp.readBoolean()
          val dom = if (hasDom) inp.readUTF() else null
          val sel = inp.readUTF()
          fallbackCosmeticRules.add(Pair(dom, sel))
        }

        // 6. Additional Cosmetic Rules
        val addCosCount = inp.readInt()
        val fallbackAdditionalCosmeticRules = ArrayList<Pair<String?, String>>(addCosCount)
        for (i in 0 until addCosCount) {
          val hasDom = inp.readBoolean()
          val dom = if (hasDom) inp.readUTF() else null
          val sel = inp.readUTF()
          fallbackAdditionalCosmeticRules.add(Pair(dom, sel))
        }

        // 7. Procedural Filters
        val procCount = inp.readInt()
        val fallbackProceduralFilters = ArrayList<String>(procCount)
        for (i in 0 until procCount) {
          fallbackProceduralFilters.add(inp.readUTF())
        }

        // 8. Cosmetic Exceptions
        val cosExcCount = inp.readInt()
        val fallbackCosmeticExceptions = HashSet<String>(cosExcCount)
        for (i in 0 until cosExcCount) {
          fallbackCosmeticExceptions.add(inp.readUTF())
        }

        val engine = FallbackEngineSet(
          blockedHostnames = blockedHostnames,
          blockedSubstrings = blockedSubstrings,
          allowList = allowList,
          fallbackNetworkRules = fallbackNetworkRules,
          fallbackCosmeticRules = fallbackCosmeticRules,
          fallbackAdditionalCosmeticRules = fallbackAdditionalCosmeticRules,
          fallbackProceduralFilters = fallbackProceduralFilters,
          fallbackCosmeticExceptions = fallbackCosmeticExceptions,
          generation = generation
        )

        val elapsed = android.os.SystemClock.elapsedRealtime() - startTime
        Log.i(TAG, "[FILTER_BINARY_CACHE_LOADED] rules=$ruleCount elapsedMs=${elapsed}ms")
        return Pair(ruleCount, engine)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed reading binary filter cache: ${e.message}", e)
      return null
    }
  }

  fun invalidate(context: Context) {
    val cacheFile = getCacheFile(context)
    if (cacheFile.exists()) {
      cacheFile.delete()
      Log.i(TAG, "[FILTER_BINARY_CACHE_INVALIDATED]")
    }
  }
}
