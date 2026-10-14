# Matt's Tools

Small tools for an Android phone, kept in one app.

| Tool | What it does |
| --- | --- |
| **Dock** | One small pill over whatever is on screen: the phone's temperature, how long Amphetamine has been on, and the rate Frames is measuring. It floats where it is dragged, or sits beside the camera with the hole inside its frame. |
| **Amphetamine** | Keeps the screen on from a quick settings tile, with a second tile for full brightness, a home screen widget, and ways to end a session by itself (low battery, unplugged, the phone folded shut). |
| **Frames** | Measures frames a second and janky frames for the app in front, and what the app costs the phone meanwhile (processor, memory, battery, data); keeps the runs per app and build, ranks apps, and sets two apps or two builds side by side. Exports JSON. |
| **Toasty** | The phone's temperature, from its battery. |
| **X-ray** | Shows what an app is made of: its frameworks, libraries and assets from its package files, its screen as a tree of boxes read the way each kind of app gives it (native views, Jetpack Compose, React Native, Flutter), and the compositor's layers. Started from a quick settings tile, it outlines the boxes over the app in front, coloured by depth; its page walks the tree and turns it as planes. |

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

X-ray has an accessibility service of its own (`xray/XrayAccess.kt`), apart from the dock's, so the dock's words stay
true. Switched on it asks for no events and reads nothing; started from X-ray's tile it reads the app in front on a
thread of its own, draws the outlines in two windows of its own (`xray/Overlay.kt`), and holds the last reading in
memory only. It never reads a password field, takes no picture of the screen and has no network permission.

Where a phone's camera is on its screen comes from the phone's own cut-out; for a screen that reports none (a Fold's
inner screen) it is kept in `Phones` in `dock/Dock.kt`.

## Building

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=$HOME/Library/Android/sdk
flutter build apk --release --target-platform android-arm64
```

The release build is signed with the debug key: it is for installing by hand, not for a store.

## Setting up on a phone

The app's first page walks through the permissions. Frames, and X-ray's compositor layers, need one more, given once from a computer:

```bash
adb shell pm grant com.mattssoftware.tools android.permission.DUMP
```

Frames' hardware use (`frames/Hardware.kt`, whose first comment says where each number comes from and what Android
keeps closed) reads the whole phone's processor, the app's memory and data and the phone's battery draw with what
is given above. The app's own share of the processor is the system's count, which wants one more, optional:

```bash
adb shell pm grant com.mattssoftware.tools android.permission.BATTERY_STATS
```

The reading of the phone's reports is tried on a computer: `cd android && ./gradlew :app:testDebugUnitTest`.

## Measuring an app screen by screen, from a computer

`tool/tabs.py <device>` prints the labels and places of the tab bar of the app in front. `tool/screens.py <device>
<package> '<[["Home",139,1696], ...]>' <x to swipe at>` taps each of those in turn, scrolls up and down for seven
seconds, and prints frames a second, the 1% low, the share of janky frames and the frame times for each, read from
the compositor as Frames reads them on the phone. It only taps the places it is given and swipes up and down.

## A new tool by pull request, made by Claude Code on its own

`tool/new-tool-pr/run.sh` takes a brief (`tool/new-tool-pr/briefs/xray.md` unless another is named) and has Claude
Code build what it describes in a worktree beside this repository, on a branch named for the brief, and open a pull
request. It pays with the `CLAUDE_KEY` in the `.env` at the top of the repository, which git ignores, and stops at
once if Claude Code starts up paying any other way. It never merges, never pushes to main and never touches a phone:
`guard.py` is asked before every command and refuses those.

    tool/new-tool-pr/run.sh --check     looks at everything a run needs and starts nothing
    tool/new-tool-pr/run.sh --key       asks the API whose key it is and whether it can spend
    tool/new-tool-pr/run.sh --smoke     one small agent, for cents: the key pays, the workflow runs, the guard holds
    tool/new-tool-pr/run.sh             the run itself: $180 and eight hours at most (BUDGET_USD, HOURS)

The work is one saved workflow (`workflow.js`, started with the word "ultracode"): the repository and the platform
are read, a plan is made and attacked, the tool is built in packages, checked, tried on the emulator, reviewed three
ways, mended, and put up for review. A run that stops part way goes on from where it stopped when started again.
