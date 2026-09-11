package com.remmi.browser.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remmi.browser.security.permissions.PermissionDecision
import com.remmi.browser.security.permissions.PermissionPromptRequest
import com.remmi.browser.security.permissions.PermissionType
import com.remmi.browser.ui.theme.CyberMonoFamily
import com.remmi.browser.ui.theme.ThemeCyber

@Composable
fun RemmiPermissionDialog(
  request: PermissionPromptRequest?,
  onDecision: (PermissionDecision) -> Unit
) {
  if (request == null) return

  val type = request.permissionType
  val icon: ImageVector = when (type) {
    PermissionType.CAMERA -> Icons.Default.Videocam
    PermissionType.MICROPHONE -> Icons.Default.Mic
    PermissionType.CAMERA_AND_MICROPHONE -> Icons.Default.Videocam
    PermissionType.GEOLOCATION -> Icons.Default.LocationOn
    PermissionType.FILE_UPLOAD -> Icons.Default.UploadFile
  }

  val accentColor = when (type) {
    PermissionType.CAMERA -> ThemeCyber.colors.neonCyan
    PermissionType.MICROPHONE -> Color(0xFFEAB308) // Amber
    PermissionType.CAMERA_AND_MICROPHONE -> ThemeCyber.colors.neonCyan
    PermissionType.GEOLOCATION -> Color(0xFF10B981) // Emerald
    PermissionType.FILE_UPLOAD -> ThemeCyber.colors.primary
  }

  AlertDialog(
    onDismissRequest = { onDecision(PermissionDecision.DENY) },
    containerColor = ThemeCyber.colors.surface,
    shape = RoundedCornerShape(16.dp),
    modifier = Modifier
      .border(1.2.dp, accentColor.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
      .testTag("permission_dialog_${type.name.lowercase()}"),
    title = {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
      ) {
        Box(
          modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(accentColor.copy(alpha = 0.15f))
            .border(1.dp, accentColor.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(22.dp)
          )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column {
          Text(
            text = "ALLOW ${type.displayName.uppercase()}?",
            color = ThemeCyber.colors.textPrimary,
            fontFamily = CyberMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
          )
          if (request.isGhost) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.padding(top = 2.dp)
            ) {
              Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                tint = ThemeCyber.colors.torPurple,
                modifier = Modifier.size(12.dp)
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = "GHOST / AIR-GAP ISOLATION",
                color = ThemeCyber.colors.torPurple,
                fontFamily = CyberMonoFamily,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
              )
            }
          }
        }
      }
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Exact Origin Identity Card
        Surface(
          color = ThemeCyber.colors.background,
          shape = RoundedCornerShape(8.dp),
          border = androidx.compose.foundation.BorderStroke(0.8.dp, ThemeCyber.colors.surfaceBorder),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(10.dp)) {
            Text(
              text = "REQUESTING EXACT ORIGIN",
              color = ThemeCyber.colors.textMuted,
              fontFamily = CyberMonoFamily,
              fontSize = 9.5.sp,
              fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
              text = request.origin,
              color = ThemeCyber.colors.primary,
              fontFamily = CyberMonoFamily,
              fontSize = 12.sp,
              fontWeight = FontWeight.SemiBold
            )
          }
        }

        Text(
          text = type.description,
          color = ThemeCyber.colors.textSecondary,
          fontSize = 12.5.sp,
          lineHeight = 17.sp
        )

        Text(
          text = "Access is strictly isolated to this exact domain and will be revoked automatically when you leave or close the page.",
          color = ThemeCyber.colors.textMuted,
          fontSize = 10.5.sp,
          lineHeight = 14.sp
        )
      }
    },
    confirmButton = {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        // 1. Allow Once
        Button(
          onClick = { onDecision(PermissionDecision.ALLOW_ONCE) },
          colors = ButtonDefaults.buttonColors(containerColor = accentColor),
          shape = RoundedCornerShape(8.dp),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("perm_btn_allow_once")
        ) {
          Text(
            text = "Allow Once",
            color = Color.Black,
            fontFamily = CyberMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
          )
        }

        // 2. Allow while using this session (disabled in Ghost mode for maximum isolation)
        if (!request.isGhost) {
          OutlinedButton(
            onClick = { onDecision(PermissionDecision.ALLOW_SESSION) },
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.6f)),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("perm_btn_allow_session")
          ) {
            Text(
              text = "Allow for This Session",
              color = accentColor,
              fontFamily = CyberMonoFamily,
              fontWeight = FontWeight.Bold,
              fontSize = 11.5.sp
            )
          }
        }

        // 3. Deny
        TextButton(
          onClick = { onDecision(PermissionDecision.DENY) },
          modifier = Modifier
            .fillMaxWidth()
            .testTag("perm_btn_deny")
        ) {
          Text(
            text = "Deny Access",
            color = ThemeCyber.colors.dangerRed,
            fontFamily = CyberMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 11.5.sp
          )
        }
      }
    }
  )
}
