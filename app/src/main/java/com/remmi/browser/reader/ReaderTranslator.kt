package com.remmi.browser.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import org.json.JSONArray
import com.remmi.browser.security.CurrentTorRoute
import com.remmi.browser.security.NetworkRouteAuthority
import java.net.URLEncoder
import java.util.Scanner

data class TranslationLanguage(
  val code: String,
  val displayName: String,
  val nativeName: String,
)

object ReaderTranslator {
  private const val TAG = "ReaderTranslator"
  private const val PARA_DELIMITER = "\n\n<<<P_BREAK>>>\n\n"
  private val DELIMITER_REGEX = Regex("""\s*<{1,3}\s*P_BREAK\s*>{1,3}\s*""", RegexOption.IGNORE_CASE)

  val SUPPORTED_LANGUAGES = listOf(
    TranslationLanguage("hi", "Hindi", "हिन्दी"),
    TranslationLanguage("es", "Spanish", "Español"),
    TranslationLanguage("fr", "French", "Français"),
    TranslationLanguage("de", "German", "Deutsch"),
    TranslationLanguage("ja", "Japanese", "日本語"),
    TranslationLanguage("zh-CN", "Chinese", "中文"),
    TranslationLanguage("ru", "Russian", "Русский"),
    TranslationLanguage("ar", "Arabic", "العربية"),
    TranslationLanguage("pt", "Portuguese", "Português"),
    TranslationLanguage("it", "Italian", "Italiano"),
    TranslationLanguage("bn", "Bengali", "বাংলা"),
    TranslationLanguage("mr", "Marathi", "मराठी"),
    TranslationLanguage("ta", "Tamil", "தமிழ்"),
    TranslationLanguage("te", "Telugu", "తెలుగు"),
    TranslationLanguage("gu", "Gujarati", "ગુજરાતી"),
    TranslationLanguage("ko", "Korean", "한국어"),
  )

  /**
   * Fast HTTP translation request for single string block
   */
  private fun executeTranslationRequest(
    context: Context,
    text: String,
    targetLanguageCode: String,
    isGhost: Boolean
  ): String {
    if (text.isBlank()) return text
    val boundedText = if (text.length > 5000) text.take(5000) else text

    val encoded = URLEncoder.encode(boundedText, "UTF-8")
    val urlStr = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$targetLanguageCode&dt=t&q=$encoded"

    // Auxiliary translation traffic must obey the same route authority as all other
    // network clients. This removes the old hard-coded SOCKS 9050 path which could
    // become stale across Tor restarts and accidentally bypass the verified route.
    try {
      val client = NetworkRouteAuthority.createHttpClient(
        isGhost = isGhost,
        targetUrl = urlStr,
        connectTimeoutSeconds = 5L,
        readTimeoutSeconds = 7L,
        followRedirects = true
      )
      val request = okhttp3.Request.Builder()
        .url(urlStr)
        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile; rv:125.0) Gecko/125.0 Firefox/125.0")
        .build()
      client.newCall(request).execute().use { response ->
        if (response.isSuccessful) {
          val body = response.body?.string().orEmpty()
          val parsed = parseTranslationResponse(body)
          if (parsed.isNotBlank()) return parsed
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Route-authorized translation attempt failed: ${e.message}")
      // Ghost mode must fail closed rather than fall back to a potentially direct Gecko request.
      if (isGhost && !CurrentTorRoute.isReady) return text
    }

    // Gecko fallback is only acceptable for non-Ghost mode.
    if (isGhost) return text

    try {
      val runtime = com.remmi.browser.engine.GeckoEngineManager.getInstance(context).runtime
      if (runtime != null) {
        val executor = GeckoWebExecutor(runtime)
        val request = WebRequest.Builder(urlStr)
          .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile; rv:125.0) Gecko/125.0 Firefox/125.0")
          .build()

        val response = executor.fetch(request).poll(6000)
        if (response != null && response.statusCode == 200) {
          val bodyStream = response.body
          if (bodyStream != null) {
            val body = Scanner(bodyStream, "UTF-8").useDelimiter("\\A").next()
            bodyStream.close()
            val parsed = parseTranslationResponse(body)
            if (parsed.isNotBlank()) return parsed
          }
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Gecko executor translation error", e)
    }

    return text
  }

  private fun parseTranslationResponse(body: String): String {
    return try {
      val jsonArray = JSONArray(body)
      val sentencesArray = jsonArray.optJSONArray(0) ?: return ""
      val sb = StringBuilder()
      for (i in 0 until sentencesArray.length()) {
        val sentenceObj = sentencesArray.optJSONArray(i)
        if (sentenceObj != null && sentenceObj.length() > 0) {
          sb.append(sentenceObj.optString(0))
        }
      }
      sb.toString().trim()
    } catch (e: Exception) {
      ""
    }
  }

  /**
   * Translates a single text string to target language
   */
  suspend fun translateText(context: Context, text: String, targetLanguageCode: String, isGhost: Boolean = false): String = withContext(Dispatchers.IO) {
    if (text.isBlank()) return@withContext ""
    if (isGhost && !com.remmi.browser.security.CurrentTorRoute.isReady) {
      Log.w(TAG, "Ghost translation blocked: Tor route is not verified")
      return@withContext text
    }

    try {
      kotlinx.coroutines.withTimeout(8000L) {
        val result = executeTranslationRequest(context, text, targetLanguageCode, isGhost)
        if (result.isNotBlank()) result else text
      }
    } catch (e: Exception) {
      Log.e(TAG, "Translation error for language $targetLanguageCode", e)
      text
    }
  }

  /**
   * Translates full ReaderArticle using smart paragraph batching & parallel coroutines for 30x faster response
   */
  suspend fun translateArticle(
    context: Context,
    article: ReaderArticle,
    targetLanguageCode: String,
    isGhost: Boolean = false,
    onProgress: (Int, Int) -> Unit = { _, _ -> }
  ): ReaderArticle = withContext(Dispatchers.IO) {
    if (isGhost && !com.remmi.browser.security.CurrentTorRoute.isReady) {
      Log.w(TAG, "Ghost article translation blocked: Tor route is not verified")
      return@withContext article
    }

    val lang = SUPPORTED_LANGUAGES.firstOrNull { it.code == targetLanguageCode }?.displayName ?: targetLanguageCode
    val boundedParagraphs = article.paragraphs.take(200)

    // Parallel step 1: Translate title asynchronously
    val titleDeferred = async {
      if (article.title.isNotBlank()) {
        translateText(context, article.title, targetLanguageCode, isGhost)
      } else {
        article.title
      }
    }

    // Step 2: Batch paragraphs into chunks of up to ~2500 characters
    val batches = mutableListOf<List<ReaderParagraph>>()
    var currentBatch = mutableListOf<ReaderParagraph>()
    var currentBatchCharCount = 0

    for (para in boundedParagraphs) {
      val paraLen = para.text.length
      if (currentBatch.isNotEmpty() && (currentBatchCharCount + paraLen > 2500 || currentBatch.size >= 12)) {
        batches.add(currentBatch)
        currentBatch = mutableListOf()
        currentBatchCharCount = 0
      }
      currentBatch.add(para)
      currentBatchCharCount += paraLen
    }
    if (currentBatch.isNotEmpty()) {
      batches.add(currentBatch)
    }

    val totalBatches = batches.size
    var completedBatches = 0
    onProgress(1, maxOf(1, totalBatches))

    // Step 3: Translate all batches in parallel
    val translatedBatchResults = batches.mapIndexed { batchIdx, batch ->
      async {
        val combinedText = batch.joinToString(separator = PARA_DELIMITER) { it.text }
        val translatedCombined = executeTranslationRequest(context, combinedText, targetLanguageCode, isGhost)
        val splitParts = translatedCombined.split(DELIMITER_REGEX)

        val resultParas = if (splitParts.size == batch.size) {
          batch.mapIndexed { idx, p ->
            p.copy(text = splitParts[idx].trim().ifBlank { p.text })
          }
        } else {
          // Fallback: Translate individual paragraphs in parallel if delimiter was altered
          batch.map { p ->
            val singleTrans = executeTranslationRequest(context, p.text, targetLanguageCode, isGhost)
            p.copy(text = singleTrans.ifBlank { p.text })
          }
        }

        synchronized(batches) {
          completedBatches++
          onProgress(completedBatches, totalBatches)
        }
        resultParas
      }
    }.awaitAll()

    val translatedParas = translatedBatchResults.flatten()
    val translatedTitle = titleDeferred.await()

    article.copy(
      translatedTitle = translatedTitle,
      translatedParagraphs = translatedParas,
      targetLanguage = lang,
    )
  }

  /**
   * Launch external Google Translate intent for a web URL or text snippet
   */
  fun launchExternalTranslator(context: Context, urlOrText: String, isGhost: Boolean = false) {
    if (isGhost) {
      Log.w(TAG, "External intent blocked in Ghost mode to prevent IP leak via ACTION_VIEW")
      return
    }
    try {
      val targetUri = if (urlOrText.startsWith("http://") || urlOrText.startsWith("https://")) {
        Uri.parse("https://translate.google.com/translate?sl=auto&tl=hi&u=${URLEncoder.encode(urlOrText, "UTF-8")}")
      } else {
        Uri.parse("https://translate.google.com/?sl=auto&tl=hi&text=${URLEncoder.encode(urlOrText, "UTF-8")}&op=translate")
      }
      val intent = Intent(Intent.ACTION_VIEW, targetUri)
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(intent)
    } catch (e: Exception) {
      Log.w(TAG, "Failed to launch external translator", e)
    }
  }
}

