# Client for OpenVPN Gate

[🇬🇧 English](README.md) | [🇷🇺 Русский](README.ru.md) | Polski

### Darmowy dostęp bez żadnych haczyków.

Darmowy, otwartoźródłowy klient VPN na Androida oparty na sieci **VPN Gate** prowadzonej przez
społeczność — bez subskrypcji, bez konta, bez podstępów.

## Dlaczego to zbudowałem

Nazywam się Yahor Zabotsin. Pochodzę z Białorusi, a obecnie mieszkam w Polsce. Jak wielu ludzi, którzy
wyjechali z domu, od czasu do czasu wciąż muszę korzystać z serwisów i treści dostępnych tylko w
kraju — i nie chciałem płacić miesięcznej subskrypcji tylko po to. Tak trafiłem na
[VPN Gate](https://www.vpngate.net/en/) — darmową, prowadzoną przez wolontariuszy sieć VPN. Sam
pomysł świetny, ale oficjalne klienty wydały mi się przestarzałe i niewygodne. Postanowiłem więc
zbudować lepszy.

Jedna rzecz, o której warto powiedzieć wprost: ta aplikacja powstała w stylu "vibe-codingu". Opisywałem,
czego potrzebuję, a większość kodu, testów i dokumentacji napisały asystenty AI — ja natomiast
sprawdzałem, kierowałem i wydawałem każdą zmianę. Mimo to jest to prawdziwa, działająca i aktywnie
rozwijana aplikacja — po prostu powstała w inny sposób niż większość.

## Co zyskujesz

- **100% za darmo** — bez subskrypcji, bez planu premium, bez płatnych funkcji.
- **Bez konta** — otwierasz aplikację i się łączysz.
- **Żadnego handlu danymi** — to nie jest "darmowy" VPN, który tak naprawdę zarabia na Twoich danych.
- **Otwarty kod źródłowy** — cały kod klienta jest tutaj, na GitHubie, nic nie jest ukryte.
- **Napędzane przez społeczność** — aplikacja działa na wolontariackiej sieci
  [VPN Gate](https://www.vpngate.net/en/), a nie na serwerach jednej firmy.
- **Połączenie jednym dotknięciem** — wybierasz kraj i gotowe.
- **Status i prędkość na żywo** — stan połączenia i przepustowość widoczne od razu.
- **Automatyczne ponowne łączenie** — aplikacja pilnuje połączenia i sama je przywraca po zerwaniu.
- **Ulubione** — przypinaj najczęściej używane kraje i serwery dla szybkiego dostępu.
- **Aplikacja na Android TV** — osobna wersja zoptymalizowana pod telewizory, z pełną obsługą
  nawigacji D-pad/Leanback.

## Zrzuty ekranu

<p>
  <img src="docs/assets/readme/phone-connected.png" alt="Stan połączenia" width="30%" />
  <img src="docs/assets/readme/phone-server-list.png" alt="Lista serwerów" width="30%" />
  <img src="docs/assets/readme/phone-main.png" alt="Ekran główny" width="30%" />
</p>

## Gdzie pobrać

Aplikacji nie ma jeszcze w oficjalnym sklepie z aplikacjami. Obecnie dostępne sposoby jej pobrania to:

- [GitHub Releases](https://github.com/zabotinegor/OpenVPNGateClientApp/releases) — pobierz
  najnowszy plik APK bezpośrednio.
- [Strona projektu](https://openvpngateclient.azurewebsites.net) — te same wydania, z dodatkowym
  kontekstem.

## Linki

- Strona główna: https://openvpngateclient.azurewebsites.net
- Polityka prywatności: https://openvpngateclient.azurewebsites.net/privacy-policy
- Warunki korzystania: https://openvpngateclient.azurewebsites.net/terms-of-use
- Licencja: [GPL-2.0-only](LICENSE)
- GitHub (ta aplikacja): https://github.com/zabotinegor/OpenVPNGateClientApp
- GitHub (silnik VPN): https://github.com/zabotinegor/OpenVPNGateClientEngine

## Oparte na VPN Gate

Ta aplikacja jest klientem dla [VPN Gate](https://www.vpngate.net/en/) — darmowej, wolontariackiej
sieci VPN prowadzonej przez Uniwersytet w Tsukubie w Japonii. Cała zasługa za utrzymanie działających
serwerów należy do tej społeczności — ten projekt stara się tylko zapewnić wygodniejszy sposób
łączenia się z nimi z poziomu Androida.

---

Szukasz instrukcji budowania, szczegółów architektury lub innej dokumentacji technicznej? Zajrzyj do
[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) (w języku angielskim).
