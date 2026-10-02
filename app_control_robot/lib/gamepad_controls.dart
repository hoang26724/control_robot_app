import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'robot_link.dart';

/// One arm of a console D-pad.
///
/// [onPressed] fires as soon as the finger lands. [onReleased] fires when it
/// lifts, drags off, or the gesture is otherwise cancelled — every one of those
/// paths stops the robot, so releasing can never leave the motors running.
///
/// Uses [Listener] rather than [InkWell] because pointer-up is delivered even
/// when the finger moves off the button. That is the behavior we want here. Do
/// not "simplify" this to an [InkWell] or a gesture recognizer.
class DpadArrow extends StatefulWidget {
  const DpadArrow({
    required this.command,
    required this.icon,
    required this.turns,
    required this.enabled,
    required this.onPressed,
    required this.onReleased,
    this.size = 84,
    super.key,
  });

  /// The single ASCII command this arm sends: `F`, `B`, `L` or `R`.
  final String command;

  final IconData icon;

  /// Clockwise quarter-turns applied to an up-pointing triangle. 0 = up,
  /// 1 = right, 2 = down, 3 = left.
  final int turns;

  /// Side of the square this arm occupies, before rotation.
  final double size;

  final bool enabled;
  final ValueChanged<String> onPressed;
  final VoidCallback onReleased;

  @override
  State<DpadArrow> createState() => _DpadArrowState();
}

class _DpadArrowState extends State<DpadArrow> {
  bool _pressed = false;

  void _start() {
    if (!widget.enabled || _pressed) {
      return;
    }
    setState(() => _pressed = true);
    HapticFeedback.selectionClick();
    widget.onPressed(widget.command);
  }

  void _end() {
    if (!_pressed) {
      return;
    }
    setState(() => _pressed = false);
    widget.onReleased();
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final active = _pressed && widget.enabled;
    final fill = !widget.enabled
        ? scheme.surfaceContainerHighest
        : active
            ? scheme.primary
            : scheme.surfaceContainerHigh;

    return Listener(
      behavior: HitTestBehavior.opaque,
      onPointerDown: widget.enabled ? (_) => _start() : null,
      onPointerUp: widget.enabled ? (_) => _end() : null,
      onPointerCancel: widget.enabled ? (_) => _end() : null,
      child: Transform.rotate(
        angle: widget.turns * math.pi / 2,
        child: ClipPath(
          clipper: const _UpTriangleClipper(),
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 80),
            width: widget.size,
            height: widget.size,
            color: fill,
            child: Icon(
              widget.icon,
              // Counter-rotate so the glyph is not printed sideways.
              size: widget.size * 0.5,
              color: widget.enabled
                  ? (active ? scheme.onPrimary : scheme.onSurface)
                  : scheme.onSurfaceVariant,
            ),
          ),
        ),
      ),
    );
  }
}

class _UpTriangleClipper extends CustomClipper<Path> {
  const _UpTriangleClipper();

  @override
  Path getClip(Size size) => Path()
    ..moveTo(size.width / 2, 0)
    ..lineTo(size.width, size.height)
    ..lineTo(0, size.height)
    ..close();

  @override
  bool shouldReclip(_UpTriangleClipper oldClipper) => false;
}

/// The four [DpadArrow]s plus the hub, laid out as one console cross.
///
/// The arms are pulled towards the middle with [Transform.translate] so they
/// share a single hub, the way a real D-pad is moulded. Transforms move the
/// paint *and* the hit-test region but not the layout box, so the cross stays
/// centred in a square and the overlap costs no extra space in the flow —
/// unlike negative padding, which Flutter rejects outright.
class GamepadDpad extends StatelessWidget {
  const GamepadDpad({
    required this.enabled,
    required this.activeCommand,
    required this.onPressed,
    required this.onReleased,
    this.size = 84,
    super.key,
  });

  final bool enabled;

  /// Command currently held, or null. Lights the matching arm and the hub.
  final String? activeCommand;

  final ValueChanged<String> onPressed;
  final VoidCallback onReleased;

  /// Side of one arm's square.
  final double size;

  static const double overlap = 14;
  static const double hubSize = 54;

  @override
  Widget build(BuildContext context) {
    Widget arm(String command, IconData icon, int turns, Offset pull) =>
        Transform.translate(
          offset: pull,
          child: DpadArrow(
            command: command,
            icon: icon,
            turns: turns,
            enabled: enabled,
            onPressed: onPressed,
            onReleased: onReleased,
            size: size,
          ),
        );

    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        arm('F', Icons.keyboard_arrow_up, 0, const Offset(0, overlap)),
        Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            arm('L', Icons.turn_left, 3, const Offset(overlap, 0)),
            _Hub(size: hubSize, activeCommand: activeCommand),
            arm('R', Icons.turn_right, 1, const Offset(-overlap, 0)),
          ],
        ),
        arm('B', Icons.keyboard_arrow_down, 2, const Offset(0, -overlap)),
      ],
    );
  }
}

/// Centre of the D-pad: shows which direction the robot is being told to go.
class _Hub extends StatelessWidget {
  const _Hub({required this.size, required this.activeCommand});

  final double size;
  final String? activeCommand;

  static const Map<String, IconData> _icons = {
    'F': Icons.keyboard_arrow_up,
    'B': Icons.keyboard_arrow_down,
    'L': Icons.turn_left,
    'R': Icons.turn_right,
  };

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final icon = activeCommand == null ? null : _icons[activeCommand];

    return SizedBox(
      width: size,
      height: size,
      child: DecoratedBox(
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: icon == null
              ? scheme.surface
              : scheme.primaryContainer,
        ),
        child: icon == null
            ? Center(
                child: Icon(
                  Icons.bluetooth_connected,
                  size: size * 0.4,
                  color: scheme.onSurfaceVariant,
                ),
              )
            : Icon(icon, size: size * 0.55, color: scheme.onPrimaryContainer),
      ),
    );
  }
}

/// A round console button that fires once per tap. Used for STOP and for
/// opening the device picker, where there is no "hold" to speak of.
class GamepadActionButton extends StatelessWidget {
  const GamepadActionButton({
    required this.label,
    required this.color,
    required this.onPressed,
    this.icon,
    this.size = 76,
    super.key,
  });

  final String label;
  final Color color;
  final VoidCallback onPressed;
  final IconData? icon;
  final double size;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Semantics(
      button: true,
      label: label,
      child: Material(
        color: color,
        shape: const CircleBorder(),
        child: InkWell(
          // A tap, not a hold: STOP must work even if the finger lands and
          // immediately lifts, so this deliberately does not use Listener.
          customBorder: const CircleBorder(),
          onTap: onPressed,
          child: SizedBox(
            width: size,
            height: size,
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                if (icon != null)
                  Icon(icon, size: size * 0.34, color: theme.colorScheme.onError),
                Text(
                  label,
                  style: theme.textTheme.labelSmall?.copyWith(
                    color: theme.colorScheme.onError,
                    fontWeight: FontWeight.bold,
                    letterSpacing: 1,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// Status LED: green while connected, amber while connecting, red otherwise.
class LinkLamp extends StatelessWidget {
  const LinkLamp({required this.state, super.key});

  final RobotLinkState state;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final color = switch (state) {
      RobotLinkState.connected => scheme.primary,
      RobotLinkState.connecting => scheme.tertiary,
      RobotLinkState.disconnected => scheme.error,
    };
    return Container(
      width: 12,
      height: 12,
      decoration: BoxDecoration(
        color: color,
        shape: BoxShape.circle,
        boxShadow: [
          BoxShadow(color: color.withValues(alpha: 0.6), blurRadius: 8),
        ],
      ),
    );
  }
}
