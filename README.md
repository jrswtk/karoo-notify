# Karoo Notify

Powiadomienia z telefonu z Androidem (Pixel 10 Pro) jako **pole danych na ekranie jazdy** Hammerhead Karoo 3.

Projekt składa się z dwóch aplikacji:

| Moduł | Gdzie | Co robi |
|---|---|---|
| `phone` | Pixel 10 Pro | Czyta wszystkie powiadomienia (NotificationListenerService), filtruje je i wystawia przez Bluetooth LE (serwer GATT). |
| `karoo` | Karoo 3 | Rozszerzenie Karoo (karoo-ext). Łączy się z telefonem przez BLE i dodaje pole graficzne **„Powiadomienia”**. |

Połączenie jest bezpośrednie (BLE), nie potrzebuje internetu ani chmury i działa obok aplikacji Hammerhead Companion.

```
Pixel 10 Pro                                   Karoo 3
┌───────────────────────┐   Bluetooth LE   ┌──────────────────────────┐
│ NotificationCatcher   │                  │ PhoneLink (skaner+GATT)  │
│   → NotificationHub   │  notify (JSON    │   → KarooStore           │
│   → BridgeService     │ ───────────────▶ │   → pole „Powiadomienia” │
│     (serwer GATT)     │   w ramkach)     │   → (opcjonalnie) baner  │
└───────────────────────┘                  └──────────────────────────┘
```

## Jak wygląda pole

Pole dopasowuje się do rozmiaru, jaki mu nadasz na stronie danych:

- **małe pole** (np. 1/4 ekranu) – ostatnie powiadomienie: aplikacja · czas, tytuł i treść w 2 liniach, plus licznik „(+3)”,
- **duże pole** (pół ekranu, cały ekran) – lista tylu najnowszych powiadomień, ile się zmieści, z nagłówkiem i nazwą telefonu,
- jeśli pole mieści tylko 1–2 wpisy, treść dostaje więcej linii i większą czcionkę.

Kolory automatycznie dopasowują się do trybu dziennego/nocnego Karoo.

## Budowanie APK

Biblioteka `karoo-ext` jest w GitHub Packages, które wymaga logowania tokenem GitHub (nawet dla publicznych pakietów).

### Opcja A – GitHub Actions (bez instalowania czegokolwiek)

1. Załóż prywatne repozytorium na GitHubie i wrzuć do niego zawartość tego folderu.
2. Workflow `.github/workflows/build.yml` uruchomi się sam (zakładka **Actions**).
3. Po ok. 3–5 min pobierz artefakt **karoo-notify-apks** – w środku są `karoo-notify-phone.apk` i `karoo-notify-karoo.apk`.
4. (Opcjonalnie) wypchnij tag `v1.0` – APK pojawią się w zakładce **Releases**, a link do APK Karoo możesz otworzyć bezpośrednio w aplikacji Companion.

### Opcja B – Android Studio

1. Utwórz token GitHub (Settings → Developer settings → Personal access tokens → classic) z uprawnieniem `read:packages`.
2. Dopisz do `~/.gradle/gradle.properties`:
   ```
   gpr.user=TWOJ_LOGIN_GITHUB
   gpr.key=ghp_TWOJ_TOKEN
   ```
3. Otwórz folder w Android Studio i uruchom `./gradlew :phone:assembleRelease :karoo:assembleRelease`.
   APK będą w `phone/build/outputs/apk/release/` i `karoo/build/outputs/apk/release/`.

Oba APK są podpisane tym samym kluczem z `signing/` – dzięki temu kolejne wersje instalują się jako aktualizacja. To klucz do użytku prywatnego; nie publikuj go, jeśli kiedyś udostępnisz aplikację innym.

## Instalacja i konfiguracja

### 1. Telefon (Pixel 10 Pro)

1. Zainstaluj `karoo-notify-phone.apk` (zgódź się na instalację z nieznanego źródła).
2. Otwórz **Karoo Notify** i przejdź przez 3 przyciski konfiguracji:
   - **Dostęp do powiadomień** – włącz Karoo Notify.
     Android 15+ blokuje to dla aplikacji spoza Sklepu Play. Jeśli przełącznik jest wyszarzony:
     *Ustawienia → Aplikacje → Karoo Notify → ⋮ → „Zezwalaj na ustawienia z ograniczeniami”* i spróbuj ponownie.
   - **Uprawnienia Bluetooth** – „Urządzenia w pobliżu” i powiadomienia.
   - **Optymalizacja baterii** – wyłącz, żeby Android nie usypiał mostka w trakcie jazdy.
3. Zanotuj **ID telefonu** pokazane u góry (8 znaków).

### 2. Karoo 3

1. Wyślij `karoo-notify-karoo.apk` na Karoo przez aplikację Hammerhead Companion (udostępnij plik APK → Companion albo wklej link do APK z Releases).
2. **Uruchom Karoo ponownie** – nowe rozszerzenia ładują się dopiero po restarcie.
3. Na Karoo otwórz **Aplikacje → Karoo Notify** i nadaj uprawnienia Bluetooth.
   Po chwili zobaczysz „Połączono z: Pixel 10 Pro” i ID telefonu (zgodne z tym na telefonie).
4. Dodaj pole do ekranu jazdy: **Profile → (profil) → strona → dodaj/zmień pole → Karoo Notify → Powiadomienia**.
5. Na telefonie naciśnij **Wyślij testowe powiadomienie** – powinno pojawić się na Karoo w ciągu sekundy.

## Opcje

**Telefon**
- *Pomijaj stałe powiadomienia* (domyślnie włączone) – odcina muzykę, nagrywanie w Stravie, nawigację, usługi w tle.
- *Pomijaj ciche powiadomienia* – tylko te, które dzwonią/wibrują.
- *Wybierz aplikacje…* – lista wszystkich zainstalowanych aplikacji z przełącznikiem „wysyłaj na Karoo” i wyszukiwarką. Dwa tryby:
  - **Wszystkie oprócz wyłączonych** – nowe aplikacje są wysyłane automatycznie, wyłączasz tylko te, których nie chcesz,
  - **Tylko wybrane** – na Karoo trafia wyłącznie to, co zaznaczysz (np. SMS, WhatsApp, telefon).
  Aplikacje, które już wysłały jakieś powiadomienie, są na górze listy. Przyciski „Włącz/Wyłącz widoczne” działają na wyniki wyszukiwania.

**Karoo**
- *Pokazuj też wyskakujący baner* (domyślnie wyłączone) – dodatkowo krótki baner na 8 s.
- *Usuwaj odrzucone na telefonie* (domyślnie włączone) – gdy odrzucisz powiadomienie na telefonie, znika też z Karoo. Wyłącz, jeśli wolisz pełną historię z jazdy.
- *Zapomnij telefon* – Karoo połączy się z pierwszym znalezionym telefonem z Karoo Notify i zapamięta go.
- Akcja przycisku **„Wyczyść powiadomienia”** – można ją przypisać do przycisku (np. bonusowego na manetkach AXS) w ustawieniach Karoo.

## Dobrze wiedzieć

- **Podwójne powiadomienia:** Karoo samo pokazuje część powiadomień przez Hammerhead Companion. Jeśli nie chcesz ich dublować, wyłącz *Powiadomienia z telefonu* w ustawieniach profilu na Karoo – Karoo Notify działa niezależnie od tego.
- Karoo przechowuje do 30 ostatnich powiadomień w pamięci; lista znika po wyłączeniu Karoo.
- Treść jest przycinana do 240 znaków, tytuł do 80.
- Po rozłączeniu (np. telefon poza zasięgiem) Karoo samo szuka telefonu – najpierw co 5 s, potem co 20 s – i po ponownym połączeniu pobiera aktualną listę.
- Pole odświeża się maks. raz na sekundę (limit Karoo) oraz co 30 s, żeby aktualizować „x min”.

## Struktura

```
phone/src/main/java/pl/karoonotify/phone/
  NotificationCatcher.kt   – odbiór i filtrowanie powiadomień (w tym komunikatory: ostatnia wiadomość + nadawca)
  NotificationHub.kt       – stan w pamięci + zdarzenia
  BridgeService.kt         – serwer GATT, reklamowanie BLE, kolejka wysyłki z potwierdzeniami
  MainActivity.kt          – ekran konfiguracji
  Protocol.kt              – UUID i format ramek
karoo/src/main/java/pl/karoonotify/karoo/
  NotifyExtension.kt       – rozszerzenie Karoo (start BLE, banery, akcja przycisku)
  PhoneLink.kt             – skaner + klient GATT + składanie ramek, auto-reconnect
  NotificationsDataType.kt – pole graficzne (RemoteViews), układ zależny od rozmiaru
  KarooStore.kt            – stan powiadomień
  MainActivity.kt          – ekran ustawień na Karoo
```

Protokół: telefon reklamuje usługę `6b4e0001-7c3a-4f2e-9a51-3d2b8c1f0a77` z ID telefonu w danych producenta (0xFFFF).
Karoo subskrybuje charakterystykę `6b4e0002-…`; każda wiadomość to JSON (`snap` / `add` / `del`) pocięty na ramki `[msgId][index][count][dane]`.
