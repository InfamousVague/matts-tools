import 'package:flutter/widgets.dart';

import '../art/app_icon.dart';
import '../art/lucide.g.dart';
import '../art/tool_icons.dart';
import '../phone.dart';
import '../shell/app_shell.dart';
import '../tools.dart';
import 'kit/settings_kit.dart';
import 'panes/about_pane.dart';
import 'panes/amphetamine_pane.dart';
import 'panes/dock_pane.dart';
import 'panes/frames_pane.dart';
import 'panes/toasty_pane.dart';
import 'panes/xray_pane.dart';
import 'settings_screen.dart';

/// Every page of Settings by its id: a page for each tool, the page of apps Amphetamine's opens, and About.
const Set<String> settingsIds = {'dock', 'amphetamine', 'amphetamine-apps', 'frames', 'toasty', 'xray', 'about'};

/// The dock's row in the list: whether it shows, and how.
String dockSummary(DockState dock) => !dock.on ? 'Off' : dock.beside ? 'On · beside the camera' : 'On · floating';

/// Amphetamine's: a session and how long, or asleep.
String awakeSummary(AwakeState awake) => awake.on ? 'Awake for ${spanOf(awake.forMs)}' : 'Asleep';

/// Frames': measuring or not, and the scenario its runs are filed under.
String framesSummary(PhoneState? phone) => phone == null
    ? ''
    : [phone.running ? 'Measuring' : 'Off', if (phone.scenario.isNotEmpty) phone.scenario].join(' · ');

/// X-ray's: whether its switch in Accessibility is on.
String xraySummary(Granted granted) => granted.xray ? 'Switched on' : 'Switch is off';

/// The sections of this app's Settings (Ghost.md's lib/settings/settings_sections.dart is its own list): a page for
/// each tool, in the page of tools' order, and About in a card of its own. [tools] and [phone] are the phone's side
/// as last read; with neither yet, a page says so.
List<SettingsSection> toolsSections({required ToolsState? tools, required PhoneState? phone, required void Function(String place) go}) {
  const reading = PaneBody(children: [PaneSection(children: [SettingRow(label: 'Reading the phone…')])]);
  return [
    SettingsSection(
      id: 'dock',
      label: 'Dock',
      icon: const AppIcon(ToolIcons.dock, size: 18),
      group: 0,
      summary: tools == null ? null : dockSummary(tools.dock),
      content: (_) => tools == null ? reading : DockPane(dock: tools.dock),
    ),
    SettingsSection(
      id: 'amphetamine',
      label: 'Amphetamine',
      icon: const AppIcon(ToolIcons.pill, size: 18),
      group: 0,
      summary: tools == null ? null : awakeSummary(tools.awake),
      content: (_) => tools == null ? reading : AmphetaminePane(awake: tools.awake, onApps: () => go(settingsPlaceOf('amphetamine-apps'))),
    ),
    SettingsSection(
      id: 'amphetamine-apps',
      label: 'Apps that keep it awake',
      icon: const AppIcon(ToolIcons.pill, size: 18),
      group: 0,
      listed: false,
      parent: 'amphetamine',
      content: (_) => tools == null ? reading : AmphetamineAppsPane(awake: tools.awake),
    ),
    SettingsSection(
      id: 'frames',
      label: 'Frames',
      icon: const AppIcon(ToolIcons.frames, size: 18),
      group: 0,
      summary: framesSummary(phone),
      content: (_) => phone == null ? reading : FramesPane(phone: phone, onDiagnostics: () => go(diagnosticsPlace)),
    ),
    SettingsSection(
      id: 'toasty',
      label: 'Toasty',
      icon: const AppIcon(Lucide.flame, size: 18),
      group: 0,
      summary: tools?.heat,
      content: (_) => tools == null ? reading : ToastyPane(tools: tools),
    ),
    SettingsSection(
      id: 'xray',
      label: 'X-ray',
      icon: const AppIcon(ToolIcons.layers, size: 18),
      group: 0,
      summary: tools == null ? null : xraySummary(tools.granted),
      content: (_) => tools == null ? reading : XrayPane(tools: tools),
    ),
    SettingsSection(
      id: 'about',
      label: 'About',
      icon: const AppIcon(Lucide.info, size: 18),
      group: 1,
      summary: tools == null ? null : [tools.build.version, if (tools.granted.missing > 0) '${tools.granted.missing} permissions to go'].join(' · '),
      content: (_) => tools == null ? reading : AboutPane(tools: tools),
    ),
  ];
}

/// Settings, as the shell draws it: the sections above over what the phone's side last said, read again as it
/// changes. Frames' own state ([Phone]) is asked for here while Settings is up, since Frames' first page, which
/// otherwise asks, may never have been opened.
class ToolsSettings extends StatefulWidget {
  const ToolsSettings({super.key, required this.activeId, this.split});
  final String? activeId;
  final bool? split;

  @override
  State<ToolsSettings> createState() => _ToolsSettingsState();
}

class _ToolsSettingsState extends State<ToolsSettings> {
  @override
  void initState() {
    super.initState();
    Phone.refresh().then((_) {}, onError: (Object _) {});
  }

  @override
  Widget build(BuildContext context) {
    final shell = AppShell.of(context);
    return ListenableBuilder(
      listenable: Listenable.merge([Tools.state, Phone.state]),
      builder: (context, _) => SettingsScreen(
        sections: toolsSections(tools: Tools.state.value, phone: Phone.state.value, go: shell.go),
        activeId: widget.activeId,
        onOpen: (id) => shell.go(settingsPlaceOf(id)),
        split: widget.split,
      ),
    );
  }
}
