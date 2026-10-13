import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

import 'data.dart';
import 'hardware.dart';
import 'pages.dart';
import 'phone.dart';
import 'shell/app_shell.dart';
import 'ui.dart';

const _months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
String two(int n) => n.toString().padLeft(2, '0');
String dayOf(DateTime d) => '${d.day} ${_months[d.month - 1]}';
String clockOf(DateTime d) => '${two(d.hour)}:${two(d.minute)}';
String stamp(DateTime d) => '${dayOf(d)}, ${clockOf(d)}';

/// The first page: measuring on or off with what it reads now, then the leaderboard, the past reports and the tools.
class Home extends StatefulWidget {
  const Home({super.key});
  @override
  State<Home> createState() => _HomeState();
}

class _HomeState extends State<Home> with WidgetsBindingObserver {
  Timer? _timer;
  int _ticks = 0;
  String _tab = 'board';
  String _metric = 'jank';
  String _order = 'best';
  final List<String> _picks = [];

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _look(runs: true);
    _timer = Timer.periodic(const Duration(seconds: 1), (_) => _look(runs: ++_ticks % 8 == 0));
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _timer?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state != AppLifecycleState.resumed) return;
    _look(runs: true);
    // The stay in the app just left is written as this one comes forward: looked for again once it has been.
    Future<void>.delayed(const Duration(seconds: 2), () => _look(runs: true));
  }

  Future<void> _look({bool runs = false}) async {
    try {
      final now = await Phone.refresh();
      if (runs) await reload(now.sessions);
    } catch (_) {
      // The phone's side is not up yet: the next second asks again.
    }
  }

  @override
  Widget build(BuildContext context) => ValueListenableBuilder<PhoneState?>(
        valueListenable: Phone.state,
        builder: (context, phone, _) => ValueListenableBuilder<List<AppData>>(
          valueListenable: library,
          builder: (context, apps, _) {
            if (phone == null) return ColoredBox(color: ink(context, 'bg'));
            if (!phone.ready) return _setup(phone);
            return Screen(
              title: 'Frames',
              trailing: GlacierPill(tone: phone.running ? GlacierPillTone.success : GlacierPillTone.neutral, child: Text(phone.running ? 'Measuring' : 'Off')),
              children: [
                _measuring(phone),
                SizedBox(height: context.tokens.space(4)),
                GlacierSegmentedControl(
                  fullWidth: true,
                  value: _tab,
                  onValueChange: (v) => setState(() => _tab = v),
                  options: const [
                    GlacierSegmentedOption(value: 'board', label: Text('Leaderboard')),
                    GlacierSegmentedOption(value: 'reports', label: Text('Reports')),
                    GlacierSegmentedOption(value: 'tools', label: Text('Tools')),
                  ],
                ),
                SizedBox(height: context.tokens.space(2)),
                ...switch (_tab) { 'board' => _board(apps), 'reports' => _reports(apps), _ => _tools(phone, apps) },
              ],
            );
          },
        ),
      );

  // ---- measuring ----

  Widget _measuring(PhoneState phone) {
    final live = phone.live;
    final t = context.tokens;
    return Panel(
      onTap: Phone.toggle,
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Row(children: [
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Txt(live != null ? '${live.fps} fps' : phone.running ? 'Measuring' : 'Not measuring', size: '2xl', weight: 'semibold'),
              Txt(
                live != null
                    ? '${live.app} · ${n1(live.jankPct)}% janky of ${live.frames} frames at ${live.hz.round()} Hz'
                    : phone.running
                        ? 'Open the app you want to measure.'
                        : 'Tap to start, then use the app you want to measure.',
                size: 'sm',
                color: 'text-muted',
              ),
            ]),
          ),
          IgnorePointer(child: GlacierSwitch(checked: phone.running, ariaLabel: 'Measuring')),
        ]),
        if (live != null && live.recent.isNotEmpty) Padding(padding: EdgeInsets.only(top: t.space(3)), child: Strip(values: live.recent, mark: 1500 / live.hz, slots: 120, height: 36)),
      ]),
    );
  }

  Widget _setup(PhoneState phone) => Screen(title: 'Frames', children: [
        const Note('Frames reads timings Android already keeps for every app. Android lets an app read them only after a computer says so, once:'),
        SizedBox(height: context.tokens.space(3)),
        Panel(child: Txt('adb shell pm grant ${phone.package} android.permission.DUMP\n\nadb shell appops set ${phone.package} GET_USAGE_STATS allow', size: 'sm', mono: true)),
        SizedBox(height: context.tokens.space(3)),
        Line(title: 'Read frame timings', subtitle: phone.dump ? 'Allowed' : 'Not yet: the first command', trailing: GlacierPill(tone: phone.dump ? GlacierPillTone.success : GlacierPillTone.warning, child: Text(phone.dump ? 'Yes' : 'No'))),
        Line(title: 'Know which app is in front', subtitle: phone.usage ? 'Allowed' : 'Not yet: the second command', trailing: GlacierPill(tone: phone.usage ? GlacierPillTone.success : GlacierPillTone.warning, child: Text(phone.usage ? 'Yes' : 'No'))),
        Btn('Diagnostics', onPressed: () => AppShell.of(context).go(diagnosticsPlace)),
      ]);

  // ---- leaderboard ----

  /// What an app is ranked by. Null where it is a hardware reading no run of the app has: such an app is listed last.
  double? _value(AppData a) => switch (_metric) { 'fps' => a.stats.fps, 'low' => a.stats.low1, 'jank' => a.stats.jankPct, _ => hardwareValue(a.hardware, _metric) };

  List<Widget> _board(List<AppData> apps) {
    final t = context.tokens;
    if (apps.isEmpty) return const [Note('Nothing measured yet. Switch measuring on, use an app for a while, and come back.')];
    final hardware = hardwareMetrics.contains(_metric);
    final higherIsBetter = _metric == 'fps' || _metric == 'low';
    final ranked = [for (final a in apps) if (_value(a) != null) a]..sort((a, b) => higherIsBetter ? _value(b)!.compareTo(_value(a)!) : _value(a)!.compareTo(_value(b)!));
    final shown = [...(_order == 'best' ? ranked : ranked.reversed), for (final a in apps) if (_value(a) == null) a];
    final most = ranked.map((a) => _value(a)!).fold(0.0001, (a, b) => a > b ? a : b);
    final unit = _metric == 'jank' ? '%' : ' fps';
    String words(double v) => hardware ? hardwareWords(_metric, v) : '${_metric == 'jank' ? n1(v) : n0(v)}$unit';
    final picked = [for (final p in _picks) apps.where((a) => a.pkg == p).firstOrNull].whereType<AppData>().toList();
    return [
      SizedBox(height: t.space(2)),
      Row(children: [
        Expanded(
          child: GlacierSegmentedControl(
            size: GlacierSegmentedSize.sm,
            fullWidth: true,
            value: _metric,
            onValueChange: (v) => setState(() => _metric = v),
            options: const [
              GlacierSegmentedOption(value: 'jank', label: Text('Jank')),
              GlacierSegmentedOption(value: 'fps', label: Text('fps')),
              GlacierSegmentedOption(value: 'low', label: Text('1% low')),
            ],
          ),
        ),
        SizedBox(width: t.space(2)),
        GlacierSegmentedControl(
          size: GlacierSegmentedSize.sm,
          value: _order,
          onValueChange: (v) => setState(() => _order = v),
          options: [GlacierSegmentedOption(value: 'best', label: Text(hardware ? 'Least' : 'Fastest')), GlacierSegmentedOption(value: 'worst', label: Text(hardware ? 'Most' : 'Slowest'))],
        ),
      ]),
      SizedBox(height: t.space(2)),
      // What each app cost the phone: the same choice as the row above, so one of the two has nothing picked.
      GlacierSegmentedControl(
        key: const ValueKey('board.hardware'),
        size: GlacierSegmentedSize.sm,
        fullWidth: true,
        value: _metric,
        onValueChange: (v) => setState(() => _metric = v),
        options: const [
          GlacierSegmentedOption(value: 'cpu', label: Text('CPU')),
          GlacierSegmentedOption(value: 'memory', label: Text('Memory')),
          GlacierSegmentedOption(value: 'battery', label: Text('Battery')),
          GlacierSegmentedOption(value: 'data', label: Text('Data')),
        ],
      ),
      SizedBox(height: t.space(3)),
      for (final (i, app) in shown.indexed)
        Line(
          onTap: () => open(context, AppScreen(pkg: app.pkg), place: appPlace(app.pkg)),
          leading: Row(mainAxisSize: MainAxisSize.min, children: [
            SizedBox(width: 22, child: Txt('${i + 1}', size: 'sm', weight: 'semibold', color: 'text-subtle')),
            AppIcon(app.pkg, name: app.title),
          ]),
          title: app.title,
          subtitle: app.subtitle,
          trailing: Row(mainAxisSize: MainAxisSize.min, children: [
            if (_value(app) case final v?) Txt(words(v), weight: 'semibold') else const Txt('Not measured', size: 'xs', color: 'text-subtle'),
            Pick(on: _picks.contains(app.pkg), letter: _picks.indexOf(app.pkg) == 0 ? 'A' : 'B', onTap: () => setState(() => _pick(app.pkg))),
          ]),
          under: _value(app) == null
              ? null
              : GlacierProgressBar(
            size: GlacierProgressBarSize.sm,
            value: _value(app)! / most * 100,
            tone: switch (toneOf(app.stats.jankPct)) { GlacierPillTone.success => GlacierProgressBarTone.success, GlacierPillTone.warning => GlacierProgressBarTone.warning, _ => GlacierProgressBarTone.danger },
            ariaLabel: app.title,
          ),
        ),
      if (apps.length > 1)
        Padding(
          padding: EdgeInsets.only(top: t.space(2)),
          child: picked.length == 2
              ? Btn('Compare ${picked[0].title} with ${picked[1].title}', kind: BtnKind.solid, onPressed: () => open(context, CompareScreen(a: picked[0], b: picked[1]), place: 'frames/compare:${picked[0].pkg}:${picked[1].pkg}'))
              : Note(picked.isEmpty ? 'Tick two apps to put them side by side.' : 'Tick one more to compare with ${picked[0].title}.'),
        ),
      Note(hardware
          ? '${hardwareNotes[_metric]} Over the runs that measured it; an app with none is listed last. The bar\'s colour is how janky the app is.'
          : 'Every run of an app counts. The bar is the app\'s share of the largest value; its colour is how janky the app is.'),
    ];
  }

  void _pick(String pkg) {
    if (!_picks.remove(pkg)) {
      if (_picks.length == 2) _picks.removeAt(0);
      _picks.add(pkg);
    }
  }

  // ---- reports ----

  List<Widget> _reports(List<AppData> apps) {
    final runs = allRuns;
    if (runs.isEmpty) return const [Note('No reports yet. Each stay in an app while measuring becomes one.')];
    final out = <Widget>[];
    String? day;
    for (final run in runs.take(120)) {
      final d = dayOf(run.when);
      if (d != day) out.add(Heading(day = d));
      out.add(Line(
        onTap: () => open(context, RunScreen(run: run), place: runPlace(run)),
        leading: AppIcon(run.pkg, name: run.label),
        title: run.label.isEmpty ? run.pkg : run.label,
        subtitle: '${clockOf(run.when)} · ${run.buildName} · ${run.lastedSeconds}s',
        trailing: Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
          Txt('${n0(run.stats.fps)} fps', weight: 'semibold'),
          GlacierPill(size: GlacierPillSize.sm, tone: toneOf(run.stats.jankPct), child: Text('${n1(run.stats.jankPct)}%')),
        ]),
      ));
    }
    return out;
  }

  // ---- tools ----

  /// What was this list's own to set (the scenario, the readout over the app, the developer switches) is Frames'
  /// page of Settings now; the scenario is still said here, since it is changed between one run and the next.
  List<Widget> _tools(PhoneState phone, List<AppData> apps) {
    final shell = AppShell.of(context);
    return [
      const Heading('Settings'),
      Line(
        title: 'Scenario',
        subtitle: phone.scenario.isEmpty ? 'None. Name what you are about to do, to compare like with like.' : phone.scenario,
        onTap: () => shell.go(settingsPlaceOf('frames')),
      ),
      Line(
        title: 'Frames\' settings',
        subtitle: 'The scenario, the readout over the app, hardware use, the developer switches',
        onTap: () => shell.go(settingsPlaceOf('frames')),
      ),
      const Heading('More'),
      Line(title: 'Diagnostics', subtitle: 'What Frames can read on this phone, and what it did lately', onTap: () => shell.go(diagnosticsPlace)),
      if (apps.isNotEmpty)
        Btn('Export everything as JSON', onPressed: () async {
          final done = await Phone.export();
          if (mounted && done) say(context, 'Exported ${allRuns.length} runs');
        }),
    ];
  }
}

Future<void> copy(BuildContext context, String words) async {
  await Clipboard.setData(ClipboardData(text: words));
  if (context.mounted) say(context, 'Copied');
}
