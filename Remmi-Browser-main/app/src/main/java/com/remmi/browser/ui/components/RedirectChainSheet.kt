package com.remmi.browser.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.remmi.browser.engine.chain.ChainStatus
import com.remmi.browser.engine.chain.HopType
import com.remmi.browser.engine.chain.NavigationChain
import com.remmi.browser.engine.chain.NavigationHop
import com.remmi.browser.security.ClipboardManager
import com.remmi.browser.ui.theme.CyberMonoFamily
import com.remmi.browser.ui.theme.ThemeCyber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RedirectChainSheet(
  chain: NavigationChain?,
  onDismiss: () -> Unit,
  onOpenUrl: (String) -> Unit,
  onOpenInNewTab: ((String) -> Unit)? = null,
  onInspectDeepRedirects: ((String) -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val clipboard = remember { ClipboardManager(context) }
  val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = ThemeCyber.colors.surface,
    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    dragHandle = {
      Box(
        modifier = Modifier
          .padding(top = 12.dp, bottom = 8.dp)
          .size(width = 40.dp, height = 4.dp)
          .clip(RoundedCornerShape(2.dp))
          .background(ThemeCyber.colors.surfaceBorder)
      )
    },
    modifier = modifier.testTag("redirect_chain_sheet"),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp)
        .padding(bottom = 32.dp)
    ) {
      // Header
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
      ) {
        Box(
          modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(ThemeCyber.colors.primary.copy(alpha = 0.15f)),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = Icons.Default.AltRoute,
            contentDescription = null,
            tint = ThemeCyber.colors.primary,
            modifier = Modifier.size(20.dp)
          )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "NAVIGATION REDIRECT CHAIN",
            fontFamily = ThemeCyber.fontFamily,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Bold,
            color = ThemeCyber.colors.textPrimary
          )
          Text(
            text = if (chain != null) "Chain #${chain.chainNavId} • ${chain.hops.size} Hop${if (chain.hops.size > 1) "s" else ""}" else "No active navigation chain",
            fontSize = 10.5.sp,
            fontFamily = CyberMonoFamily,
            color = ThemeCyber.colors.primary
          )
        }
        if (chain != null && chain.hops.isNotEmpty()) {
          IconButton(
            onClick = {
              val summary = buildString {
                appendLine("--- Navigation Chain #${chain.chainNavId} ---")
                appendLine("Status: ${chain.status}")
                appendLine("Source: ${chain.sourceUrl}")
                appendLine("Current/Final: ${chain.currentUrl}")
                if (chain.terminalDownloadUrl != null) {
                  appendLine("Download: ${chain.terminalDownloadUrl}")
                }
                appendLine("Hops (${chain.hops.size}):")
                chain.hops.forEach { h ->
                  appendLine("[${h.hopIndex}] ${h.hopType} -> ${h.uri}")
                }
              }
              clipboard.copy(summary, "Redirect Chain #${chain.chainNavId}")
              Toast.makeText(context, "Copied chain trace to clipboard", Toast.LENGTH_SHORT).show()
            }
          ) {
            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Chain", tint = ThemeCyber.colors.textMuted)
          }
        }
        IconButton(onClick = onDismiss) {
          Icon(Icons.Default.Close, contentDescription = "Close", tint = ThemeCyber.colors.textMuted)
        }
      }

      if (chain == null || chain.hops.isEmpty()) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
          contentAlignment = Alignment.Center
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
              Icons.Default.Info,
              contentDescription = null,
              tint = ThemeCyber.colors.textMuted,
              modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
              text = "No navigation hops recorded yet.",
              fontFamily = CyberMonoFamily,
              fontSize = 12.sp,
              color = ThemeCyber.colors.textMuted
            )
          }
        }
      } else {
        // Status banner
        ChainStatusBanner(chain = chain)

        Spacer(modifier = Modifier.height(14.dp))

        // Hops list
        LazyColumn(
          verticalArrangement = Arrangement.spacedBy(10.dp),
          modifier = Modifier.weight(1f, fill = false)
        ) {
          items(chain.hops) { hop ->
            ChainHopCard(
              hop = hop,
              timeFormat = timeFormat,
              onCopyUrl = {
                clipboard.copy(hop.uri, "URL")
                Toast.makeText(context, "URL copied", Toast.LENGTH_SHORT).show()
              },
              onOpenUrl = {
                onDismiss()
                onOpenUrl(hop.uri)
              },
              onOpenInNewTab = onOpenInNewTab?.let { cb ->
                {
                  onDismiss()
                  cb(hop.uri)
                }
              },
              onInspectDeep = onInspectDeepRedirects?.let { cb ->
                {
                  onDismiss()
                  cb(hop.uri)
                }
              }
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ChainStatusBanner(chain: NavigationChain) {
  val (bgColor, textColor, label, icon) = when (chain.status) {
    ChainStatus.IN_FLIGHT -> Quad(
      ThemeCyber.colors.primary.copy(alpha = 0.12f),
      ThemeCyber.colors.primary,
      "IN-FLIGHT NAVIGATION",
      Icons.Default.Sync
    )
    ChainStatus.COMPLETED_PAGE -> Quad(
      Color(0xFF10B981).copy(alpha = 0.12f),
      Color(0xFF10B981),
      "PAGE LOAD CONFIRMED",
      Icons.Default.CheckCircle
    )
    ChainStatus.COMPLETED_DOWNLOAD -> Quad(
      Color(0xFF3B82F6).copy(alpha = 0.12f),
      Color(0xFF3B82F6),
      "DOWNLOAD COMPLETED",
      Icons.Default.Download
    )
    ChainStatus.LOOP_DETECTED -> Quad(
      Color(0xFFEF4444).copy(alpha = 0.15f),
      Color(0xFFEF4444),
      "REDIRECT LOOP DETECTED",
      Icons.Default.Loop
    )
    ChainStatus.DEPTH_EXCEEDED -> Quad(
      Color(0xFFF59E0B).copy(alpha = 0.15f),
      Color(0xFFF59E0B),
      "MAX HOPS EXCEEDED (32)",
      Icons.Default.Warning
    )
    ChainStatus.BLOCKED_SECURITY -> Quad(
      Color(0xFFEF4444).copy(alpha = 0.15f),
      Color(0xFFEF4444),
      "BLOCKED BY SECURITY AUTHORITY",
      Icons.Default.Shield
    )
    ChainStatus.ABORTED -> Quad(
      Color(0xFF6B7280).copy(alpha = 0.12f),
      Color(0xFF9CA3AF),
      "NAVIGATION ABORTED",
      Icons.Default.Cancel
    )
  }

  Surface(
    shape = RoundedCornerShape(10.dp),
    color = bgColor,
    border = BorderStroke(1.dp, textColor.copy(alpha = 0.3f)),
    modifier = Modifier.fillMaxWidth()
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
      Icon(icon, contentDescription = null, tint = textColor, modifier = Modifier.size(16.dp))
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = label,
        fontFamily = CyberMonoFamily,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = textColor
      )
      Spacer(modifier = Modifier.weight(1f))
      if (chain.terminalDownloadUrl != null) {
        Text(
          text = "TERMINAL: DOWNLOAD",
          fontFamily = CyberMonoFamily,
          fontSize = 9.5.sp,
          color = textColor
        )
      }
    }
  }
}

@Composable
private fun ChainHopCard(
  hop: NavigationHop,
  timeFormat: SimpleDateFormat,
  onCopyUrl: () -> Unit,
  onOpenUrl: () -> Unit,
  onOpenInNewTab: (() -> Unit)?,
  onInspectDeep: (() -> Unit)?,
) {
  val (badgeBg, badgeText, badgeLabel) = when (hop.hopType) {
    HopType.USER_GESTURE -> Triple(Color(0xFF10B981).copy(alpha = 0.15f), Color(0xFF10B981), "USER TAP")
    HopType.HTTP_REDIRECT -> Triple(Color(0xFFF59E0B).copy(alpha = 0.15f), Color(0xFFF59E0B), "HTTP REDIRECT")
    HopType.NEW_WINDOW_BLANK -> Triple(Color(0xFF8B5CF6).copy(alpha = 0.15f), Color(0xFF8B5CF6), "NEW WINDOW (_blank)")
    HopType.JS_WINDOW_OPEN -> Triple(Color(0xFF06B6D4).copy(alpha = 0.15f), Color(0xFF06B6D4), "JS window.open")
    HopType.JS_NAVIGATION -> Triple(Color(0xFF06B6D4).copy(alpha = 0.15f), Color(0xFF06B6D4), "JS LOCATION")
    HopType.SAME_DOCUMENT_SPA -> Triple(Color(0xFF6366F1).copy(alpha = 0.15f), Color(0xFF6366F1), "SPA / HASH")
    HopType.LOCATION_CHANGE -> Triple(Color(0xFF14B8A6).copy(alpha = 0.15f), Color(0xFF14B8A6), "LOCATION VISIBLE")
    HopType.DOWNLOAD_ATTACHMENT -> Triple(Color(0xFF3B82F6).copy(alpha = 0.15f), Color(0xFF3B82F6), "DOWNLOAD ATTACHMENT")
    HopType.BLOCKED_POPUP -> Triple(Color(0xFFEF4444).copy(alpha = 0.15f), Color(0xFFEF4444), "BLOCKED POPUP")
    HopType.INITIAL_LOAD -> Triple(ThemeCyber.colors.primary.copy(alpha = 0.15f), ThemeCyber.colors.primary, "INITIAL LOAD")
  }

  Surface(
    shape = RoundedCornerShape(12.dp),
    color = ThemeCyber.colors.background,
    border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder),
    modifier = Modifier.fillMaxWidth()
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      // Header: step number & type chip
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
          modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(ThemeCyber.colors.surfaceBorder),
          contentAlignment = Alignment.Center
        ) {
          Text(
            text = hop.hopIndex.toString(),
            fontFamily = CyberMonoFamily,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            color = ThemeCyber.colors.textPrimary
          )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
          shape = RoundedCornerShape(6.dp),
          color = badgeBg,
          modifier = Modifier.padding(end = 6.dp)
        ) {
          Text(
            text = badgeLabel,
            fontFamily = CyberMonoFamily,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            color = badgeText,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
          )
        }
        if (hop.hasUserGesture) {
          Surface(
            shape = RoundedCornerShape(6.dp),
            color = Color(0xFF10B981).copy(alpha = 0.12f),
          ) {
            Text(
              text = "GESTURE",
              fontFamily = CyberMonoFamily,
              fontSize = 9.sp,
              color = Color(0xFF10B981),
              modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
            )
          }
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
          text = timeFormat.format(Date(hop.timestamp)),
          fontFamily = CyberMonoFamily,
          fontSize = 9.5.sp,
          color = ThemeCyber.colors.textMuted
        )
      }

      Spacer(modifier = Modifier.height(8.dp))

      // URL display
      Text(
        text = hop.uri,
        fontFamily = CyberMonoFamily,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.Medium,
        color = ThemeCyber.colors.textPrimary,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis
      )

      if (hop.triggerUri != null && hop.triggerUri != hop.uri) {
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = "TRIGGER: ",
            fontFamily = CyberMonoFamily,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            color = ThemeCyber.colors.textMuted
          )
          Text(
            text = hop.triggerUri,
            fontFamily = CyberMonoFamily,
            fontSize = 9.5.sp,
            color = ThemeCyber.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
      }

      if (hop.rejectionReason != null) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = "REASON: ${hop.rejectionReason}",
          fontFamily = CyberMonoFamily,
          fontSize = 9.5.sp,
          color = Color(0xFFEF4444)
        )
      }

      Spacer(modifier = Modifier.height(8.dp))

      // Action buttons
      Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
      ) {
        FilledTonalButton(
          onClick = onOpenUrl,
          contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
          modifier = Modifier.height(28.dp)
        ) {
          Text("Open", fontSize = 10.sp, fontFamily = CyberMonoFamily)
        }
        if (onOpenInNewTab != null) {
          OutlinedButton(
            onClick = onOpenInNewTab,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            modifier = Modifier.height(28.dp)
          ) {
            Text("New Tab", fontSize = 10.sp, fontFamily = CyberMonoFamily)
          }
        }
        if (onInspectDeep != null) {
          OutlinedButton(
            onClick = onInspectDeep,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            modifier = Modifier.height(28.dp)
          ) {
            Text("Deep Inspect", fontSize = 10.sp, fontFamily = CyberMonoFamily)
          }
        }
        Spacer(modifier = Modifier.weight(1f))
        IconButton(
          onClick = onCopyUrl,
          modifier = Modifier.size(28.dp)
        ) {
          Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(14.dp), tint = ThemeCyber.colors.textMuted)
        }
      }
    }
  }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
