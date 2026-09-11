package com.dramaslayer

interface DramaSlayerInfProvider {
    suspend fun getInf(): String
}

object EmptyDramaSlayerInfProvider : DramaSlayerInfProvider {
    override suspend fun getInf(): String = ""
}
