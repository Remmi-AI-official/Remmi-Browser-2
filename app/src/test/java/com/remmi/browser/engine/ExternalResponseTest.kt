package com.remmi.browser.engine

import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.WebResponse
import org.junit.Test

class ExternalResponseTest : GeckoSession.ContentDelegate {
    override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
    }

    @Test
    fun test() {
    }
}
