package com.remmi.browser.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remmi.browser.security.permissions.ActivePermissionState
import com.remmi.browser.security.permissions.PermissionSessionManager
import com.remmi.browser.security.permissions.PermissionType
import com.remmi.browser.ui.theme.CyberMonoFamily
import com.remmi.browser.ui.theme.ThemeCyber

@Composable
fun ActivePermissionToolbarIndicator(
  state: ActivePermissionState?,
  modifier: Modifier = Modifier
) {
  if (state == null || !state.hasAnyActive) return

  var showInspectorDialog by remember { mutableStateOf(false) }

  val infiniteTransition = rememberInfiniteTransition(label = "indicator_pulse")
  val pulseAlpha by infiniteTransition.animateFloat(
    initialValue = 0.4f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(600, easing = LinearEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "pulse"
  )

  Surface(
    shape = RoundedCornerShape(12.dp),
    color = Color(0xFF10B981).copy(alpha = 0.15f),
    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = pulseAlpha)),
    modifier = modifier
      .clip(RoundedCornerShape(12.dp))
      .clickable { showInspectorDialog = true }
      .testTag("active_permission_indicator")
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Box(
        modifier = Modifier
          .size(7.dp)
          .clip(CircleShape)
          .background(Color(0xFF10B981))
      )

      Spacer(modifier = Modifier.width(4.dp))

      if (state.isCameraActive) {
        Icon(
          imageVector = Icons.Default.Videocam,
          contentDescription = "Camera In Use",
          tint = Color(0xFF10B981),
          modifier = Modifier.size(13.dp)
        )
      }
      if (state.isMicrophoneActive) {
        if (state.isCameraActive) Spacer(modifier = Modifier.width(3.dp))
        Icon(
          imageVector = Icons.Default.Mic,
          contentDescription = "Microphone In Use",
          tint = Color(0xFFEAB308),
          modifier = Modifier.size(13.dp)
        )
      }
      if (state.isGeolocationActive) {
        if (state.isCameraActive || state.isMicrophoneActive) Spacer(modifier = Modifier.width(3.dp))
        Icon(
          imageVector = Icons.Default.LocationOn,
          contentDescription = "Location In Use",
          tint = Color(0xFF3B82F6),
          modifier = Modifier.size(13.dp)
        )
      }
    }
  }

  if (showInspectorDialog) {
    ActivePermissionInspectorDialog(
      state = state,
      onDismiss = { showInspectorDialog = false }
    )
  }
}

@Composable
fun ActivePermissionInspectorDialog(
  state: ActivePermissionState,
  onDismiss: () -> Unit
) {
  val manager = remember { PermissionSessionManager.getInstance() }

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = ThemeCyber.colors.surface,
    shape = RoundedCornerShape(16.dp),
    title = {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
          modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(Color(0xFF10B981))
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "ACTIVE PERMISSIONS",
          color = ThemeCyber.colors.primary,
          fontFamily = CyberMonoFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 14.sp
        )
      }
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
          color = ThemeCyber.colors.background,
          shape = RoundedCornerShape(8.dp),
          border = androidx.compose.foundation.BorderStroke(0.8.dp, ThemeCyber.colors.surfaceBorder),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(10.dp)) {
            Text(
              text = "SITE ORIGIN",
              color = ThemeCyber.colors.textMuted,
              fontFamily = CyberMonoFamily,
              fontSize = 9.5.sp,
              fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
              text = state.origin,
              color = ThemeCyber.colors.textPrimary,
              fontFamily = CyberMonoFamily,
              fontSize = 12.sp,
              fontWeight = FontWeight.SemiBold
            )
          }
        }

        if (state.isCameraActive) {
          PermissionControlRow(
            title = "Camera Active",
            icon = Icons.Default.Videocam,
            color = ThemeCyber.colors.neonCyan,
            onStop = {
              manager.revokePermission(state.tabId, PermissionType.CAMERA)
              if (!state.isMicrophoneActive && !state.isGeolocationActive) {
                onDismiss()
              }
            }
          )
        }

        if (state.isMicrophoneActive) {
          PermissionControlRow(
            title = "Microphone Active",
            icon = Icons.Default.Mic,
            color = Color(0xFFEAB308),
            onStop = {
              manager.revokePermission(state.tabId, PermissionType.MICROPHONE)
              if (!state.isCameraActive && !state.isGeolocationActive) {
                onDismiss()
              }
            }
          )
        }

        if (state.isGeolocationActive) {
          PermissionControlRow(
            title = "Location Active",
            icon = Icons.Default.LocationOn,
            color = Color(0xFF3B82F6),
            onStop = {
              manager.revokePermission(state.tabId, PermissionType.GEOLOCATION)
              if (!state.isCameraActive && !state.isMicrophoneActive) {
                onDismiss()
              }
            }
          )
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          if (state.isCameraActive && state.isMicrophoneActive) {
            manager.revokePermission(state.tabId, PermissionType.CAMERA_AND_MICROPHONE)
          } else if (state.isCameraActive) {
            manager.revokePermission(state.tabId, PermissionType.CAMERA)
          } else if (state.isMicrophoneActive) {
            manager.revokePermission(state.tabId, PermissionType.MICROPHONE)
          }
          if (state.isGeolocationActive) {
            manager.revokePermission(state.tabId, PermissionType.GEOLOCATION)
          }
          onDismiss()
        },
        colors = ButtonDefaults.buttonColors(containerColor = ThemeCyber.colors.dangerRed),
        shape = RoundedCornerShape(8.dp)
      ) {
        Icon(Icons.Default.StopCircle, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text("Stop All Access", fontFamily = CyberMonoFamily, fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Close", color = ThemeCyber.colors.textSecondary, fontFamily = CyberMonoFamily)
      }
    }
  )
}

@Composable
private fun PermissionControlRow(
  title: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  color: Color,
  onStop: () -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .background(ThemeCyber.colors.surfaceLight)
      .padding(horizontal = 10.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
      Spacer(modifier = Modifier.width(8.dp))
      Text(text = title, color = ThemeCyber.colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }

    Surface(
      shape = RoundedCornerShape(6.dp),
      color = ThemeCyber.colors.dangerRed.copy(alpha = 0.15f),
      border = androidx.compose.foundation.BorderStroke(1.dp, ThemeCyber.colors.dangerRed.copy(alpha = 0.5f)),
      modifier = Modifier.clickable { onStop() }
    ) {
      Text(
        text = "STOP",
        color = ThemeCyber.colors.dangerRed,
        fontFamily = CyberMonoFamily,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
      )
    }
  }
}
