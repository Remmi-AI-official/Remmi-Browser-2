package com.remmi.browser.ui.components

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remmi.browser.downloads.DownloadFileType
import com.remmi.browser.downloads.DownloadHandler
import com.remmi.browser.storage.DownloadItem
import com.remmi.browser.ui.theme.CyberMonoFamily
import com.remmi.browser.ui.theme.ThemeCyber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun DownloadsDrawer(
  downloadsList: List<DownloadItem>,
  onDeleteDownload: (DownloadItem) -> Unit,
  onClearAll: () -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val downloadHandler = remember { DownloadHandler.getInstance(context) }
  val activeDownloads by downloadHandler.activeDownloads.collectAsState()
  val scope = rememberCoroutineScope()
  val dateFormatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
  val accentColor = ThemeCyber.colors.primary

  var searchQuery by remember { mutableStateOf("") }
  var selectedCategory by remember { mutableStateOf<DownloadFileType?>(null) }
  var showClearConfirmDialog by remember { mutableStateOf(false) }

  val displayList = remember(downloadsList) {
    downloadsList.distinctBy { if (it.downloadId != 0L) it.downloadId else it.fileName + "_" + it.timestamp }
  }

  val activeDownloadingList = remember(activeDownloads) {
    activeDownloads.values.filter { it.status == "DOWNLOADING" }
  }
  val activePausedList = remember(activeDownloads) {
    activeDownloads.values.filter { it.status == "PAUSED" }
  }
  val totalActiveCount = activeDownloadingList.size
  val totalPausedCount = activePausedList.size

  // Calculate total consumed storage size
  val totalStorageBytes = remember(displayList) {
    displayList.sumOf { it.fileSize }
  }

  // Count items per category
  val categoryCounts = remember(displayList) {
    val counts = mutableMapOf<DownloadFileType, Int>()
    displayList.forEach { item ->
      val type = DownloadFileType.fromMimeOrFilename(item.mimeType, item.fileName)
      counts[type] = (counts[type] ?: 0) + 1
    }
    counts
  }

  // Filter list by category and search query
  val filteredList = remember(displayList, selectedCategory, searchQuery, activeDownloads) {
    displayList.filter { item ->
      val activeProgress = activeDownloads[item.downloadId]
      
      val matchesCategory = when (selectedCategory) {
        null, DownloadFileType.ALL -> true
        else -> {
          val type = DownloadFileType.fromMimeOrFilename(item.mimeType, item.fileName)
          type == selectedCategory
        }
      }

      val matchesQuery = if (searchQuery.isBlank()) {
        true
      } else {
        item.fileName.contains(searchQuery, ignoreCase = true) ||
          item.url.contains(searchQuery, ignoreCase = true) ||
          item.mimeType.contains(searchQuery, ignoreCase = true)
      }

      matchesCategory && matchesQuery
    }
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(ThemeCyber.colors.background)
      .padding(16.dp)
  ) {
    // 1. Top Action Header
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.weight(1f, fill = false)
      ) {
        Box(
          modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(accentColor.copy(alpha = 0.15f))
            .border(1.dp, accentColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = Icons.Default.Download,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(20.dp)
          )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Text(
              text = "DOWNLOAD HUB",
              color = accentColor,
              fontFamily = CyberMonoFamily,
              fontSize = 14.sp,
              fontWeight = FontWeight.Bold,
              letterSpacing = 0.5.sp
            )

            if (totalActiveCount > 0) {
              Surface(
                color = ThemeCyber.colors.neonCyan.copy(alpha = 0.15f),
                shape = RoundedCornerShape(6.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, ThemeCyber.colors.neonCyan.copy(alpha = 0.5f))
              ) {
                Text(
                  text = "$totalActiveCount ACTIVE",
                  color = ThemeCyber.colors.neonCyan,
                  fontFamily = CyberMonoFamily,
                  fontSize = 8.5.sp,
                  fontWeight = FontWeight.Bold,
                  modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                )
              }
            } else if (totalPausedCount > 0) {
              Surface(
                color = ThemeCyber.colors.primary.copy(alpha = 0.15f),
                shape = RoundedCornerShape(6.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, ThemeCyber.colors.primary.copy(alpha = 0.5f))
              ) {
                Text(
                  text = "$totalPausedCount PAUSED",
                  color = ThemeCyber.colors.primary,
                  fontFamily = CyberMonoFamily,
                  fontSize = 8.5.sp,
                  fontWeight = FontWeight.Bold,
                  modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                )
              }
            }
          }

          Spacer(modifier = Modifier.height(2.dp))

          Text(
            text = "${displayList.size} files • ${formatFileSize(totalStorageBytes)}",
            color = ThemeCyber.colors.textMuted,
            fontFamily = CyberMonoFamily,
            fontSize = 10.5.sp
          )
        }
      }

      Spacer(modifier = Modifier.width(8.dp))

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        // Open system downloads folder
        IconButton(
          onClick = { openSystemDownloadsFolder(context) },
          modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(ThemeCyber.colors.surface)
            .border(1.dp, ThemeCyber.colors.surfaceBorder, RoundedCornerShape(8.dp))
            .size(36.dp)
            .testTag("open_system_downloads_button")
        ) {
          Icon(
            imageVector = Icons.Default.FolderOpen,
            contentDescription = "System Downloads",
            tint = ThemeCyber.colors.primary,
            modifier = Modifier.size(18.dp)
          )
        }

        if (displayList.isNotEmpty()) {
          IconButton(
            onClick = { showClearConfirmDialog = true },
            modifier = Modifier
              .clip(RoundedCornerShape(8.dp))
              .background(ThemeCyber.colors.surface)
              .border(1.dp, ThemeCyber.colors.surfaceBorder, RoundedCornerShape(8.dp))
              .size(36.dp)
              .testTag("clear_downloads_button")
          ) {
            Icon(
              imageVector = Icons.Default.Delete,
              contentDescription = "Clear All",
              tint = ThemeCyber.colors.dangerRed,
              modifier = Modifier.size(18.dp)
            )
          }
        }

        IconButton(
          onClick = onDismiss,
          modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(ThemeCyber.colors.surface)
            .border(1.dp, ThemeCyber.colors.surfaceBorder, RoundedCornerShape(8.dp))
            .size(36.dp)
            .testTag("close_downloads_button")
        ) {
          Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Close",
            tint = ThemeCyber.colors.textPrimary,
            modifier = Modifier.size(18.dp)
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // 2. Active Transfers Banner (if any downloads in progress)
    if (totalActiveCount > 0) {
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = ThemeCyber.colors.surface,
        border = androidx.compose.foundation.BorderStroke(1.2.dp, ThemeCyber.colors.neonCyan.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
              modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(ThemeCyber.colors.neonCyan)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "$totalActiveCount file(s) downloading in background",
              color = ThemeCyber.colors.textPrimary,
              fontSize = 12.sp,
              fontWeight = FontWeight.SemiBold
            )
          }

          TextButton(
            onClick = {
              scope.launch { downloadHandler.cancelAllDownloads() }
            }
          ) {
            Text(
              text = "Cancel All",
              color = ThemeCyber.colors.dangerRed,
              fontFamily = CyberMonoFamily,
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold
            )
          }
        }
      }
      Spacer(modifier = Modifier.height(10.dp))
    }

    // 3. Search Bar
    OutlinedTextField(
      value = searchQuery,
      onValueChange = { searchQuery = it },
      placeholder = {
        Text(
          "Search downloads by name, extension...",
          color = ThemeCyber.colors.textMuted,
          fontSize = 12.5.sp
        )
      },
      leadingIcon = {
        Icon(
          imageVector = Icons.Default.Search,
          contentDescription = null,
          tint = ThemeCyber.colors.textSecondary,
          modifier = Modifier.size(18.dp)
        )
      },
      trailingIcon = {
        if (searchQuery.isNotEmpty()) {
          IconButton(onClick = { searchQuery = "" }) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = "Clear",
              tint = ThemeCyber.colors.textSecondary,
              modifier = Modifier.size(16.dp)
            )
          }
        }
      },
      singleLine = true,
      colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = accentColor,
        unfocusedBorderColor = ThemeCyber.colors.surfaceBorder,
        focusedContainerColor = ThemeCyber.colors.surface,
        unfocusedContainerColor = ThemeCyber.colors.surface,
        focusedTextColor = ThemeCyber.colors.textPrimary,
        unfocusedTextColor = ThemeCyber.colors.textPrimary
      ),
      shape = RoundedCornerShape(10.dp),
      modifier = Modifier
        .fillMaxWidth()
        .height(48.dp)
        .testTag("downloads_search_input")
    )

    Spacer(modifier = Modifier.height(10.dp))

    // 4. Category Filter Chips (Horizontal Scrollable)
    val isLightTheme = ThemeCyber.colors.isLight
    val categories = listOf(
      DownloadFileType.ALL,
      DownloadFileType.AUDIO,
      DownloadFileType.VIDEO,
      DownloadFileType.DOCUMENT,
      DownloadFileType.IMAGE,
      DownloadFileType.APP,
      DownloadFileType.ARCHIVE,
      DownloadFileType.CODE,
      DownloadFileType.OTHER
    )

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      categories.forEach { cat ->
        val count = if (cat == DownloadFileType.ALL) displayList.size else (categoryCounts[cat] ?: 0)
        val isSelected = (selectedCategory == null && cat == DownloadFileType.ALL) || (selectedCategory == cat)
        val effectiveColor = cat.getEffectiveColor(isLightTheme)
        val effectiveBg = cat.getEffectiveBackgroundColor(isLightTheme)

        Surface(
          shape = RoundedCornerShape(20.dp),
          color = if (isSelected) {
            if (isLightTheme) effectiveBg else effectiveColor.copy(alpha = 0.22f)
          } else {
            ThemeCyber.colors.surface
          },
          border = androidx.compose.foundation.BorderStroke(
            if (isSelected) 1.5.dp else 1.dp,
            if (isSelected) effectiveColor else ThemeCyber.colors.surfaceBorder
          ),
          modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable {
              selectedCategory = if (cat == DownloadFileType.ALL) null else cat
            }
            .testTag("filter_chip_${cat.name.lowercase()}")
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(
              imageVector = cat.icon,
              contentDescription = null,
              tint = if (isSelected) effectiveColor else ThemeCyber.colors.textSecondary,
              modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = "${cat.label} ($count)",
              color = if (isSelected) effectiveColor else ThemeCyber.colors.textPrimary,
              fontFamily = CyberMonoFamily,
              fontSize = 11.5.sp,
              fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
            )
          }
        }
      }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // 5. Downloads List or Empty State
    if (filteredList.isEmpty() && activeDownloadingList.isEmpty()) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f),
        contentAlignment = Alignment.Center,
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier.padding(24.dp)
        ) {
          Box(
            modifier = Modifier
              .size(64.dp)
              .clip(CircleShape)
              .background(ThemeCyber.colors.surface)
              .border(1.dp, ThemeCyber.colors.surfaceBorder, CircleShape),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Default.InsertDriveFile,
              contentDescription = null,
              tint = ThemeCyber.colors.primary.copy(alpha = 0.6f),
              modifier = Modifier.size(32.dp)
            )
          }

          Spacer(modifier = Modifier.height(14.dp))

          Text(
            text = if (searchQuery.isNotBlank() || selectedCategory != null) "NO MATCHING DOWNLOADS" else "NO DOWNLOADS RECORDED",
            color = ThemeCyber.colors.textPrimary,
            fontFamily = CyberMonoFamily,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
          )

          Spacer(modifier = Modifier.height(6.dp))

          Text(
            text = if (searchQuery.isNotBlank() || selectedCategory != null) {
              "Try changing your search term or category filter."
            } else {
              "Downloaded audio, video, documents, and media will be stored air-gapped on your device."
            },
            color = ThemeCyber.colors.textMuted,
            fontSize = 11.5.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 20.dp)
          )
        }
      }
    } else {
      LazyColumn(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
      ) {
        items(filteredList, key = { it.downloadId }) { item ->
          val activeProgress = activeDownloads[item.downloadId]
          val isCurrentlyDownloading = item.status == "DOWNLOADING" || item.status == "PAUSED" || activeProgress != null

          DownloadItemRow(
            item = item,
            isCurrentlyDownloading = isCurrentlyDownloading,
            activeProgress = activeProgress,
            accentColor = accentColor,
            dateFormatter = dateFormatter,
            formatFileSize = { formatFileSize(it) },
            onPauseDownload = {
              scope.launch { downloadHandler.pauseDownload(item.downloadId) }
            },
            onResumeDownload = {
              scope.launch { downloadHandler.resumeDownload(item.downloadId) }
            },
            onCancelDownload = { 
              scope.launch { downloadHandler.cancelDownload(item.downloadId) } 
            },
            onDeleteLog = { onDeleteDownload(item) },
            onDeleteDevice = {
              try {
                val uri = android.net.Uri.parse(item.filePath)
                context.contentResolver.delete(uri, null, null)
                onDeleteDownload(item)
                Toast.makeText(context, "Deleted from device", Toast.LENGTH_SHORT).show()
              } catch (e: Exception) {
                try {
                  val file = File(item.filePath)
                  if (file.exists() && file.delete()) {
                    onDeleteDownload(item)
                    Toast.makeText(context, "Deleted from device", Toast.LENGTH_SHORT).show()
                  } else {
                    Toast.makeText(context, "Failed to delete file", Toast.LENGTH_SHORT).show()
                  }
                } catch (e2: Exception) {
                  Toast.makeText(context, "Failed to delete: ${e2.message}", Toast.LENGTH_SHORT).show()
                }
              }
            },
            onOpen = { openDownloadedFile(context, item) }
          )
        }
      }
    }
  }

  // Clear All Confirmation Dialog
  if (showClearConfirmDialog) {
    AlertDialog(
      onDismissRequest = { showClearConfirmDialog = false },
      containerColor = ThemeCyber.colors.surface,
      shape = RoundedCornerShape(16.dp),
      title = {
        Text(
          "Clear Download History?",
          color = ThemeCyber.colors.dangerRed,
          fontFamily = CyberMonoFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 15.sp
        )
      },
      text = {
        Text(
          "This will clear all download logs from your browser history. Files stored in your device's Downloads directory will not be deleted.",
          color = ThemeCyber.colors.textPrimary,
          fontSize = 12.5.sp
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            showClearConfirmDialog = false
            onClearAll()
          }
        ) {
          Text("Clear All", color = ThemeCyber.colors.dangerRed, fontFamily = CyberMonoFamily, fontWeight = FontWeight.Bold)
        }
      },
      dismissButton = {
        TextButton(onClick = { showClearConfirmDialog = false }) {
          Text("Cancel", color = ThemeCyber.colors.textSecondary, fontFamily = CyberMonoFamily)
        }
      }
    )
  }
}

private fun formatFileSize(bytes: Long): String {
  if (bytes <= 0) return "0 B"
  val kb = bytes / 1024.0
  val mb = kb / 1024.0
  val gb = mb / 1024.0
  return when {
    gb >= 1.0 -> String.format(Locale.US, "%.1f GB", gb)
    mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
    kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
    else -> "$bytes B"
  }
}

private fun openSystemDownloadsFolder(context: Context) {
  try {
    val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    context.startActivity(intent)
  } catch (e: Exception) {
    Toast.makeText(context, "System Downloads folder opened", Toast.LENGTH_SHORT).show()
  }
}

private fun openDownloadedFile(context: Context, item: DownloadItem) {
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
        Toast.makeText(context, "File located at: ${item.filePath}", Toast.LENGTH_SHORT).show()
        return
      }
    }

    val intent = Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(uri, item.mimeType.ifEmpty { "*/*" })
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
    }
    context.startActivity(Intent.createChooser(intent, "Open with"))
  } catch (e: Exception) {
    Toast.makeText(context, "No app found to open this file type: ${e.message}", Toast.LENGTH_SHORT).show()
  }
}
