package com.vyan.xtreamplayer.models

import com.vyan.xtreamplayer.utils.PlaylistFormat

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
    // Raw ClearKey key value. Only populated for Format 3 / JSON playlists,
    // where the key material is given to us directly instead of behind a
    // license server URL (which is what `keyId` holds for Format 1 / 2).
    val key: String = "",
    // Which source format this channel was parsed from. StreamFormatNormalizer
    // uses this to pick the matching (non-shared) normalize path — see
    // PlaylistFormat for why the split matters.
    val format: PlaylistFormat = PlaylistFormat.UNKNOWN
)

data class M3uPlaylist(
    val id: String,
    val name: String,
    val sourceUrl: String?, // Null if uploaded from a local file
    val addedAt: Long,
    val updatedAt: Long,
    val channels: List<M3uChannel>
)