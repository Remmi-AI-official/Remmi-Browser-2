package com.remmi.browser.ui.passwords

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.remmi.browser.security.crypto.DecryptedPasswordEntry
import com.remmi.browser.ui.theme.ThemeCyber

@Composable
fun SecurityAuditDialog(
  entries: List<DecryptedPasswordEntry>,
  onDismiss: () -> Unit,
  onEditEntry: (DecryptedPasswordEntry) -> Unit,
) {
  val stats = remember(entries) { PasswordManagerUtils.computeVaultStats(entries) }

  val weakEntries = remember(entries) {
    entries.filter {
      val str = PasswordManagerUtils.evaluateStrength(it.password)
      str == PasswordStrength.CRITICAL || str == PasswordStrength.WEAK
    }
  }

  val reusedGroups = remember(entries) {
    val map = mutableMapOf<String, MutableList<DecryptedPasswordEntry>>()
    for (e in entries) {
      map.getOrPut(e.password) { mutableListOf() }.add(e)
    }
    map.filter { it.value.size > 1 }.values.toList()
  }

  Dialog(onDismissRequest = onDismiss) {
    Card(
      shape = RoundedCornerShape(20.dp),
      colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surface),
      border = BorderStroke(1.5.dp, ThemeCyber.colors.primary.copy(alpha = 0.5f)),
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 4.dp)
        .testTag("dialog_security_audit")
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
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
              text = "Vault Security Audit",
              color = ThemeCyber.colors.textPrimary,
              fontWeight = FontWeight.Bold,
              fontSize = 17.sp,
              fontFamily = ThemeCyber.fontFamily
            )
            Text(
              text = "Zero-Knowledge Cryptographic Analysis",
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

        // Health Score Summary
        Card(
          shape = RoundedCornerShape(14.dp),
          colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surfaceLight),
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(14.dp)
          ) {
            Box(
              contentAlignment = Alignment.Center,
              modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(
                  if (stats.overallHealth >= 80) ThemeCyber.colors.successGreen.copy(alpha = 0.2f)
                  else if (stats.overallHealth >= 50) Color(0xFFFFB300).copy(alpha = 0.2f)
                  else ThemeCyber.colors.dangerRed.copy(alpha = 0.2f)
                )
            ) {
              Text(
                text = "${stats.overallHealth}%",
                color = if (stats.overallHealth >= 80) ThemeCyber.colors.successGreen
                else if (stats.overallHealth >= 50) Color(0xFFFFB300)
                else ThemeCyber.colors.dangerRed,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                fontFamily = ThemeCyber.fontFamily
              )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = if (stats.overallHealth >= 80) "Military-Grade Protection" else "Vulnerabilities Detected",
                color = ThemeCyber.colors.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 13.5.sp,
                fontFamily = ThemeCyber.fontFamily
              )
              Text(
                text = "${stats.weakPasswordsCount} weak passwords • ${stats.reusedPasswordsCount} reused instances",
                color = ThemeCyber.colors.textSecondary,
                fontSize = 11.5.sp,
                fontFamily = ThemeCyber.fontFamily
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn(
          verticalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.weight(1f, fill = false)
        ) {
          if (weakEntries.isEmpty() && reusedGroups.isEmpty()) {
            item {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(vertical = 20.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.CheckCircle,
                  contentDescription = null,
                  tint = ThemeCyber.colors.successGreen,
                  modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                  text = "All passwords in your vault meet military strength requirements!",
                  color = ThemeCyber.colors.textPrimary,
                  fontSize = 12.5.sp,
                  fontFamily = ThemeCyber.fontFamily
                )
              }
            }
          }

          if (weakEntries.isNotEmpty()) {
            item {
              Text(
                text = "Weak or Short Passwords (${weakEntries.size})",
                color = ThemeCyber.colors.dangerRed,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                fontFamily = ThemeCyber.fontFamily,
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
              )
            }

            items(weakEntries) { entry ->
              val domain = PasswordManagerUtils.extractDomain(entry.url)
              val strength = PasswordManagerUtils.evaluateStrength(entry.password)
              AuditItemRow(
                title = domain,
                subtitle = entry.username.ifBlank { "No username" },
                badge = strength.label,
                badgeColor = strength.color,
                onClick = {
                  onDismiss()
                  onEditEntry(entry)
                }
              )
            }
          }

          if (reusedGroups.isNotEmpty()) {
            item {
              Text(
                text = "Reused Passwords (${reusedGroups.size} groups)",
                color = Color(0xFFFFB300),
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                fontFamily = ThemeCyber.fontFamily,
                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
              )
            }

            items(reusedGroups) { group ->
              val domains = group.map { PasswordManagerUtils.extractDomain(it.url) }.distinct().joinToString(", ")
              AuditItemRow(
                title = domains,
                subtitle = "Used across ${group.size} accounts",
                badge = "Reused",
                badgeColor = Color(0xFFFFB300),
                onClick = {
                  onDismiss()
                  onEditEntry(group.first())
                }
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun AuditItemRow(
  title: String,
  subtitle: String,
  badge: String,
  badgeColor: Color,
  onClick: () -> Unit,
) {
  Card(
    shape = RoundedCornerShape(10.dp),
    colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surfaceLight),
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(10.dp)
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          color = ThemeCyber.colors.textPrimary,
          fontWeight = FontWeight.Bold,
          fontSize = 13.sp,
          fontFamily = ThemeCyber.fontFamily
        )
        Text(
          text = subtitle,
          color = ThemeCyber.colors.textSecondary,
          fontSize = 11.5.sp,
          fontFamily = ThemeCyber.fontFamily
        )
      }

      Box(
        modifier = Modifier
          .clip(RoundedCornerShape(6.dp))
          .background(badgeColor.copy(alpha = 0.15f))
          .padding(horizontal = 8.dp, vertical = 3.dp)
      ) {
        Text(
          text = badge,
          color = badgeColor,
          fontSize = 10.5.sp,
          fontWeight = FontWeight.Bold,
          fontFamily = ThemeCyber.fontFamily
        )
      }

      Spacer(modifier = Modifier.width(8.dp))

      Icon(
        imageVector = Icons.Default.Edit,
        contentDescription = "Edit",
        tint = ThemeCyber.colors.primary,
        modifier = Modifier.size(16.dp)
      )
    }
  }
}
