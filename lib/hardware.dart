import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

import 'data.dart';
import 'ui.dart';

/// What an app cost the phone, as the pages show it: a run's own, an app's or a build's over its runs, and two side
/// by side. A number the phone would not give is said to be not available, with the reason, and never shown as 0.

/// Bytes in the largest unit that keeps them readable.
String bytesWords(double bytes) {
  if (bytes < 1000) return '${bytes.round()} B';
  if (bytes < 1024 * 1000) return '${(bytes / 1024).toStringAsFixed(bytes < 10240 ? 1 : 0)} KB';
  if (bytes < 1024 * 1024 * 1000) return '${(bytes / 1024 / 1024).toStringAsFixed(bytes < 10485760 ? 1 : 0)} MB';
  return '${(bytes / 1024 / 1024 / 1024).toStringAsFixed(2)} GB';
}

String _sentence(String why) => why.isEmpty ? '' : '${why[0].toUpperCase()}${why.substring(1)}${why.endsWith('.') || why.endsWith(')') ? '' : '.'}';

/// One line of a hardware panel: a name, its number, and a smaller line under it. With no [value] it reads
/// "Not available", and the line under it says why.
class HardwareRow {
  const HardwareRow(this.id, this.name, this.value, [this.detail]);
  final String id, name;
  final String? value, detail;
}

class HardwareRows extends StatelessWidget {
  const HardwareRows(this.rows, {super.key, this.missing = 'Not available'});
  final List<HardwareRow> rows;
  final String missing;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    return Panel(
      child: Column(children: [
        for (final row in rows)
          Padding(
            key: ValueKey('hardware.${row.id}'),
            padding: EdgeInsets.symmetric(vertical: t.space(1)),
            child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Txt(row.name, size: 'sm', color: 'text-muted'),
                SizedBox(width: t.space(3)),
                Expanded(child: Txt(row.value ?? missing, size: 'sm', color: row.value == null ? 'text-subtle' : 'text', align: TextAlign.right)),
              ]),
              if (row.detail != null && row.detail!.isNotEmpty) Txt(row.detail!, size: 'xs', color: 'text-subtle', align: TextAlign.right),
            ]),
          ),
      ]),
    );
  }
}

/// A run's hardware use, line by line.
List<HardwareRow> runRows(Hardware h) => [
      HardwareRow(
        'cpuApp',
        'Processor, app',
        h.cpuAppPct == null ? null : '${n1(h.cpuAppPct!)}% of one core',
        h.cpuAppPct == null
            ? _sentence(h.cpuAppWhy ?? '')
            : '${n1(h.cpuAppOfDevicePct ?? 0)}% of the phone\'s ${h.cores} cores · ${n1((h.cpuAppMs ?? 0) / 1000)}s of processor time${h.cpuAppPartly ? ' · counted only while unplugged' : ''}',
      ),
      HardwareRow(
        'cpuDevice',
        'Processor, phone',
        h.cpuDevicePct == null ? null : '${n0(h.cpuDevicePct!)}% average${h.cpuDevicePeakPct == null ? '' : ', ${n0(h.cpuDevicePeakPct!)}% at most'}',
        h.cpuDevicePct == null ? _sentence(h.cpuDeviceWhy ?? '') : 'Of all ${h.cores} cores, everything running',
      ),
      HardwareRow(
        'memory',
        'Memory, app',
        h.memoryAvgMb == null ? null : '${n0(h.memoryAvgMb!)} MB average, ${n0(h.memoryPeakMb ?? h.memoryAvgMb!)} MB at most',
        h.memoryAvgMb == null
            ? _sentence(h.memoryWhy ?? '')
            : '${h.memoryKind.toUpperCase()} over ${h.memorySamples} of the phone\'s own sample${h.memorySamples == 1 ? '' : 's'}, ${h.memoryProcesses} process${h.memoryProcesses == 1 ? '' : 'es'}',
      ),
      HardwareRow(
        'battery',
        'Battery, phone',
        h.drawMa == null ? null : '${n0(h.drawMa!)} mA${h.powerMw == null ? '' : ' · ${(h.powerMw! / 1000).toStringAsFixed(2)} W'}',
        h.drawMa == null
            ? _sentence(h.batteryWhy ?? '')
            : [
                if (h.pctPerHour != null) '${n1(h.pctPerHour!)}% an hour of about ${n0(h.capacityMah ?? 0)} mAh',
                if (h.usedMah != null) '${h.usedMah!.toStringAsFixed(h.usedMah! < 10 ? 2 : 0)} mAh used',
                if (h.charging) 'plugged in for ${h.pluggedSeconds}s, which is left out',
              ].join(' · '),
      ),
      HardwareRow(
        'data',
        'Data, app',
        h.rxBytes == null ? null : '${bytesWords(h.rxBytes!)} down, ${bytesWords(h.txBytes ?? 0)} up',
        h.rxBytes == null
            ? _sentence(h.dataWhy ?? '')
            : [
                if (h.seconds > 0) '${bytesWords(h.rxBytes! * 60 / h.seconds)} a minute down, ${bytesWords((h.txBytes ?? 0) * 60 / h.seconds)} up',
                h.mobileRxBytes == null ? 'Wi-Fi only: mobile not available' : 'mobile ${bytesWords(h.mobileRxBytes! + (h.mobileTxBytes ?? 0))} of it',
                if (h.dataCoarse) 'a run this short may read low',
              ].join(' · '),
      ),
    ];

/// The "Hardware" part of a run's page.
List<Widget> runHardware(BuildContext context, Run run) {
  final h = run.hardware;
  if (h == null) return const [Heading('Hardware'), Note('This run is from before Frames read what an app costs the phone.')];
  if (h.off) return const [Heading('Hardware'), Note('Hardware use was switched off in Frames\' settings for this run.')];
  final cpu = [for (final p in h.cpuSeries) p.$2];
  final memory = [for (final p in h.memorySeries) p.$2];
  return [
    const Heading('Hardware'),
    HardwareRows(runRows(h)),
    if (cpu.length > 2) ...[
      SizedBox(height: context.tokens.space(2)),
      Panel(key: const ValueKey('hardware.cpuChart'), child: Strip(values: cpu, mark: h.cpuDevicePct ?? 0, flag: false)),
      const Note('The whole phone\'s processor use through the run. The line is its average.'),
    ],
    if (memory.length > 2) ...[
      SizedBox(height: context.tokens.space(2)),
      Panel(key: const ValueKey('hardware.memoryChart'), child: Strip(values: memory, mark: h.memoryAvgMb ?? 0, flag: false)),
      const Note('The app\'s memory at each of the phone\'s own samples. The line is its average.'),
    ],
  ];
}

String _of(HardwareSum s, double? Function(Hardware) value, int runs) {
  final n = s.count(value);
  return n == runs ? '' : 'From $n of $runs run${runs == 1 ? '' : 's'}';
}

/// Several runs' hardware use, line by line: an app's, or a build's.
List<HardwareRow> sumRows(HardwareSum s, int runs) => [
      HardwareRow('cpuApp', 'Processor, app', s.cpuAppPct == null ? null : '${n1(s.cpuAppPct!)}% of one core', _of(s, (h) => h.cpuAppPct, runs)),
      HardwareRow(
        'cpuDevice',
        'Processor, phone',
        s.cpuDevicePct == null ? null : '${n0(s.cpuDevicePct!)}% average${s.cpuDevicePeakPct == null ? '' : ', ${n0(s.cpuDevicePeakPct!)}% at most'}',
        _of(s, (h) => h.cpuDevicePct, runs),
      ),
      HardwareRow('memory', 'Memory, app', s.memoryAvgMb == null ? null : '${n0(s.memoryAvgMb!)} MB average, ${n0(s.memoryPeakMb ?? s.memoryAvgMb!)} MB at most', _of(s, (h) => h.memoryAvgMb, runs)),
      HardwareRow(
        'battery',
        'Battery, phone',
        s.drawMa == null ? null : '${n0(s.drawMa!)} mA${s.pctPerHour == null ? '' : ' · ${n1(s.pctPerHour!)}% an hour'}',
        _of(s, (h) => h.drawMa, runs),
      ),
      HardwareRow(
        'data',
        'Data, app',
        s.rxPerMin == null ? null : '${bytesWords(s.rxPerMin!)} a minute down, ${bytesWords(s.txPerMin ?? 0)} up',
        [if (s.rxBytes != null) '${bytesWords(s.rxBytes!)} down and ${bytesWords(s.txBytes ?? 0)} up in all', _of(s, (h) => h.rxBytes, runs)].where((w) => w.isNotEmpty).join(' · '),
      ),
    ];

/// A few words of a build's hardware use for its line in a list: nothing where none was measured.
String briefHardware(HardwareSum s) => [
      if (s.cpuAppPct != null) 'app CPU ${n0(s.cpuAppPct!)}%' else if (s.cpuDevicePct != null) 'phone CPU ${n0(s.cpuDevicePct!)}%',
      if (s.memoryAvgMb != null) '${n0(s.memoryAvgMb!)} MB',
      if (s.drawMa != null) '${n0(s.drawMa!)} mA',
    ].join(' · ');

/// What the leaderboard can rank apps by, beside the frames' own: the value of each for an app, null where no run
/// of it has one.
const hardwareMetrics = ['cpu', 'memory', 'battery', 'data'];
double? hardwareValue(HardwareSum s, String metric) => switch (metric) {
      'cpu' => s.cpuDevicePct,
      'memory' => s.memoryAvgMb,
      'battery' => s.drawMa,
      'data' => s.bytesPerMin,
      _ => null,
    };
String hardwareWords(String metric, double v) => switch (metric) {
      'cpu' => '${n0(v)}%',
      'memory' => '${n0(v)} MB',
      'battery' => '${n0(v)} mA',
      _ => '${bytesWords(v)}/min',
    };
const hardwareNotes = {
  'cpu': 'The whole phone\'s processor use while the app was in front, of all its cores. The app\'s own share is on its page.',
  'memory': 'The app\'s memory, averaged over the phone\'s own samples of it.',
  'battery': 'The whole phone\'s draw while the app was in front and the phone unplugged.',
  'data': 'The app\'s own bytes a minute, down and up together.',
};

/// The "Hardware" part of a comparison: a pair of bars for each number both sides have, and a line for each they
/// do not.
List<Widget> compareHardware(BuildContext context, Subject a, Subject b) {
  final x = a.hardware, y = b.hardware;
  if (!x.any && !y.any) return const [];
  final t = context.tokens;
  final pairs = <(String, String, double?, double?, String, int)>[
    ('cpuApp', 'Processor, the app, of one core', x.cpuAppPct, y.cpuAppPct, '%', 1),
    ('cpuDevice', 'Processor, phone', x.cpuDevicePct, y.cpuDevicePct, '%', 0),
    ('memory', 'Memory, average', x.memoryAvgMb, y.memoryAvgMb, ' MB', 0),
    ('memoryPeak', 'Memory, at most', x.memoryPeakMb, y.memoryPeakMb, ' MB', 0),
    ('battery', 'Battery draw, whole phone', x.drawMa, y.drawMa, ' mA', 0),
    ('data', 'Data a minute', x.bytesPerMin == null ? null : x.bytesPerMin! / 1024, y.bytesPerMin == null ? null : y.bytesPerMin! / 1024, ' KB', 0),
  ];
  String lacking(double? p, double? q) => p == null && q == null ? 'not measured on either side' : 'not measured for ${p == null ? 'A' : 'B'}';
  return [
    const Heading('Hardware'),
    Panel(
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        for (final (id, name, p, q, unit, digits) in pairs)
          if (p != null && q != null)
            PairBars(key: ValueKey('hardware.$id'), name: name, a: p, b: q, unit: unit, higherIsBetter: false, digits: digits)
          else
            Padding(
              key: ValueKey('hardware.$id'),
              padding: EdgeInsets.only(bottom: t.space(3)),
              child: Row(children: [Expanded(child: Txt(name, size: 'sm', weight: 'medium')), Txt(lacking(p, q), size: 'xs', color: 'text-subtle')]),
            ),
      ]),
    ),
    const Note('Each side over its runs that measured it. The battery and the whole phone\'s processor are everything running, not the app alone.'),
  ];
}
