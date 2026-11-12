import 'package:flutter/material.dart';

import 'tokens.dart';

const ColorScheme _lightScheme = ColorScheme(
  brightness: Brightness.light,
  primary: Color(0xFF0B6B66),
  onPrimary: Color(0xFFFFFFFF),
  primaryContainer: Color(0xFFCDE8E4),
  onPrimaryContainer: Color(0xFF00201E),
  secondary: Color(0xFF4A5A5E),
  onSecondary: Color(0xFFFFFFFF),
  secondaryContainer: Color(0xFFE1E8E7),
  onSecondaryContainer: Color(0xFF0F1B1D),
  tertiary: Color(0xFF9A5B00),
  onTertiary: Color(0xFFFFFFFF),
  tertiaryContainer: Color(0xFFFBE3BC),
  onTertiaryContainer: Color(0xFF301A00),
  error: Color(0xFFB3261E),
  onError: Color(0xFFFFFFFF),
  errorContainer: Color(0xFFF7DAD6),
  onErrorContainer: Color(0xFF410002),
  surface: Color(0xFFFFFFFF),
  onSurface: Color(0xFF1A2124),
  onSurfaceVariant: Color(0xFF5B6569),
  outline: Color(0xFFB9B4A6),
  outlineVariant: Color(0xFFDCD8CD),
  surfaceContainerLowest: Color(0xFFFFFFFF),
  surfaceContainerLow: Color(0xFFFAF9F6),
  surfaceContainer: Color(0xFFF5F3EE),
  surfaceContainerHigh: Color(0xFFEEEBE4),
  surfaceContainerHighest: Color(0xFFE8E5DC),
  inverseSurface: Color(0xFF2B3336),
  onInverseSurface: Color(0xFFF1F1EE),
  inversePrimary: Color(0xFF7ED3CC),
  shadow: Color(0xFF000000),
  scrim: Color(0xFF000000),
);

const ColorScheme _darkScheme = ColorScheme(
  brightness: Brightness.dark,
  primary: Color(0xFF7ED3CC),
  onPrimary: Color(0xFF00201E),
  primaryContainer: Color(0xFF0B4F4B),
  onPrimaryContainer: Color(0xFFCDE8E4),
  secondary: Color(0xFFB4C4C6),
  onSecondary: Color(0xFF1E2C2F),
  secondaryContainer: Color(0xFF2C3A3D),
  onSecondaryContainer: Color(0xFFDDE7E8),
  tertiary: Color(0xFFF0C673),
  onTertiary: Color(0xFF412A00),
  tertiaryContainer: Color(0xFF5C3F00),
  onTertiaryContainer: Color(0xFFFBE3BC),
  error: Color(0xFFF2B8B5),
  onError: Color(0xFF601410),
  errorContainer: Color(0xFF8C1D18),
  onErrorContainer: Color(0xFFF9DEDC),
  surface: Color(0xFF1B2124),
  onSurface: Color(0xFFE9ECEA),
  onSurfaceVariant: Color(0xFFA3ADAF),
  outline: Color(0xFF465054),
  outlineVariant: Color(0xFF2E3639),
  surfaceContainerLowest: Color(0xFF101416),
  surfaceContainerLow: Color(0xFF171C1E),
  surfaceContainer: Color(0xFF1B2124),
  surfaceContainerHigh: Color(0xFF242B2E),
  surfaceContainerHighest: Color(0xFF2E3639),
  inverseSurface: Color(0xFFE9ECEA),
  onInverseSurface: Color(0xFF1B2124),
  inversePrimary: Color(0xFF0B6B66),
  shadow: Color(0xFF000000),
  scrim: Color(0xFF000000),
);

ThemeData buildTheme(Brightness brightness) {
  final dark = brightness == Brightness.dark;
  final scheme = dark ? _darkScheme : _lightScheme;
  final tokens = dark ? AppTokens.dark : AppTokens.light;
  final base = ThemeData(brightness: brightness, colorScheme: scheme, useMaterial3: true);
  final text = base.textTheme.apply(bodyColor: tokens.ink, displayColor: tokens.ink).copyWith(
        headlineSmall: base.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w600, letterSpacing: -0.2),
        titleLarge: base.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w600, letterSpacing: -0.1),
        titleMedium: base.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600),
        titleSmall: base.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w600),
        labelLarge: base.textTheme.labelLarge?.copyWith(fontWeight: FontWeight.w600),
        bodySmall: base.textTheme.bodySmall?.copyWith(color: tokens.inkSoft),
      );
  final border = OutlineInputBorder(
    borderRadius: BorderRadius.circular(AppTokens.radius),
    borderSide: BorderSide(color: tokens.lineStrong),
  );
  return base.copyWith(
    extensions: [tokens],
    scaffoldBackgroundColor: tokens.paper,
    textTheme: text,
    visualDensity: VisualDensity.standard,
    appBarTheme: AppBarTheme(
      backgroundColor: scheme.surface,
      foregroundColor: tokens.ink,
      elevation: 0,
      scrolledUnderElevation: 0,
      centerTitle: false,
      titleTextStyle: text.titleMedium,
      shape: Border(bottom: BorderSide(color: tokens.line)),
    ),
    cardTheme: CardThemeData(
      color: scheme.surface,
      elevation: 0,
      margin: EdgeInsets.zero,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(AppTokens.radius),
        side: BorderSide(color: tokens.line),
      ),
    ),
    dividerTheme: DividerThemeData(color: tokens.line, space: 1, thickness: 1),
    inputDecorationTheme: InputDecorationTheme(
      isDense: true,
      filled: true,
      fillColor: scheme.surface,
      contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
      border: border,
      enabledBorder: border,
      focusedBorder: border.copyWith(borderSide: BorderSide(color: scheme.primary, width: 2)),
      errorBorder: border.copyWith(borderSide: BorderSide(color: scheme.error)),
      focusedErrorBorder: border.copyWith(borderSide: BorderSide(color: scheme.error, width: 2)),
      labelStyle: text.bodyMedium?.copyWith(color: tokens.inkSoft),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        minimumSize: const Size(64, AppTokens.tapTarget),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppTokens.radius)),
        textStyle: text.labelLarge,
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        minimumSize: const Size(64, AppTokens.tapTarget),
        side: BorderSide(color: tokens.lineStrong),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppTokens.radius)),
        textStyle: text.labelLarge,
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(
        minimumSize: const Size(48, AppTokens.tapTarget),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppTokens.radius)),
        textStyle: text.labelLarge,
      ),
    ),
    chipTheme: base.chipTheme.copyWith(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppTokens.radius)),
      side: BorderSide(color: tokens.line),
    ),
    dialogTheme: DialogThemeData(
      backgroundColor: scheme.surface,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(AppTokens.radiusLarge),
        side: BorderSide(color: tokens.line),
      ),
    ),
    snackBarTheme: SnackBarThemeData(
      behavior: SnackBarBehavior.floating,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppTokens.radius)),
    ),
    navigationBarTheme: NavigationBarThemeData(
      backgroundColor: scheme.surface,
      indicatorColor: scheme.primaryContainer,
      surfaceTintColor: Colors.transparent,
      labelTextStyle: WidgetStatePropertyAll(text.labelMedium),
    ),
    navigationRailTheme: NavigationRailThemeData(
      backgroundColor: scheme.surface,
      indicatorColor: scheme.primaryContainer,
      selectedLabelTextStyle: text.labelLarge?.copyWith(color: scheme.primary),
      unselectedLabelTextStyle: text.labelLarge?.copyWith(color: tokens.inkSoft),
    ),
    tabBarTheme: TabBarThemeData(
      labelStyle: text.labelLarge,
      unselectedLabelStyle: text.labelLarge,
      indicatorColor: scheme.primary,
      labelColor: scheme.primary,
      unselectedLabelColor: tokens.inkSoft,
      dividerColor: tokens.line,
    ),
    checkboxTheme: CheckboxThemeData(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(3)),
    ),
  );
}
