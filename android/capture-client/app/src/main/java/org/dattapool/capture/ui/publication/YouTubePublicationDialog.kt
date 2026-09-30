package org.dattapool.capture.ui.publication

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.dattapool.capture.publication.MediaPublication
import org.dattapool.capture.youtube.UploadProgress
import org.dattapool.capture.youtube.YouTubeUploadStatus
import org.dattapool.capture.youtube.YouTubeVisibility

@Composable
fun YouTubePublicationDialog(
    publication: MediaPublication?,
    uploadProgress: UploadProgress,
    onDismiss: () -> Unit,
    onStartUpload: (visibility: YouTubeVisibility, includeMetadata: Boolean, customToken: String?) -> Unit,
    onOpenVideo: (String) -> Unit
) {
    val context = LocalContext.current
    var selectedVisibility by remember { mutableStateOf(YouTubeVisibility.UNLISTED) }
    var includeMetadata by remember { mutableStateOf(true) }
    var showTokenInput by remember { mutableStateOf(false) }
    var customToken by remember { mutableStateOf("") }

    Dialog(onDismissRequest = {
        if (uploadProgress.status != YouTubeUploadStatus.UPLOADING &&
            uploadProgress.status != YouTubeUploadStatus.AUTHORIZING &&
            uploadProgress.status != YouTubeUploadStatus.NOSTR_ANNOUNCING
        ) {
            onDismiss()
        }
    }) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .wrapContentHeight(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.PlayCircle,
                            contentDescription = null,
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (publication != null || uploadProgress.status == YouTubeUploadStatus.AVAILABLE || uploadProgress.status == YouTubeUploadStatus.PUBLISHED) {
                                "YOUTUBE PUBLICATION"
                            } else {
                                "UPLOAD TO YOUTUBE"
                            },
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    if (uploadProgress.status != YouTubeUploadStatus.UPLOADING &&
                        uploadProgress.status != YouTubeUploadStatus.AUTHORIZING
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Mode 1: Already Published or Available
                if (publication != null || uploadProgress.status == YouTubeUploadStatus.AVAILABLE || uploadProgress.status == YouTubeUploadStatus.PUBLISHED) {
                    val pub = publication
                    val videoId = pub?.videoId ?: "Processing"
                    val reqVis = pub?.requestedVisibility ?: selectedVisibility.apiValue
                    val actVis = pub?.actualVisibility ?: selectedVisibility.apiValue
                    val watchUrl = pub?.watchUrl ?: "https://youtu.be/$videoId"

                    // Success Banner
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFF064E3B),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF34D399), modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Capture Published to YouTube",
                                    color = Color(0xFF34D399),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Distribution & Discovery Representation Available",
                                    color = Color(0xFFA7F3D0),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Status and Visibility Info
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Status", color = Color(0xFF94A3B8), fontSize = 12.sp)
                                Text("Published ✓", color = Color(0xFF10B981), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Requested Visibility", color = Color(0xFF94A3B8), fontSize = 12.sp)
                                Text(reqVis.replaceFirstChar { it.uppercase() }, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Actual Visibility", color = Color(0xFF94A3B8), fontSize = 12.sp)
                                Text(
                                    text = actVis.replaceFirstChar { it.uppercase() },
                                    color = if (actVis.equals("private", ignoreCase = true) && !reqVis.equals("private", ignoreCase = true)) {
                                        Color(0xFFF59E0B)
                                    } else {
                                        Color(0xFF38BDF8)
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            if (actVis.equals("private", ignoreCase = true) && !reqVis.equals("private", ignoreCase = true)) {
                                Text(
                                    text = "Note: YouTube API project restriction enforced Private visibility.",
                                    color = Color(0xFFFBBF24),
                                    fontSize = 10.sp
                                )
                            }

                            HorizontalDivider(color = Color(0xFF334155), modifier = Modifier.padding(vertical = 2.dp))

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Video ID", color = Color(0xFF94A3B8), fontSize = 12.sp)
                                Text(videoId, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            }

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("DattaPool Metadata", color = Color(0xFF94A3B8), fontSize = 12.sp)
                                Text("Included ✓", color = Color(0xFF10B981), fontSize = 12.sp)
                            }

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Nostr Availability", color = Color(0xFF94A3B8), fontSize = 12.sp)
                                Text("Published ✓", color = Color(0xFF10B981), fontSize = 12.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onOpenVideo(watchUrl) },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("OPEN VIDEO", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("YouTube URL", watchUrl))
                                Toast.makeText(context, "Copied video link to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF38BDF8)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("COPY LINK", fontSize = 12.sp)
                        }
                    }
                }
                // Mode 2: In-Progress Upload / Processing
                else if (uploadProgress.status == YouTubeUploadStatus.UPLOADING ||
                    uploadProgress.status == YouTubeUploadStatus.AUTHORIZING ||
                    uploadProgress.status == YouTubeUploadStatus.PROCESSING ||
                    uploadProgress.status == YouTubeUploadStatus.NOSTR_ANNOUNCING
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFFEF4444),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        val statusText = when (uploadProgress.status) {
                            YouTubeUploadStatus.AUTHORIZING -> "Authorizing with YouTube..."
                            YouTubeUploadStatus.UPLOADING -> "Uploading Video (${uploadProgress.progressPercent}%)..."
                            YouTubeUploadStatus.PROCESSING -> "Processing on YouTube..."
                            YouTubeUploadStatus.NOSTR_ANNOUNCING -> "Publishing Nostr Availability Announcement..."
                            else -> "Publishing..."
                        }

                        Text(
                            text = statusText,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )

                        if (uploadProgress.status == YouTubeUploadStatus.UPLOADING) {
                            Spacer(modifier = Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { (uploadProgress.progressPercent / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp),
                                color = Color(0xFFEF4444),
                                trackColor = Color(0xFF334155)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "${uploadProgress.progressPercent}% of ${(uploadProgress.totalBytes / 1024 / 1024)} MB",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp
                            )
                        }
                    }
                }
                // Mode 3: Upload Configuration & Trigger Screen (Default)
                else {
                    if (uploadProgress.status == YouTubeUploadStatus.FAILED) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF7F1D1D),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Error, contentDescription = null, tint = Color(0xFFF87171), modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = uploadProgress.errorMessage ?: "Upload failed. You can retry.",
                                    color = Color(0xFFFECACA),
                                    fontSize = 11.sp
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Visibility Selector
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "Visibility",
                                color = Color(0xFF38BDF8),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            YouTubeVisibility.entries.forEach { vis ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedVisibility = vis }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = selectedVisibility == vis,
                                        onClick = { selectedVisibility = vis },
                                        colors = RadioButtonDefaults.colors(
                                            selectedColor = Color(0xFFEF4444),
                                            unselectedColor = Color(0xFF64748B)
                                        )
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Column {
                                        Text(
                                            text = vis.displayLabel,
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = if (selectedVisibility == vis) FontWeight.Bold else FontWeight.Normal
                                        )
                                        if (vis == YouTubeVisibility.UNLISTED) {
                                            Text(
                                                text = "Default for development / discovery testing",
                                                color = Color(0xFF94A3B8),
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Metadata Checkbox
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { includeMetadata = !includeMetadata }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = includeMetadata,
                            onCheckedChange = { includeMetadata = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = Color(0xFF0284C7),
                                checkmarkColor = Color.White
                            )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text("Include DattaPool metadata", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("Embeds machine-readable capture chain & manifest hash", color = Color(0xFF94A3B8), fontSize = 10.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Optional Custom Token Toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showTokenInput = !showTokenInput }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (showTokenInput) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "OAuth Access Token (Account / Custom)",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }

                    if (showTokenInput) {
                        OutlinedTextField(
                            value = customToken,
                            onValueChange = { customToken = it },
                            placeholder = { Text("Paste OAuth token (ya29...) to override", fontSize = 11.sp, color = Color(0xFF64748B)) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF38BDF8),
                                unfocusedBorderColor = Color(0xFF475569)
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Action Buttons
                    Button(
                        onClick = {
                            onStartUpload(
                                selectedVisibility,
                                includeMetadata,
                                customToken.trim().ifBlank { null }
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = "Upload", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (uploadProgress.status == YouTubeUploadStatus.FAILED) "RETRY UPLOAD" else "UPLOAD",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
