package com.matchpoint.myaidietapp.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.matchpoint.myaidietapp.R
import kotlinx.coroutines.launch
import java.util.UUID

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
fun MenuScanPhotoScreen(
    isProcessing: Boolean,
    onUploadPhoto: suspend (scanId: String, uri: Uri) -> String,
    onSubmit: (menuPhotoUrl: String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var scanId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var localUri by remember { mutableStateOf<Uri?>(null) }
    var menuUrl by remember { mutableStateOf<String?>(null) }

    var isUploading by remember { mutableStateOf(false) }
    var pendingCameraAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showCameraSettingsDialog by remember { mutableStateOf(false) }
    val activity = remember(context) { context.findActivity() }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted) {
                pendingCameraAction?.invoke()
                pendingCameraAction = null
            } else {
                if (activity != null &&
                    !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                        activity,
                        Manifest.permission.CAMERA
                    )
                ) {
                    showCameraSettingsDialog = true
                }
            }
        }
    )

    fun ensureCameraPermissionAndRun(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            pendingCameraAction = action
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
        onResult = { success ->
            val uri = cameraUri
            if (success && uri != null) {
                localUri = uri
                isUploading = true
                scope.launch {
                    runCatching { onUploadPhoto(scanId, uri) }
                        .onSuccess { menuUrl = it }
                    isUploading = false
                }
            }
        }
    )

    val canSubmit = menuUrl != null && !isUploading && !isProcessing

    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "Back")
                }
                Image(
                    painter = painterResource(id = R.drawable.header_menu_scan),
                    contentDescription = "Menu scan",
                    modifier = Modifier
                        .align(Alignment.Center)
                        // ~3x bigger header
                        .height(210.dp),
                    contentScale = ContentScale.Fit
                )
            }
            Text(
                text = "Take a photo of the menu. I’ll read it and recommend the best options for your diet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            MenuPhotoBox(
                localUri = localUri,
                onClick = {
                    ensureCameraPermissionAndRun {
                        val uri = createImageUri(context)
                        cameraUri = uri
                        cameraLauncher.launch(uri)
                    }
                }
            )

            if (isUploading || isProcessing) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Working…")
                }
            }

            Button(
                onClick = {
                    val url = menuUrl ?: return@Button
                    onSubmit(url)
                    // reset for next scan
                    scanId = UUID.randomUUID().toString()
                    localUri = null
                    menuUrl = null
                },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Submit menu")
            }

            Text(
                text = "Use the phone back button to cancel.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

    if (showCameraSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showCameraSettingsDialog = false },
            title = { Text("Camera permission needed") },
            text = {
                Text(
                    "To take a photo, enable Camera permission in Settings.\n\n" +
                        "Settings → Apps → AI Diet Assistant → Permissions → Camera"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCameraSettingsDialog = false
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)
                        )
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                ) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(onClick = { showCameraSettingsDialog = false }) { Text("Cancel") }
            }
        )
    }
    }
}

@Composable
private fun MenuPhotoBox(
    localUri: Uri?,
    onClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = "Menu photo (Required)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, Color(0x33000000), RoundedCornerShape(12.dp))
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            if (localUri != null) {
                AsyncImage(
                    model = localUri,
                    contentDescription = "Menu photo",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    text = "Tap to take photo",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}


