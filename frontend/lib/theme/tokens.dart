import 'package:flutter/material.dart';

enum Tone { neutral, info, ok, warn, danger }

@immutable
class AppTokens extends ThemeExtension<AppTokens> {
  const AppTokens({
    required this.paper,
    required this.line,
    required this.lineStrong,
    required this.ink,
    required this.inkSoft,
    required this.toneFg,
    required this.toneBg,
    required this.groupLabel,
  });

  final Color paper;
  final Color line;
  final Color lineStrong;
  final Color ink;
  final Color inkSoft;
  final Map<Tone, Color> toneFg;
  final Map<Tone, Color> toneBg;
  final Color groupLabel;

  static const double s1 = 4;
  static const double s2 = 8;
  static const double s3 = 12;
  static const double s4 = 16;
  static const double s5 = 24;
  static const double s6 = 32;
  static const double radius = 6;
  static const double radiusLarge = 10;
  static const double tapTarget = 44;
  static const double contentMaxWidth = 1120;
  static const double formMaxWidth = 720;

  static const Color _paperLight = Color(0xFFF5F3EE);
  static const Color _paperDark = Color(0xFF14181A);

  static const light = AppTokens(
    paper: _paperLight,
    line: Color(0xFFDCD8CD),
    lineStrong: Color(0xFFB9B4A6),
    ink: Color(0xFF1A2124),
    inkSoft: Color(0xFF5B6569),
    toneFg: {
      Tone.neutral: Color(0xFF3F484B),
      Tone.info: Color(0xFF1B4F8A),
      Tone.ok: Color(0xFF0B5F43),
      Tone.warn: Color(0xFF7A4A00),
      Tone.danger: Color(0xFF9C2118),
    },
    toneBg: {
      Tone.neutral: Color(0xFFE8E5DC),
      Tone.info: Color(0xFFDDE9F6),
      Tone.ok: Color(0xFFDAEFE5),
      Tone.warn: Color(0xFFF8E7C2),
      Tone.danger: Color(0xFFF7DAD6),
    },
    groupLabel: Color(0xFF0B5F5A),
  );

  static const dark = AppTokens(
    paper: _paperDark,
    line: Color(0xFF2E3639),
    lineStrong: Color(0xFF465054),
    ink: Color(0xFFE9ECEA),
    inkSoft: Color(0xFFA3ADAF),
    toneFg: {
      Tone.neutral: Color(0xFFC9D0D1),
      Tone.info: Color(0xFF9CC3F0),
      Tone.ok: Color(0xFF8ED8B6),
      Tone.warn: Color(0xFFF0C673),
      Tone.danger: Color(0xFFF2A39B),
    },
    toneBg: {
      Tone.neutral: Color(0xFF263033),
      Tone.info: Color(0xFF1B2C40),
      Tone.ok: Color(0xFF173528),
      Tone.warn: Color(0xFF3B2F15),
      Tone.danger: Color(0xFF3F1D1A),
    },
    groupLabel: Color(0xFF6FD0C8),
  );

  static AppTokens of(BuildContext context) {
    final tokens = Theme.of(context).extension<AppTokens>();
    assert(tokens != null, 'AppTokens is missing from the theme');
    return tokens ?? light;
  }

  @override
  AppTokens copyWith({
    Color? paper,
    Color? line,
    Color? lineStrong,
    Color? ink,
    Color? inkSoft,
    Map<Tone, Color>? toneFg,
    Map<Tone, Color>? toneBg,
    Color? groupLabel,
  }) {
    return AppTokens(
      paper: paper ?? this.paper,
      line: line ?? this.line,
      lineStrong: lineStrong ?? this.lineStrong,
      ink: ink ?? this.ink,
      inkSoft: inkSoft ?? this.inkSoft,
      toneFg: toneFg ?? this.toneFg,
      toneBg: toneBg ?? this.toneBg,
      groupLabel: groupLabel ?? this.groupLabel,
    );
  }

  @override
  AppTokens lerp(ThemeExtension<AppTokens>? other, double t) {
    if (other is! AppTokens) {
      return this;
    }
    return t < 0.5 ? this : other;
  }
}
