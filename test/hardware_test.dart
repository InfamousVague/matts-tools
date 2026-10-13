import 'dart:convert';
import 'dart:io';

import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:frames/data.dart';
import 'package:frames/pages.dart';
import 'package:frames/shell/app_shell.dart';
import 'package:frames/ui.dart';
import 'package:glacier/glacier.dart';

import 'support/fake_phone.dart';
import 'support/render.dart';
import 'support/runs.dart';

const Size tall = Size(390, 3200);

Finder hw(String id) => find.byKey(ValueKey('hardware.$id'));
Finder within(String id, String words) => find.descendant(of: hw(id), matching: find.textContaining(words));

/// Hardware use on the pages that show runs: a run's own, an app's over its runs, two side by side, and the
/// leaderboard's order. With `--dart-define=shots=true` nothing changes; the pictures are test/shots_test.dart's.
void main() {
  setUpAll(loadAppFonts);
  setUp(FakePhone.new);
  tearDown(() => library.value = const []);

  testWidgets('a run\'s page: each hardware number in its row, and the two charts', (tester) async {
    final run = Run(sessionJson(hardware: hardwareJson(plugged: 12)));
    await pumpApp(tester, size: tall, home: RunScreen(run: run));
    await settle(tester);
    expect(find.text('HARDWARE'), findsOneWidget);
    expect(within('cpuApp', '41.2% of one core'), findsOneWidget);
    expect(within('cpuApp', '5.2% of the phone\'s 8 cores'), findsOneWidget);
    expect(within('cpuDevice', '23% average, 71% at most'), findsOneWidget);
    expect(within('memory', '312 MB average, 340 MB at most'), findsOneWidget);
    expect(within('memory', 'PSS over 5 of the phone\'s own samples, 2 processes'), findsOneWidget);
    expect(within('battery', '412 mA · 1.61 W'), findsOneWidget);
    expect(within('battery', '9.4% an hour of about 4400 mAh'), findsOneWidget);
    expect(within('battery', 'plugged in for 12s, which is left out'), findsOneWidget);
    expect(within('data', '1.2 MB down, 80 KB up'), findsOneWidget);
    expect(within('data', '614 KB a minute down, 40 KB up'), findsOneWidget);
    expect(hw('cpuChart'), findsOneWidget);
    expect(hw('memoryChart'), findsOneWidget);
    // What was there before is there still.
    expect(find.text('THE PHONE MEANWHILE'), findsOneWidget);
    await endApp(tester);
  });

  testWidgets('a run\'s page: what the phone would not give is "Not available" with the reason, never a 0', (tester) async {
    await pumpApp(tester, size: tall, home: RunScreen(run: Run(sessionJson(hardware: hardwareMissingJson()))));
    await settle(tester);
    for (final id in ['cpuApp', 'cpuDevice', 'memory', 'battery', 'data']) {
      expect(within(id, 'Not available'), findsOneWidget, reason: id);
      expect(find.descendant(of: hw(id), matching: find.textContaining(RegExp(r'\b0( |%|\.0)'))), findsNothing, reason: id);
    }
    expect(within('cpuApp', 'Needs android.permission.BATTERY_STATS'), findsOneWidget);
    expect(within('battery', 'The phone was plugged in throughout'), findsOneWidget);
    expect(within('memory', 'ten seconds after an app comes up'), findsOneWidget);
    expect(hw('cpuChart'), findsNothing);
    await endApp(tester);
  });

  testWidgets('a run from before hardware use was kept, and one with it switched off, say so', (tester) async {
    await pumpApp(tester, size: tall, home: RunScreen(run: Run(sessionJson())));
    await settle(tester);
    expect(find.text('HARDWARE'), findsOneWidget);
    expect(find.textContaining('from before Frames read'), findsOneWidget);
    expect(hw('cpuDevice'), findsNothing);
    await endApp(tester);

    await pumpApp(tester, size: tall, home: RunScreen(run: Run(sessionJson(hardware: {'off': true}))));
    await settle(tester);
    expect(find.textContaining('switched off in Frames\' settings'), findsOneWidget);
    await endApp(tester);
  });

  testWidgets('an app\'s page: its runs\' hardware use together, and a few words of it on each build', (tester) async {
    library.value = [
      appOf([
        sessionJson(startedAt: 1791800000000, hardware: hardwareJson(seconds: 60, devicePct: 20, devicePeakPct: 50, appPct: 30, avgMb: 300, peakMb: 320, drawMa: 400)),
        sessionJson(startedAt: 1791800300000, versionName: '2.5.0', versionCode: 250, hardware: hardwareJson(seconds: 180, devicePct: 40, devicePeakPct: 90, appPct: null, avgMb: 340, peakMb: 380, drawMa: 500)),
        sessionJson(startedAt: 1791800600000, versionName: '2.3.0', versionCode: 230),
      ]),
    ];
    await pumpApp(tester, size: tall, home: const AppScreen(pkg: 'com.example.reader'));
    await settle(tester);
    expect(find.text('HARDWARE, ALL RUNS'), findsOneWidget);
    expect(within('cpuDevice', '35% average, 90% at most'), findsOneWidget);
    expect(within('cpuApp', '30.0% of one core'), findsOneWidget);
    expect(within('cpuApp', 'From 1 of 3 runs'), findsOneWidget);
    expect(within('memory', '330 MB average, 380 MB at most'), findsOneWidget);
    expect(within('battery', '475 mA'), findsOneWidget);
    expect(find.textContaining('app CPU 30% · 300 MB · 400 mA'), findsOneWidget);
    expect(find.textContaining('phone CPU 40% · 340 MB · 500 mA'), findsOneWidget);
    await endApp(tester);

    // An app with no run that read hardware use has no such part.
    library.value = [appOf([sessionJson()])];
    await pumpApp(tester, size: tall, home: const AppScreen(pkg: 'com.example.reader'));
    await settle(tester);
    expect(find.text('HARDWARE, ALL RUNS'), findsNothing);
    await endApp(tester);
  });

  testWidgets('side by side: a pair of bars for what both have, and a line for what one lacks', (tester) async {
    final a = appOf([sessionJson(hardware: hardwareJson(devicePct: 20, avgMb: 300, drawMa: 400, appPct: 30))]);
    final b = appOf([sessionJson(pkg: 'com.example.maps', app: 'Maps', hardware: hardwareJson(devicePct: 40, avgMb: 520, drawMa: 610, appPct: null))]);
    await pumpApp(tester, size: tall, home: CompareScreen(a: a, b: b));
    await settle(tester);
    expect(find.text('HARDWARE'), findsOneWidget);
    for (final id in ['cpuDevice', 'memory', 'memoryPeak', 'battery', 'data']) {
      expect(tester.widget(hw(id)), isA<PairBars>(), reason: id);
    }
    expect(within('memory', '300 MB'), findsOneWidget);
    expect(within('memory', '520 MB'), findsOneWidget);
    expect(within('memory', '+220 MB'), findsOneWidget);
    expect(tester.widget(hw('cpuApp')), isNot(isA<PairBars>()));
    expect(within('cpuApp', 'not measured for B'), findsOneWidget);
    await endApp(tester);

    // Neither side measured: the comparison is as it was.
    await pumpApp(tester, size: tall, home: CompareScreen(a: appOf([sessionJson()]), b: appOf([sessionJson(pkg: 'com.example.maps', app: 'Maps')])));
    await settle(tester);
    expect(find.text('HARDWARE'), findsNothing);
    await endApp(tester);
  });

  testWidgets('the leaderboard ranks by a hardware number, least first, with an app that has none last', (tester) async {
    final dir = await tester.runAsync(() => Directory.systemTemp.createTemp('frames-board'));
    addTearDown(() => dir!.deleteSync(recursive: true));
    void keep(Map<String, dynamic> session) {
      Directory('${dir!.path}/${session['package']}').createSync();
      File('${dir.path}/${session['package']}/${session['startedAt']}.json').writeAsStringSync(jsonEncode(session));
    }

    keep(sessionJson(pkg: 'com.example.reader', app: 'Reader', hardware: hardwareJson(avgMb: 300, devicePct: 44)));
    keep(sessionJson(pkg: 'com.example.maps', app: 'Maps', hardware: hardwareJson(avgMb: 520, devicePct: 21)));
    keep(sessionJson(pkg: 'com.example.old', app: 'Old'));
    final fake = FakePhone()..frames['sessions'] = dir!.path;
    await pumpApp(tester, size: tall, home: const AppShell(first: 'frames'));
    await tester.runAsync(() => reload(dir.path));
    await settle(tester);
    expect(fake.of('state'), isNotEmpty);

    double top(String app) => tester.getTopLeft(find.text(app)).dy;
    await tester.tap(find.descendant(of: find.byKey(const ValueKey('board.hardware')), matching: find.text('Memory')));
    await settle(tester);
    expect(find.text('300 MB'), findsOneWidget);
    expect(find.text('520 MB'), findsOneWidget);
    expect(find.text('Not measured'), findsOneWidget);
    expect(top('Reader'), lessThan(top('Maps')));
    expect(top('Maps'), lessThan(top('Old')));
    expect(find.text('Least'), findsOneWidget);

    await tester.tap(find.text('Most'));
    await settle(tester);
    expect(top('Maps'), lessThan(top('Reader')));
    expect(top('Reader'), lessThan(top('Old')));

    await tester.tap(find.descendant(of: find.byKey(const ValueKey('board.hardware')), matching: find.text('CPU')));
    await settle(tester);
    expect(find.text('44%'), findsOneWidget);
    expect(top('Reader'), lessThan(top('Maps')));
    // And back to the frames' own numbers.
    await tester.tap(find.text('fps'));
    await settle(tester);
    expect(find.text('Not measured'), findsNothing);
    expect(find.text('Fastest'), findsOneWidget);
    expect(find.byType(GlacierProgressBar), findsNWidgets(3));
    await endApp(tester);
  });
}
