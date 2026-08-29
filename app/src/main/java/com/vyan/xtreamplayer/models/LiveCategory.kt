package com.vyan.xtreamplayer.models

import com.google.gson.annotations.SerializedName

data class LiveCategory(
    @SerializedName("category_id") val category_id: String,
    @SerializedName("category_name") val category_name: String,
    @SerializedName("parent_id") val parent_id: Int = 0
)

data class VodCategory(
    @SerializedName("category_id") val category_id: String,
    @SerializedName("category_name") val category_name: String,
    @SerializedName("parent_id") val parent_id: Int = 0
)

data class SeriesCategory(
    @SerializedName("category_id") val category_id: String,
    @SerializedName("category_name") val category_name: String,
    @SerializedName("parent_id") val parent_id: Int = 0
)

data class VodMovie(
    @SerializedName("stream_id") val stream_id: Int,
    @SerializedName("name") val name: String,
    @SerializedName("stream_icon") val stream_icon: String?,
    @SerializedName("rating") val rating: String?,
    @SerializedName("category_id") val category_id: String?,
    @SerializedName("container_extension") val container_extension: String? = "mp4"
)

data class SeriesItem(
    @SerializedName("series_id") val series_id: Int,
    @SerializedName("name") val name: String,
    @SerializedName("cover") val cover: String?,
    @SerializedName("plot") val plot: String?,
    @SerializedName("genre") val genre: String?,
    @SerializedName("rating") val rating: String?,
    @SerializedName("category_id") val category_id: String?
)

data class Episode(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("container_extension") val container_extension: String? = "mp4",
    @SerializedName("episode_num") val episode_num: Int = 1,
    @SerializedName("season") val season: Int = 1,
    @SerializedName("info") val info: EpisodeInfo?
)

data class EpisodeInfo(
    @SerializedName("plot") val plot: String?,
    @SerializedName("duration") val duration: String?
)

data class SeriesDetailsResponse(
    @SerializedName("seasons") val seasons: List<Map<String, Any>>?,
    @SerializedName("info") val info: Map<String, Any>?,
    @SerializedName("episodes") val episodes: Map<String, List<Episode>>?
)
