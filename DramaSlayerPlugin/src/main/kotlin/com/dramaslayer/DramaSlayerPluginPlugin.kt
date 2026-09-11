package com.dramaslayer

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class DramaSlayerPluginPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(DramaSlayerPlugin())
    }
}
