import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

import '../shell/app_shell.dart';
import '../tools.dart';
import '../ui.dart';
import 'xray.dart';

/// X-ray's page: X-ray's switch, the last reading of a screen, and the apps on the phone, each opening what it is
/// made of. Arriving with the switch off, it opens Accessibility at once (the brief: "if there is a setting that
/// needs turned on the app should prompt immediatly when tapping"), once an arrival; the apps stay usable without it.
class XrayPage extends StatefulWidget {
  const XrayPage({super.key});

  @override
  State<XrayPage> createState() => _XrayPageState();
}

class _XrayPageState extends State<XrayPage> {
  /// Null while the list is being read; empty where it could not be.
  List<AppChoice>? _apps;

  /// What the phone last said of X-ray; null until it has answered, or where it gives no answer.
  XrayState? _state;
  bool _asked = false;
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    Xray.apps().then((apps) {
      if (mounted) setState(() => _apps = apps);
    }).catchError((_) {
      if (mounted) setState(() => _apps = const []);
    });
    _look();
    // Looked at again each second: coming back from Accessibility, and after X-ray is closed on the phone, the page
    // says so, and lets go of the capture as soon as the phone has.
    _timer = Timer.periodic(const Duration(seconds: 1), (_) => _look());
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  Future<void> _look() async {
    final state = await Xray.state();
    if (!mounted) return;
    if (state != null && !state.on && !_asked) {
      _asked = true;
      Xray.ask();
    }
    setState(() => _state = state);
  }

  @override
  Widget build(BuildContext context) {
    final apps = _apps;
    final state = _state;
    final capture = state?.capture;
    return Screen(title: 'X-ray', subtitle: 'What an app is made of.', children: [
      if (state != null && !state.on) _switchOff(context),
      if (state != null && state.on) Note(state.running ? 'X-ray is drawing over the app in front. Close it from its bar.' : 'X-ray is on. Start it from its tile over the app you want to look into.'),
      if (state != null) ...[
        const Heading('Last capture'),
        if (capture == null) const Note('Nothing captured. Start X-ray from its tile over an app, then tap Open on its bar.') else _capture(context, capture),
      ],
      const Heading('What X-ray can read'),
      Panel(
        key: const ValueKey('xray.canread'),
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          for (final k in canRead) ..._kindWords(context, k),
          Txt(hiddenViewsNote, size: 'sm', color: 'text-muted'),
          SizedBox(height: context.tokens.space(3)),
          const Txt(flutterNavigationNote, size: 'sm', color: 'text-muted'),
        ]),
      ),
      const Heading('Apps'),
      const Note('Choose an app to see what it is built with, read from the files of its package. Nothing is run, installed or sent anywhere.'),
      if (apps == null) const Note('Reading the apps...') else if (apps.isEmpty) const Note('No apps could be listed.'),
      if (apps != null)
        for (final app in apps)
          Line(
            key: ValueKey('xray.app.${app.package}'),
            leading: AppIcon(app.package, name: app.label),
            title: app.label,
            subtitle: app.package,
            subtitleLines: 1,
            onTap: () => open(context, AnatomyPage(app: app), place: 'xray/anatomy:${app.package}'),
          ),
    ]);
  }

  List<Widget> _kindWords(BuildContext context, ({String kind, String reads, String cannot}) k) {
    final t = context.tokens;
    return [
      Txt(k.kind, weight: 'semibold'),
      SizedBox(height: t.space(1)),
      Txt('Reads: ${k.reads}', size: 'sm', color: 'text-muted'),
      Txt('Cannot read: ${k.cannot}', size: 'sm', color: 'text-muted'),
      SizedBox(height: t.space(3)),
    ];
  }

  Widget _switchOff(BuildContext context) {
    final t = context.tokens;
    return Panel(
      key: const ValueKey('xray.switch'),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        const Txt('X-ray\'s switch is off', weight: 'semibold'),
        SizedBox(height: t.space(1)),
        const Txt('To read the screen of the app in front, X-ray needs its own switch in Accessibility. Under Installed apps (Downloaded apps on some phones), switch on Matt\'s Tools: X-ray.', size: 'sm', color: 'text-muted'),
        SizedBox(height: t.space(3)),
        const Btn('Open Accessibility', onPressed: Xray.ask),
        SizedBox(height: t.space(3)),
        const Txt(
          'If the switch is greyed out, the phone is holding back an app it did not install from a store: open Settings, Apps, Matt\'s Tools, the three dots at the top, Allow restricted settings, then come back.',
          size: 'sm',
          color: 'text-muted',
        ),
      ]),
    );
  }

  Widget _capture(BuildContext context, CaptureSummary c) {
    final t = context.tokens;
    final at = DateTime.fromMillisecondsSinceEpoch(c.at);
    String two(int n) => n.toString().padLeft(2, '0');
    return Panel(
      key: const ValueKey('xray.capture'),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          AppIcon(c.package, name: c.label, size: 28),
          SizedBox(width: t.space(3)),
          Expanded(child: Txt(c.label, weight: 'semibold', maxLines: 1)),
          Txt('${two(at.hour)}:${two(at.minute)}:${two(at.second)}', size: 'sm', color: 'text-muted'),
        ]),
        SizedBox(height: t.space(2)),
        Txt('${c.count} ${c.count == 1 ? 'box' : 'boxes'}', size: 'xl', weight: 'semibold'),
        if (c.cut) const Txt('Cut short: X-ray reads at most 3000 boxes, 64 deep.', size: 'sm', color: 'text-muted'),
        if (c.inspectors.isNotEmpty) Txt('Read as ${_and(c.inspectors)}', size: 'sm', color: 'text-muted'),
        SizedBox(height: t.space(3)),
        Btn('Walk the tree', onPressed: () => AppShell.of(context).go(xrayTreePlace)),
        SizedBox(height: t.space(4)),
        const Txt('Compositor layers', weight: 'semibold'),
        SizedBox(height: t.space(1)),
        ..._layers(context, c),
      ]),
    );
  }

  /// The surfaces the system composes for the captured app, top first, each with what it is, its name and its place.
  List<Widget> _layers(BuildContext context, CaptureSummary c) {
    final t = context.tokens;
    final layers = c.layers;
    if (c.layersWhy != null) {
      return [
        Txt(c.layersWhy!, size: 'sm', color: 'text-muted'),
        SizedBox(height: t.space(2)),
        const Txt('adb shell pm grant com.mattssoftware.tools android.permission.DUMP', size: 'sm', mono: true),
      ];
    }
    if (layers == null) return const [Txt('Not read. Tap Layers on X-ray\'s bar, or Open, to read them.', size: 'sm', color: 'text-muted')];
    if (layers.isEmpty) return const [Txt('The compositor listed none for this app.', size: 'sm', color: 'text-muted')];
    String place(Rect r, int z) => '${r.left.round()}, ${r.top.round()}, ${r.width.round()} by ${r.height.round()}, z $z';
    return [
      for (final l in layers.reversed)
        Padding(
          key: ValueKey('xray.layer.${l.id}'),
          padding: EdgeInsets.only(top: t.space(2)),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Txt(l.words, size: 'sm', weight: 'semibold'),
            Txt(l.name, size: 'sm', mono: true, color: 'text-muted'),
            Txt(l.place == null ? 'Place not given' : place(l.place!, l.z), size: 'sm', color: 'text-muted'),
          ]),
        ),
    ];
  }

  String _and(List<String> words) => words.length == 1 ? words.first : '${words.sublist(0, words.length - 1).join(', ')} and ${words.last}';
}

/// The anatomy of one app: what it is built with and the files that show it, its libraries, assets, code and
/// Android levels, and what the package cannot say.
class AnatomyPage extends StatefulWidget {
  const AnatomyPage({super.key, required this.app});
  final AppChoice app;

  @override
  State<AnatomyPage> createState() => _AnatomyPageState();
}

class _AnatomyPageState extends State<AnatomyPage> {
  late final Future<Anatomy> _anatomy = Xray.anatomy(widget.app.package);

  @override
  Widget build(BuildContext context) => FutureBuilder<Anatomy>(
        future: _anatomy,
        builder: (context, snapshot) {
          final a = snapshot.data;
          return Screen(
            title: widget.app.label,
            subtitle: a == null || a.version.isEmpty ? widget.app.package : 'Version ${a.version}',
            leading: AppIcon(widget.app.package, name: widget.app.label, size: 44),
            children: [
              if (a == null) const Note('Reading the package...') else if (a.error != null) Panel(child: Txt(a.error!, color: 'text-muted')) else ..._facts(context, a),
            ],
          );
        },
      );

  List<Widget> _facts(BuildContext context, Anatomy a) {
    final t = context.tokens;
    final total = a.files.fold<int>(0, (sum, f) => sum + (f.size ?? 0));
    return [
      Panel(
        key: const ValueKey('xray.headline'),
        padding: EdgeInsets.symmetric(horizontal: t.space(5), vertical: t.space(5)),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          const Txt('Built with', size: 'sm', color: 'text-muted'),
          Txt(a.headline, size: '2xl', weight: 'semibold'),
          SizedBox(height: t.space(2)),
          Txt('${a.entries} entries in ${a.files.length} package ${a.files.length == 1 ? 'file' : 'files'}, ${bytesOf(total)}', size: 'sm', color: 'text-muted'),
        ]),
      ),
      for (final kind in a.kinds) ..._kind(context, kind),
      const Heading('Native libraries'),
      if (a.libs.isEmpty) const Note('None in the package files. Some apps keep theirs in another package, or have none.'),
      for (final libs in a.libs) _libs(context, libs),
      const Heading('Assets'),
      if (a.assets.isEmpty) const Note('None.') else _rows(context, [for (final x in a.assets) (x.kind, '${x.count} ${x.count == 1 ? 'file' : 'files'}', x.bytes)]),
      const Heading('Code'),
      _rows(context, [('Dex files', a.dexCount == 0 ? 'None' : '${a.dexCount}', a.dexCount == 0 ? null : a.dexBytes)]),
      const Heading('Android levels'),
      _rows(context, [
        ('Runs on', a.minSdk == null ? 'Not given' : 'Android API ${a.minSdk} and later', null),
        ('Built for', a.targetSdk == null ? 'Not given' : 'Android API ${a.targetSdk}', null),
        if (a.compileSdk != null) ('Compiled against', 'Android API ${a.compileSdk}', null),
      ]),
      const Heading('What it cannot say'),
      Panel(
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          for (final c in a.cannot) Padding(padding: EdgeInsets.only(bottom: t.space(2)), child: Txt(c, size: 'sm', color: 'text-muted')),
        ]),
      ),
    ];
  }

  List<Widget> _kind(BuildContext context, KindOf kind) {
    final t = context.tokens;
    return [
      Heading(kind.name),
      Panel(
        key: ValueKey('xray.kind.${kind.name}'),
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          if (kind.evidence.isNotEmpty) const Txt('Shown by', size: 'xs', color: 'text-subtle'),
          for (final e in kind.evidence) _fact(e.name, e.size == null ? null : bytesOf(e.size!), mono: true),
          for (final d in kind.details) Padding(padding: EdgeInsets.only(top: t.space(3)), child: _detail(d)),
        ]),
      ),
    ];
  }

  Widget _detail(Detail d) => Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Txt(d.label, size: 'xs', color: 'text-subtle'),
        Txt(d.size == null ? d.value : '${d.value}, ${bytesOf(d.size!)}', size: 'sm'),
      ]);

  Widget _fact(String name, String? size, {bool mono = false}) => Row(children: [
        Expanded(child: Txt(name, size: 'sm', mono: mono)),
        if (size != null) Txt(size, size: 'sm', color: 'text-muted'),
      ]);

  Widget _libs(BuildContext context, LibsOf libs) {
    final t = context.tokens;
    const shown = 8;
    return Padding(
      padding: EdgeInsets.only(bottom: t.space(2)),
      child: Panel(
        key: ValueKey('xray.libs.${libs.abi}'),
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          _fact(libs.abi, bytesOf(libs.total)),
          SizedBox(height: t.space(2)),
          for (final f in libs.files.take(shown)) _fact(f.name, f.size == null ? null : bytesOf(f.size!), mono: true),
          if (libs.files.length > shown) Padding(padding: EdgeInsets.only(top: t.space(1)), child: Txt('and ${libs.files.length - shown} more', size: 'sm', color: 'text-muted')),
        ]),
      ),
    );
  }

  Widget _rows(BuildContext context, List<(String, String, int?)> rows) => Panel(
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          for (final r in rows) Padding(padding: EdgeInsets.symmetric(vertical: context.tokens.space(1)), child: _fact('${r.$1}: ${r.$2}', r.$3 == null ? null : bytesOf(r.$3!))),
        ]),
      );
}
