import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

/// A whole number chosen along a line, from [min] to [max]: this app's own, since neither Ghost.md's settings kit
/// nor Glacier has a slider. A track in the segmented control's ground, filled in ink up to a thumb a finger can
/// take hold of. It says each value as it is dragged over ([onChanged]) and the one it is let go on ([onChangeEnd]).
class LevelSlider extends StatefulWidget {
  const LevelSlider({super.key, required this.value, required this.min, required this.max, required this.label, this.onChanged, required this.onChangeEnd});

  final int value, min, max;

  /// What it sets, said aloud.
  final String label;
  final ValueChanged<int>? onChanged;
  final ValueChanged<int> onChangeEnd;

  @override
  State<LevelSlider> createState() => _LevelSliderState();
}

class _LevelSliderState extends State<LevelSlider> {
  int? _dragged;

  int _at(double x, double wide) => (widget.min + (x / wide).clamp(0.0, 1.0) * (widget.max - widget.min)).round();

  void _move(double x, double wide) {
    final next = _at(x, wide);
    if (next == _dragged) return;
    setState(() => _dragged = next);
    widget.onChanged?.call(next);
  }

  void _end() {
    final value = _dragged;
    if (value == null) return;
    widget.onChangeEnd(value);
    setState(() => _dragged = null);
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final value = (_dragged ?? widget.value).clamp(widget.min, widget.max);
    const thumb = 24.0, track = 6.0;
    return Semantics(
      slider: true,
      label: widget.label,
      value: '$value',
      increasedValue: value < widget.max ? '${value + 1}' : null,
      decreasedValue: value > widget.min ? '${value - 1}' : null,
      onIncrease: value < widget.max ? () => widget.onChangeEnd(value + 1) : null,
      onDecrease: value > widget.min ? () => widget.onChangeEnd(value - 1) : null,
      child: LayoutBuilder(
        builder: (context, box) {
          final wide = box.maxWidth - thumb;
          final x = wide * (value - widget.min) / (widget.max - widget.min);
          return GestureDetector(
            behavior: HitTestBehavior.opaque,
            onHorizontalDragStart: (d) => _move(d.localPosition.dx - thumb / 2, wide),
            onHorizontalDragUpdate: (d) => _move(d.localPosition.dx - thumb / 2, wide),
            onHorizontalDragEnd: (_) => _end(),
            onHorizontalDragCancel: _end,
            onTapUp: (d) {
              _move(d.localPosition.dx - thumb / 2, wide);
              _end();
            },
            child: SizedBox(
              height: 40,
              child: Stack(
                alignment: Alignment.centerLeft,
                children: [
                  Positioned(
                    left: thumb / 2,
                    right: thumb / 2,
                    child: Container(height: track, decoration: BoxDecoration(color: t.color('glacier-segment-track'), borderRadius: BorderRadius.circular(track))),
                  ),
                  Positioned(
                    left: thumb / 2,
                    width: x,
                    child: Container(height: track, decoration: BoxDecoration(color: t.color('glacier-text'), borderRadius: BorderRadius.circular(track))),
                  ),
                  Positioned(
                    left: x,
                    child: Container(
                      width: thumb,
                      height: thumb,
                      decoration: BoxDecoration(color: t.color('glacier-text'), shape: BoxShape.circle, border: Border.all(color: t.color('glacier-bg'), width: 2)),
                    ),
                  ),
                ],
              ),
            ),
          );
        },
      ),
    );
  }
}
