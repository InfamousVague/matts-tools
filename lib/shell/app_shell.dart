import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

import '../home.dart';
import '../pages.dart';
import '../settings/settings_sections.dart';
import '../tools.dart';
import '../tools_pages.dart';
import 'top_bar.dart';
import 'trail.dart';
import 'visited.dart';

/// The pages of the app that are always there, by their place. A page of one thing (an app's runs, a run, two apps
/// side by side) is a place of its own for as long as the shell holds it ([AppShellState.show]).
const Place framesPlace = 'frames';
const Place diagnosticsPlace = 'frames/diagnostics';
const Place amphetaminePlace = 'amphetamine';
const Place toastyPlace = 'toasty';
const Place settingsPlace = 'settings';

/// A page of Settings by its section: `settings/dock`.
Place settingsPlaceOf(String id) => '$settingsPlace/$id';

/// The section a place of Settings is, or null for the list and for any other place.
String? settingsIdOf(Place place) => place.startsWith('$settingsPlace/') ? place.substring(settingsPlace.length + 1) : null;

bool _fixed(Place place) =>
    place == homePlace || place == framesPlace || place == diagnosticsPlace || place == amphetaminePlace || place == toastyPlace || place == settingsPlace || settingsIds.contains(settingsIdOf(place));

/// The app's one frame: whichever page is the place, under the bar ([TopBar]), with the trail of where he has been
/// ([TrailController]) that the bar's arrows and Android's back walk.
///
/// Every way of moving goes through [go] (or [show], for a page of one thing), so the trail has every page of the
/// app in it: the page of tools, Frames' pages, Amphetamine, Toasty, and each page of Settings. Android's back does
/// what the back arrow does, and with nowhere to go back to it leaves the app, as it would from any first page.
class AppShell extends StatefulWidget {
  const AppShell({super.key, this.first, this.split});

  /// The page to open at, where it is not the page of tools: the app is then one step in, with the page of tools
  /// behind it.
  final Place? first;

  /// Whether Settings is a split view; null asks the window.
  final bool? split;

  static AppShellState of(BuildContext context) => context.findAncestorStateOfType<AppShellState>()!;
  static AppShellState? maybeOf(BuildContext context) => context.findAncestorStateOfType<AppShellState>();

  @override
  State<AppShell> createState() => AppShellState();
}

class AppShellState extends State<AppShell> with WidgetsBindingObserver {
  /// The pages of one thing that can still be gone back to.
  final Map<Place, Widget> _held = {};
  late final TrailController _walk = TrailController(there: (place) => _fixed(place) || _held.containsKey(place));
  Timer? _timer;

  /// Frames' first page is kept once it has been opened: it holds which of its lists is showing and what is ticked,
  /// and keeps asking the phone what it reads, as it did under the pages opened from it.
  bool _framesOpened = false;

  Place get place => _walk.trail.place ?? homePlace;
  bool get canBack => _walk.canBack;
  bool get canOn => _walk.canOn;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _walk.addListener(_moved);
    final first = widget.first;
    if (first != null && _fixed(first)) _walk.arrive(first);
    _look();
    // The tools' settings as they stand, asked again each second: a tile, the dock or a service may change one.
    _timer = Timer.periodic(const Duration(seconds: 1), (_) => _look());
    Tools.onOpen(open);
    unawaited(Tools.launch().then((page) {
      if (page != null && mounted) open(page);
    }, onError: (Object _) {}));
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _timer?.cancel();
    _walk.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _look();
  }

  Future<void> _look() async {
    try {
      await Tools.refresh();
    } catch (_) {
      // The phone's side is not up yet: the next second asks again.
    }
  }

  void _moved() => setState(() {
        if (place == framesPlace) _framesOpened = true;
      });

  /// Goes to one of the app's own pages.
  void go(Place to) {
    if (_fixed(to)) _walk.arrive(to);
  }

  /// Goes to a page of one thing, kept under [to] so the arrows can come back to it.
  void show(Place to, Widget page) {
    _held[to] = page;
    _walk.arrive(to);
  }

  /// A page asked for from outside the app's own pages: a tile held down, a notification, a tap on the dock.
  void open(String page) => go(page);

  /// One step back through where he has been, and forward again.
  void back() => _walk.back();
  void on() => _walk.on();

  /// The thing this page showed is gone (deleted): the page is let go, and the place before it shown.
  void leave() {
    final was = place;
    _walk.back();
    setState(() => _held.remove(was));
  }

  Widget _page(Place place) {
    final held = _held[place];
    if (held != null) return KeyedSubtree(key: ValueKey(place), child: held);
    if (place == homePlace) return const ToolsPage();
    if (place == diagnosticsPlace) return const DiagScreen();
    if (place == amphetaminePlace) return const AmphetaminePage();
    if (place == toastyPlace) return const ToastyPage();
    if (place == settingsPlace || settingsIdOf(place) != null) return ToolsSettings(activeId: settingsIdOf(place), split: widget.split);
    return const SizedBox.shrink();
  }

  @override
  Widget build(BuildContext context) {
    final here = place;
    return PopScope(
      // Android's back: one step back through the trail; at the first page it is the system's, and leaves the app.
      canPop: !canBack,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) back();
      },
      child: ColoredBox(
        color: context.tokens.color('glacier-bg'),
        child: Stack(
          fit: StackFit.expand,
          children: [
            if (_framesOpened) Offstage(offstage: here != framesPlace, child: TickerMode(enabled: here == framesPlace, child: const Home())),
            if (here != framesPlace) Positioned.fill(child: _page(here)),
            TopBar(
              atHome: here == homePlace,
              onHome: () => go(homePlace),
              canGoBack: canBack,
              onGoBack: back,
              canGoOn: canOn,
              onGoOn: on,
              inSettings: here == settingsPlace || settingsIdOf(here) != null,
              onSettings: () => go(_settingsFor(here)),
            ),
          ],
        ),
      ),
    );
  }

  /// Where the cog goes from [here]: a tool's own page of Settings from that tool, else the list.
  Place _settingsFor(Place here) {
    if (here == amphetaminePlace) return settingsPlaceOf('amphetamine');
    if (here == toastyPlace) return settingsPlaceOf('toasty');
    if (here.startsWith(framesPlace)) return settingsPlaceOf('frames');
    return settingsPlace;
  }
}
