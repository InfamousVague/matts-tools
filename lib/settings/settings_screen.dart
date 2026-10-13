import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

import 'kit/settings_icons.dart';
import 'kit/settings_kit.dart';
import 'kit/settings_type.dart';
import 'section_steps.dart';
import 'settings_search.dart';

// Copied from Ghost.md's lib/settings/settings_screen.dart (the web app's src/app/settings/SettingsScreen.tsx with
// the shell's rules of settings.css) and tailored for this app. [SettingsSection], [settingsHueOf], the list's
// cards and rows ([_Group], [_SectionRow]), the scroller and a page's own layout are Ghost's as they stood. What is
// this app's own is said on [SettingsScreen].

/// One section of a settings screen (`SettingsSection`, SettingsScreen.tsx): a row on the list, or a sub-page off
/// it, and the page it opens.
class SettingsSection implements SearchableSection, SectionPlace {
  const SettingsSection({
    required this.id,
    required this.label,
    required this.icon,
    required this.content,
    this.summary,
    required this.group,
    this.hue,
    this.words,
    this.settings = const [],
    this.listed = true,
    this.parent,
  });

  @override
  final String id;
  @override
  final String label;

  /// Its glyph, drawn in the section's colour on its chip.
  final Widget icon;

  /// Its page: usually a [PaneBody] of the kit's cards.
  final WidgetBuilder content;

  /// The row's second line: the section's state, read live.
  @override
  final String? summary;

  /// Rows with the same group share one card.
  @override
  final int group;

  /// Its colour, when it brings its own; otherwise the one kept for its id, or grey.
  final String? hue;

  /// Other words the search finds the section by.
  @override
  final String? words;

  /// The settings on its page, by the names the page gives them.
  @override
  final List<SettingsFindable> settings;

  /// False keeps it off the list: a sub-page, opened from a row on its parent's page.
  @override
  final bool listed;

  /// The section a sub-page steps back to, and whose row is current in the split view while it shows.
  @override
  final String? parent;
}

/// Each section's colour (`HUES`): its row's chip in the list, and the icons on its own page. Names, not values:
/// settings.css draws each (settings_css.dart), a shade deeper on the light page than on the dark. This app's
/// sections, in Ghost's colours.
const Map<String, String> _hues = {
  'dock': 'graphite',
  'amphetamine': 'coral',
  'amphetamine-apps': 'coral',
  'frames': 'blue',
  'toasty': 'red',
  'about': 'grey',
};

/// A section's colour (`hueOf`): its own, or its id's, or grey for a section added later without one.
String settingsHueOf(SettingsSection section) => section.hue ?? _hues[section.id] ?? 'grey';

/// The list's own column and a page beside it no narrower than a small phone (`SIDEBAR_WIDE`), in a window that is
/// not a strip (`SIDEBAR_TALL`): Ghost.md's lib/shell/wide_screen.dart `splitFits`, for a phone, which has no mouse.
const double sidebarWide = 660;
const double sidebarTall = 600;

/// Whether Settings is a split view in this window: a tablet, or a folding phone opened out.
bool settingsSplitFits(BuildContext context) {
  final window = MediaQuery.sizeOf(context);
  return window.width >= sidebarWide && window.height >= sidebarTall;
}

/// The settings surface: on a phone the list of sections, and a section's page in its place once one is opened; on
/// a window with room, a split view with the sections down the left and a page beside them.
///
/// What differs from Ghost.md's:
/// - Which page shows is not the screen's own to keep. It is the app's place ([activeId], from the shell's trail),
///   and a row asks the shell to go there ([onOpen]). So the bar's arrows and Android's back walk the settings'
///   pages with every other page of the app, and Ghost's own "← Settings" head, its swipe back and forward and its
///   back handler are not here: the bar over every page is the way back.
/// - It is a page under the app's bar, not a sheet over the app: it is always open, and pads its top by the bar
///   (`--app-safe-top`).
/// - There is no search field (five sections), and a page does not slide in.
class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key, required this.sections, required this.activeId, required this.onOpen, this.title = 'Settings', this.split});

  final List<SettingsSection> sections;

  /// The page showing, or null over the list.
  final String? activeId;

  /// A row was pressed: go to that section's page.
  final void Function(String id) onOpen;

  /// The screen's name, over the list.
  final String title;

  /// Whether to draw the split view; null asks the window ([settingsSplitFits]).
  final bool? split;

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  final SettingsFinder _finder = SettingsFinder();
  final ScrollController _listScroll = ScrollController();
  bool _split = false;

  SettingsSection? _sectionOf(String? id) {
    if (id == null) return null;
    for (final section in widget.sections) {
      if (section.id == id) return section;
    }
    return null;
  }

  @override
  void dispose() {
    _listScroll.dispose();
    super.dispose();
  }

  TextStyle _body(Tokens t) => pageText(t, t.lenOf('1rem'));

  List<Widget> _list(Tokens t, String? current) {
    var row = 0;
    return [
      for (final cluster in clustersOf(widget.sections))
        _Group(
          key: ValueKey('cluster ${cluster.first.id}'),
          rows: [
            for (final section in cluster)
              _SectionRow(
                key: ValueKey('row ${section.id}'),
                section: section,
                label: section.label,
                summary: section.summary,
                current: section.id == current,
                index: row++,
                split: _split,
                onPress: () => widget.onOpen(section.id),
              ),
          ],
        ),
    ];
  }

  Widget _scroller(Tokens t, {required ScrollController controller, required EdgeInsets padding, required List<Widget> children, Key? key}) {
    final gap = t.space(4);
    return SingleChildScrollView(
      key: key,
      controller: controller,
      padding: padding,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          for (var i = 0; i < children.length; i++) ...[if (i > 0) SizedBox(height: gap), children[i]],
        ],
      ),
    );
  }

  /// `.settingsScreen__display`: a title rather than a display word, so the rows start near the top.
  Widget _display(Tokens t, String words) {
    final titleSize = t.len('app-title');
    return Padding(
      padding: EdgeInsets.only(bottom: t.space(3) + t.space(4)),
      child: Semantics(
        header: true,
        child: ExactLines.of(
          Text(
            words,
            key: const ValueKey('settings-display'),
            style: pageText(t, titleSize, weight: 'bold', height: 1.15, tracking: t.len('app-tracking-title', em: titleSize)),
          ),
          dpr: t.css.lineDpr,
        ),
      ),
    );
  }

  /// A section's page: its name as the page's word, then what it holds, in its colour.
  Widget _pane(Tokens t, SettingsSection shown, {required EdgeInsets padding}) {
    return GlacierScope(
      key: ValueKey('pane ${shown.id}'),
      attrs: {'hue': settingsHueOf(shown)},
      child: SettingsPaneScope(
        finder: _finder,
        child: Builder(
          builder: (context) => SingleChildScrollView(
            key: ValueKey('scroll ${shown.id}'),
            padding: padding,
            child: RepaintBoundary(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [_display(t, shown.label), shown.content(context)],
              ),
            ),
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    _split = widget.split ?? settingsSplitFits(context);
    return GlacierScope(
      classes: const {'settingsScreen'},
      attrs: {if (_split) 'layout': 'split'},
      child: Builder(builder: _screen),
    );
  }

  Widget _screen(BuildContext context) {
    final t = context.tokens;
    final split = _split;
    final sections = widget.sections;
    final active = _sectionOf(widget.activeId);
    final top = t.len('app-safe-top') + t.space(2);
    final bottom = t.lenOf('calc(var(--app-safe-bottom) + var(--glacier-space-16))');
    final gutter = EdgeInsets.only(left: t.len('app-gutter-start'), right: t.len('app-gutter-end'));

    final Widget body;
    if (split) {
      final shown = active ?? listedOf(sections).firstOrNull;
      final current = currentRow(sections, shown);
      final side = t.len('settings-side');
      final hairline = t.len('glacier-hairline');
      final columnPad = EdgeInsets.only(left: t.lenOf('calc(var(--app-safe-left) + var(--glacier-space-4))'), right: t.space(4));
      body = Row(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // The left column: the sections, which scroll under the bar.
          Container(
            width: side,
            decoration: BoxDecoration(border: BorderDirectional(end: BorderSide(color: t.color('glacier-border-subtle'), width: hairline))),
            child: Semantics(
              container: true,
              label: '${widget.title} sections',
              child: _scroller(
                t,
                key: const ValueKey('settings-list'),
                controller: _listScroll,
                padding: columnPad.copyWith(top: top, bottom: bottom),
                children: [_display(t, widget.title), ..._list(t, current)],
              ),
            ),
          ),
          if (shown != null)
            Expanded(
              // The page takes the pane's whole width, in the app's gutter.
              child: _pane(t, shown, padding: EdgeInsets.only(left: t.len('app-gutter'), right: t.len('app-gutter-end'), top: top, bottom: bottom)),
            ),
        ],
      );
    } else if (active != null) {
      body = _pane(t, active, padding: gutter.copyWith(top: top, bottom: bottom));
    } else {
      body = Semantics(
        container: true,
        label: '${widget.title} sections',
        child: _scroller(
          t,
          key: const ValueKey('settings-list'),
          controller: _listScroll,
          padding: gutter.copyWith(top: top, bottom: bottom),
          children: [_display(t, widget.title), ..._list(t, null)],
        ),
      );
    }

    return ColoredBox(
      color: t.color('sheet-ground'),
      child: DefaultTextStyle(
        style: _body(t),
        // The page ends where the keyboard begins, so a field on it can be scrolled clear of the keyboard.
        child: Padding(padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom), child: body),
      ),
    );
  }
}

/// `.settingsScreen__cluster` holding its `.settingsScreen__group`: consecutive rows of one group share a card, the
/// iOS-settings shape, with a hairline between one row and the next.
class _Group extends StatelessWidget {
  const _Group({super.key, required this.rows});
  final List<Widget> rows;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final hairline = t.len('glacier-hairline');
    final rule = t.color('app-rule');
    return GlacierScope(
      classes: const {'settingsScreen__group'},
      child: ClipRRect(
        borderRadius: BorderRadius.circular(t.radius('lg')),
        child: ColoredBox(
          color: t.color('app-paper-2'),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            mainAxisSize: MainAxisSize.min,
            children: [
              for (var i = 0; i < rows.length; i++)
                // `.settingsScreen__row + .settingsScreen__row { border-block-start }`
                i == 0 ? rows[i] : DecoratedBox(decoration: BoxDecoration(border: Border(top: BorderSide(color: rule, width: hairline))), child: Padding(padding: EdgeInsets.only(top: hairline), child: rows[i])),
            ],
          ),
        ),
      ),
    );
  }
}

/// `.settingsScreen__row` (`SectionRow`): a row that opens a section, its chip in the section's colour, a label and a
/// line under it, and a chevron on a phone.
class _SectionRow extends StatelessWidget {
  const _SectionRow({
    super.key,
    required this.section,
    required this.label,
    this.summary,
    required this.current,
    required this.index,
    required this.split,
    required this.onPress,
  });

  final SettingsSection section;
  final String label;
  final String? summary;
  final bool current;

  /// Its place in the rows' arrival, one after another.
  final int index;
  final bool split;
  final VoidCallback onPress;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final gap = t.space(3);
    final chip = t.lenOf('1.85rem');
    final hasSummary = summary != null && summary!.isNotEmpty;
    return SettingsRise(
      index: index,
      child: GlacierPressable(
        onPressed: onPress,
        // `aria-current="page"`
        selected: current ? true : null,
        builder: (context, state) => GlacierFocusRing(
          visible: state.focused,
          radius: t.radius('sm'),
          offset: -3,
          child: ColoredBox(
            // The section on show, in the left column: the row's own ground, a shade deeper.
            color: state.pressed
                ? t.color('app-paper-3')
                : current
                    ? t.colorOf('color-mix(in srgb, var(--glacier-text) 7%, transparent)')
                    : const Color(0x00000000),
            child: ConstrainedBox(
              // A row a thumb's height.
              constraints: BoxConstraints(minHeight: t.lenOf('3.5rem')),
              child: Padding(
                padding: EdgeInsets.all(gap),
                child: Row(
                  children: [
                    GlacierScope(
                      attrs: {'hue': settingsHueOf(section)},
                      child: Builder(
                        builder: (context) {
                          final hued = context.tokens;
                          return Container(
                            key: const ValueKey('settings-row-icon'),
                            width: chip,
                            height: chip,
                            decoration: BoxDecoration(color: settingsHueWash(hued), borderRadius: BorderRadius.circular(t.lenOf('0.55rem'))),
                            alignment: Alignment.center,
                            child: ExcludeSemantics(child: IconTheme.merge(data: IconThemeData(color: settingsHue(hued)), child: section.icon)),
                          );
                        },
                      ),
                    ),
                    SizedBox(width: gap),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          ExactLines.of(Text(label, key: const ValueKey('settings-row-label'), style: pageText(t, t.fontSize('md'), weight: 'medium')), normal: true, dpr: t.css.lineDpr),
                          if (hasSummary) ...[
                            SizedBox(height: t.lenOf('0.1rem')),
                            ExactLines.of(
                              Text(
                                summary!,
                                key: const ValueKey('settings-row-summary'),
                                maxLines: 1,
                                softWrap: false,
                                overflow: TextOverflow.ellipsis,
                                style: pageText(t, t.fontSize('sm'), color: t.color('app-ink-3')),
                              ),
                              normal: true,
                              dpr: t.css.lineDpr,
                            ),
                          ],
                        ],
                      ),
                    ),
                    if (!split) ...[SizedBox(width: gap), ExcludeSemantics(child: SettingsIcon(SettingsGlyph.chevronRight, size: 18, color: t.color('app-ink-4')))],
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
