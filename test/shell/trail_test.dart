import 'package:flutter_test/flutter_test.dart';
import 'package:frames/shell/trail.dart';
import 'package:frames/shell/visited.dart';

void main() {
  test('a trail starts on the page of tools with nowhere to go', () {
    final walk = TrailController();
    expect(walk.trail.place, homePlace);
    expect(walk.canBack, isFalse);
    expect(walk.canOn, isFalse);
  });

  test('back and forward walk where he has been, and somewhere new forgets what was ahead', () {
    final walk = TrailController();
    walk.arrive('frames');
    walk.arrive('settings');
    expect(walk.back(), 'frames');
    expect(walk.canOn, isTrue);
    expect(walk.on(), 'settings');
    expect(walk.back(), 'frames');
    walk.arrive('toasty');
    expect(walk.canOn, isFalse);
    expect(walk.trail.places, [homePlace, 'frames', 'toasty']);
  });

  test('arriving where the trail stands changes nothing', () {
    final walk = TrailController();
    walk.arrive('frames');
    walk.arrive('frames');
    expect(walk.trail.places, [homePlace, 'frames']);
  });

  test('a step skips a place that is no longer there', () {
    final gone = <Place>{};
    final walk = TrailController(there: (place) => !gone.contains(place));
    walk.arrive('frames');
    walk.arrive('frames/run:1');
    walk.arrive('settings');
    gone.add('frames/run:1');
    expect(walk.back(), 'frames');
    expect(walk.on(), 'settings');
  });

  test('no more places are kept than anyone steps through by hand', () {
    final walk = TrailController();
    for (var i = 0; i < mostPlaces + 10; i++) {
      walk.arrive('frames/run:$i');
    }
    expect(walk.trail.places.length, mostPlaces);
  });
}
