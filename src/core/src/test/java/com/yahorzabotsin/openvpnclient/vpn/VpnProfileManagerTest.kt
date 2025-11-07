package com.yahorzabotsin.openvpnclient.vpn

import android.content.Context
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ProfileManager
import org.junit.Assert.*`nimport de.blinkt.openvpn.core.VpnStatus
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.*

@RunWith(RobolectricTestRunner::class)
class VpnProfileManagerTest {
    private lateinit var context: Context
    private lateinit var profileManager: ProfileManager
    private lateinit var testProfile: VpnProfile

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        profileManager = ProfileManager.getInstance(context)
        
        // Create a test profile
        testProfile = VpnProfile("TestProfile")
        testProfile.mServerName = "test.example.com"
        testProfile.mUsername = "testuser"
    }

    @Test
    fun `adds and retrieves profile`() {
        profileManager.addProfile(testProfile)
        profileManager.saveProfileList(context)
        
        val retrieved = profileManager.getProfileByName("TestProfile")
        assertNotNull(retrieved)
        assertEquals(testProfile.mServerName, retrieved?.mServerName)
        assertEquals(testProfile.mUsername, retrieved?.mUsername)
    }

    @Test
    fun `updates existing profile`() {
        // Add initial profile
        profileManager.addProfile(testProfile)
        profileManager.saveProfileList(context)
        val initialVersion = testProfile.mVersion

        // Update profile
        testProfile.mServerName = "updated.example.com"
        ProfileManager.saveProfile(context, testProfile)

        // Verify update by forcing a reload from disk
        val retrieved = ProfileManager.get(context, testProfile.getUUIDString(), initialVersion + 1, 1)
        assertNotNull(retrieved)
        assertEquals("updated.example.com", retrieved?.mServerName)
    }

    @Test
    fun `remembers last connected profile`() {
        profileManager.addProfile(testProfile)
        profileManager.saveProfileList(context)

        // Set as connected
        ProfileManager.setConnectedVpnProfile(context, testProfile)

        // Verify last connected
        val lastConnected = ProfileManager.getLastConnectedProfile(context)
        assertNotNull(lastConnected)
        assertEquals(testProfile.getUUID(), lastConnected?.getUUID())
    }

    @Test
    fun `handles temporary profiles`() {
        // Create temporary profile
        ProfileManager.setTemporaryProfile(context, testProfile)
        ProfileManager.setConnectedVpnProfile(context, testProfile)

        assertTrue(testProfile.mTemporaryProfile)
        assertTrue(ProfileManager.isTempProfile())

        // Should still be retrievable
        val tempProfile = ProfileManager.get(context, testProfile.getUUIDString())
        assertNotNull(tempProfile)
        assertTrue(tempProfile?.mTemporaryProfile ?: false)
    }

    @Test
    fun `clones profile correctly`() {
        // Original profile
        profileManager.addProfile(testProfile)

        // Clone profile
        val clonedProfile = testProfile.copy("ClonedProfile")
        assertNotNull(clonedProfile)
        
        // Verify clone has different UUID but same settings
        assertNotEquals(testProfile.getUUID(), clonedProfile?.getUUID())
        assertEquals(testProfile.mServerName, clonedProfile?.mServerName)
        assertEquals(testProfile.mUsername, clonedProfile?.mUsername)
        assertEquals("ClonedProfile", clonedProfile?.getName())
    }

    @Test
    fun `maintains profile version on update`() {
        // Add initial profile
        profileManager.addProfile(testProfile)
        profileManager.saveProfileList(context)
        val initialVersion = testProfile.mVersion

        // Update profile
        testProfile.mServerName = "newserver.example.com"
        ProfileManager.saveProfile(context, testProfile)

        // Version should be incremented by forcing a reload from disk
        val retrieved = ProfileManager.get(context, testProfile.getUUIDString(), initialVersion + 1, 1)
        assertNotNull(retrieved)
        assertTrue(retrieved?.mVersion ?: 0 > initialVersion)
    }

    @Test
fun `notifies version changes`() {
    profileManager.addProfile(testProfile)
    profileManager.saveProfileList(context)

    var received: Triple<String, Int, Boolean>? = null
    val listener = object : VpnStatus.ProfileNotifyListener {
        override fun notifyProfileVersionChanged(uuid: String, version: Int, changedInThisProcess: Boolean) {
            received = Triple(uuid, version, changedInThisProcess)
        }
    }
    try {
        VpnStatus.addProfileStateListener(listener)

        testProfile.mServerName = "changed.example.com"
        ProfileManager.saveProfile(context, testProfile)

        ProfileManager.notifyProfileVersionChanged(
            context,
            testProfile.getUUIDString(),
            testProfile.mVersion
        )

        assertNotNull("Expected profile version change notification", received)
        assertEquals(testProfile.getUUIDString(), received!!.first)
        assertEquals(testProfile.mVersion, received!!.second)
    } finally {
        VpnStatus.removeProfileStateListener(listener)
    }
}






