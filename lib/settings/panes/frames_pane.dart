import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

import '../../phone.dart';
import '../../ui.dart' show say;
import '../kit/settings_kit.dart';

/// Frames' settings: the scenario its runs are filed under, the readout over the app being measured, and the
/// phone's own developer switches. They were the Tools list of Frames' first page; each still goes through the same
/// door ([Phone]) to the same preferences (frames/Pieces.kt `Prefs`) and the phone's own global settings.
class FramesPane extends StatelessWidget {
  const FramesPane({super.key, required this.phone, this.onDiagnostics});
  final PhoneState phone;

  /// Opens the diagnostics page.
  final VoidCallback? onDiagnostics;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    return PaneBody(
      children: [
        PaneSection(
          title: 'Scenario',
          footer: const Text('Runs are grouped by app, build and scenario. Do the same thing on each build to compare like with like.'),
          children: [
            SettingRow(
              key: const ValueKey('frames.scenario'),
              label: 'What you are about to do',
              layout: SettingRowLayout.stacked,
              control: GlacierInput(defaultValue: phone.scenario, placeholder: 'e.g. scroll the feed', onChange: Phone.scenario),
            ),
          ],
        ),
        PaneSection(
          title: 'Readout over the app',
          children: [
            SettingRow(
              key: const ValueKey('frames.hud'),
              label: 'Show the readout',
              hint: Text(phone.overlay
                  ? 'The rate, the jank and the last frames in a corner of whatever is being measured.'
                  : 'The rate, the jank and the last frames in a corner of whatever is being measured. It has to be allowed to display over other apps: switching it on opens that setting, and it is on when you come back.'),
              control: GlacierSwitch(
                ariaLabel: 'Show the readout',
                checked: phone.hud,
                onCheckedChange: (_) async {
                  if (await Phone.hud() == 'asked' && context.mounted) say(context, 'Allow Frames to display over other apps');
                },
              ),
            ),
            if (phone.hud)
              SettingRow(
                key: const ValueKey('frames.corner'),
                label: 'Corner',
                layout: SettingRowLayout.stacked,
                control: GlacierSegmentedControl(
                  size: GlacierSegmentedSize.sm,
                  fullWidth: true,
                  value: '${phone.corner}',
                  onValueChange: (v) => Phone.corner(int.parse(v)),
                  options: const [
                    GlacierSegmentedOption(value: '0', label: Text('Top left')),
                    GlacierSegmentedOption(value: '1', label: Text('Top right')),
                    GlacierSegmentedOption(value: '3', label: Text('Low left')),
                    GlacierSegmentedOption(value: '2', label: Text('Low right')),
                  ],
                ),
              ),
          ],
        ),
        PaneSection(
          title: 'Hardware',
          footer: phone.batteryStats
              ? null
              : Text('The app\'s own share of the processor is the system\'s count, which Android gives only after a computer says so, once: adb shell pm grant ${phone.package} android.permission.BATTERY_STATS. Without it a run still has the whole phone\'s processor use.'),
          children: [
            SettingRow(
              key: const ValueKey('frames.hardware'),
              label: 'Hardware use',
              hint: const Text('Reads the app\'s processor, memory, battery and data while measuring, and keeps them with each run. A reading this phone will not give is said so, never shown as nothing.'),
              control: GlacierSwitch(checked: phone.hardware, ariaLabel: 'Hardware use', onCheckedChange: Phone.hardware),
            ),
          ],
        ),
        if (!phone.secure)
          PaneSection(
            title: 'Developer switches',
            description: const Text('These change the phone\'s own developer settings, which Android allows only after a computer says so, once:'),
            children: [
              SettingRowBox(
                key: const ValueKey('frames.secure'),
                child: Text('adb shell pm grant ${phone.package} android.permission.WRITE_SECURE_SETTINGS', style: t.text('sm', mono: true, color: t.color('glacier-text'))),
              ),
            ],
          )
        else
          PaneSection(
            title: 'Developer switches',
            description: const Text('The phone\'s own developer settings, changed for every app.'),
            children: [
              SettingRow(
                key: const ValueKey('frames.animation'),
                label: 'Animation speed',
                layout: SettingRowLayout.stacked,
                control: GlacierSegmentedControl(
                  size: GlacierSegmentedSize.sm,
                  fullWidth: true,
                  value: '${phone.animation}',
                  onValueChange: (v) => Phone.animation(double.parse(v)),
                  options: const [
                    GlacierSegmentedOption(value: '0.0', label: Text('Off')),
                    GlacierSegmentedOption(value: '0.5', label: Text('2x fast')),
                    GlacierSegmentedOption(value: '1.0', label: Text('Normal')),
                    GlacierSegmentedOption(value: '5.0', label: Text('5x slow')),
                    GlacierSegmentedOption(value: '10.0', label: Text('10x slow')),
                  ],
                ),
              ),
              SettingRow(
                key: const ValueKey('frames.awake'),
                label: 'Stay awake while charging',
                hint: const Text('The screen never sleeps while the phone is plugged in.'),
                control: GlacierSwitch(checked: phone.awake, ariaLabel: 'Stay awake while charging', onCheckedChange: Phone.awake),
              ),
            ],
          ),
        PaneSection(
          title: 'More',
          children: [
            SettingRow(
              key: const ValueKey('frames.diagnostics'),
              label: 'Diagnostics',
              hint: const Text('What Frames can read on this phone, and what it did lately.'),
              onPress: onDiagnostics ?? () {},
              disabled: onDiagnostics == null,
            ),
          ],
        ),
      ],
    );
  }
}
