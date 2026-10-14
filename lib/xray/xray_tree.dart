import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

import '../art/app_icon.dart' as art;
import '../art/lucide.g.dart';
import '../tools.dart';
import '../ui.dart';
import 'exploded.dart';
import 'xray.dart';
import 'xray_page.dart';

/// One line of the tree as walked: a box, how far in it sits, and whether anything is under it.
class TreeRow {
  const TreeRow(this.box, this.level, this.hasChildren);
  final CaptureBox box;
  final int level;
  final bool hasChildren;
}

/// The rows of [boxes] in tree order with everything under a box that is not in [open] left out. A box whose parent
/// is not among them is a root, so nothing is lost to a capture that was cut short.
List<TreeRow> treeRows(List<CaptureBox> boxes, Set<int> open) {
  final known = {for (final b in boxes) b.i};
  final children = <int?, List<CaptureBox>>{};
  for (final b in boxes) {
    (children[b.parent != null && known.contains(b.parent) ? b.parent : null] ??= []).add(b);
  }
  final rows = <TreeRow>[];
  void walk(CaptureBox b, int level) {
    final under = children[b.i] ?? const [];
    rows.add(TreeRow(b, level, under.isNotEmpty));
    if (open.contains(b.i)) {
      for (final c in under) {
        walk(c, level + 1);
      }
    }
  }

  for (final root in children[null] ?? const <CaptureBox>[]) {
    walk(root, 0);
  }
  return rows;
}

String _short(String? cls) => cls == null ? 'view' : cls.substring(cls.lastIndexOf('.') + 1);

/// What a row says of its kind: "Native, TextView", "Flutter, semantics node", "React Native, Button".
String kindOf(CaptureBox b) {
  if (b.kind == 'flutter view') return 'Flutter view';
  return switch (b.inspector) {
    'flutter' => 'Flutter, semantics node',
    'react-native' => 'React Native, ${_short(b.cls)}',
    'compose' => 'Compose, likely, ${_short(b.cls)}',
    _ => 'Native, ${_short(b.cls)}',
  };
}

const String passwordWords = 'Password field: its text is never read';

/// What a box's card says, as the overlay's does (xray/XrayTree.kt `card`): what it is, its name, its place and depth,
/// what can be done to it. Nothing of a password box's text, whatever it was sent with.
List<String> cardLines(CaptureBox b, {bool surface = false}) {
  String list(List<String> w) => w.length == 1 ? w.first : '${w.sublist(0, w.length - 1).join(', ')} and ${w.last}';
  final flutter = b.inspector == 'flutter' || b.kind == 'flutter view';
  final name = [if (b.testId == null && b.id != null) b.id!, if (!b.password && b.label != null) b.label!].join(', ');
  final p = b.place;
  return [
    if (b.kind == 'flutter view') surface ? 'Flutter view: it draws to the Flutter surface' : 'Flutter view: it draws to a surface of its own' else if (b.inspector == 'flutter') 'Flutter, semantics node' else if (b.inspector == 'react-native' && b.testId != null) 'React Native, testID ${b.testId}' else if (b.inspector == 'react-native') 'React Native, ${b.cls ?? 'a view'}' else if (b.inspector == 'compose') 'Jetpack Compose, likely: ${b.cls ?? 'a node'}' else 'Native, ${b.cls ?? 'a view'}',
    if (name.isNotEmpty) name,
    if (b.password) passwordWords,
    '${p.left.round()}, ${p.top.round()}, ${p.width.round()} by ${p.height.round()}',
    'Depth ${b.depth}',
    if (b.can.isNotEmpty) 'Can be ${list(b.can)}',
    if (flutter) 'Widget names are not in a release build' else if (b.inspector == 'react-native') 'Component names are not in a release build',
  ];
}

/// The tree last captured, to walk through, and its exploded view: the page Open on X-ray's bar lands on, with the
/// box that was picked there open. It shows another app's boxes, so its window is kept out of screenshots, recordings
/// and Recents while it is up ([Xray.private]); and, like the page before it, it lets go of the capture as soon as the
/// phone has.
class XrayTreePage extends StatefulWidget {
  const XrayTreePage({super.key});

  @override
  State<XrayTreePage> createState() => _XrayTreePageState();
}

class _XrayTreePageState extends State<XrayTreePage> {
  Capture? _capture;
  bool _loaded = false;
  bool _exploded = false;
  Set<int> _open = {};
  int? _picked;
  Timer? _timer;
  final GlobalKey _pickedRow = GlobalKey();

  @override
  void initState() {
    super.initState();
    Xray.private(true);
    _load();
    // Looked at again each second, so a capture the phone has let go of is let go of here too.
    _timer = Timer.periodic(const Duration(seconds: 1), (_) => _check());
  }

  @override
  void dispose() {
    _timer?.cancel();
    Xray.private(false);
    super.dispose();
  }

  Future<void> _load() async {
    final capture = await Xray.capture();
    if (!mounted) return;
    setState(() {
      _loaded = true;
      _capture = capture;
      _picked = capture?.picked;
      // Down to depth 2 is open, and the way to the picked box.
      _open = {if (capture != null) for (final b in capture.boxes) if (b.depth < 3) b.i};
      if (capture != null) _openTo(capture, _picked);
    });
    WidgetsBinding.instance.addPostFrameCallback((_) {
      final at = _pickedRow.currentContext;
      if (mounted && at != null) Scrollable.ensureVisible(at, alignment: 0.3);
    });
  }

  Future<void> _check() async {
    final state = await Xray.state();
    if (!mounted || state == null || state.capture != null || _capture == null) return;
    setState(() => _capture = null);
  }

  void _openTo(Capture c, int? picked) {
    final byId = {for (final b in c.boxes) b.i: b};
    var at = picked == null ? null : byId[picked]?.parent;
    while (at != null) {
      _open.add(at);
      at = byId[at]?.parent;
    }
  }

  Future<void> _forget() async {
    await Xray.forget();
    if (mounted) setState(() => _capture = null);
  }

  @override
  Widget build(BuildContext context) {
    final c = _capture;
    return Screen(title: 'Tree', subtitle: 'The boxes last captured.', children: [
      if (!_loaded) const Note('Reading the capture...') else if (c == null) const Note('Nothing captured. Start X-ray from its tile over an app, then tap Open on its bar.') else ..._page(context, c),
    ]);
  }

  List<Widget> _page(BuildContext context, Capture c) {
    final t = context.tokens;
    final at = DateTime.fromMillisecondsSinceEpoch(c.at);
    String two(int n) => n.toString().padLeft(2, '0');
    final flutter = c.inspectors.contains('Flutter');
    final react = c.inspectors.contains('React Native');
    final picked = _picked == null ? null : c.boxes.where((b) => b.i == _picked).firstOrNull;
    final card = picked == null ? null : _card(context, c, picked);
    return [
      Line(
        key: const ValueKey('xray.app'),
        leading: AppIcon(c.package, name: c.label, size: 36),
        title: c.label,
        subtitle: 'Opens what it is built with',
        trailing: Txt('${two(at.hour)}:${two(at.minute)}:${two(at.second)}', size: 'sm', color: 'text-muted'),
        onTap: () => open(context, AnatomyPage(app: AppChoice({'package': c.package, 'label': c.label})), place: 'xray/anatomy:${c.package}'),
      ),
      Panel(
        key: const ValueKey('xray.tree.summary'),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Txt('${c.boxes.length} ${c.boxes.length == 1 ? 'box' : 'boxes'}', size: 'xl', weight: 'semibold'),
          if (c.cut) const Txt('Cut short: X-ray reads at most 3000 boxes, 64 deep.', size: 'sm', color: 'text-muted'),
          if (c.inspectors.isNotEmpty) Txt('Read as ${_and(c.inspectors)}', size: 'sm', color: 'text-muted'),
          if (flutter) const Txt('Widget names are not in a release build, so none are shown.', size: 'sm', color: 'text-muted'),
          if (react) const Txt('Component names are not in a release build, so none are shown.', size: 'sm', color: 'text-muted'),
          SizedBox(height: t.space(3)),
          GlacierSegmentedControl(
            size: GlacierSegmentedSize.sm,
            fullWidth: true,
            value: _exploded ? 'exploded' : 'tree',
            onValueChange: (v) => setState(() => _exploded = v == 'exploded'),
            options: const [GlacierSegmentedOption(value: 'tree', label: Text('Tree')), GlacierSegmentedOption(value: 'exploded', label: Text('Exploded'))],
          ),
        ]),
      ),
      SizedBox(height: t.space(3)),
      if (_exploded) ...[
        Panel(
          padding: EdgeInsets.all(t.space(2)),
          child: ExplodedView(capture: c, picked: _picked, onPick: (i) => setState(() => _picked = i)),
        ),
        const Note('Each depth is a plane, drawn in outline: nothing of the screen itself is taken. Drag to turn it, double tap to put it back, tap a box to open it.'),
        if (card != null) Padding(padding: EdgeInsets.only(top: t.space(3)), child: card),
      ] else ...[
        Row(children: [
          Btn('Fold all', wide: false, onPressed: () => setState(() => _open = {})),
          SizedBox(width: t.space(2)),
          Btn('Unfold all', wide: false, onPressed: () => setState(() => _open = {for (final b in c.boxes) b.i})),
        ]),
        SizedBox(height: t.space(3)),
        for (final row in treeRows(c.boxes, _open)) ..._row(context, c, row, row.box.i == _picked ? card : null),
      ],
      SizedBox(height: t.space(4)),
      Btn('Forget it', onPressed: _forget),
    ];
  }

  List<Widget> _row(BuildContext context, Capture c, TreeRow row, Widget? card) {
    final t = context.tokens;
    final b = row.box;
    final second = b.testId != null ? 'testID ${b.testId}' : (b.id != null && b.id!.isNotEmpty) ? b.id! : (b.inspector == 'flutter' ? b.cls : null);
    final words = b.password ? passwordWords : b.label;
    final on = b.i == _picked;
    return [
      Padding(
        key: on ? _pickedRow : null,
        padding: EdgeInsets.only(left: row.level.clamp(0, 10) * 12.0),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          GlacierPressable(
            key: ValueKey('xray.fold.${b.i}'),
            onPressed: row.hasChildren ? () => setState(() => _open.contains(b.i) ? _open.remove(b.i) : _open.add(b.i)) : null,
            semanticLabel: row.hasChildren ? (_open.contains(b.i) ? 'Fold' : 'Unfold') : null,
            builder: (context, state) => SizedBox(
              width: 40,
              height: 44,
              child: row.hasChildren ? Center(child: art.AppIcon(_open.contains(b.i) ? Lucide.chevronDown : Lucide.chevronRight, size: 18, wash: false, color: ink(context, 'text-muted'))) : null,
            ),
          ),
          Expanded(
            child: GlacierPressable(
              key: ValueKey('xray.row.${b.i}'),
              onPressed: () => setState(() => _picked = on ? null : b.i),
              builder: (context, state) => Container(
                constraints: const BoxConstraints(minHeight: 44),
                padding: EdgeInsets.symmetric(vertical: t.space(2), horizontal: t.space(2)),
                decoration: BoxDecoration(color: on || state.pressed ? ink(context, 'surface-sunken') : null, borderRadius: BorderRadius.circular(t.radius('md'))),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Txt(kindOf(b), weight: 'medium', maxLines: 1),
                  if (second != null) Txt(second, size: 'sm', color: 'text-muted', mono: true, maxLines: 1),
                  if (words != null && words.isNotEmpty) Txt(words, size: 'sm', color: 'text-muted', maxLines: 1),
                ]),
              ),
            ),
          ),
        ]),
      ),
      if (card != null) Padding(padding: EdgeInsets.only(left: t.space(10), bottom: t.space(2)), child: card),
    ];
  }

  Widget _card(BuildContext context, Capture c, CaptureBox b) {
    final surface = c.layers?.any((l) => l.kind == 'surface') ?? false;
    final lines = cardLines(b, surface: surface);
    return Panel(
      key: const ValueKey('xray.card'),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Txt(lines.first, weight: 'semibold'),
        for (final line in lines.skip(1)) Txt(line, size: 'sm', color: 'text-muted'),
      ]),
    );
  }

  String _and(List<String> words) => words.length == 1 ? words.first : '${words.sublist(0, words.length - 1).join(', ')} and ${words.last}';
}
