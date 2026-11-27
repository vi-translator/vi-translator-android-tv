<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="VI Translator logo" width="160" />
</p>

<h1 align="center">VI Translator - Android TV</h1>

<p align="center">VI Translator's live subtitles on your Android TV, over the game from your console.</p>

---

## What it does

Shows VI Translator's translations on your TV, over the game from your console's HDMI input. It
needs a TV that runs Google TV or Android TV itself (for example TCL, Sony, Hisense or Philips
Google TV models), with the console plugged into the TV.

## How it works

1. The console goes into a capture card with passthrough, and the card's passthrough goes into the
   TV, so the game reaches the TV with no delay.
2. VI Translator on the PC reads the subtitles from the capture card and translates them.
3. This app shows the translations over the TV picture, where they would be on the game picture.
   Only the text crosses your home network, so it shows as fast as on the PC.

Or with the VI Translator phone app instead of the PC: the phone plays the PS5 or the Xbox over
Remote Play (no capture card), reads the subtitles there, and this app shows the translations over
the console's picture on the TV, the same way.

The overlay never takes the remote: it keeps working the TV and the game under it.

## Setup

1. **Install the app on the TV:** install *Downloader* from Google Play on the TV, open it and enter
   the code **3156727**: it downloads the APK, then press *Install* (allow Downloader to install apps
   if the TV asks). Or download the APK from the
   [latest release](https://github.com/vi-translator/vi-translator-android-tv/releases/latest) and
   install it with `adb install`.
2. **Open it:** it shows a 6-digit code. It keeps waiting for 10 minutes after you leave it, so
   you can switch the TV to the PC's input if the PC is on the same TV.
3. **On the PC or the phone:** in VI Translator, *Show on* tab, *Connect a TV*. Type the code and
   press *Connect*. Sharing on the home network is turned on if it was off.
4. **Allow it to draw over other apps** if the app asks. If the TV has no such setting (some TCL
   models hide it), allow it once from a computer, with developer options and USB or network
   debugging on:

   ```sh
   adb connect <TV address>
   adb shell appops set app.vitranslator.tv SYSTEM_ALERT_WINDOW allow
   ```

   Or, with no permission and no computer, press **Play inside the app**: the game from the TV's
   HDMI input plays inside the app, with the translations over it (as in the webOS app). Pick the
   console's input the first time. *Back* shows the app's panel; *Back* again (or *Leave*) leaves.
   This needs a TV with HDMI inputs: a stick or a box plugged into a TV (Chromecast, Mi Stick) has
   none, so only the overlay works there.

Once connected, the app shows the connection and two buttons:

- **Show on:** all inputs, or one HDMI input only (the console's): the overlay then hides on the
  home screen, in apps and on other inputs. Offered on TVs that tell apps which input is on screen
  (TCL); elsewhere the overlay shows everywhere.
- **Play inside the app:** the same as above, where the overlay works too (on a TV with HDMI
  inputs).
- **Disconnect:** the TV forgets the PC (or phone) and shows a new code. Removing the TV in VI
  Translator on the PC or phone does the same.

The overlay comes back by itself when the TV turns on, and finds the PC or phone again if the
router gives it a new address.

If the PC or phone stops answering (on an iPhone, iOS pauses VI Translator when another app such
as PS Remote Play comes to the front), the TV shows a small note: keep VI Translator open on the
phone or PC.

## Tips

- Show the game full screen with no zoom or overscan (picture size *Auto* or *Just scan*), so the
  translations land where the PC or phone expects.
- The text style, the language and what is read are all set in VI Translator on the PC or phone.
- If the app says the TV's web engine is too old, update *Android System WebView* from Google Play
  (or *Settings › Apps › Android System WebView*): the translations need version 84 or newer.

## Build

Needs JDK 17 or newer and the Android SDK (platform 36), or open the folder in Android Studio:

```sh
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
```

## Releases

Pushing a tag like `v1.2.3` builds the signed APK (to install directly) and app bundle (`.aab`, for
Google Play) and attaches them to a GitHub Release
(`.github/workflows/release.yml`). The tag must be newer than the latest release. Every release is
signed with the same key, set in the repository's secrets: `ANDROID_KEYSTORE_B64` (the keystore,
base64), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`. Keep a backup
of the keystore: a TV only installs an update signed with the same key.

## Credits

The Changa font is under the SIL Open Font License (`FONT-LICENSE-Changa.txt`).
