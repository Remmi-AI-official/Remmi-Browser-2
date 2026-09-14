package com.remmi.browser.ui.passwords

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.remmi.browser.security.crypto.DecryptedPasswordEntry
import com.remmi.browser.ui.theme.ThemeCyber

@Composable
fun AddEditCredentialDialog(
  initialEntry: DecryptedPasswordEntry? = null,
  initialDomain: String? = null,
  onDismiss: () -> Unit,
  onSave: (url: String, user: String, pass: String, notes: String, id: Long) -> Unit,
  onDelete: ((Long) -> Unit)? = null,
) {
  var url by remember {
    mutableStateOf(
      initialEntry?.url
        ?: if (!initialDomain.isNullOrBlank()) "https://$initialDomain" else ""
    )
  }
  var username by remember { mutableStateOf(initialEntry?.username ?: "") }
  var password by remember { mutableStateOf(initialEntry?.password ?: "") }
  var notes by remember { mutableStateOf(initialEntry?.notes ?: "") }
  var showPassword by remember { mutableStateOf(false) }
  var showGenerator by remember { mutableStateOf(false) }

  val detectedDomain = remember(url) { PasswordManagerUtils.extractDomain(url) }
  val strength = remember(password) { PasswordManagerUtils.evaluateStrength(password) }

  if (showGenerator) {
    MilitaryPasswordGeneratorDialog(
      onDismiss = { showGenerator = false },
      onUsePassword = { generated ->
        password = generated
        showGenerator = false
      }
    )
  }

  Dialog(onDismissRequest = onDismiss) {
    Card(
      shape = RoundedCornerShape(20.dp),
      colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surface),
      border = BorderStroke(1.5.dp, ThemeCyber.colors.primary.copy(alpha = 0.5f)),
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 4.dp)
        .testTag("dialog_add_edit_credential")
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(20.dp)
      ) {
        // Header
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
              imageVector = Icons.Default.Key,
              contentDescription = null,
              tint = ThemeCyber.colors.primary,
              modifier = Modifier.size(20.dp)
            )
          }
          Spacer(modifier = Modifier.width(12.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = if (initialEntry == null) "Add Password" else "Edit Credential",
              color = ThemeCyber.colors.textPrimary,
              fontWeight = FontWeight.Bold,
              fontSize = 17.sp,
              fontFamily = ThemeCyber.fontFamily
            )
            Text(
              text = "Website Folder: $detectedDomain",
              color = ThemeCyber.colors.primary,
              fontSize = 11.5.sp,
              fontFamily = ThemeCyber.fontFamily
            )
          }
          IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = "Close",
              tint = ThemeCyber.colors.textSecondary
            )
          }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Website / Domain Field
        OutlinedTextField(
          value = url,
          onValueChange = { url = it },
          label = { Text("Website or Domain (e.g. google.com)", color = ThemeCyber.colors.textSecondary, fontSize = 12.5.sp) },
          leadingIcon = {
            Icon(Icons.Default.Language, contentDescription = null, tint = ThemeCyber.colors.textSecondary, modifier = Modifier.size(20.dp))
          },
          singleLine = true,
          shape = RoundedCornerShape(12.dp),
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = ThemeCyber.colors.primary,
            unfocusedBorderColor = ThemeCyber.colors.surfaceBorder,
            focusedTextColor = ThemeCyber.colors.textPrimary,
            unfocusedTextColor = ThemeCyber.colors.textPrimary,
          ),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("input_credential_url")
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Username / Email Field
        OutlinedTextField(
          value = username,
          onValueChange = { username = it },
          label = { Text("Username or Email", color = ThemeCyber.colors.textSecondary, fontSize = 12.5.sp) },
          leadingIcon = {
            Icon(Icons.Default.Person, contentDescription = null, tint = ThemeCyber.colors.textSecondary, modifier = Modifier.size(20.dp))
          },
          singleLine = true,
          shape = RoundedCornerShape(12.dp),
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = ThemeCyber.colors.primary,
            unfocusedBorderColor = ThemeCyber.colors.surfaceBorder,
            focusedTextColor = ThemeCyber.colors.textPrimary,
            unfocusedTextColor = ThemeCyber.colors.textPrimary,
          ),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("input_credential_username")
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Password Field + Generator Trigger
        OutlinedTextField(
          value = password,
          onValueChange = { password = it },
          label = { Text("Password", color = ThemeCyber.colors.textSecondary, fontSize = 12.5.sp) },
          leadingIcon = {
            Icon(Icons.Default.Key, contentDescription = null, tint = ThemeCyber.colors.textSecondary, modifier = Modifier.size(20.dp))
          },
          trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
              IconButton(onClick = { showGenerator = true }) {
                Icon(
                  imageVector = Icons.Default.AutoAwesome,
                  contentDescription = "Generate",
                  tint = ThemeCyber.colors.primary,
                  modifier = Modifier.size(20.dp)
                )
              }
              IconButton(onClick = { showPassword = !showPassword }) {
                Icon(
                  imageVector = if (showPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                  contentDescription = null,
                  tint = ThemeCyber.colors.textSecondary,
                  modifier = Modifier.size(20.dp)
                )
              }
            }
          },
          visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
          singleLine = true,
          shape = RoundedCornerShape(12.dp),
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = ThemeCyber.colors.primary,
            unfocusedBorderColor = ThemeCyber.colors.surfaceBorder,
            focusedTextColor = ThemeCyber.colors.textPrimary,
            unfocusedTextColor = ThemeCyber.colors.textPrimary,
          ),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("input_credential_password")
        )

        if (password.isNotEmpty()) {
          Spacer(modifier = Modifier.height(6.dp))
          LinearProgressIndicator(
            progress = { strength.scoreFraction },
            color = strength.color,
            trackColor = ThemeCyber.colors.surfaceBorder,
            modifier = Modifier
              .fillMaxWidth()
              .height(4.dp)
              .clip(RoundedCornerShape(2.dp))
          )
          Spacer(modifier = Modifier.height(4.dp))
          Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
          ) {
            Text(
              text = strength.label,
              color = strength.color,
              fontSize = 11.5.sp,
              fontWeight = FontWeight.SemiBold,
              fontFamily = ThemeCyber.fontFamily
            )
            Text(
              text = "${PasswordManagerUtils.calculateEntropyBits(password)} bits",
              color = ThemeCyber.colors.textSecondary,
              fontSize = 11.sp,
              fontFamily = ThemeCyber.fontFamily
            )
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Notes Field
        OutlinedTextField(
          value = notes,
          onValueChange = { notes = it },
          label = { Text("Notes (Optional)", color = ThemeCyber.colors.textSecondary, fontSize = 12.5.sp) },
          maxLines = 3,
          shape = RoundedCornerShape(12.dp),
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = ThemeCyber.colors.primary,
            unfocusedBorderColor = ThemeCyber.colors.surfaceBorder,
            focusedTextColor = ThemeCyber.colors.textPrimary,
            unfocusedTextColor = ThemeCyber.colors.textPrimary,
          ),
          modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Action Buttons
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth()
        ) {
          if (initialEntry != null && onDelete != null) {
            OutlinedButton(
              onClick = {
                onDelete(initialEntry.id)
                onDismiss()
              },
              border = BorderStroke(1.dp, ThemeCyber.colors.dangerRed),
              shape = RoundedCornerShape(12.dp),
              contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
              modifier = Modifier.weight(1f)
            ) {
              Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = null,
                tint = ThemeCyber.colors.dangerRed,
                modifier = Modifier.size(16.dp)
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = "Delete",
                color = ThemeCyber.colors.dangerRed,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = ThemeCyber.fontFamily,
                maxLines = 1,
                softWrap = false
              )
            }
          }

          Button(
            onClick = {
              if (url.isNotBlank() && password.isNotBlank()) {
                onSave(
                  url.trim(),
                  username.trim(),
                  password.trim(),
                  notes.trim(),
                  initialEntry?.id ?: 0L
                )
                onDismiss()
              }
            },
            enabled = url.isNotBlank() && password.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = ThemeCyber.colors.primary),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
            modifier = Modifier
              .weight(1f)
              .testTag("btn_save_credential")
          ) {
            Text(
              text = if (initialEntry == null) "Save to Folder" else "Update",
              color = Color.Black,
              fontWeight = FontWeight.Bold,
              fontSize = 12.5.sp,
              fontFamily = ThemeCyber.fontFamily,
              maxLines = 1,
              softWrap = false
            )
          }
        }
      }
    }
  }
}
