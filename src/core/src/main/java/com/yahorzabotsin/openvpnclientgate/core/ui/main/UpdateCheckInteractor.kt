package com.yahorzabotsin.openvpnclientgate.core.ui.main

import com.yahorzabotsin.openvpnclientgate.core.updates.AppUpdateInfo
import com.yahorzabotsin.openvpnclientgate.core.updates.UpdateCheckRepository

interface UpdateCheckInteractor {
    suspend fun check(forceRefresh: Boolean = false): AppUpdateInfo?
}

class DefaultUpdateCheckInteractor(
    private val repository: UpdateCheckRepository
) : UpdateCheckInteractor {
    override suspend fun check(forceRefresh: Boolean): AppUpdateInfo? = repository.checkForUpdate(forceRefresh)
}
