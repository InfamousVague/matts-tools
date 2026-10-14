import 'package:flutter/services.dart';

import '../tools.dart';

/// X-ray's side of the one channel (tools/Shell.kt `xray` answers it, in xray/Door.kt): what an app is made of,
/// read on the phone from the files of its package, and the last reading of a screen. Every model reads its map with defaults, so an answer that
/// leaves a key out draws less and does not throw.

/// A file named in the anatomy, with its size in bytes where one is known.
class Named {
  Named(Map m)
      : name = (m['name'] as String?) ?? '',
        size = (m['size'] as num?)?.toInt();
  final String name;
  final int? size;
}

/// One thing the package shows an app is built with, the files that show it, and what is told of it.
class KindOf {
  KindOf(Map m)
      : name = (m['name'] as String?) ?? '',
        evidence = [for (final e in (m['evidence'] as List?) ?? const []) Named(e as Map)],
        details = [for (final d in (m['details'] as List?) ?? const []) Detail(d as Map)];
  final String name;
  final List<Named> evidence;
  final List<Detail> details;
}

class Detail {
  Detail(Map m)
      : label = (m['label'] as String?) ?? '',
        value = (m['value'] as String?) ?? '',
        size = (m['size'] as num?)?.toInt();
  final String label, value;
  final int? size;
}

/// The libraries of one processor type.
class LibsOf {
  LibsOf(Map m)
      : abi = (m['abi'] as String?) ?? '',
        total = (m['total'] as num?)?.toInt() ?? 0,
        files = [for (final f in (m['files'] as List?) ?? const []) Named(f as Map)];
  final String abi;
  final int total;
  final List<Named> files;
}

class AssetsOf {
  AssetsOf(Map m)
      : kind = (m['kind'] as String?) ?? '',
        count = (m['count'] as num?)?.toInt() ?? 0,
        bytes = (m['bytes'] as num?)?.toInt() ?? 0;
  final String kind;
  final int count, bytes;
}

Map _map(Object? m) => (m as Map?) ?? const {};

/// What the phone's side says an app is made of (xray/AnatomyParse.kt `Result.toMap`), or why it cannot.
class Anatomy {
  Anatomy(Map m)
      : error = m['error'] as String?,
        package = (m['package'] as String?) ?? '',
        version = (m['version'] as String?) ?? '',
        headline = (m['headline'] as String?) ?? '',
        kinds = [for (final k in (m['kinds'] as List?) ?? const []) KindOf(k as Map)],
        libs = [for (final l in (m['libs'] as List?) ?? const []) LibsOf(l as Map)],
        assets = [for (final a in (m['assets'] as List?) ?? const []) AssetsOf(a as Map)],
        dexCount = (_map(m['dex'])['count'] as num?)?.toInt() ?? 0,
        dexBytes = (_map(m['dex'])['bytes'] as num?)?.toInt() ?? 0,
        minSdk = (_map(m['sdk'])['min'] as num?)?.toInt(),
        targetSdk = (_map(m['sdk'])['target'] as num?)?.toInt(),
        compileSdk = (_map(m['sdk'])['compile'] as num?)?.toInt(),
        files = [for (final f in (_map(m['apk'])['files'] as List?) ?? const []) Named(f as Map)],
        entries = (_map(m['apk'])['entries'] as num?)?.toInt() ?? 0,
        cannot = [for (final c in (m['cannot'] as List?) ?? const []) c as String];

  /// Why nothing can be said, in words for the page; null where something can.
  final String? error;
  final String package, version, headline;
  final List<KindOf> kinds;
  final List<LibsOf> libs;
  final List<AssetsOf> assets;
  final int dexCount, dexBytes, entries;

  /// The API levels it was built for and will run on, where the phone gives them.
  final int? minSdk, targetSdk, compileSdk;

  /// The package files read (the base and each split), with their sizes.
  final List<Named> files;

  /// What the package cannot say, one plain sentence each.
  final List<String> cannot;
}

/// One of the app's compositor layers (xray/LayerParse.kt `AppLayer.toMap`).
class CaptureLayer {
  CaptureLayer(Map m)
      : id = (m['id'] as num?)?.toInt() ?? 0,
        name = (m['name'] as String?) ?? '',
        kind = (m['kind'] as String?) ?? 'layer',
        words = (m['words'] as String?) ?? '',
        z = (m['z'] as num?)?.toInt() ?? 0,
        order = (m['order'] as num?)?.toInt() ?? 0,
        place = m['l'] == null ? null : Rect.fromLTRB(((m['l'] as num?) ?? 0).toDouble(), ((m['t'] as num?) ?? 0).toDouble(), ((m['r'] as num?) ?? 0).toDouble(), ((m['b'] as num?) ?? 0).toDouble());
  final int id, z;

  /// From the bottom, among the app's layers.
  final int order;

  /// The whole name the compositor gives it, and what it is: "window", "view root", "surface" and so on, and in words.
  final String name, kind, words;

  /// In the screen's own pixels; null where the phone does not give it.
  final Rect? place;
}

List<CaptureLayer>? _layers(Object? list) => list == null ? null : [for (final l in list as List) CaptureLayer(l as Map)];

/// What X-ray's page shows of the last reading of a screen before it is opened (xray/XrayAccess.kt `Capture.summary`).
class CaptureSummary {
  CaptureSummary(Map m)
      : package = (m['package'] as String?) ?? '',
        label = (m['label'] as String?) ?? '',
        count = (m['count'] as num?)?.toInt() ?? 0,
        windows = (m['windows'] as num?)?.toInt() ?? 0,
        inspectors = [for (final i in (m['inspectors'] as List?) ?? const []) i as String],
        at = (m['at'] as num?)?.toInt() ?? 0,
        picked = (m['picked'] as num?)?.toInt(),
        cut = (m['cut'] as bool?) ?? false,
        layers = _layers(m['layers']),
        layersWhy = m['layersWhy'] as String?;
  final String package, label;
  final int count, windows, at;

  /// The app's compositor layers, bottom to top; null where they were not read, with [layersWhy] saying why where they cannot be.
  final List<CaptureLayer>? layers;
  final String? layersWhy;

  /// The inspectors that read some box: "Native", "Jetpack Compose, likely", "React Native", "Flutter".
  final List<String> inspectors;
  final int? picked;

  /// The reading stopped at its limit of boxes or depth.
  final bool cut;
}

/// X-ray's switch, whether it is drawing, and the last reading, if one is held (xray/Door.kt `state`).
class XrayState {
  XrayState(Map m)
      : on = (m['on'] as bool?) ?? false,
        running = (m['running'] as bool?) ?? false,
        dump = (m['dump'] as bool?) ?? false,
        capture = m['capture'] == null ? null : CaptureSummary(m['capture'] as Map);

  /// Switched on in Accessibility, by the service's whole name.
  final bool on;
  final bool running, dump;
  final CaptureSummary? capture;
}

/// One box of a reading (xray/XrayTree.kt `Box.toMap`).
class CaptureBox {
  CaptureBox(Map m)
      : i = (m['i'] as num?)?.toInt() ?? 0,
        parent = (m['parent'] as num?)?.toInt(),
        depth = (m['depth'] as num?)?.toInt() ?? 0,
        kind = (m['kind'] as String?) ?? 'view',
        inspector = (m['inspector'] as String?) ?? 'native',
        cls = m['cls'] as String?,
        id = m['id'] as String?,
        testId = m['testId'] as String?,
        label = m['label'] as String?,
        place = Rect.fromLTRB(((m['l'] as num?) ?? 0).toDouble(), ((m['t'] as num?) ?? 0).toDouble(), ((m['r'] as num?) ?? 0).toDouble(), ((m['b'] as num?) ?? 0).toDouble()),
        can = [for (final c in (m['can'] as List?) ?? const []) c as String],
        password = (m['password'] as bool?) ?? false;
  final int i, depth;
  final int? parent;
  final String kind, inspector;
  final String? cls, id, testId, label;

  /// In the screen's own pixels.
  final Rect place;
  final List<String> can;
  final bool password;
}

/// The whole of the last reading, for walking through (xray/XrayAccess.kt `Capture.toMap`).
class Capture {
  Capture(Map m)
      : package = (m['package'] as String?) ?? '',
        label = (m['label'] as String?) ?? '',
        at = (m['at'] as num?)?.toInt() ?? 0,
        width = (m['width'] as num?)?.toInt() ?? 0,
        height = (m['height'] as num?)?.toInt() ?? 0,
        boxes = [for (final b in (m['boxes'] as List?) ?? const []) CaptureBox(b as Map)],
        inspectors = [for (final i in (m['inspectors'] as List?) ?? const []) i as String],
        picked = (m['picked'] as num?)?.toInt(),
        cut = (m['cut'] as bool?) ?? false,
        layers = _layers(m['layers']),
        layersWhy = m['layersWhy'] as String?;
  final String package, label;
  final int at, width, height;
  final List<CaptureLayer>? layers;
  final String? layersWhy;
  final List<CaptureBox> boxes;
  final List<String> inspectors;
  final int? picked;
  final bool cut;
}

class Xray {
  static const _channel = MethodChannel('frames');

  static Future<Object?> _ask(String what, [Map<String, Object?> more = const {}]) => _channel.invokeMethod('xray', {'do': what, ...more});

  /// Every app that can be opened, by name, Matt's Tools among them.
  static Future<List<AppChoice>> apps() async => [for (final a in ((await _ask('apps')) as List?) ?? const []) AppChoice(a as Map)];

  /// X-ray's switch and its last reading; null where the phone gives no answer.
  static Future<XrayState?> state() async {
    try {
      final answer = await _ask('state');
      return answer is Map ? XrayState(answer) : null;
    } catch (_) {
      return null;
    }
  }

  /// The whole of the last reading, or null where none is held.
  static Future<Capture?> capture() async {
    try {
      final answer = await _ask('capture');
      return answer is Map ? Capture(answer) : null;
    } catch (_) {
      return null;
    }
  }

  /// Opens Accessibility at once, with the line saying what to switch on.
  static Future<void> ask() async {
    try {
      await _ask('ask');
    } catch (_) {}
  }

  /// Drops the last reading on the phone.
  static Future<void> forget() async {
    try {
      await _ask('forget');
    } catch (_) {}
  }

  /// What [package] is made of. Never throws: a phone that cannot say gives an [Anatomy] with its [Anatomy.error].
  static Future<Anatomy> anatomy(String package) async {
    try {
      final answer = await _ask('anatomy', {'package': package});
      if (answer is Map) return Anatomy(answer);
      return Anatomy({'error': 'The phone gave no answer.'});
    } catch (_) {
      return Anatomy({'error': 'X-ray could not read this app.'});
    }
  }
}

/// A size as a person reads it: "11.2 MB", "840 KB", "12 bytes". Thousands, as Android's own file sizes are.
String bytesOf(int bytes) {
  if (bytes < 1000) return '$bytes bytes';
  if (bytes < 1000 * 1000) return '${(bytes / 1000).round()} KB';
  if (bytes < 1000 * 1000 * 1000) return '${(bytes / 1000000).toStringAsFixed(1)} MB';
  return '${(bytes / 1000000000).toStringAsFixed(2)} GB';
}
