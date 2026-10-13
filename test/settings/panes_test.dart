import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:frames/settings/kit/settings_kit.dart';
import 'package:frames/settings/level_slider.dart';
import 'package:frames/shell/app_shell.dart';
import 'package:frames/tools.dart';
import 'package:glacier/glacier.dart';

import '../support/fake_phone.dart';
import '../support/render.dart';

/// A screen tall enough that every row of a page is on it, to be pressed.
const Size tall = Size(390, 2400);

Finder row(String key) => find.byKey(ValueKey(key));
Finder switchIn(String key) => find.descendant(of: row(key), matching: find.byType(GlacierSwitch));

Future<void> press(WidgetTester tester, Finder what) async {
  await tester.tap(what);
  await settle(tester);
}

void main() {
  setUpAll(loadAppFonts);
  late FakePhone fake;
  setUp(() => fake = FakePhone());

  Future<void> page(WidgetTester tester, String id) async {
    await pumpApp(tester, size: tall, home: AppShell(first: 'settings/$id'));
    await settle(tester);
  }

  testWidgets('the dock\'s page, floating: every setting a row, each written through the door', (tester) async {
    await page(tester, 'dock');
    for (final key in ['dock.on', 'dock.mode', 'dock.vertical', 'dock.blur', 'dock.heat', 'dock.fahrenheit', 'dock.awake', 'dock.frames']) {
      expect(row(key), findsOneWidget, reason: key);
    }
    // Beside the camera's own rows are not there while it floats.
    expect(row('dock.side'), findsNothing);
    expect(row('dock.nudge'), findsNothing);

    await press(tester, switchIn('dock.on'));
    expect(fake.lastSet('dock.on'), false);
    expect(tester.widget<GlacierSwitch>(switchIn('dock.on')).checked, isFalse);
    await press(tester, switchIn('dock.blur'));
    expect(fake.lastSet('dock.blur'), false);
    await press(tester, switchIn('dock.heat'));
    expect(fake.lastSet('dock.heat'), false);
    await press(tester, switchIn('dock.awake'));
    expect(fake.lastSet('dock.awake'), false);
    await press(tester, switchIn('dock.frames'));
    expect(fake.lastSet('dock.frames'), false);
    await press(tester, find.text('Down'));
    expect(fake.lastSet('dock.vertical'), true);
    await press(tester, find.text('Fahrenheit'));
    expect(fake.lastSet('dock.fahrenheit'), true);

    await press(tester, find.text('Beside the camera'));
    expect(fake.lastSet('dock.mode'), 'island');
    // And the page is the one for a dock beside the camera.
    expect(row('dock.side'), findsOneWidget);
    expect(row('dock.vertical'), findsNothing);
    await endApp(tester);
  });

  testWidgets('the dock\'s page, beside the camera: its side, lining it up, and the way to Accessibility', (tester) async {
    fake.dock['beside'] = true;
    await page(tester, 'dock');
    for (final key in ['dock.taps', 'dock.side', 'dock.nudge']) {
      expect(row(key), findsOneWidget, reason: key);
    }
    expect(find.textContaining('589, 34 to 659, 104'), findsOneWidget);
    await press(tester, find.text('Both sides'));
    expect(fake.lastSet('dock.side'), 'both');
    await press(tester, find.byKey(const ValueKey('nudge Left')));
    expect(fake.lastSet('dock.nudge'), [-3, 0, 0]);
    await press(tester, find.byKey(const ValueKey('nudge Larger')));
    expect(fake.lastSet('dock.nudge'), [0, 0, 2]);
    await press(tester, find.text('Open Accessibility'));
    expect(fake.of('openSetting').last.arguments, 'accessibility');
    await endApp(tester);
  });

  testWidgets('the dock switched on with no leave to draw over apps says the setting was opened', (tester) async {
    fake.dock['on'] = false;
    fake.dock['overlay'] = false;
    fake.setAnswers = 'asked';
    await page(tester, 'dock');
    await press(tester, switchIn('dock.on'));
    expect(fake.lastSet('dock.on'), true);
    expect(find.text('Allow the dock to display over other apps'), findsOneWidget);
    await tester.pump(const Duration(seconds: 10));
    await endApp(tester);
  });

  testWidgets('a screen whose camera is not known has no choice of where the dock sits', (tester) async {
    fake.dock['camera'] = false;
    await page(tester, 'dock');
    expect(find.text('Beside the camera'), findsNothing);
    expect(find.text('Floating'), findsOneWidget);
    await endApp(tester);
  });

  testWidgets('Amphetamine\'s page: every setting a row, each written through the door', (tester) async {
    await page(tester, 'amphetamine');
    for (final key in ['awake.bright', 'awake.endOnBattery', 'awake.batteryFloor', 'awake.endUnplugged', 'awake.endFolded', 'awake.startCharging', 'awake.apps', 'awake.method.overlay', 'awake.method.system']) {
      expect(row(key), findsOneWidget, reason: key);
    }
    expect(find.text('At 20% and not charging.'), findsOneWidget);
    await press(tester, switchIn('awake.bright'));
    expect(fake.lastSet('awake.bright'), true);
    await press(tester, switchIn('awake.endUnplugged'));
    expect(fake.lastSet('awake.endUnplugged'), false);
    await press(tester, switchIn('awake.endFolded'));
    expect(fake.lastSet('awake.endFolded'), false);
    await press(tester, switchIn('awake.startCharging'));
    expect(fake.lastSet('awake.startCharging'), true);

    // The level: let go at the line's far end, it is 50.
    final line = find.byType(LevelSlider);
    await tester.tapAt(tester.getTopRight(line) + const Offset(-2, 20));
    await settle(tester);
    expect(fake.lastSet('awake.batteryFloor'), 50);
    expect(find.text('At 50% and not charging.'), findsOneWidget);

    await press(tester, find.descendant(of: row('awake.method.system'), matching: find.byType(Pick)));
    expect(fake.lastSet('awake.method'), 'system');

    // With the battery's ending off, there is no level to set.
    await press(tester, switchIn('awake.endOnBattery'));
    expect(fake.lastSet('awake.endOnBattery'), false);
    expect(row('awake.batteryFloor'), findsNothing);
    await endApp(tester);
  });

  testWidgets('the apps that keep it awake: a page of its own, a switch an app', (tester) async {
    await page(tester, 'amphetamine');
    await press(tester, row('awake.apps'));
    expect(tester.state<AppShellState>(find.byType(AppShell)).place, 'settings/amphetamine-apps');
    expect(find.text('Maps'), findsOneWidget);
    expect(find.text('Reader'), findsOneWidget);
    await press(tester, find.descendant(of: row('app com.example.reader'), matching: find.byType(GlacierSwitch)));
    expect(fake.lastSet('awake.apps'), ['com.example.reader']);
    await press(tester, find.descendant(of: row('app com.example.maps'), matching: find.byType(GlacierSwitch)));
    expect(fake.lastSet('awake.apps'), ['com.example.reader', 'com.example.maps']);
    await press(tester, find.descendant(of: row('app com.example.reader'), matching: find.byType(GlacierSwitch)));
    expect(fake.lastSet('awake.apps'), ['com.example.maps']);
    // Back on Amphetamine's page, the row names them.
    tester.state<AppShellState>(find.byType(AppShell)).back();
    await settle(tester);
    expect(find.descendant(of: row('awake.apps'), matching: find.text('Maps')), findsOneWidget);
    await endApp(tester);
  });

  testWidgets('apps chosen with no leave to know which is in front: a row that opens the setting', (tester) async {
    fake.awake['apps'] = ['com.example.maps'];
    fake.awake['appNames'] = ['Maps'];
    fake.awake['seesApps'] = false;
    await page(tester, 'amphetamine');
    await press(tester, find.descendant(of: row('awake.usage'), matching: find.byType(RowAction)));
    expect(fake.of('openSetting').last.arguments, 'usage');
    await endApp(tester);
  });

  testWidgets('Frames\' page: the scenario, the readout and its corner, the developer switches', (tester) async {
    fake.frames['hud'] = true;
    await page(tester, 'frames');
    for (final key in ['frames.scenario', 'frames.hud', 'frames.corner', 'frames.animation', 'frames.awake', 'frames.diagnostics']) {
      expect(row(key), findsOneWidget, reason: key);
    }
    await tester.enterText(find.descendant(of: row('frames.scenario'), matching: find.byType(EditableText)), 'scroll the feed');
    await settle(tester);
    expect(fake.of('scenario').last.arguments, 'scroll the feed');
    await press(tester, find.text('Low right'));
    expect(fake.of('corner').last.arguments, 2);
    await press(tester, find.text('5x slow'));
    expect(fake.of('animation').last.arguments, 5.0);
    await press(tester, switchIn('frames.awake'));
    expect(fake.of('awake').last.arguments, true);
    await press(tester, switchIn('frames.hud'));
    expect(fake.of('hud'), hasLength(1));
    expect(row('frames.corner'), findsNothing);
    await press(tester, row('frames.diagnostics'));
    expect(tester.state<AppShellState>(find.byType(AppShell)).place, 'frames/diagnostics');
    await endApp(tester);
  });

  testWidgets('Frames\' page without leave to change developer settings says how to give it', (tester) async {
    fake.frames['secure'] = false;
    await page(tester, 'frames');
    expect(row('frames.animation'), findsNothing);
    expect(find.textContaining('pm grant com.mattssoftware.tools android.permission.WRITE_SECURE_SETTINGS'), findsOneWidget);
    await endApp(tester);
  });

  testWidgets('Frames\' page: hardware use, on unless switched off, and how to let it read an app\'s own processor time', (tester) async {
    await page(tester, 'frames');
    expect(row('frames.hardware'), findsOneWidget);
    expect(tester.widget<GlacierSwitch>(switchIn('frames.hardware')).checked, isTrue);
    expect(find.textContaining('processor, memory, battery and data'), findsOneWidget);
    expect(find.textContaining('pm grant com.mattssoftware.tools android.permission.BATTERY_STATS'), findsOneWidget);
    await press(tester, switchIn('frames.hardware'));
    expect(fake.of('hardware').last.arguments, false);
    expect(tester.widget<GlacierSwitch>(switchIn('frames.hardware')).checked, isFalse);
    await endApp(tester);

    // With the permission given, the line that says how to give it is gone.
    fake.frames['batteryStats'] = true;
    fake.frames['hardware'] = true;
    await page(tester, 'frames');
    expect(find.textContaining('android.permission.BATTERY_STATS'), findsNothing);
    await endApp(tester);
  });

  testWidgets('Toasty\'s page: the degrees and whether the dock shows it, the dock\'s own two', (tester) async {
    await page(tester, 'toasty');
    expect(find.text('Now 31°C.'), findsOneWidget);
    await press(tester, find.text('Fahrenheit'));
    expect(fake.lastSet('dock.fahrenheit'), true);
    expect(find.text('Now 89°F.'), findsOneWidget);
    await press(tester, switchIn('toasty.heat'));
    expect(fake.lastSet('dock.heat'), false);
    await endApp(tester);
  });

  testWidgets('About: the version, each permission with where it stands, and this build', (tester) async {
    await page(tester, 'about');
    expect(find.text('1.0.1'), findsOneWidget);
    for (final key in ['notifications', 'overlay', 'usage', 'access', 'write', 'dump', 'secure', 'setup']) {
      expect(row('granted.$key'), findsOneWidget, reason: key);
    }
    expect(find.descendant(of: row('granted.overlay'), matching: find.text('Given')), findsOneWidget);
    // One not given has the way to Android's own setting for it.
    await press(tester, find.descendant(of: row('granted.access'), matching: find.byType(RowAction)));
    expect(fake.of('openSetting').last.arguments, 'accessibility');
    await press(tester, find.descendant(of: row('granted.write'), matching: find.byType(RowAction)));
    expect(fake.of('openSetting').last.arguments, 'write');
    await press(tester, row('granted.setup'));
    expect(fake.of('openSetting').last.arguments, 'setup');
    expect(find.descendant(of: row('build.Version'), matching: find.text('1.0.1 (2)')), findsOneWidget);
    expect(find.descendant(of: row('build.Phone'), matching: find.text('samsung SM-F971U')), findsOneWidget);
    await endApp(tester);
  });

  testWidgets('a permission given from a computer says the command while it is not', (tester) async {
    fake.granted['dump'] = false;
    await page(tester, 'about');
    expect(find.text('adb shell pm grant com.mattssoftware.tools android.permission.DUMP'), findsOneWidget);
    expect(find.descendant(of: row('granted.dump'), matching: find.text('Not given')), findsOneWidget);
    await endApp(tester);
  });

  testWidgets('Amphetamine\'s own page: the switch starts and ends a session', (tester) async {
    await pumpApp(tester, home: const AppShell(first: 'amphetamine'));
    await settle(tester);
    expect(find.text('Asleep'), findsOneWidget);
    await press(tester, row('awake.session'));
    expect(fake.lastSet('awake.session'), true);
    expect(find.text('Awake'), findsOneWidget);
    await press(tester, row('awake.session'));
    expect(fake.lastSet('awake.session'), false);
    await endApp(tester);
  });

  test('a length of time as Amphetamine says it', () {
    expect(spanOf(42000), '42s');
    expect(spanOf(312000), '5m12s');
    expect(spanOf(5405000), '1h30m5s');
    expect(spanOf(-5), '0s');
  });
}
