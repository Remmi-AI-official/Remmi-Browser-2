package com.remmi.browser.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import com.remmi.browser.ui.theme.CyberMonoFamily
import com.remmi.browser.ui.theme.ThemeCyber
import com.remmi.browser.util.FaviconHelper
import com.remmi.browser.util.ShortcutHelper
import java.net.URI

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallShortcutSheet(
  url: String,
  title: String,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  var showNameEditDialog by remember { mutableStateOf<Boolean>(false) }
  var isInstallModeSelected by remember { mutableStateOf<Boolean>(false) }
  var customShortcutTitle by remember {
    mutableStateOf(
      title.ifBlank {
        try {
          URI(url).host?.removePrefix("www.") ?: "Web App"
        } catch (_: Exception) {
          "Web App"
        }
      }
    )
  }

  val displayHost = remember(url) {
    try {
      URI(url).host?.removePrefix("www.") ?: url
    } catch (_: Exception) {
      url
    }
  }

  val initialChar = remember(displayHost, title) {
    val src = if (displayHost.isNotBlank()) displayHost else title
    src.firstOrNull()?.uppercaseChar()?.toString() ?: "W"
  }

  val faviconCandidates = remember(url) {
    FaviconHelper.getCandidateFaviconUrls(url)
  }
  val primaryFaviconUrl = faviconCandidates.firstOrNull()

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = ThemeCyber.colors.surface,
    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    dragHandle = {
      Box(
        modifier = Modifier
          .padding(top = 12.dp, bottom = 12.dp)
          .size(width = 44.dp, height = 4.dp)
          .clip(RoundedCornerShape(2.dp))
          .background(ThemeCyber.colors.surfaceBorder.copy(alpha = 0.8f))
      )
    },
    modifier = modifier.testTag("install_shortcut_sheet")
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp)
        .padding(bottom = 36.dp)
    ) {
      // Header Title
      Text(
        text = "Install & Shortcuts",
        color = ThemeCyber.colors.textPrimary,
        fontFamily = ThemeCyber.fontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        modifier = Modifier
          .fillMaxWidth()
          .padding(bottom = 20.dp),
        textAlign = TextAlign.Center
      )

      // Option 1: Install as Standalone App (PWA)
      Surface(
        onClick = {
          isInstallModeSelected = true
          showNameEditDialog = true
        },
        shape = RoundedCornerShape(16.dp),
        color = ThemeCyber.colors.background,
        border = BorderStroke(1.dp, ThemeCyber.colors.primary.copy(alpha = 0.5f)),
        modifier = Modifier
          .fillMaxWidth()
          .padding(bottom = 14.dp)
          .testTag("shortcut_option_install")
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          // Icon Container with Real Website Logo + Install Badge
          Box(
            modifier = Modifier.size(52.dp),
            contentAlignment = Alignment.Center
          ) {
            Box(
              modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .border(1.dp, ThemeCyber.colors.primary, RoundedCornerShape(12.dp)),
              contentAlignment = Alignment.Center
            ) {
              if (primaryFaviconUrl != null) {
                SubcomposeAsyncImage(
                  model = primaryFaviconUrl,
                  contentDescription = "Website Logo",
                  contentScale = ContentScale.Fit,
                  modifier = Modifier
                    .size(32.dp)
                    .padding(2.dp),
                  loading = {
                    Text(
                      text = initialChar,
                      color = Color.Black,
                      fontFamily = CyberMonoFamily,
                      fontWeight = FontWeight.ExtraBold,
                      fontSize = 20.sp
                    )
                  },
                  error = {
                    Text(
                      text = initialChar,
                      color = Color.Black,
                      fontFamily = CyberMonoFamily,
                      fontWeight = FontWeight.ExtraBold,
                      fontSize = 20.sp
                    )
                  }
                )
              } else {
                Text(
                  text = initialChar,
                  color = Color.Black,
                  fontFamily = CyberMonoFamily,
                  fontWeight = FontWeight.ExtraBold,
                  fontSize = 20.sp
                )
              }
            }

            // Install Download Badge
            Box(
              modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(20.dp)
                .clip(CircleShape)
                .background(ThemeCyber.colors.primary)
                .border(1.5.dp, ThemeCyber.colors.background, CircleShape),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = Icons.Default.Download,
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(12.dp)
              )
            }
          }

          Spacer(modifier = Modifier.width(16.dp))

          Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                text = "Install App",
                color = ThemeCyber.colors.textPrimary,
                fontFamily = ThemeCyber.fontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
              )
              Spacer(modifier = Modifier.width(6.dp))
              Surface(
                color = ThemeCyber.colors.primary.copy(alpha = 0.15f),
                shape = RoundedCornerShape(4.dp)
              ) {
                Text(
                  text = "STANDALONE",
                  color = ThemeCyber.colors.primary,
                  fontFamily = CyberMonoFamily,
                  fontSize = 9.sp,
                  fontWeight = FontWeight.Bold,
                  modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                )
              }
            }
            Text(
              text = "Installs on device, opens in its own window outside browser",
              color = ThemeCyber.colors.textSecondary,
              fontFamily = ThemeCyber.fontFamily,
              fontSize = 12.sp,
              modifier = Modifier.padding(top = 2.dp)
            )
          }

          Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            tint = ThemeCyber.colors.textSecondary.copy(alpha = 0.7f),
            modifier = Modifier.size(16.dp)
          )
        }
      }

      // Option 2: Create shortcut (Shortcuts open in Remmi)
      Surface(
        onClick = {
          isInstallModeSelected = false
          showNameEditDialog = true
        },
        shape = RoundedCornerShape(16.dp),
        color = ThemeCyber.colors.background,
        border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder.copy(alpha = 0.7f)),
        modifier = Modifier
          .fillMaxWidth()
          .testTag("shortcut_option_create_shortcut")
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          // Icon Container with Real Website Logo + Remmi Badge
          Box(
            modifier = Modifier.size(52.dp),
            contentAlignment = Alignment.Center
          ) {
            Box(
              modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .border(1.dp, ThemeCyber.colors.secondary.copy(alpha = 0.6f), RoundedCornerShape(12.dp)),
              contentAlignment = Alignment.Center
            ) {
              if (primaryFaviconUrl != null) {
                SubcomposeAsyncImage(
                  model = primaryFaviconUrl,
                  contentDescription = "Website Logo",
                  contentScale = ContentScale.Fit,
                  modifier = Modifier
                    .size(32.dp)
                    .padding(2.dp),
                  loading = {
                    Text(
                      text = initialChar,
                      color = Color.Black,
                      fontFamily = CyberMonoFamily,
                      fontWeight = FontWeight.ExtraBold,
                      fontSize = 20.sp
                    )
                  },
                  error = {
                    Text(
                      text = initialChar,
                      color = Color.Black,
                      fontFamily = CyberMonoFamily,
                      fontWeight = FontWeight.ExtraBold,
                      fontSize = 20.sp
                    )
                  }
                )
              } else {
                Text(
                  text = initialChar,
                  color = Color.Black,
                  fontFamily = CyberMonoFamily,
                  fontWeight = FontWeight.ExtraBold,
                  fontSize = 20.sp
                )
              }
            }

            // Remmi Logo Badge
            Box(
              modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(20.dp)
                .clip(CircleShape)
                .background(ThemeCyber.colors.secondary)
                .border(1.5.dp, ThemeCyber.colors.background, CircleShape),
              contentAlignment = Alignment.Center
            ) {
              Text(
                text = "R",
                color = Color.Black,
                fontFamily = CyberMonoFamily,
                fontWeight = FontWeight.Black,
                fontSize = 11.sp
              )
            }
          }

          Spacer(modifier = Modifier.width(16.dp))

          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "Create shortcut",
              color = ThemeCyber.colors.textPrimary,
              fontFamily = ThemeCyber.fontFamily,
              fontWeight = FontWeight.Bold,
              fontSize = 16.sp
            )
            Text(
              text = "Adds shortcut on Home screen that opens in Remmi",
              color = ThemeCyber.colors.textSecondary,
              fontFamily = ThemeCyber.fontFamily,
              fontSize = 12.sp,
              modifier = Modifier.padding(top = 2.dp)
            )
          }

          Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            tint = ThemeCyber.colors.textSecondary.copy(alpha = 0.7f),
            modifier = Modifier.size(16.dp)
          )
        }
      }
    }
  }

  // Edit / Confirm Name Dialog with Live Website Logo Preview
  if (showNameEditDialog) {
    AlertDialog(
      onDismissRequest = { showNameEditDialog = false },
      containerColor = ThemeCyber.colors.surface,
      shape = RoundedCornerShape(16.dp),
      title = {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Box(
            modifier = Modifier
              .size(36.dp)
              .clip(RoundedCornerShape(8.dp))
              .background(Color.White)
              .border(1.dp, if (isInstallModeSelected) ThemeCyber.colors.primary else ThemeCyber.colors.secondary, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
          ) {
            if (primaryFaviconUrl != null) {
              SubcomposeAsyncImage(
                model = primaryFaviconUrl,
                contentDescription = "Logo",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(24.dp),
                error = {
                  Text(
                    text = initialChar,
                    color = Color.Black,
                    fontFamily = CyberMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                  )
                }
              )
            } else {
              Text(
                text = initialChar,
                color = Color.Black,
                fontFamily = CyberMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
              )
            }
          }

          Text(
            text = if (isInstallModeSelected) "Install Web App" else "Add to Home screen",
            color = ThemeCyber.colors.textPrimary,
            fontFamily = ThemeCyber.fontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
          )
        }
      },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(
            text = if (isInstallModeSelected) {
              "This web application will be installed on your device and launch in a dedicated standalone window with website logo."
            } else {
              "A direct shortcut with website logo will be added to your Home screen to open in Remmi Browser."
            },
            color = ThemeCyber.colors.textSecondary,
            fontFamily = ThemeCyber.fontFamily,
            fontSize = 13.sp
          )

          OutlinedTextField(
            value = customShortcutTitle,
            onValueChange = { customShortcutTitle = it },
            label = { Text("App Name", color = ThemeCyber.colors.textSecondary) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = if (isInstallModeSelected) ThemeCyber.colors.primary else ThemeCyber.colors.secondary,
              unfocusedBorderColor = ThemeCyber.colors.surfaceBorder,
              focusedTextColor = ThemeCyber.colors.textPrimary,
              unfocusedTextColor = ThemeCyber.colors.textPrimary
            ),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("shortcut_name_input")
          )

          Text(
            text = "URL: $url",
            color = ThemeCyber.colors.textSecondary.copy(alpha = 0.8f),
            fontFamily = CyberMonoFamily,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            showNameEditDialog = false
            onDismiss()
            ShortcutHelper.createHomeScreenShortcut(
              context = context,
              url = url,
              title = customShortcutTitle,
              isInstallMode = isInstallModeSelected
            )
          },
          colors = ButtonDefaults.buttonColors(
            containerColor = if (isInstallModeSelected) ThemeCyber.colors.primary else ThemeCyber.colors.secondary
          ),
          shape = RoundedCornerShape(8.dp),
          modifier = Modifier.testTag("shortcut_confirm_add")
        ) {
          Text(
            text = if (isInstallModeSelected) "Install" else "Add Shortcut",
            color = Color.Black,
            fontFamily = ThemeCyber.fontFamily,
            fontWeight = FontWeight.Bold
          )
        }
      },
      dismissButton = {
        TextButton(
          onClick = { showNameEditDialog = false }
        ) {
          Text("Cancel", color = ThemeCyber.colors.textSecondary)
        }
      }
    )
  }
}
