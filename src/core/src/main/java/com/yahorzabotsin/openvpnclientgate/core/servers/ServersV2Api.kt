package com.yahorzabotsin.openvpnclientgate.core.servers

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Query

interface ServersV2Api {
    @GET("api/v2/servers/countries/active")
    suspend fun getCountries(): ResponseBody

    @GET("api/v2/servers")
    suspend fun getServers(
        @Query("countryCode") countryCode: String,
        @Query("isActive") isActive: Boolean = true,
        @Query("skip") skip: Int = 0,
        @Query("take") take: Int = 50
    ): ResponseBody
}
