# Update check, installer, and release notes

How the app learns a newer build exists, installs it, and shows what changed.

## Index

- [Update check](#update-check)
- [In-app APK install](#in-app-apk-install)
- [Release notes](#release-notes)
- [Trust boundary](#trust-boundary)

---

## Update check

`UpdateCheckRepository`, driven from `ui/main/UpdateCheckInteractor`.

It tries **two endpoints in order** — v2 first, v1 as a fallback for older backends:

```
GET /api/v2/versions/check-update
GET /api/v1/versions/check-update
```

Query parameters: `platform`, `releaseType`, `currentBuild`, `locale`. `releaseType` comes from the
`APP_RELEASE_TYPE` build field (`release` or `beta`), so a beta build asks a different question than a
release build — see [../reference/build-config.md](../reference/build-config.md).

Results are cached by `UpdateCheckCacheStore` so the check does not re-run on every foreground.
Models are in `updates/AppUpdateModels.kt`.

## In-app APK install

`AppUpdateInstaller` downloads the asset and hands it to the package installer;
`UpdateInstallProgressDialog` shows progress. `ui/updates/UpdateDialogMessage` builds the prompt text.

Two manifest pieces make this work, both in the core manifest:

- `REQUEST_INSTALL_PACKAGES` permission
- the `${applicationId}.fileprovider` `FileProvider` — the installer cannot be handed a raw `file://`
  URI on modern Android

See [../reference/permissions.md](../reference/permissions.md).

The download URL is a **proxy URL** served by the backend rather than a direct storage link, so the
server keeps control of where assets actually live.

## Release notes

`VersionReleaseRepository` with `VersionReleaseCacheStore`, driven from
`ui/main/VersionReleaseInteractor` and surfaced as `MainWhatsNew` /
`MainDestination.WhatsNew`.

```
GET /api/v1/versions/number/{versionName}/build/{buildNumber}?locale=
```

The response is Markdown. It is rendered with commonmark via
`ui/common/navigation/MarkdownRenderer`, presented through `TemplatePage` / `WebViewActivity`. The
cache is per-locale, so switching language re-fetches rather than showing stale translated notes.

## Trust boundary

**Update checks and release notes always use routes derived from `PRIMARY_SERVERS_URL`.**
`FALLBACK_SERVERS_URL` is never trusted as an update host — it is a third-party VPN Gate CSV
endpoint, and treating it as a source of installable binaries would be a supply-chain problem. This
holds regardless of which server source the user has selected.

*Last verified against: `updates/UpdateCheckRepository.kt`, `updates/AppUpdateInstaller.kt`, `versions/VersionReleaseRepository.kt`, `core/AppConstants.kt`, core `AndroidManifest.xml` (2026-07-31).*
