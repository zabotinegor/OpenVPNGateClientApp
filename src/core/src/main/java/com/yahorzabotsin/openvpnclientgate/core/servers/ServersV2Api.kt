package com.yahorzabotsin.openvpnclientgate.core.servers

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

data class ServersPageResponse(
    @SerializedName("items") val items: List<ServerV2>?,
    @SerializedName("total") val total: Int = 0
)

interface ServersV2Api {
    @GET("api/v2/servers/countries/active")
    suspend fun getCountries(): List<CountryV2>

    @GET("api/v2/servers")
    suspend fun getServers(
        @Query("countryCode") countryCode: String,
        @Query("isActive") isActive: Boolean = true,
        @Query("skip") skip: Int = 0,
        @Query("take") take: Int = 50
    ): ServersPageResponse
}
