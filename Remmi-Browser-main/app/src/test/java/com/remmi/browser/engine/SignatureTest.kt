package com.remmi.browser.engine

import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoResult

class SignatureTest : GeckoSession.NavigationDelegate {
    override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
        return null
    }
}
