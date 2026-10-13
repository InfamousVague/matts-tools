import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

import 'bar_icon.dart';
import 'bar_parts.dart';

/// The app's bar, over every page: home, back and forward through where he has been, the page's own controls, and
/// the Settings cog at the far end. It is Ghost.md's top bar drawn the Classic way (lib/notes/note_tabs.dart's first
/// row in lib/shell/app_tab_bar.dart's box, over lib/shell/header_pane.dart's glass), made of the same pieces
/// (bar_parts.dart): rings `--app-bar-ring` across, muted until pressed, a ring round the one that is the page, dead
/// at 30% with nowhere to go so the row never changes width, and the header's glass under them from the top of the
/// phone, the page scrolling beneath.
///
/// What Ghost's bar has that this one does not: the sidebar's button, the organizations' people, the bell, the
/// aside's toggle and the row of tabs. There is no sidebar, no organization, no notification and no tab in this app.
///
/// Put it in a [Stack] over the page, which pads its own top by `--app-safe-top`.
class TopBar extends StatelessWidget {
  const TopBar({
    super.key,
    required this.atHome,
    required this.onHome,
    required this.canGoBack,
    required this.onGoBack,
    required this.canGoOn,
    required this.onGoOn,
    required this.inSettings,
    required this.onSettings,
    this.tools,
  });

  final bool atHome;
  final VoidCallback onHome;

  /// Back and forward through where he has been.
  final bool canGoBack, canGoOn;
  final VoidCallback onGoBack, onGoOn;

  /// Whether the page is one of Settings': the cog then wears its ring.
  final bool inSettings;
  final VoidCallback onSettings;

  /// The page's own controls, pushed to the far end before the cog.
  final Widget? tools;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    // A narrow phone: the rings keep their size, the air between them gives (NoteTabs.module.css).
    final gap = MediaQuery.sizeOf(context).width <= 440 ? t.space(1) : t.space(2);
    final tools = this.tools;
    final row = ToolRow(
      gap: gap,
      endInset: t.len('app-bar-end'),
      before: [
        BarRing(glyph: BarGlyph.house, iconSize: 20, label: 'Home', onPressed: onHome, on: atHome, current: atHome),
        // Where he has been: the same two arrows a browser has, in the same place.
        BarRing(glyph: BarGlyph.arrowLeft, iconSize: 19, label: 'Back to where you were', onPressed: canGoBack ? onGoBack : null),
        BarRing(glyph: BarGlyph.arrowRight, iconSize: 19, label: 'Forward again', onPressed: canGoOn ? onGoOn : null),
      ],
      slot: tools == null ? const SizedBox.shrink() : SingleChildScrollView(scrollDirection: Axis.horizontal, reverse: true, child: tools),
      after: [BarRing(glyph: BarGlyph.settings, iconSize: 19, label: 'Settings', onPressed: onSettings, on: inSettings, current: inSettings)],
    );
    return Positioned(
      top: 0,
      left: 0,
      right: 0,
      // `.app-headerPane`: the glass from the top of the phone, the notch and the bar.
      child: BarGlass(
        child: Padding(
          padding: EdgeInsets.only(top: t.len('app-inset-top')),
          // `.app-tabBar`: `--app-tabs` tall, padded by `--app-bar-top` above and `--app-tab-bottom` below.
          child: SizedBox(
            height: t.len('app-tabs'),
            child: Padding(
              padding: EdgeInsets.only(top: t.len('app-bar-top'), bottom: t.len('app-tab-bottom'), left: t.len('app-bar-start')),
              child: Align(alignment: Alignment.topLeft, child: SizedBox(height: t.len('app-bar-ring'), child: row)),
            ),
          ),
        ),
      ),
    );
  }
}
