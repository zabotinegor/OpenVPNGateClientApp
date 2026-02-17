package com.yahorzabotsin.openvpnclientgate.core.ui.main

import com.yahorzabotsin.openvpnclientgate.core.versions.LatestReleaseInfo
import com.yahorzabotsin.openvpnclientgate.core.versions.VersionReleaseRepository

interface VersionReleaseInteractor {
    suspend fun loadLatestRelease(): LatestReleaseInfo?
}

class DefaultVersionReleaseInteractor(
    private val repository: VersionReleaseRepository
) : VersionReleaseInteractor {
    override suspend fun loadLatestRelease(): LatestReleaseInfo? = repository.getLatestRelease()
}
