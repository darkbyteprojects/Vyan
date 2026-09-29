package com.vyan.iptv.models

enum class AccountType {
    XTREAM,
    M3U_URL,
    M3U_FILE
}

data class UserAccount(
    val id: String = System.currentTimeMillis().toString(),
    val url: String,
    val username: String = "",
    val pass: String = "",
    val alias: String = "",
    val type: AccountType = AccountType.XTREAM,
    val localFilePath: String = "",

    // New Playlist Settings from Image 1
    val useDefaultUserAgent: Boolean = true,
    val userAgent: String = "",
    val turnOnPlaylist: Boolean = true,
    val updateInterval: String = "Never",
    val enableChannels: Boolean = true,
    val enableMovies: Boolean = false,
    val enableSeries: Boolean = false,
    val streamType: String = "MPEG-TS (.ts)",
    val archiveDuration: String = "Auto",
    val loadEpg: Boolean = true,
    val useAllGuides: Boolean = true
)