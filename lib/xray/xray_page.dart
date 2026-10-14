import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

import '../tools.dart';
import '../ui.dart';
import 'xray.dart';

/// X-ray's page: the apps on the phone, each opening what it is made of.
class XrayPage extends StatefulWidget {
  const XrayPage({super.key});

  @override
  State<XrayPage> createState() => _XrayPageState();
}

class _XrayPageState extends State<XrayPage> {
  /// Null while the list is being read; empty where it could not be.
  List<AppChoice>? _apps;

  @override
  void initState() {
    super.initState();
    Xray.apps().then((apps) {
      if (mounted) setState(() => _apps = apps);
    }).catchError((_) {
      if (mounted) setState(() => _apps = const []);
    });
  }

  @override
  Widget build(BuildContext context) {
    final apps = _apps;
    return Screen(title: 'X-ray', subtitle: 'What an app is made of.', children: [
      const Note('Choose an app to see what it is built with, read from the files of its package. Nothing is run, installed or sent anywhere.'),
      const Heading('Apps'),
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
