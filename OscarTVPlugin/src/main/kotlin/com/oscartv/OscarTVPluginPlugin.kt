package com.oscartv

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class OscarTVPluginPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(OscarTVPlugin())
    }
}
