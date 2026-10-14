import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:frames/shell/app_shell.dart';
import 'package:frames/xray/exploded.dart';
import 'package:frames/xray/xray.dart';
import 'package:frames/xray/xray_tree.dart';

import '../support/fake_phone.dart';
import '../support/render.dart';

Map<String, Object?> box(int i, int? parent, int depth, String kind, String inspector, String? cls, {String? id, String? label, List<String> can = const [], bool password = false, List<int> at = const [0, 0, 1248, 1972]}) => {
      'i': i, 'parent': parent, 'depth': depth, 'kind': kind, 'inspector': inspector, 'cls': cls, 'id': id, 'label': label,
      'l': at[0], 't': at[1], 'r': at[2], 'b': at[3], 'can': can, 'password': password, //
    };

/// Matt's Tools' Frames page as the emulator showed it (the plan's section 1): 4 native boxes down to the Flutter host,
/// then 14 semantics nodes, 13 of them `android.view.View` and one the `android.widget.Button` "Diagnostics".
Map<String, Object?> framesCapture({int? picked, bool password = false}) => {
      'package': 'com.mattssoftware.tools', 'label': 'Matt\'s Tools', 'at': 0, 'width': 1248, 'height': 1972, 'rotation': 0,
      'inspectors': ['Native', 'Flutter'], 'picked': picked, 'cut': false,
      'boxes': [
        box(0, null, 0, 'view', 'native', 'android.widget.FrameLayout'),
        box(1, 0, 1, 'view', 'native', 'android.widget.LinearLayout', id: 'com.mattssoftware.tools:id/action_bar_root'),
        box(2, 1, 2, 'view', 'native', 'android.widget.FrameLayout', id: 'android:id/content'),
        box(3, 2, 3, 'flutter view', 'native', 'android.widget.FrameLayout'),
        box(4, 3, 4, 'semantics node', 'flutter', 'android.view.View'),
        for (var i = 5; i < 17; i++) box(i, 4, 5, 'semantics node', 'flutter', 'android.view.View', label: i == 5 ? 'Frames' : null, at: [0, 100 * (i - 4), 1248, 100 * (i - 3)]),
        box(17, 4, 5, 'semantics node', 'flutter', password ? 'android.widget.EditText' : 'android.widget.Button', label: password ? 'hunter2' : 'Diagnostics', can: ['tapped', 'long pressed'], password: password, at: [48, 1319, 1200, 1448]),
      ],
    };

void main() {
  setUpAll(loadAppFonts);
  late FakePhone fake;
  setUp(() => fake = FakePhone());

  Future<void> arrive(WidgetTester tester, Map<String, Object?>? capture) async {
    fake.xrayCapture = capture;
    fake.xrayState['capture'] = capture == null ? null : {'package': capture['package'], 'label': capture['label'], 'count': (capture['boxes'] as List).length, 'inspectors': capture['inspectors'], 'picked': capture['picked']};
    await pumpApp(tester, size: const Size(390, 2400), home: const AppShell(first: xrayTreePlace));
    await settle(tester);
  }

  testWidgets('the tree page shows the count, folds and unfolds, and each row says its kind, class and id', (tester) async {
    await arrive(tester, framesCapture());
    expect(find.byType(XrayTreePage), findsOneWidget);
    expect(find.text('18 boxes'), findsOneWidget);
    // Down to depth 2 is open at first: the host (depth 3) is a row, its semantics nodes are folded under it.
    expect(find.byKey(const ValueKey('xray.row.3')), findsOneWidget);
    expect(find.byKey(const ValueKey('xray.row.4')), findsNothing);
    expect(find.text('Native, LinearLayout'), findsOneWidget);
    expect(find.text('com.mattssoftware.tools:id/action_bar_root'), findsOneWidget);
    expect(find.text('Flutter view'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('xray.fold.3')));
    await settle(tester);
    expect(find.byKey(const ValueKey('xray.row.4')), findsOneWidget);
    // The root of the Flutter nodes has twelve children and one button under it, not yet shown.
    expect(find.byKey(const ValueKey('xray.row.17')), findsNothing);
    await tester.tap(find.byKey(const ValueKey('xray.fold.4')));
    await settle(tester);
    expect(find.byKey(const ValueKey('xray.row.17')), findsOneWidget);
    expect(find.text('Flutter, semantics node'), findsWidgets);
    expect(find.text('Diagnostics'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('xray.fold.4')));
    await settle(tester);
    expect(find.byKey(const ValueKey('xray.row.17')), findsNothing);
    await endApp(tester);
  });

  testWidgets('a Flutter capture says widget names are not in a release build', (tester) async {
    await arrive(tester, framesCapture());
    expect(find.textContaining('Widget names are not in a release build'), findsWidgets);
    await endApp(tester);
  });

  testWidgets('a box opens its card; the picked box on the overlay is open on arrival, in the tree', (tester) async {
    await arrive(tester, framesCapture(picked: 17));
    // Its parents were folded, and are open now.
    expect(find.byKey(const ValueKey('xray.row.17')), findsOneWidget);
    expect(find.byKey(const ValueKey('xray.card')), findsOneWidget);
    expect(find.text('48, 1319, 1152 by 129'), findsOneWidget);
    expect(find.text('Depth 5'), findsOneWidget);
    expect(find.text('Can be tapped and long pressed'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('xray.row.1')));
    await settle(tester);
    expect(find.text('0, 0, 1248 by 1972'), findsOneWidget);
    expect(find.text('Depth 1'), findsOneWidget);
    expect(find.text('Depth 5'), findsNothing);
    await shot(tester, 'xray-tree');
    await endApp(tester);
  });

  testWidgets('a password box shows none of its text, whatever the phone sent', (tester) async {
    await arrive(tester, framesCapture(picked: 17, password: true));
    expect(find.textContaining('hunter2'), findsNothing);
    expect(find.text('Password field: its text is never read'), findsWidgets);
    await endApp(tester);
  });

  testWidgets('the captured app\'s name opens its anatomy, so an app with no launcher icon has one', (tester) async {
    fake.anatomies['com.mattssoftware.tools'] = {'package': 'com.mattssoftware.tools', 'headline': 'Flutter and native', 'kinds': [], 'cannot': []};
    await arrive(tester, framesCapture());
    await tester.tap(find.text('Matt\'s Tools'));
    await settle(tester);
    expect(find.text('Flutter and native'), findsOneWidget);
    expect(fake.of('xray').where((c) => (c.arguments as Map)['do'] == 'anatomy').length, 1);
    await endApp(tester);
  });

  testWidgets('the page asks for no Recents picture while it is shown, and lets that go when it goes', (tester) async {
    await arrive(tester, framesCapture());
    expect(fake.privateOn, isTrue);
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump();
    expect(fake.privateOn, isFalse);
    await endApp(tester);
  });

  testWidgets('a new capture opened while the page is still up is shown in place of the old one', (tester) async {
    await arrive(tester, framesCapture(picked: 17));
    expect(find.text('18 boxes'), findsOneWidget);
    // Home pressed with the page left up, X-ray started over Settings, Open tapped: the place is the same, so the
    // shell does not build the page again, and the phone now holds Settings' capture.
    final settings = {
      'package': 'com.android.settings', 'label': 'Settings', 'at': 5000, 'width': 1248, 'height': 1972, 'rotation': 0, 'inspectors': ['Native'], 'picked': 1, 'cut': false,
      'boxes': [
        box(0, null, 0, 'view', 'native', 'android.widget.FrameLayout'),
        box(1, 0, 1, 'view', 'native', 'android.widget.TextView', id: 'android:id/title', label: 'Network & internet', at: [210, 670, 646, 741]),
      ],
    };
    fake.xrayCapture = settings;
    fake.xrayState['capture'] = {'package': 'com.android.settings', 'label': 'Settings', 'count': 2, 'inspectors': ['Native'], 'at': 5000, 'picked': 1};
    await tester.pump(const Duration(seconds: 1));
    await settle(tester);
    expect(find.text('2 boxes'), findsOneWidget);
    expect(find.text('18 boxes'), findsNothing);
    expect(find.text('Settings'), findsOneWidget);
    // Its own pick is open, not the old one's.
    expect(find.text('210, 670, 436 by 71'), findsOneWidget);
    expect(find.text('48, 1319, 1152 by 129'), findsNothing);
    await endApp(tester);
  });

  testWidgets('while X-ray is drawing the page keeps what it shows, though each reading is a new capture', (tester) async {
    await arrive(tester, framesCapture());
    fake.xrayState['running'] = true;
    fake.xrayState['capture'] = {'package': 'com.mattssoftware.tools', 'label': 'Matt\'s Tools', 'count': 18, 'inspectors': ['Native', 'Flutter'], 'at': 7000};
    final asked = fake.of('xray').where((c) => (c.arguments as Map)['do'] == 'capture').length;
    await tester.pump(const Duration(seconds: 2));
    await settle(tester);
    expect(fake.of('xray').where((c) => (c.arguments as Map)['do'] == 'capture').length, asked);
    expect(find.text('18 boxes'), findsOneWidget);
    await endApp(tester);
  });

  testWidgets('with nothing captured the page says how to capture, and Forget it drops the capture', (tester) async {
    await arrive(tester, null);
    expect(find.textContaining('Nothing captured'), findsOneWidget);
    await endApp(tester);
    await arrive(tester, framesCapture());
    await tester.tap(find.text('Forget it'));
    await settle(tester);
    expect(fake.of('xray').where((c) => (c.arguments as Map)['do'] == 'forget').length, 1);
    await tester.pump(const Duration(seconds: 1));
    await settle(tester);
    expect(find.text('18 boxes'), findsNothing);
    expect(find.textContaining('Nothing captured'), findsOneWidget);
    await endApp(tester);
  });

  testWidgets('the exploded view turns with a drag and goes back on a double tap, and a tap on a box opens its card', (tester) async {
    await arrive(tester, framesCapture());
    await tester.tap(find.text('Exploded'));
    await settle(tester);
    ExplodedPainter painter() => tester.widget<CustomPaint>(find.descendant(of: find.byKey(const ValueKey('xray.exploded')), matching: find.byType(CustomPaint))).painter! as ExplodedPainter;
    final rest = (painter().yaw, painter().pitch);
    expect(rest, (ExplodedView.restYaw, ExplodedView.restPitch));
    await tester.drag(find.byKey(const ValueKey('xray.exploded')), const Offset(60, -40));
    await tester.pump();
    expect(painter().yaw, isNot(rest.$1));
    expect(painter().pitch, isNot(rest.$2));
    final at = tester.getCenter(find.byKey(const ValueKey('xray.exploded')));
    await tester.tapAt(at);
    await tester.pump(const Duration(milliseconds: 100));
    await tester.tapAt(at);
    await settle(tester);
    expect((painter().yaw, painter().pitch), rest);
    await shot(tester, 'xray-exploded');
    // A tap on a box opens its card under the view.
    await tester.tapAt(at);
    await settle(tester);
    expect(find.byKey(const ValueKey('xray.card')), findsOneWidget);
    await endApp(tester);
  });

  test('rows follow the tree in order, hide what is folded, and say which have something under them', () {
    final boxes = [for (final b in (framesCapture()['boxes'] as List)) CaptureBox(b as Map)];
    final all = treeRows(boxes, {for (final b in boxes) b.i});
    expect(all.length, 18);
    expect([for (final r in all.take(5)) r.box.i], [0, 1, 2, 3, 4]);
    expect(all[4].level, 4);
    expect(all[17].hasChildren, isFalse);
    expect(all[4].hasChildren, isTrue);
    final folded = treeRows(boxes, {0, 1, 2});
    expect([for (final r in folded) r.box.i], [0, 1, 2, 3]);
  });

  test('a box whose parent is not in the list is a root, and nothing is lost', () {
    final boxes = [CaptureBox({'i': 7, 'parent': 3, 'depth': 2}), CaptureBox({'i': 8, 'parent': 7, 'depth': 3})];
    expect([for (final r in treeRows(boxes, {7})) r.box.i], [7, 8]);
  });
}
