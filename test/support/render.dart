import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:frames/main.dart';
import 'package:frames/phone.dart';
import 'package:frames/tools.dart';

/// A test draws text as boxes unless the faces are loaded by hand. This loads the app's own (pubspec.yaml), so a
/// picture taken in a test is the picture the phone draws. (After Ghost.md's test/support/render.dart.)
Future<void> loadAppFonts() async {
  String? current;
  final found = <String, List<String>>{};
  for (final line in File('pubspec.yaml').readAsLinesSync()) {
    final name = RegExp(r'^\s*- family:\s*(.+)$').firstMatch(line);
    final asset = RegExp(r'^\s*- asset:\s*(.+)$').firstMatch(line);
    if (name != null) current = name.group(1)!.trim();
    if (asset != null && current != null) found.putIfAbsent(current, () => []).add(asset.group(1)!.trim());
  }
  for (final entry in found.entries) {
    final loader = FontLoader(entry.key);
    for (final file in entry.value) {
      loader.addFont(File(file).readAsBytes().then((bytes) => ByteData.view(Uint8List.fromList(bytes).buffer)));
    }
    await loader.load();
  }
}

/// A phone's screen, and a folding phone's opened out.
const Size phone = Size(390, 844);
const Size wideWindow = Size(900, 800);

final GlobalKey _picture = GlobalKey();

/// The app on a phone's screen (or [size]), ready for a picture: real fonts, real shadows, the token sheets the app
/// loads. [home] is the app's frame, or one screen in its place. [dark] is the phone's own theme.
Future<void> pumpApp(WidgetTester tester, {Size size = phone, double ratio = 2, bool dark = true, required Widget home}) async {
  debugDisableShadows = false;
  tester.view.physicalSize = size * ratio;
  tester.view.devicePixelRatio = ratio;
  tester.view.padding = FakeViewPadding(top: 36 * ratio, bottom: 20 * ratio);
  tester.view.viewPadding = FakeViewPadding(top: 36 * ratio, bottom: 20 * ratio);
  tester.platformDispatcher.platformBrightnessTestValue = dark ? Brightness.dark : Brightness.light;
  addTearDown(tester.view.reset);
  addTearDown(tester.platformDispatcher.clearPlatformBrightnessTestValue);
  final sheet = await tester.runAsync(loadAppSheet);
  await tester.pumpWidget(RepaintBoundary(key: _picture, child: FramesApp(sheet: sheet!, home: home)));
  await tester.pump();
}

/// Past the arrivals: a page's, the rows' and the cards'.
Future<void> settle(WidgetTester tester) async {
  for (var i = 0; i < 10; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

/// Ends a test that drew with real shadows ([pumpApp] turns them on, and the binding checks the switch as it ends),
/// and stops the shell's clock.
Future<void> endApp(WidgetTester tester) async {
  debugDisableShadows = true;
  await tester.pumpWidget(const SizedBox.shrink());
  await tester.pump(const Duration(seconds: 2));
  Tools.state.value = null;
  Phone.state.value = null;
}

/// Writes the screen as it stands to `build/pictures/<name>.png`: a picture to look at, not one a test is held to.
Future<void> shot(WidgetTester tester, String name) async {
  await settle(tester);
  final boundary = _picture.currentContext!.findRenderObject()! as RenderRepaintBoundary;
  await tester.runAsync(() async {
    final image = await boundary.toImage(pixelRatio: tester.view.devicePixelRatio);
    final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
    final file = File('build/pictures/$name.png');
    file.parent.createSync(recursive: true);
    file.writeAsBytesSync(bytes!.buffer.asUint8List());
    image.dispose();
  });
}
