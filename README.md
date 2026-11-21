# Slim Volume

A minimal, iOS-style volume bar for the AYN Thor (Android 13+), replacing the stock volume panel.

- Slim pill that slides in from the screen edge, nudges toward the centre on each press, and glides smoothly while a button is held
- Drag on the bar to set the volume by touch
- Place it anywhere on either screen: drag to move, pinch to resize
- Vertical or horizontal, preset or custom colors, optional embossed edge
- iOS mode: wide on the first press, slims down as you keep going
- Very subtle haptics per step, scaled with volume (none at 0)
- Built-in update check against this repo's GitHub Releases

## Install

1. Download the latest `thor-slim-volume-v*.apk` from [Releases](https://github.com/crowdoeswobs/thor-slim-volume/releases/latest) and install it.
2. Open Slim Volume and turn it on under **Accessibility → Downloaded apps → Slim Volume**.
   It uses an accessibility service only to catch the volume buttons and draw the bar.

The app checks for updates when it opens, or tap **Check for updates**.

## Build

Requires JDK 17+ and the Android SDK (platform 34).

```powershell
.\deploy.cmd      # build debug, install on the connected device, launch
.\release.cmd     # build a signed release APK into .\release\
```

Releases are signed with a key that is not in this repo (`keystore.properties`, git-ignored).
Updates only install over builds signed with the same key.

## Releasing

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. `.\release.cmd`
3. On GitHub, create a release tagged `v<versionName>` (e.g. `v1.1`) and attach the APK from `release\`.
