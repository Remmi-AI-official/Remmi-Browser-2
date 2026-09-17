package com.remmi.browser.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class OnionFallbackUnitTest {

    @Test
    fun testOnionFallbackUrlGeneration() {
        val httpsOnion = "https://digdig2nugjpszzmqe5ep2bk7lqfpdlyrkojsx2j6kzalnrqtwedr3id.onion/chat/ee6500c4#c"
        assertTrue(httpsOnion.startsWith("https://", ignoreCase = true))
        assertTrue(httpsOnion.contains(".onion", ignoreCase = true))

        val fallback = "http://" + httpsOnion.substring(8)
        assertEquals("http://digdig2nugjpszzmqe5ep2bk7lqfpdlyrkojsx2j6kzalnrqtwedr3id.onion/chat/ee6500c4#c", fallback)
    }

    @Test
    fun testOfflineErrorPageGenerationForBadCert() {
        val targetUrl = "https://digdig2nugjpszzmqe5ep2bk7lqfpdlyrkojsx2j6kzalnrqtwedr3id.onion/chat/ee6500c4#c"
        val dataUri = OfflineErrorPageGenerator.toDataUri(
            targetUrl = targetUrl,
            errorCode = "ERR_CERT_COMMON_NAME_INVALID",
            isDark = true
        )
        assertNotNull(dataUri)
        assertTrue(dataUri.startsWith("data:text/html;charset=utf-8,"))
        assertTrue(dataUri.contains("digdig2nugjpszzmqe5ep2bk7lqfpdlyrkojsx2j6kzalnrqtwedr3id.onion"))
    }
}
