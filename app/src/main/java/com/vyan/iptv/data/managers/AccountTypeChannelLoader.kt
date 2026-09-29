package com.vyan.iptv.data.managers

import android.content.Context
import android.net.Uri
import com.vyan.iptv.models.AccountType
import com.vyan.iptv.models.LiveCategory
import com.vyan.iptv.models.XtreamLiveChannelModel
import com.vyan.iptv.models.M3uChannel
import com.vyan.iptv.models.UserAccount
import com.vyan.iptv.network.XtreamApi
import com.vyan.iptv.stream.PlaylistImportParser
import com.vyan.iptv.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * THE single place that knows how to turn a [UserAccount] into Live TV data, regardless of
 * its [AccountType]. Every screen that previously did:
 *
 *   if (account.type == AccountType.XTREAM) { ...call XtreamApi... }
 *   else { ...fetch text, call PlaylistImportParser... }
 *
 * should call one of the functions below instead. Do not re-add per-screen `when (account.type)`
 * branches for fetching — add a new branch here and every screen gets it for free.
 *
 * Where each type's data actually comes from:
 *   AccountType.XTREAM   -> XtreamApi   (server-side categories + per-category streams)
 *   AccountType.M3U_URL  -> fetched over network, then PlaylistImportParser (client-side grouping)
 *   AccountType.M3U_FILE -> read from local file, then PlaylistImportParser (client-side grouping)
 */
object AccountTypeChannelLoader {

    /**
     * Result of a full-playlist parse for M3U_URL / M3U_FILE accounts: the derived categories
     * (one per playlist "group") plus every channel already bucketed by that category id.
     * XTREAM accounts don't need this shape since categories/streams are fetched separately
     * from the server — see [loadLiveCategories] and [loadLiveChannelsForCategory].
     */
    data class ParsedPlaylist(
        val categories: List<LiveCategory>,
        val channelsByCategoryId: Map<String, List<XtreamLiveChannelModel>>
    )

    /**
     * Fetches just the category list for [account]. For M3U types this also parses the whole
     * playlist (there's no server-side category endpoint for M3U), so prefer
     * [loadLiveCategoriesAndChannels] when you'll need the channels too — it avoids parsing twice.
     */
    suspend fun loadLiveCategories(context: Context, account: UserAccount): List<LiveCategory> =
        when (account.type) {
            AccountType.XTREAM -> fetchXtreamCategories(account)
            AccountType.M3U_URL, AccountType.M3U_FILE -> loadLiveCategoriesAndChannels(context, account).categories
        }

    /**
     * Fetches categories AND channels in one pass. For XTREAM, channels are left empty here
     * (fetch per-category via [loadLiveChannelsForCategory] instead, since the API supports
     * server-side filtering). For M3U types, the whole playlist has to be parsed anyway, so both
     * come back together, already grouped.
     */
    suspend fun loadLiveCategoriesAndChannels(context: Context, account: UserAccount): ParsedPlaylist =
        when (account.type) {
            AccountType.XTREAM -> ParsedPlaylist(fetchXtreamCategories(account), emptyMap())
            AccountType.M3U_URL, AccountType.M3U_FILE -> parsePlaylistAccount(context, account)
        }

    /**
     * Fetches channels for a single category. For XTREAM this calls the API with the category
     * filter. For M3U types there's no server to filter with, so the caller should have already
     * populated DataCache via [loadLiveCategoriesAndChannels] — this falls back to an empty list
     * if that hasn't happened yet, rather than re-parsing the whole playlist per category.
     */
    suspend fun loadLiveChannelsForCategory(account: UserAccount, categoryId: String): List<XtreamLiveChannelModel> =
        when (account.type) {
            AccountType.XTREAM -> fetchXtreamChannels(account, categoryId)
            AccountType.M3U_URL, AccountType.M3U_FILE -> emptyList()
        }

    /**
     * Every channel across every category, flattened - for flows that need to search/scan the
     * whole account at once (Unified Live TV search, Auto-channel aggregation) instead of
     * per-category browsing. For XTREAM this is one unfiltered API call; for M3U types it's the
     * full parsed playlist.
     */
    suspend fun loadAllLiveChannelsFlat(context: Context, account: UserAccount): List<XtreamLiveChannelModel> =
        when (account.type) {
            AccountType.XTREAM -> fetchXtreamChannels(account, categoryId = null)
            AccountType.M3U_URL, AccountType.M3U_FILE ->
                parsePlaylistAccount(context, account).channelsByCategoryId.values.flatten()
        }

    // ---- XTREAM ----

    private suspend fun fetchXtreamCategories(account: UserAccount): List<LiveCategory> =
        withContext(Dispatchers.IO) {
            try {
                val response = XtreamApi.service.getLiveCategories(
                    XtreamApi.formatApiUrl(account.url), account.username, account.pass
                )
                if (response.isSuccessful) response.body() ?: emptyList() else emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }

    private suspend fun fetchXtreamChannels(account: UserAccount, categoryId: String?): List<XtreamLiveChannelModel> =
        withContext(Dispatchers.IO) {
            try {
                val response = XtreamApi.service.getLiveStreams(
                    XtreamApi.formatApiUrl(account.url), account.username, account.pass, categoryId
                )
                if (response.isSuccessful) response.body() ?: emptyList() else emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }

    // ---- M3U_URL / M3U_FILE ----

    /** Reads the raw playlist text for an M3U_URL or M3U_FILE account. */
    suspend fun fetchPlaylistContent(context: Context, account: UserAccount): String =
        withContext(Dispatchers.IO) {
            try {
                if (account.type == AccountType.M3U_URL) {
                    NetworkClient.defaultClient.newCall(Request.Builder().url(account.url).build())
                        .execute().body?.string() ?: ""
                } else {
                    context.contentResolver.openInputStream(Uri.parse(account.localFilePath))
                        ?.bufferedReader()?.use { it.readText() } ?: ""
                }
            } catch (_: Exception) {
                ""
            }
        }

    private suspend fun parsePlaylistAccount(context: Context, account: UserAccount): ParsedPlaylist =
        withContext(Dispatchers.IO) {
            val content = fetchPlaylistContent(context, account)
            val parsedChannels = PlaylistImportParser.parse(content)

            val channelsByGroup = parsedChannels.groupBy { it.group.ifEmpty { "Uncategorized" } }
            val categories = channelsByGroup.keys.map { LiveCategory(category_id = it, category_name = it, parent_id = 0) }
            val channelsByCategoryId = channelsByGroup.mapValues { (groupId, m3uChannels) ->
                m3uChannels.map { it.toLiveChannel(groupId) }
            }

            ParsedPlaylist(categories, channelsByCategoryId)
        }

    private fun M3uChannel.toLiveChannel(categoryId: String): XtreamLiveChannelModel {
        val uniqueHashId = ("${url}_$name").hashCode()
        return XtreamLiveChannelModel(
            num = 0,
            name = name,
            stream_type = "live",
            stream_id = uniqueHashId,
            stream_icon = logo,
            epg_channel_id = tvgId,
            added = "",
            category_id = categoryId,
            custom_sid = "",
            tv_archive = 0,
            direct_source = url,
            tv_archive_duration = 0
        )
    }
}