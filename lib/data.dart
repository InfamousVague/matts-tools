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
        _activeSeconds = ((o['stats'] as Map?)?['activeSeconds'] as num?)?.toInt() ?? 0;

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
