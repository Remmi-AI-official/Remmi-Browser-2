package com.remmi.browser.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.remmi.browser.downloads.DownloadConfirmationRequest
import com.remmi.browser.downloads.DownloadFileType
import com.remmi.browser.downloads.DownloadHandler
import com.remmi.browser.ui.theme.CyberMonoFamily
import com.remmi.browser.ui.theme.ThemeCyber

@Composable
fun DownloadConfirmDialog(
  request: DownloadConfirmationRequest,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val downloadHandler = remember { DownloadHandler.getInstance(context) }
  var filenameInput by remember(request.suggestedFilename) {
    mutableStateOf(request.suggestedFilename)
  }

  val fileType = remember(filenameInput, request.mimeType) {
    DownloadFileType.fromMimeOrFilename(request.mimeType, filenameInput)
  }
  val isLight = ThemeCyber.colors.isLight
  val typeColor = fileType.getEffectiveColor(isLight)
  val typeBgColor = fileType.getEffectiveBackgroundColor(isLight)
  val extensionBadge = remember(filenameInput) {
    DownloadFileType.getExtensionBadge(filenameInput)
  }

  val domain = remember(request.url) {
    try {
      val uri = android.net.Uri.parse(request.url)
      uri.host ?: request.url.substringBefore('?').take(40)
    } catch (_: Exception) {
      request.url.substringBefore('?').take(40)
    }
  }

  val isDangerousType = remember(filenameInput) {
    val ext = filenameInput.substringAfterLast('.', "").lowercase()
    ext in listOf("apk", "xapk", "exe", "bat", "cmd", "sh", "bin", "dmg", "pkg", "msi", "vbs", "jar", "dex")
  }

  AlertDialog(
    onDismissRequest = {
      request.onCancel()
      onDismiss()
    },
    properties = DialogProperties(
      dismissOnBackPress = true,
      dismissOnClickOutside = true,
      usePlatformDefaultWidth = false
    ),
    modifier = modifier
      .padding(horizontal = 24.dp)
      .fillMaxWidth()
      .testTag("download_confirm_dialog"),
    shape = RoundedCornerShape(20.dp),
    containerColor = ThemeCyber.colors.surface,
    title = {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            modifier = Modifier
              .size(36.dp)
              .clip(CircleShape)
              .background(ThemeCyber.colors.primary.copy(alpha = 0.15f))
              .border(1.dp, ThemeCyber.colors.primary.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Default.Download,
              contentDescription = null,
              tint = ThemeCyber.colors.primary,
              modifier = Modifier.size(20.dp)
            )
          }
          Spacer(modifier = Modifier.width(10.dp))
          Text(
            text = "DOWNLOAD FILE",
            fontFamily = CyberMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = ThemeCyber.colors.textPrimary
          )
        }
        IconButton(
          onClick = {
            request.onCancel()
            onDismiss()
          },
          modifier = Modifier.size(32.dp)
        ) {
          Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Close",
            tint = ThemeCyber.colors.textSecondary,
            modifier = Modifier.size(18.dp)
          )
        }
      }
    },
    text = {
      Column(modifier = Modifier.fillMaxWidth()) {
        // 1. File Type Preview Card
        Card(
          shape = RoundedCornerShape(14.dp),
          colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surfaceLight),
          border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder.copy(alpha = 0.6f)),
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Box(
              modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(typeBgColor)
                .border(1.dp, typeColor.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
              contentAlignment = Alignment.Center
            ) {
              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
              ) {
                Icon(
                  imageVector = fileType.icon,
                  contentDescription = null,
                  tint = typeColor,
                  modifier = Modifier.size(22.dp)
                )
                Text(
                  text = extensionBadge,
                  color = typeColor,
                  fontSize = 8.sp,
                  fontWeight = FontWeight.Black,
                  fontFamily = CyberMonoFamily
                )
              }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = fileType.label,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = typeColor,
                fontFamily = CyberMonoFamily
              )
              Text(
                text = filenameInput.ifBlank { "unnamed_file" },
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = ThemeCyber.colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
              )
              Spacer(modifier = Modifier.height(2.dp))
              Text(
                text = if (request.contentLength > 0) {
                  downloadHandler.formatBytes(request.contentLength)
                } else {
                  "Unknown size (Stream)"
                },
                fontSize = 11.sp,
                fontFamily = CyberMonoFamily,
                color = ThemeCyber.colors.textSecondary
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 2. Editable Filename Field
        Text(
          text = "FILE NAME",
          fontSize = 10.sp,
          fontFamily = CyberMonoFamily,
          fontWeight = FontWeight.Bold,
          color = ThemeCyber.colors.textSecondary,
          modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )
        OutlinedTextField(
          value = filenameInput,
          onValueChange = { filenameInput = it },
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("download_filename_input"),
          shape = RoundedCornerShape(10.dp),
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = ThemeCyber.colors.primary,
            unfocusedBorderColor = ThemeCyber.colors.surfaceBorder,
            focusedTextColor = ThemeCyber.colors.textPrimary,
            unfocusedTextColor = ThemeCyber.colors.textPrimary,
            focusedContainerColor = ThemeCyber.colors.surfaceLight,
            unfocusedContainerColor = ThemeCyber.colors.surfaceLight,
          ),
          textStyle = androidx.compose.ui.text.TextStyle(
            fontSize = 13.sp,
            fontFamily = CyberMonoFamily
          )
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 3. Metadata Details (Origin and Privacy Route)
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = ThemeCyber.colors.surfaceLight.copy(alpha = 0.7f),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            // Origin Row
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(
                imageVector = Icons.Default.Language,
                contentDescription = null,
                tint = ThemeCyber.colors.textSecondary,
                modifier = Modifier.size(14.dp)
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = "Source: $domain",
                fontSize = 11.sp,
                fontFamily = CyberMonoFamily,
                color = ThemeCyber.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Route Row
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(
                imageVector = if (request.isGhost) Icons.Default.Security else Icons.Default.Shield,
                contentDescription = null,
                tint = if (request.isGhost) ThemeCyber.colors.torPurple else ThemeCyber.colors.primary,
                modifier = Modifier.size(14.dp)
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = if (request.isGhost) "Ghost Mode (Encrypted Tor Circuit)" else "Shield Mode (Ad/Tracker Shielded)",
                fontSize = 11.sp,
                fontFamily = CyberMonoFamily,
                color = if (request.isGhost) ThemeCyber.colors.torPurple else ThemeCyber.colors.primary,
              )
            }
          }
        }

        // 4. Executable / APK Security Warning
        if (isDangerousType) {
          Spacer(modifier = Modifier.height(10.dp))
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = ThemeCyber.colors.warningYellow.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, ThemeCyber.colors.warningYellow.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
          ) {
            Row(
              modifier = Modifier.padding(8.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Warning",
                tint = ThemeCyber.colors.warningYellow,
                modifier = Modifier.size(18.dp)
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = "Caution: Executable/Package file. Only download if you trust this website.",
                fontSize = 10.5.sp,
                fontFamily = CyberMonoFamily,
                color = ThemeCyber.colors.warningYellow,
                lineHeight = 14.sp
              )
            }
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          val cleanName = filenameInput.trim().ifEmpty { request.suggestedFilename }
          request.onConfirm(cleanName)
          onDismiss()
        },
        colors = ButtonDefaults.buttonColors(
          containerColor = if (request.isGhost) ThemeCyber.colors.torPurple else ThemeCyber.colors.primary
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
          .height(44.dp)
          .testTag("download_confirm_button")
      ) {
        Icon(
          imageVector = Icons.Default.Download,
          contentDescription = null,
          tint = Color.White,
          modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text = "DOWNLOAD",
          color = Color.White,
          fontWeight = FontWeight.Bold,
          fontFamily = CyberMonoFamily,
          fontSize = 13.sp
        )
      }
    },
    dismissButton = {
      OutlinedButton(
        onClick = {
          request.onCancel()
          onDismiss()
        },
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder),
        modifier = Modifier
          .height(44.dp)
          .testTag("download_cancel_button")
      ) {
        Text(
          text = "CANCEL",
          color = ThemeCyber.colors.textSecondary,
          fontFamily = CyberMonoFamily,
          fontSize = 12.sp
        )
      }
    }
  )
}
