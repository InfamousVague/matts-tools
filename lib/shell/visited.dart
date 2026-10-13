import 'package:flutter/foundation.dart';

// Where a person has been, so the arrows in the app's bar can go back and forward through it. Copied from Ghost.md
// (lib/shell/visited.dart, which is the web app's notes/visited.ts); the [Trail] below is Ghost's, word for word.
// What is this app's own: the places. Ghost's were its home page, its grid and a note by id; here a place is a page
// of the app by its name (shell/places.dart), and a page that shows one thing among many (an app's runs, a run, two
// apps side by side) is a place for as long as the shell still holds it.
//
// A trail of places, and where along it we are now. It behaves the way a browser's arrows do: going somewhere new
// after going back forgets what was ahead. A page's thing may be deleted, so a step skips any place that is no
// longer there rather than landing on nothing.

/// A place: a page of the app by its name, `'home'`, `'frames'`, `'settings/dock'`.
typedef Place = String;

/// The page of tools.
const Place homePlace = 'home';

/// As many places as anyone will step through by hand (`MOST_PLACES`); older ones fall off the start.
const int mostPlaces = 30;

/// A trail of places, and where along it we are now (`Trail`, notes/visited.ts).
///
/// Differs from the web: `placeAt(trail)` is [place], `went(trail, place)` is [went], and `backFrom`, `onFrom`,
/// `canGoBack` and `canGoOn` are methods, so none of them stands beside notes/tabDrag.ts's `placeAt` as a second
/// function of that name.
@immutable
class Trail {
  const Trail(this.places, this.at);

  /// `FIRST`: on the page of tools, with nowhere to go.
  static const Trail first = Trail([homePlace], 0);

  final List<Place> places;

  /// Where along [places] we are now.
  final int at;

  /// Where the trail stands, or null for an empty one.
  Place? get place => at >= 0 && at < places.length ? places[at] : null;

  /// Somewhere new: it goes after where we are, and anything that was ahead is forgotten. Arriving where you already
  /// are changes nothing, and answers this same trail.
  Trail went(Place to) {
    if (place == to) return this;
    final next = [...places.take(at + 1), to];
    final kept = next.length > mostPlaces ? next.sublist(next.length - mostPlaces) : next;
    return Trail(List.unmodifiable(kept), kept.length - 1);
  }

  /// The nearest place before this one that is still there, or null when there is none.
  Trail? backFrom(bool Function(Place place) there) {
    for (var i = at - 1; i >= 0; i--) {
      if (i < places.length && there(places[i])) return Trail(places, i);
    }
    return null;
  }

  /// The nearest place after this one that is still there, or null when there is none.
  Trail? onFrom(bool Function(Place place) there) {
    for (var i = at + 1; i < places.length; i++) {
      if (there(places[i])) return Trail(places, i);
    }
    return null;
  }

  /// Whether the arrows can do anything, for drawing them live or dead.
  bool canGoBack(bool Function(Place place) there) => backFrom(there) != null;
  bool canGoOn(bool Function(Place place) there) => onFrom(there) != null;

  @override
  bool operator ==(Object other) => other is Trail && other.at == at && listEquals(other.places, places);

  @override
  int get hashCode => Object.hash(at, Object.hashAll(places));

  @override
  String toString() => 'Trail($places, at $at)';
}
