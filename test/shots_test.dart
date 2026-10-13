import 'dart:convert';
import 'dart:io';

import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:frames/data.dart';
import 'package:frames/pages.dart';
import 'package:frames/shell/app_shell.dart';

import 'support/fake_phone.dart';
import 'support/render.dart';
import 'support/runs.dart';

/// Pictures to look at, one a page, written to build/pictures (not kept in the repository, and nothing is held to
/// them): the bar over the page of tools, the list of Settings, each tool's page of it, and the wide layout.
void main() {
  setUpAll(loadAppFonts);

  Future<void> picture(WidgetTester tester, String name, String? place, {Size size = phone, bool dark = true, void Function(FakePhone phone)? with_}) async {
    final fake = FakePhone();
    with_?.call(fake);
    await pumpApp(tester, size: size, dark: dark, home: AppShell(first: place));
    await settle(tester);
    await shot(tester, name);
    await endApp(tester);
  }

  testWidgets('the page of tools under the bar', (tester) => picture(tester, 'home', null));
  testWidgets('the list of settings', (tester) => picture(tester, 'settings', 'settings'));
  testWidgets('the list of settings on paper', (tester) => picture(tester, 'settings-light', 'settings', dark: false));
  testWidgets('the dock, floating', (tester) => picture(tester, 'settings-dock', 'settings/dock'));
  testWidgets('the dock, beside the camera', (tester) => picture(tester, 'settings-dock-camera', 'settings/dock', size: const Size(390, 1100), with_: (p) => p.dock['beside'] = true));
  testWidgets('amphetamine', (tester) => picture(tester, 'settings-amphetamine', 'settings/amphetamine', size: const Size(390, 1250), with_: (p) {
        p.awake['apps'] = ['com.example.maps'];
        p.awake['appNames'] = ['Maps'];
      }));
  testWidgets('the apps that keep it awake', (tester) => picture(tester, 'settings-amphetamine-apps', 'settings/amphetamine-apps'));
  testWidgets('frames', (tester) => picture(tester, 'settings-frames', 'settings/frames', size: const Size(390, 1500), with_: (p) => p.frames['hud'] = true));
  testWidgets('toasty', (tester) => picture(tester, 'settings-toasty', 'settings/toasty'));
  testWidgets('about', (tester) => picture(tester, 'settings-about', 'settings/about', size: const Size(390, 1900)));
  testWidgets('settings, opened out', (tester) => picture(tester, 'settings-wide', 'settings/dock', size: wideWindow));
  testWidgets('amphetamine\'s own page', (tester) => picture(tester, 'amphetamine', 'amphetamine', with_: (p) {
        p.awake['on'] = true;
        p.awake['forMs'] = 754000;
      }));
  testWidgets('toasty\'s own page', (tester) => picture(tester, 'toasty', 'toasty'));
  testWidgets('frames\' first page', (tester) => picture(tester, 'frames', 'frames'));

  // ---- hardware use: a run, an app, two side by side, the leaderboard ----

  Future<void> page(WidgetTester tester, String name, Widget home, {Size size = phone}) async {
    FakePhone();
    await pumpApp(tester, size: size, home: home);
    await settle(tester);
    await shot(tester, name);
    await endApp(tester);
    library.value = const [];
  }

  final reader = [
    sessionJson(startedAt: 1791800000000, hardware: hardwareJson(seconds: 60, devicePct: 20, devicePeakPct: 50, appPct: 30, avgMb: 300, peakMb: 320, drawMa: 400)),
    sessionJson(startedAt: 1791800300000, versionName: '2.5.0', versionCode: 250, hardware: hardwareJson(seconds: 180, devicePct: 40, devicePeakPct: 90, appPct: null, avgMb: 340, peakMb: 380, drawMa: 500)),
    sessionJson(startedAt: 1791800600000, versionName: '2.3.0', versionCode: 230),
  ];
  final maps = [sessionJson(pkg: 'com.example.maps', app: 'Maps', jankEvery: 9, hardware: hardwareJson(devicePct: 44, devicePeakPct: 93, avgMb: 520, peakMb: 610, drawMa: 610, appPct: null, rxBytes: 9400000, txBytes: 310000))];

  testWidgets('a run, with its hardware use', (tester) => page(tester, 'hardware-run', RunScreen(run: Run(sessionJson(hardware: hardwareJson(plugged: 12)))), size: const Size(390, 2100)));
  testWidgets('a run, where the phone gave nothing', (tester) => page(tester, 'hardware-run-missing', RunScreen(run: Run(sessionJson(hardware: hardwareMissingJson()))), size: const Size(390, 1900)));
  testWidgets('a run from before hardware use', (tester) => page(tester, 'hardware-run-old', RunScreen(run: Run(sessionJson())), size: const Size(390, 1500)));
  testWidgets('an app, with its runs\' hardware use', (tester) {
    library.value = [appOf(reader)];
    return page(tester, 'hardware-app', const AppScreen(pkg: 'com.example.reader'), size: const Size(390, 1700));
  });
  testWidgets('two apps side by side, with hardware use', (tester) => page(tester, 'hardware-compare', CompareScreen(a: appOf(reader), b: appOf(maps)), size: const Size(390, 2100)));
  testWidgets('the leaderboard, by memory', (tester) async {
    final dir = await tester.runAsync(() => Directory.systemTemp.createTemp('frames-shots'));
    addTearDown(() => dir!.deleteSync(recursive: true));
    for (final session in [...reader, ...maps, sessionJson(pkg: 'com.example.old', app: 'Old')]) {
      Directory('${dir!.path}/${session['package']}').createSync();
      File('${dir.path}/${session['package']}/${session['startedAt']}.json').writeAsStringSync(jsonEncode(session));
    }
    FakePhone().frames['sessions'] = dir!.path;
    await pumpApp(tester, size: const Size(390, 1000), home: const AppShell(first: 'frames'));
    await tester.runAsync(() => reload(dir.path));
    await settle(tester);
    await tester.tap(find.descendant(of: find.byKey(const ValueKey('board.hardware')), matching: find.text('Memory')));
    await shot(tester, 'hardware-board');
    await endApp(tester);
    library.value = const [];
  });
}
