package com.remmi.browser.downloads

import org.mozilla.geckoview.WebResponse

/**
 * Data holder representing a pending download confirmation request.
 */
data class DownloadConfirmationRequest(
  val id: Long = System.currentTimeMillis(),
  val url: String,
  val suggestedFilename: String,
  val mimeType: String = "application/octet-stream",
  val contentLength: Long = 0L,
  val isGhost: Boolean = false,
  val webResponse: WebResponse? = null,
  val onConfirm: (finalFilename: String) -> Unit,
  val onCancel: () -> Unit = {}
)
