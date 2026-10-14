import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:frames/shell/app_shell.dart';
import 'package:frames/xray/xray.dart';
import 'package:frames/xray/xray_page.dart';

import '../support/fake_phone.dart';
import '../support/render.dart';

/// What the phone's side answers for Matt's Tools: the shape of xray/AnatomyParse.kt `toMap`, with the numbers of
/// its own package listing (test/xray's kotlin twin: AnatomyParseTest).
final Map<String, Object?> toolsAnatomy = {
  'package': 'com.mattssoftware.tools',
  'version': '1.1.0',
  'versionCode': 3,
  'headline': 'Flutter and native',
  'kinds': [
    {
      'name': 'Flutter',
      'evidence': [
        {'name': 'lib/arm64-v8a/libflutter.so', 'size': 11747864},
      ],
      'details': [
        {'label': 'Engine', 'value': 'libflutter.so in arm64-v8a', 'size': 11747864},
        {'label': 'Impeller', 'value': 'Not switched off in the manifest', 'size': null},
      ],
    },
    {
      'name': 'Native',
      'evidence': [
        {'name': 'classes.dex', 'size': 6326472},
      ],
      'details': [
        {'label': 'Jetpack Compose', 'value': 'Not in the package\'s version files', 'size': null},
      ],
    },
  ],
  'libs': [
    {
      'abi': 'arm64-v8a',
      'total': 15615392,
      'files': [
        {'name': 'libflutter.so', 'size': 11747864},
        {'name': 'libapp.so', 'size': 3867528},
      ],
    },
  ],
  'assets': [
    {'kind': 'Fonts', 'count': 3, 'bytes': 629124},
  ],
  'dex': {'count': 1, 'bytes': 6326472},
  'sdk': {'min': 29, 'target': 36, 'compile': 36},
  'apk': {
    'files': [
      {'name': 'base.apk', 'size': 23127949},
    ],
    'entries': 155,
  },
  'cannot': ['Which Flutter version built it: nothing in the package says.', 'Widget names: they are not in a release build.'],
};

void main() {
  setUpAll(loadAppFonts);
  late FakePhone fake;
  setUp(() => fake = FakePhone());

  testWidgets('the first page has X-ray in a second row, and a tap opens its page', (tester) async {
    await pumpApp(tester, home: const AppShell());
    await settle(tester);
    expect(find.text('X-ray'), findsOneWidget);
    // Under the four, not beside them: the row of four keeps its width.
    expect(tester.getTopLeft(find.text('X-ray')).dy, greaterThan(tester.getBottomLeft(find.text('Dock')).dy));
    await tester.tap(find.text('X-ray'));
    await settle(tester);
    expect(tester.state<AppShellState>(find.byType(AppShell)).place, xrayPlace);
    expect(find.byType(XrayPage), findsOneWidget);
    await endApp(tester);
  });

  testWidgets('the list shows the apps the phone gives, and a tap on one draws what it is made of', (tester) async {
    fake.anatomies['com.mattssoftware.tools'] = toolsAnatomy;
    await pumpApp(tester, size: const Size(390, 2600), home: const AppShell(first: xrayPlace));
    await settle(tester);
    expect(find.text('Maps'), findsOneWidget);
    expect(find.text('Matt\'s Tools'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('xray.app.com.mattssoftware.tools')));
    await settle(tester);
    expect(fake.of('xray').last.arguments, {'do': 'anatomy', 'package': 'com.mattssoftware.tools'});
    expect(find.text('Flutter and native'), findsOneWidget);
    expect(find.text('Version 1.1.0'), findsOneWidget);
    expect(find.text('libflutter.so in arm64-v8a, 11.7 MB'), findsOneWidget);
    expect(find.text('Not switched off in the manifest'), findsOneWidget);
    expect(find.text('Widget names: they are not in a release build.'), findsOneWidget);
    await shot(tester, 'xray-anatomy');
    await endApp(tester);
  });

  testWidgets('an app the phone cannot read says so in plain words, with the page still drawn', (tester) async {
    await pumpApp(tester, home: const AppShell(first: xrayPlace));
    await settle(tester);
    await tester.tap(find.byKey(const ValueKey('xray.app.com.example.maps')));
    await settle(tester);
    expect(find.text('Android would not let Matt\'s Tools read this app\'s package.'), findsOneWidget);
    expect(find.text('Maps'), findsOneWidget);
    await endApp(tester);
  });

  testWidgets('a phone that answers nothing at all does not throw', (tester) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(const MethodChannel('frames'), (call) async => null);
    await pumpApp(tester, home: const AppShell(first: xrayPlace));
    await settle(tester);
    expect(tester.takeException(), isNull);
    expect(find.text('No apps could be listed.'), findsOneWidget);
    expect((await Xray.anatomy('com.example.maps')).error, 'The phone gave no answer.');
    await endApp(tester);
  });

  testWidgets('with X-ray\'s switch off the page asks at once, once, and keeps its apps and the way to the switch', (tester) async {
    fake.xrayState['on'] = false;
    await pumpApp(tester, size: const Size(390, 1400), home: const AppShell(first: xrayPlace));
    await settle(tester);
    // Some seconds of the page looking again: still asked only once on this arrival.
    for (var i = 0; i < 4; i++) {
      await tester.pump(const Duration(seconds: 1));
    }
    expect(fake.of('xray').where((c) => (c.arguments as Map)['do'] == 'ask').length, 1);
    expect(find.byKey(const ValueKey('xray.switch')), findsOneWidget);
    expect(find.text('Open Accessibility'), findsOneWidget);
    expect(find.textContaining('If the switch is greyed out'), findsOneWidget);
    expect(find.text('Maps'), findsOneWidget);
    await tester.tap(find.text('Open Accessibility'));
    await settle(tester);
    expect(fake.of('xray').where((c) => (c.arguments as Map)['do'] == 'ask').length, 2);
    await shot(tester, 'xray-switch-off');
    await endApp(tester);
  });

  testWidgets('with the switch on the page asks nothing, and shows the last capture\'s app, count and inspectors', (tester) async {
    fake.xrayState['capture'] = {'package': 'com.mattssoftware.tools', 'label': 'Matt\'s Tools', 'count': 18, 'windows': 1, 'inspectors': ['Native', 'Flutter'], 'at': 0, 'picked': null, 'cut': false};
    await pumpApp(tester, size: const Size(390, 1400), home: const AppShell(first: xrayPlace));
    await settle(tester);
    expect(fake.of('xray').where((c) => (c.arguments as Map)['do'] == 'ask'), isEmpty);
    expect(find.text('X-ray is on. Start it from its tile over the app you want to look into.'), findsOneWidget);
    expect(find.byKey(const ValueKey('xray.capture')), findsOneWidget);
    expect(find.text('18 boxes'), findsOneWidget);
    expect(find.text('Read as Native and Flutter'), findsOneWidget);
    await shot(tester, 'xray-capture');
    // Closed on the phone: the state says there is none, and the page lets its copy go.
    fake.xrayState['capture'] = null;
    await tester.pump(const Duration(seconds: 1));
    await settle(tester);
    expect(find.byKey(const ValueKey('xray.capture')), findsNothing);
    expect(find.text('18 boxes'), findsNothing);
    await endApp(tester);
  });

  test('a state or capture that leaves keys out reads with defaults', () {
    final s = XrayState({'capture': {'count': 3}});
    expect(s.on, isFalse);
    expect(s.capture!.count, 3);
    expect(s.capture!.inspectors, isEmpty);
    final c = Capture({'boxes': [{'i': 0, 'label': 'Frames'}]});
    expect(c.boxes.single.label, 'Frames');
    expect(c.boxes.single.depth, 0);
    expect(c.boxes.single.password, isFalse);
  });

  test('an anatomy that leaves keys out reads as empty, not as an error', () {
    final a = Anatomy({'headline': 'Native'});
    expect(a.error, isNull);
    expect(a.kinds, isEmpty);
    expect(a.minSdk, isNull);
    expect(a.entries, 0);
  });

  test('sizes read as a person says them', () {
    expect(bytesOf(12), '12 bytes');
    expect(bytesOf(840000), '840 KB');
    expect(bytesOf(11747864), '11.7 MB');
    expect(bytesOf(2500000000), '2.50 GB');
  });
}
