# SMOKE-02 — Server list load (DEFAULT_V2 cache)

**Result: PASS**

## Evidence

Logcat (pid 24603):

```
07:45:34.475  D  ServersV2SyncCoordinator: syncCountries(forceRefresh=false, cacheOnly=false)
07:45:34.485  D  ServersV2Repository: getCountries[locale=ru]: cache hit
07:52:19.202  I  MainViewModel: Initial selection load mode resolved. vpn_connected=false, cache_only=false
07:52:19.205  D  ServersV2Repository: getCountries[locale=ru]: cache hit
07:52:19.730  D  ServersV2Repository: getServersForCountry[AU]: serverCount=1 locale=ru
07:52:19.794  I  ServersV2SyncCoordinator: syncSelectedCountryServers: synced country=Австралия servers=1
```

UI dump confirmed:
- `server_selection_container` text: `🇦🇺 Австралия`
- `address_value` text: `Сидней (+10:00 UTC)` — US-09 city+UTC display intact
- `start_connection_button` visible and enabled
