# Client for OpenVPN Gate

🇬🇧 English | [🇷🇺 Русский](README.ru.md) | [🇵🇱 Polski](README.pl.md)

### Free access, no strings attached.

A free, open-source Android VPN client built on the community-run **VPN Gate** network — no
subscriptions, no account, no catch.

## Why I built this

My name is Yahor Zabotsin. I'm originally from Belarus and now live in Poland. Like a lot of people
who've left home, I still need to reach services and content back home from time to time — and I
didn't want to pay a monthly subscription just for that. That's how I found [VPN Gate](https://www.vpngate.net/en/),
a free volunteer-run VPN relay network — great idea, but the official clients felt dated and clunky.
So I decided to build a better one myself.

One detail worth being upfront about: this app was built "vibe-coding" style. I described what I
wanted, AI coding assistants wrote most of the code, tests, and documentation, and I reviewed,
directed, and shipped every change. It's still a real, working, actively maintained app — just built
with a different process than most.

## What you get

- **100% free** — no subscriptions, no premium tier, no paywalls.
- **No account required** — open the app and connect.
- **No data-selling business model** — this isn't a "free" VPN that pays for itself with your data.
- **Open source** — the full client source is right here on GitHub, nothing hidden.
- **Community-powered** — runs on the [VPN Gate](https://www.vpngate.net/en/) volunteer relay
  network, not one company's servers.
- **One-tap connect** — pick a country and go.
- **Live status and speed** — see your connection state and throughput at a glance.
- **Auto-reconnect** — the app watches the connection and recovers automatically if it drops.
- **Favorites** — pin the countries and servers you use most for quick access.
- **Android TV app** — a separate TV-optimized build with full D-pad/Leanback navigation.

## Screenshots

<p>
  <img src="docs/assets/readme/phone-connected.png" alt="Connected state" width="30%" />
  <img src="docs/assets/readme/phone-server-list.png" alt="Server list" width="30%" />
  <img src="docs/assets/readme/phone-main.png" alt="Main screen" width="30%" />
</p>

## Get it

The app isn't on an official app store yet. For now, the current ways to get it are:

- [GitHub Releases](https://github.com/zabotinegor/OpenVPNGateClientApp/releases) — download the
  latest APK directly.
- The [project website](https://openvpngateclient.azurewebsites.net) — same releases, with more
  context.

## Links

- Homepage: https://openvpngateclient.azurewebsites.net
- Privacy Policy: https://openvpngateclient.azurewebsites.net/privacy-policy
- Terms of Use: https://openvpngateclient.azurewebsites.net/terms-of-use
- License: [GPL-2.0-only](LICENSE)
- GitHub (this app): https://github.com/zabotinegor/OpenVPNGateClientApp
- GitHub (VPN engine): https://github.com/zabotinegor/OpenVPNGateClientEngine

## Built on VPN Gate

This app is a client for [VPN Gate](https://www.vpngate.net/en/), a free, volunteer-operated VPN
relay network run out of the University of Tsukuba, Japan. All the credit for keeping the actual
servers running goes to that community — this project just tries to be a nicer way to reach them
from Android.

---

Looking for build instructions, architecture details, or other technical documentation? Head over to
[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).
