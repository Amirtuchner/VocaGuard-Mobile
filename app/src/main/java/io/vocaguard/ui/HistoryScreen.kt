package io.vocaguard.ui

import android.content.Intent
import android.media.MediaPlayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.collect
import io.vocaguard.data.CallTranscript
import io.vocaguard.data.ScamType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryTab(viewModel: HistoryViewModel = viewModel()) {
    val displayed    by viewModel.displayedTranscripts.collectAsStateWithLifecycle()
    val filter       by viewModel.filter.collectAsStateWithLifecycle()
    val hasMore      by viewModel.hasMore.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    var reportingTranscript by remember { mutableStateOf<CallTranscript?>(null) }
    var transcriptToDelete  by remember { mutableStateOf<CallTranscript?>(null) }
    var exportMenuExpanded  by remember { mutableStateOf(false) }
    var typeDropdownExpanded by remember { mutableStateOf(false) }

    val scamTypes = enumValues<ScamType>()
        .filter { it != ScamType.UNKNOWN }
        .map { it.name }

    val isFilterActive = filter.searchQuery.isNotBlank() ||
        filter.scamTypeFilter != null || filter.showScamOnly

    // Delete confirmation dialog
    transcriptToDelete?.let { transcript ->
        AlertDialog(
            onDismissRequest = { transcriptToDelete = null },
            title = { Text("Delete Transcript") },
            text = { Text("Delete this call transcript? This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.delete(transcript.id)
                        transcriptToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { transcriptToDelete = null }) { Text("Cancel") }
            }
        )
    }

    reportingTranscript?.let { transcript ->
        ReportScamDialog(
            initialPhoneNumber = transcript.phoneNumber,
            onConfirm = { phoneNumber, scamType ->
                viewModel.reportScamNumber(phoneNumber, scamType, transcript.id)
                reportingTranscript = null
            },
            onDismiss = { reportingTranscript = null }
        )
    }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.snackbarEvent.collect { event ->
            when (event) {
                is SnackbarEvent.Success -> snackbarHostState.showSnackbar(event.message)
                is SnackbarEvent.Error -> snackbarHostState.showSnackbar(
                    message = event.message,
                    duration = SnackbarDuration.Long
                )
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    if (displayed.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                if (isFilterActive) {
                    Text(
                        text = "No matching transcripts",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { viewModel.clearFilters() }) { Text("Clear filters") }
                } else {
                    Text(
                        text = "No call transcripts yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Transcripts appear here after monitored calls",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    } else {

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Header ────────────────────────────────────────────────────────────
        item {
            Spacer(modifier = Modifier.height(28.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Call History",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Box {
                    TextButton(onClick = { exportMenuExpanded = true }) { Text("Export") }
                    DropdownMenu(
                        expanded = exportMenuExpanded,
                        onDismissRequest = { exportMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Export as text") },
                            onClick = {
                                exportMenuExpanded = false
                                val text = viewModel.exportAsText()
                                ctx.startActivity(Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, text)
                                        putExtra(Intent.EXTRA_SUBJECT, "VocaGuard Call History")
                                    }, "Export transcripts"
                                ))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Export as CSV") },
                            onClick = {
                                exportMenuExpanded = false
                                val csv = viewModel.exportAsCsv()
                                ctx.startActivity(Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/csv"
                                        putExtra(Intent.EXTRA_TEXT, csv)
                                        putExtra(Intent.EXTRA_SUBJECT, "VocaGuard Call History")
                                    }, "Export CSV"
                                ))
                            }
                        )
                    }
                }
            }
        }

        // ── Search bar ────────────────────────────────────────────────────────
        item {
            OutlinedTextField(
                value = filter.searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = { Text("Search by number or transcript…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (filter.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.medium
            )
        }

        // ── Filter chips ──────────────────────────────────────────────────────
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                FilterChip(
                    selected = filter.showScamOnly,
                    onClick = { viewModel.setShowScamOnly(!filter.showScamOnly) },
                    label = { Text("Scams only") }
                )

                // Scam type chip
                ExposedDropdownMenuBox(
                    expanded = typeDropdownExpanded,
                    onExpandedChange = { typeDropdownExpanded = !typeDropdownExpanded }
                ) {
                    FilterChip(
                        selected = filter.scamTypeFilter != null,
                        onClick = { typeDropdownExpanded = true },
                        label = {
                            Text(
                                filter.scamTypeFilter
                                    ?.replace("_", " ")
                                    ?.lowercase()
                                    ?.replaceFirstChar { it.uppercase() }
                                    ?: "All types"
                            )
                        },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeDropdownExpanded)
                        },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = typeDropdownExpanded,
                        onDismissRequest = { typeDropdownExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("All types") },
                            onClick = { viewModel.setScamTypeFilter(null); typeDropdownExpanded = false }
                        )
                        scamTypes.forEach { type ->
                            DropdownMenuItem(
                                text = {
                                    Text(type.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() })
                                },
                                onClick = {
                                    viewModel.setScamTypeFilter(type)
                                    typeDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        // ── Recordings ────────────────────────────────────────────────────────
        item {
            val recordingsDir = File(ctx.getExternalFilesDir(null), "recordings")
            var recordings by remember {
                mutableStateOf(
                    recordingsDir.listFiles()
                        ?.filter { it.extension == "wav" }
                        ?.sortedByDescending { it.lastModified() }
                        ?: emptyList()
                )
            }
            if (recordings.isNotEmpty()) {
                var recordingsExpanded by remember { mutableStateOf(false) }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { recordingsExpanded = !recordingsExpanded },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Recordings (${recordings.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Icon(
                                imageVector = if (recordingsExpanded) Icons.Default.KeyboardArrowUp
                                              else Icons.Default.KeyboardArrowDown,
                                contentDescription = null
                            )
                        }
                        if (recordingsExpanded) {
                            Spacer(modifier = Modifier.height(8.dp))
                            recordings.forEach { file ->
                                RecordingItem(
                                    file = file,
                                    onDelete = {
                                        file.delete()
                                        recordings = recordingsDir.listFiles()
                                            ?.filter { it.extension == "wav" }
                                            ?.sortedByDescending { it.lastModified() }
                                            ?: emptyList()
                                    }
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                        }
                    }
                }
            }
        }

        // ── Transcript list ───────────────────────────────────────────────────
        items(displayed, key = { it.id }) { transcript ->
            TranscriptCard(
                transcript = transcript,
                onDelete = { transcriptToDelete = transcript },
                onReport = { reportingTranscript = transcript },
                onWhitelist = {
                    if (transcript.phoneNumber.isNotEmpty()) {
                        viewModel.addToWhitelist(transcript.phoneNumber)
                    }
                },
                onMarkFalsePositive = { viewModel.markAsFalsePositive(transcript.id) }
            )
        }

        // ── Load more ─────────────────────────────────────────────────────────
        if (hasMore) {
            item {
                OutlinedButton(
                    onClick = { viewModel.loadMore() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Load more") }
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
    } // end else
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter)
    )
    } // end outer Box
}

@Composable
fun TranscriptCard(
    transcript: CallTranscript,
    onDelete: () -> Unit,
    onReport: () -> Unit,
    onWhitelist: () -> Unit,
    onMarkFalsePositive: () -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }
    var markedFalsePositive by remember(transcript.id) { mutableStateOf(transcript.isFalsePositive) }
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.ENGLISH) }
    val isScam = transcript.detectedScamTypes.isNotEmpty()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isScam && markedFalsePositive -> MaterialTheme.colorScheme.surfaceVariant
                isScam -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = dateFormat.format(Date(transcript.timestamp)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (transcript.phoneNumber.isNotEmpty()) {
                        Text(
                            text = transcript.phoneNumber,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                if (transcript.phoneNumber.isNotEmpty()) {
                    var whitelisted by remember { mutableStateOf(false) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = {
                            onWhitelist()
                            whitelisted = true
                        }) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = "Whitelist number",
                                tint = if (whitelisted) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "Safe",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = onReport) {
                        Icon(
                            imageVector = Icons.Default.Flag,
                            contentDescription = "Report scam",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "Report",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // "Not a scam" button — only shown for scam-flagged, not yet corrected entries
                if (isScam && !markedFalsePositive) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = {
                            markedFalsePositive = true
                            onMarkFalsePositive()
                        }) {
                            Icon(
                                imageVector = Icons.Default.ThumbDown,
                                contentDescription = "Mark as not a scam",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "Not scam",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "Delete",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isScam && markedFalsePositive) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Marked as not a scam",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (isScam) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    transcript.detectedScamTypes.forEach { type ->
                        SuggestionChip(
                            onClick = {},
                            label = {
                                Text(
                                    text = type.replace("_", " ")
                                        .lowercase()
                                        .replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (expanded) transcript.text
                       else transcript.text.take(120) + if (transcript.text.length > 120) "…" else "",
                style = MaterialTheme.typography.bodySmall
            )

            if (transcript.text.length > 120) {
                Row(
                    modifier = Modifier.clickable { expanded = !expanded }.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (expanded) "Show less" else "Show more",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp
                                      else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScamDialog(
    initialPhoneNumber: String,
    onConfirm: (phoneNumber: String, scamType: ScamType) -> Unit,
    onDismiss: () -> Unit
) {
    var phoneNumber by remember { mutableStateOf(initialPhoneNumber) }
    var selectedScamType by remember { mutableStateOf(ScamType.IRS_SCAM) }
    var expanded by remember { mutableStateOf(false) }

    val scamTypes = enumValues<ScamType>().filter { it != ScamType.UNKNOWN }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report Scam Number") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = phoneNumber,
                    onValueChange = { phoneNumber = it },
                    label = { Text("Phone Number") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = selectedScamType.name.replace("_", " ").lowercase()
                            .replaceFirstChar { it.uppercase() },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Scam Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        scamTypes.forEach { scamType ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        scamType.name.replace("_", " ").lowercase()
                                            .replaceFirstChar { it.uppercase() }
                                    )
                                },
                                onClick = {
                                    selectedScamType = scamType
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(phoneNumber, selectedScamType) },
                enabled = phoneNumber.isNotBlank()
            ) { Text("Report") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun RecordingItem(file: File, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    val dateFmt = remember { SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.ENGLISH) }
    val sizeKb = file.length() / 1024
    val sizeText = if (sizeKb > 1024) "%.1f MB".format(sizeKb / 1024f) else "$sizeKb KB"
    var playing by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Recording") },
            text = { Text("Delete ${file.name}?") },
            confirmButton = {
                Button(
                    onClick = { showDeleteConfirm = false; player?.release(); player = null; onDelete() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
        )
    }

    DisposableEffect(file.absolutePath) {
        onDispose { player?.release(); player = null }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = dateFmt.format(Date(file.lastModified())),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = sizeText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // Play/Stop
        IconButton(onClick = {
            if (playing) {
                player?.stop()
                player?.release()
                player = null
                playing = false
            } else {
                val mp = MediaPlayer()
                mp.setDataSource(file.absolutePath)
                mp.prepare()
                mp.setOnCompletionListener { playing = false; it.release(); player = null }
                mp.start()
                player = mp
                playing = true
            }
        }) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = if (playing) "Stop" else "Play",
                tint = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // Share
        IconButton(onClick = {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            ctx.startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "audio/wav"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share recording"
            ))
        }) {
            Icon(Icons.Default.Share, contentDescription = "Share", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Delete
        IconButton(onClick = { showDeleteConfirm = true }) {
            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
