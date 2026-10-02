# CrispyDoom for Android

An Android port of [Crispy Doom](https://github.com/fabiangreffrath/crispy-doom) with a custom touch-control layer.
The engine and rendering run on SDL2. A control overlay is drawn on top of the game surface, detects what the
game is doing right now (menu, level, intermission screen) and switches the button layout accordingly.

- Package: `com.crispy.doom`
- minSdk 24, targetSdk 34, compileSdk 36
- ABI: `arm64-v8a` only
- Orientation: landscape only (both directions)
- Bundled content: the shareware `doom1.wad`

---

## Table of contents

1. [Features](#features)
2. [Project layout](#project-layout)
3. [How it works](#how-it-works)
4. [How the control mode is detected](#how-the-control-mode-is-detected)
5. [Button reference](#button-reference)
6. [Development history: what broke and how it was fixed](#development-history-what-broke-and-how-it-was-fixed)
7. [Building](#building)
8. [Updating libcrispy.so](#updating-libcrispyso)
9. [Troubleshooting](#troubleshooting)
10. [Known limitations](#known-limitations)
11. [Licenses](#licenses)

---

## Features

- Full Crispy Doom running on SDL2 through the native library `libcrispy.so`.
- Two touch layouts that switch automatically:
  - **Menu layout:** arrows, OK, BACK, Y, N, QS, QL, keyboard.
  - **Game layout:** movement, look, Fire, Use, weapon switch, map.
- The ☰ button in game opens the Doom menu and immediately shows the menu layout.
  The ▶ button closes the menu and brings the game layout back.
- On the level-intermission screen and in the finale, OK and BACK advance the screen.
  Doom advances those screens with game keys (use/fire), not with Enter or Esc.
- The device's built-in on-screen keyboard, toggled from the same button row as Y/N/QS/QL,
  for typing savegame names.
- Landscape from the very first frame, with no flash of portrait on startup.
- Music through SDL2_mixer.

---

## Project layout

```
app/
├── build.gradle
└── src/main/
    ├── AndroidManifest.xml
    ├── assets/
    │   └── doom1.wad                    # shareware IWAD, copied to internal storage on first launch
    ├── jniLibs/arm64-v8a/
    │   ├── libcrispy.so                 # Crispy Doom engine (built separately)
    │   ├── libSDL2.so
    │   └── libSDL2_mixer.so             # music (exact file name depends on your build)
    ├── java/
    │   ├── com/crispy/doom/
    │   │   ├── MainActivity.java        # startup, WAD handling, orientation, overlay wiring
    │   │   └── TouchControlsView.java   # the whole touch UI and the mode logic
    │   └── org/libsdl/app/              # stock SDL2 Java glue (SDLActivity, SDLSurface, ...)
    └── res/
```

What is ours and what is not:

| Part | Origin |
|---|---|
| `org/libsdl/app/*` | Stock SDL2 Android glue. Not modified; it is controlled from the outside. |
| `MainActivity` | Ours, small. Extends `SDLActivity`. |
| `TouchControlsView` | Ours, the bulk of the work (about 1,450 lines). |
| `libcrispy.so`, `libSDL2.so`, SDL2_mixer | Prebuilt native libraries, built separately. |

---

## How it works

### Startup

1. `MainActivity` copies `doom1.wad` from assets into `files/iwad/` if it is not there yet.
2. SDL starts `SDL_main` from `libcrispy.so` with the arguments `-iwad <path to doom1.wad>`.
3. `TouchControlsView` is added on top of the SDL surface and receives all touch input.

### Input path

The overlay turns touches into key events and injects them into SDL, the same way a hardware
keyboard would. Doom therefore needs no changes to understand the touch controls:

- movement and look: sticks and drag areas mapped to the keys and mouse motion Doom already uses;
- buttons: Fire = `Ctrl`, Use = `Space`, menu navigation = arrows, `Enter`, `Esc`, `Y`, `N`, `F6` (quick save), `F9` (quick load);
- short taps are sent as a press followed by a release about 90 ms later.

### Orientation

SDL sets the requested orientation to "full sensor" by itself during startup. On a phone held upright
this made the game open in portrait and rotate a moment later. `MainActivity` overrides
`setRequestedOrientation()` and always forces `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`, whatever the engine asks for.
The manifest uses `sensorLandscape` for the same reason.

### On-screen keyboard

The keyboard button uses SDL's own text-input machinery (`SDLActivity.showTextInput` and the
`SDLInputConnection`), so the user's regular keyboard appears. Letters, Backspace and Enter reach the game as key presses.
The activity uses `windowSoftInputMode="adjustNothing"` so the game surface is not resized when the keyboard opens.

---

## How the control mode is detected

This was the hardest part of the project, so it gets its own section.

The overlay needs to know one thing: **is the player in a level with the menu closed (game layout), or anywhere else (menu layout)?**

### The signal that does not work

SDL reports "mouse grabbed / released". It looks like the natural signal, but Crispy Doom in fullscreen mode
grabs the mouse **always**, including while the menu is open. The signal therefore says nothing about the menu.
It is still logged (`mouse grab = ...`) but is ignored.

### The approach that works

`libcrispy.so` exports Doom's own state variables as dynamic symbols. The overlay reads them directly from the
engine's memory about every 100 ms:

| Variable | Meaning |
|---|---|
| `gamestate` | 0 = level, 1 = intermission, 2 = finale, 3 = demo/title screen |
| `menuactive` | 1 while the Doom menu (or a Y/N prompt) is open |
| `usergame` | 1 during a real game (as opposed to demos) |
| `demoplayback` | 1 while a demo is playing |

Resulting rules:

- **Game layout:** `gamestate == 0` and `usergame == 1` and `demoplayback == 0` and `menuactive == 0`.
- **Menu layout:** everything else.
- **Advance mode** (OK and BACK send `Space`): `gamestate` is 1 or 2, `menuactive == 0`, `demoplayback == 0`.
- **▶ instead of ☰:** the menu is open on top of a running game.

### How the variables are located

1. The library is stored uncompressed inside `base.apk`, so `/proc/self/maps` shows it as `base.apk` with a file offset.
   The name `libcrispy.so` does not appear there.
2. The overlay reads the APK's zip directory, finds the entry `lib/arm64-v8a/libcrispy.so` and computes the offset of its data.
3. It finds the mapping in `/proc/self/maps` whose file offset equals that value. Its start address is the library base.
   (If the library is extracted to disk, it is found by name instead.)
4. It parses the ELF `.dynsym` section of the library, directly from the APK or the file, and looks up the four symbols by name.
   Addresses are `base + symbol value`.
5. Values are read through `/proc/self/mem`, with `sun.misc.Unsafe` as a fallback. Every address is checked
   against the mapped, readable ranges first, so a wrong offset cannot crash the app.
6. Sanity checks (`gamestate` in 0..3, the other three in 0..1) reject garbage reads.

### Fallback

If the engine state cannot be read for several seconds, the overlay switches to **manual mode**:
☰ toggles to the menu layout and ▶ toggles back. The overlay keeps retrying in the background and leaves manual mode as soon as reading works.

---

## Button reference

| Button | Menu layout | Game layout |
|---|---|---|
| Fire / OK | `Enter` (on intermission/finale: `Space`) | `Ctrl` (fire) |
| Use / BACK | `Esc` (on intermission/finale: `Space`) | `Space` (use) |
| ☰ | Opens or closes the Doom menu (`Esc`) | Opens the Doom menu and switches to the menu layout |
| ▶ | Closes the menu and returns to the game layout | not shown |
| Y / N | `Y` / `N` | in the "more" strip |
| QS / QL | `F6` / `F9` | in the "more" strip |
| Keyboard | Toggles the system on-screen keyboard | in the "more" strip |

In game, the Y / N / QS / QL / keyboard row is hidden behind the "more" button.
In the menu layout it is always visible.

---

## Development history: what broke and how it was fixed

The project was developed iteratively, with logs from a real device (Xiaomi, Snapdragon, Android 14+) driving each step.

### 1. The layout did not follow the game

- **Symptom:** after starting a level the menu buttons stayed on screen. Opening the settings dialog "fixed" it.
- **Cause:** switching depended on the SDL mouse-grab callback, and the callback was filtered by "a tap happened and 4 seconds have passed since startup".
  The grab signal arrived once, early, was discarded, and never came again. Opening a dialog made SDL re-grab the mouse, which is why settings "helped".
- **First attempt:** remember the grab state and poll it. This made things worse (the layouts became inverted).

### 2. The grab signal turned out to be useless

- **Finding:** inspecting `libcrispy.so` showed that Crispy Doom grabs the mouse always in fullscreen, so the signal is the same in menu and in game.
- **Fix:** stop using the signal. The overlay now reads the game state directly, as described above.

### 3. The engine library was "not mapped"

- **Symptom (from the log):** `engine probe disabled: libcrispy.so not mapped`.
- **Cause:** the library is stored uncompressed in the APK, so it never appears by name in `/proc/self/maps`.
  The probe also gave up for good after five failures in half a second, before the library had even been loaded.
- **Fix:** locate the base through the APK entry offset; retry with backoff instead of giving up; fall back to manual mode only after a long failure streak.

### 4. Intermission and finale screens could not be skipped

- **Cause:** Doom advances them with game keys (use/fire), not with `Enter` or `Esc`.
- **Fix:** an "advance mode" (`gamestate` 1 or 2 with the menu closed) in which OK and BACK send `Space`.

### 5. The game opened in portrait

- **Cause:** SDL requests `FULL_SENSOR` orientation during startup.
- **Fix:** override `setRequestedOrientation()` in `MainActivity` and use `sensorLandscape` in the manifest.

### 6. Rebuilding the native library broke detection again

- **Symptom:** after adding SDL2_mixer and rebuilding `libcrispy.so`, the log showed `bad values gs=-185339146 ...`.
- **Cause:** variable addresses were hard-coded for the old build and shifted after the rebuild.
- **Fix:** read the addresses at runtime from the library's `.dynsym` by symbol name. Hard-coded offsets remain only as a last-resort fallback.

### 7. On-screen keyboard

Added as a fifth button in the Y / N / QS / QL row, using the system keyboard through SDL's text-input path.
The alternative (a custom in-app keyboard that sends key events directly) is more predictable
but takes screen space; it remains an option if the system keyboard proves unreliable.

---

## Building

Requirements:

- Android Studio (or another Gradle-based Android environment) with JDK 17
- Android SDK Platform 36
- A device or emulator with an arm64 ABI

Steps:

```bash
./gradlew assembleDebug
# the APK ends up in app/build/outputs/apk/debug/
```

The three native libraries must already be present in `app/src/main/jniLibs/arm64-v8a/`.
They are not built by this Gradle project.

Notes:

- Native libraries are stored uncompressed in the APK (the Android Gradle Plugin default for minSdk 23+).
  The engine-state reader relies on this: it needs the library to be a stored (not deflated) APK entry.
- Only `arm64-v8a` is supported. The reader looks for the entry `lib/arm64-v8a/libcrispy.so`.

---

## Updating libcrispy.so

You can rebuild or replace `libcrispy.so` without touching the Java code, as long as the engine still **exports** these symbols:

```
gamestate   menuactive   usergame   demoplayback
```

Check with:

```bash
nm -D app/src/main/jniLibs/arm64-v8a/libcrispy.so | grep -E "gamestate|menuactive|usergame|demoplayback"
```

If the output is empty (for example, symbol visibility was set to hidden in the build), the overlay cannot find the state variables
and falls back to manual mode. Either remove the visibility restriction for these four variables or export them explicitly.

---

## Troubleshooting

Filter `logcat` by the tag `CrispyDoom`. The relevant lines:

| Log line | Meaning |
|---|---|
| `libcrispy data offset in apk = 0x...` | The library entry was found in the APK. |
| `libcrispy base = 0x... (by apk offset)` | The library base address was found. |
| `symbols: gamestate=0x... menuactive=0x... ...` | The four symbols were resolved from `.dynsym`. |
| `engine: gamestate=... menuactive=... usergame=... demoplayback=...` | Printed on every state change. This is the main thing to watch. |
| `symbol lookup failed, using built-in offsets` | The symbols are not exported or the ELF could not be parsed. |
| `engine probe: ... bad values ...` | Wrong addresses; the library changed and symbol lookup did not succeed. |
| `engine probe: ... not mapped` | The library is not loaded yet (normal for the first moments after launch). |
| `mouse grab = ... (ignored)` | SDL's grab callback, kept for diagnostics only. |

Common problems:

- **The layout does not switch to the game controls.** Check that `symbols:` and then `engine:` lines appear. If they do not, see "Updating libcrispy.so".
- **Letters do not appear in a savegame name.** The keyboard itself works through SDL, but text handling lives in the engine build.
  Check that the engine accepts the typed characters; if not, a custom keyboard that sends key events directly is the alternative.
- **The picture jumps when the keyboard opens.** Make sure `android:windowSoftInputMode="adjustNothing"` is present in the manifest.

---

## Known limitations

- `arm64-v8a` only.
- Engine-state detection depends on four exported symbols and on an uncompressed library entry in the APK.
- Reading engine memory through `/proc/self/mem` is an implementation trick, not a public API.
  It is guarded by range checks and a fallback, but it may behave differently on unusual ROMs.
- Keyboard text input depends on how the bundled Crispy Doom build handles SDL text events.
- Only the shareware IWAD is bundled. To play other IWADs the WAD handling in `MainActivity` has to be extended.

---

## Licenses

- The Android-specific code in this repository (`MainActivity`, `TouchControlsView`) is released under the **GNU General Public License v2.0**. See [LICENSE](LICENSE).
Since the project links and ships GPL code, the complete source must be made available to anyone you distribute the APK to.
