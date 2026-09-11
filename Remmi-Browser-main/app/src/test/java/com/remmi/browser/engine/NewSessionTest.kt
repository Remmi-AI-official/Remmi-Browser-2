package com.remmi.browser.engine

import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoResult
import org.junit.Test

class NewSessionTest : GeckoSession.NavigationDelegate {
    override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
        return null
    }

    @Test
    fun test() {
    }
}
