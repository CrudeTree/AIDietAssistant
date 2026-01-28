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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
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
fun FoodPhotoCaptureScreen(
    isProcessing: Boolean,
    lockedCategories: Set<String>? = null,
    onUploadPhoto: suspend (foodId: String, kind: String, uri: Uri) -> String,
    onSubmit: (categories: Set<String>, productUrl: String, labelUrl: String?, nutritionFactsUrl: String?) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activity = remember(context) { context.findActivity() }

    // Photo flow does not take a quantity; it's always 1 per submitted item.

    var pendingFoodId by remember { mutableStateOf(UUID.randomUUID().toString()) }

    var productLocalUri by remember { mutableStateOf<Uri?>(null) }
    var labelLocalUri by remember { mutableStateOf<Uri?>(null) }
    var nutritionLocalUri by remember { mutableStateOf<Uri?>(null) }
    var productUrl by remember { mutableStateOf<String?>(null) }
    var labelUrl by remember { mutableStateOf<String?>(null) }
    var nutritionFactsUrl by remember { mutableStateOf<String?>(null) }

    val locked = lockedCategories
        ?.map { it.trim().uppercase() }
        ?.filter { it == "MEAL" || it == "INGREDIENT" || it == "SNACK" }
        ?.toSet()
        ?.ifEmpty { null }

    // Categories for photo items (user can pick; default is SNACK) unless locked.
    var catMeal by remember { mutableStateOf(locked?.contains("MEAL") == true) }
    var catIngredient by remember { mutableStateOf(locked?.contains("INGREDIENT") == true) }
    var catSnack by remember { mutableStateOf(locked?.contains("SNACK") != false) }

    var isUploading by remember { mutableStateOf(false) }
    var pendingCameraAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showCameraSettingsDialog by remember { mutableStateOf(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted) {
                pendingCameraAction?.invoke()
                pendingCameraAction = null
            } else {
                // If the system is no longer showing the permission prompt (user selected
                // "Don't ask again" or policy), guide them to Settings.
                if (activity != null &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(
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

    if (showCameraSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showCameraSettingsDialog = false },
            title = { Text("Camera permission needed") },
            text = {
                Text(
                    "Camera permission is currently blocked. Please enable Camera in your app settings to take photos."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCameraSettingsDialog = false
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:${context.packageName}")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                ) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(onClick = { showCameraSettingsDialog = false }) { Text("Cancel") }
            }
        )
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
                    runCatching {
                        onUploadPhoto(pendingFoodId, "product", uri)
                    }.onSuccess { url ->
                        productUrl = url
                    }.also {
                        isUploading = false
                    }
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
                    runCatching {
                        onUploadPhoto(pendingFoodId, "ingredients", uri)
                    }.onSuccess { url ->
                        labelUrl = url
                    }.also {
                        isUploading = false
                    }
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
                    runCatching {
                        onUploadPhoto(pendingFoodId, "nutrition_facts", uri)
                    }.onSuccess { url ->
                        nutritionFactsUrl = url
                    }.also {
                        isUploading = false
                    }
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Add food photos",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Tap a box to take a picture. The front product photo is required; ingredients are optional.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (locked == null) {
                Text(
                    text = "Use as:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = catMeal, onCheckedChange = { catMeal = it })
                        Text("Meal")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = catIngredient, onCheckedChange = { catIngredient = it })
                        Text("Ingredient")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = catSnack, onCheckedChange = { catSnack = it })
                        Text("Snack")
                    }
                }
            } else {
                Text(
                    text = "Adding to: ${locked.joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            PhotoSlot(
                title = "Front Product Picture (Required)",
                localUri = productLocalUri,
                isRequired = true,
                onClick = {
                    ensureCameraPermissionAndRun {
                        val uri = createImageUri(context)
                        productCameraUri = uri
                        productCameraLauncher.launch(uri)
                    }
                }
            )

            PhotoSlot(
                title = "Ingredients List (Optional)",
                localUri = labelLocalUri,
                isRequired = false,
                onClick = {
                    ensureCameraPermissionAndRun {
                        val uri = createImageUri(context)
                        labelCameraUri = uri
                        labelCameraLauncher.launch(uri)
                    }
                }
            )

            PhotoSlot(
                title = "Nutrition Facts (Optional)",
                localUri = nutritionLocalUri,
                isRequired = false,
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
                        val pUrl = productUrl ?: return@Button
                        val cats = locked ?: buildSet {
                            if (catMeal) add("MEAL")
                            if (catIngredient) add("INGREDIENT")
                            if (catSnack) add("SNACK")
                        }.ifEmpty { setOf("SNACK") }
                        // Photo mode is photo-only (no text hint); AI must infer from images.
                        onSubmit(cats, pUrl, labelUrl, nutritionFactsUrl)

                        // Reset screen state for next open.
                        pendingFoodId = UUID.randomUUID().toString()
                        productLocalUri = null
                        labelLocalUri = null
                        nutritionLocalUri = null
                        productUrl = null
                        labelUrl = null
                        nutritionFactsUrl = null
                        catMeal = locked?.contains("MEAL") == true
                        catIngredient = locked?.contains("INGREDIENT") == true
                        catSnack = locked?.contains("SNACK") != false
                    },
                    enabled = canSubmit,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Submit")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PhotoSlot(
    title: String,
    localUri: Uri?,
    isRequired: Boolean,
    onClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title + if (isRequired) " *" else "",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
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


