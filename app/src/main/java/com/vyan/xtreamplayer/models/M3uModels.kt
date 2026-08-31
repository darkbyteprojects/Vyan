package com.vyan.xtreamplayer.models

data class M3uChannel(
    val name: String,
    val url: String,
    val logo: String = "",
    val group: String = "",
    val tvgId: String = "",
    val tvgName: String = "",
    val userAgent: String = "",
    val cookie: String = "",
    val keyId: String = "",
    val key: String = ""
)

data class M3uPlaylist(
    val id: String,
    val name: String,
    val sourceUrl: String?, // Null if uploaded from a local file
    val addedAt: Long,
    val updatedAt: Long,
    val channels: List<M3uChannel>
)