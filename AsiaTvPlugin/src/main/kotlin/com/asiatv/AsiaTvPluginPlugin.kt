package com.asiatv

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AsiaTvPluginPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AsiaTvPlugin())
    }
}
