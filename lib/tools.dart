import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// What the phone's side says of every tool's settings now (tools/Shell.kt `tools`): each value is the one the
/// Kotlin code itself reads, out of the same preferences, so a page here and a service there never disagree.

/// The dock's settings ("dock" preferences), and what this screen of the phone allows.
class DockState {
  DockState(Map m)
      : on = m['on'] as bool,
        overlay = m['overlay'] as bool,
        camera = m['camera'] as bool,
        cameraAt = (m['cameraAt'] as String?) ?? '',
        guess = m['guess'] as bool,
        beside = m['beside'] as bool,
        taps = m['taps'] as bool,
        side = m['side'] as String,
        vertical = m['vertical'] as bool,
        blur = m['blur'] as bool,
        heat = m['heat'] as bool,
        fahrenheit = m['fahrenheit'] as bool,
        awake = m['awake'] as bool,
        frames = m['frames'] as bool;

  /// Showing: switched on, and allowed to draw over other apps.
  final bool on;
  final bool overlay;

  /// Whether where this screen's camera is, is known; [cameraAt] says where, and [guess] that it was never lined up.
  final bool camera, guess;
  final String cameraAt;

  /// Beside the camera on this screen, and whether a tap reaches it there (the accessibility service is on).
  final bool beside, taps;

  /// "left", "right" or "both": where its parts stand about the camera.
  final String side;
  final bool vertical, blur, heat, fahrenheit, awake, frames;
}

/// Amphetamine's settings ("awake" preferences) and its session.
class AwakeState {
  AwakeState(Map m)
      : on = m['on'] as bool,
        forMs = (m['forMs'] as num).toInt(),
        why = m['why'] as String,
        bright = m['bright'] as bool,
        endOnBattery = m['endOnBattery'] as bool,
        batteryFloor = (m['batteryFloor'] as num).toInt(),
        endUnplugged = m['endUnplugged'] as bool,
        endFolded = m['endFolded'] as bool,
        startCharging = m['startCharging'] as bool,
        apps = [for (final a in (m['apps'] as List? ?? const [])) a as String],
        appNames = [for (final a in (m['appNames'] as List? ?? const [])) a as String],
        seesApps = m['seesApps'] as bool,
        method = m['method'] as String,
        overlay = m['overlay'] as bool,
        system = m['system'] as bool;

  final bool on;

  /// How long the session has been on, in milliseconds.
  final int forMs;

  /// "tap", "charger" or "app": why it is on.
  final String why;
  final bool bright, endOnBattery, endUnplugged, endFolded, startCharging;
  final int batteryFloor;

  /// The apps a session is kept on for while one is in front: their packages, and their names in order.
  final List<String> apps, appNames;

  /// Whether Android lets the app know which app is in front.
  final bool seesApps;

  /// "overlay" (an unseen window) or "system" (the phone's own timeout), and whether each is allowed.
  final String method;
  final bool overlay, system;

  bool get canHold => overlay || system;
}

/// What each permission stands at.
class Granted {
  Granted(Map m)
      : notifications = m['notifications'] as bool,
        overlay = m['overlay'] as bool,
        usage = m['usage'] as bool,
        access = m['access'] as bool,
        write = m['write'] as bool,
        dump = m['dump'] as bool,
        secure = m['secure'] as bool;

  final bool notifications, overlay, usage, access, write, dump, secure;

  /// How many of the three every tool leans on are still to give.
  int get missing => [notifications, overlay, usage].where((given) => !given).length;
}

/// What this build is, as the phone knows it.
class BuildState {
  BuildState(Map m)
      : version = m['version'] as String,
        code = (m['code'] as num).toInt(),
        package = m['package'] as String,
        device = m['device'] as String,
        android = m['android'] as String,
        installed = (m['installed'] as String?) ?? '';

  final String version, package, device, android, installed;
  final int code;
}

class ToolsState {
  ToolsState(Map m)
      : dock = DockState(m['dock'] as Map),
        awake = AwakeState(m['awake'] as Map),
        granted = Granted(m['granted'] as Map),
        build = BuildState(m['build'] as Map),
        celsius = (m['celsius'] as num?)?.toDouble();

  final DockState dock;
  final AwakeState awake;
  final Granted granted;
  final BuildState build;

  /// The battery's temperature, or null where the phone gives none.
  final double? celsius;

  /// The temperature as the dock says it.
  String get heat => celsius == null ? 'No reading' : dock.fahrenheit ? '${(celsius! * 9 / 5 + 32).round()}°F' : '${celsius!.round()}°C';
}

/// An app on the phone that can be opened, for choosing among.
class AppChoice {
  AppChoice(Map m)
      : package = m['package'] as String,
        label = m['label'] as String;
  final String package, label;
}

/// A length of time as Amphetamine's own words say it: "42s", "5m12s", "1h30m5s" (amphetamine/State.kt `span`).
String spanOf(int ms) {
  final total = (ms < 0 ? 0 : ms) ~/ 1000;
  final parts = [(total ~/ 86400, 'd'), (total % 86400 ~/ 3600, 'h'), (total % 3600 ~/ 60, 'm'), (total % 60, 's')];
  var from = parts.indexWhere((p) => p.$1 > 0);
  if (from < 0) from = parts.length - 1;
  return parts.skip(from).map((p) => '${p.$1}${p.$2}').join();
}

/// The door to the tools' settings on the phone's side. The same channel as Frames' own ([Phone], phone.dart), whose
/// Kotlin end is one activity.
class Tools {
  static const _channel = MethodChannel('frames');
  static final ValueNotifier<ToolsState?> state = ValueNotifier(null);

  static Future<ToolsState> refresh() async {
    final now = ToolsState(await _channel.invokeMethod('tools') as Map);
    state.value = now;
    return now;
  }

  /// Sets one setting by its name ("dock.blur", "awake.bright"), through whatever the Kotlin side does when its own
  /// screen changed it: the dock made again, the session told. Answers "asked" where a permission was wanted first
  /// and its setting has been opened.
  static Future<String?> set(String name, Object? value) async {
    final answer = await _channel.invokeMethod<String>('set', {'name': name, 'value': value});
    await refresh();
    return answer;
  }

  /// Opens one of Android's own settings screens: "overlay", "usage", "accessibility", "write", "notifications",
  /// or "setup" for the app's own walk through them.
  static Future<void> openSetting(String which) => _channel.invokeMethod('openSetting', which);

  /// Every app that can be opened, by name.
  static Future<List<AppChoice>> apps() async => [for (final a in (await _channel.invokeMethod<List>('apps')) ?? const []) AppChoice(a as Map)];

  /// The page the app was opened at (a tile held down, the dock's notification, a tap on the dock), or null for
  /// the page of tools.
  static Future<String?> launch() => _channel.invokeMethod<String>('launch');

  /// Told each time the app, already open, is asked for a page.
  static void onOpen(void Function(String page) open) {
    _channel.setMethodCallHandler((call) async {
      if (call.method == 'open' && call.arguments is String) open(call.arguments as String);
      return null;
    });
  }
}
