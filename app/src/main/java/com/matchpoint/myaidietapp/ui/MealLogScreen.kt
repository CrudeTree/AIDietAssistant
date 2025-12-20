package com.matchpoint.myaidietapp.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun MealLogScreen(
    isProcessing: Boolean,
    onUploadMealPhoto: suspend (mealId: String, uri: Uri) -> String,
    onSubmit: (mealName: String?, grams: Int?, mealPhotoUrl: String?) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mealName by remember { mutableStateOf("") }
    var gramsText by remember { mutableStateOf("") }

    var mealId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var mealLocalUri by remember { mutableStateOf<Uri?>(null) }
    var mealPhotoUrl by remember { mutableStateOf<String?>(null) }

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

    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
        onResult = { success ->
            val uri = cameraUri
            if (success && uri != null) {
                mealLocalUri = uri
                isUploading = true
                scope.launch {
                    runCatching {
                        onUploadMealPhoto(mealId, uri)
                    }.onSuccess { url ->
                        mealPhotoUrl = url
                    }.also {
                        isUploading = false
                    }
                }
            }
        }
    )

    val grams = gramsText.toIntOrNull()?.takeIf { it > 0 }
    val hasEnough = mealPhotoUrl != null || grams != null
    val canSubmit = hasEnough && !isUploading && !isProcessing

    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Log your meal",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Upload a meal photo and/or enter total grams. This helps the AI decide when to poke you and what to eat next.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = mealName,
                onValueChange = { mealName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Meal name (optional)") }
            )

            OutlinedTextField(
                value = gramsText,
                onValueChange = { gramsText = it.filter { c -> c.isDigit() }.take(5) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Total grams (optional)") }
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Meal photo (optional)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0x33000000), RoundedCornerShape(12.dp))
                        .clickable {
                            ensureCameraPermissionAndRun {
                                val uri = createImageUri(context)
                                cameraUri = uri
                                cameraLauncher.launch(uri)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (mealLocalUri != null) {
                        AsyncImage(
                            model = mealLocalUri,
                            contentDescription = "Meal photo",
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

            if (!hasEnough) {
                Text(
                    text = "Add a photo or grams to submit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        onSubmit(
                            mealName.trim().ifBlank { null },
                            grams,
                            mealPhotoUrl
                        )
                        // reset state
                        mealId = UUID.randomUUID().toString()
                        mealLocalUri = null
                        mealPhotoUrl = null
                        mealName = ""
                        gramsText = ""
                    },
                    enabled = canSubmit,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Submit")
                }
            }
        }
    }
}


