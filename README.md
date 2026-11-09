<p align="center">
  <img src="app/src/main/res/drawable-xhdpi/banner.png" alt="Pilot" width="360">
</p>

# Pilot

Two jobs for the Home button on an Android TV or Google TV device:

- **Press** it and you land in one app, Nuvio by default.
- **Hold** it and an apps overlay opens on top of whatever is playing.

If you spend your day in a single app, the stock launcher is one more screen between you and it. This replaces that screen with a button press and keeps the app list a hold away.

## How it works

Pilot is an accessibility service. It asks Android to show it key events before the system acts on them, takes the Home key, and times the press itself. The system's own Home and long-press (Dashboard) actions never run.

It is not a launcher app and doesn't register as one, so the Google TV home screen stays installed and is always reachable from the overlay.

## Using it

| Action | Result |
| --- | --- |
| Press Home | Opens the home app. If that app is Nuvio and already on its home screen, nothing happens. |
| Hold Home (half a second) | Opens the apps overlay. |
| Enter on an app | Opens it. |
| Hold Enter on an app | Menu: Open, Move, Force close, App settings, Uninstall. |
| Move, then arrows | Reorders the app. Enter drops it, Back cancels. The order is saved. |
| Back | Closes the menu, then the overlay. |

Two small icons sit at the top right of the overlay: the system home screen, and this app's settings. The home app and Pilot itself are left out of the grid.

### Settings

Open them from the overlay's gear icon, or from the apps row of the stock TV launcher.

- **Home app**: the app a short press opens. Defaults to Nuvio.
- **Restart the home app on every Home press**: on by default. Nuvio ignores a launch while it is open, so restarting is what gets you back to its home screen. Turn it off to return to the app as you left it.
- **Service status**: shows whether the accessibility service is running, with a shortcut to the accessibility settings.

## Install

Download `pilot-<version>.apk` from the [latest release](https://github.com/c-orter/pilot/releases/latest) and install it with adb. Wireless debugging works on most TV boxes.

```sh
adb install pilot-0.1.0.apk
```

Then turn the service on. Android 13 and later can block accessibility toggles for sideloaded apps in the settings UI, so use adb:

```sh
adb shell settings put secure enabled_accessibility_services app.overlaylauncher/app.overlaylauncher.HomeKeyService
adb shell settings put secure accessibility_enabled 1
```

That command replaces the list of enabled services. If you already use another accessibility service, join the two names with a colon.

If Home ever misbehaves, turn the service off and the stock behavior returns at once:

```sh
adb shell settings delete secure enabled_accessibility_services
```

## Limits

- **Device support.** Built for the onn 4K Pro (Android 14, Google TV) and developed against the Google TV emulator. Whether a remote's Home button reaches an accessibility service depends on the device firmware, so other boxes may differ.
- **Force close.** Android doesn't let a normal app force-stop another one. The menu entry kills background processes only, so it won't close the app currently in front. Use *App settings*, then *Force stop*, for that.
- **Already-home detection** reads Nuvio's sidebar, so it only applies when Nuvio is the home app. Any other home app is simply launched on every press.

## Build

Needs JDK 17 and the Android SDK (platform 35, build-tools 36).

```sh
./gradlew assembleDebug      # app/build/outputs/apk/debug/
./gradlew assembleRelease    # signed if keystore.properties exists
```

For a signed release, create `keystore.properties` in the project root. It is gitignored.

```properties
storeFile=/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

## Layout

| File | Role |
| --- | --- |
| `HomeKeyService.kt` | Accessibility service: Home key handling, launching, app actions |
| `AppsOverlay.kt` | The overlay: grid, long-press menu, move mode, entrance animation |
| `MainActivity.kt` | Settings screen and home-app picker |
| `Prefs.kt` | Home app, restart toggle, app order |
| `Apps.kt` | Lists launchable apps |

The app has no dependencies beyond the Android framework.
