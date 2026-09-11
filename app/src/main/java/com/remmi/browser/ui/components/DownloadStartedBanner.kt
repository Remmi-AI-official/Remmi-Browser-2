package com.remmi.browser.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remmi.browser.downloads.DownloadEvent
import com.remmi.browser.downloads.DownloadFileType
import com.remmi.browser.ui.theme.CyberMonoFamily
import com.remmi.browser.ui.theme.ThemeCyber

@Composable
fun DownloadNotificationBanner(
  event: DownloadEvent?,
  onOpenDownloads: () -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier
) {
  AnimatedVisibility(
    visible = event != null,
    enter = slideInVertically(
      initialOffsetY = { -it },
      animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium)
    ) + fadeIn(),
    exit = slideOutVertically(
      targetOffsetY = { -it }
    ) + fadeOut(),
    modifier = modifier
  ) {
    if (event == null) return@AnimatedVisibility

    val isStarted = event is DownloadEvent.Started
    val isCompleted = event is DownloadEvent.Completed
    val isFailed = event is DownloadEvent.Failed

    val title = when (event) {
      is DownloadEvent.Started -> "DOWNLOAD STARTED"
      is DownloadEvent.Completed -> "DOWNLOAD COMPLETE"
      is DownloadEvent.Failed -> "DOWNLOAD FAILED"
    }

    val fileName = when (event) {
      is DownloadEvent.Started -> event.fileName
      is DownloadEvent.Completed -> event.fileName
      is DownloadEvent.Failed -> event.fileName
    }

    val fileType = when (event) {
      is DownloadEvent.Started -> event.fileType
      is DownloadEvent.Completed -> event.fileType
      is DownloadEvent.Failed -> DownloadFileType.OTHER
    }

    val isGhost = (event as? DownloadEvent.Started)?.isGhost ?: false

    val statusColor = when {
      isFailed -> ThemeCyber.colors.dangerRed
      isCompleted -> Color(0xFF10B981) // Emerald Green
      else -> fileType.color
    }

    val containerGradient = Brush.horizontalGradient(
      colors = listOf(
        ThemeCyber.colors.surface.copy(alpha = 0.96f),
        ThemeCyber.colors.surfaceLight.copy(alpha = 0.98f)
      )
    )

    Surface(
      shape = RoundedCornerShape(16.dp),
      color = Color.Transparent,
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 8.dp)
        .shadow(16.dp, RoundedCornerShape(16.dp), spotColor = statusColor.copy(alpha = 0.4f))
        .border(1.2.dp, statusColor.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
        .clip(RoundedCornerShape(16.dp))
        .background(containerGradient)
        .clickable {
          onOpenDownloads()
          onDismiss()
        }
        .testTag("download_started_banner")
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        // Category Icon with colored background
        Box(
          modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(fileType.backgroundColor)
            .border(1.dp, fileType.color.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = fileType.icon,
            contentDescription = null,
            tint = fileType.color,
            modifier = Modifier.size(22.dp)
          )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Text details
        Column(
          modifier = Modifier.weight(1f),
          verticalArrangement = Arrangement.Center
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = title,
              color = statusColor,
              fontFamily = CyberMonoFamily,
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold,
              letterSpacing = 0.5.sp
            )

            if (isGhost) {
              Spacer(modifier = Modifier.width(6.dp))
              Surface(
                color = ThemeCyber.colors.torPurple.copy(alpha = 0.2f),
                shape = RoundedCornerShape(4.dp),
                border = androidx.compose.foundation.BorderStroke(0.6.dp, ThemeCyber.colors.torPurple.copy(alpha = 0.6f))
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = ThemeCyber.colors.torPurple,
                    modifier = Modifier.size(10.dp)
                  )
                  Spacer(modifier = Modifier.width(2.dp))
                  Text(
                    text = "TOR",
                    color = ThemeCyber.colors.torPurple,
                    fontFamily = CyberMonoFamily,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Bold
                  )
                }
              }
            }
          }

          Spacer(modifier = Modifier.height(2.dp))

          Text(
            text = fileName,
            color = ThemeCyber.colors.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Open Downloads Action button
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = statusColor.copy(alpha = 0.15f),
          border = androidx.compose.foundation.BorderStroke(1.dp, statusColor.copy(alpha = 0.4f)),
          modifier = Modifier.clickable {
            onOpenDownloads()
            onDismiss()
          }
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "VIEW",
              color = statusColor,
              fontFamily = CyberMonoFamily,
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(3.dp))
            Icon(
              imageVector = Icons.Default.ArrowForward,
              contentDescription = "View",
              tint = statusColor,
              modifier = Modifier.size(12.dp)
            )
          }
        }

        Spacer(modifier = Modifier.width(4.dp))

        // Close icon button
        IconButton(
          onClick = onDismiss,
          modifier = Modifier.size(28.dp)
        ) {
          Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Dismiss",
            tint = ThemeCyber.colors.textMuted,
            modifier = Modifier.size(16.dp)
          )
        }
      }
    }
  }
}
