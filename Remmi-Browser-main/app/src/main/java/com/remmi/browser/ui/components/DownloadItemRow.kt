package com.remmi.browser.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remmi.browser.downloads.DownloadFileType
import com.remmi.browser.downloads.DownloadProgressInfo
import com.remmi.browser.storage.DownloadItem
import com.remmi.browser.ui.theme.CyberMonoFamily
import com.remmi.browser.ui.theme.ThemeCyber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun DownloadItemRow(
  item: DownloadItem,
  isCurrentlyDownloading: Boolean,
  activeProgress: DownloadProgressInfo?,
  accentColor: Color,
  dateFormatter: SimpleDateFormat,
  formatFileSize: (Long) -> String,
  onPauseDownload: () -> Unit = {},
  onResumeDownload: () -> Unit = {},
  onCancelDownload: () -> Unit,
  onDeleteLog: () -> Unit,
  onDeleteDevice: () -> Unit,
  onOpen: () -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val isLight = ThemeCyber.colors.isLight
  var showMenu by remember { mutableStateOf(false) }
  var showInfoDialog by remember { mutableStateOf(false) }

  val fileType = remember(item.mimeType, item.fileName) {
    DownloadFileType.fromMimeOrFilename(item.mimeType, item.fileName)
  }
  val extensionBadge = remember(item.fileName) {
    DownloadFileType.getExtensionBadge(item.fileName)
  }
  val effectiveColor = fileType.getEffectiveColor(isLight)
  val effectiveBg = fileType.getEffectiveBackgroundColor(isLight)

  val isPaused = item.status == "PAUSED" || activeProgress?.status == "PAUSED"
  val isFailed = item.status == "FAILED"
  val isCancelled = item.status == "CANCELLED"
  val isActive = isCurrentlyDownloading && !isPaused

  // Pulsing animation for active downloads
  val infiniteTransition = rememberInfiniteTransition(label = "download_pulse")
  val pulseAlpha by infiniteTransition.animateFloat(
    initialValue = 0.4f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(800, easing = LinearEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "alpha_pulse"
  )

  val itemBorderColor = when {
    isActive -> effectiveColor.copy(alpha = pulseAlpha)
    isPaused -> ThemeCyber.colors.primary.copy(alpha = 0.6f)
    isFailed -> ThemeCyber.colors.dangerRed.copy(alpha = 0.4f)
    else -> ThemeCyber.colors.surfaceBorder
  }

  Surface(
    shape = RoundedCornerShape(12.dp),
    color = ThemeCyber.colors.surface,
    border = androidx.compose.foundation.BorderStroke(1.dp, itemBorderColor),
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .clickable(enabled = !isActive && !isPaused && !isFailed) { onOpen() }
      .testTag("download_item_${item.downloadId}")
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(10.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
      ) {
        // Category Icon with integrated bottom extension badge in favicon/logo box
        Box(
          modifier = Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(effectiveBg)
            .border(
              1.dp,
              effectiveColor.copy(alpha = if (isActive) pulseAlpha else 0.4f),
              RoundedCornerShape(10.dp)
            )
        ) {
          Icon(
            imageVector = fileType.icon,
            contentDescription = fileType.label,
            tint = effectiveColor,
            modifier = Modifier
              .size(22.dp)
              .align(Alignment.TopCenter)
              .padding(top = 4.dp)
          )

          // Sleek Extension Badge anchored at the bottom of the icon box
          Surface(
            color = effectiveColor.copy(alpha = if (isLight) 0.92f else 0.85f),
            shape = RoundedCornerShape(bottomStart = 9.dp, bottomEnd = 9.dp),
            modifier = Modifier
              .fillMaxWidth()
              .align(Alignment.BottomCenter)
          ) {
            Text(
              text = extensionBadge,
              color = if (isLight) Color.White else Color.Black,
              fontFamily = CyberMonoFamily,
              fontSize = 7.5.sp,
              fontWeight = FontWeight.Black,
              textAlign = TextAlign.Center,
              maxLines = 1,
              modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 1.dp)
            )
          }
        }

        Spacer(modifier = Modifier.width(10.dp))

        // Center Details (Filename, size, progress, speed)
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = item.fileName,
            color = ThemeCyber.colors.textPrimary,
            fontFamily = ThemeCyber.fontFamily,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )

          Spacer(modifier = Modifier.height(3.dp))

          // Subtitle stats / info
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
          ) {
            if (isActive && activeProgress != null) {
              // Live active download stats
              val currentBytes = activeProgress.bytesDownloaded
              val totalBytes = activeProgress.totalBytes
              val percent = activeProgress.progressPercent
              val speed = activeProgress.speedBytesPerSec

              Text(
                text = if (totalBytes > 0) {
                  "$percent% • ${formatFileSize(currentBytes)} / ${formatFileSize(totalBytes)}"
                } else {
                  "${formatFileSize(currentBytes)} downloaded"
                },
                color = effectiveColor,
                fontFamily = CyberMonoFamily,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
              )

              if (speed > 0) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                  text = "• ${formatFileSize(speed)}/s",
                  color = ThemeCyber.colors.neonCyan,
                  fontFamily = CyberMonoFamily,
                  fontSize = 9.5.sp,
                  fontWeight = FontWeight.SemiBold,
                  maxLines = 1,
                  softWrap = false
                )
              }
            } else if (isPaused) {
              // Paused state stats
              val currentBytes = activeProgress?.bytesDownloaded ?: item.fileSize
              val totalBytes = activeProgress?.totalBytes ?: item.fileSize
              Text(
                text = if (totalBytes > 0 && currentBytes > 0 && currentBytes < totalBytes) {
                  "PAUSED • ${formatFileSize(currentBytes)} / ${formatFileSize(totalBytes)}"
                } else {
                  "PAUSED • ${formatFileSize(currentBytes)}"
                },
                color = ThemeCyber.colors.primary,
                fontFamily = CyberMonoFamily,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
              )
            } else {
              // Completed / Recorded state
              val displaySize = if (item.fileSize > 0) formatFileSize(item.fileSize) else "Unknown size"
              Text(
                text = displaySize,
                color = ThemeCyber.colors.textSecondary,
                fontFamily = CyberMonoFamily,
                fontSize = 10.5.sp,
                maxLines = 1,
                softWrap = false
              )

              Spacer(modifier = Modifier.width(6.dp))

              Text(
                text = "• ${dateFormatter.format(Date(item.timestamp))}",
                color = ThemeCyber.colors.textMuted,
                fontFamily = CyberMonoFamily,
                fontSize = 9.5.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
              )

              if (isFailed) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                  text = "FAILED",
                  color = ThemeCyber.colors.dangerRed,
                  fontFamily = CyberMonoFamily,
                  fontSize = 9.sp,
                  fontWeight = FontWeight.Bold,
                  maxLines = 1,
                  softWrap = false
                )
              } else if (isCancelled) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                  text = "CANCELLED",
                  color = ThemeCyber.colors.textMuted,
                  fontFamily = CyberMonoFamily,
                  fontSize = 9.sp,
                  maxLines = 1,
                  softWrap = false
                )
              }
            }
          }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Action Buttons
        if (isActive || isPaused) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            if (isPaused) {
              // Resume Button - compact icon
              Surface(
                onClick = onResumeDownload,
                shape = RoundedCornerShape(8.dp),
                color = ThemeCyber.colors.neonCyan.copy(alpha = 0.15f),
                border = androidx.compose.foundation.BorderStroke(1.dp, ThemeCyber.colors.neonCyan.copy(alpha = 0.6f)),
                modifier = Modifier.size(32.dp)
              ) {
                Box(contentAlignment = Alignment.Center) {
                  Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Resume Download",
                    tint = ThemeCyber.colors.neonCyan,
                    modifier = Modifier.size(16.dp)
                  )
                }
              }
            } else {
              // Pause Button - compact icon
              Surface(
                onClick = onPauseDownload,
                shape = RoundedCornerShape(8.dp),
                color = ThemeCyber.colors.primary.copy(alpha = 0.15f),
                border = androidx.compose.foundation.BorderStroke(1.dp, ThemeCyber.colors.primary.copy(alpha = 0.5f)),
                modifier = Modifier.size(32.dp)
              ) {
                Box(contentAlignment = Alignment.Center) {
                  Icon(
                    imageVector = Icons.Default.Pause,
                    contentDescription = "Pause Download",
                    tint = ThemeCyber.colors.primary,
                    modifier = Modifier.size(16.dp)
                  )
                }
              }
            }

            // Cancel active download button - compact icon
            Surface(
              onClick = onCancelDownload,
              shape = RoundedCornerShape(8.dp),
              color = ThemeCyber.colors.dangerRed.copy(alpha = 0.12f),
              border = androidx.compose.foundation.BorderStroke(1.dp, ThemeCyber.colors.dangerRed.copy(alpha = 0.4f)),
              modifier = Modifier.size(32.dp)
            ) {
              Box(contentAlignment = Alignment.Center) {
                Icon(
                  imageVector = Icons.Default.Close,
                  contentDescription = "Cancel Download",
                  tint = ThemeCyber.colors.dangerRed,
                  modifier = Modifier.size(16.dp)
                )
              }
            }
          }
        } else {
          // Completed item: Play and Open buttons removed as requested, only options menu
          Box {
            IconButton(
              onClick = { showMenu = true },
              modifier = Modifier.size(32.dp)
            ) {
              Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "Options",
                tint = ThemeCyber.colors.textSecondary,
                modifier = Modifier.size(20.dp)
              )
            }

              DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                containerColor = ThemeCyber.colors.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder),
                shape = RoundedCornerShape(12.dp)
              ) {
                if (!isFailed && !isCancelled) {
                  DropdownMenuItem(
                    text = { Text("Open / View File", color = ThemeCyber.colors.textPrimary, fontFamily = CyberMonoFamily, fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Default.OpenInBrowser, null, tint = effectiveColor, modifier = Modifier.size(18.dp)) },
                    onClick = {
                      showMenu = false
                      onOpen()
                    }
                  )
                  DropdownMenuItem(
                    text = { Text("Share File", color = ThemeCyber.colors.textPrimary, fontFamily = CyberMonoFamily, fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Default.Share, null, tint = ThemeCyber.colors.primary, modifier = Modifier.size(18.dp)) },
                    onClick = {
                      showMenu = false
                      shareDownloadedFile(context, item)
                    }
                  )
                }

                DropdownMenuItem(
                  text = { Text("File Details & Integrity", color = ThemeCyber.colors.textPrimary, fontFamily = CyberMonoFamily, fontSize = 12.sp) },
                  leadingIcon = { Icon(Icons.Default.Info, null, tint = ThemeCyber.colors.textSecondary, modifier = Modifier.size(18.dp)) },
                  onClick = {
                    showMenu = false
                    showInfoDialog = true
                  }
                )

                if (item.url.isNotBlank()) {
                  DropdownMenuItem(
                    text = { Text("Copy Download Link", color = ThemeCyber.colors.textPrimary, fontFamily = CyberMonoFamily, fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, null, tint = ThemeCyber.colors.textSecondary, modifier = Modifier.size(18.dp)) },
                    onClick = {
                      showMenu = false
                      val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                      clipboard.setPrimaryClip(ClipData.newPlainText("URL", item.url))
                      Toast.makeText(context, "Download link copied", Toast.LENGTH_SHORT).show()
                    }
                  )
                }

                DropdownMenuItem(
                  text = { Text("Delete from List", color = ThemeCyber.colors.textPrimary, fontFamily = CyberMonoFamily, fontSize = 12.sp) },
                  leadingIcon = { Icon(Icons.Default.Delete, null, tint = ThemeCyber.colors.textSecondary, modifier = Modifier.size(18.dp)) },
                  onClick = {
                    showMenu = false
                    onDeleteLog()
                  }
                )

                if (item.filePath.isNotBlank()) {
                  DropdownMenuItem(
                    text = { Text("Delete from Device", color = ThemeCyber.colors.dangerRed, fontFamily = CyberMonoFamily, fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Default.DeleteForever, null, tint = ThemeCyber.colors.dangerRed, modifier = Modifier.size(18.dp)) },
                    onClick = {
                      showMenu = false
                      onDeleteDevice()
                    }
                  )
                }
              }
            }
          }
        }

      // Progress Bar when active or paused
      if (isActive || isPaused) {
        Spacer(modifier = Modifier.height(8.dp))
        val progressFraction = if (activeProgress != null && activeProgress.totalBytes > 0) {
          activeProgress.progressPercent / 100f
        } else if (item.fileSize > 0 && activeProgress?.bytesDownloaded != null) {
          (activeProgress.bytesDownloaded.toFloat() / item.fileSize.toFloat()).coerceIn(0f, 1f)
        } else {
          0f
        }

        if (activeProgress != null && activeProgress.totalBytes > 0) {
          LinearProgressIndicator(
            progress = { progressFraction },
            modifier = Modifier
              .fillMaxWidth()
              .height(5.dp)
              .clip(RoundedCornerShape(3.dp)),
            color = if (isPaused) ThemeCyber.colors.primary else effectiveColor,
            trackColor = effectiveBg,
            strokeCap = StrokeCap.Round
          )
        } else {
          LinearProgressIndicator(
            modifier = Modifier
              .fillMaxWidth()
              .height(5.dp)
              .clip(RoundedCornerShape(3.dp)),
            color = if (isPaused) ThemeCyber.colors.primary else effectiveColor,
            trackColor = effectiveBg,
            strokeCap = StrokeCap.Round
          )
        }
      }
    }
  }

  // File Details and SHA-256 Checksum Dialog
  if (showInfoDialog) {
    var fileSha256 by remember { mutableStateOf<String?>("Computing SHA-256...") }

    LaunchedEffect(item.filePath) {
      withContext(Dispatchers.IO) {
        try {
          val file = if (item.filePath.isNotBlank()) File(item.filePath) else null
          if (file != null && file.exists() && file.canRead()) {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
              val buffer = ByteArray(8192)
              var read: Int
              while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
              }
            }
            fileSha256 = digest.digest().joinToString("") { "%02x".format(it) }
          } else {
            fileSha256 = "Stored safely via Android MediaStore"
          }
        } catch (e: Exception) {
          fileSha256 = "Checksum unavailable: ${e.localizedMessage}"
        }
      }
    }

    AlertDialog(
      onDismissRequest = { showInfoDialog = false },
      containerColor = ThemeCyber.colors.surface,
      shape = RoundedCornerShape(16.dp),
      title = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = fileType.icon,
            contentDescription = null,
            tint = fileType.color,
            modifier = Modifier.size(20.dp)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            "File Details & Verification",
            color = ThemeCyber.colors.primary,
            fontFamily = CyberMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
          )
        }
      },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("File: ${item.fileName}", color = ThemeCyber.colors.textPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
          Text("Category: ${fileType.label} (${extensionBadge})", color = fileType.color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
          Text("Size: ${formatFileSize(item.fileSize)}", color = ThemeCyber.colors.textPrimary, fontSize = 12.sp)
          Text("MIME Type: ${item.mimeType.ifEmpty { "application/octet-stream" }}", color = ThemeCyber.colors.textPrimary, fontSize = 11.5.sp)
          Text("Date: ${dateFormatter.format(Date(item.timestamp))}", color = ThemeCyber.colors.textSecondary, fontSize = 11.5.sp)
          if (item.filePath.isNotBlank()) {
            Text("Storage URI: ${item.filePath}", color = ThemeCyber.colors.textMuted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
          }
          if (item.url.isNotBlank()) {
            Text("Source: ${item.url}", color = ThemeCyber.colors.textMuted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
          }

          Spacer(modifier = Modifier.height(4.dp))

          Surface(
            color = ThemeCyber.colors.background,
            border = androidx.compose.foundation.BorderStroke(0.8.dp, ThemeCyber.colors.surfaceBorder),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(modifier = Modifier.padding(10.dp)) {
              Text(
                text = "SHA-256 CHECKSUM / AIR-GAP HASH",
                color = ThemeCyber.colors.primary,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = CyberMonoFamily
              )
              Spacer(modifier = Modifier.height(3.dp))
              Text(
                text = fileSha256 ?: "Computing...",
                color = if (fileSha256 != null && fileSha256!!.length == 64) ThemeCyber.colors.neonCyan else ThemeCyber.colors.textSecondary,
                fontSize = 9.5.sp,
                fontFamily = CyberMonoFamily,
                lineHeight = 13.sp
              )
            }
          }
        }
      },
      confirmButton = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          if (fileSha256 != null && fileSha256!!.length == 64) {
            TextButton(
              onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("SHA-256", fileSha256))
                Toast.makeText(context, "SHA-256 Checksum copied", Toast.LENGTH_SHORT).show()
              }
            ) {
              Icon(Icons.Default.ContentCopy, contentDescription = null, tint = ThemeCyber.colors.primary, modifier = Modifier.size(14.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("Copy Hash", color = ThemeCyber.colors.primary, fontFamily = CyberMonoFamily, fontSize = 11.sp)
            }
          }
          TextButton(onClick = { showInfoDialog = false }) {
            Text("Close", color = ThemeCyber.colors.primary, fontFamily = CyberMonoFamily, fontSize = 11.sp)
          }
        }
      }
    )
  }
}

private fun shareDownloadedFile(context: Context, item: DownloadItem) {
  try {
    val uri = if (item.filePath.startsWith("content://") || item.filePath.startsWith("file://")) {
      android.net.Uri.parse(item.filePath)
    } else {
      val file = File(item.filePath)
      if (file.exists()) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
          androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } else {
          android.net.Uri.fromFile(file)
        }
      } else {
        Toast.makeText(context, "File path not directly shareable", Toast.LENGTH_SHORT).show()
        return
      }
    }

    val shareIntent = Intent(Intent.ACTION_SEND).apply {
      type = item.mimeType.ifEmpty { "*/*" }
      putExtra(Intent.EXTRA_STREAM, uri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(shareIntent, "Share ${item.fileName}"))
  } catch (e: Exception) {
    Toast.makeText(context, "Unable to share: ${e.message}", Toast.LENGTH_SHORT).show()
  }
}
