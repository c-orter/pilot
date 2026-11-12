<p align="center">
  <img src="app/src/main/res/drawable-xhdpi/banner.png" alt="Pilot" width="360">
</p>

# Pilot

I use Nuvio almost exclusively on my onn 4K Pro. The Home button kept taking me to the Google TV home screen, which I never wanted, so I wrote this to change what it does. A short press opens Nuvio. Holding it opens an overlay with the rest of my apps on top of whatever is playing.

Nuvio is only the default. The home app can be any app you pick in settings.

## How it works

Pilot is an accessibility service. Android shows those services key events before it acts on them, so Pilot takes the Home key and times the press itself. The system's own Home and long-press actions never run.

The Home button and the overlay are separate from Android's own "go home" request, which is what runs when the box wakes from a long sleep. See Wake from sleep below for how Pilot handles that.

## Using it

Press Home to open your home app. If you're already in it, nothing happens. Pilot knows which app is in front but not which screen inside it, so it can't take you back to that app's own home screen.

Hold Home for half a second to open the overlay.

The overlay shows your apps as banner tiles, five to a row. Apps that don't ship a banner get a tile with their icon and name. Your home app and Pilot itself are left out. An icon at the top right opens Pilot's settings.

Press OK on a tile to open that app. Hold OK for a menu: Open, Move, Force close, App settings, Uninstall. Move lets you rearrange with the arrow keys. OK drops the app and Back cancels, and the order is saved. Back closes the menu, then the overlay. The overlay also closes by itself when the screen turns off, when another app comes to the front, or after a minute with no key presses.

Settings are behind the gear icon in the overlay, and in the apps row of the stock launcher. You can pick the home app and decide whether it restarts when you press Home from another app. With restart on, which is the default, the app starts fresh and lands on its home screen. Turn it off to return to the app where you left it. The screen also says whether the service is running.

## Install

Download `pilot-<version>.apk` from the [latest release](https://github.com/c-orter/pilot/releases/latest) and install it with adb. I use wireless debugging.

```sh
adb install pilot-0.3.0.apk
```

Then turn the service on. Android 13 and later can grey out the accessibility toggle for sideloaded apps, so do it with adb. First check what is already enabled:

```sh
adb shell settings get secure enabled_accessibility_services
```

If that prints `null`, run these two:

```sh
adb shell settings put secure enabled_accessibility_services app.overlaylauncher/app.overlaylauncher.HomeKeyService
adb shell settings put secure accessibility_enabled 1
```

If it lists other services, the first command would replace them, so add Pilot after a colon instead:

```sh
adb shell settings put secure enabled_accessibility_services "<what it printed>:app.overlaylauncher/app.overlaylauncher.HomeKeyService"
```

When the service is off, Pilot's settings screen shows these commands with your TV's IP address.

To update, install over the old one. The service stays enabled.

```sh
adb install -r pilot-<version>.apk
```

Google TV caches app tiles, so an old icon or banner can linger after an update. Restarting the box should clear it.

If Home misbehaves, turn the service off and you get the stock behavior back:

```sh
adb shell settings delete secure enabled_accessibility_services
```

That clears the whole list. If you use other accessibility services, put them back or edit the list instead.

## Wake from sleep

When the box wakes after a long sleep, Android asks for its home app itself, and Pilot never sees that. Pilot registers a small invisible home app that forwards the request to your home app. The stock Google TV launcher outranks it, so to make Pilot the home app you have to turn the stock launcher off:

```sh
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
adb shell cmd package set-home-activity app.overlaylauncher/.HomeRelayActivity
```

The Google TV home screen and its recommendations disappear. To get the stock launcher back:

```sh
adb shell pm enable com.google.android.apps.tv.launcherx
```

If Pilot stops being the home app after that, run the `set-home-activity` line again. An OTA update can also turn the stock launcher back on.

## Limits

I've only run this on my onn 4K Pro (Android 14, Google TV) and in the Google TV emulator. Whether a remote's Home button reaches an accessibility service depends on the device's firmware, so it may not work on other boxes.

Force close only kills background processes, because Android doesn't let a normal app force-stop another one. It won't close the app that's in front. For that, use App settings and then Force stop.

## Build

You need JDK 17 and the Android SDK (platform 35, build-tools 36).

```sh
./gradlew assembleDebug      # app/build/outputs/apk/debug/
./gradlew assembleRelease    # signed if keystore.properties exists
```

For a signed release, put a `keystore.properties` in the project root. It's gitignored.

```properties
storeFile=/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

The code is a handful of Kotlin files in `app/src/main/java/app/overlaylauncher`. `HomeKeyService.kt` handles the key and launching, `AppsOverlay.kt` is the overlay, and `MainActivity.kt` is the settings screen. `Prefs.kt` and `Apps.kt` hold the saved settings and the app list. There are no dependencies beyond the Android framework.
