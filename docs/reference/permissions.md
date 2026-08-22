# Permissions and manifest layout

Permissions are split across **three** manifests. Looking in only one of them is the usual reason a
permission seems to be missing.

## Where each permission is declared

| Manifest | Declares |
|---|---|
| `src/core/src/main/AndroidManifest.xml` | `INTERNET`, `ACCESS_NETWORK_STATE`, `REQUEST_INSTALL_PACKAGES`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, a signature-level `${applicationId}.permission.VPN_STATUS` (declared *and* consumed), and a `<queries>` LAUNCHER block |
| `src/mobile` / `src/tv` manifests | `POST_NOTIFICATIONS` |
| **engine submodule** `src/external/OpenVPNEngine/main/src/main/AndroidManifest.xml` | **`BIND_VPN_SERVICE`**, `QUERY_ALL_PACKAGES`, `RECEIVE_BOOT_COMPLETED`, `READ_EXTERNAL_STORAGE`, `POST_NOTIFICATIONS` |

> **`BIND_VPN_SERVICE` is not in this app's manifests.** It guards the engine's
> `de.blinkt.openvpn.core.OpenVPNService`, which is the real Android `VpnService`. A statement that
> "VPN permissions live in the core manifest" is only half true — the core manifest declares the
> *controller* service, not the VPN one.

`QUERY_ALL_PACKAGES` also comes from the engine, which is what makes the per-app filter's package
enumeration work — see [../features/app-filter.md](../features/app-filter.md).

## Services

| Service | Process | Type |
|---|---|---|
| app controller `vpn/OpenVpnService` | main | `specialUse`, subtype `vpn`, `exported=false` |
| engine `de.blinkt.openvpn.core.OpenVPNService` | `:openvpn` | `specialUse`, subtype `vpn`, `BIND_VPN_SERVICE`, intent-filter `android.net.VpnService` |

The engine also declares `.api.ExternalOpenVPNService`, `.core.OpenVPNStatusService`,
`.OnBootReceiver` (BOOT_COMPLETED / MY_PACKAGE_REPLACED) and `.core.keepVPNAlive`.

Architecture of the two-process split: [../features/vpn-connection.md](../features/vpn-connection.md).

## Runtime permission requests

`POST_NOTIFICATIONS` is requested at connect time —
`MainAction.ConnectionButtonClicked(hasNotificationPermission, hasVpnPermission)` →
`MainEffect.RequestNotificationPermission`.

## FileProvider

`${applicationId}.fileprovider` in the core manifest (`@xml/file_paths`), used by log export and the
in-app APK installer.

## TV versus phone

| | mobile | tv |
|---|---|---|
| Required feature | — | `android.software.leanback` |
| Touchscreen | required | **not** required |
| Launcher category | `LAUNCHER` | `LEANBACK_LAUNCHER` |
| Orientation | unrestricted | `landscape` on both activities |
| `allowBackup` | `true` | `false` |
| Banner drawable | — | present |

Both share the same `applicationId`, which is intentional — splitting it has VPN-permission and
signing implications.

*Last verified against: `src/core/src/main/AndroidManifest.xml`, `src/mobile` and `src/tv` manifests, `src/external/OpenVPNEngine/main/src/main/AndroidManifest.xml` (2026-07-31).*
