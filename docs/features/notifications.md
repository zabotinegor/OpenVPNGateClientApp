# Notifications and the foreground service

A VPN cannot run without a visible ongoing notification. This describes what the app shows and why it
is structured the way it is.

## Index

- [Two foreground services](#two-foreground-services)
- [Channels and the controller notification](#channels-and-the-controller-notification)
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

The controller's ongoing notification uses id **`CONTROLLER_NOTIFICATION_ID = 7014`** and is posted
via `startForeground()` as part of the connect path. `enterControllerForeground()` always
(re)issues this call on a genuine `ACTION_START`, even if the service was already foreground-active
from an earlier, unrelated promotion — repeated `startForeground()` calls are safe/idempotent, and
skipping the reissue was the root cause of a foreground-service-start crash; see
[guides/troubleshooting.md](../guides/troubleshooting.md#openvpnservice-remoteserviceexception-foregroundservicedidnotstartintimeexception-on-reconnect-after-a-background-status-sync--bug-86cb35fbt).

`specialUse` is the correct type for VPN on modern Android; it is not a placeholder to be "corrected"
to `connectedDevice` or `dataSync`.

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

*Last verified against: `vpn/OpenVpnService.kt` notification setup, `vpn/DisconnectReceiver.kt`, `ui/main/MainContract`, launcher manifests, `src/external/OpenVPNEngine/main/.../core/OpenVPNService.java` (2026-08-16).*
