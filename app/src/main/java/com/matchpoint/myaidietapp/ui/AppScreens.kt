package com.matchpoint.myaidietapp.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue


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
                onBack = { showProfile = false }
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
    var selectedDiet by remember { mutableStateOf(DietType.CARNIVORE) }

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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                DietType.CARNIVORE,
                DietType.KETO,
                DietType.OMNIVORE,
                DietType.PALEO,
                DietType.VEGAN,
                DietType.VEGETARIAN,
                DietType.OTHER
            ).forEach { type ->
                val selected = type == selectedDiet
                OutlinedButton(onClick = { selectedDiet = type }) {
                    Text(type.name.lowercase().replaceFirstChar { it.uppercase() })
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
    var moreFoodQty by remember { mutableStateOf("1") }
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

    val productPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri: Uri? ->
            if (uri != null) {
                scope.launch {
                    val url = onUploadPhoto(morePendingFoodId, false, uri)
                    moreProductUrl = url
                }
            }
        }
    )

    val labelPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri: Uri? ->
            if (uri != null) {
                scope.launch {
                    val url = onUploadPhoto(morePendingFoodId, true, uri)
                    moreLabelUrl = url
                }
            }
        }
    )

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted) {
                pendingCameraAction?.invoke()
                pendingCameraAction = null
            }
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
            }
        }
    )

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

        Spacer(modifier = Modifier.height(16.dp))

        // Optional section to add more food items after onboarding
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = { expandFoods = !expandFoods }) {
            Text(if (expandFoods) "Hide food options" else "Add more foods for me to plan with")
        }
        if (expandFoods) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Give me more foods to play with:",
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedTextField(
                value = moreFoodName,
                onValueChange = { moreFoodName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Food name") }
            )
            OutlinedTextField(
                value = moreFoodQty,
                onValueChange = { moreFoodQty = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Qty") }
            )
            Spacer(modifier = Modifier.height(4.dp))

            fun ensureCameraPermissionAndRun(action: () -> Unit) {
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

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    productPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }) {
                    Text(if (moreProductUrl == null) "Add product photo" else "Product photo added")
                }
                OutlinedButton(onClick = {
                    ensureCameraPermissionAndRun {
                        val uri = createImageUri(context)
                        productCameraUri = uri
                        productCameraLauncher.launch(uri)
                    }
                }) {
                    Text("Take product photo")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    labelPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }) {
                    Text(if (moreLabelUrl == null) "Add label photo (optional)" else "Label photo added")
                }
                OutlinedButton(onClick = {
                    ensureCameraPermissionAndRun {
                        val uri = createImageUri(context)
                        labelCameraUri = uri
                        labelCameraLauncher.launch(uri)
                    }
                }) {
                    Text("Take label photo")
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = {
                    val qty = moreFoodQty.toIntOrNull() ?: 1
                    if (moreFoodName.isNotBlank() && moreProductUrl != null) {
                        onAddFood(moreFoodName.trim(), qty, moreProductUrl, moreLabelUrl)
                        moreFoodName = ""
                        moreFoodQty = "1"
                        moreProductUrl = null
                        moreLabelUrl = null
                        morePendingFoodId = UUID.randomUUID().toString()
                    }
                },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Add food")
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("- ${item.name} x${item.quantity}")
                        OutlinedButton(onClick = { onRemoveFood(item.id) }) {
                            Text("X")
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


