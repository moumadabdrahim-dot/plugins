package com.asiatv

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AsiaTvPluginPlugin : CloudstreamPlugin() {
    override fun load() {
        registerMainAPI(AsiaTvPlugin())
    }
}
