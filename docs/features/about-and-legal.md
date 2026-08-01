# About screen, log export, and legal documents

## Index

- [About screen](#about-screen)
- [Legal documents](#legal-documents)
- [Log export](#log-export)

---

## About screen

`ui/about/` backed by `about/AboutInfoProvider` (version, build, engine attribution) and
`about/AboutLinksProvider`. `about/AboutMeta` holds the constants; `ElapsedRealtimeProvider` and
`YearProvider` are injected rather than read statically so the screen is testable.

Layout rows are in `res/layout/content_about.xml`.

## Legal documents

Privacy policy and terms are **two hardcoded website URLs** in `AboutMeta`, opened in a browser:

```
https://openvpngateclient.azurewebsites.net/privacy-policy
https://openvpngateclient.azurewebsites.net/terms-of-use
```

> **The app does not call `/api/v1/legal/*`.** The backend exposes those endpoints and serves the same
> documents, but this client never requests them — it links to the website instead. Do not "fix" the
> URLs to point at the API; nothing consumes the response.

The repository also carries `PRIVACY_POLICY.md` and `TERMS.md` at its root. Those are the source the
**backend** fetches from GitHub to serve its own legal endpoints — they are not read by the app.

This is the one place where hardcoded production URLs are intentional. The
"never hardcode production endpoints" rule in
[../conventions/kotlin-android-standards.md](../conventions/kotlin-android-standards.md) is about API
endpoints the client negotiates with; these are user-facing destinations.

## Log export

`about/LogExportUseCase` packages the app's file logs for sharing, with retention governed by
`about/LogExportRetention`. Sharing goes through the `${applicationId}.fileprovider` `FileProvider`
declared in the core manifest — the same provider the APK installer uses, see
[../reference/permissions.md](../reference/permissions.md).

Log content, levels and privacy rules are in [logging.md](logging.md). Exported logs must not contain
secrets or full sensitive URLs; that constraint is enforced at write time by the logging policy, not
at export time, so a violation in a `Timber` call is a violation in the exported bundle.

*Last verified against: `about/AboutMeta.kt`, `about/AboutInfoProvider.kt`, `about/LogExportUseCase.kt`, `about/LogExportRetention.kt`, core `AndroidManifest.xml` (2026-07-31).*
