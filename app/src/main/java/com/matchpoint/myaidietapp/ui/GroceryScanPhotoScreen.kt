package com.matchpoint.myaidietapp.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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

@Composable
fun GroceryScanPhotoScreen(
    isProcessing: Boolean,
    onUploadPhoto: suspend (scanId: String, kind: String, uri: Uri) -> String,
    onSubmit: (productUrl: String, labelUrl: String?, nutritionFactsUrl: String?) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var scanId by remember { mutableStateOf(UUID.randomUUID().toString()) }

    var productLocalUri by remember { mutableStateOf<Uri?>(null) }
    var labelLocalUri by remember { mutableStateOf<Uri?>(null) }
    var nutritionLocalUri by remember { mutableStateOf<Uri?>(null) }

    var productUrl by remember { mutableStateOf<String?>(null) }
    var labelUrl by remember { mutableStateOf<String?>(null) }
    var nutritionUrl by remember { mutableStateOf<String?>(null) }

    var isUploading by remember { mutableStateOf(false) }
    var pendingCameraAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted) {
                pendingCameraAction?.invoke()
                pendingCameraAction = null
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

    var productCameraUri by remember { mutableStateOf<Uri?>(null) }
    var labelCameraUri by remember { mutableStateOf<Uri?>(null) }
    var nutritionCameraUri by remember { mutableStateOf<Uri?>(null) }

    val productCameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
        onResult = { success ->
            val uri = productCameraUri
            if (success && uri != null) {
                productLocalUri = uri
                isUploading = true
                scope.launch {
                    runCatching { onUploadPhoto(scanId, "product", uri) }
                        .onSuccess { productUrl = it }
                    isUploading = false
                }
            }
        }
    )

    val labelCameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
        onResult = { success ->
            val uri = labelCameraUri
            if (success && uri != null) {
                labelLocalUri = uri
                isUploading = true
                scope.launch {
                    runCatching { onUploadPhoto(scanId, "ingredients", uri) }
                        .onSuccess { labelUrl = it }
                    isUploading = false
                }
            }
        }
    )

    val nutritionCameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
        onResult = { success ->
            val uri = nutritionCameraUri
            if (success && uri != null) {
                nutritionLocalUri = uri
                isUploading = true
                scope.launch {
                    runCatching { onUploadPhoto(scanId, "nutrition_facts", uri) }
                        .onSuccess { nutritionUrl = it }
                    isUploading = false
                }
            }
        }
    )

    val canSubmit = productUrl != null && !isUploading && !isProcessing

    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                // Keep the bottom submit button above the system nav bar and keyboard.
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Image(
                    painter = painterResource(id = R.drawable.header_grocery_scan),
                    contentDescription = "Grocery scan",
                    modifier = Modifier
                        .align(Alignment.Center)
                        // ~3x bigger header
                        .height(210.dp),
                    contentScale = ContentScale.Fit
                )
            }
            Text(
                text = "Take the front photo (required). Ingredients and nutrition facts are optional. After evaluation you can Add or Leave.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            PhotoBox(
                title = "Front Product Picture (Required)",
                localUri = productLocalUri,
                onClick = {
                    ensureCameraPermissionAndRun {
                        val uri = createImageUri(context)
                        productCameraUri = uri
                        productCameraLauncher.launch(uri)
                    }
                }
            )
            PhotoBox(
                title = "Ingredients List (Optional)",
                localUri = labelLocalUri,
                onClick = {
                    ensureCameraPermissionAndRun {
                        val uri = createImageUri(context)
                        labelCameraUri = uri
                        labelCameraLauncher.launch(uri)
                    }
                }
            )
            PhotoBox(
                title = "Nutrition Facts (Optional)",
                localUri = nutritionLocalUri,
                onClick = {
                    ensureCameraPermissionAndRun {
                        val uri = createImageUri(context)
                        nutritionCameraUri = uri
                        nutritionCameraLauncher.launch(uri)
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
                    val p = productUrl ?: return@Button
                    onSubmit(p, labelUrl, nutritionUrl)
                    // reset local state for next scan
                    scanId = UUID.randomUUID().toString()
                    productLocalUri = null
                    labelLocalUri = null
                    nutritionLocalUri = null
                    productUrl = null
                    labelUrl = null
                    nutritionUrl = null
                },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Submit for evaluation")
            }

            Text(
                text = "Use the phone back button to cancel.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PhotoBox(
    title: String,
    localUri: Uri?,
    onClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, Color(0x33000000), RoundedCornerShape(12.dp))
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            if (localUri != null) {
                AsyncImage(
                    model = localUri,
                    contentDescription = title,
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






