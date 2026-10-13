import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:frames/data.dart';
import 'package:frames/hardware.dart';

import 'support/runs.dart';

void main() {
  test('a run from before hardware use was kept still reads, with no hardware', () {
    final run = Run(sessionJson());
    expect(run.hardware, isNull);
    expect(run.stats.frames, 2400);
    expect(run.stats.jankPct, closeTo(2.5, 0.01));
    final app = appOf([sessionJson()]);
    expect(app.hardware.any, isFalse);
    expect(app.hardware.cpuDevicePct, isNull);
    expect(app.hardware.memoryAvgMb, isNull);
    expect(app.hardware.bytesPerMin, isNull);
  });

  test('a run with hardware use reads every number', () {
    final h = Run(sessionJson(hardware: hardwareJson())).hardware!;
    expect(h.off, isFalse);
    expect(h.cores, 8);
    expect(h.cpuDevicePct, 23.4);
    expect(h.cpuDevicePeakPct, 71.2);
    expect(h.cpuAppPct, 41.2);
    expect(h.cpuAppOfDevicePct, closeTo(5.15, 0.001));
    expect(h.memoryAvgMb, 312.4);
    expect(h.memoryPeakMb, 340.1);
    expect(h.memoryKind, 'pss');
    expect(h.drawMa, 412.3);
    expect(h.pctPerHour, closeTo(9.37, 0.01));
    expect(h.charging, isFalse);
    expect(h.rxBytes, 1258291);
    expect(h.txBytes, 81920);
    expect(h.cpuSeries.length, 59);
    expect(h.memorySeries.length, 5);
    expect(h.cost['memory'], isA<Map>());
  });

  test('a number the phone would not give is null with its reason, never 0', () {
    final h = Run(sessionJson(hardware: hardwareMissingJson())).hardware!;
    expect(h.cpuDevicePct, isNull);
    expect(h.cpuDeviceWhy, contains('idle'));
    expect(h.cpuAppPct, isNull);
    expect(h.cpuAppWhy, contains('BATTERY_STATS'));
    expect(h.memoryAvgMb, isNull);
    expect(h.memoryWhy, contains('sampled'));
    expect(h.drawMa, isNull);
    expect(h.charging, isTrue);
    expect(h.batteryWhy, contains('plugged in'));
    expect(h.rxBytes, isNull);
    expect(h.dataWhy, contains('refused'));
    for (final row in runRows(h)) {
      expect(row.value, isNull, reason: row.id);
      expect(row.detail, isNotEmpty, reason: row.id);
    }
  });

  test('hardware written in a shape not expected is no crash and no number', () {
    for (final odd in <Object?>[
      <String, dynamic>{},
      {'v': 9, 'cpu': 'fast', 'memory': 4, 'battery': <Object>[], 'data': null, 'series': {'cpu': 'up', 'memoryMb': [[1], 'x', [2, 'y'], [3, 4.5]]}, 'cost': 7},
      {'off': true},
      {'seconds': 'long', 'series': [1, 2], 'cpu': {'devicePct': '23', 'cores': 'eight'}},
    ]) {
      final h = Run(sessionJson(hardware: (odd as Map).cast<String, dynamic>())).hardware!;
      expect(h.cpuDevicePct, isNull);
      expect(h.memoryAvgMb, isNull);
      expect(h.drawMa, isNull);
      expect(h.rxBytes, isNull);
      expect(h.cpuSeries, isEmpty);
    }
    final some = Hardware({'series': {'memoryMb': [[1], 'x', [2, 'y'], [3, 4.5]]}});
    expect(some.memorySeries, [(3, 4.5)]);
    // Something that is not a map at all is as if it were not there.
    expect(Run({...sessionJson(), 'hardware': 'yes'}).hardware, isNull);
    expect(Run(sessionJson(hardware: {'off': true})).hardware!.off, isTrue);
  });

  test('an app\'s runs put together: weighted by how long each ran, over the runs that have the number', () {
    final app = appOf([
      sessionJson(startedAt: 1791800000000, hardware: hardwareJson(seconds: 60, devicePct: 20, devicePeakPct: 50, appPct: 30, avgMb: 300, peakMb: 320, drawMa: 400, rxBytes: 600000, txBytes: 60000)),
      sessionJson(startedAt: 1791800300000, hardware: hardwareJson(seconds: 180, devicePct: 40, devicePeakPct: 90, appPct: null, avgMb: 340, peakMb: 380, drawMa: 500, rxBytes: 1800000, txBytes: 0)),
      sessionJson(startedAt: 1791800600000, hardware: hardwareMissingJson()),
      sessionJson(startedAt: 1791800900000),
      sessionJson(startedAt: 1791801200000, hardware: {'off': true}),
    ]);
    final s = app.hardware;
    expect(s.measured.length, 3);
    expect(s.cpuDevicePct, closeTo(35, 0.001));
    expect(s.cpuDevicePeakPct, 90);
    // Only the first run had the app's own share.
    expect(s.cpuAppPct, 30);
    expect(s.count((h) => h.cpuAppPct), 1);
    expect(s.memoryAvgMb, closeTo(330, 0.001));
    expect(s.memoryPeakMb, 380);
    expect(s.drawMa, closeTo(475, 0.001));
    expect(s.rxBytes, 2400000);
    expect(s.rxPerMin, closeTo(600000, 0.001));
    expect(s.bytesPerMin, closeTo(615000, 0.001));
    expect(hardwareValue(s, 'memory'), closeTo(330, 0.001));
    expect(briefHardware(s), 'app CPU 30% · 330 MB · 475 mA');
    final rows = {for (final r in sumRows(s, app.runs.length)) r.id: r};
    expect(rows['cpuApp']!.detail, 'From 1 of 5 runs');
    expect(rows['memory']!.value, '330 MB average, 380 MB at most');
  });

  test('bytes in words', () {
    expect(bytesWords(0), '0 B');
    expect(bytesWords(812), '812 B');
    expect(bytesWords(2048), '2.0 KB');
    expect(bytesWords(81920), '80 KB');
    expect(bytesWords(1258291), '1.2 MB');
    expect(bytesWords(52428800), '50 MB');
    expect(bytesWords(3221225472), '3.00 GB');
  });

  test('runs are read from their files, old and new side by side', () async {
    final dir = await Directory.systemTemp.createTemp('frames-runs');
    addTearDown(() => dir.delete(recursive: true));
    final app = Directory('${dir.path}/com.example.reader')..createSync();
    File('${app.path}/1.json').writeAsStringSync(jsonEncode(sessionJson(startedAt: 1791800000000)));
    File('${app.path}/2.json').writeAsStringSync(jsonEncode(sessionJson(startedAt: 1791800300000, hardware: hardwareJson())));
    File('${app.path}/3.json').writeAsStringSync('{"package": "com.example.reader", "startedAt": 17');
    await reload(dir.path);
    addTearDown(() => library.value = const []);
    expect(library.value.single.runs.length, 2);
    expect(library.value.single.runs.first.hardware, isNotNull);
    expect(library.value.single.runs.last.hardware, isNull);
    expect(library.value.single.hardware.cpuDevicePct, 23.4);
  });
}
