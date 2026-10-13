import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:frames/settings/panes/dock_pane.dart';
import 'package:frames/settings/panes/frames_pane.dart';
import 'package:frames/settings/settings_screen.dart';
import 'package:frames/shell/app_shell.dart';
import 'package:frames/shell/bar_icon.dart';
import 'package:frames/shell/bar_parts.dart';
import 'package:frames/shell/top_bar.dart';
import 'package:frames/tools_pages.dart';
import 'package:glacier/glacier.dart';

import '../support/fake_phone.dart';
import '../support/render.dart';

Finder ring(BarGlyph glyph) => find.byWidgetPredicate((w) => w is BarRing && w.glyph == glyph);
BarRing ringOf(WidgetTester tester, BarGlyph glyph) => tester.widget<BarRing>(ring(glyph));
AppShellState shellOf(WidgetTester tester) => tester.state<AppShellState>(find.byType(AppShell));

bool canPop(WidgetTester tester) => tester.widget<PopScope>(find.byWidgetPredicate((w) => w is PopScope)).canPop;

Future<void> press(WidgetTester tester, Finder what) async {
  await tester.tap(what);
  await settle(tester);
}

void main() {
  setUpAll(loadAppFonts);
  late FakePhone fake;
  setUp(() => fake = FakePhone());

  testWidgets('the bar is over the page of tools: home, two dead arrows, and the cog', (tester) async {
    await pumpApp(tester, home: const AppShell());
    await settle(tester);
    expect(find.byType(TopBar), findsOneWidget);
    expect(find.byType(ToolsPage), findsOneWidget);
    expect(ringOf(tester, BarGlyph.house).on, isTrue);
    expect(ringOf(tester, BarGlyph.arrowLeft).onPressed, isNull);
    expect(ringOf(tester, BarGlyph.arrowRight).onPressed, isNull);
    expect(ringOf(tester, BarGlyph.settings).on, isFalse);
    // A ring is `--app-bar-ring` across, as Ghost.md's.
    final t = GlacierTheme.of(tester.element(find.byType(TopBar)));
    expect(tester.getSize(ring(BarGlyph.settings)), Size.square(t.len('app-bar-ring')));
    // With nowhere to go back to, Android's back is the system's own: it leaves the app.
    expect(canPop(tester), isTrue);
    await endApp(tester);
  });

  testWidgets('the arrows walk the pages he has been on, across tools, Frames and Settings', (tester) async {
    await pumpApp(tester, home: const AppShell());
    await settle(tester);
    await press(tester, ring(BarGlyph.settings));
    expect(shellOf(tester).place, 'settings');
    expect(ringOf(tester, BarGlyph.settings).on, isTrue);
    expect(ringOf(tester, BarGlyph.arrowLeft).onPressed, isNotNull);
    expect(ringOf(tester, BarGlyph.arrowRight).onPressed, isNull);

    await press(tester, find.text('Toasty'));
    expect(shellOf(tester).place, 'settings/toasty');

    await press(tester, ring(BarGlyph.house));
    expect(shellOf(tester).place, 'home');
    await press(tester, find.text('Frames'));
    expect(shellOf(tester).place, 'frames');

    await press(tester, ring(BarGlyph.arrowLeft));
    expect(shellOf(tester).place, 'home');
    await press(tester, ring(BarGlyph.arrowLeft));
    expect(shellOf(tester).place, 'settings/toasty');
    await press(tester, ring(BarGlyph.arrowLeft));
    expect(shellOf(tester).place, 'settings');
    await press(tester, ring(BarGlyph.arrowLeft));
    expect(shellOf(tester).place, 'home');
    expect(ringOf(tester, BarGlyph.arrowLeft).onPressed, isNull);

    await press(tester, ring(BarGlyph.arrowRight));
    await press(tester, ring(BarGlyph.arrowRight));
    expect(shellOf(tester).place, 'settings/toasty');
    expect(ringOf(tester, BarGlyph.arrowRight).onPressed, isNotNull);
    // Somewhere new forgets what was ahead.
    await press(tester, ring(BarGlyph.settings));
    expect(shellOf(tester).place, 'settings');
    expect(ringOf(tester, BarGlyph.arrowRight).onPressed, isNull);
    await endApp(tester);
  });

  testWidgets('Android\'s back does what the back arrow does, and leaves the app from the first page', (tester) async {
    await pumpApp(tester, home: const AppShell());
    await settle(tester);
    await press(tester, ring(BarGlyph.settings));
    await press(tester, find.text('Dock'));
    expect(find.byType(DockPane), findsOneWidget);
    expect(canPop(tester), isFalse);

    await tester.binding.handlePopRoute();
    await settle(tester);
    expect(shellOf(tester).place, 'settings');
    await tester.binding.handlePopRoute();
    await settle(tester);
    expect(shellOf(tester).place, 'home');
    expect(ringOf(tester, BarGlyph.arrowRight).onPressed, isNotNull);
    expect(canPop(tester), isTrue);
    await endApp(tester);
  });

  testWidgets('the cog on a tool\'s page opens that tool\'s settings', (tester) async {
    await pumpApp(tester, home: const AppShell(first: 'frames'));
    await settle(tester);
    await press(tester, ring(BarGlyph.settings));
    expect(shellOf(tester).place, 'settings/frames');
    expect(find.byType(FramesPane), findsOneWidget);
    await endApp(tester);
  });

  testWidgets('opened at a page from outside, the page of tools is one step back', (tester) async {
    fake.launch = 'settings/amphetamine';
    await pumpApp(tester, home: const AppShell());
    await settle(tester);
    expect(shellOf(tester).place, 'settings/amphetamine');
    await press(tester, ring(BarGlyph.arrowLeft));
    expect(shellOf(tester).place, 'home');

    // Already open, and asked for another page (a tap on the dock, a tile held down).
    await tester.binding.defaultBinaryMessenger.handlePlatformMessage('frames', const StandardMethodCodec().encodeMethodCall(const MethodCall('open', 'toasty')), (_) {});
    await settle(tester);
    expect(shellOf(tester).place, 'toasty');
    expect(find.byType(ToastyPage), findsOneWidget);
    // A page that is not one of the app's is not gone to.
    await tester.binding.defaultBinaryMessenger.handlePlatformMessage('frames', const StandardMethodCodec().encodeMethodCall(const MethodCall('open', 'nowhere')), (_) {});
    await settle(tester);
    expect(shellOf(tester).place, 'toasty');
    await endApp(tester);
  });

  testWidgets('on a window with room, Settings is the sections beside a page, and a row changes the page', (tester) async {
    await pumpApp(tester, size: wideWindow, home: const AppShell(first: 'settings'));
    await settle(tester);
    expect(settingsSplitFits(tester.element(find.byType(SettingsScreen))), isTrue);
    // The list, and the first section's page beside it.
    expect(find.byKey(const ValueKey('settings-list')), findsOneWidget);
    expect(find.byType(DockPane), findsOneWidget);
    expect(tester.getTopLeft(find.byType(DockPane)).dx, greaterThan(tester.getTopRight(find.byKey(const ValueKey('settings-list'))).dx - 1));

    await press(tester, find.byKey(const ValueKey('row frames')));
    expect(shellOf(tester).place, 'settings/frames');
    expect(find.byKey(const ValueKey('settings-list')), findsOneWidget);
    expect(find.byType(FramesPane), findsOneWidget);
    await endApp(tester);
  });

  testWidgets('on a phone, Settings is the list, then a page in its place', (tester) async {
    await pumpApp(tester, home: const AppShell(first: 'settings'));
    await settle(tester);
    expect(find.byKey(const ValueKey('settings-list')), findsOneWidget);
    expect(find.byType(DockPane), findsNothing);
    for (final name in ['Dock', 'Amphetamine', 'Frames', 'Toasty', 'About']) {
      expect(find.text(name), findsOneWidget, reason: name);
    }
    // Each row says where its tool stands.
    expect(find.text('On · floating'), findsOneWidget);
    expect(find.text('Asleep'), findsOneWidget);
    expect(find.text('31°C'), findsOneWidget);
    await press(tester, find.byKey(const ValueKey('row dock')));
    expect(find.byKey(const ValueKey('settings-list')), findsNothing);
    expect(find.byType(DockPane), findsOneWidget);
    await endApp(tester);
  });
}
