import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

import '../../tools.dart';
import '../../xray/xray.dart';
import '../kit/settings_kit.dart';

/// X-ray's settings: its own switch in Accessibility, the computer's permission for the compositor's layers, and
/// what X-ray can read of each kind of app and what it cannot. X-ray has no settings of its own to change: it reads
/// only while it is started from its tile.
class XrayPane extends StatelessWidget {
  const XrayPane({super.key, required this.tools});
  final ToolsState tools;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final granted = tools.granted;
    final package = tools.build.package;
    return PaneBody(
      children: [
        PaneSection(
          title: 'Switch',
          description: const Text('X-ray reads the screen through an accessibility service of its own, apart from the dock\'s. Switched on it still reads nothing: it reads only while you have started it from its tile, over the app in front.'),
          children: [
            SettingRow(
              key: const ValueKey('xray.switch'),
              label: 'X-ray\'s switch',
              hint: const Text('In Accessibility, under Installed apps (Downloaded apps on some phones), switch on Matt\'s Tools: X-ray.'),
              value: granted.xray ? const Text('On') : null,
              control: granted.xray ? null : RowAction(onPress: Xray.ask, child: const Text('Open Accessibility')),
              layout: granted.xray ? SettingRowLayout.trailing : SettingRowLayout.stacked,
            ),
          ],
        ),
        PaneSection(
          title: 'Compositor layers',
          description: const Text('Android lets an app read the surfaces it composes for another app only after a computer says so, once. Without it X-ray reads the tree and says why the layers are not there. With the phone plugged in or on wireless debugging:'),
          children: [
            if (granted.dump)
              const SettingRow(key: ValueKey('xray.dump'), label: 'Frame timings, from a computer', hint: Text('This is given, and Frames\' own readings need it too.'), value: Text('Given'))
            else
              SettingRowBox(
                key: const ValueKey('xray.dump'),
                child: Text('adb shell pm grant $package android.permission.DUMP', style: t.text('sm', mono: true, color: t.color('glacier-text'))),
              ),
          ],
        ),
        PaneSection(
          title: 'What X-ray can read',
          description: const Text('Each kind of app gives its screen to accessibility in its own way. This is what each gives, and what it does not.'),
          children: [
            for (final k in canRead)
              SettingRow(
                key: ValueKey('xray.reads.${k.kind}'),
                label: k.kind,
                layout: SettingRowLayout.stacked,
                hint: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text('Reads: ${k.reads}'),
                  SizedBox(height: t.space(2)),
                  Text('Cannot read: ${k.cannot}'),
                ]),
              ),
            const SettingRow(key: ValueKey('xray.hidden'), label: 'Parts an app hides', layout: SettingRowLayout.stacked, hint: Text(hiddenViewsNote)),
            const SettingRow(key: ValueKey('xray.navigation'), label: 'Flutter\'s accessible navigation', layout: SettingRowLayout.stacked, hint: Text(flutterNavigationNote)),
          ],
        ),
        const SettingsFootnote(child: Text('Nothing X-ray reads leaves the phone or is written down. A reading is held in memory until the next one, until you close X-ray or forget it, or until the screen goes off.')),
      ],
    );
  }
}
