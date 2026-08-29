package com.vyan.xtreamplayer.models

import com.google.gson.annotations.SerializedName

data class UserInfo(
    @SerializedName("username") val username: String? = null,
    @SerializedName("password") val password: String? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("auth") val auth: Any? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("exp_date") val expDate: String? = null,
    @SerializedName("active_cons") val activeCons: Any? = null,
    @SerializedName("max_connections") val maxConnections: Any? = null
) {
    fun isValid(): Boolean {
        if (auth == 1.0 || auth == 1 || auth == "1") return true
        if (status?.lowercase() == "active" || status == "1") return true
        if (auth == 0.0 || auth == 0 || auth == "0") return false
        if (status?.lowercase() == "disabled" || status?.lowercase() == "expired") return false
        return !username.isNullOrEmpty()
    }
}

data class ServerInfo(
    @SerializedName("url") val url: String? = null,
    @SerializedName("port") val port: String? = null,
    @SerializedName("https_port") val httpsPort: String? = null,
    @SerializedName("server_protocol") val serverProtocol: String? = null,
    @SerializedName("rtmp_port") val rtmpPort: String? = null,
    @SerializedName("timezone") val timezone: String? = null
)

data class LoginResponse(
    @SerializedName("user_info") val userInfo: UserInfo? = null,
    @SerializedName("server_info") val serverInfo: ServerInfo? = null
)

