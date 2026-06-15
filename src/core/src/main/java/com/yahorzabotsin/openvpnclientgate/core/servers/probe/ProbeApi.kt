package com.yahorzabotsin.openvpnclientgate.core.servers.probe

import retrofit2.Response
import retrofit2.http.POST
import retrofit2.http.Path

interface ProbeApi {
    @POST("api/v2/servers/{id}/probe")
    suspend fun probe(@Path("id") serverId: Int): Response<Unit>
}
