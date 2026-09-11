package com.remmi.browser.engine

import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoResult
import org.junit.Test

class LoadErrorTest : GeckoSession.NavigationDelegate {
    override fun onLoadError(session: GeckoSession, uri: String?, error: org.mozilla.geckoview.WebRequestError): GeckoResult<String>? {
        return null
    }

    @Test
    fun test() {
    }
}
