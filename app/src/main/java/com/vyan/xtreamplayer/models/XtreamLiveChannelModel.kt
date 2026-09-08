package com.vyan.xtreamplayer.models

import com.google.gson.annotations.SerializedName

data class XtreamLiveChannelModel(
    @SerializedName("num") val num: Int,
    @SerializedName("name") val name: String,
    @SerializedName("stream_type") val stream_type: String,
    @SerializedName("stream_id") val stream_id: Int,
    @SerializedName("stream_icon") val stream_icon: String?,
    @SerializedName("epg_channel_id") val epg_channel_id: String?,
    @SerializedName("added") val added: String?,
    @SerializedName("category_id") val category_id: String,
    @SerializedName("custom_sid") val custom_sid: String?,
    @SerializedName("tv_archive") val tv_archive: Int,
    @SerializedName("direct_source") val direct_source: String?,
    @SerializedName("tv_archive_duration") val tv_archive_duration: Int
)