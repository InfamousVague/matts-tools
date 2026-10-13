import 'package:flutter/foundation.dart';

import 'visited.dart';

/// Where he has been, and the arrows that walk it: Ghost.md's lib/shell/trail.dart (the web app's shell/useTrail.ts),
/// drawn in the app's bar.
///
/// The trail records arriving somewhere rather than every way of getting there. The arrows only say where to go
/// ([back], [on]); the shell does the going, as it does for every other way of moving. A step is not itself recorded
/// as somewhere new: it moves the trail to where it lands before the screen gets there, and arriving where the trail
/// already stands changes nothing.
///
/// Differs from Ghost.md: there a place was stepped over when its note was no longer among the live ids; here the
/// shell says whether a place is still there ([there]), which a page of the app always is and a page of one thing
/// (an app's runs, a run) is while the shell still holds it.
class TrailController extends ChangeNotifier {
  TrailController({bool Function(Place place)? there}) : _there = there;

  Trail _trail = Trail.first;
  final bool Function(Place place)? _there;

  /// The trail itself, for whatever must follow it changing.
  Trail get trail => _trail;

  /// Where the screen is now, or null for a screen that is no place.
  void arrive(Place? place) {
    if (place == null) return;
    final next = _trail.went(place);
    if (identical(next, _trail)) return;
    _trail = next;
    notifyListeners();
  }

  /// A place worth landing on.
  bool stillThere(Place spot) => _there?.call(spot) ?? true;

  bool get canBack => _trail.canGoBack(stillThere);
  bool get canOn => _trail.canGoOn(stillThere);

  Place? _step(Trail? next) {
    final spot = next?.place;
    if (next == null || spot == null) return null;
    _trail = next;
    notifyListeners();
    return spot;
  }

  /// One step back: answers the place to go to, or null when there is nowhere.
  Place? back() => _step(_trail.backFrom(stillThere));

  /// One step forward again: answers the place to go to, or null when there is nowhere.
  Place? on() => _step(_trail.onFrom(stillThere));
}
