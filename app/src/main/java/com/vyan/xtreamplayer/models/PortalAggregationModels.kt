package com.vyan.xtreamplayer.models

enum class IptvSection { LIVE, VOD, SERIES }

data class IptvPortal(
    val url: String,
    val username: String,
    val pass: String,
    val source: String = ""
) {
    val key: String get() = "${url}|$username|$pass".lowercase()
    val credKey: String get() = "$username|$pass".lowercase()
}

data class VerifiedPortal(
    val portal: IptvPortal,
    val name: String,
    val expiry: String,
    val maxConnections: String = "1",
    val activeConnections: String = "0"
)

data class IptvCategory(
    val id: String,
    val name: String
)

data class IptvStream(
    val streamId: String,
    val name: String,
    val icon: String,
    val categoryId: String,
    val containerExt: String,
    val kind: String, // "live", "vod", "series"
    val epgChannelId: String = ""
)

data class EpgEntry(
    val title: String,
    val description: String,
    val startMs: Long,
    val stopMs: Long
) {
    val isNow: Boolean
        get() {
            val now = System.currentTimeMillis()
            return now in startMs..stopMs
        }
}

data class IptvEpisode(
    val id: String,
    val title: String,
    val containerExt: String,
    val season: Int,
    val episode: Int,
    val plot: String,
    val image: String
)