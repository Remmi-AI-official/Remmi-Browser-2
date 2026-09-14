package com.remmi.browser.ui.passwords

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.remmi.browser.security.ClipboardManager
import com.remmi.browser.ui.theme.ThemeCyber
import java.security.SecureRandom
import kotlin.math.roundToInt

data class GeneratorSettings(
  val length: Int = 20,
  val includeUppercase: Boolean = true,
  val includeLowercase: Boolean = true,
  val includeDigits: Boolean = true,
  val includeSymbols: Boolean = true,
  val excludeAmbiguous: Boolean = true,
  val isPassphraseMode: Boolean = false,
  val wordCount: Int = 4,
)

object MilitaryPasswordEngine {
  private val RNG = SecureRandom()

  private val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
  private val UPPER_AMBIGUOUS = "IO"
  private val LOWER = "abcdefghijkmnopqrstuvwxyz"
  private val LOWER_AMBIGUOUS = "l"
  private val DIGITS = "23456789"
  private val DIGITS_AMBIGUOUS = "01"
  private val SYMBOLS = "!@#$%^&*()_+-=[]{}|;:,.<>?"

  private val WORDS = listOf(
    "quantum", "cipher", "matrix", "falcon", "nexus", "titan", "plasma",
    "shield", "vortex", "cobalt", "iron", "phantom", "sentinel", "apex",
    "crypto", "zenith", "hyper", "stellar", "aurora", "beacon", "delta",
    "eclipse", "gravity", "horizon", "infinity", "jupiter", "kinetic",
    "lumina", "monolith", "nebula", "omega", "pulsar", "quasar", "radiant",
    "solstice", "thermal", "ultra", "vector", "warp", "xenon", "yield", "zero"
  )

  fun generate(settings: GeneratorSettings): String {
    if (settings.isPassphraseMode) {
      val selectedWords = (0 until settings.wordCount).map {
        WORDS[RNG.nextInt(WORDS.size)]
      }
      val separator = if (settings.includeSymbols) {
        val syms = listOf("-", "_", ".", "#", "$")
        syms[RNG.nextInt(syms.size)]
      } else "-"
      val joined = selectedWords.joinToString(separator)
      val numSuffix = if (settings.includeDigits) RNG.nextInt(900) + 100 else ""
      return if (settings.includeUppercase) {
        joined.split(separator).joinToString(separator) { it.replaceFirstChar { c -> c.uppercase() } } + numSuffix
      } else {
        joined + numSuffix
      }
    }

    var pool = StringBuilder()
    if (settings.includeUppercase) pool.append(UPPER + if (!settings.excludeAmbiguous) UPPER_AMBIGUOUS else "")
    if (settings.includeLowercase) pool.append(LOWER + if (!settings.excludeAmbiguous) LOWER_AMBIGUOUS else "")
    if (settings.includeDigits) pool.append(DIGITS + if (!settings.excludeAmbiguous) DIGITS_AMBIGUOUS else "")
    if (settings.includeSymbols) pool.append(SYMBOLS)

    val charPool = pool.toString()
    if (charPool.isEmpty()) return "RemmiVault#2026!"

    val chars = CharArray(settings.length)
    for (i in 0 until settings.length) {
      chars[i] = charPool[RNG.nextInt(charPool.length)]
    }
    return String(chars)
  }
}

@Composable
fun MilitaryPasswordGeneratorDialog(
  onDismiss: () -> Unit,
  onUsePassword: ((String) -> Unit)? = null,
) {
  val context = LocalContext.current
  val clipboard = remember { ClipboardManager(context) }

  var settings by remember { mutableStateOf(GeneratorSettings()) }
  var currentPassword by remember { mutableStateOf(MilitaryPasswordEngine.generate(settings)) }

  val entropy = remember(currentPassword) { PasswordManagerUtils.calculateEntropyBits(currentPassword) }
  val strength = remember(currentPassword) { PasswordManagerUtils.evaluateStrength(currentPassword) }

  Dialog(onDismissRequest = onDismiss) {
    Card(
      shape = RoundedCornerShape(20.dp),
      colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surface),
      border = BorderStroke(1.5.dp, ThemeCyber.colors.primary.copy(alpha = 0.5f)),
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 4.dp)
        .testTag("dialog_military_generator")
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(20.dp)
      ) {
        // Header
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth()
        ) {
          Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
              .size(36.dp)
              .clip(RoundedCornerShape(10.dp))
              .background(ThemeCyber.colors.primary.copy(alpha = 0.15f))
          ) {
            Icon(
              imageVector = Icons.Default.Security,
              contentDescription = null,
              tint = ThemeCyber.colors.primary,
              modifier = Modifier.size(20.dp)
            )
          }
          Spacer(modifier = Modifier.width(12.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "Military Password Engine",
              color = ThemeCyber.colors.textPrimary,
              fontWeight = FontWeight.Bold,
              fontSize = 17.sp,
              fontFamily = ThemeCyber.fontFamily
            )
            Text(
              text = "CSPRNG Quantum-Resistant Entropy",
              color = ThemeCyber.colors.primary,
              fontSize = 11.sp,
              fontFamily = ThemeCyber.fontFamily
            )
          }
          IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = "Close",
              tint = ThemeCyber.colors.textSecondary
            )
          }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Generated Password Card
        Card(
          shape = RoundedCornerShape(14.dp),
          colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surfaceLight),
          border = BorderStroke(1.dp, strength.color.copy(alpha = 0.5f)),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(14.dp)) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
              modifier = Modifier.fillMaxWidth()
            ) {
              Text(
                text = currentPassword,
                color = ThemeCyber.colors.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = if (currentPassword.length > 28) 13.sp else 16.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
              )
              IconButton(
                onClick = {
                  currentPassword = MilitaryPasswordEngine.generate(settings)
                },
                modifier = Modifier.size(36.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.Refresh,
                  contentDescription = "Regenerate",
                  tint = ThemeCyber.colors.primary
                )
              }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Strength bar
            LinearProgressIndicator(
              progress = { strength.scoreFraction },
              color = strength.color,
              trackColor = ThemeCyber.colors.surfaceBorder,
              modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
              horizontalArrangement = Arrangement.SpaceBetween,
              modifier = Modifier.fillMaxWidth()
            ) {
              Text(
                text = strength.label,
                color = strength.color,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = ThemeCyber.fontFamily
              )
              Text(
                text = "$entropy bits of entropy",
                color = ThemeCyber.colors.textSecondary,
                fontSize = 12.sp,
                fontFamily = ThemeCyber.fontFamily
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Controls
        if (!settings.isPassphraseMode) {
          Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
          ) {
            Text(
              text = "Length: ${settings.length} characters",
              color = ThemeCyber.colors.textPrimary,
              fontSize = 13.sp,
              fontWeight = FontWeight.Medium,
              fontFamily = ThemeCyber.fontFamily
            )
            Text(
              text = if (settings.length >= 24) "Military (24+)" else if (settings.length >= 16) "Strong" else "Standard",
              color = if (settings.length >= 24) ThemeCyber.colors.primary else ThemeCyber.colors.textSecondary,
              fontSize = 12.sp,
              fontFamily = ThemeCyber.fontFamily
            )
          }

          Slider(
            value = settings.length.toFloat(),
            onValueChange = {
              settings = settings.copy(length = it.roundToInt())
              currentPassword = MilitaryPasswordEngine.generate(settings)
            },
            valueRange = 8f..64f,
            steps = 55,
            colors = SliderDefaults.colors(
              thumbColor = ThemeCyber.colors.primary,
              activeTrackColor = ThemeCyber.colors.primary,
              inactiveTrackColor = ThemeCyber.colors.surfaceBorder
            ),
            modifier = Modifier.fillMaxWidth()
          )
        } else {
          Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
          ) {
            Text(
              text = "Words: ${settings.wordCount}",
              color = ThemeCyber.colors.textPrimary,
              fontSize = 13.sp,
              fontFamily = ThemeCyber.fontFamily
            )
          }
          Slider(
            value = settings.wordCount.toFloat(),
            onValueChange = {
              settings = settings.copy(wordCount = it.roundToInt())
              currentPassword = MilitaryPasswordEngine.generate(settings)
            },
            valueRange = 3f..8f,
            steps = 4,
            colors = SliderDefaults.colors(
              thumbColor = ThemeCyber.colors.primary,
              activeTrackColor = ThemeCyber.colors.primary,
              inactiveTrackColor = ThemeCyber.colors.surfaceBorder
            ),
            modifier = Modifier.fillMaxWidth()
          )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Toggles
        GeneratorToggleRow("Passphrase Mode (Diceware)", settings.isPassphraseMode) {
          settings = settings.copy(isPassphraseMode = it)
          currentPassword = MilitaryPasswordEngine.generate(settings)
        }
        if (!settings.isPassphraseMode) {
          GeneratorToggleRow("Uppercase (A-Z)", settings.includeUppercase) {
            settings = settings.copy(includeUppercase = it)
            currentPassword = MilitaryPasswordEngine.generate(settings)
          }
          GeneratorToggleRow("Lowercase (a-z)", settings.includeLowercase) {
            settings = settings.copy(includeLowercase = it)
            currentPassword = MilitaryPasswordEngine.generate(settings)
          }
          GeneratorToggleRow("Numbers (0-9)", settings.includeDigits) {
            settings = settings.copy(includeDigits = it)
            currentPassword = MilitaryPasswordEngine.generate(settings)
          }
          GeneratorToggleRow("Special Characters (!@#$)", settings.includeSymbols) {
            settings = settings.copy(includeSymbols = it)
            currentPassword = MilitaryPasswordEngine.generate(settings)
          }
          GeneratorToggleRow("Exclude Ambiguous (1, l, 0, O)", settings.excludeAmbiguous) {
            settings = settings.copy(excludeAmbiguous = it)
            currentPassword = MilitaryPasswordEngine.generate(settings)
          }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Action Buttons
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth()
        ) {
          OutlinedButton(
            onClick = {
              clipboard.copyWithAutoClear(currentPassword, label = "Generated Password", clearAfterMs = 30000)
              Toast.makeText(context, "Copied! Auto-clears in 30s.", Toast.LENGTH_SHORT).show()
            },
            border = BorderStroke(1.dp, ThemeCyber.colors.primary),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
            modifier = Modifier.weight(if (onUsePassword != null) 0.8f else 1f)
          ) {
            Icon(
              imageVector = Icons.Default.ContentCopy,
              contentDescription = null,
              tint = ThemeCyber.colors.primary,
              modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = "Copy",
              color = ThemeCyber.colors.primary,
              fontWeight = FontWeight.Bold,
              fontSize = 12.sp,
              fontFamily = ThemeCyber.fontFamily,
              maxLines = 1,
              softWrap = false
            )
          }

          if (onUsePassword != null) {
            Button(
              onClick = {
                onUsePassword(currentPassword)
                onDismiss()
              },
              colors = ButtonDefaults.buttonColors(containerColor = ThemeCyber.colors.primary),
              shape = RoundedCornerShape(12.dp),
              contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
              modifier = Modifier.weight(1.2f)
            ) {
              Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(16.dp)
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = "Use Password",
                color = Color.Black,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                fontFamily = ThemeCyber.fontFamily,
                maxLines = 1,
                softWrap = false
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun GeneratorToggleRow(
  label: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  Row(
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 3.dp)
  ) {
    Text(
      text = label,
      color = ThemeCyber.colors.textPrimary,
      fontSize = 12.5.sp,
      fontFamily = ThemeCyber.fontFamily
    )
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      colors = SwitchDefaults.colors(
        checkedThumbColor = ThemeCyber.colors.primary,
        checkedTrackColor = ThemeCyber.colors.primary.copy(alpha = 0.3f),
        uncheckedThumbColor = ThemeCyber.colors.textSecondary,
        uncheckedTrackColor = ThemeCyber.colors.surfaceLight,
      ),
      modifier = Modifier.size(36.dp, 24.dp)
    )
  }
}
