# X-ray: see what an app is made of

A brief for `tool/new-tool-pr/run.sh`. It is the whole of what is asked: read all of it.

## What Matt asked

> "Add another tool to create a pull request for a new app in Matt's tools, id like it to use tools to break apart apps and see their layers with inspectors for react native flutter and native solutions and visualize the apps layers with an overlay over the app"

Asked on 2026-10-12, after an evening of measuring a Flutter build and a React Native build of the same app against
each other with Frames. He builds apps in all three ways and wants to look inside any app on his phone, his own or
someone else's, without a computer.

Things he has said about this app that hold here too:
- "keep it simple but well featured"
- "Keep the same astetic as ghostmd" (ink and paper, Inter, the kit the other pages use)
- "if there is a setting that needs turned on the app should prompt immediatly when tapping"
- Of text squeezed into a small space: "crammed", "so small". Nothing here is drawn too small to read.

## What it is

A fifth tool in Matt's Tools beside the Dock, Amphetamine, Frames and Toasty. Its name is **X-ray** (Kotlin package
`com.mattssoftware.xray`). Pointed at the app in front, it shows what that app is built with and what its screen is
made of, and draws that over the app itself.

Four parts:

1. **Anatomy** ("break apart apps"). For any installed app: what it is built with (native views, Jetpack Compose,
   Flutter, React Native, and the other common ways: Unity, .NET, a web view shell, and so on), read from what is
   inside its package: its native libraries with their sizes, its engines, its assets by kind, how many dex files, its
   SDK levels. Per framework, the details that framework has: for Flutter the engine and the compiled app library,
   whether it draws with Impeller, its fonts and packages where the assets say; for React Native whether it runs
   Hermes, whether it is on the new architecture, the size of its bundle, Expo if present; for native whether Compose
   is in it.

2. **The live tree** ("see their layers"). What is on the screen of the app in front, as a tree of boxes: each with
   its place, its depth, its kind, its name where it has one. Read the way each kind of app exposes it, by an
   **inspector for each kind**:
   - **Native**: the views themselves, with their classes and resource ids; Compose nodes as Compose exposes them.
   - **React Native**: the native views React Native made, with the `testID` of each where the app gave one, and
     the tree read in React Native's terms as far as what can be read allows.
   - **Flutter**: the semantics tree Flutter gives to an accessibility service (labels, actions, places), and the
     surface it draws to. Widget names are not in a release build and must not be made up: say they are not there.
   Which inspector applies is decided from the anatomy and from what the tree itself shows; an app that is two
   things at once (a native shell round a Flutter view) gets both.

3. **Compositor layers**. The surfaces the system composes for that app (its window, a Flutter or video surface,
   and so on), as Frames already reads them, each with its place where the phone says it.

4. **The overlay** ("visualize the apps layers with an overlay over the app"). Drawn over the app in front: an
   outline round each box of the tree, coloured by depth. With it, a small bar of controls that never covers the
   whole screen:
   - **Peel**: show only the boxes down to a depth, or only one depth, a step at a time.
   - **Select**: while on, a tap on the screen picks the innermost box there and shows what it is (kind, name, place,
     depth, what can be done to it); while off, every touch goes through to the app and the outlines keep up as the
     app changes.
   - **Layers**: the compositor's surfaces outlined instead of the tree.
   - **Open**: X-ray's page at the box that is picked.
   - **Close**: everything X-ray drew is gone.
   X-ray is started and stopped on the app in front from a **quick settings tile** (as Amphetamine and Frames are),
   since opening X-ray's own page would put Matt's Tools in front instead.

X-ray's page in the app has: the anatomy of an app chosen from a list (with its icon); the tree last captured, to
walk through; and an **exploded view** of that capture: its boxes as planes stacked by depth that can be turned with
a finger, in outline (no picture of the screen is taken).

## What must be so

- **Its own accessibility service**, switched on only by someone who wants X-ray. The Dock's service says of itself
  that it reads nothing on the screen, and that stays true: do not change the Dock's service or its words.
- **It reads only while X-ray is on.** Switched on in the phone's settings but not inspecting, it asks for no events
  and reads no tree.
- **Nothing leaves the phone, and nothing is kept.** No network permission. A capture lives in memory until the
  next one or until X-ray is closed. Nothing of another app's screen is written to disk or to the log (the log may
  say counts and timings, never text or names). No picture of the screen is taken in this pull request.
- **What is typed in a field that hides it is never read or shown.** Text of other boxes may be shown on the phone,
  since the person is looking at their own screen, but only as much as tells boxes apart.
- **A person can always get out.** The controls are small and can be moved. Close always works. With Select off the
  app underneath works as if X-ray were not there. If the service is switched off or the app is updated while X-ray
  is drawing, what it drew is gone.
- **What it says of itself is true**: the service's description, the step in the setting-up, the page. It says
  plainly what it can read of each kind of app and what it cannot.
- **It asks at once**: tapping the tile or the page with the service off goes straight to the setting, with a line
  saying what to switch on, and X-ray is on when they come back.
- The other tools go on working exactly as they do.

## Not in this pull request

Say each of these in the pull request under "Left for later":
- A picture of the screen cut into the exploded view's planes (it needs the right to take screenshots).
- Flutter's widget, render and layer trees from a debug build's VM service.
- React Native's component tree from a development build.
- Saving or sharing a capture.
- Anything on the website, a version bump, a release.

## What must be found out before it is planned

The plan is to be built on what is true. For each of these find the answer in a primary source, and say where:

1. What an accessibility service must declare to read the whole tree of the app in front (`canRetrieveWindowContent`,
   the flags for interactive windows, view ids and views not marked important), from which API level each, and the
   difference between the active window's root and the list of windows.
2. Whether a service can ask for no events while idle and change that while running (`setServiceInfo`), and what it
   is still sent.
3. `isAccessibilityTool` and `accessibilityDataSensitive` (API 34): what a service that is not an accessibility tool
   is not shown.
4. For each kind of app, what a node reports as its class name, its resource id, its text, its drawing order and its
   place: native views; Jetpack Compose; React Native (do its views report their own classes or the platform's? does
   `testID` arrive as the resource id, and from which version?); Flutter (when does it build its semantics tree for a
   service, and what do its nodes report?).
5. Windows of an accessibility service drawn over everything: where they sit against the status bar, the keyboard
   and system dialogs; how a window takes touches only on its controls, or everywhere only while selecting.
6. Reading another app's package from an app: whether its `.apk` files and its native library directory can be read
   on Android 10 to 17 with `QUERY_ALL_PACKAGES`, and what it costs for a large app.
7. What inside a package tells each framework apart, by file names first (a native library, an asset path, a
   `META-INF` version file) and by classes in the dex only where nothing cheaper will do: Flutter, React Native
   (Hermes, the new architecture, Expo), Jetpack Compose, Unity, .NET, Cordova and Capacitor, and any other that is
   common. With a source for each.
8. The compositor's own report (`dumpsys SurfaceFlinger --list` and the whole dump) on Android 10 to 17: where a
   layer's place, order and size are, and how they differ between versions.
9. Starting something from a quick settings tile while another app stays in front, on Android 14 and later.

## What is already known, from Matt's phone

Seen on his Galaxy Z Fold 8 (Android 17, One UI 9) on 2026-10-12. Trust these over memory:

- The compositor names a layer `RequestedLayerState{<name> parentId=<id> ...}` in `--list`. A window's frames are on
  `VRI-<package>/<activity>$_<pid>#<id>`; a Flutter app draws to `<hex> SurfaceView[<package>/<activity>]@0(BLAST)#<id>`,
  and the hex is part of the name: asked for without it, the phone answers with nothing. The window's plain layer
  `<hex> <package>/<activity>#<id>` is listed too and holds no frames. Ids change every time the app comes forward,
  and only the app in front has a `VRI-` layer.
- A React Native app's frames were on its `VRI-` layer, like a native app's; a Flutter app's on its `SurfaceView`.
- Read from the tree with a computer's tools, a Flutter build of one app showed about 110 nodes and its React Native
  build about 480, both with the labels of their tab bars.
- An app can read `dumpsys SurfaceFlinger` once it has been given `android.permission.DUMP` from a computer. It
  cannot reach `gfxinfo`, `meminfo` or `cpuinfo` even then.
- Apps that deal in money mark their windows secure: a screenshot of them is empty.
- The status bar takes every touch on its strip from a window an app draws over other apps. A window of an
  accessibility service is drawn above the status bar and does get touches there; the Dock uses one.
- The Settings app hides windows that apps draw over it. Whether it hides an accessibility service's window too is
  not known: find out on the emulator.
- Android refuses to start a foreground service from the app's start-up after an update; services are taken up again
  from the receiver of `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`.
- On the emulator an accessibility service can be switched on from a computer with
  `settings put secure enabled_accessibility_services <package>/<class>` and `settings put secure accessibility_enabled 1`.
- The released app cannot be looked into with `run-as`; what it does is read from its log and from `dumpsys`.
- His phone has two screens (2448 by 1848 open, 1248 by 1972 shut) and the Dock may be sitting beside the camera on
  either: X-ray's controls must not land under it.

## Samples beside this brief

In the directory beside this file (`brief/` in the run's scratch, `briefs/xray/` in the repository), from the Fold,
with the names of other people's apps replaced by `com.example.appN`:

- `fold8-android17-layers-flutter-in-front.txt`: `dumpsys SurfaceFlinger --list` with a Flutter app (Ghost.md,
  `com.mattssoftware.glyph`) in front.
- `fold8-android17-layers-native-in-front.txt`: the same with a native app in front (`com.example.app8`).
- `fold8-android17-latency-sample.txt`: `--latency` for a window's `VRI-` layer: the refresh period, then a row a
  frame.

Tests of the layer reader run against these, so that it is right on the phone it will be used on and not only on
the emulator.

## How it is known to work

On the emulator, by someone with only `adb`:

1. X-ray is on the first page with an icon of its own (the kit's "layers" glyph), opens its page, and looks like the
   other tools' pages.
2. The setting-up has a step for X-ray's switch that can be passed over and says what it reads. With the switch off,
   X-ray's page and its tile both go to the setting.
3. With the Settings app in front and X-ray started from its tile: outlines sit on the things they are of (look at a
   screenshot); Peel changes which are drawn; with Select on a tap picks a box and says what it is; with Select off a
   tap opens the row under it and the outlines follow; Close leaves nothing behind.
4. The anatomy of Matt's Tools itself says Flutter and native and names its engine library with its size; the
   anatomy of the Settings app says native.
5. With a Flutter page in front (Matt's Tools' own Frames page), X-ray says it is a Flutter surface, shows the
   semantics boxes, and says that widget names are not to be had.
6. The compositor's layers for the app in front are listed, the Flutter surface among them.
7. X-ray's page shows the tree last captured with its count, a box can be opened, and the exploded view turns.
8. With the switch on and X-ray off, using another app for half a minute, the log shows it read nothing.
9. Turning the screen, leaving the app, and the screen going off: the overlay keeps up or goes, and nothing crashes.
10. The Dock, Amphetamine and Frames do what they did: their tests pass.

There is no React Native app on the emulator. Its inspector is tested against trees written out by hand from what
the facts establish, and the pull request says it has not been seen on a real one.
