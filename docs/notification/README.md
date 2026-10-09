# Notification icon screenshots

How TigerDuck's notifications look on each phone, captured on 2026-10-09 from a
debug build of `fix/3pty-noti` (TigerDuck 2.3.1). Each phone posted the
same two notifications from the debug screen (*More → Debug → Notification*):

- **Preview Live Update (in class)**: the Android 16 Live Update, which the
  phone puts in its island or status bar chip
- **Send bulletin (Other)**: an ordinary notification, built like a push

Which small icon a phone gets is decided by `DeviceSkin.notificationSmallIcon`;
the reasons for each skin are documented on `DeviceSkin` in
`app/src/main/java/org/ntust/app/tigerduck/notification/StatusBarChipSupport.kt`.

The vivo, HONOR, POCO, OPPO, Samsung and Pixel screenshots were retaken on the
branch as it stands (`77453459`). The moto, ZTE and Zenfone ones were captured
earlier, before the emoji were dropped from the detail lines (`615a9327`):
their rows still put 📍, 👤 and 🕒 in front of the room, the instructor and the
time, which now show as plain lines. Nothing else in them has changed.

Every phone is the international (Taiwan) model. Wi-Fi network names, a
weather widget's location, one unrelated chat notification and promotional
notifications from other apps were pixelated.

## Phones

| Phone | Model | OEM system | Android | Build |
|---|---|---|---|---|
| vivo V60 Lite | V2529 (PD2512F_EX) | OriginOS 6, overseas | 16 (API 36) | PD2512F_EX_A_16.1.18.0.W20 |
| HONOR X6d 5G | NLA-NX1 | MagicOS 10.0 | 16 (API 36) | NLA-N31 10.0.0.193(C363E8R202P1) |
| POCO C85 | 25078PC3EG | Xiaomi HyperOS 3.0 (OS3.0.302.0.WBNTWXM) | 16 (API 36) | BP2A.250605.031.A3 |
| OPPO Reno11 5G | CPH2599 | ColorOS 16.0.5 | 16 (API 36) | CPH2599_16.0.5.1200(EX01) |
| Samsung Galaxy A26 5G | SM-A266B | One UI 8.5 | 16 (API 36) | BP4A.251205.006.A266BXXSCCZH3 |
| Pixel (Android Emulator, Medium Phone) | sdk_gphone16k_arm64 | Stock Android | 17 (API 37) | CP31.260623.012 |
| moto g34 5G | moto g34 5G | Motorola stock | 15 (API 35) | V1UGS35H.75-14-3-10 |
| ASUS Zenfone 6 | ASUS_I01WD | OmniROM 14, custom ROM (2024-08-06 build) | 14 (API 34) | OmniROM-14-202408061250-zenfone6-GAPPS |
| ZTE P505 | P505 (Z3103O) | ZTE MyOS 15, Android Go | 15 Go (API 35) | P505_M03 |

## What each phone shows

| Phone | Status bar | Island / chip | Tapping the island | Shade |
|---|---|---|---|---|
| vivo V60 Lite | Paw in the system colour | White paw | Goes straight into TigerDuck | Full app icon |
| HONOR X6d 5G | Paw in the system colour | Yellow paw | Expands into a card | White paw on a yellow tile |
| POCO C85 | Paw in the system colour | Yellow paw | Expands into a card | Small paw in the notification colour |
| OPPO Reno11 5G | Full app icon | Yellow paw | Expands into a card | Full app icon |
| Samsung Galaxy A26 5G | Paw in the system colour | Yellow chip with the countdown | Drops the Live Update down as a card | Full app icon |
| Pixel (emulator) | Paw in the system colour | Chip, system colour | Shows the notification as a banner | Full app icon |
| moto g34 5G | Paw in the system colour | None (Android 15) | — | Small paw in the notification colour |
| ASUS Zenfone 6 | Paw in the system colour | None (Android 14) | — | Small paw in the notification colour |
| ZTE P505 | Paw in the system colour | None (Android 15) | — | Small paw in the notification colour |

Where a phone shows something other than a yellow island or a full app icon,
that is the skin's own rule rather than something the app picks:

- **vivo**: overseas OriginOS draws a coloured small icon untinted in the status
  bar and draws the same icon in the island, so the icon is white on both.
- **HONOR**: MagicOS sets the status bar and the shade from one flag; a
  monochrome status bar icon always becomes a glyph on a tile in the shade. The
  tile takes its colour from the icon. Its padding is thicker on an ordinary
  notification than on a Live Update, for every app. Its island also shrinks
  to an icon on a timer; see [below](#honor-the-island-shrinks-to-an-icon).
- **POCO**: in HyperOS's default "Android" notification style every app shows
  its small icon in the shade. The "MIUI" style (Settings → Status bar →
  Notification style) shows app icons instead.
- **Pixel**: Android tints the chip's icon itself.
- **Android 15 and earlier**: there is no Live Update island, and the shade
  shows each app's small icon in a circle of its notification colour.

The bulletin also carries the full-colour logo as its large icon, at the right
of the row, but only on HONOR, HyperOS and Android 15 and earlier, where the
shade shows the small icon. Where the shade already shows the full app icon the
logo would appear twice, so it's left off (`DeviceSkin.shadeShowsAppIcon`).

## OPPO: the Live Alerts card

ColorOS 16 doesn't draw the Live Update as an ordinary notification row. While
it is promoted, the shade and a tap on the island show ColorOS's Live Alerts
card (SystemUIPlugin, `normal_card_content_section`), which reads fewer
fields than other skins:

- **One slot for the countdown or the text**: under the title the card shows
  either the chronometer or the content text, never both. With the countdown
  running, the content text never shows.
- **No big text**: BigTextStyle gets the standard template, so the expanded
  text never shows either.
- **A bar only for ProgressStyle**: the card draws a progress bar for
  `Notification.ProgressStyle` and ignores `setProgress`.
- **Sub text has its own line** under the countdown, up to two lines.

So on ColorOS TigerDuck puts the room, the instructor and the time in the sub
text, joined by " · ", and posts the bar as a ProgressStyle, with no big text.
Before that, the card was the title and the countdown alone. When no countdown
is running the card shows the content text, which ends with the time, so the
sub text leaves the time out. Other skins keep the big text. With ColorOS's
per-app switch off the post isn't promoted and is an ordinary row, so it gets
the big text too. The island reads neither field and still shows
the paw and the minutes left. The bar is ColorOS's own grey.

Promotion is the *Show Live Updates on Live Alerts* switch on TigerDuck's
notification page in ColorOS settings, and it ships off. The status bar chip
row in TigerDuck's settings opens that page, and ColorOS pulses the switch's
row once, about 0.6 s after the page opens.

## HONOR: the island shrinks to an icon

MagicOS's island (the Magic Capsule) shrinks to a small icon circle after a
while and expands again later, repeating until the notification ends. This is
MagicOS's design and applies to every app; TigerDuck cannot change it.

- **The cycle**: SystemUI's `CapsuleCountDownTimerManager` keeps the capsule
  expanded for `capsule_expanded_state_duration`, then collapsed for
  `capsule_collapsed_state_duration`, and repeats. SystemUI's defaults are
  5 minutes expanded and 3 collapsed. The X6d 5G's SystemUI overlay sets both
  to 3 minutes, and HONOR can change them from its cloud config.
- **Measured on the X6d 5G**: expanded until about 3 min 57 s, an icon until
  about 6 min 57 s, then expanded again. The expanded timer had restarted at
  57 s, when the Live Update was re-posted on the minute.
- **What restarts it**: when the notification is shown as a heads-up banner the
  timer pauses. When the banner goes away the capsule expands and the expanded
  timer starts over. Re-posting the notification restarts it too.
- **Long-term collapse**: an "extreme tolerance" mode can keep the capsule
  collapsed (at 80% opacity) for good after a long time. It is off on the
  X6d 5G.
- **Tapping**: tapping the capsule or the icon expands it into a card
  ([HONOR support][honor-capsule]).

## HONOR: the Cutout setting

*Settings → Display & brightness → More display settings → Cutout* controls how
apps treat the camera hole at the top of the screen
([Huawei support][huawei-cutout]; HONOR inherited it from EMUI):

- **Auto** (default): each app follows its own layout. Apps that support
  cutouts draw around the camera; others start below it.
- **Hide cutout**: darkens the strip across the top of the screen, so the
  camera hole sits inside a black bar.
- **Show cutout**: lets the app draw its content up beside the camera hole.
- **Custom**: sets any of the three for one app, overriding the global choice.

It is stored per app (`setAppUseNotchMode(packageName, mode)` in HONOR's
framework). The island positions itself from the camera hole's shape, not from
this setting, so it plays no part in the island shrinking.

[honor-capsule]: https://www.honor.com/global/support/content/en-us15860432
[huawei-cutout]: https://consumer.huawei.com/uk/support/content/en-gb15834572/

## Screenshots

1 is the home screen with both notifications posted, 2 is the notification
shade pulled down, and 3 is two seconds after tapping the island or chip.

### vivo V60 Lite: OriginOS 6, Android 16

Tapping the island goes straight into TigerDuck, with no expanded card in
between, so there is no third screenshot.

<img src="screenshots/vivo-v60-lite/1-status-bar-and-island.webp" width="240"> <img src="screenshots/vivo-v60-lite/2-notification-shade.webp" width="240">

### HONOR X6d 5G: MagicOS 10.0, Android 16

<img src="screenshots/honor-x6d-5g/1-status-bar-and-island.webp" width="240"> <img src="screenshots/honor-x6d-5g/2-notification-shade.webp" width="240"> <img src="screenshots/honor-x6d-5g/3-island-tapped.webp" width="240">

### POCO C85: HyperOS 3.0, Android 16

<img src="screenshots/poco-c85/1-status-bar-and-island.webp" width="240"> <img src="screenshots/poco-c85/2-notification-shade.webp" width="240"> <img src="screenshots/poco-c85/3-island-tapped.webp" width="240">

### OPPO Reno11 5G: ColorOS 16.0.5, Android 16

The Live Alerts card shows the countdown, then the room, the instructor and the
time on one line, then the bar; see [above](#oppo-the-live-alerts-card).

<img src="screenshots/oppo-reno11-5g/1-status-bar-and-island.webp" width="240"> <img src="screenshots/oppo-reno11-5g/2-notification-shade.webp" width="240"> <img src="screenshots/oppo-reno11-5g/3-island-tapped.webp" width="240">

### Samsung Galaxy A26 5G: One UI 8.5, Android 16

One UI draws the Live Update chip in the notification's colour, so the chip is
yellow while the status bar icon beside it follows the system colour.

<img src="screenshots/samsung-galaxy-a26-5g/1-status-bar-and-island.webp" width="240"> <img src="screenshots/samsung-galaxy-a26-5g/2-notification-shade.webp" width="240"> <img src="screenshots/samsung-galaxy-a26-5g/3-island-tapped.webp" width="240">

### Pixel (Android Emulator): Android 17

<img src="screenshots/pixel-emulator/1-status-bar-and-island.webp" width="240"> <img src="screenshots/pixel-emulator/2-notification-shade.webp" width="240"> <img src="screenshots/pixel-emulator/3-island-tapped.webp" width="240">

### moto g34 5G: Android 15

<img src="screenshots/moto-g34-5g/1-status-bar-and-island.webp" width="240"> <img src="screenshots/moto-g34-5g/2-notification-shade.webp" width="240">

### ASUS Zenfone 6: OmniROM 14, Android 14

<img src="screenshots/asus-zenfone6-omnirom/1-status-bar-and-island.webp" width="240"> <img src="screenshots/asus-zenfone6-omnirom/2-notification-shade.webp" width="240">

### ZTE P505: MyOS 15, Android 15 Go

<img src="screenshots/zte-p505/1-status-bar-and-island.webp" width="240"> <img src="screenshots/zte-p505/2-notification-shade.webp" width="240">
