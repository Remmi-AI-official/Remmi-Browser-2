package com.remmi.browser.ui.components

import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.remmi.browser.model.WebContextMenuData
import com.remmi.browser.ui.theme.CyberMonoFamily
import com.remmi.browser.ui.theme.ThemeCyber
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebContextMenuSheet(
  data: WebContextMenuData,
  onDismiss: () -> Unit,
  onOpenInNewTab: (url: String) -> Unit,
  onOpenInNewTabInBackground: (url: String) -> Unit,
  onOpenInNewTabInGroup: (url: String) -> Unit,
  onOpenInInPrivateTab: (url: String) -> Unit,
  onOpenInNewWindow: (url: String) -> Unit,
  onPreviewPage: (url: String, title: String) -> Unit,
  onPreviewImage: (imageUrl: String, title: String) -> Unit,
  onAskAiAboutImage: (imageUrl: String, title: String) -> Unit,
  onCopyLinkAddress: (url: String) -> Unit,
  onCopyLinkText: (text: String) -> Unit,
  onCopyImage: (imageUrl: String) -> Unit,
  onDownloadLink: (url: String) -> Unit,
  onDownloadImage: (imageUrl: String) -> Unit,
  onSearchWebForImage: (imageUrl: String) -> Unit,
  onShareLink: (url: String, title: String) -> Unit,
  onShareImage: (imageUrl: String, title: String) -> Unit,
  onInspectRedirects: ((url: String) -> Unit)? = null,
  onInspectLink: ((data: WebContextMenuData) -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val clipboardManager = LocalClipboardManager.current
  val scope = rememberCoroutineScope()
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

  val isLightMode = ThemeCyber.colors.isLight
  val backgroundColor = ThemeCyber.colors.surface
  val cardBg = if (isLightMode) Color(0xFFF6F8FA) else Color(0xFF1C202B)
  val cardBorder = if (isLightMode) Color(0xFFE5E7EB) else Color(0xFF2E3544)
  val textColor = ThemeCyber.colors.textPrimary
  val textSubColor = ThemeCyber.colors.textSecondary
  val accentColor = ThemeCyber.colors.primary

  var isCopiedTemporarily by remember { mutableStateOf(false) }

  val targetUrl = if (data.isImage) (data.resolvedSrcUri ?: data.srcUri ?: "") else (data.linkUri ?: "")
  val isHttps = targetUrl.startsWith("https://", ignoreCase = true)

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = backgroundColor,
    scrimColor = Color.Black.copy(alpha = 0.55f),
    tonalElevation = 0.dp,
    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    dragHandle = {
      Box(
        modifier = Modifier
          .padding(top = 10.dp, bottom = 8.dp)
          .size(width = 40.dp, height = 4.dp)
          .clip(CircleShape)
          .background(ThemeCyber.colors.surfaceBorder)
      )
    },
    modifier = modifier,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 4.dp)
        .padding(bottom = 28.dp)
    ) {
      // 1. Header Hero Card
      Surface(
        shape = RoundedCornerShape(16.dp),
        color = cardBg,
        border = androidx.compose.foundation.BorderStroke(1.dp, cardBorder),
        modifier = Modifier.fillMaxWidth()
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          if (data.isImage && (!data.resolvedSrcUri.isNullOrBlank() || !data.srcUri.isNullOrBlank())) {
            val imgUrl = data.resolvedSrcUri ?: data.srcUri!!
            Box(
              modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (isLightMode) Color(0xFFECEFF3) else Color(0xFF262C38))
                .border(1.dp, cardBorder, RoundedCornerShape(12.dp)),
              contentAlignment = Alignment.Center
            ) {
              AsyncImage(
                model = ImageRequest.Builder(context)
                  .data(imgUrl)
                  .crossfade(true)
                  .build(),
                contentDescription = data.displayTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                  .size(48.dp)
                  .clip(RoundedCornerShape(10.dp))
              )
            }
          } else {
            // Clean Domain Icon Box
            Box(
              modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (isLightMode) Color(0xFFEEF2F6) else Color(0xFF262C38))
                .border(1.dp, cardBorder, RoundedCornerShape(12.dp)),
              contentAlignment = Alignment.Center
            ) {
              Text(
                text = data.initialLetter.uppercase(),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = CyberMonoFamily,
                color = accentColor
              )
            }
          }

          Spacer(modifier = Modifier.width(12.dp))

          Column(modifier = Modifier.weight(1f)) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              Text(
                text = data.displayTitle,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
              )

              // Badge
              Surface(
                color = if (isHttps) Color(0xFF10B981).copy(alpha = 0.12f) else Color(0xFF3B82F6).copy(alpha = 0.12f),
                shape = RoundedCornerShape(6.dp),
                border = androidx.compose.foundation.BorderStroke(
                  0.8.dp,
                  if (isHttps) Color(0xFF10B981).copy(alpha = 0.35f) else Color(0xFF3B82F6).copy(alpha = 0.35f)
                )
              ) {
                Text(
                  text = if (data.isImage) "IMAGE" else if (isHttps) "HTTPS" else "HTTP",
                  color = if (isHttps) Color(0xFF059669) else Color(0xFF2563EB),
                  fontSize = 9.sp,
                  fontFamily = CyberMonoFamily,
                  fontWeight = FontWeight.Bold,
                  modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                )
              }
            }

            Spacer(modifier = Modifier.height(3.dp))

            Text(
              text = data.displayUrlSnippet,
              fontSize = 11.5.sp,
              color = textSubColor,
              fontFamily = CyberMonoFamily,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }

          Spacer(modifier = Modifier.width(6.dp))

          // Quick Copy Icon Button
          Surface(
            onClick = {
              if (targetUrl.isNotBlank()) {
                clipboardManager.setText(AnnotatedString(targetUrl))
                isCopiedTemporarily = true
                Toast.makeText(context, "URL Copied to clipboard", Toast.LENGTH_SHORT).show()
                scope.launch {
                  delay(2000)
                  isCopiedTemporarily = false
                }
              }
            },
            shape = RoundedCornerShape(10.dp),
            color = if (isLightMode) Color(0xFFEEF2F6) else Color(0xFF262C38),
            modifier = Modifier.size(36.dp)
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = if (isCopiedTemporarily) Icons.Default.Check else Icons.Default.ContentCopy,
                contentDescription = "Copy URL",
                tint = if (isCopiedTemporarily) Color(0xFF10B981) else textSubColor,
                modifier = Modifier.size(16.dp)
              )
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(14.dp))

      // 2. Quick Action Chips Row (iOS/Modern Android style)
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        QuickActionButton(
          icon = Icons.AutoMirrored.Filled.OpenInNew,
          label = "New Tab",
          iconTint = Color(0xFF3B82F6),
          cardBg = cardBg,
          cardBorder = cardBorder,
          textColor = textColor,
          modifier = Modifier.weight(1f),
          onClick = {
            onDismiss()
            if (data.isImage) onOpenInNewTab(targetUrl) else onOpenInNewTab(targetUrl)
          }
        )

        QuickActionButton(
          icon = Icons.Default.Shield,
          label = "InPrivate",
          iconTint = Color(0xFF8B5CF6),
          cardBg = cardBg,
          cardBorder = cardBorder,
          textColor = textColor,
          modifier = Modifier.weight(1f),
          onClick = {
            onDismiss()
            onOpenInInPrivateTab(targetUrl)
          }
        )

        QuickActionButton(
          icon = Icons.Default.ContentCopy,
          label = if (data.isImage) "Copy Img" else "Copy Link",
          iconTint = Color(0xFF10B981),
          cardBg = cardBg,
          cardBorder = cardBorder,
          textColor = textColor,
          modifier = Modifier.weight(1f),
          onClick = {
            onDismiss()
            if (data.isImage) onCopyImage(targetUrl) else onCopyLinkAddress(targetUrl)
          }
        )

        QuickActionButton(
          icon = Icons.Default.Share,
          label = "Share",
          iconTint = Color(0xFFF59E0B),
          cardBg = cardBg,
          cardBorder = cardBorder,
          textColor = textColor,
          modifier = Modifier.weight(1f),
          onClick = {
            onDismiss()
            if (data.isImage) onShareImage(targetUrl, data.displayTitle) else onShareLink(targetUrl, data.displayTitle)
          }
        )
      }

      Spacer(modifier = Modifier.height(14.dp))

      // 3. Action Categories
      if (data.isImage && (!data.resolvedSrcUri.isNullOrBlank() || !data.srcUri.isNullOrBlank())) {
        val imageUrl = data.resolvedSrcUri ?: data.srcUri!!

        // Image Actions Section
        ModernActionGroupCard(
          title = "IMAGE ACTIONS",
          cardBg = cardBg,
          cardBorder = cardBorder,
          titleColor = textSubColor
        ) {
          ModernActionRow(
            icon = Icons.AutoMirrored.Filled.OpenInNew,
            iconTint = Color(0xFF3B82F6),
            title = "Open image in new tab",
            subtitle = "Load high resolution source in tab",
            textColor = textColor,
            subTextColor = textSubColor,
            testTag = "menu_open_image_new_tab",
            onClick = {
              onDismiss()
              onOpenInNewTab(imageUrl)
            }
          )

          ModernActionDivider(cardBorder)

          ModernActionRow(
            icon = Icons.Default.Visibility,
            iconTint = Color(0xFF06B6D4),
            title = "Preview image",
            subtitle = "Full screen zoomable viewer",
            textColor = textColor,
            subTextColor = textSubColor,
            testTag = "menu_preview_image",
            onClick = {
              onDismiss()
              onPreviewImage(imageUrl, data.displayTitle)
            }
          )

          ModernActionDivider(cardBorder)

          ModernActionRow(
            icon = Icons.Default.AutoAwesome,
            iconTint = Color(0xFFDE5833),
            title = "Ask AI about this image",
            subtitle = "DuckDuckGo AI analysis & insights",
            textColor = textColor,
            subTextColor = textSubColor,
            testTag = "menu_ask_ai_image",
            onClick = {
              onDismiss()
              onAskAiAboutImage(imageUrl, data.displayTitle)
            }
          )

          ModernActionDivider(cardBorder)

          ModernActionRow(
            icon = Icons.Default.ImageSearch,
            iconTint = Color(0xFF4285F4),
            title = "Search web for image",
            subtitle = "Reverse image lookup via Google Lens",
            textColor = textColor,
            subTextColor = textSubColor,
            testTag = "menu_search_web_image",
            onClick = {
              onDismiss()
              onSearchWebForImage(imageUrl)
            }
          )

          ModernActionDivider(cardBorder)

          ModernActionRow(
            icon = Icons.Default.Download,
            iconTint = Color(0xFF10B981),
            title = "Download image",
            subtitle = "Save to device gallery",
            textColor = textColor,
            subTextColor = textSubColor,
            testTag = "menu_download_image",
            onClick = {
              onDismiss()
              onDownloadImage(imageUrl)
            }
          )

          ModernActionDivider(cardBorder)

          ModernActionRow(
            icon = Icons.Default.ContentCopy,
            iconTint = Color(0xFFF59E0B),
            title = "Copy image",
            subtitle = "Copy bitmap to system clipboard",
            textColor = textColor,
            subTextColor = textSubColor,
            testTag = "menu_copy_image",
            onClick = {
              onDismiss()
              onCopyImage(imageUrl)
            }
          )
        }

        // If image is also a link
        if (data.isLink && !data.linkUri.isNullOrBlank()) {
          val linkUrl = data.linkUri!!
          Spacer(modifier = Modifier.height(14.dp))

          ModernActionGroupCard(
            title = "LINKED DESTINATION",
            cardBg = cardBg,
            cardBorder = cardBorder,
            titleColor = textSubColor
          ) {
            ModernActionRow(
              icon = Icons.Default.Link,
              iconTint = Color(0xFF3B82F6),
              title = "Open linked URL",
              subtitle = linkUrl,
              textColor = textColor,
              subTextColor = textSubColor,
              onClick = {
                onDismiss()
                onOpenInNewTab(linkUrl)
              }
            )

            ModernActionDivider(cardBorder)

            ModernActionRow(
              icon = Icons.Default.Shield,
              iconTint = Color(0xFF8B5CF6),
              title = "Open link in InPrivate tab",
              subtitle = "Open without cookies or history tracking",
              textColor = textColor,
              subTextColor = textSubColor,
              onClick = {
                onDismiss()
                onOpenInInPrivateTab(linkUrl)
              }
            )

            ModernActionDivider(cardBorder)

            ModernActionRow(
              icon = Icons.Default.ContentCopy,
              iconTint = Color(0xFF10B981),
              title = "Copy linked URL",
              textColor = textColor,
              subTextColor = textSubColor,
              onClick = {
                onDismiss()
                onCopyLinkAddress(linkUrl)
              }
            )

            if (onInspectRedirects != null) {
              ModernActionDivider(cardBorder)
              ModernActionRow(
                icon = Icons.Default.AltRoute,
                iconTint = Color(0xFFF97316),
                title = "Reveal original destination (Inspect redirects)",
                subtitle = "Follow and analyze intermediate hops",
                textColor = textColor,
                subTextColor = textSubColor,
                testTag = "menu_inspect_redirects_image_link",
                onClick = {
                  onDismiss()
                  onInspectRedirects(linkUrl)
                }
              )
            }

            if (onInspectLink != null) {
              ModernActionDivider(cardBorder)
              ModernActionRow(
                icon = Icons.Default.Security,
                iconTint = Color(0xFFEF4444),
                title = "Inspect link (Forensic & Chain)",
                subtitle = "Inspect certificates, redirects & safety",
                textColor = textColor,
                subTextColor = textSubColor,
                testTag = "menu_inspect_link_image",
                onClick = {
                  onDismiss()
                  onInspectLink(data)
                }
              )
            }
          }
        }
      } else {
        // --- LINK CONTEXT MENU (Modern Grouped Cards) ---
        val linkUrl = data.linkUri ?: ""

        // Group 1: Navigation & Tabs
        ModernActionGroupCard(
          title = "NAVIGATION & TABS",
          cardBg = cardBg,
          cardBorder = cardBorder,
          titleColor = textSubColor
        ) {
          ModernActionRow(
            icon = Icons.AutoMirrored.Filled.OpenInNew,
            iconTint = Color(0xFF3B82F6),
            title = "Open in new tab",
            subtitle = "Open in active tab session",
            textColor = textColor,
            subTextColor = textSubColor,
            testTag = "menu_open_new_tab",
            onClick = {
              onDismiss()
              onOpenInNewTab(linkUrl)
            }
          )

          ModernActionDivider(cardBorder)

          ModernActionRow(
            icon = Icons.Default.DashboardCustomize,
            iconTint = Color(0xFF06B6D4),
            title = "Open in tab group",
            subtitle = "Group with related pages",
            textColor = textColor,
            subTextColor = textSubColor,
            testTag = "menu_open_new_tab_group",
            onClick = {
              onDismiss()
              onOpenInNewTabInGroup(linkUrl)
            }
          )

          ModernActionDivider(cardBorder)

          ModernActionRow(
            icon = Icons.Default.Shield,
            iconTint = Color(0xFF8B5CF6),
            title = "Open in InPrivate tab",
            subtitle = "Ghost session without history",
            textColor = textColor,
            subTextColor = textSubColor,
            testTag = "menu_open_inprivate",
            onClick = {
              onDismiss()
              onOpenInInPrivateTab(linkUrl)
            }
          )

          ModernActionDivider(cardBorder)

          ModernActionRow(
            icon = Icons.Default.OpenInBrowser,
            iconTint = Color(0xFF10B981),
            title = "Open in new window",
            subtitle = "Create isolated browsing session",
            textColor = textColor,
            subTextColor = textSubColor,
            testTag = "menu_open_new_window",
            onClick = {
              onDismiss()
              onOpenInNewWindow(linkUrl)
            }
          )

          ModernActionDivider(cardBorder)

          ModernActionRow(
            icon = Icons.Default.Preview,
            iconTint = Color(0xFFEC4899),
            title = "Preview page",
            subtitle = "Quick peek sheet without navigating",
            textColor = textColor,
            subTextColor = textSubColor,
            testTag = "menu_preview_page",
            onClick = {
              onDismiss()
              onPreviewPage(linkUrl, data.displayTitle)
            }
          )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Group 2: Forensic & Security Inspection
        if (onInspectLink != null || onInspectRedirects != null) {
          ModernActionGroupCard(
            title = "SECURITY & ANALYSIS",
            cardBg = cardBg,
            cardBorder = cardBorder,
            titleColor = textSubColor
          ) {
            if (onInspectLink != null) {
              ModernActionRow(
                icon = Icons.Default.Security,
                iconTint = Color(0xFFEF4444),
                title = "Inspect link (Forensic & Chain)",
                subtitle = "Security inspection, certificate & URL breakdown",
                textColor = textColor,
                subTextColor = textSubColor,
                testTag = "menu_inspect_link",
                onClick = {
                  onDismiss()
                  onInspectLink(data)
                }
              )
            }

            if (onInspectLink != null && onInspectRedirects != null) {
              ModernActionDivider(cardBorder)
            }

            if (onInspectRedirects != null) {
              ModernActionRow(
                icon = Icons.Default.AltRoute,
                iconTint = Color(0xFFF97316),
                title = "Reveal original link (Inspect redirects)",
                subtitle = "Unmask shorteners & tracker redirects",
                textColor = textColor,
                subTextColor = textSubColor,
                testTag = "menu_inspect_redirects",
                onClick = {
                  onDismiss()
                  onInspectRedirects(linkUrl)
                }
              )
            }
          }

          Spacer(modifier = Modifier.height(14.dp))
        }

        // Group 3: Data, Download & Sharing
        ModernActionGroupCard(
          title = "DATA & SHARING",
          cardBg = cardBg,
          cardBorder = cardBorder,
          titleColor = textSubColor
        ) {
          ModernActionRow(
            icon = Icons.Default.ContentCopy,
            iconTint = Color(0xFF3B82F6),
            title = "Copy link address",
            subtitle = "Copy target URL to clipboard",
            textColor = textColor,
            subTextColor = textSubColor,
            testTag = "menu_copy_link_address",
            onClick = {
              onDismiss()
              onCopyLinkAddress(linkUrl)
            }
          )

          if (data.resolvedLinkText.isNotBlank()) {
            ModernActionDivider(cardBorder)
            ModernActionRow(
              icon = Icons.Default.TextFields,
              iconTint = Color(0xFF06B6D4),
              title = "Copy link text",
              subtitle = "\"${data.resolvedLinkText.take(40)}\"",
              textColor = textColor,
              subTextColor = textSubColor,
              testTag = "menu_copy_link_text",
              onClick = {
                onDismiss()
                onCopyLinkText(data.resolvedLinkText)
              }
            )
          }

          ModernActionDivider(cardBorder)

          ModernActionRow(
            icon = Icons.Default.FileDownload,
            iconTint = Color(0xFF10B981),
            title = "Download link / file",
            subtitle = "Download linked file into Download Hub",
            textColor = textColor,
            subTextColor = textSubColor,
            testTag = "menu_download_link",
            onClick = {
              onDismiss()
              onDownloadLink(linkUrl)
            }
          )

          ModernActionDivider(cardBorder)

          ModernActionRow(
            icon = Icons.Default.Share,
            iconTint = Color(0xFFF59E0B),
            title = "Share link",
            subtitle = "Share via apps or nearby devices",
            textColor = textColor,
            subTextColor = textSubColor,
            testTag = "menu_share_link",
            onClick = {
              onDismiss()
              onShareLink(linkUrl, data.displayTitle)
            }
          )
        }
      }
    }
  }
}

@Composable
private fun QuickActionButton(
  icon: ImageVector,
  label: String,
  iconTint: Color,
  cardBg: Color,
  cardBorder: Color,
  textColor: Color,
  modifier: Modifier = Modifier,
  onClick: () -> Unit
) {
  val isLight = ThemeCyber.colors.isLight
  val iconBg = if (isLight) Color(0xFFEEF2F6) else Color(0xFF262C38)
  Surface(
    onClick = onClick,
    shape = RoundedCornerShape(14.dp),
    color = cardBg,
    border = androidx.compose.foundation.BorderStroke(1.dp, cardBorder),
    modifier = modifier.height(64.dp)
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
      modifier = Modifier.padding(4.dp)
    ) {
      Box(
        modifier = Modifier
          .size(28.dp)
          .clip(CircleShape)
          .background(iconBg),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = icon,
          contentDescription = label,
          tint = iconTint,
          modifier = Modifier.size(16.dp)
        )
      }
      Spacer(modifier = Modifier.height(3.dp))
      Text(
        text = label,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = textColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center
      )
    }
  }
}

@Composable
private fun ModernActionGroupCard(
  title: String,
  cardBg: Color,
  cardBorder: Color,
  titleColor: Color,
  content: @Composable () -> Unit
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Text(
      text = title,
      fontSize = 11.sp,
      fontWeight = FontWeight.Bold,
      fontFamily = CyberMonoFamily,
      color = titleColor,
      modifier = Modifier.padding(start = 6.dp, bottom = 6.dp)
    )
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = cardBg,
      border = androidx.compose.foundation.BorderStroke(1.dp, cardBorder),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(modifier = Modifier.fillMaxWidth()) {
        content()
      }
    }
  }
}

@Composable
private fun ModernActionRow(
  icon: ImageVector,
  iconTint: Color,
  title: String,
  subtitle: String? = null,
  textColor: Color,
  subTextColor: Color,
  testTag: String = "",
  onClick: () -> Unit
) {
  val isLight = ThemeCyber.colors.isLight
  val iconBg = if (isLight) Color(0xFFEEF2F6) else Color(0xFF262C38)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 14.dp, vertical = 11.dp)
      .then(if (testTag.isNotBlank()) Modifier.testTag(testTag) else Modifier),
    verticalAlignment = Alignment.CenterVertically
  ) {
    // Leading Squircle Icon
    Box(
      modifier = Modifier
        .size(36.dp)
        .clip(RoundedCornerShape(10.dp))
        .background(iconBg),
      contentAlignment = Alignment.Center
    ) {
      Icon(
        imageVector = icon,
        contentDescription = title,
        tint = iconTint,
        modifier = Modifier.size(18.dp)
      )
    }

    Spacer(modifier = Modifier.width(12.dp))

    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = textColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      if (!subtitle.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(1.dp))
        Text(
          text = subtitle,
          fontSize = 11.sp,
          color = subTextColor,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
    }

    Icon(
      imageVector = Icons.Default.ChevronRight,
      contentDescription = null,
      tint = subTextColor.copy(alpha = 0.4f),
      modifier = Modifier.size(16.dp)
    )
  }
}

@Composable
private fun ModernActionDivider(color: Color) {
  HorizontalDivider(
    color = color,
    thickness = 0.6.dp,
    modifier = Modifier.padding(horizontal = 14.dp)
  )
}
