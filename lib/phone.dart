import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// What the phone's side says now (MainActivity.kt `state`).
class PhoneState {
  PhoneState(Map m)
      : running = m['running'] as bool,
        dump = m['dump'] as bool,
        usage = m['usage'] as bool,
        overlay = m['overlay'] as bool,
        secure = m['secure'] as bool,
        hud = m['hud'] as bool,
        hardware = (m['hardware'] as bool?) ?? true,
        batteryStats = (m['batteryStats'] as bool?) ?? false,
        corner = m['corner'] as int,
        scenario = m['scenario'] as String,
        sessions = m['sessions'] as String,
        package = m['package'] as String,
        animation = (m['animation'] as num).toDouble(),
        awake = m['awake'] as bool,
        live = m['live'] == null ? null : Live(m['live'] as Map);

  final bool running, dump, usage, overlay, secure, hud, awake;

  /// Whether a run also reads what the app costs the phone, and whether the phone lets Frames read another app's
  /// processor time (android.permission.BATTERY_STATS, given from a computer).
  final bool hardware, batteryStats;
  final int corner;
  final String scenario, sessions, package;
  final double animation;
  final Live? live;
  bool get ready => dump && usage;
}

class Live {
  Live(Map m)
      : app = m['app'] as String,
        screen = (m['screen'] as String?) ?? '',
        fps = m['fps'] as int,
        frames = m['frames'] as int,
        jankPct = (m['jankPct'] as num).toDouble(),
        hz = (m['hz'] as num).toDouble(),
        recent = [for (final v in (m['recent'] as List? ?? const [])) (v as num).toDouble()];
  final String app, screen;
  final int fps, frames;
  final double jankPct, hz;
  final List<double> recent;
}

class Phone {
  static const _channel = MethodChannel('frames');
  static final ValueNotifier<PhoneState?> state = ValueNotifier(null);
  static final Map<String, Future<Uint8List?>> _icons = {};

  static Future<PhoneState> refresh() async {
    final now = PhoneState(await _channel.invokeMethod('state') as Map);
    state.value = now;
    return now;
  }

  static Future<T?> _do<T>(String method, [Object? args]) async {
    final answer = await _channel.invokeMethod<T>(method, args);
    await refresh();
    return answer;
  }

  static Future<void> toggle() => _do('toggle');
  static Future<void> scenario(String words) => _do('scenario', words);
  static Future<void> corner(int at) => _do('corner', at);
  static Future<String?> hud() => _do<String>('hud');
  static Future<void> hardware(bool on) => _do('hardware', on);
  static Future<void> animation(double scale) => _do('animation', scale);
  static Future<void> awake(bool on) => _do('awake', on);
  static Future<String?> diagnostics() => _channel.invokeMethod<String>('diagnostics');
  static Future<String?> probe() => _channel.invokeMethod<String>('probe');
  static Future<void> delete(String pkg, [int? startedAt]) => _channel.invokeMethod('delete', {'package': pkg, 'startedAt': startedAt});
  static Future<bool> export({String? pkg, int? startedAt}) async => await _channel.invokeMethod<bool>('export', {'package': pkg, 'startedAt': startedAt}) ?? false;

  /// The app's own icon, as a picture; null for an app no longer on the phone.
  static Future<Uint8List?> icon(String pkg) => _icons[pkg] ??= _channel.invokeMethod<Uint8List>('icon', pkg).catchError((_) => null);
}
