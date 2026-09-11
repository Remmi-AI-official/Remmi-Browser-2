package com.remmi.browser.downloads

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

enum class DownloadFileType(
  val label: String,
  val icon: ImageVector,
  val color: Color,
  val backgroundColor: Color,
  val lightColor: Color = color,
  val lightBackgroundColor: Color = backgroundColor,
) {
  ALL(
    label = "All",
    icon = Icons.Default.InsertDriveFile,
    color = Color(0xFF00E5FF),
    backgroundColor = Color(0x1F00E5FF),
    lightColor = Color(0xFF1A73E8), // Rich Google Blue for Light Theme
    lightBackgroundColor = Color(0xFFE8F0FE)
  ),
  AUDIO(
    label = "Audio",
    icon = Icons.Default.MusicNote,
    color = Color(0xFFD946EF), // Vibrant Fuchsia
    backgroundColor = Color(0x24D946EF),
    lightColor = Color(0xFF9333EA), // Deep Purple for Light Theme
    lightBackgroundColor = Color(0xFFF3E8FF)
  ),
  VIDEO(
    label = "Video",
    icon = Icons.Default.Movie,
    color = Color(0xFFF97316), // Vibrant Amber/Orange
    backgroundColor = Color(0x24F97316),
    lightColor = Color(0xFFEA580C), // Deep Orange for Light Theme
    lightBackgroundColor = Color(0xFFFFEDD5)
  ),
  DOCUMENT(
    label = "Documents",
    icon = Icons.Default.Description,
    color = Color(0xFF3B82F6), // Electric Blue
    backgroundColor = Color(0x243B82F6),
    lightColor = Color(0xFF2563EB), // Deep Blue for Light Theme
    lightBackgroundColor = Color(0xFFDBEAFE)
  ),
  IMAGE(
    label = "Images",
    icon = Icons.Default.Image,
    color = Color(0xFF10B981), // Emerald Green
    backgroundColor = Color(0x2410B981),
    lightColor = Color(0xFF059669), // Deep Emerald for Light Theme
    lightBackgroundColor = Color(0xFFD1FAE5)
  ),
  APP(
    label = "APKs / Apps",
    icon = Icons.Default.Android,
    color = Color(0xFF14B8A6), // Teal / Cyan
    backgroundColor = Color(0x2414B8A6),
    lightColor = Color(0xFF0D9488), // Deep Teal for Light Theme
    lightBackgroundColor = Color(0xFFCCFBF1)
  ),
  ARCHIVE(
    label = "Archives",
    icon = Icons.Default.FolderZip,
    color = Color(0xFFF59E0B), // Warm Gold
    backgroundColor = Color(0x24F59E0B),
    lightColor = Color(0xFFD97706), // Deep Amber for Light Theme
    lightBackgroundColor = Color(0xFFFEF3C7)
  ),
  CODE(
    label = "Code",
    icon = Icons.Default.Code,
    color = Color(0xFF06B6D4), // Neon Cyan
    backgroundColor = Color(0x2406B6D4),
    lightColor = Color(0xFF0891B2), // Deep Cyan for Light Theme
    lightBackgroundColor = Color(0xFFCFFAFE)
  ),
  OTHER(
    label = "Other",
    icon = Icons.Default.InsertDriveFile,
    color = Color(0xFF94A3B8), // Slate Gray
    backgroundColor = Color(0x2494A3B8),
    lightColor = Color(0xFF475569), // Slate 600 for Light Theme
    lightBackgroundColor = Color(0xFFF1F5F9)
  );

  fun getEffectiveColor(isLight: Boolean): Color = if (isLight) lightColor else color
  fun getEffectiveBackgroundColor(isLight: Boolean): Color = if (isLight) lightBackgroundColor else backgroundColor

  companion object {
    fun fromMimeOrFilename(mimeType: String?, fileName: String?): DownloadFileType {
      val mime = mimeType?.lowercase() ?: ""
      val ext = fileName?.substringAfterLast('.', "")?.lowercase() ?: ""

      return when {
        // Audio
        mime.startsWith("audio/") || ext in listOf("mp3", "wav", "m4a", "aac", "flac", "ogg", "opus", "wma", "mid", "midi", "aiff", "alac") -> AUDIO

        // Video
        mime.startsWith("video/") || ext in listOf("mp4", "mkv", "webm", "avi", "mov", "flv", "3gp", "ts", "m4v", "wmv", "asf", "ogv", "vob") -> VIDEO

        // APK / Android package
        mime == "application/vnd.android.package-archive" || ext in listOf("apk", "xapk", "apkm", "aab") -> APP

        // Images
        mime.startsWith("image/") || ext in listOf("jpg", "jpeg", "png", "webp", "gif", "svg", "bmp", "avif", "heic", "ico", "tiff", "psd") -> IMAGE

        // Documents & PDFs
        mime == "application/pdf" || ext == "pdf" ||
        mime.contains("document") || mime.contains("msword") || mime.contains("wordprocessing") ||
        mime.contains("spreadsheet") || mime.contains("presentation") || mime.contains("text/plain") ||
        ext in listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "odt", "ods", "odp", "epub", "mobi", "csv", "tsv") -> DOCUMENT

        // Archives / Compressed
        mime.contains("zip") || mime.contains("compressed") || mime.contains("tar") || mime.contains("rar") || mime.contains("7z") ||
        ext in listOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "iso", "cab", "lzma") -> ARCHIVE

        // Code / Markup
        mime.contains("javascript") || mime.contains("json") || mime.contains("xml") || mime.contains("html") ||
        ext in listOf("html", "htm", "js", "ts", "json", "xml", "css", "py", "java", "kt", "c", "cpp", "h", "hpp", "sh", "sql", "yaml", "yml", "md") -> CODE

        else -> OTHER
      }
    }

    fun getExtensionBadge(fileName: String?): String {
      if (fileName.isNullOrBlank()) return "FILE"
      val ext = fileName.substringAfterLast('.', "").uppercase()
      return if (ext.isNotEmpty() && ext.length <= 5) ext else "FILE"
    }
  }
}
