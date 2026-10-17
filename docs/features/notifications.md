# Notifications and the foreground service

A VPN cannot run without a visible ongoing notification. This describes what the app shows and why it
is structured the way it is.

## Index

- [Two foreground services](#two-foreground-services)
- [Channels and the controller notification](#channels-and-the-controller-notification)
- [Safety-net controller notification](#safety-net-controller-notification)
- [Disconnect action](#disconnect-action)
- [Permission gating](#permission-gating)

---

## Two foreground services

Both the app's controller (`vpn/OpenVpnService`, main process) and the engine's real `VpnService`
(`:openvpn` process) run as foreground services with type **`specialUse`** and the subtype property
`vpn`. That means **two** foreground-service lifecycles exist, in two processes.

This matters when debugging a notification that will not clear: the notification you are looking at
may belong to the other process. See
[vpn-connection.md](vpn-connection.md#two-processes-one-aidl-boundary).

The engine's own service has its own independent FGS deadline, separate from the controller's. A
bug in how the engine decides whether to (re)issue its `startForeground()` call on rapid
stop/retry churn caused a real, device-reproduced crash of the engine process — mitigated but not
fixed on the client side (the engine-side root cause is out of scope to edit directly). See
[guides/troubleshooting.md](../guides/troubleshooting.md#engines-own-deblinktopenvpncoreopenvpnservice-fgs-timeout-crash-under-rapid-stopretry-churn--mitigated-root-cause-open-bug-86cb35fbt-fix-cycles-13-14)
for the full root cause, the mitigation, and the recommended deterministic follow-up.

## Channels and the controller notification

`OpenVpnService.ensureEngineNotificationChannels()` creates the channels the engine expects, so the
engine's own notifications land in a channel the app controls rather than a default one.

The controller's ongoing notification uses id **`CONTROLLER_NOTIFICATION_ID = 7014`**. This is a
controller id, not an engine id. It is posted via `startForeground()` on the connect path and on the
safety-net paths described below. `enterControllerForeground()` always (re)issues this call on a
genuine `ACTION_START`, even if the service was already foreground-active from an earlier, unrelated
promotion. Repeated `startForeground()` calls are safe and idempotent. Skipping the reissue was the
root cause of a foreground-service-start crash; see
[guides/troubleshooting.md](../guides/troubleshooting.md#openvpnservice-remoteserviceexception-foregroundservicedidnotstartintimeexception-on-reconnect-after-a-background-status-sync--bug-86cb35fbt).

`specialUse` is the correct type for VPN on modern Android; it is not a placeholder to be "corrected"
to `connectedDevice` or `dataSync`.

## Safety-net controller notification

The engine normally shows its own ongoing status notification. The controller shows notification 7014
only as a **safety net**, when no such engine notification exists while a tunnel may still be live.

- **Engine ownership check.** `hasEngineOngoingNotification()` looks for an active, ongoing or
  foreground notification posted under the hash codes of the engine channels `openvpn_bg` and
  `openvpn_newstat`. The `openvpn_userreq` channel is excluded because that notification is
  dismissable and never keeps the engine in the foreground. If the notification manager cannot be
  queried, the controller keeps its safety notification.
- **Variants** (`ControllerNotificationMode`). Neither safety variant uses "connecting" wording,
  because a possibly live tunnel must not be described as being established.
  - `CONNECTED`: restored after controller recreation when the engine reports `LEVEL_CONNECTED` or
    `LEVEL_VPNPAUSED` (a lagging raw level whose detail is `CONNECTED` counts as connected, like the app state) while neither the controller nor the engine shows a notification. This is
    not gated on the app `ConnectionState`. Includes the Disconnect action.
    Never posted while a user stop is in flight (including a stop detected only after the restore was queued):
    the `DISCONNECTING` variant is kept or posted instead. Once the stop has failed (`STOP_FAILED`) it is no longer
    in flight, so `STOP_FAILED` and any later restore (for example after the engine notification vanished) show
    this actionable variant.
  - `DISCONNECTING`: shown on `ACTION_STOP` when neither notification is present, so the notification
    is not removed before teardown is confirmed. No Disconnect action.
- **Tap target.** Both variants open the launcher activity through the content intent.
- **Lifetime.** The last VPN notification is never removed before teardown is confirmed. If the engine
  owns an ongoing notification, `ACTION_STOP` hands ownership to it and releases whichever controller
  variant is showing, so there is never a duplicate. If the engine owns none, the controller keeps (or
  switches to) `DISCONNECTING`, and a failed or unconfirmed stop keeps it; `STOP_FAILED` then upgrades it to
  the `CONNECTED` variant with Disconnect, but only when the engine still owns no notification (see
  [connection-recovery.md](connection-recovery.md#stop-and-teardown)). Outside a user stop,
  `exitControllerForeground()` releases it when the engine reports a level other than START/UNKNOWN and either
  the tunnel is not live (any normalized level except CONNECTED/VPNPAUSED, including a terminal AUTH_FAILED) or the engine
  owns an ongoing notification of its own, except for a stale connecting-family level that the pause guard
  discards while a pause is in flight (such a level neither releases, restores nor revokes a pending restore;
  one pause-guard decision per callback covers all of these); it is also
  released when the service is destroyed.

### Notification-loss recovery and its limits

While the accepted engine level is live, the AIDL byte-count callback schedules a recheck at most every
15 s (`scheduleNotificationOwnershipRecheck()`), decided on the main thread by the same restore as the
state-callback path:

- the engine notification vanished and the controller shows none (or the user dismissed the posted
  `CONNECTED` variant: the cached mode only counts while notification 7014 is actually posted) -> the
  actionable `CONNECTED` variant is (re)posted, or `DISCONNECTING` during a stop;
- the engine re-posted its own notification while the controller `CONNECTED` variant is showing -> the
  controller variant is released.

Known limitation: the recheck is owned by the controller service instance. After ownership is handed to the
engine the controller is no longer a foreground service, so Android may stop it while the engine process
survives; if the engine notification then also disappears, nothing in the main process can repost 7014 until the
app is opened, a state callback recreates the controller, or the user acts from the app UI or the system VPN
indicator. Recovering from that needs engine-side notification recovery or a permanently foreground
controller (which would bring back the duplicate notification the handoff removes); it is tracked as a
follow-up outside this fix, whose scope is the orphaned-stop path.

## Disconnect action

`vpn/DisconnectReceiver` handles the notification's disconnect action. If the app's own path is
unavailable it falls back to launching the engine's `de.blinkt.openvpn.activities.DisconnectVPN`, so
the notification stays functional even when the controller is not in a state to handle it.

The disconnect that runs from here goes through the same bounded stop/teardown flow as an in-app
Stop — see [connection-recovery.md](connection-recovery.md#stop-and-teardown).

## Permission gating

`POST_NOTIFICATIONS` is declared in both launcher manifests and requested **at connect time**, not at
startup:

```
MainAction.ConnectionButtonClicked(hasNotificationPermission, hasVpnPermission)
    → MainEffect.RequestNotificationPermission
```

Asking at the moment of connection is deliberate — the permission is only meaningful once there is an
ongoing session to report, and asking on first launch produces a denial the user cannot easily undo.

The VPN consent grant is tracked alongside it in the same action, so both are resolved before a
connect attempt starts rather than failing partway.

*Last verified against: `vpn/OpenVpnService.kt` notification setup, `vpn/DisconnectReceiver.kt`, `ui/main/MainContract`, launcher manifests, `src/external/OpenVPNEngine/main/.../core/OpenVPNService.java` (2026-08-16); safety-net notification against `OpenVpnService.kt` `enterControllerForeground`, `hasEngineOngoingNotification` and the ACTION_STOP path (2026-10-16).*
