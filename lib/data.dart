import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';

/// What a run of frames comes to (the phone's own `Stats`, Core.kt). Times in milliseconds.
class Stats {
  Stats(this.activeSeconds, this.periodMs, List<double> times) : sorted = [...times]..sort();

  final int activeSeconds;
  final double periodMs;
  final List<double> sorted;

  int get frames => sorted.length;
  double _pct(double p) => sorted.isEmpty ? 0 : sorted[((p * sorted.length).ceil() - 1).clamp(0, sorted.length - 1)];
  late final double p50 = _pct(0.50), p90 = _pct(0.90), p95 = _pct(0.95), p99 = _pct(0.99);
  late final double worst = sorted.isEmpty ? 0 : sorted.last;
  late final double fps = activeSeconds == 0 ? 0 : frames / activeSeconds;
  late final double low1 = p99 <= 0 ? 0 : (1000 / p99).clamp(0, 1000 / periodMs).toDouble();
  late final int janky = sorted.where((t) => t > periodMs * 1.5).length;
  late final int slow = sorted.where((t) => t > periodMs * 2.5).length;
  late final int frozen = sorted.where((t) => t > 700).length;
  late final double jankPct = frames == 0 ? 0 : janky * 100 / frames;

  /// Frames by the refreshes each missed: none, one, two or three, four to seven, eight or more.
  late final List<int> missed = [
    sorted.where((t) => t <= periodMs * 1.5).length,
    sorted.where((t) => t > periodMs * 1.5 && t <= periodMs * 2.5).length,
    sorted.where((t) => t > periodMs * 2.5 && t <= periodMs * 4.5).length,
    sorted.where((t) => t > periodMs * 4.5 && t <= periodMs * 8.5).length,
    sorted.where((t) => t > periodMs * 8.5).length,
  ];
  static const missedNames = ['0', '1', '2-3', '4-7', '8+'];
}

class ScreenStat {
  ScreenStat(this.name, this.frames, this.janky, this.slow, this.worstMs, this.seconds);
  final String name;
  int frames, janky, slow, seconds;
  double worstMs;
  double get jankPct => frames == 0 ? 0 : janky * 100 / frames;
}

double? _num(Map? m, String key) => (m?[key] is num) ? (m![key] as num).toDouble() : null;
String? _why(Map? m, String key) => (m?[key] is String && (m![key] as String).isNotEmpty) ? m[key] as String : null;
List<(int, double)> _points(Object? list) => [
      for (final p in (list is List ? list : const []))
        if (p is List && p.length >= 2 && p[0] is num && p[1] is num) ((p[0] as num).toInt(), (p[1] as num).toDouble()),
    ];

/// What an app cost the phone during one run (frames/Hardware.kt): processor, memory, battery, data. A number the
/// phone would not give is null and its `...Why` says why; nothing here is ever 0 for want of a reading. A run from
/// before these were kept has no [Hardware] at all ([Run.hardware] is null).
class Hardware {
  Hardware(Map<String, dynamic> o)
      : off = o['off'] == true,
        seconds = o['seconds'] is num ? (o['seconds'] as num).toInt() : 0,
        _cpu = o['cpu'] is Map ? o['cpu'] as Map : null,
        _memory = o['memory'] is Map ? o['memory'] as Map : null,
        _battery = o['battery'] is Map ? o['battery'] as Map : null,
        _data = o['data'] is Map ? o['data'] as Map : null,
        cpuSeries = _points(o['series'] is Map ? (o['series'] as Map)['cpu'] : null),
        memorySeries = _points(o['series'] is Map ? (o['series'] as Map)['memoryMb'] : null),
        cost = o['cost'] is Map ? (o['cost'] as Map).cast<String, dynamic>() : const {};

  /// Switched off in Frames' settings for this run: nothing was read.
  final bool off;
  final int seconds;
  final Map? _cpu, _memory, _battery, _data;

  /// The whole phone's processor use every two seconds, and the app's memory at each of the system's samples.
  final List<(int, double)> cpuSeries, memorySeries;

  /// What the reading itself took, by kind (reads, avgMs, worstMs, cpuMs).
  final Map<String, dynamic> cost;

  int get cores => (_cpu?['cores'] as num?)?.toInt() ?? 0;

  /// The whole phone's processor, as a percent of all its cores: over the run, and at its most over six seconds.
  double? get cpuDevicePct => _num(_cpu, 'devicePct');
  double? get cpuDevicePeakPct => _num(_cpu, 'devicePeakPct');
  String? get cpuDeviceWhy => _why(_cpu, 'deviceWhy');

  /// The app's own processor time: as a percent of one core, of the whole phone, and in milliseconds.
  double? get cpuAppPct => _num(_cpu, 'appPct');
  double? get cpuAppOfDevicePct => _num(_cpu, 'appOfDevicePct');
  double? get cpuAppMs => _num(_cpu, 'appMs');
  String? get cpuAppWhy => _why(_cpu, 'appWhy');
  bool get cpuAppPartly => _cpu?['appPartly'] == true;

  double? get memoryAvgMb => _num(_memory, 'avgMb');
  double? get memoryPeakMb => _num(_memory, 'peakMb');
  String get memoryKind => (_memory?['kind'] as String?) ?? '';
  int get memorySamples => (_memory?['samples'] as num?)?.toInt() ?? 0;
  int get memoryProcesses => (_memory?['processes'] as num?)?.toInt() ?? 0;
  String? get memoryWhy => _why(_memory, 'why');

  /// The whole phone's draw while unplugged.
  double? get drawMa => _num(_battery, 'avgDrawMa');
  double? get powerMw => _num(_battery, 'avgPowerMw');
  double? get pctPerHour => _num(_battery, 'pctPerHour');
  double? get usedMah => _num(_battery, 'usedMah');
  double? get capacityMah => _num(_battery, 'capacityMah');
  int get pluggedSeconds => (_battery?['pluggedSeconds'] as num?)?.toInt() ?? 0;
  int get unpluggedSeconds => (_battery?['unpluggedSeconds'] as num?)?.toInt() ?? 0;
  bool get charging => pluggedSeconds > 0;
  String? get batteryWhy => _why(_battery, 'why');

  /// The app's own bytes.
  double? get rxBytes => _num(_data, 'rxBytes');
  double? get txBytes => _num(_data, 'txBytes');
  double? get wifiRxBytes => _num(_data, 'wifiRxBytes');
  double? get wifiTxBytes => _num(_data, 'wifiTxBytes');
  double? get mobileRxBytes => _num(_data, 'mobileRxBytes');
  double? get mobileTxBytes => _num(_data, 'mobileTxBytes');
  String? get mobileWhy => _why(_data, 'mobileWhy');
  String? get dataWhy => _why(_data, 'why');

  /// Too short a run for the system's totals to have caught up (it brings them up to date every fifteen seconds).
  bool get dataCoarse => _data?['coarse'] == true;
}

/// The hardware use of several runs put together: each number over the runs that have it, weighted by how long
/// each ran, and null where none has it.
class HardwareSum {
  HardwareSum(List<Run> runs) : measured = [for (final r in runs) if (r.hardware != null && !r.hardware!.off) r.hardware!];

  /// The runs that read hardware use at all.
  final List<Hardware> measured;
  bool get any => measured.isNotEmpty;

  double? _mean(double? Function(Hardware) value, [int Function(Hardware)? weight]) {
    var sum = 0.0, over = 0.0;
    for (final h in measured) {
      final v = value(h);
      if (v == null) continue;
      final w = ((weight ?? (Hardware h) => h.seconds)(h)).clamp(1, 1 << 30).toDouble();
      sum += v * w;
      over += w;
    }
    return over == 0 ? null : sum / over;
  }

  double? _most(double? Function(Hardware) value) => measured.map(value).whereType<double>().fold<double?>(null, (a, b) => a == null || b > a ? b : a);
  int count(double? Function(Hardware) value) => measured.where((h) => value(h) != null).length;

  late final double? cpuDevicePct = _mean((h) => h.cpuDevicePct);
  late final double? cpuDevicePeakPct = _most((h) => h.cpuDevicePeakPct);
  late final double? cpuAppPct = _mean((h) => h.cpuAppPct);
  late final double? memoryAvgMb = _mean((h) => h.memoryAvgMb);
  late final double? memoryPeakMb = _most((h) => h.memoryPeakMb);
  late final double? drawMa = _mean((h) => h.drawMa, (h) => h.unpluggedSeconds);
  late final double? powerMw = _mean((h) => h.powerMw, (h) => h.unpluggedSeconds);
  late final double? pctPerHour = _mean((h) => h.pctPerHour, (h) => h.unpluggedSeconds);

  /// The app's bytes in all, and a minute, over the runs that counted them.
  late final double? rxBytes = count((h) => h.rxBytes) == 0 ? null : measured.fold<double>(0, (n, h) => n + (h.rxBytes ?? 0));
  late final double? txBytes = count((h) => h.txBytes) == 0 ? null : measured.fold<double>(0, (n, h) => n + (h.txBytes ?? 0));
  late final int _dataSeconds = measured.where((h) => h.rxBytes != null).fold(0, (n, h) => n + (h.seconds < 1 ? 1 : h.seconds));
  double? get rxPerMin => rxBytes == null ? null : rxBytes! * 60 / _dataSeconds;
  double? get txPerMin => txBytes == null ? null : txBytes! * 60 / _dataSeconds;
  double? get bytesPerMin => rxBytes == null ? null : (rxBytes! + (txBytes ?? 0)) * 60 / _dataSeconds;
}

/// One stay in one app while measuring, as the phone kept it.
class Run {
  Run(Map<String, dynamic> o)
      : pkg = o['package'] as String,
        label = (o['app'] as String?) ?? '',
        versionName = (o['versionName'] as String?) ?? '?',
        versionCode = (o['versionCode'] as num?)?.toInt() ?? 0,
        startedAt = (o['startedAt'] as num).toInt(),
        endedAt = (o['endedAt'] as num?)?.toInt() ?? 0,
        scenario = (o['scenario'] as String?) ?? '',
        refreshHz = (o['refreshHz'] as num?)?.toDouble() ?? 60,
        startTempC = (o['startTempC'] as num?)?.toDouble() ?? 0,
        endTempC = (o['endTempC'] as num?)?.toDouble() ?? 0,
        thermalWorst = (o['thermalWorst'] as num?)?.toInt() ?? 0,
        times = [for (final t in (o['frameMs'] as List? ?? const [])) (t as num).toDouble()],
        seconds = [for (final s in (o['seconds'] as List? ?? const [])) (s as Map).cast<String, dynamic>()],
        slowFrames = [for (final s in (o['slowFrames'] as List? ?? const [])) (s as Map).cast<String, dynamic>()],
        screens = [
          for (final s in (o['screens'] as List? ?? const []))
            ScreenStat(s['name'] as String? ?? '?', (s['frames'] as num).toInt(), (s['janky'] as num).toInt(), (s['slow'] as num).toInt(), (s['worstMs'] as num).toDouble(), (s['seconds'] as num?)?.toInt() ?? 0),
        ],
        hardware = o['hardware'] is Map ? Hardware((o['hardware'] as Map).cast<String, dynamic>()) : null,
        _activeSeconds = ((o['stats'] as Map?)?['activeSeconds'] as num?)?.toInt() ?? 0;

  /// What the app cost the phone; null on a run from before Frames read it.
  final Hardware? hardware;

  final String pkg, label, versionName, scenario;
  final int versionCode, startedAt, endedAt, thermalWorst;
  final double refreshHz, startTempC, endTempC;
  final List<double> times;
  final List<Map<String, dynamic>> seconds, slowFrames;
  final List<ScreenStat> screens;
  final int _activeSeconds;

  double get periodMs => 1000 / (refreshHz < 1 ? 1 : refreshHz);
  late final Stats stats = Stats(_activeSeconds, periodMs, times);
  DateTime get when => DateTime.fromMillisecondsSinceEpoch(startedAt);
  int get lastedSeconds => ((endedAt - startedAt) / 1000).round();
  String get buildName => '$versionName${scenario.isEmpty ? '' : ' · $scenario'}';
  String get buildKey => '$versionName\n$versionCode\n$scenario';
}

/// Something with numbers to its name that can stand on one side of a comparison: an app, or one build of it.
class Subject {
  Subject(this.pkg, this.title, this.subtitle, this.runs)
      : stats = Stats(runs.fold(0, (n, r) => n + r.stats.activeSeconds), runs.isEmpty ? 16.67 : runs.map((r) => r.periodMs).reduce((a, b) => a + b) / runs.length, [for (final r in runs) ...r.times]);
  final String pkg, title, subtitle;
  final List<Run> runs;
  final Stats stats;
  late final HardwareSum hardware = HardwareSum(runs);
  double get jankLow => runs.map((r) => r.stats.jankPct).reduce((a, b) => a < b ? a : b);
  double get jankHigh => runs.map((r) => r.stats.jankPct).reduce((a, b) => a > b ? a : b);
}

/// An app that has been measured: its runs, newest first, and the builds they were of.
class AppData extends Subject {
  AppData(String pkg, String label, List<Run> runs) : super(pkg, label, '${runs.length} run${runs.length == 1 ? '' : 's'} · ${runs.first.versionName}', runs);

  late final List<Subject> builds = () {
    final by = <String, List<Run>>{};
    for (final r in runs) {
      by.putIfAbsent(r.buildKey, () => []).add(r);
    }
    return [for (final group in by.values) Subject(pkg, group.first.buildName, '${group.length} run${group.length == 1 ? '' : 's'}', group)];
  }();

  /// Where in the app the jank is: every run's frames, by the screen they were drawn on, worst first.
  late final List<ScreenStat> screens = () {
    final by = <String, ScreenStat>{};
    for (final r in runs) {
      for (final s in r.screens) {
        final sum = by.putIfAbsent(s.name, () => ScreenStat(s.name, 0, 0, 0, 0, 0));
        sum.frames += s.frames;
        sum.janky += s.janky;
        sum.slow += s.slow;
        sum.seconds += s.seconds;
        if (s.worstMs > sum.worstMs) sum.worstMs = s.worstMs;
      }
    }
    return by.values.toList()..sort((a, b) => b.janky.compareTo(a.janky));
  }();
}

/// Every run on the phone, by app. Filled by [reload].
final ValueNotifier<List<AppData>> library = ValueNotifier(const []);

List<Run> get allRuns => [for (final a in library.value) ...a.runs]..sort((a, b) => b.startedAt.compareTo(a.startedAt));

Future<void> reload(String sessionsDir) async {
  final root = Directory(sessionsDir);
  final apps = <AppData>[];
  if (await root.exists()) {
    await for (final dir in root.list()) {
      if (dir is! Directory) continue;
      final runs = <Run>[];
      await for (final file in dir.list()) {
        if (file is! File || !file.path.endsWith('.json')) continue;
        try {
          runs.add(Run(jsonDecode(await file.readAsString()) as Map<String, dynamic>));
        } catch (_) {
          // A file half written, or of a shape from before: left out.
        }
      }
      if (runs.isEmpty) continue;
      runs.sort((a, b) => b.startedAt.compareTo(a.startedAt));
      apps.add(AppData(runs.first.pkg, runs.first.label.isEmpty ? runs.first.pkg : runs.first.label, runs));
    }
  }
  apps.sort((a, b) => b.runs.first.startedAt.compareTo(a.runs.first.startedAt));
  library.value = apps;
}
