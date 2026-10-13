# Matt's Tools

Small tools for an Android phone, kept in one app.

| Tool | What it does |
| --- | --- |
| **Dock** | One small pill over whatever is on screen: the phone's temperature, how long Amphetamine has been on, and the rate Frames is measuring. It floats where it is dragged, or sits beside the camera with the hole inside its frame. |
| **Amphetamine** | Keeps the screen on from a quick settings tile, with a second tile for full brightness, a home screen widget, and ways to end a session by itself (low battery, unplugged, the phone folded shut). |
| **Frames** | Measures frames a second and janky frames for the app in front, keeps the runs per app and build, ranks apps fastest to slowest, and sets two apps or two builds side by side. Exports JSON. |
| **Toasty** | The phone's temperature, from its battery. |

## How it is put together

Each tool was an app of its own and keeps its own package under `android/app/src/main/kotlin/com/mattssoftware/`:
`dock`, `amphetamine`, `frames`, and `tools` (the app's one activity and the setting-up). What each tool does is
Kotlin with no libraries; every page is Flutter, drawn with the Glacier kit (`lib/`, `packages/glacier`).

The pages share one frame (`lib/shell/app_shell.dart`): a bar over each with home, back and forward through where
you have been, and the Settings cog, as Ghost.md has it. Android's back does what the back arrow does. Settings
(`lib/settings/`) is Ghost.md's settings screen and its kit of cards and rows, copied, with a page for each tool and
About; every setting of every tool is a row there. A row reads and writes the tool's own preferences through the
activity (`tools/Shell.kt`: `tools` says what they stand at, `set` changes one the way the tool itself would), so a
service already running takes a change up at once. A tile held down, the dock's notification and a tap on the dock
open the app at the right page (`Shell.intent`).

The dock beside the camera is drawn through an accessibility service (`dock/DockAccess.kt`), because the status bar
takes every touch on its strip from a window drawn over apps and an accessibility service's window is the one kind
Android draws above it. The service asks for no events and reads nothing on the screen.

Where a phone's camera is on its screen comes from the phone's own cut-out; for a screen that reports none (a Fold's
inner screen) it is kept in `Phones` in `dock/Dock.kt`.

## Building

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=$HOME/Library/Android/sdk
flutter build apk --release --target-platform android-arm64
```

The release build is signed with the debug key: it is for installing by hand, not for a store.

## Setting up on a phone

The app's first page walks through the permissions. Frames needs one more, given once from a computer:

```bash
adb shell pm grant com.mattssoftware.tools android.permission.DUMP
```

## Measuring an app screen by screen, from a computer

`tool/tabs.py <device>` prints the labels and places of the tab bar of the app in front. `tool/screens.py <device>
<package> '<[["Home",139,1696], ...]>' <x to swipe at>` taps each of those in turn, scrolls up and down for seven
seconds, and prints frames a second, the 1% low, the share of janky frames and the frame times for each, read from
the compositor as Frames reads them on the phone. It only taps the places it is given and swipes up and down.
