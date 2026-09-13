package com.remmi.browser.ui.passwords

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.remmi.browser.security.PasswordManagerRepository
import com.remmi.browser.security.crypto.PasswordBackupManager
import com.remmi.browser.security.crypto.PasswordCryptoEngine
import com.remmi.browser.storage.RemmiDatabase
import com.remmi.browser.ui.theme.ThemeCyber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

@Composable
fun BackupRestoreDialog(
  dek: ByteArray,
  repo: PasswordManagerRepository,
  onDismiss: () -> Unit,
  onRefresh: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  var selectedTab by remember { mutableIntStateOf(0) } // 0: Export to File, 1: Import from File
  var backupPassword by remember { mutableStateOf("") }
  var showPassword by remember { mutableStateOf(false) }
  var isProcessing by remember { mutableStateOf(false) }
  var statusMessage by remember { mutableStateOf("") }

  // SAF File Save Launcher: Creates file on device without touching clipboard
  val createDocumentLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("application/octet-stream")
  ) { uri: Uri? ->
    if (uri != null) {
      isProcessing = true
      statusMessage = "Encrypting vault and saving file..."
      scope.launch(Dispatchers.IO) {
        try {
          val db = RemmiDatabase.getDatabaseAsync(context)
          val entities = db.passwordEntryDao().getAllEntriesList()
          val backupJson = PasswordBackupManager.exportEncryptedBackup(
            entries = entities,
            exportPassword = if (backupPassword.isNotBlank()) backupPassword.toCharArray() else null,
            dek = dek
          )

          context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            outputStream.write(backupJson.toByteArray(StandardCharsets.UTF_8))
            outputStream.flush()
          } ?: throw IllegalStateException("Could not open destination file for writing.")

          withContext(Dispatchers.Main) {
            isProcessing = false
            Toast.makeText(
              context,
              "Backup file saved successfully (${entities.size} credentials)!",
              Toast.LENGTH_LONG
            ).show()
            onDismiss()
          }
        } catch (e: Exception) {
          withContext(Dispatchers.Main) {
            isProcessing = false
            Toast.makeText(context, "Export error: ${e.message}", Toast.LENGTH_LONG).show()
          }
        }
      }
    }
  }

  // SAF File Open Launcher: Opens and restores backup file directly from storage
  val openDocumentLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
  ) { uri: Uri? ->
    if (uri != null) {
      isProcessing = true
      statusMessage = "Reading encrypted backup file..."
      scope.launch(Dispatchers.IO) {
        try {
          val jsonString = context.contentResolver.openInputStream(uri)?.use { inputStream ->
            inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
          } ?: throw IllegalStateException("Could not read backup file.")

          withContext(Dispatchers.Main) {
            statusMessage = "Verifying HMAC-SHA256 integrity & decrypting..."
          }

          val (backupDek, restoredEntries) = PasswordBackupManager.importEncryptedBackup(
            backupJsonString = jsonString,
            exportPassword = if (backupPassword.isNotBlank()) backupPassword.toCharArray() else null,
            currentDek = dek
          )

          var importedCount = 0
          for (item in restoredEntries) {
            try {
              val url = String(PasswordCryptoEngine.decryptAesGcm(backupDek, item.siteUrlEncrypted, item.iv, item.authTag), StandardCharsets.UTF_8)
              val user = String(PasswordCryptoEngine.decryptAesGcm(backupDek, item.usernameEncrypted, item.iv, item.authTag), StandardCharsets.UTF_8)
              val pass = String(PasswordCryptoEngine.decryptAesGcm(backupDek, item.passwordEncrypted, item.iv, item.authTag), StandardCharsets.UTF_8)
              val notes = String(PasswordCryptoEngine.decryptAesGcm(backupDek, item.notesEncrypted, item.iv, item.authTag), StandardCharsets.UTF_8)

              repo.saveOrUpdateEntry(
                url = url,
                username = user,
                password = pass,
                notes = notes,
              )
              importedCount++
            } catch (_: Exception) {}
          }

          withContext(Dispatchers.Main) {
            isProcessing = false
            Toast.makeText(
              context,
              "Restored $importedCount credentials from file successfully!",
              Toast.LENGTH_LONG
            ).show()
            onRefresh()
            onDismiss()
          }
        } catch (e: Exception) {
          withContext(Dispatchers.Main) {
            isProcessing = false
            Toast.makeText(context, "Restore failed: ${e.message}", Toast.LENGTH_LONG).show()
          }
        }
      }
    }
  }

  Dialog(onDismissRequest = { if (!isProcessing) onDismiss() }) {
    Card(
      shape = RoundedCornerShape(20.dp),
      colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surface),
      border = BorderStroke(1.5.dp, ThemeCyber.colors.primary.copy(alpha = 0.5f)),
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 4.dp)
        .testTag("dialog_backup_restore")
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(20.dp)
      ) {
        // Top Header
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth()
        ) {
          Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
              .size(36.dp)
              .clip(RoundedCornerShape(10.dp))
              .background(ThemeCyber.colors.primary.copy(alpha = 0.15f))
          ) {
            Icon(
              imageVector = Icons.Default.Security,
              contentDescription = null,
              tint = ThemeCyber.colors.primary,
              modifier = Modifier.size(20.dp)
            )
          }
          Spacer(modifier = Modifier.width(12.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "Vault Backup & Restore",
              color = ThemeCyber.colors.textPrimary,
              fontWeight = FontWeight.Bold,
              fontSize = 17.sp,
              fontFamily = ThemeCyber.fontFamily
            )
            Text(
              text = "File-based • Zero Clipboard • AES-256-GCM",
              color = ThemeCyber.colors.primary,
              fontSize = 11.sp,
              fontFamily = ThemeCyber.fontFamily
            )
          }
          if (!isProcessing) {
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
              Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = ThemeCyber.colors.textSecondary
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Tabs
        TabRow(
          selectedTabIndex = selectedTab,
          containerColor = ThemeCyber.colors.surfaceLight,
          contentColor = ThemeCyber.colors.primary,
          indicator = { tabPositions ->
            TabRowDefaults.SecondaryIndicator(
              modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
              color = ThemeCyber.colors.primary,
              height = 3.dp
            )
          },
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
        ) {
          Tab(
            selected = selectedTab == 0,
            onClick = { if (!isProcessing) selectedTab = 0 },
            text = {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Save Backup", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, fontFamily = ThemeCyber.fontFamily)
              }
            }
          )
          Tab(
            selected = selectedTab == 1,
            onClick = { if (!isProcessing) selectedTab = 1 },
            text = {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Restore File", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, fontFamily = ThemeCyber.fontFamily)
              }
            }
          )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Optional Encryption Passphrase
        OutlinedTextField(
          value = backupPassword,
          onValueChange = { backupPassword = it },
          enabled = !isProcessing,
          label = {
            Text(
              if (selectedTab == 0) "Backup Encryption Password (Optional)" else "Backup Password (If Protected)",
              color = ThemeCyber.colors.textSecondary,
              fontSize = 12.sp
            )
          },
          visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
          trailingIcon = {
            IconButton(onClick = { showPassword = !showPassword }) {
              Icon(
                imageVector = if (showPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                contentDescription = null,
                tint = ThemeCyber.colors.textSecondary
              )
            }
          },
          singleLine = true,
          shape = RoundedCornerShape(12.dp),
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = ThemeCyber.colors.primary,
            unfocusedBorderColor = ThemeCyber.colors.surfaceBorder,
            focusedTextColor = ThemeCyber.colors.textPrimary,
            unfocusedTextColor = ThemeCyber.colors.textPrimary,
          ),
          modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Info Card
        Card(
          shape = RoundedCornerShape(12.dp),
          colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surfaceLight),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(12.dp)) {
            Text(
              text = if (selectedTab == 0)
                "• Saves an encrypted .enc file directly to your device storage.\n• Does NOT copy text to clipboard for maximum security.\n• Protected with Argon2id, AES-256-GCM, and HMAC-SHA256 tamper seal."
              else
                "• Select a previously saved .enc backup file.\n• Restores all accounts and merges with existing credentials.\n• Verifies tamper-proof cryptographic signature before decryption.",
              color = ThemeCyber.colors.textSecondary,
              fontSize = 11.5.sp,
              lineHeight = 16.sp,
              fontFamily = ThemeCyber.fontFamily
            )
          }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isProcessing) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
          ) {
            CircularProgressIndicator(
              color = ThemeCyber.colors.primary,
              strokeWidth = 3.dp,
              modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
              text = statusMessage,
              color = ThemeCyber.colors.primary,
              fontSize = 12.5.sp,
              fontWeight = FontWeight.Medium,
              fontFamily = ThemeCyber.fontFamily
            )
          }
        } else {
          if (selectedTab == 0) {
            Button(
              onClick = {
                val fileName = "remmi_vault_backup_${System.currentTimeMillis()}.enc"
                createDocumentLauncher.launch(fileName)
              },
              colors = ButtonDefaults.buttonColors(containerColor = ThemeCyber.colors.primary),
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier
                .fillMaxWidth()
                .testTag("btn_export_save_file")
            ) {
              Icon(Icons.Default.Download, contentDescription = null, tint = Color.Black)
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = "Save Backup to File",
                color = Color.Black,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                fontFamily = ThemeCyber.fontFamily
              )
            }
          } else {
            Button(
              onClick = {
                openDocumentLauncher.launch(arrayOf("*/*"))
              },
              colors = ButtonDefaults.buttonColors(containerColor = ThemeCyber.colors.primary),
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier
                .fillMaxWidth()
                .testTag("btn_import_open_file")
            ) {
              Icon(Icons.Default.FileUpload, contentDescription = null, tint = Color.Black)
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = "Choose Backup File (.enc)",
                color = Color.Black,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                fontFamily = ThemeCyber.fontFamily
              )
            }
          }
        }
      }
    }
  }
}
