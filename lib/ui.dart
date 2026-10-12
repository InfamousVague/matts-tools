import 'dart:typed_data';

import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

import 'phone.dart';

/// The app's own parts, made of the kit's tokens and its press: what the kit has no component for yet (a card, a
/// button, a row, a chart).

Color ink(BuildContext context, String name) => context.tokens.color('glacier-$name');

class Txt extends StatelessWidget {
  const Txt(this.words, {super.key, this.size = 'md', this.weight = 'regular', this.color = 'text', this.mono = false, this.maxLines, this.align});
  final String words, size, weight, color;
  final bool mono;
  final int? maxLines;
  final TextAlign? align;

  @override
  Widget build(BuildContext context) => Text(
        words,
        maxLines: maxLines,
        overflow: maxLines == null ? null : TextOverflow.ellipsis,
        textAlign: align,
        style: context.tokens.text(size, weight: weight, mono: mono, color: ink(context, color)).copyWith(fontFeatures: const [FontFeature.tabularFigures()]),
      );
}

class Panel extends StatelessWidget {
  const Panel({super.key, required this.child, this.onTap, this.padding, this.color = 'surface-raised'});
  final Widget child;
  final VoidCallback? onTap;
  final EdgeInsets? padding;
  final String color;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    Widget box(bool pressed) => DecoratedBox(
          decoration: BoxDecoration(
            color: pressed ? ink(context, 'surface-sunken') : ink(context, color),
            borderRadius: BorderRadius.circular(t.radius('xl')),
            border: Border.all(color: ink(context, 'border-subtle'), width: 1),
          ),
          child: Padding(padding: padding ?? EdgeInsets.all(t.space(4)), child: child),
        );
    if (onTap == null) return box(false);
    return GlacierPressable(onPressed: onTap, builder: (context, state) => box(state.pressed));
  }
}

enum BtnKind { solid, soft, danger }

class Btn extends StatelessWidget {
  const Btn(this.label, {super.key, required this.onPressed, this.kind = BtnKind.soft, this.wide = true});
  final String label;
  final VoidCallback? onPressed;
  final BtnKind kind;
  final bool wide;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final (bg, hover, fg) = switch (kind) {
      BtnKind.solid => ('accent-solid', 'accent-solid-hover', 'accent-contrast'),
      BtnKind.soft => ('surface-raised', 'surface-sunken', 'text'),
      BtnKind.danger => ('danger-soft', 'danger-soft-hover', 'danger-text'),
    };
    return GlacierPressable(
      onPressed: onPressed,
      builder: (context, state) => Container(
        width: wide ? double.infinity : null,
        alignment: wide ? Alignment.center : null,
        padding: EdgeInsets.symmetric(horizontal: t.space(5), vertical: t.space(3)),
        decoration: BoxDecoration(
          color: ink(context, state.pressed ? hover : bg),
          borderRadius: BorderRadius.circular(t.radius('full')),
          border: kind == BtnKind.soft ? Border.all(color: ink(context, 'border'), width: 1) : null,
        ),
        child: Txt(label, size: 'sm', weight: 'medium', color: onPressed == null ? 'text-disabled' : fg),
      ),
    );
  }
}

class Heading extends StatelessWidget {
  const Heading(this.words, {super.key});
  final String words;
  @override
  Widget build(BuildContext context) => Padding(
        padding: EdgeInsets.only(top: context.tokens.space(6), bottom: context.tokens.space(2), left: context.tokens.space(1)),
        child: Text(words.toUpperCase(), style: context.tokens.text('xs', weight: 'medium', color: ink(context, 'text-subtle')).copyWith(letterSpacing: 0.9)),
      );
}

class Note extends StatelessWidget {
  const Note(this.words, {super.key});
  final String words;
  @override
  Widget build(BuildContext context) => Padding(padding: EdgeInsets.fromLTRB(context.tokens.space(1), context.tokens.space(2), context.tokens.space(1), 0), child: Txt(words, size: 'sm', color: 'text-muted'));
}

/// An app's own icon; a letter on a tile for an app that is gone from the phone.
class AppIcon extends StatelessWidget {
  const AppIcon(this.pkg, {super.key, this.size = 40, this.name = ''});
  final String pkg, name;
  final double size;

  @override
  Widget build(BuildContext context) => SizedBox(
        width: size,
        height: size,
        child: FutureBuilder<Uint8List?>(
          future: Phone.icon(pkg),
          builder: (context, picture) {
            final bytes = picture.data;
            if (bytes != null) return Image.memory(bytes, width: size, height: size, gaplessPlayback: true, filterQuality: FilterQuality.medium);
            return DecoratedBox(
              decoration: BoxDecoration(color: ink(context, 'surface-sunken'), borderRadius: BorderRadius.circular(size * 0.28)),
              child: Center(child: Txt(name.isEmpty ? '?' : name.substring(0, 1).toUpperCase(), weight: 'semibold', color: 'text-muted')),
            );
          },
        ),
      );
}

/// A row of a list: something leading, two lines, something trailing.
class Line extends StatelessWidget {
  const Line({super.key, this.leading, required this.title, this.subtitle, this.trailing, this.onTap, this.under});
  final Widget? leading, trailing, under;
  final String title;
  final String? subtitle;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    return Padding(
      padding: EdgeInsets.only(bottom: t.space(2)),
      child: Panel(
        onTap: onTap,
        padding: EdgeInsets.symmetric(horizontal: t.space(4), vertical: t.space(3)),
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Row(children: [
            if (leading != null) Padding(padding: EdgeInsets.only(right: t.space(3)), child: leading),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Txt(title, weight: 'medium', maxLines: 1),
                if (subtitle != null && subtitle!.isNotEmpty) Txt(subtitle!, size: 'sm', color: 'text-muted', maxLines: 2),
              ]),
            ),
            if (trailing != null) Padding(padding: EdgeInsets.only(left: t.space(3)), child: trailing),
          ]),
          if (under != null) Padding(padding: EdgeInsets.only(top: t.space(3)), child: under),
        ]),
      ),
    );
  }
}

/// The mark that picks a row for a comparison.
class Pick extends StatelessWidget {
  const Pick({super.key, required this.on, required this.onTap, this.letter});
  final bool on;
  final VoidCallback onTap;
  final String? letter;

  @override
  Widget build(BuildContext context) => GlacierPressable(
        onPressed: onTap,
        semanticLabel: on ? 'Picked for comparison' : 'Pick for comparison',
        toggled: on,
        builder: (context, state) => Padding(
          padding: const EdgeInsets.all(6),
          child: Container(
            width: 26,
            height: 26,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: on ? ink(context, 'accent-solid') : null,
              border: Border.all(color: ink(context, on ? 'accent-solid' : 'border-strong'), width: 1.5),
            ),
            child: on ? Txt(letter ?? '', size: 'xs', weight: 'semibold', color: 'accent-contrast') : null,
          ),
        ),
      );
}

/// A page: the title with a way back, and the rest under it, scrolling.
class Screen extends StatelessWidget {
  const Screen({super.key, required this.title, this.subtitle, this.leading, required this.children, this.trailing});
  final String title;
  final String? subtitle;
  final Widget? leading, trailing;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final back = Navigator.of(context).canPop();
    return ColoredBox(
      color: ink(context, 'bg'),
      child: SafeArea(
        child: ListView(
          padding: EdgeInsets.fromLTRB(t.space(4), t.space(4), t.space(4), t.space(12)),
          children: [
            Row(children: [
              if (back)
                GlacierPressable(
                  onPressed: () => Navigator.of(context).maybePop(),
                  semanticLabel: 'Back',
                  builder: (context, state) => Container(
                    width: 40,
                    height: 40,
                    margin: EdgeInsets.only(right: t.space(3)),
                    decoration: BoxDecoration(shape: BoxShape.circle, color: ink(context, state.pressed ? 'surface-sunken' : 'surface-raised'), border: Border.all(color: ink(context, 'border-subtle'))),
                    child: CustomPaint(painter: _Chevron(ink(context, 'text'))),
                  ),
                ),
              if (leading != null) Padding(padding: EdgeInsets.only(right: t.space(3)), child: leading),
              Expanded(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Txt(title, size: back ? 'xl' : '3xl', weight: 'semibold', maxLines: 1),
                  if (subtitle != null) Txt(subtitle!, size: 'sm', color: 'text-muted', maxLines: 1),
                ]),
              ),
              ?trailing,
            ]),
            SizedBox(height: t.space(4)),
            ...children,
          ],
        ),
      ),
    );
  }
}

class _Chevron extends CustomPainter {
  _Chevron(this.color);
  final Color color;
  @override
  void paint(Canvas canvas, Size size) {
    final pen = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round;
    final c = size.center(Offset.zero);
    canvas.drawPath(Path()..moveTo(c.dx + 3, c.dy - 7)..lineTo(c.dx - 4, c.dy)..lineTo(c.dx + 3, c.dy + 7), pen);
  }

  @override
  bool shouldRepaint(_Chevron old) => old.color != color;
}

void open(BuildContext context, Widget page) => Navigator.of(context).push(PageRouteBuilder<void>(
      pageBuilder: (_, _, _) => page,
      transitionDuration: const Duration(milliseconds: 220),
      reverseTransitionDuration: const Duration(milliseconds: 180),
      transitionsBuilder: (_, animation, _, child) => FadeTransition(
        opacity: animation,
        child: SlideTransition(position: Tween(begin: const Offset(0.04, 0), end: Offset.zero).animate(CurvedAnimation(parent: animation, curve: Curves.easeOutCubic)), child: child),
      ),
    ));

void say(BuildContext context, String words) => GlacierToast.of(context).toast(message: Text(words));

GlacierPillTone toneOf(double jankPct) => jankPct < 5 ? GlacierPillTone.success : jankPct < 15 ? GlacierPillTone.warning : GlacierPillTone.danger;

String n1(double v) => v.toStringAsFixed(1);
String n0(double v) => v.toStringAsFixed(0);

// ---- charts ----

/// One number of two things as two bars, one over the other, each as long as its share of the larger.
class PairBars extends StatelessWidget {
  const PairBars({super.key, required this.name, required this.a, required this.b, required this.unit, required this.higherIsBetter, this.digits = 1});
  final String name, unit;
  final double a, b;
  final bool higherIsBetter;
  final int digits;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final most = [a, b, 0.0001].reduce((x, y) => x > y ? x : y);
    final same = (a - b).abs() < 0.05;
    final aWins = !same && (higherIsBetter ? a > b : a < b);
    Widget bar(double v, String color, bool wins) => Padding(
          padding: EdgeInsets.only(top: t.space(1)),
          child: Row(children: [
            Expanded(
              child: Align(
                alignment: Alignment.centerLeft,
                child: FractionallySizedBox(
                  widthFactor: (v / most).clamp(0.015, 1.0),
                  child: Container(height: 10, decoration: BoxDecoration(color: ink(context, color), borderRadius: BorderRadius.circular(5))),
                ),
              ),
            ),
            SizedBox(width: 84, child: Txt('${v.toStringAsFixed(digits)}$unit', size: 'sm', weight: wins ? 'semibold' : 'regular', color: wins ? 'text' : 'text-muted', align: TextAlign.right)),
          ]),
        );
    final delta = b - a;
    return Padding(
      padding: EdgeInsets.only(bottom: t.space(4)),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Row(children: [
          Expanded(child: Txt(name, size: 'sm', weight: 'medium')),
          Txt(same ? 'level' : '${delta > 0 ? '+' : ''}${delta.toStringAsFixed(digits)}$unit', size: 'xs', color: same ? 'text-subtle' : ((higherIsBetter ? delta > 0 : delta < 0) ? 'success-text' : 'danger-text')),
        ]),
        bar(a, 'accent-solid', aWins),
        bar(b, 'warning-solid', !same && !aWins),
      ]),
    );
  }
}

/// Bars standing in groups: one set of values, or two side by side, with a word under each group.
class GroupBars extends StatelessWidget {
  const GroupBars({super.key, required this.labels, required this.a, this.b, this.height = 120, this.suffix = ''});
  final List<String> labels;
  final List<double> a;
  final List<double>? b;
  final double height;
  final String suffix;

  @override
  Widget build(BuildContext context) {
    final most = [...a, ...?b, 0.0001].reduce((x, y) => x > y ? x : y);
    Widget bar(double v, String color) => Expanded(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 2),
            child: Column(mainAxisAlignment: MainAxisAlignment.end, children: [
              Txt('${v >= 100 || v == v.roundToDouble() ? n0(v) : n1(v)}$suffix', size: 'xs', color: 'text-muted', maxLines: 1),
              const SizedBox(height: 3),
              Container(
                height: v <= 0 ? 0 : ((height - 22) * v / most).clamp(2.0, height),
                decoration: BoxDecoration(color: ink(context, color), borderRadius: const BorderRadius.vertical(top: Radius.circular(4))),
              ),
            ]),
          ),
        );
    return Column(children: [
      SizedBox(
        height: height,
        child: Row(crossAxisAlignment: CrossAxisAlignment.end, children: [
          for (var i = 0; i < labels.length; i++)
            Expanded(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 4),
                child: Row(crossAxisAlignment: CrossAxisAlignment.end, children: [bar(a[i], 'accent-solid'), if (b != null) bar(b![i], 'warning-solid')]),
              ),
            ),
        ]),
      ),
      Container(height: 1, color: ink(context, 'border')),
      const SizedBox(height: 4),
      Row(children: [for (final label in labels) Expanded(child: Txt(label, size: 'xs', color: 'text-subtle', align: TextAlign.center))]),
    ]);
  }
}

/// Thin bars along a line of time, with a rule across at [mark]; a bar over it is drawn in the danger colour.
class Strip extends StatelessWidget {
  const Strip({super.key, required this.values, required this.mark, this.height = 72, this.slots});
  final List<double> values;
  final double mark, height;

  /// How many bars the width is cut into: more than there are values leaves the left empty, as a readout filling.
  final int? slots;

  @override
  Widget build(BuildContext context) => SizedBox(
        height: height,
        width: double.infinity,
        child: CustomPaint(painter: _Strip(values, mark, slots ?? values.length, ink(context, 'text'), ink(context, 'danger-solid'), ink(context, 'border-strong'))),
      );
}

class _Strip extends CustomPainter {
  _Strip(this.values, this.mark, this.slots, this.plain, this.hot, this.rule);
  final List<double> values;
  final double mark;
  final int slots;
  final Color plain, hot, rule;

  @override
  void paint(Canvas canvas, Size size) {
    if (values.isEmpty || slots == 0) return;
    final tallest = [...values, mark * 1.4].reduce((a, b) => a > b ? a : b);
    final each = size.width / slots;
    final gap = each > 5 ? 1.5 : each > 2 ? 0.5 : 0.0;
    final fill = Paint();
    for (var i = 0; i < values.length; i++) {
      final v = values[i];
      if (v <= 0) continue;
      fill.color = v > mark ? hot : plain;
      final x = (slots - values.length + i) * each;
      final tall = (size.height * v / tallest).clamp(1.5, size.height);
      canvas.drawRRect(RRect.fromLTRBR(x + gap, size.height - tall, x + each - gap, size.height, Radius.circular(each > 5 ? 2 : 0)), fill);
    }
    final y = size.height - size.height * mark / tallest;
    canvas.drawRect(Rect.fromLTWH(0, y, size.width, 1), Paint()..color = rule);
  }

  @override
  bool shouldRepaint(_Strip old) => old.values != values || old.mark != mark || old.plain != plain;
}
