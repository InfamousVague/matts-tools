import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

import 'data.dart';
import 'home.dart';
import 'phone.dart';
import 'shell/app_shell.dart';
import 'ui.dart';

/// Four numbers in a grid: what a run, a build or an app comes to at a glance.
class Tiles extends StatelessWidget {
  const Tiles(this.stats, {super.key});
  final Stats stats;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    Widget tile(String name, String value, String unit) => Expanded(
          child: Panel(
            padding: EdgeInsets.all(t.space(3)),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Txt(name, size: 'xs', color: 'text-subtle'),
              Row(crossAxisAlignment: CrossAxisAlignment.baseline, textBaseline: TextBaseline.alphabetic, children: [
                Txt(value, size: 'xl', weight: 'semibold'),
                Txt(unit, size: 'xs', color: 'text-muted'),
              ]),
            ]),
          ),
        );
    return Row(children: [
      tile('Rate', n0(stats.fps), ' fps'),
      SizedBox(width: t.space(2)),
      tile('1% low', n0(stats.low1), ' fps'),
      SizedBox(width: t.space(2)),
      tile('Janky', n1(stats.jankPct), '%'),
      SizedBox(width: t.space(2)),
      tile('99th', n0(stats.p99), ' ms'),
    ]);
  }
}

/// A run's place in the app, and an app's.
String runPlace(Run run) => 'frames/run:${run.pkg}:${run.when.millisecondsSinceEpoch}';
String appPlace(String pkg) => 'frames/app:$pkg';

// ---- one app ----

class AppScreen extends StatefulWidget {
  const AppScreen({super.key, required this.pkg});
  final String pkg;
  @override
  State<AppScreen> createState() => _AppScreenState();
}

class _AppScreenState extends State<AppScreen> {
  final List<Subject> _picks = [];

  @override
  Widget build(BuildContext context) => ValueListenableBuilder<List<AppData>>(
        valueListenable: library,
        builder: (context, apps, _) {
          final app = apps.where((a) => a.pkg == widget.pkg).firstOrNull;
          if (app == null) return const Screen(title: 'Gone', children: [Note('This app has no runs kept any more.')]);
          final t = context.tokens;
          final picked = [for (final p in _picks) app.builds.where((b) => b.title == p.title).firstOrNull].whereType<Subject>().toList();
          return Screen(
            title: app.title,
            subtitle: app.pkg,
            leading: AppIcon(app.pkg, name: app.title, size: 44),
            children: [
              Tiles(app.stats),
              Heading(app.builds.length > 1 ? 'Builds: tick two to compare' : 'Builds'),
              for (final build in app.builds)
                Line(
                  title: build.title,
                  subtitle: '${build.subtitle} · jank ${n1(build.jankLow)} to ${n1(build.jankHigh)}% across runs',
                  trailing: Row(mainAxisSize: MainAxisSize.min, children: [
                    Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
                      Txt('${n0(build.stats.fps)} fps', weight: 'semibold'),
                      GlacierPill(size: GlacierPillSize.sm, tone: toneOf(build.stats.jankPct), child: Text('${n1(build.stats.jankPct)}%')),
                    ]),
                    if (app.builds.length > 1)
                      Pick(
                        on: picked.contains(build),
                        letter: picked.indexOf(build) == 0 ? 'A' : 'B',
                        onTap: () => setState(() {
                          final had = _picks.indexWhere((p) => p.title == build.title);
                          if (had >= 0) {
                            _picks.removeAt(had);
                          } else {
                            if (_picks.length == 2) _picks.removeAt(0);
                            _picks.add(build);
                          }
                        }),
                      ),
                  ]),
                ),
              if (picked.length == 2) Btn('Compare the two builds', kind: BtnKind.solid, onPressed: () => open(context, CompareScreen(a: picked[0], b: picked[1], builds: true), place: 'frames/builds:${picked[0].pkg}:${picked[0].title}:${picked[1].title}')),
              if (app.screens.isNotEmpty) ...[
                const Heading('Screens, all runs'),
                for (final s in app.screens.take(12))
                  Line(title: s.name, subtitle: '${s.frames} frames · ${s.janky} janky · ${s.slow} slow', trailing: GlacierPill(size: GlacierPillSize.sm, tone: toneOf(s.jankPct), child: Text('${n1(s.jankPct)}%'))),
              ],
              const Heading('Reports'),
              for (final run in app.runs.take(40))
                Line(
                  onTap: () => open(context, RunScreen(run: run), place: runPlace(run)),
                  title: stamp(run.when),
                  subtitle: '${run.buildName} · ${run.lastedSeconds}s${run.thermalWorst >= 3 ? ' · throttled' : ''}',
                  trailing: Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
                    Txt('${n0(run.stats.fps)} fps', weight: 'semibold'),
                    GlacierPill(size: GlacierPillSize.sm, tone: toneOf(run.stats.jankPct), child: Text('${n1(run.stats.jankPct)}%')),
                  ]),
                ),
              SizedBox(height: t.space(3)),
              Btn('Export this app as JSON', onPressed: () async {
                final done = await Phone.export(pkg: app.pkg);
                if (context.mounted && done) say(context, 'Exported ${app.runs.length} runs');
              }),
              SizedBox(height: t.space(2)),
              Btn(app.runs.length == 1 ? 'Delete this app\'s run' : 'Delete this app\'s ${app.runs.length} runs', kind: BtnKind.danger, onPressed: () async {
                await Phone.delete(app.pkg);
                await reload(Phone.state.value!.sessions);
                if (context.mounted) AppShell.of(context).leave();
              }),
            ],
          );
        },
      );
}

// ---- one run ----

class RunScreen extends StatelessWidget {
  const RunScreen({super.key, required this.run});
  final Run run;

  String? _range(String key, String unit, [int digits = 0]) {
    final all = [for (final s in run.seconds) if (s[key] is num) (s[key] as num).toDouble()];
    if (all.isEmpty) return null;
    final low = all.reduce((a, b) => a < b ? a : b), high = all.reduce((a, b) => a > b ? a : b);
    return low == high ? '${low.toStringAsFixed(digits)}$unit' : '${low.toStringAsFixed(digits)} to ${high.toStringAsFixed(digits)}$unit';
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final st = run.stats;
    final rx = run.seconds.fold<int>(0, (n, s) => n + ((s['rxKb'] as num?)?.toInt() ?? 0)), tx = run.seconds.fold<int>(0, (n, s) => n + ((s['txKb'] as num?)?.toInt() ?? 0));
    final phone = <(String, String)>[
      ('Battery', '${n1(run.startTempC)} to ${n1(run.endTempC)}°C'),
      if (_range('skinC', '°C', 1) case final v?) ('Skin', v),
      if (_range('cpuC', '°C', 1) case final v?) ('Processor', v),
      if (_range('gpuC', '°C', 1) case final v?) ('Graphics', v),
      ('Throttling at worst', '${run.thermalWorst} of 6'),
      if (_range('availMb', ' MB') case final v?) ('Memory free', v),
      if (_range('cpuFreqPct', '%') case final v?) ('Core clock speed', '$v of full'),
      if (_range('hz', ' Hz') case final v?) ('Refresh rate', v),
      if (run.seconds.any((s) => s.containsKey('rxKb'))) ('Network, whole phone', '$rx KB down, $tx KB up'),
      if (_range('batteryPct', '%') case final v?) ('Battery level', v),
    ];
    return Screen(
      title: stamp(run.when),
      subtitle: '${run.label} ${run.buildName}',
      leading: AppIcon(run.pkg, name: run.label, size: 44),
      children: [
        Tiles(st),
        SizedBox(height: t.space(2)),
        Panel(
          child: Txt(
            '${st.frames} frames over ${st.activeSeconds}s of drawing at ${run.refreshHz.round()} Hz\n'
            '${st.janky} janky, ${st.slow} slow, ${st.frozen} frozen\n'
            'Median ${n1(st.p50)} ms, 90th ${n1(st.p90)}, 95th ${n1(st.p95)}, worst ${n0(st.worst)}',
            size: 'sm',
            color: 'text-muted',
          ),
        ),
        const Heading('Refreshes missed, by frame'),
        Panel(child: GroupBars(labels: Stats.missedNames, a: [for (final n in st.missed) n.toDouble()])),
        Note('How many frames missed no refresh, one, two or three, and so on. At ${run.refreshHz.round()} Hz a refresh is ${n1(run.periodMs)} ms.'),
        if (run.seconds.length > 1) ...[
          const Heading('Second by second'),
          Panel(child: Strip(values: [for (final s in run.seconds) ((s['worstMs'] as num?)?.toDouble() ?? 0).clamp(0, run.periodMs * 12).toDouble()], mark: run.periodMs * 1.5)),
          const Note('The slowest frame of each second, cut off at twelve refreshes. Over the line is a missed refresh.'),
        ],
        if (run.screens.isNotEmpty) ...[
          const Heading('Screens'),
          for (final s in [...run.screens]..sort((a, b) => b.janky.compareTo(a.janky)))
            Line(title: s.name, subtitle: '${s.frames} frames in ${s.seconds}s · ${s.slow} slow · worst ${n0(s.worstMs)} ms', trailing: GlacierPill(size: GlacierPillSize.sm, tone: toneOf(s.jankPct), child: Text('${n1(s.jankPct)}%'))),
        ],
        if (run.slowFrames.isNotEmpty) ...[
          const Heading('Slow frames'),
          Panel(
            child: Column(children: [
              for (final e in run.slowFrames.take(30))
                Padding(
                  padding: EdgeInsets.symmetric(vertical: t.space(1)),
                  child: Row(children: [
                    SizedBox(width: 72, child: Txt('${n1(((e['t'] as num?) ?? 0) / 1000)}s', size: 'sm', color: 'text-muted')),
                    Expanded(child: Txt('${e['screen'] ?? ''}', size: 'sm', maxLines: 1)),
                    Txt('${n0(((e['ms'] as num?) ?? 0).toDouble())} ms', size: 'sm', weight: 'medium', color: e['kind'] == 'frozen' ? 'danger-text' : 'text'),
                  ]),
                ),
            ]),
          ),
          if (run.slowFrames.length > 30) Note('${run.slowFrames.length - 30} more in the export.'),
        ],
        const Heading('The phone meanwhile'),
        Panel(
          child: Column(children: [
            for (final (name, value) in phone)
              Padding(
                padding: EdgeInsets.symmetric(vertical: t.space(1)),
                child: Row(children: [Expanded(child: Txt(name, size: 'sm', color: 'text-muted')), Txt(value, size: 'sm')]),
              ),
          ]),
        ),
        SizedBox(height: t.space(4)),
        Btn('Export this report as JSON', onPressed: () async {
          final done = await Phone.export(pkg: run.pkg, startedAt: run.startedAt);
          if (context.mounted && done) say(context, 'Exported');
        }),
        SizedBox(height: t.space(2)),
        Btn('Delete this report', kind: BtnKind.danger, onPressed: () async {
          await Phone.delete(run.pkg, run.startedAt);
          await reload(Phone.state.value!.sessions);
          if (context.mounted) AppShell.of(context).leave();
        }),
      ],
    );
  }
}

// ---- two things side by side ----

class CompareScreen extends StatelessWidget {
  const CompareScreen({super.key, required this.a, required this.b, this.builds = false});
  final Subject a, b;

  /// Two builds of one app, where the spread between runs says whether a difference is real.
  final bool builds;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final x = a.stats, y = b.stats;
    Widget side(Subject s, String letter, String color) => Expanded(
          child: Panel(
            padding: EdgeInsets.all(t.space(3)),
            child: Column(children: [
              AppIcon(s.pkg, name: s.title, size: 48),
              SizedBox(height: t.space(2)),
              Txt(s.title, weight: 'semibold', maxLines: 1),
              Txt(s.subtitle, size: 'xs', color: 'text-muted', maxLines: 1),
              SizedBox(height: t.space(2)),
              Row(mainAxisAlignment: MainAxisAlignment.center, children: [
                Container(width: 10, height: 10, decoration: BoxDecoration(color: ink(context, color), shape: BoxShape.circle)),
                SizedBox(width: t.space(1)),
                Txt(letter, size: 'xs', weight: 'medium', color: 'text-muted'),
              ]),
            ]),
          ),
        );
    final gap = (x.jankPct - y.jankPct).abs();
    final smoother = x.jankPct <= y.jankPct ? a : b;
    final verdict = gap < 0.5
        ? 'Level on smoothness: ${n1(x.jankPct)}% janky against ${n1(y.jankPct)}%.'
        : '${smoother.title} is smoother: ${n1(smoother.stats.jankPct)}% janky against ${n1((identical(smoother, a) ? y : x).jankPct)}%.';
    final few = a.runs.length < 3 || b.runs.length < 3;
    final overlap = a.jankLow <= b.jankHigh && b.jankLow <= a.jankHigh;
    double share(Stats s, int i) => s.frames == 0 ? 0 : s.missed[i] * 100 / s.frames;
    return Screen(
      title: 'Side by side',
      children: [
        Row(children: [side(a, 'A', 'accent-solid'), SizedBox(width: t.space(2)), side(b, 'B', 'warning-solid')]),
        SizedBox(height: t.space(3)),
        Panel(color: 'surface-sunken', child: Txt(verdict, weight: 'medium')),
        const Heading('The numbers'),
        Panel(
          child: Column(children: [
            PairBars(name: 'Rate', a: x.fps, b: y.fps, unit: ' fps', higherIsBetter: true),
            PairBars(name: '1% low', a: x.low1, b: y.low1, unit: ' fps', higherIsBetter: true),
            PairBars(name: 'Janky frames', a: x.jankPct, b: y.jankPct, unit: '%', higherIsBetter: false),
            PairBars(name: 'Slow frames in a thousand', a: x.frames == 0 ? 0 : x.slow * 1000 / x.frames, b: y.frames == 0 ? 0 : y.slow * 1000 / y.frames, unit: '', higherIsBetter: false),
            PairBars(name: 'Worst frame', a: x.worst, b: y.worst, unit: ' ms', higherIsBetter: false, digits: 0),
          ]),
        ),
        const Heading('Frame times, ms'),
        Panel(child: GroupBars(labels: const ['Median', '90th', '95th', '99th'], a: [x.p50, x.p90, x.p95, x.p99], b: [y.p50, y.p90, y.p95, y.p99])),
        const Heading('Refreshes missed, % of frames'),
        Panel(child: GroupBars(labels: Stats.missedNames, a: [for (var i = 0; i < 5; i++) share(x, i)], b: [for (var i = 0; i < 5; i++) share(y, i)])),
        Note(
          !builds
              ? 'Two apps do different work${(x.periodMs - y.periodMs).abs() > 0.5 ? ', and these ran at different refresh rates (${n0(1000 / x.periodMs)} and ${n0(1000 / y.periodMs)} Hz)' : ''}: read this as how each feels, not as a race on equal terms. The change shown is B against A.'
              : few
                  ? 'Fewer than three runs on a side: treat the difference as a hint, not a result.'
                  : overlap
                      ? 'The runs of A and B overlap in jank (${n1(a.jankLow)} to ${n1(a.jankHigh)}% against ${n1(b.jankLow)} to ${n1(b.jankHigh)}%): the difference may be noise.'
                      : 'The runs of A and B do not overlap in jank: the difference is larger than the spread between runs.',
        ),
      ],
    );
  }
}

// ---- diagnostics ----

class DiagScreen extends StatefulWidget {
  const DiagScreen({super.key});
  @override
  State<DiagScreen> createState() => _DiagScreenState();
}

class _DiagScreenState extends State<DiagScreen> {
  String _text = '', _probe = '';

  @override
  void initState() {
    super.initState();
    _read();
  }

  Future<void> _read() async {
    final text = await Phone.diagnostics() ?? '';
    if (mounted) setState(() => _text = text);
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    return Screen(title: 'Diagnostics', children: [
      Panel(child: Txt(_text, size: 'sm', mono: true)),
      SizedBox(height: t.space(3)),
      Btn('Try a read now', onPressed: () async {
        setState(() => _probe = 'Reading…');
        final words = await Phone.probe() ?? '';
        if (mounted) setState(() => _probe = words);
        _read();
      }),
      if (_probe.isNotEmpty) Note(_probe),
      SizedBox(height: t.space(2)),
      Btn('Copy all of this', onPressed: () => copy(context, '$_text\n\n$_probe')),
    ]);
  }
}
