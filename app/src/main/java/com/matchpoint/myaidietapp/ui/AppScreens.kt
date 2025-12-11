package com.matchpoint.myaidietapp.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.matchpoint.myaidietapp.model.HungerLevel
import com.matchpoint.myaidietapp.model.MessageSender
import com.matchpoint.myaidietapp.model.FoodItem
import com.matchpoint.myaidietapp.model.DietType
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import android.content.Context
import android.content.pm.PackageManager
import android.Manifest
import java.io.File
import java.util.UUID
import kotlinx.coroutines.launch
import android.net.Uri


@Composable
fun DigitalStomachApp() {
    val context = LocalContext.current
    val vm: HomeViewModel = viewModel(
        factory = HomeViewModelFactory(context.applicationContext)
    )
    val state by vm.uiState.collectAsState()
    var showProfile by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        when {
            state.isLoading -> LoadingScreen()
            state.profile == null -> OnboardingScreen(
                onComplete = { name, goal, diet, weight ->
                    vm.completeOnboarding(name, goal, diet, weight)
                    showProfile = false
                }
            )
            showProfile && state.profile != null -> ProfileScreen(
                profile = state.profile!!,
                onBack = { showProfile = false },
                onDietChange = { vm.updateDiet(it) },
                onRemoveFood = { vm.removeFoodItem(it) },
                onAutoPilotChange = { vm.setAutoPilotEnabled(it) }
            )
            else -> HomeScreen(
                state = state,
                onAddFood = { name, qty, productUrl, labelUrl ->
                    vm.addFoodItemSimple(name, qty, productUrl, labelUrl)
                },
                onRemoveFood = { vm.removeFoodItem(it) },
                onIntroDone = { vm.markIntroDone() },
                onConfirmMeal = { vm.confirmMealConsumed() },
                onSendChat = { vm.sendFreeformMessage(it) },
                onOpenProfile = { showProfile = true },
                onUploadPhoto = { id, isLabel, uri ->
                    vm.uploadFoodPhoto(id, isLabel, uri)
                }
            )
        }
    }
}

@Composable
fun LoadingScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Spinning up your digital stomach…")
    }
}

@Composable
fun OnboardingScreen(
    onComplete: (
        name: String,
        weightGoal: Double?,
        dietType: DietType,
        startingWeight: Double?
    ) -> Unit
) {
    var nameText by remember { mutableStateOf("") }
    var goalWeightText by remember { mutableStateOf("") }
    var weightText by remember { mutableStateOf("") }
    var selectedDiet by remember { mutableStateOf(DietType.NO_DIET) }
    var dietExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "AI Digital Stomach",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "I’ll decide when and what to eat – you just follow.",
            style = MaterialTheme.typography.bodyMedium
        )

        OutlinedTextField(
            value = nameText,
            onValueChange = { nameText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Your name") }
        )

        OutlinedTextField(
            value = weightText,
            onValueChange = { weightText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Current weight (optional)") }
        )

        OutlinedTextField(
            value = goalWeightText,
            onValueChange = { goalWeightText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Goal weight (optional)") }
        )
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Diet type",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { dietExpanded = !dietExpanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val label = when (selectedDiet) {
                        DietType.NO_DIET -> "No Diet"
                        else -> selectedDiet.name.lowercase().replaceFirstChar { it.uppercase() }
                    }
                    Text(label)
                    Text("▼")
                }
            }
            DropdownMenu(
                expanded = dietExpanded,
                onDismissRequest = { dietExpanded = false },
                modifier = Modifier.fillMaxWidth()
            ) {
                listOf(
                    DietType.NO_DIET,
                    DietType.CARNIVORE,
                    DietType.KETO,
                    DietType.OMNIVORE,
                    DietType.PALEO,
                    DietType.VEGAN,
                    DietType.VEGETARIAN,
                    DietType.OTHER
                ).forEach { type ->
                    val label = when (type) {
                        DietType.NO_DIET -> "No Diet"
                        else -> type.name.lowercase().replaceFirstChar { it.uppercase() }
                    }
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            dietExpanded = false
                            selectedDiet = type
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                val weight = weightText.toDoubleOrNull()
                val goal = goalWeightText.toDoubleOrNull()
                onComplete(nameText.trim(), goal, selectedDiet, weight)
            },
            modifier = Modifier.align(Alignment.End)
        ) {
            Text("Lock it in")
        }
    }
}

@Composable
fun HomeScreen(
    state: UiState,
    onAddFood: (String, Int, String?, String?) -> Unit,
    onRemoveFood: (String) -> Unit,
    onIntroDone: () -> Unit,
    onConfirmMeal: () -> Unit,
    onSendChat: (String) -> Unit,
    onOpenProfile: () -> Unit,
    onUploadPhoto: suspend (foodId: String, isLabel: Boolean, uri: Uri) -> String
) {
    var chatInput by remember { mutableStateOf("") }
    var expandFoods by remember { mutableStateOf(false) }
    var moreFoodName by remember { mutableStateOf("") }
    var moreProductUrl by remember { mutableStateOf<String?>(null) }
    var moreLabelUrl by remember { mutableStateOf<String?>(null) }
    var morePendingFoodId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var productCameraUri by remember { mutableStateOf<Uri?>(null) }
    var labelCameraUri by remember { mutableStateOf<Uri?>(null) }
    var pendingCameraAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val nowMillis = System.currentTimeMillis()
    val mealDue = state.profile?.nextMealAtMillis?.let { nowMillis >= it } == true
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted) {
                pendingCameraAction?.invoke()
                pendingCameraAction = null
            }
        }
    )

    fun ensureCameraPermissionAndRunForHome(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            pendingCameraAction = action
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Finalize a photo-based food submission by calling onAddFood
    fun finalizeFoodAfterPhotos() {
        val productUrl = moreProductUrl
        if (productUrl != null) {
            // If the user didn't type a name, use a generic placeholder;
            // the AI will override with a normalizedName anyway.
            val safeName = moreFoodName.trim().ifBlank { "Food item" }
            onAddFood(safeName, 1, productUrl, moreLabelUrl)
            // Reset fields for next item
            moreFoodName = ""
            moreProductUrl = null
            moreLabelUrl = null
            morePendingFoodId = UUID.randomUUID().toString()
        }
    }

    val labelCameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
        onResult = { success: Boolean ->
            val uri = labelCameraUri
            if (success && uri != null) {
                scope.launch {
                    val url = onUploadPhoto(morePendingFoodId, true, uri)
                    moreLabelUrl = url
                }
            }
            // Regardless of success, try to finalize using product photo (and label if present)
            finalizeFoodAfterPhotos()
        }
    )

    val productCameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
        onResult = { success: Boolean ->
            val uri = productCameraUri
            if (success && uri != null) {
                scope.launch {
                    val url = onUploadPhoto(morePendingFoodId, false, uri)
                    moreProductUrl = url
                }
                // After product photo, immediately offer label photo (optional)
                ensureCameraPermissionAndRunForHome {
                    val labelUri = createImageUri(context)
                    labelCameraUri = labelUri
                    labelCameraLauncher.launch(labelUri)
                }
            } else {
                // If product photo failed, reset pending state
                moreProductUrl = null
                moreLabelUrl = null
            }
        }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Digital Stomach",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "I’ll ping you when it’s time to feed. For now, just talk to me here.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            OutlinedButton(onClick = onOpenProfile) {
                Text("Profile")
            }
        }

        if (state.isProcessing) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Digital stomach is thinking…")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Optional section to add more food items after onboarding
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = { expandFoods = !expandFoods }) {
            Text(if (expandFoods) "Hide food options" else "Add more foods for me to plan with")
        }
        if (expandFoods) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Option 1: Type a food or meal (no photo):",
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedTextField(
                value = moreFoodName,
                onValueChange = { moreFoodName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Food or meal name") }
            )
            Spacer(modifier = Modifier.height(4.dp))

            // Text-only submit
            Button(
                onClick = {
                    val safeName = moreFoodName.trim()
                    if (safeName.isNotEmpty()) {
                        onAddFood(safeName, 1, null, null)
                        moreFoodName = ""
                    }
                },
                enabled = moreFoodName.isNotBlank()
            ) {
                Text("Submit food/meal for analysis (no photo)")
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Option 2: Use photos for analysis:",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    ensureCameraPermissionAndRunForHome {
                        val uri = createImageUri(context)
                        productCameraUri = uri
                        productCameraLauncher.launch(uri)
                    }
                }) {
                    Text("Take product photo (then label optional)")
                }
            }

            val items = state.profile?.foodItems ?: emptyList()
            if (items.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Current foods:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                items.forEach { item ->
                    val healthRating = item.rating
                    val dietFitRating = item.dietFitRating
                    val healthColor = when {
                        healthRating == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        healthRating <= 3 -> Color(0xFFB00020) // red
                        healthRating <= 6 -> Color(0xFFFFC107) // yellow/amber
                        healthRating <= 9 -> Color(0xFF4CAF50) // green
                        else -> Color(0xFF1B5E20)       // dark green
                    }
                    val dietColor = when {
                        dietFitRating == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        dietFitRating <= 3 -> Color(0xFFB00020) // red
                        dietFitRating <= 6 -> Color(0xFFFFC107) // yellow/amber
                        dietFitRating <= 9 -> Color(0xFF4CAF50) // green
                        else -> Color(0xFF1B5E20)       // dark green
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = item.name,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "x${item.quantity}",
                                modifier = Modifier.padding(end = 8.dp),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Column(
                                horizontalAlignment = Alignment.End
                            ) {
                                Text(
                                    text = "Health Rating ${healthRating?.let { "$it/10" } ?: "-/10"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = healthColor
                                )
                                if (dietFitRating != null && state.profile?.dietType != DietType.NO_DIET) {
                                    Text(
                                        text = "Diet Rating ${dietFitRating}/10",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = dietColor
                                    )
                                }
                            }
                            OutlinedButton(onClick = { onRemoveFood(item.id) }) {
                                Text("X")
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Chat history
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            reverseLayout = true
        ) {
            items(state.messages.reversed()) { msg ->
                val isAi = msg.sender == MessageSender.AI
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isAi) Arrangement.Start else Arrangement.End
                ) {
                    Text(
                        text = msg.text,
                        modifier = Modifier
                            .background(
                                if (isAi) MaterialTheme.colorScheme.surfaceVariant
                                else MaterialTheme.colorScheme.primaryContainer
                            )
                            .padding(10.dp),
                        color = if (isAi) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Freeform chat input with AI
        OutlinedTextField(
            value = chatInput,
            onValueChange = { chatInput = it },
            modifier = Modifier
                .fillMaxWidth(),
            label = { Text("Tell your digital stomach anything…") },
            keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    if (chatInput.isNotBlank()) {
                        onSendChat(chatInput.trim())
                        chatInput = ""
                    }
                }
            )
        )
        Spacer(modifier = Modifier.height(4.dp))
        Button(
            onClick = {
                if (chatInput.isNotBlank()) {
                    onSendChat(chatInput.trim())
                    chatInput = ""
                }
            },
            modifier = Modifier.align(Alignment.End)
        ) {
            Text("Send")
        }
    }
}

private fun createImageUri(context: Context): Uri {
    val imagesDir = File(context.cacheDir, "images").apply { mkdirs() }
    val file = File.createTempFile("photo_", ".jpg", imagesDir)
    val authority = "${context.packageName}.fileprovider"
    return FileProvider.getUriForFile(context, authority, file)
}
