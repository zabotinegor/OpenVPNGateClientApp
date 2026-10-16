package com.yahorzabotsin.openvpnclientgate.vpn

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.RemoteException
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.IStatusCallbacks
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.OpenVPNThread
import de.blinkt.openvpn.core.VpnStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression coverage for the orphaned-engine defect: the native OpenVPN tunnel is alive and the
 * engine reports LEVEL_CONNECTED, but its management channel is unusable, so
 * `engineBinder.stopVPN(false)` returns `false` forever.
 *
 * The first test is end to end: it uses the REAL engine service and binder, a real child process
 * standing in for the native tunnel, and the real status-callback path back into the controller.
 * The others drive the controller's decision logic through a counting binder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OpenVpnServiceOrphanedEngineStopTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val notifications get() = shadowOf(app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
    private val threads = CopyOnWriteArrayList<Thread>()
    private val bridges = CopyOnWriteArrayList<VpnStatus.StateListener>()

    @Before
    fun setUp() {
        ConnectionStateManager.setReconnectingHint(false)
        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_NOTCONNECTED, null)
        ConnectionStateManager.clearStopFailure()
        VpnManager.resetActionStartDispatchTrackingForTest()
        app.getSharedPreferences("vpn_stop_teardown", Context.MODE_PRIVATE).edit().clear().apply()
    }

    @After
    fun tearDown() {
        threads.forEach { it.interrupt() }
        bridges.forEach { VpnStatus.removeStateListener(it) }
        ConnectionStateManager.clearStopFailure()
        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_NOTCONNECTED, null)
    }

    private class CountingBinder(
        private val stopResult: Boolean = false,
        private val forceResult: Int = 1,
        private val forceThrows: Boolean = false
    ) : IOpenVPNServiceInternal.Stub() {
        val stopCalls = AtomicInteger()
        val forceCalls = AtomicInteger()
        override fun protect(fd: Int) = false
        override fun userPause(b: Boolean) {}
        override fun stopVPN(replaceConnection: Boolean): Boolean {
            stopCalls.incrementAndGet()
            return stopResult
        }

        override fun forceStopVPN(): Int {
            forceCalls.incrementAndGet()
            if (forceThrows) throw RemoteException("engine gone")
            return forceResult
        }

        override fun addAllowedExternalApp(packagename: String?) {}
        override fun isAllowedExternalApp(packagename: String?) = false
        override fun challengeResponse(repsonse: String?) {}
    }

    private fun controllerWithConnectedEngine(binder: IOpenVPNServiceInternal): OpenVpnService {
        val service = Robolectric.buildService(OpenVpnService::class.java).create().get()
        // Engine process survived: it keeps reporting CONNECTED to the (re)created controller.
        reportEngineLevel(service, ConnectionStatus.LEVEL_CONNECTED)
        ReflectionHelpers.setField(service, "engineBinder", binder)
        ReflectionHelpers.setField(service, "boundToEngine", true)
        return service
    }

    /** The production status path: the engine's AIDL status callback. */
    private fun reportEngineLevel(service: OpenVpnService, level: ConnectionStatus) {
        val callbacks = ReflectionHelpers.getField<IStatusCallbacks>(service, "statusCallbacks")
        callbacks.updateStateString(level.name, null, 0, level, null)
    }

    private fun stopIntent() = Intent(app, OpenVpnService::class.java).apply {
        putExtra(VpnManager.actionKey(app), VpnManager.ACTION_STOP)
    }

    private fun startIntent() = Intent(app, OpenVpnService::class.java).apply {
        putExtra(VpnManager.actionKey(app), VpnManager.ACTION_START)
        putExtra(VpnManager.extraConfigKey(app), "client\n")
        putExtra(VpnManager.extraTitleKey(app), "RU")
    }

    private fun sleeperArgv(): Array<String> {
        val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        val argv = if (windows) arrayOf("ping", "-n", "60", "127.0.0.1") else arrayOf("sleep", "60")
        val found = System.getenv("PATH").orEmpty().split(File.pathSeparator).any {
            File(it, argv[0]).exists() || File(it, argv[0] + ".exe").exists()
        }
        assumeTrue("needs a ${argv[0]} binary on PATH", found)
        return argv
    }

    private fun waitUntil(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting for: $what")
            ShadowLooper.idleMainLooper()
            Thread.sleep(25)
        }
    }

    private fun hasControllerNotification() = notifications.allNotifications.isNotEmpty()

    // Acceptance: connected engine + unusable management channel + user ACTION_STOP.
    @Test
    fun brokenManagementChannel_userStop_terminatesRealProcess_andEndsDisconnected() {
        val argv = sleeperArgv()
        val engine = Robolectric.buildService(OpenVPNService::class.java).create().get()
        val process = OpenVPNThread(engine, argv, "/nonexistent", System.getProperty("java.io.tmpdir"))
        val processThread = Thread(process, "OpenVPNProcessThread").apply { isDaemon = true }
        threads.add(processThread)
        processThread.start()
        val nativeProcess: () -> Process? = { ReflectionHelpers.getField<Process?>(process, "mProcess") }
        waitUntil("native tunnel process started") { nativeProcess()?.isAlive == true }
        // The engine's view of the world: a live session whose management object is gone.
        ReflectionHelpers.setField(engine, "mCurrentOpenVPNProcess", process)
        ReflectionHelpers.setField(engine, "mProcessThread", processThread)
        val engineBinder = IOpenVPNServiceInternal.Stub.asInterface(engine.asBinder())
        assertFalse("Precondition: management stop is dead", engineBinder.stopVPN(false))

        val controller = controllerWithConnectedEngine(engineBinder)
        // In production OpenVPNStatusService pushes every VpnStatus update to the controller over
        // AIDL; this bridge is that hop, so the real engine state is what the controller sees.
        val callbacks = ReflectionHelpers.getField<IStatusCallbacks>(controller, "statusCallbacks")
        val bridge = object : VpnStatus.StateListener {
            override fun updateState(state: String?, logmessage: String?, localizedResId: Int, level: ConnectionStatus?, intent: Intent?) {
                if (level != null) callbacks.updateStateString(state, logmessage, localizedResId, level, intent)
            }

            override fun setConnectedVPN(uuid: String?) {}
        }
        VpnStatus.addStateListener(bridge)
        bridges.add(bridge)
        // The engine (really) says the tunnel is up; VpnStatus replays its last state on
        // registration, so report CONNECTED only after the bridge exists.
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        VpnStatus.updateStateString("CONNECTED", "", 0, ConnectionStatus.LEVEL_CONNECTED)
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)
        assertEquals(ConnectionState.CONNECTED, ConnectionStateManager.state.value)

        controller.onStartCommand(stopIntent(), 0, 1)

        assertTrue("Notification must stay while termination is unconfirmed", hasControllerNotification())
        waitUntil("native process terminated by the force-stop escalation") { nativeProcess()?.isAlive == false }
        waitUntil("app state DISCONNECTED confirmed by the engine's LEVEL_NOTCONNECTED") {
            ConnectionStateManager.state.value == ConnectionState.DISCONNECTED
        }
        assertFalse("No tunnel process may remain", nativeProcess()?.isAlive == true)
        assertEquals(ConnectionStateManager.VpnError.NONE, ConnectionStateManager.error.value)
        waitUntil("stop flow closed by the engine confirmation") {
            !ReflectionHelpers.getField<Boolean>(controller, "userInitiatedStop")
        }
        assertFalse(ReflectionHelpers.getField<Boolean>(controller, "stopForceAttempted"))
        assertEquals(ConnectionStateManager.VpnError.NONE, ConnectionStateManager.error.value)
    }

    @Test
    fun dispatchFalseWhileEngineConnected_escalatesOnceInsteadOfRetryingTheDeadPath() {
        val binder = CountingBinder(stopResult = false, forceResult = 1)
        val controller = controllerWithConnectedEngine(binder)

        controller.onStartCommand(stopIntent(), 0, 1)
        // Past every retry/confirmation timer: the dead path must still have been used only once.
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertEquals("management stop must not be retried", 1, binder.stopCalls.get())
        assertEquals("exactly one escalation", 1, binder.forceCalls.get())
        // No terminal level ever arrived: unconfirmed, recoverable, still visible.
        assertEquals(ConnectionStateManager.VpnError.STOP_FAILED, ConnectionStateManager.error.value)
        assertTrue("Notification must survive a failed teardown", hasControllerNotification())
        assertNotEquals(ConnectionState.DISCONNECTED, ConnectionStateManager.state.value)
    }

    @Test
    fun failedTeardown_userCanRetry_andGetsAFreshEscalation() {
        val binder = CountingBinder(stopResult = false, forceResult = 1)
        val controller = controllerWithConnectedEngine(binder)
        controller.onStartCommand(stopIntent(), 0, 1)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertEquals(ConnectionStateManager.VpnError.STOP_FAILED, ConnectionStateManager.error.value)

        controller.onStartCommand(stopIntent(), 0, 2)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertEquals(2, binder.stopCalls.get())
        assertEquals(2, binder.forceCalls.get())
        assertTrue(hasControllerNotification())
    }

    @Test
    fun repeatedStopWhileForceStopAwaitsConfirmation_isCoalesced() {
        val binder = CountingBinder(stopResult = false, forceResult = 1)
        val controller = controllerWithConnectedEngine(binder)

        controller.onStartCommand(stopIntent(), 0, 1)
        controller.onStartCommand(stopIntent(), 0, 2)
        controller.onStartCommand(stopIntent(), 0, 3)

        assertEquals(1, binder.stopCalls.get())
        assertEquals(1, binder.forceCalls.get())
        assertEquals(ConnectionState.DISCONNECTING, ConnectionStateManager.state.value)
    }

    @Test
    fun dispatchOfForceStopIsNotConfirmation_onlyTheEnginesTerminalLevelIs() {
        val binder = CountingBinder(stopResult = false, forceResult = 1)
        val controller = controllerWithConnectedEngine(binder)

        controller.onStartCommand(stopIntent(), 0, 1)
        assertEquals(1, binder.forceCalls.get())
        assertEquals(
            "A successful force-stop dispatch must not flip the app to DISCONNECTED",
            ConnectionState.DISCONNECTING, ConnectionStateManager.state.value
        )

        reportEngineLevel(controller, ConnectionStatus.LEVEL_NOTCONNECTED)

        assertEquals(ConnectionState.DISCONNECTED, ConnectionStateManager.state.value)
        assertEquals(ConnectionStateManager.VpnError.NONE, ConnectionStateManager.error.value)
    }

    @Test
    fun forceStopBinderFailure_keepsNotificationAndRecoverableState() {
        val binder = CountingBinder(stopResult = false, forceThrows = true)
        val controller = controllerWithConnectedEngine(binder)

        controller.onStartCommand(stopIntent(), 0, 1)

        assertEquals(ConnectionStateManager.VpnError.STOP_FAILED, ConnectionStateManager.error.value)
        assertTrue(hasControllerNotification())
        assertEquals(1, binder.forceCalls.get())
        // Recoverable: a later user stop dispatches again.
        controller.onStartCommand(stopIntent(), 0, 2)
        assertEquals(2, binder.forceCalls.get())
    }

    @Test
    fun staleEscalation_neverTerminatesALaterConnectionAttempt() {
        val binder = CountingBinder(stopResult = false, forceResult = 1)
        val controller = controllerWithConnectedEngine(binder)
        controller.onStartCommand(stopIntent(), 0, 1)
        assertEquals(1, binder.forceCalls.get())

        // The user reconnects before the engine confirmed the teardown.
        controller.onStartCommand(startIntent(), 0, 2)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertEquals("no further teardown work after the new start", 1, binder.forceCalls.get())
        assertEquals(1, binder.stopCalls.get())
        assertFalse(ReflectionHelpers.getField<Boolean>(controller, "userInitiatedStop"))
        assertFalse(ReflectionHelpers.getField<Boolean>(controller, "stopForceAttempted"))
        assertTrue(
            "The stale stop timers must not mark the new attempt as a failed stop",
            ConnectionStateManager.error.value != ConnectionStateManager.VpnError.STOP_FAILED
        )
    }

    @Test
    fun connectingEngine_dispatchFalse_keepsBoundedRetriesBeforeEscalating() {
        val binder = CountingBinder(stopResult = false, forceResult = 1)
        val controller = Robolectric.buildService(OpenVpnService::class.java).create().get()
        reportEngineLevel(controller, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET)
        ReflectionHelpers.setField(controller, "engineBinder", binder)
        ReflectionHelpers.setField(controller, "boundToEngine", true)

        controller.onStartCommand(stopIntent(), 0, 1)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        // Management is often not up yet while connecting: the bounded retries stay, then escalate.
        assertEquals(3, binder.stopCalls.get())
        assertEquals(1, binder.forceCalls.get())
    }

    @Test
    fun recreatedController_olderConnectedEngineWithoutNotification_postsHonestSafetyNotification() {
        val binder = CountingBinder()
        val service = Robolectric.buildService(OpenVpnService::class.java).create().get()
        // onCreate() enters foreground; model a controller that has since lost it.
        ReflectionHelpers.callInstanceMethod<Unit>(service, "exitControllerForeground")
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancelAll()
        ReflectionHelpers.setField(service, "engineBinder", binder)

        reportEngineLevel(service, ConnectionStatus.LEVEL_CONNECTED)
        ShadowLooper.idleMainLooper()

        assertTrue("Live tunnel without an engine notification needs the controller's", hasControllerNotification())
        val posted = notifications.allNotifications.single()
        // Not "VPN connecting": this notification stands in for a tunnel that is already up.
        // (String resources are absent from this sandbox, so the code's English fallback shows.)
        assertEquals("VPN connected", posted.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("The safety notification must offer Disconnect", 1, posted.actions?.size ?: 0)
        assertTrue((posted.flags and Notification.FLAG_ONGOING_EVENT) != 0)
    }

    @Test
    fun freshlyCreatedController_connectingNotificationIsUpgradedForALiveTunnel() {
        val binder = CountingBinder()
        // Production path: onCreate() has already posted the CONNECTING variant (foreground active).
        val service = Robolectric.buildService(OpenVpnService::class.java).create().get()
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancelAll()
        ReflectionHelpers.setField(service, "engineBinder", binder)

        reportEngineLevel(service, ConnectionStatus.LEVEL_CONNECTED)
        ShadowLooper.idleMainLooper()

        val posted = notifications.allNotifications.single()
        assertEquals("VPN connected", posted.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("The upgraded notification must offer Disconnect", 1, posted.actions?.size ?: 0)
    }

    @Test
    fun failedStopAfterDisconnectingNotification_isUpgradedToTheActionableVariant() {
        val binder = CountingBinder(stopResult = false, forceThrows = true)
        val service = controllerWithConnectedEngine(binder)
        // The controller has lost its foreground notification while the engine tunnel is live.
        ReflectionHelpers.callInstanceMethod<Unit>(service, "exitControllerForeground")
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancelAll()

        // ACTION_STOP posts the DISCONNECTING variant (no engine notification), then the stop fails.
        service.onStartCommand(stopIntent(), 0, 1)

        assertEquals(ConnectionStateManager.VpnError.STOP_FAILED, ConnectionStateManager.error.value)
        val posted = notifications.allNotifications.single()
        assertEquals("VPN connected", posted.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("A failed stop must leave Disconnect available", 1, posted.actions?.size ?: 0)
    }

    @Test
    fun recreatedController_engineOwnsOngoingNotification_controllerHandsOver() {
        val service = Robolectric.buildService(OpenVpnService::class.java).create().get()
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val engineId = OpenVPNService.NOTIFICATION_CHANNEL_NEWSTATUS_ID.hashCode()
        manager.notify(engineId, Notification().apply { flags = flags or Notification.FLAG_ONGOING_EVENT })
        val controllerId = 7014
        assertTrue(notifications.allNotifications.size >= 2 || notifications.getNotification(controllerId) != null)

        reportEngineLevel(service, ConnectionStatus.LEVEL_CONNECTED)

        assertEquals(
            "Only the engine's notification may remain",
            null, notifications.getNotification(controllerId)
        )
        assertNotNull(notifications.getNotification(engineId))
    }

    @Test
    fun userRequestNotificationDoesNotCountAsTheEnginesOngoingNotification() {
        val service = Robolectric.buildService(OpenVpnService::class.java).create().get()
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(OpenVPNService.NOTIFICATION_CHANNEL_USERREQ_ID.hashCode(), Notification())

        reportEngineLevel(service, ConnectionStatus.LEVEL_CONNECTED)

        assertNotNull(
            "A dismissable user-request notification cannot keep the tunnel visible",
            notifications.getNotification(7014)
        )
    }

    private fun assertNotEquals(unexpected: Any?, actual: Any?) {
        assertFalse("Did not expect $unexpected", unexpected == actual)
    }
}
