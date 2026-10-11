package io.vocaguard.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val features = listOf(
    "Real-time AI scam call detection",
    "Instant on-screen alerts",
    "Family Guard — notify caregivers automatically",
    "Full call history with transcripts",
    "Senior Mode with simplified interface",
)

@Composable
fun PaywallScreen(
    onSubscribeClick: () -> Unit,
    onRestoreClick: () -> Unit,
    priceLine: String? = null,
    // Returns true if the reviewer/testing code was accepted (premium granted).
    onRedeemCode: (String) -> Boolean = { false },
) {
    // Hidden reviewer access: tap the title 7× to open a code entry (for Google
    // app review — the code is supplied in Play Console → App access).
    var titleTaps by remember { mutableStateOf(0) }
    var showCodeDialog by remember { mutableStateOf(false) }
    var codeText by remember { mutableStateOf("") }
    var codeError by remember { mutableStateOf(false) }

    if (showCodeDialog) {
        AlertDialog(
            onDismissRequest = { showCodeDialog = false; codeError = false },
            title = { Text("Enter access code") },
            text = {
                Column {
                    OutlinedTextField(
                        value = codeText,
                        onValueChange = { codeText = it; codeError = false },
                        singleLine = true,
                        isError = codeError,
                        label = { Text("Code") },
                    )
                    if (codeError) {
                        Text(
                            "Invalid code",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (onRedeemCode(codeText)) showCodeDialog = false else codeError = true
                }) { Text("Unlock") }
            },
            dismissButton = {
                TextButton(onClick = { showCodeDialog = false; codeError = false }) { Text("Cancel") }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Shield,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "VocaGuard",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable {
                titleTaps++
                if (titleTaps >= 7) { titleTaps = 0; showCodeDialog = true }
            },
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = "Protect every call with AI",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(32.dp))

        features.forEach { feature ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = feature,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }

        Spacer(Modifier.height(40.dp))

        Button(
            onClick = onSubscribeClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text(
                text = "Start free trial",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = priceLine ?: "1 month free · then $4.99/month · cancel any time",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(16.dp))

        TextButton(onClick = onRestoreClick) {
            Text("Restore purchase")
        }
    }
}
