package com.remmi.browser.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
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
import com.remmi.browser.model.WebContextMenuData
import com.remmi.browser.security.ClipboardManager
import com.remmi.browser.ui.theme.CyberMonoFamily
import com.remmi.browser.ui.theme.ThemeCyber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkInspectorSheet(
  data: WebContextMenuData,
  onDismiss: () -> Unit,
  onOpen: (String) -> Unit,
  onOpenInNewTab: (String) -> Unit,
  onOpenChain: (String) -> Unit,
  onDeepAnalyze: ((String) -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val clipboard = remember { ClipboardManager(context) }
  val linkUrl = data.linkUri ?: ""

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
    modifier = modifier.testTag("link_inspector_sheet"),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp)
        .padding(bottom = 32.dp)
        .verticalScroll(rememberScrollState())
    ) {
      // Header
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)
      ) {
        Box(
          modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(ThemeCyber.colors.primary.copy(alpha = 0.15f)),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = ThemeCyber.colors.primary,
            modifier = Modifier.size(20.dp)
          )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "LINK FORENSIC INSPECTOR",
            fontFamily = ThemeCyber.fontFamily,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = ThemeCyber.colors.textPrimary
          )
          Text(
            text = "Target inspection & Chain Origin Gate",
            fontSize = 10.5.sp,
            fontFamily = CyberMonoFamily,
            color = ThemeCyber.colors.primary
          )
        }
        IconButton(onClick = onDismiss) {
          Icon(Icons.Default.Close, contentDescription = "Close", tint = ThemeCyber.colors.textMuted)
        }
      }

      // Metadata Cards
      Surface(
        shape = RoundedCornerShape(14.dp),
        color = ThemeCyber.colors.background,
        border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(modifier = Modifier.padding(14.dp)) {
          // Visible Text
          InspectorField(
            label = "VISIBLE TEXT / ANCHOR",
            value = data.resolvedLinkText.ifBlank { "(No anchor text)" },
            highlightColor = ThemeCyber.colors.textPrimary
          )

          Spacer(modifier = Modifier.height(12.dp))
          HorizontalDivider(color = ThemeCyber.colors.surfaceBorder, thickness = 0.8.dp)
          Spacer(modifier = Modifier.height(12.dp))

          // Declared Href
          InspectorField(
            label = "DECLARED HREF",
            value = linkUrl.ifBlank { "(Empty)" },
            highlightColor = ThemeCyber.colors.primary,
            onCopy = {
              clipboard.copy(linkUrl, "Link Href")
              Toast.makeText(context, "Copied URL to clipboard", Toast.LENGTH_SHORT).show()
            }
          )

          Spacer(modifier = Modifier.height(12.dp))
          HorizontalDivider(color = ThemeCyber.colors.surfaceBorder, thickness = 0.8.dp)
          Spacer(modifier = Modifier.height(12.dp))

          // Target & Trigger Grid
          Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
              InspectorField(
                label = "TARGET ATTRIBUTE",
                value = data.target ?: "_blank (Inferred)",
                highlightColor = Color(0xFF8B5CF6)
              )
            }
            Column(modifier = Modifier.weight(1f)) {
              InspectorField(
                label = "TRIGGER TYPE",
                value = "USER_GESTURE",
                highlightColor = Color(0xFF10B981)
              )
            }
          }

          Spacer(modifier = Modifier.height(12.dp))
          HorizontalDivider(color = ThemeCyber.colors.surfaceBorder, thickness = 0.8.dp)
          Spacer(modifier = Modifier.height(12.dp))

          // Redirect Chain preview
          Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "REDIRECT CHAIN STATUS",
                fontFamily = CyberMonoFamily,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                color = ThemeCyber.colors.textMuted
              )
              Text(
                text = "Unvisited • Ready to trace with active chain tracking",
                fontFamily = CyberMonoFamily,
                fontSize = 11.sp,
                color = ThemeCyber.colors.textSecondary
              )
            }
            Surface(
              shape = RoundedCornerShape(6.dp),
              color = ThemeCyber.colors.primary.copy(alpha = 0.12f)
            ) {
              Text(
                text = "STANDBY",
                fontFamily = CyberMonoFamily,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = ThemeCyber.colors.primary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
              )
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(18.dp))

      // Action Buttons
      Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
      ) {
        // OPEN CHAIN Button (Highlighted primary action)
        Button(
          onClick = {
            onDismiss()
            onOpenChain(linkUrl)
          },
          colors = ButtonDefaults.buttonColors(containerColor = ThemeCyber.colors.primary),
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.fillMaxWidth().height(44.dp)
        ) {
          Icon(Icons.Default.AltRoute, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = "OPEN & TRACE CHAIN",
            fontFamily = CyberMonoFamily,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold
          )
        }

        // Secondary Actions Row
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          FilledTonalButton(
            onClick = {
              onDismiss()
              onOpen(linkUrl)
            },
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1f).height(40.dp)
          ) {
            Text("Open Normal", fontSize = 11.5.sp, fontFamily = CyberMonoFamily)
          }

          FilledTonalButton(
            onClick = {
              onDismiss()
              onOpenInNewTab(linkUrl)
            },
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1f).height(40.dp)
          ) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("New Tab", fontSize = 11.5.sp, fontFamily = CyberMonoFamily)
          }
        }

        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          if (onDeepAnalyze != null) {
            OutlinedButton(
              onClick = {
                onDismiss()
                onDeepAnalyze(linkUrl)
              },
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier.weight(1f).height(40.dp)
            ) {
              Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(14.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("Deep Network Scan", fontSize = 11.sp, fontFamily = CyberMonoFamily)
            }
          }

          OutlinedButton(
            onClick = {
              clipboard.copy(linkUrl, "Link Href")
              Toast.makeText(context, "Copied URL to clipboard", Toast.LENGTH_SHORT).show()
            },
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1f).height(40.dp)
          ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Copy URL", fontSize = 11.sp, fontFamily = CyberMonoFamily)
          }
        }
      }
    }
  }
}

@Composable
private fun InspectorField(
  label: String,
  value: String,
  highlightColor: Color,
  onCopy: (() -> Unit)? = null,
) {
  Column {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = label,
        fontFamily = CyberMonoFamily,
        fontSize = 9.5.sp,
        fontWeight = FontWeight.Bold,
        color = ThemeCyber.colors.textMuted
      )
      if (onCopy != null) {
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onCopy, modifier = Modifier.size(20.dp)) {
          Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = ThemeCyber.colors.textMuted, modifier = Modifier.size(12.dp))
        }
      }
    }
    Spacer(modifier = Modifier.height(2.dp))
    Text(
      text = value,
      fontFamily = CyberMonoFamily,
      fontSize = 11.5.sp,
      color = highlightColor,
      maxLines = 4,
      overflow = TextOverflow.Ellipsis
    )
  }
}
