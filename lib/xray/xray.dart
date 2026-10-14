import 'package:flutter/services.dart';

import '../tools.dart';

/// X-ray's side of the one channel (tools/Shell.kt `xray` answers it, in xray/Door.kt): what an app is made of,
/// read on the phone from the files of its package. Every model reads its map with defaults, so an answer that
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

class Xray {
  static const _channel = MethodChannel('frames');

  static Future<Object?> _ask(String what, [Map<String, Object?> more = const {}]) => _channel.invokeMethod('xray', {'do': what, ...more});

  /// Every app that can be opened, by name, Matt's Tools among them.
  static Future<List<AppChoice>> apps() async => [for (final a in ((await _ask('apps')) as List?) ?? const []) AppChoice(a as Map)];

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
