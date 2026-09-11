package com.remmi.browser.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import com.remmi.browser.storage.AppearanceMode

import androidx.compose.ui.text.font.FontFamily

@Composable
fun RemmiTheme(
  appearanceMode: AppearanceMode = AppearanceMode.SYSTEM,
  cyberTheme: CyberTheme = CyberTheme.NORMAL_DEFAULT,
  cyberHudEnabled: Boolean = false,
  pureBlackOled: Boolean = false,
  browserFont: BrowserFont = BrowserFont.CHROME_SANS,
  content: @Composable () -> Unit,
) {
  val systemIsDark = isSystemInDarkTheme()
  val effectiveIsDark = when (appearanceMode) {
    AppearanceMode.SYSTEM -> systemIsDark
    AppearanceMode.LIGHT -> false
    AppearanceMode.DARK -> true
  }

  // If Cyberpunk HUD is disabled (default), always use clean normal default theme
  val effectiveTheme = when {
    !cyberHudEnabled -> if (effectiveIsDark) CyberTheme.MINIMAL_DARK else CyberTheme.NORMAL_DEFAULT
    !effectiveIsDark && !cyberTheme.isLight -> CyberTheme.NORMAL_DEFAULT
    effectiveIsDark && cyberTheme.isLight -> CyberTheme.MINIMAL_DARK
    else -> cyberTheme
  }

  val isLight = effectiveTheme.isLight
  val isNormal = effectiveTheme.isNormalTheme

  val bg = if (isLight) Color(0xFFF8F9FA) else if (pureBlackOled) Color(0xFF000000) else Color(0xFF0A0A0F)
  val bgDarker = if (isLight) Color(0xFFFFFFFF) else if (pureBlackOled) Color(0xFF000000) else Color(0xFF050508)
  val surface = if (isLight) Color(0xFFFFFFFF) else if (pureBlackOled) Color(0xFF0D0D12) else Color(0xFF12121A)
  val surfaceLight = if (isLight) Color(0xFFF1F3F4) else if (pureBlackOled) Color(0xFF161622) else Color(0xFF1A1A28)
  val surfaceBorder = if (isLight) Color(0xFFDADCE0) else if (pureBlackOled) Color(0xFF222234) else Color(0xFF26263A)

  val activeFontFamily = browserFont.fontFamily

  val cyberColors = CyberColorScheme(
    primary = effectiveTheme.primaryAccent,
    secondary = effectiveTheme.secondaryAccent,
    tertiary = effectiveTheme.tertiaryAccent,
    background = bg,
    backgroundDarker = bgDarker,
    surface = surface,
    surfaceLight = surfaceLight,
    surfaceBorder = surfaceBorder,
    glow = effectiveTheme.glowColor,
    textPrimary = if (isLight) Color(0xFF202124) else Color(0xFFE0F7FA),
    textSecondary = if (isLight) Color(0xFF5F6368) else Color(0xFF88A0B0),
    textMuted = if (isLight) Color(0xFF70757A) else Color(0xFF4C5D6E),
    neonCyan = if (isLight) Color(0xFF007A87) else Color(0xFF00FFFF),
    dangerRed = if (isLight) Color(0xFFD93025) else Color(0xFFFF003C),
    successGreen = if (isLight) Color(0xFF137333) else Color(0xFF00FF66),
    warningYellow = if (isLight) Color(0xFFE37400) else Color(0xFFFFE600),
    torPurple = if (isLight) Color(0xFF7B1FA2) else Color(0xFFB026FF),
    successContainer = if (isLight) Color(0xFFE6F4EA) else Color(0x2600FF66),
    dangerContainer = if (isLight) Color(0xFFFCE8E6) else Color(0x26FF003C),
    isLight = isLight,
    isNormalTheme = isNormal,
  )

  val m3ColorScheme = if (isLight) {
    lightColorScheme(
      primary = effectiveTheme.primaryAccent,
      onPrimary = Color.White,
      primaryContainer = Color(0xFFE8F0FE),
      onPrimaryContainer = Color(0xFF1967D2),
      secondary = Color(0xFF5F6368),
      onSecondary = Color.White,
      secondaryContainer = Color(0xFFF1F3F4),
      onSecondaryContainer = Color(0xFF202124),
      tertiary = cyberColors.successGreen,
      onTertiary = Color.White,
      background = bg,
      onBackground = cyberColors.textPrimary,
      surface = surface,
      onSurface = cyberColors.textPrimary,
      surfaceVariant = surfaceLight,
      onSurfaceVariant = cyberColors.textSecondary,
      error = cyberColors.dangerRed,
      onError = Color.White,
      outline = surfaceBorder,
      outlineVariant = Color(0xFFE0E0E0),
    )
  } else {
    darkColorScheme(
      primary = effectiveTheme.primaryAccent,
      onPrimary = bgDarker,
      primaryContainer = surfaceLight,
      onPrimaryContainer = effectiveTheme.primaryAccent,
      secondary = effectiveTheme.secondaryAccent,
      onSecondary = bgDarker,
      secondaryContainer = surfaceLight,
      onSecondaryContainer = effectiveTheme.secondaryAccent,
      tertiary = effectiveTheme.tertiaryAccent,
      onTertiary = bgDarker,
      background = bg,
      onBackground = cyberColors.textPrimary,
      surface = surface,
      onSurface = cyberColors.textPrimary,
      surfaceVariant = surfaceLight,
      onSurfaceVariant = cyberColors.textSecondary,
      error = cyberColors.dangerRed,
      onError = bgDarker,
      outline = surfaceBorder,
      outlineVariant = NeonColors.GridLine,
    )
  }

  CompositionLocalProvider(
    LocalCyberColors provides cyberColors,
    LocalCyberFontFamily provides activeFontFamily
  ) {
    MaterialTheme(
      colorScheme = m3ColorScheme,
      typography = getBrowserTypography(activeFontFamily, isNormal),
      content = content,
    )
  }
}


@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  RemmiTheme(content = content)
}
