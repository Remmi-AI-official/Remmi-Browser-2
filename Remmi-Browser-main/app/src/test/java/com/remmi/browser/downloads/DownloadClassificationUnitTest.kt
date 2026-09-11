package com.remmi.browser.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadClassificationUnitTest {

  @Test
  fun testAudioClassification() {
    assertEquals(
      DownloadFileType.AUDIO,
      DownloadFileType.fromMimeOrFilename("audio/mpeg", "song.mp3")
    )
    assertEquals(
      DownloadFileType.AUDIO,
      DownloadFileType.fromMimeOrFilename(null, "podcast.aac")
    )
    assertEquals(
      DownloadFileType.AUDIO,
      DownloadFileType.fromMimeOrFilename("audio/flac", "audio_track")
    )
  }

  @Test
  fun testVideoClassification() {
    assertEquals(
      DownloadFileType.VIDEO,
      DownloadFileType.fromMimeOrFilename("video/mp4", "movie.mp4")
    )
    assertEquals(
      DownloadFileType.VIDEO,
      DownloadFileType.fromMimeOrFilename(null, "clip.mkv")
    )
    assertEquals(
      DownloadFileType.VIDEO,
      DownloadFileType.fromMimeOrFilename("video/webm", "video.webm")
    )
  }

  @Test
  fun testDocumentClassification() {
    assertEquals(
      DownloadFileType.DOCUMENT,
      DownloadFileType.fromMimeOrFilename("application/pdf", "document.pdf")
    )
    assertEquals(
      DownloadFileType.DOCUMENT,
      DownloadFileType.fromMimeOrFilename("application/msword", "report.docx")
    )
    assertEquals(
      DownloadFileType.DOCUMENT,
      DownloadFileType.fromMimeOrFilename(null, "sheet.xlsx")
    )
  }

  @Test
  fun testAppApkClassification() {
    assertEquals(
      DownloadFileType.APP,
      DownloadFileType.fromMimeOrFilename("application/vnd.android.package-archive", "app-release.apk")
    )
    assertEquals(
      DownloadFileType.APP,
      DownloadFileType.fromMimeOrFilename(null, "game.xapk")
    )
  }

  @Test
  fun testArchiveClassification() {
    assertEquals(
      DownloadFileType.ARCHIVE,
      DownloadFileType.fromMimeOrFilename("application/zip", "backup.zip")
    )
    assertEquals(
      DownloadFileType.ARCHIVE,
      DownloadFileType.fromMimeOrFilename(null, "files.7z")
    )
  }

  @Test
  fun testExtensionBadge() {
    assertEquals("MP4", DownloadFileType.getExtensionBadge("video.mp4"))
    assertEquals("PDF", DownloadFileType.getExtensionBadge("whitepaper.pdf"))
    assertEquals("APK", DownloadFileType.getExtensionBadge("browser.apk"))
    assertEquals("FILE", DownloadFileType.getExtensionBadge(""))
  }

  @Test
  fun testDownloadProgressCalculation() {
    val progress = DownloadProgressInfo(
      downloadId = 123L,
      fileName = "sample.mp4",
      url = "https://example.com/sample.mp4",
      bytesDownloaded = 50L,
      totalBytes = 100L,
      isGhost = false,
      status = "DOWNLOADING"
    )
    assertEquals(50, progress.progressPercent)
    assertEquals(false, progress.isIndeterminate)
  }
}
