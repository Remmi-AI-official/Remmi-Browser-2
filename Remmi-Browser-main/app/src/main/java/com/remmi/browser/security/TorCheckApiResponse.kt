package com.remmi.browser.security

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Strict JSON schema for check.torproject.org/api/ip response.
 */
@JsonClass(generateAdapter = true)
data class TorCheckApiResponse(
  @Json(name = "IsTor")
  val isTor: Boolean = false,
  @Json(name = "IP")
  val ip: String? = null
)
