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

### APK cache and download cleanup

`DefaultAppUpdateInstaller` writes only inside the app-private `cacheDir/updates` directory.
Downloaded APKs are staged to a `.part` file and made installable only after the
transfer completes, the declared size matches (when present), and the declared
SHA-256 matches (when valid). Cancellation and failures discard the incomplete
`.part` artifact rather than allowing a later Update action to install it. Cancelling the
Update coroutine also cancels the in-flight OkHttp call, so a blocked connect or body read is
interrupted instead of running to completion.

On repeated Update, the installer may skip the network transfer **only** for a
complete asset matching the release identity, declared size, and valid SHA-256;
it recomputes the cached file hash before each reuse. Missing or malformed hashes
do not authorize reuse. The cache key covers the release identity and the download URL,
so an asset whose download URL changes on every request never hits the cache and is
downloaded again. Install-launch failures do not discard an already
verified complete APK. Concurrent update actions are serialized inside the
shared installer singleton.

The installer removes abandoned partial files, expires APKs older than seven
days, and keeps at most three APKs, each limited to 250 MiB. Android may also
evict app cache entries independently. This is **disk-cache retention**, not
evidence of a heap/RAM leak fix. The update download proxy remains restricted
to the trusted primary backend.

**Note on `UpdateInstallProgressDialog` theming:** This dialog uses a custom view (`LinearLayout`
with a `ProgressBar` and a bare programmatic `TextView` for the progress text), not the standard
`setMessage()` call. It calls `DialogUtils.applyThemedTitleColor()` but has no `android.R.id.message`
view, so the message-color utility does not apply to it. The progress text `TextView` is not styled
by `DialogUtils` — it uses the theme's default text color, which QA verified on real devices is
readable in both light and dark themes without additional fixes.

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

*Last verified against: `updates/UpdateCheckRepository.kt`, `updates/AppUpdateInstaller.kt`, `versions/VersionReleaseRepository.kt`, `core/AppConstants.kt`, core `AndroidManifest.xml` (2026-10-19).*
