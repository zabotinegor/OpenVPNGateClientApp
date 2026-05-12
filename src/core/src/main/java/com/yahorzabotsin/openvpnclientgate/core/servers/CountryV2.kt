package com.yahorzabotsin.openvpnclientgate.core.servers

import com.google.gson.annotations.SerializedName

data class CountryV2(
    @SerializedName("code") val code: String,
    @SerializedName("name") val name: String,
    @SerializedName("serverCount") val serverCount: Int
)
