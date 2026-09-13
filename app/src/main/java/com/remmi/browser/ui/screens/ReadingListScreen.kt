package com.remmi.browser.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remmi.browser.reader.ReaderArticle
import com.remmi.browser.reader.ReaderExporter
import com.remmi.browser.storage.RemmiDatabase
import com.remmi.browser.storage.ReadingListItem
import com.remmi.browser.ui.components.ReaderView
import com.remmi.browser.ui.theme.ThemeCyber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

enum class ReadingSortOption(val displayName: String) {
  NEWEST("Newest First"),
  OLDEST("Oldest First"),
  READING_TIME("Reading Time"),
  ALPHABETICAL("Title (A-Z)"),
  WEBSITE("Website Domain"),
}

/**
 * Returns a rich, high-contrast signature color for each reading list folder/category.
 * In Light Mode: Deep, saturated tones with certified WCAG AAA/AA contrast against white.
 * In Dark Mode: Luminous, vibrant cyber neon tones that pop against dark surfaces.
 */
fun getReadingFolderColor(folder: String, isLight: Boolean): Color {
  return when (folder.trim().lowercase()) {
    "all" -> if (isLight) Color(0xFF1D4ED8) else Color(0xFF38BDF8) // Rich Sapphire / Sky Blue
    "favorites" -> if (isLight) Color(0xFFD97706) else Color(0xFFFBBF24) // Rich Warm Amber / Bright Gold
    "unread" -> if (isLight) Color(0xFF0284C7) else Color(0xFF00E5FF) // Vivid Azure / Electric Cyan
    "general" -> if (isLight) Color(0xFF4338CA) else Color(0xFF818CF8) // Deep Indigo / Soft Indigo
    "technology", "tech" -> if (isLight) Color(0xFF0284C7) else Color(0xFF38BDF8) // Electric Azure
    "research" -> if (isLight) Color(0xFF6D28D9) else Color(0xFFA78BFA) // Deep Purple / Vivid Violet
    "news" -> if (isLight) Color(0xFF047857) else Color(0xFF34D399) // Deep Emerald / Bright Mint
    "ai & science", "science", "ai" -> if (isLight) Color(0xFFBE185D) else Color(0xFFF472B6) // Deep Magenta / Rose Pink
    "crypto & privacy", "crypto", "privacy" -> if (isLight) Color(0xFFB45309) else Color(0xFFF59E0B) // Amber Brown / Warm Amber
    "read later" -> if (isLight) Color(0xFF0F766E) else Color(0xFF2DD4BF) // Deep Teal / Bright Teal
    else -> {
      val hash = kotlin.math.abs(folder.hashCode())
      val lightPalette = listOf(
        Color(0xFF1D4ED8), Color(0xFF047857), Color(0xFF6D28D9),
        Color(0xFFD97706), Color(0xFFBE185D), Color(0xFF0F766E),
        Color(0xFF4338CA), Color(0xFFBE123C)
      )
      val darkPalette = listOf(
        Color(0xFF38BDF8), Color(0xFF34D399), Color(0xFFA78BFA),
        Color(0xFFFBBF24), Color(0xFFF472B6), Color(0xFF2DD4BF),
        Color(0xFF818CF8), Color(0xFFFB7185)
      )
      if (isLight) lightPalette[hash % lightPalette.size] else darkPalette[hash % darkPalette.size]
    }
  }
}

fun getReadingFolderContainerColor(folder: String, isLight: Boolean): Color {
  val base = getReadingFolderColor(folder, isLight)
  return if (isLight) base.copy(alpha = 0.12f) else base.copy(alpha = 0.22f)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingListScreen(
  onOpenUrl: (String) -> Unit = {},
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val focusManager = LocalFocusManager.current
  val dbState by RemmiDatabase.databaseState.collectAsState()
  val database = (dbState as? RemmiDatabase.DatabaseState.Ready)?.database
  val isLight = ThemeCyber.colors.isLight

  // Theme Colors - Enhanced for punchy, rock-solid visibility ("visibility tagadi")
  val bgColor = if (isLight) Color(0xFFF8FAFC) else Color(0xFF090D16)
  val cardBg = if (isLight) Color.White else Color(0xFF131B26)
  val borderColor = if (isLight) Color(0xFFE2E8F0) else Color(0xFF1E293B)
  val textColor = if (isLight) Color(0xFF0F172A) else Color(0xFFF8FAFC)
  val subtextColor = if (isLight) Color(0xFF475569) else Color(0xFF94A3B8)
  val rawPrimary = ThemeCyber.colors.primary
  // High contrast accent for text & interactive highlights
  val accentColor = if (isLight) {
    when {
      rawPrimary == Color(0xFFFFC400) || rawPrimary == Color(0xFFFFE600) -> Color(0xFFD97706) // Deep Amber
      rawPrimary == Color(0xFF00E5FF) || rawPrimary == Color(0xFF00FFFF) -> Color(0xFF0284C7) // Azure
      rawPrimary == Color(0xFF00E676) || rawPrimary == Color(0xFF00FF66) -> Color(0xFF059669) // Deep Emerald
      else -> Color(0xFF1D4ED8) // Deep Sapphire Blue
    }
  } else {
    rawPrimary
  }
  val primaryCyan = if (isLight) Color(0xFF0284C7) else Color(0xFF00E5FF)

  // Live Database Queries
  val allReadings by remember(database) {
    database?.readingListDao()?.getAllReadings() ?: kotlinx.coroutines.flow.flowOf(emptyList<com.remmi.browser.storage.ReadingListItem>())
  }.collectAsState(initial = emptyList<com.remmi.browser.storage.ReadingListItem>())
  val distinctFoldersFromDb by remember(database) {
    database?.readingListDao()?.getAllFolders() ?: kotlinx.coroutines.flow.flowOf(emptyList<String>())
  }.collectAsState(initial = emptyList<String>())

  // States
  var searchQuery by remember { mutableStateOf("") }
  var selectedFolder by remember { mutableStateOf("ALL") } // "ALL", "FAVORITES", "UNREAD", or specific folder name
  var currentSort by remember { mutableStateOf(ReadingSortOption.NEWEST) }
  var showSortMenu by remember { mutableStateOf(false) }
  var showNewFolderDialog by remember { mutableStateOf(false) }
  var newFolderNameInput by remember { mutableStateOf("") }

  // Item Action States
  var itemToChangeFolder by remember { mutableStateOf<ReadingListItem?>(null) }
  var itemToDelete by remember { mutableStateOf<ReadingListItem?>(null) }
  var showClearAllDialog by remember { mutableStateOf(false) }

  // Offline Reader Fullscreen Mode
  var offlineArticleToRead by remember { mutableStateOf<ReaderArticle?>(null) }
  var offlineReadingItemId by remember { mutableStateOf<Long?>(null) }

  // Compute Available Folders
  val defaultFolders = listOf("General", "Technology", "Research", "News", "AI & Science", "Crypto & Privacy", "Read Later")
  val allFolders = remember(distinctFoldersFromDb) {
    (defaultFolders + distinctFoldersFromDb).distinct().filter { it.isNotBlank() }
  }

  // Filtered & Sorted Readings
  val filteredReadings = remember(allReadings, searchQuery, selectedFolder, currentSort) {
    var list = allReadings

    // Search filter
    if (searchQuery.isNotBlank()) {
      val q = searchQuery.trim().lowercase()
      list = list.filter { item ->
        item.title.lowercase().contains(q) ||
          item.domain.lowercase().contains(q) ||
          item.folder.lowercase().contains(q) ||
          item.topic.lowercase().contains(q) ||
          item.excerpt.lowercase().contains(q) ||
          item.byline.lowercase().contains(q)
      }
    }

    // Folder / Category filter
    list = when (selectedFolder) {
      "ALL" -> list
      "FAVORITES" -> list.filter { it.isFavorite }
      "UNREAD" -> list.filter { !it.isRead }
      else -> list.filter { it.folder.equals(selectedFolder, ignoreCase = true) }
    }

    // Sort order
    when (currentSort) {
      ReadingSortOption.NEWEST -> list.sortedByDescending { it.savedAt }
      ReadingSortOption.OLDEST -> list.sortedBy { it.savedAt }
      ReadingSortOption.READING_TIME -> list.sortedByDescending { it.readingTimeMinutes }
      ReadingSortOption.ALPHABETICAL -> list.sortedBy { it.title.lowercase() }
      ReadingSortOption.WEBSITE -> list.sortedBy { it.domain.lowercase() }
    }
  }

  // Calculate Metrics
  val totalReadTimeMinutes = remember(filteredReadings) {
    filteredReadings.sumOf { it.readingTimeMinutes }
  }
  val unreadCount = remember(allReadings) {
    allReadings.count { !it.isRead }
  }
  val favoritesCount = remember(allReadings) {
    allReadings.count { it.isFavorite }
  }

  // If reading an offline article, show fullscreen ReaderView!
  if (offlineArticleToRead != null) {
    ReaderView(
      article = offlineArticleToRead,
      onClose = {
        offlineArticleToRead = null
        offlineReadingItemId = null
      },
      isGhostRoute = com.remmi.browser.security.CurrentTorRoute.isGhostActive,
      modifier = Modifier.fillMaxSize()
    )
    return
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(bgColor)
      .statusBarsPadding()
      .navigationBarsPadding()
  ) {
    Column(modifier = Modifier.fillMaxSize()) {

      // ==========================================
      // 1. BRAND HEADER BAR
      // ==========================================
      Surface(
        modifier = Modifier.fillMaxWidth(),
        color = cardBg,
        shadowElevation = if (isLight) 2.dp else 0.dp,
        border = BorderStroke(1.dp, borderColor)
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          // Left: Back button (Clean compact alignment)
          Surface(
            onClick = onDismiss,
            shape = RoundedCornerShape(8.dp),
            color = if (isLight) Color(0xFFF1F5F9) else Color(0xFF1E293B),
            border = BorderStroke(0.8.dp, if (isLight) Color(0xFFCBD5E1) else Color(0xFF334155)),
            modifier = Modifier
              .size(32.dp)
              .testTag("reading_list_back_btn")
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = textColor,
                modifier = Modifier.size(16.dp)
              )
            }
          }

          Spacer(modifier = Modifier.width(10.dp))

          // Middle: Title & Stat Badges (flexible weight)
          Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              Box(
                modifier = Modifier
                  .size(24.dp)
                  .clip(RoundedCornerShape(6.dp))
                  .background(getReadingFolderColor("all", isLight).copy(alpha = if (isLight) 0.15f else 0.25f)),
                contentAlignment = Alignment.Center
              ) {
                Icon(
                  imageVector = Icons.Default.MenuBook,
                  contentDescription = null,
                  tint = getReadingFolderColor("all", isLight),
                  modifier = Modifier.size(14.dp)
                )
              }
              Text(
                text = "Reading List",
                fontFamily = ThemeCyber.fontFamily,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }

            // Visual Stats Mini-Pills with high-contrast, vibrant tag colors
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
              Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (isLight) Color(0xFFEFF6FF) else Color(0xFF1E293B),
                border = BorderStroke(0.6.dp, if (isLight) Color(0xFFBFDBFE) else Color(0xFF334155))
              ) {
                Text(
                  text = "${allReadings.size} saved",
                  fontSize = 9.sp,
                  fontWeight = FontWeight.SemiBold,
                  color = if (isLight) Color(0xFF1D4ED8) else Color(0xFF60A5FA),
                  modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                  maxLines = 1,
                  softWrap = false
                )
              }

              if (unreadCount > 0) {
                Surface(
                  shape = RoundedCornerShape(6.dp),
                  color = if (isLight) Color(0xFFF0FDFA) else Color(0xFF132A32),
                  border = BorderStroke(0.6.dp, if (isLight) Color(0xFF99F6E4) else Color(0xFF0F766E))
                ) {
                  Text(
                    text = "${unreadCount} unread",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isLight) Color(0xFF0D9488) else Color(0xFF2DD4BF),
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                    maxLines = 1,
                    softWrap = false
                  )
                }
              }
            }
          }

          Spacer(modifier = Modifier.width(8.dp))

          // Right: Action Buttons (Add Folder, Sort) - Fixed sized containers with clear spacing so they never overlap
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.wrapContentWidth()
          ) {
            // New Folder Action
            Surface(
              onClick = {
                newFolderNameInput = ""
                showNewFolderDialog = true
              },
              shape = RoundedCornerShape(8.dp),
              color = getReadingFolderColor("general", isLight).copy(alpha = if (isLight) 0.12f else 0.22f),
              border = BorderStroke(0.8.dp, getReadingFolderColor("general", isLight).copy(alpha = 0.35f)),
              modifier = Modifier
                .size(32.dp)
                .testTag("reading_list_add_folder_btn")
            ) {
              Box(contentAlignment = Alignment.Center) {
                Icon(
                  imageVector = Icons.Default.CreateNewFolder,
                  contentDescription = "New Folder",
                  tint = getReadingFolderColor("general", isLight),
                  modifier = Modifier.size(16.dp)
                )
              }
            }

            // Sort Menu Action
            Box(modifier = Modifier.size(32.dp)) {
              Surface(
                onClick = { showSortMenu = true },
                shape = RoundedCornerShape(8.dp),
                color = if (isLight) Color(0xFFF1F5F9) else Color(0xFF1E293B),
                border = BorderStroke(0.8.dp, if (isLight) Color(0xFFCBD5E1) else Color(0xFF334155)),
                modifier = Modifier
                  .fillMaxSize()
                  .testTag("reading_list_sort_btn")
              ) {
                Box(contentAlignment = Alignment.Center) {
                  Icon(
                    imageVector = Icons.Default.Sort,
                    contentDescription = "Sort Articles",
                    tint = textColor,
                    modifier = Modifier.size(16.dp)
                  )
                }
              }

              DropdownMenu(
                expanded = showSortMenu,
                onDismissRequest = { showSortMenu = false },
                modifier = Modifier.background(cardBg)
              ) {
                ReadingSortOption.entries.forEach { option ->
                  DropdownMenuItem(
                    text = {
                      Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                      ) {
                        if (currentSort == option) {
                          Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(16.dp)
                          )
                        } else {
                          Spacer(modifier = Modifier.width(16.dp))
                        }
                        Text(
                          text = option.displayName,
                          fontSize = 13.sp,
                          fontWeight = if (currentSort == option) FontWeight.Bold else FontWeight.Normal,
                          color = if (currentSort == option) accentColor else textColor
                        )
                      }
                    },
                    onClick = {
                      currentSort = option
                      showSortMenu = false
                    }
                  )
                }

                HorizontalDivider(color = borderColor)

                if (allReadings.isNotEmpty()) {
                  DropdownMenuItem(
                    text = {
                      Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                      ) {
                        Icon(
                          imageVector = Icons.Default.DeleteSweep,
                          contentDescription = null,
                          tint = ThemeCyber.colors.dangerRed,
                          modifier = Modifier.size(16.dp)
                        )
                        Text(
                          text = "Clear All Readings",
                          fontSize = 13.sp,
                          color = ThemeCyber.colors.dangerRed
                        )
                      }
                    },
                    onClick = {
                      showSortMenu = false
                      showClearAllDialog = true
                    }
                  )
                }
              }
            }
          }
        }
      }

      // ==========================================
      // 2. SEARCH BAR
      // ==========================================
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 14.dp, vertical = 8.dp)
      ) {
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = cardBg,
          border = BorderStroke(1.dp, borderColor),
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Icon(
              imageVector = Icons.Default.Search,
              contentDescription = "Search",
              tint = subtextColor,
              modifier = Modifier.size(18.dp)
            )

            BasicTextField(
              value = searchQuery,
              onValueChange = { searchQuery = it },
              textStyle = TextStyle(
                color = textColor,
                fontSize = 13.sp,
                fontFamily = ThemeCyber.fontFamily
              ),
              cursorBrush = SolidColor(accentColor),
              singleLine = true,
              keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
              keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
              modifier = Modifier
                .weight(1f)
                .testTag("reading_list_search_input"),
              decorationBox = { innerTextField ->
                if (searchQuery.isEmpty()) {
                  Text(
                    text = "Search by title, website, topic or folder...",
                    color = subtextColor,
                    fontSize = 13.sp
                  )
                }
                innerTextField()
              }
            )

            if (searchQuery.isNotEmpty()) {
              Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Clear Search",
                tint = subtextColor,
                modifier = Modifier
                  .size(18.dp)
                  .clip(CircleShape)
                  .clickable { searchQuery = "" }
              )
            }
          }
        }
      }

      // ==========================================
      // 3. FOLDER / CATEGORY TABS CAROUSEL
      // ==========================================
      LazyRow(
        modifier = Modifier
          .fillMaxWidth()
          .padding(bottom = 6.dp),
        contentPadding = PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        // Tab 1: All
        item {
          FolderFilterChip(
            title = "All",
            count = allReadings.size,
            isSelected = selectedFolder == "ALL",
            icon = Icons.Default.Folder,
            folderColor = getReadingFolderColor("all", isLight),
            isLight = isLight,
            onClick = { selectedFolder = "ALL" }
          )
        }

        // Tab 2: Favorites
        item {
          FolderFilterChip(
            title = "Favorites",
            count = favoritesCount,
            isSelected = selectedFolder == "FAVORITES",
            icon = Icons.Default.Star,
            folderColor = getReadingFolderColor("favorites", isLight),
            isLight = isLight,
            onClick = { selectedFolder = "FAVORITES" }
          )
        }

        // Tab 3: Unread
        item {
          FolderFilterChip(
            title = "Unread",
            count = unreadCount,
            isSelected = selectedFolder == "UNREAD",
            icon = Icons.Default.Markunread,
            folderColor = getReadingFolderColor("unread", isLight),
            isLight = isLight,
            onClick = { selectedFolder = "UNREAD" }
          )
        }

        // Custom Folders from Database with Dedicated Vibrant Color Coding
        items(allFolders) { folder ->
          val folderCount = remember(allReadings, folder) {
            allReadings.count { it.folder.equals(folder, ignoreCase = true) }
          }
          FolderFilterChip(
            title = folder,
            count = folderCount,
            isSelected = selectedFolder.equals(folder, ignoreCase = true),
            icon = Icons.Default.FolderOpen,
            folderColor = getReadingFolderColor(folder, isLight),
            isLight = isLight,
            onClick = { selectedFolder = folder }
          )
        }

        // Quick Add Folder Button Chip
        item {
          Surface(
            shape = RoundedCornerShape(20.dp),
            color = if (isLight) Color(0xFFF1F5F9) else Color(0xFF1E293B),
            border = BorderStroke(1.dp, getReadingFolderColor("general", isLight).copy(alpha = 0.4f)),
            modifier = Modifier
              .clip(RoundedCornerShape(20.dp))
              .clickable {
                newFolderNameInput = ""
                showNewFolderDialog = true
              }
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
              Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Add Folder",
                tint = getReadingFolderColor("general", isLight),
                modifier = Modifier.size(16.dp)
              )
              Text(
                text = "New Folder",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isLight) Color(0xFF0F172A) else Color(0xFFF8FAFC)
              )
            }
          }
        }
      }

      // ==========================================
      // 4. SAVED READINGS LIST / EMPTY STATE
      // ==========================================
      if (filteredReadings.isEmpty()) {
        // Empty State View
        Box(
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(24.dp),
          contentAlignment = Alignment.Center
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
          ) {
            Box(
              modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(accentColor.copy(alpha = 0.12f)),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = Icons.Default.MenuBook,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(36.dp)
              )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
              text = if (searchQuery.isNotEmpty()) "No Matching Readings" else if (selectedFolder != "ALL") "Folder '$selectedFolder' is Empty" else "No Saved Readings Yet",
              fontFamily = ThemeCyber.fontFamily,
              fontSize = 16.sp,
              fontWeight = FontWeight.Bold,
              color = textColor
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
              text = if (searchQuery.isNotEmpty()) "Try searching for a different keyword or topic." else "While browsing, open any page in Reader Mode and tap the Bookmark/Save icon to keep it here for 100% offline reading.",
              fontSize = 12.sp,
              color = subtextColor,
              textAlign = TextAlign.Center,
              modifier = Modifier.widthIn(max = 300.dp)
            )

            if (selectedFolder != "ALL" || searchQuery.isNotEmpty()) {
              Spacer(modifier = Modifier.height(14.dp))
              Button(
                onClick = {
                  selectedFolder = "ALL"
                  searchQuery = ""
                },
                colors = ButtonDefaults.buttonColors(containerColor = getReadingFolderColor("all", isLight)),
                shape = RoundedCornerShape(8.dp)
              ) {
                Text("Show All Readings", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
              }
            }
          }
        }
      } else {
        // Readings LazyColumn
        LazyColumn(
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
          contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          items(filteredReadings, key = { it.id }) { reading ->
            ReadingItemCard(
              item = reading,
              isLight = isLight,
              cardBg = cardBg,
              borderColor = borderColor,
              textColor = textColor,
              subtextColor = subtextColor,
              accentColor = accentColor,
              onReadOffline = {
                // Parse offline JSON article or reconstruct
                val article = if (reading.contentJson.isNotBlank()) {
                  ReaderArticle.fromJson(reading.contentJson) ?: ReaderArticle(
                    title = reading.title,
                    siteName = reading.siteName,
                    excerpt = reading.excerpt,
                    sourceUrl = reading.url,
                    readingTimeMinutes = reading.readingTimeMinutes
                  )
                } else {
                  ReaderArticle(
                    title = reading.title,
                    siteName = reading.siteName,
                    excerpt = reading.excerpt,
                    sourceUrl = reading.url,
                    readingTimeMinutes = reading.readingTimeMinutes
                  )
                }
                offlineArticleToRead = article
                offlineReadingItemId = reading.id

                // Mark as read
                scope.launch(Dispatchers.IO) {
                  val db = RemmiDatabase.getDatabaseAsync(context)
                  db.readingListDao().updateReadStatus(reading.id, true)
                }
              },
              onOpenInBrowser = {
                onOpenUrl(reading.url)
                onDismiss()
              },
              onToggleFavorite = {
                scope.launch(Dispatchers.IO) {
                  val db = RemmiDatabase.getDatabaseAsync(context)
                  db.readingListDao().toggleFavorite(reading.id, !reading.isFavorite)
                }
              },
              onToggleRead = {
                scope.launch(Dispatchers.IO) {
                  val db = RemmiDatabase.getDatabaseAsync(context)
                  db.readingListDao().updateReadStatus(reading.id, !reading.isRead)
                }
              },
              onChangeFolder = {
                itemToChangeFolder = reading
              },
              onDelete = {
                itemToDelete = reading
              },
              onShare = {
                ReaderExporter.shareArticle(
                  context,
                  ReaderArticle(
                    title = reading.title,
                    sourceUrl = reading.url,
                    excerpt = reading.excerpt
                  )
                )
              }
            )
          }

          // Bottom padding spacer
          item {
            Spacer(modifier = Modifier.height(24.dp))
          }
        }
      }
    }
  }

  // ==========================================
  // 5. CREATE NEW FOLDER DIALOG
  // ==========================================
  if (showNewFolderDialog) {
    AlertDialog(
      onDismissRequest = { showNewFolderDialog = false },
      containerColor = cardBg,
      title = {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Icon(
            imageVector = Icons.Default.CreateNewFolder,
            contentDescription = null,
            tint = accentColor
          )
          Text(
            text = "Create Reading Folder",
            fontFamily = ThemeCyber.fontFamily,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = textColor
          )
        }
      },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(
            text = "Organize your offline articles into categorized topics and folders.",
            fontSize = 12.sp,
            color = subtextColor
          )

          OutlinedTextField(
            value = newFolderNameInput,
            onValueChange = { newFolderNameInput = it },
            label = { Text("Folder Name (e.g. Technology, AI, Finance)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = accentColor,
              unfocusedBorderColor = borderColor,
              focusedLabelColor = accentColor,
              unfocusedLabelColor = subtextColor
            )
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            val name = newFolderNameInput.trim()
            if (name.isNotBlank()) {
              selectedFolder = name
              showNewFolderDialog = false
              Toast.makeText(context, "Folder '$name' created!", Toast.LENGTH_SHORT).show()
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = accentColor),
          shape = RoundedCornerShape(8.dp)
        ) {
          Text("Create Folder", color = Color.Black, fontWeight = FontWeight.Bold)
        }
      },
      dismissButton = {
        TextButton(onClick = { showNewFolderDialog = false }) {
          Text("Cancel", color = subtextColor)
        }
      }
    )
  }

  // ==========================================
  // 6. MOVE TO FOLDER DIALOG
  // ==========================================
  if (itemToChangeFolder != null) {
    val currentItem = itemToChangeFolder!!
    var selectedTargetFolder by remember { mutableStateOf(currentItem.folder) }

    AlertDialog(
      onDismissRequest = { itemToChangeFolder = null },
      containerColor = cardBg,
      title = {
        Text(
          text = "Move Article to Folder",
          fontFamily = ThemeCyber.fontFamily,
          fontSize = 16.sp,
          fontWeight = FontWeight.Bold,
          color = textColor
        )
      },
      text = {
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Text(
            text = currentItem.title,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = textColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
          )

          HorizontalDivider(color = borderColor)

          Text(
            text = "Select Destination Folder:",
            fontSize = 11.sp,
            color = subtextColor
          )

          Column(
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(max = 220.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            allFolders.forEach { folderName ->
              val isSelected = selectedTargetFolder.equals(folderName, ignoreCase = true)
              val itemFolderColor = getReadingFolderColor(folderName, isLight)
              Surface(
                modifier = Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(8.dp))
                  .clickable { selectedTargetFolder = folderName },
                color = if (isSelected) itemFolderColor.copy(alpha = 0.15f) else (if (isLight) Color(0xFFF1F5F9) else Color(0xFF1E293B)),
                border = BorderStroke(1.dp, if (isSelected) itemFolderColor else borderColor)
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween
                ) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                  ) {
                    Icon(
                      imageVector = Icons.Default.Folder,
                      contentDescription = null,
                      tint = if (isSelected) itemFolderColor else (if (isLight) Color(0xFF64748B) else Color(0xFF94A3B8)),
                      modifier = Modifier.size(16.dp)
                    )
                    Text(
                      text = folderName,
                      fontSize = 13.sp,
                      fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                      color = if (isSelected) itemFolderColor else textColor
                    )
                  }

                  if (isSelected) {
                    Icon(
                      imageVector = Icons.Default.Check,
                      contentDescription = null,
                      tint = itemFolderColor,
                      modifier = Modifier.size(16.dp)
                    )
                  }
                }
              }
            }
          }
        }
      },
      confirmButton = {
        Button(
          onClick = {
            scope.launch(Dispatchers.IO) {
              val db = RemmiDatabase.getDatabaseAsync(context)
              db.readingListDao().updateFolder(currentItem.id, selectedTargetFolder)
              withContext(Dispatchers.Main) {
                Toast.makeText(context, "Moved to '$selectedTargetFolder'", Toast.LENGTH_SHORT).show()
                itemToChangeFolder = null
              }
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = accentColor),
          shape = RoundedCornerShape(8.dp)
        ) {
          Text("Move", color = Color.Black, fontWeight = FontWeight.Bold)
        }
      },
      dismissButton = {
        TextButton(onClick = { itemToChangeFolder = null }) {
          Text("Cancel", color = subtextColor)
        }
      }
    )
  }

  // ==========================================
  // 7. DELETE SINGLE ITEM CONFIRMATION DIALOG
  // ==========================================
  if (itemToDelete != null) {
    val item = itemToDelete!!
    AlertDialog(
      onDismissRequest = { itemToDelete = null },
      containerColor = cardBg,
      title = {
        Text(
          text = "Remove from Reading List?",
          fontFamily = ThemeCyber.fontFamily,
          fontSize = 16.sp,
          fontWeight = FontWeight.Bold,
          color = textColor
        )
      },
      text = {
        Text(
          text = "This will remove '${item.title}' and delete its local offline copy.",
          fontSize = 12.sp,
          color = subtextColor
        )
      },
      confirmButton = {
        Button(
          onClick = {
            scope.launch(Dispatchers.IO) {
              val db = RemmiDatabase.getDatabaseAsync(context)
              db.readingListDao().delete(item)
              withContext(Dispatchers.Main) {
                Toast.makeText(context, "Removed from Reading List", Toast.LENGTH_SHORT).show()
                itemToDelete = null
              }
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = ThemeCyber.colors.dangerRed),
          shape = RoundedCornerShape(8.dp)
        ) {
          Text("Remove", color = Color.White, fontWeight = FontWeight.Bold)
        }
      },
      dismissButton = {
        TextButton(onClick = { itemToDelete = null }) {
          Text("Cancel", color = subtextColor)
        }
      }
    )
  }

  // ==========================================
  // 8. CLEAR ALL READINGS CONFIRMATION DIALOG
  // ==========================================
  if (showClearAllDialog) {
    AlertDialog(
      onDismissRequest = { showClearAllDialog = false },
      containerColor = cardBg,
      title = {
        Text(
          text = "Clear All Saved Readings?",
          fontFamily = ThemeCyber.fontFamily,
          fontSize = 16.sp,
          fontWeight = FontWeight.Bold,
          color = ThemeCyber.colors.dangerRed
        )
      },
      text = {
        Text(
          text = "This will delete all saved articles and offline cached reading content permanently.",
          fontSize = 12.sp,
          color = subtextColor
        )
      },
      confirmButton = {
        Button(
          onClick = {
            scope.launch(Dispatchers.IO) {
              val db = RemmiDatabase.getDatabaseAsync(context)
              db.readingListDao().clearAll()
              withContext(Dispatchers.Main) {
                Toast.makeText(context, "All saved readings cleared", Toast.LENGTH_SHORT).show()
                showClearAllDialog = false
              }
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = ThemeCyber.colors.dangerRed),
          shape = RoundedCornerShape(8.dp)
        ) {
          Text("Clear All", color = Color.White, fontWeight = FontWeight.Bold)
        }
      },
      dismissButton = {
        TextButton(onClick = { showClearAllDialog = false }) {
          Text("Cancel", color = subtextColor)
        }
      }
    )
  }
}

/**
 * Filter Chip for Folders and Categories
 */
/**
 * Folder Filter Chip with distinctive category color signature and rock-solid contrast ("visibility tagadi").
 */
@Composable
private fun FolderFilterChip(
  title: String,
  count: Int,
  isSelected: Boolean,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  folderColor: Color,
  isLight: Boolean,
  onClick: () -> Unit
) {
  val chipBg = if (isSelected) {
    if (isLight) folderColor.copy(alpha = 0.14f) else folderColor.copy(alpha = 0.24f)
  } else {
    if (isLight) Color.White else Color(0xFF131B26)
  }
  val border = if (isSelected) folderColor else (if (isLight) Color(0xFFE2E8F0) else Color(0xFF1E293B))
  val contentColor = if (isSelected) folderColor else (if (isLight) Color(0xFF0F172A) else Color(0xFFE2E8F0))
  val iconTint = folderColor // Retain topic color for visual richness and scanning!

  Surface(
    shape = RoundedCornerShape(20.dp),
    color = chipBg,
    border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, border),
    shadowElevation = if (isLight && isSelected) 1.5.dp else 0.dp,
    modifier = Modifier
      .clip(RoundedCornerShape(20.dp))
      .clickable { onClick() }
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = iconTint,
        modifier = Modifier.size(15.dp)
      )

      Text(
        text = title,
        fontSize = 12.sp,
        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
        color = contentColor
      )

      if (count > 0) {
        Box(
          modifier = Modifier
            .clip(CircleShape)
            .background(if (isSelected) folderColor else (if (isLight) Color(0xFFEEF2F6) else Color(0xFF1E293B)))
            .padding(horizontal = 6.dp, vertical = 1.dp),
          contentAlignment = Alignment.Center
        ) {
          Text(
            text = "$count",
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            color = if (isSelected) Color.White else (if (isLight) Color(0xFF334155) else Color(0xFFE2E8F0))
          )
        }
      }
    }
  }
}

/**
 * Rich Card for Individual Saved Reading Item with COMPLETE info:
 * - Left vertical vibrant color bar (Topic / Folder color signature)
 * - Website domain & icon with high-contrast chip
 * - Folder / Topic Pill with vibrant background & border
 * - Exact Date & Time (formatted cleanly without overlapping)
 * - Reading Time (~5 min read) in warm amber pill
 * - Word Count in slate pill
 * - Punchy, luminous UNREAD / READ status badge
 * - Single-line "Offline Ready" emerald badge with check icon
 * - Excerpt
 * - Full offline reading action
 */
@Composable
private fun ReadingItemCard(
  item: ReadingListItem,
  isLight: Boolean,
  cardBg: Color,
  borderColor: Color,
  textColor: Color,
  subtextColor: Color,
  accentColor: Color,
  onReadOffline: () -> Unit,
  onOpenInBrowser: () -> Unit,
  onToggleFavorite: () -> Unit,
  onToggleRead: () -> Unit,
  onChangeFolder: () -> Unit,
  onDelete: () -> Unit,
  onShare: () -> Unit
) {
  var showMenu by remember { mutableStateOf(false) }

  val folderColor = getReadingFolderColor(item.folder, isLight)
  val folderContainer = getReadingFolderContainerColor(item.folder, isLight)

  // Format Date and Time cleanly with distinct separation
  val formattedDate = remember(item.savedAt) {
    SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(item.savedAt))
  }
  val formattedTime = remember(item.savedAt) {
    SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(item.savedAt))
  }

  Surface(
    shape = RoundedCornerShape(16.dp),
    color = cardBg,
    border = BorderStroke(
      width = if (item.isFavorite) 1.5.dp else 1.2.dp,
      color = if (item.isFavorite) Color(0xFFF59E0B) else (if (isLight) Color(0xFFE2E8F0) else Color(0xFF1E293B))
    ),
    shadowElevation = if (isLight) 3.dp else 0.dp,
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(16.dp))
      .clickable { onReadOffline() }
      .testTag("reading_item_${item.id}")
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .height(IntrinsicSize.Min)
    ) {
      // Left vertical accent strip with folder color
      Box(
        modifier = Modifier
          .width(5.dp)
          .fillMaxHeight()
          .background(folderColor)
      )

      Column(
        modifier = Modifier
          .weight(1f)
          .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        // ----------------------------------------------------
        // ROW 1: Website Domain, Topic/Folder Badge, Read Badge & Actions
        // ----------------------------------------------------
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          // Left: Domain & Folder & Read Status
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f, fill = false)
          ) {
            // Website Domain Badge
            Surface(
              shape = RoundedCornerShape(6.dp),
              color = if (isLight) Color(0xFFF1F5F9) else Color(0xFF1E293B),
              border = BorderStroke(1.dp, if (isLight) Color(0xFFCBD5E1) else Color(0xFF334155))
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.Language,
                  contentDescription = null,
                  tint = if (isLight) Color(0xFF1D4ED8) else Color(0xFF38BDF8),
                  modifier = Modifier.size(12.dp)
                )
                Text(
                  text = item.domain.ifBlank { "web" },
                  fontSize = 10.5.sp,
                  fontWeight = FontWeight.Bold,
                  color = if (isLight) Color(0xFF1E293B) else Color(0xFFF1F5F9),
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.widthIn(max = 100.dp)
                )
              }
            }

            // Folder / Category Badge
            Surface(
              shape = RoundedCornerShape(6.dp),
              color = folderContainer,
              border = BorderStroke(1.dp, folderColor.copy(alpha = if (isLight) 0.5f else 0.7f))
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.Folder,
                  contentDescription = null,
                  tint = folderColor,
                  modifier = Modifier.size(11.dp)
                )
                Text(
                  text = item.folder,
                  fontSize = 10.sp,
                  fontWeight = FontWeight.Bold,
                  color = folderColor,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.widthIn(max = 85.dp)
                )
              }
            }

            // Read Status Indicator
            if (!item.isRead) {
              Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (isLight) Color(0xFFE0F2FE) else Color(0xFF082F49),
                border = BorderStroke(1.dp, if (isLight) Color(0xFF7DD3FC) else Color(0xFF0284C7))
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.5.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                  Box(
                    modifier = Modifier
                      .size(5.dp)
                      .clip(CircleShape)
                      .background(if (isLight) Color(0xFF0284C7) else Color(0xFF38BDF8))
                  )
                  Text(
                    text = "UNREAD",
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isLight) Color(0xFF0369A1) else Color(0xFF38BDF8)
                  )
                }
              }
            }
          }

          // Right: Favorite Star & Context Menu (⋮)
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
          ) {
            IconButton(
              onClick = onToggleFavorite,
              modifier = Modifier.size(30.dp)
            ) {
              Icon(
                imageVector = if (item.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = "Favorite",
                tint = if (item.isFavorite) Color(0xFFF59E0B) else (if (isLight) Color(0xFF94A3B8) else Color(0xFF64748B)),
                modifier = Modifier.size(18.dp)
              )
            }

            Box(modifier = Modifier.size(30.dp)) {
              IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.fillMaxSize()
              ) {
                Icon(
                  imageVector = Icons.Default.MoreVert,
                  contentDescription = "Options",
                  tint = if (isLight) Color(0xFF64748B) else Color(0xFF94A3B8),
                  modifier = Modifier.size(18.dp)
                )
              }

              DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                modifier = Modifier.background(cardBg)
              ) {
                DropdownMenuItem(
                  text = { Text("Read Offline", fontWeight = FontWeight.SemiBold) },
                  leadingIcon = { Icon(Icons.Default.MenuBook, contentDescription = null, tint = folderColor) },
                  onClick = {
                    showMenu = false
                    onReadOffline()
                  }
                )
                DropdownMenuItem(
                  text = { Text("Open in Browser") },
                  leadingIcon = { Icon(Icons.Default.OpenInBrowser, contentDescription = null, tint = textColor) },
                  onClick = {
                    showMenu = false
                    onOpenInBrowser()
                  }
                )
                DropdownMenuItem(
                  text = { Text(if (item.isRead) "Mark as Unread" else "Mark as Read") },
                  leadingIcon = { Icon(Icons.Default.MarkChatRead, contentDescription = null, tint = textColor) },
                  onClick = {
                    showMenu = false
                    onToggleRead()
                  }
                )
                DropdownMenuItem(
                  text = { Text("Move to Folder...") },
                  leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null, tint = textColor) },
                  onClick = {
                    showMenu = false
                    onChangeFolder()
                  }
                )
                DropdownMenuItem(
                  text = { Text("Share Article") },
                  leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = textColor) },
                  onClick = {
                    showMenu = false
                    onShare()
                  }
                )
                HorizontalDivider(color = borderColor)
                DropdownMenuItem(
                  text = { Text("Remove from List", color = ThemeCyber.colors.dangerRed, fontWeight = FontWeight.Bold) },
                  leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = ThemeCyber.colors.dangerRed) },
                  onClick = {
                    showMenu = false
                    onDelete()
                  }
                )
              }
            }
          }
        }

        // ----------------------------------------------------
        // ROW 2: Article Title (Prominent, Bold, High Contrast)
        // ----------------------------------------------------
        Text(
          text = item.title.ifBlank { "Untitled Web Article" },
          fontFamily = ThemeCyber.fontFamily,
          fontSize = 15.5.sp,
          fontWeight = FontWeight.Bold,
          color = textColor,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          lineHeight = 21.sp
        )

        // ----------------------------------------------------
        // ROW 3: Excerpt / Preview Snippet
        // ----------------------------------------------------
        if (item.excerpt.isNotBlank()) {
          Text(
            text = item.excerpt,
            fontSize = 12.5.sp,
            color = if (isLight) Color(0xFF334155) else Color(0xFFCBD5E1),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 17.5.sp
          )
        }

        HorizontalDivider(
          color = if (isLight) Color(0xFFE2E8F0) else Color(0xFF1E293B),
          thickness = 1.dp
        )

        // ----------------------------------------------------
        // ROW 4: Footer Meta (Clean Spacing, High Contrast, Offline Pill)
        // ----------------------------------------------------
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          // Left: Date & Reading Time tag (clean single line without overflow)
          Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            // Calendar icon + Date
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(3.dp),
              modifier = Modifier.weight(1f, fill = false)
            ) {
              Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = null,
                tint = if (isLight) Color(0xFF64748B) else Color(0xFF94A3B8),
                modifier = Modifier.size(12.dp)
              )
              Text(
                text = formattedDate,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                color = if (isLight) Color(0xFF475569) else Color(0xFF94A3B8),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }

            // Reading Time Badge
            Surface(
              shape = RoundedCornerShape(4.dp),
              color = if (isLight) Color(0xFFFEF3C7) else Color(0xFF2D2310),
              border = BorderStroke(0.8.dp, if (isLight) Color(0xFFFDE68A) else Color(0xFF78350F))
            ) {
              Text(
                text = "~${item.readingTimeMinutes}m",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (isLight) Color(0xFFB45309) else Color(0xFFFBBF24),
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                maxLines = 1,
                softWrap = false
              )
            }
          }

          Spacer(modifier = Modifier.width(6.dp))

          // Right: Offline Ready Badge (compact, crisp emerald green)
          Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (isLight) Color(0xFFECFDF5) else Color(0xFF064E3B).copy(alpha = 0.6f),
            border = BorderStroke(1.dp, if (isLight) Color(0xFF6EE7B7) else Color(0xFF059669))
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.5.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
              Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = if (isLight) Color(0xFF059669) else Color(0xFF34D399),
                modifier = Modifier.size(11.dp)
              )
              Text(
                text = "Offline",
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                color = if (isLight) Color(0xFF065F46) else Color(0xFF6EE7B7),
                maxLines = 1,
                softWrap = false
              )
            }
          }
        }
      }
    }
  }
}
