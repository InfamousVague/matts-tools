import 'dart:math';

import 'package:frames/data.dart';

/// Runs as the phone keeps them (frames/Core.kt `Session.json`), for a test or a picture.

/// A run's hardware use as frames/Hardware.kt writes it, with every number the phone gave.
Map<String, dynamic> hardwareJson({
  int seconds = 120,
  double devicePct = 23.4,
  double devicePeakPct = 71.2,
  double? appPct = 41.2,
  double avgMb = 312.4,
  double peakMb = 340.1,
  double drawMa = 412.3,
  int rxBytes = 1258291,
  int txBytes = 81920,
  int plugged = 0,
}) {
  final wave = Random(seconds);
  return {
    'v': 1,
    'seconds': seconds,
    'cpu': {
      'cores': 8,
      'devicePct': devicePct,
      'devicePeakPct': devicePeakPct,
      if (appPct != null) ...{'appMs': (appPct * seconds * 10).round(), 'appPct': appPct, 'appOfDevicePct': appPct / 8} else 'appWhy': 'needs android.permission.BATTERY_STATS, given once from a computer',
    },
    'memory': {'avgMb': avgMb, 'peakMb': peakMb, 'kind': 'pss', 'samples': 5, 'processes': 2},
    'battery': {
      'pluggedSeconds': plugged, 'unpluggedSeconds': seconds - plugged, 'levelStart': 81, 'levelEnd': 80, 'reportedIn': 'µA', 'dischargeReads': 'negative',
      'avgDrawMa': drawMa, 'usedMah': drawMa * (seconds - plugged) / 3600, 'avgPowerMw': drawMa * 3.9, 'capacityMah': 4400, 'pctPerHour': drawMa * 100 / 4400,
      if (plugged > 0) 'charging': true, //
    },
    'data': {'wifiRxBytes': rxBytes, 'wifiTxBytes': txBytes, 'mobileRxBytes': 0, 'mobileTxBytes': 0, 'rxBytes': rxBytes, 'txBytes': txBytes, 'rxPerMin': rxBytes * 60 ~/ seconds, 'txPerMin': txBytes * 60 ~/ seconds},
    'series': {
      'cpu': [for (var t = 2; t < seconds; t += 2) [t, (devicePct + 18 * sin(t / 7) + wave.nextInt(14)).clamp(2, 100)]],
      'memoryMb': [for (var i = 0; i < 5; i++) [12 + i * 24, avgMb - 20 + i * 10]],
    },
    'cost': {
      'idle': {'reads': 61, 'avgMs': 0.8, 'worstMs': 2.1, 'cpuMs': 31.0},
      'memory': {'reads': 8, 'avgMs': 46.0, 'worstMs': 71.0, 'cpuMs': 22.0},
    },
  };
}

/// A run where the phone gave nothing: each part says why.
Map<String, dynamic> hardwareMissingJson() => {
      'v': 1,
      'seconds': 9,
      'cpu': {'cores': 8, 'deviceWhy': 'this phone does not show how long its cores sit idle', 'appWhy': 'needs android.permission.BATTERY_STATS, given once from a computer'},
      'memory': {'why': 'the run ended before the phone sampled the app\'s memory (it does so ten seconds after an app comes up, then about once a minute)'},
      'battery': {'pluggedSeconds': 9, 'unpluggedSeconds': 0, 'charging': true, 'why': 'the phone was plugged in throughout, so what the battery took or gave says nothing of the app'},
      'data': {'why': 'the phone refused: SecurityException'},
      'series': <String, dynamic>{},
      'cost': <String, dynamic>{},
    };

/// One run: [frames] frames at 120 Hz of which every [jankEvery]th missed a refresh. [hardware] null is a run from
/// before hardware use was kept.
Map<String, dynamic> sessionJson({
  String pkg = 'com.example.reader',
  String app = 'Reader',
  String versionName = '2.4.0',
  int versionCode = 240,
  int startedAt = 1791800000000,
  int seconds = 120,
  int frames = 2400,
  int jankEvery = 40,
  Map<String, dynamic>? hardware,
}) =>
    {
      'package': pkg, 'app': app, 'versionName': versionName, 'versionCode': versionCode, 'startedAt': startedAt, 'endedAt': startedAt + seconds * 1000,
      'scenario': '', 'refreshHz': 120.0, 'startTempC': 31.0, 'endTempC': 32.5, 'thermalWorst': 0, 'avgCurrentMa': -410,
      'stats': {'activeSeconds': frames ~/ 100},
      'screens': [
        {'name': 'MainActivity', 'frames': frames, 'janky': frames ~/ jankEvery, 'slow': 2, 'worstMs': 41.0, 'seconds': seconds},
      ],
      'slowFrames': <Object>[],
      'seconds': [for (var t = 0; t < 30; t++) {'t': t, 'frames': 100, 'worstMs': t % 9 == 0 ? 17.0 : 8.4, 'tempC': 31.0, 'ma': -410, 'hz': 120, 'thermal': 0, 'availMb': 5200, 'rxKb': 3, 'txKb': 1, 'batteryPct': 81}],
      'frameMs': [for (var i = 0; i < frames; i++) i % jankEvery == 0 ? 17.0 : 8.33],
      'hardware': ?hardware,
    };

AppData appOf(List<Map<String, dynamic>> sessions) {
  final runs = [for (final s in sessions) Run(s)]..sort((a, b) => b.startedAt.compareTo(a.startedAt));
  return AppData(runs.first.pkg, runs.first.label, runs);
}
