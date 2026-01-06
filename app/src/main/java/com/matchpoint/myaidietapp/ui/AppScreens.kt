package com.matchpoint.myaidietapp.ui

import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.rotate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.alpha
import androidx.lifecycle.viewmodel.compose.viewModel
import com.matchpoint.myaidietapp.data.AuthRepository
import com.matchpoint.myaidietapp.model.MessageSender
import com.matchpoint.myaidietapp.model.FoodItem
import com.matchpoint.myaidietapp.model.DietType
import com.matchpoint.myaidietapp.model.WeightUnit
import com.matchpoint.myaidietapp.R
import android.content.Context
import java.io.File
import android.net.Uri
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import androidx.compose.ui.unit.sp
import com.matchpoint.myaidietapp.billing.RevenueCatEvent
import com.matchpoint.myaidietapp.billing.RevenueCatPackages
import com.matchpoint.myaidietapp.billing.RevenueCatRepository
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.Offerings

private enum class Screen {
    HOME,
    PROFILE,
    SETTINGS,
    FOOD_LIST,
    RECIPES,
    ADD_ENTRY,
    TEXT_FOOD_ENTRY,
    PHOTO_CAPTURE,
    MEAL_LOG,
    GROCERY_SCAN,
    MENU_SCAN,
    PAYMENT,
    CHOOSE_PLAN,
    RECIPE_DETAIL
}

enum class BillingCycle {
    MONTHLY,
    YEARLY
}

private fun inferCurrentBillingCycle(
    customerInfo: CustomerInfo?,
    offerings: Offerings?
): BillingCycle? {
    val info = customerInfo ?: return null
    val activeProductIds = info.activeSubscriptions.toSet()
    if (activeProductIds.isEmpty()) return null

    val pkgs = offerings?.current?.availablePackages ?: return null
    val activePkg = pkgs.firstOrNull { pkg ->
        // activeSubscriptions contains store product IDs; match against the StoreProduct id.
        val productId = runCatching { pkg.product.id }.getOrNull()
        productId != null && activeProductIds.contains(productId)
    } ?: return null

    return when (activePkg.identifier) {
        RevenueCatPackages.BASIC_MONTHLY, RevenueCatPackages.PREMIUM_MONTHLY -> BillingCycle.MONTHLY
        RevenueCatPackages.BASIC_ANNUAL, RevenueCatPackages.PREMIUM_ANNUAL -> BillingCycle.YEARLY
        else -> when {
            activePkg.identifier.contains("annual", ignoreCase = true) ||
                activePkg.identifier.contains("year", ignoreCase = true) -> BillingCycle.YEARLY
            activePkg.identifier.contains("monthly", ignoreCase = true) ||
                activePkg.identifier.contains("month", ignoreCase = true) -> BillingCycle.MONTHLY
            else -> null
        }
    }
}

private enum class TextEntryMode {
    MEAL,
    INGREDIENT,
    SNACK
}

@Composable
fun DigitalStomachApp() {
    val context = LocalContext.current
    val authRepo = remember { AuthRepository() }
    val authUid by produceState<String?>(initialValue = authRepo.currentUid()) {
        while (true) {
            value = authRepo.currentUid()
            delay(750L)
        }
    }
    val scope = rememberCoroutineScope()

    // When signed in, create the HomeViewModel scoped to this uid.
    val vm: HomeViewModel? = authUid?.let { uid ->
        viewModel(
            key = uid,
            factory = HomeViewModelFactory(context.applicationContext, uid)
        )
    }
    var screen by remember { mutableStateOf(Screen.HOME) }
    val backStack = remember { androidx.compose.runtime.mutableStateListOf<Screen>() }
    var textEntryMode by remember { mutableStateOf(TextEntryMode.MEAL) }
    var foodListFilter by remember { mutableStateOf<String?>(null) }
    var lockedPhotoCategories by remember { mutableStateOf<Set<String>?>(null) }
    var pendingUpgradeTier by remember { mutableStateOf<com.matchpoint.myaidietapp.model.SubscriptionTier?>(null) }
    var pendingUpgradeCycle by remember { mutableStateOf(BillingCycle.MONTHLY) }
    var pendingPlanNotice by remember { mutableStateOf<String?>(null) }
    var openRecipeId by remember { mutableStateOf<String?>(null) }
    // Photo-based food capture no longer uses a quantity (always 1).

    fun navigate(to: Screen) {
        if (to == screen) return
        backStack.add(screen)
        screen = to
    }

    fun popOrHome() {
        if (backStack.isNotEmpty()) {
            screen = backStack.removeAt(backStack.lastIndex)
        } else {
            screen = Screen.HOME
        }
    }

    fun goHomeClear() {
        backStack.clear()
        screen = Screen.HOME
    }

    // System back behavior:
    // - From any screen: go back to previous screen
    // - From HOME: let Android handle back (app exits)
    BackHandler(enabled = screen != Screen.HOME) {
        popOrHome()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        if (authUid == null) {
            var authError by remember { mutableStateOf<String?>(null) }
            var authLoading by remember { mutableStateOf(false) }
            AuthScreen(
                isProcessing = authLoading,
                error = authError,
                onSignIn = { email, password ->
                    authLoading = true
                    authError = null
                    scope.launch {
                        try {
                            authRepo.signIn(email, password)
                        } catch (e: Exception) {
                            authError = e.message
                        } finally {
                            authLoading = false
                        }
                    }
                },
                onCreateAccount = { email, password ->
                    authLoading = true
                    authError = null
                    scope.launch {
                        try {
                            authRepo.createAccount(email, password)
                        } catch (e: Exception) {
                            authError = e.message
                        } finally {
                            authLoading = false
                        }
                    }
                }
            )
            return@Surface
        }

        // Safe: vm exists if authUid != null
        val realVm = vm!!
        val state by realVm.uiState.collectAsState()

        // Keep Firestore "subscriptionTier" in sync with RevenueCat entitlements.
        // This prevents stale "Basic" in-app if a test sub expires/cancels in Play.
        val rc = remember { RevenueCatRepository() }
        val rcOfferings by rc.offerings.collectAsState()
        val rcCustomerInfo by rc.customerInfo.collectAsState()
        LaunchedEffect(authUid) {
            // On fresh dev machines, REVENUECAT_API_KEY may be unset; RevenueCatRepository will emit a
            // non-fatal error event. Avoid spamming refresh calls in that case by gating here too.
            if (com.matchpoint.myaidietapp.BuildConfig.REVENUECAT_API_KEY.isNotBlank()) {
                rc.refresh()
                rc.events.collect { e ->
                    if (e is RevenueCatEvent.TierUpdated) {
                        val currentTier = realVm.uiState.value.profile?.subscriptionTier
                        if (currentTier != null && currentTier != e.tier) {
                            realVm.updateSubscriptionTier(e.tier)
                        }
                    }
                }
            }
        }

        val currentTierForPlanUi =
            rcCustomerInfo?.let { rc.tierFrom(it) }
                ?: state.profile?.subscriptionTier
                ?: com.matchpoint.myaidietapp.model.SubscriptionTier.FREE
        val currentCycleForPlanUi = remember(rcCustomerInfo, rcOfferings) {
            inferCurrentBillingCycle(rcCustomerInfo, rcOfferings)
        }

        fun foodLimitFor(tier: com.matchpoint.myaidietapp.model.SubscriptionTier): Int = when (tier) {
            com.matchpoint.myaidietapp.model.SubscriptionTier.FREE -> 20
            com.matchpoint.myaidietapp.model.SubscriptionTier.REGULAR -> 100
            com.matchpoint.myaidietapp.model.SubscriptionTier.PRO -> 500
        }

        // If a quota/limit is hit, send user to Choose Plan screen with a notice banner.
        val gate = state.planGateNotice
        if (gate != null) {
            pendingPlanNotice = gate
            realVm.clearPlanGateNotice()
            if (screen != Screen.CHOOSE_PLAN) {
                navigate(Screen.CHOOSE_PLAN)
            }
        }

        when {
            state.isLoading -> LoadingScreen()
            state.profile == null -> OnboardingScreen(
                onComplete = { name, goal, diet, weight, unit, fasting, startMinutes ->
                    realVm.completeOnboarding(name, goal, diet, weight, unit, fasting, startMinutes)
                    goHomeClear()
                }
            )
            screen == Screen.ADD_ENTRY && state.profile != null -> AddEntryScreen(
                onAddMealByText = {
                    textEntryMode = TextEntryMode.MEAL
                    navigate(Screen.TEXT_FOOD_ENTRY)
                },
                onAddIngredientByText = {
                    textEntryMode = TextEntryMode.INGREDIENT
                    navigate(Screen.TEXT_FOOD_ENTRY)
                },
                onAddItemByPhotos = {
                    lockedPhotoCategories = null
                    navigate(Screen.PHOTO_CAPTURE)
                },
                onBack = { popOrHome() }
            )
            screen == Screen.TEXT_FOOD_ENTRY && state.profile != null -> TextFoodEntryScreen(
                title = when (textEntryMode) {
                    TextEntryMode.MEAL -> "Add meal by text"
                    TextEntryMode.INGREDIENT -> "Add ingredient by text"
                    TextEntryMode.SNACK -> "Add snack by text"
                },
                helperText = when (textEntryMode) {
                    TextEntryMode.MEAL ->
                        "Type a meal name or idea. The AI will estimate nutrition + ingredients and categorize it as a MEAL."
                    TextEntryMode.INGREDIENT ->
                        "Type an ingredient. The AI will estimate nutrition + typical ingredients and categorize it as an INGREDIENT."
                    TextEntryMode.SNACK ->
                        "Type a snack item. The AI will estimate nutrition + ingredients and categorize it as a SNACK."
                },
                defaultCategories = when (textEntryMode) {
                    TextEntryMode.MEAL -> setOf("MEAL")
                    TextEntryMode.INGREDIENT -> setOf("INGREDIENT")
                    TextEntryMode.SNACK -> setOf("SNACK")
                },
                isProcessing = state.isProcessing,
                remainingSlots = (foodLimitFor(state.profile!!.subscriptionTier) - state.profile!!.foodItems.size).coerceAtLeast(0),
                limit = foodLimitFor(state.profile!!.subscriptionTier),
                onSubmit = { names, categories ->
                    vm.addFoodItemsBatch(names, categories)
                    goHomeClear()
                },
                onBack = { popOrHome() }
            )
            screen == Screen.PHOTO_CAPTURE && state.profile != null -> FoodPhotoCaptureScreen(
                isProcessing = state.isProcessing,
                lockedCategories = lockedPhotoCategories,
                onUploadPhoto = { id, kind, uri -> vm.uploadFoodPhoto(id, kind, uri) },
                onSubmit = { categories, productUrl, labelUrl, nutritionFactsUrl ->
                    vm.addFoodItemSimple("Food item", categories, 1, productUrl, labelUrl, nutritionFactsUrl)
                    goHomeClear()
                },
                onCancel = { popOrHome() }
            )
            screen == Screen.MEAL_LOG && state.profile != null -> MealLogScreen(
                isProcessing = state.isProcessing,
                onUploadMealPhoto = { mealId, uri -> vm.uploadMealPhoto(mealId, uri) },
                onSubmit = { mealName, grams, photoUrl ->
                    vm.logMeal(mealName, grams, photoUrl)
                    goHomeClear()
                },
                onCancel = { popOrHome() }
            )
            screen == Screen.GROCERY_SCAN && state.profile != null -> GroceryScanPhotoScreen(
                isProcessing = state.isProcessing,
                onUploadPhoto = { id, kind, uri -> vm.uploadGroceryPhoto(id, kind, uri) },
                onSubmit = { productUrl, labelUrl, nutritionFactsUrl ->
                    vm.evaluateGroceryScan(productUrl, labelUrl, nutritionFactsUrl)
                    goHomeClear()
                },
                onCancel = { popOrHome() }
            )
            screen == Screen.MENU_SCAN && state.profile != null -> MenuScanPhotoScreen(
                isProcessing = state.isProcessing,
                onUploadPhoto = { id, uri -> vm.uploadMenuPhoto(id, uri) },
                onSubmit = { menuUrl ->
                    vm.evaluateMenuScan(menuUrl)
                    goHomeClear()
                },
                onCancel = { popOrHome() }
            )
            screen == Screen.FOOD_LIST && state.profile != null -> FoodListScreen(
                dietType = state.profile!!.dietType,
                items = state.profile!!.foodItems,
                filterCategory = foodListFilter,
                totalLimit = foodLimitFor(state.profile!!.subscriptionTier),
                showFoodIcons = state.profile!!.showFoodIcons,
                fontSizeSp = state.profile!!.uiFontSizeSp,
                onRemoveFood = { vm.removeFoodItem(it) },
                onUpdateCategories = { id, cats -> vm.updateFoodItemCategories(id, cats) },
                onAddText = { category ->
                    lockedPhotoCategories = null
                    textEntryMode = when (category) {
                        "MEAL" -> TextEntryMode.MEAL
                        "INGREDIENT" -> TextEntryMode.INGREDIENT
                        else -> TextEntryMode.SNACK
                    }
                    navigate(Screen.TEXT_FOOD_ENTRY)
                },
                onAddPhotos = { category ->
                    lockedPhotoCategories = setOf(category)
                    navigate(Screen.PHOTO_CAPTURE)
                },
                onOpenAddFoodHub = {
                    lockedPhotoCategories = null
                    navigate(Screen.ADD_ENTRY)
                },
                onBack = { popOrHome() }
            )
            screen == Screen.PROFILE && state.profile != null -> ProfileScreen(
                profile = state.profile!!,
                savedRecipes = state.savedRecipes,
                onBack = { popOrHome() },
                onRemoveFood = { vm.removeFoodItem(it) },
                onAutoPilotChange = { vm.setAutoPilotEnabled(it) },
                onUpdateWeightGoal = { vm.updateWeightGoal(it) },
                onLogWeight = { vm.logCurrentWeight(it) },
                onOpenFoodList = { filter ->
                    foodListFilter = filter
                    navigate(Screen.FOOD_LIST)
                },
                onOpenRecipeList = { navigate(Screen.RECIPES) },
                onSignOut = { vm.signOut() },
                onOpenChoosePlan = {
                    pendingPlanNotice = null
                    navigate(Screen.CHOOSE_PLAN)
                },
                isProcessing = state.isProcessing,
                errorText = state.error,
                onOpenRecipe = { recipe ->
                    openRecipeId = recipe.id
                    navigate(Screen.RECIPE_DETAIL)
                },
                onOpenSettings = { navigate(Screen.SETTINGS) }
            )
            screen == Screen.SETTINGS && state.profile != null -> SettingsScreen(
                profile = state.profile!!,
                isProcessing = state.isProcessing,
                errorText = state.error,
                onBack = { popOrHome() },
                onToggleShowFoodIcons = { show -> vm.updateShowFoodIcons(show) },
                onSetFontSizeSp = { sp -> vm.updateUiFontSizeSp(sp) },
                onUpdateWeightUnit = { unit -> vm.updateWeightUnit(unit) },
                onUpdateWeightGoal = { goal -> vm.updateWeightGoal(goal) },
                onDietChange = { vm.updateDiet(it) },
                onUpdateFastingPreset = { vm.updateFastingPreset(it) },
                onUpdateEatingWindowStart = { vm.updateEatingWindowStart(it) },
                onDeleteAccount = { password -> vm.deleteAccount(password) }
            )
            screen == Screen.RECIPES -> RecipesScreen(
                recipes = state.savedRecipes,
                onBack = { popOrHome() },
                onOpenRecipe = { recipe ->
                    openRecipeId = recipe.id
                    navigate(Screen.RECIPE_DETAIL)
                }
            )
            screen == Screen.RECIPE_DETAIL && state.profile != null -> run {
                val recipe = openRecipeId?.let { id -> state.savedRecipes.firstOrNull { it.id == id } }
                if (recipe == null) {
                    // If missing (deleted/never loaded), just go back.
                    popOrHome()
                    Unit
                } else {
                    RecipeDetailScreen(
                        recipe = recipe,
                        profile = state.profile!!,
                        onBack = { popOrHome() }
                    )
                }
            }
            screen == Screen.PAYMENT -> PaymentScreen(
                selectedTier = pendingUpgradeTier,
                billingCycle = pendingUpgradeCycle,
                onBack = { popOrHome() },
                onEntitlementGranted = { tier ->
                    // Source of truth is Firestore user profile; this also drives server-side quota enforcement.
                    realVm.updateSubscriptionTier(tier)
                    pendingPlanNotice = null
                    goHomeClear()
                }
            )
            screen == Screen.CHOOSE_PLAN && state.profile != null -> ChoosePlanScreen(
                currentTier = currentTierForPlanUi,
                currentCycle = currentCycleForPlanUi,
                notice = pendingPlanNotice,
                onClose = { popOrHome() },
                onPickPlan = { tier, cycle ->
                    pendingUpgradeTier = tier
                    pendingUpgradeCycle = cycle
                    navigate(Screen.PAYMENT)
                },
                onManageSubscription = {
                    // Cancellation/downgrade is managed by Google Play.
                    // Send user to the Play subscriptions management screen for this app.
                    val uri = android.net.Uri.parse(
                        "https://play.google.com/store/account/subscriptions?package=${context.packageName}"
                    )
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                    context.startActivity(intent)
                }
            )
            else -> HomeScreen(
                state = state,
                onAddFood = { name, qty, productUrl, labelUrl ->
                    vm.addFoodItemSimple(name, setOf("SNACK"), qty, productUrl, labelUrl, null)
                },
                onRemoveFood = { vm.removeFoodItem(it) },
                onIntroDone = { vm.markIntroDone() },
                onConfirmMeal = { vm.confirmMealConsumed() },
                onSendChat = { vm.sendFreeformMessage(it) },
                onOpenProfile = { navigate(Screen.PROFILE) },
                onOpenAddEntry = { navigate(Screen.ADD_ENTRY) },
                onOpenGroceryScan = { navigate(Screen.GROCERY_SCAN) },
                onOpenMenuScan = { navigate(Screen.MENU_SCAN) },
                onGenerateMeal = { vm.generateMeal() },
                onSaveRecipe = { messageId -> vm.saveRecipeFromMessage(messageId) },
                onConfirmGroceryAdd = { vm.confirmAddPendingGrocery() },
                onDiscardGrocery = { vm.discardPendingGrocery() }
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
        Text("Spinning up your AI Food Coach…")
    }
}

@Composable
fun OnboardingScreen(
    onComplete: (
        name: String,
        weightGoal: Double?,
        dietType: DietType,
        startingWeight: Double?,
        weightUnit: WeightUnit,
        fastingPreset: com.matchpoint.myaidietapp.model.FastingPreset,
        eatingWindowStartMinutes: Int?
    ) -> Unit
) {
    var nameText by remember { mutableStateOf("") }
    var goalWeightText by remember { mutableStateOf("") }
    var weightText by remember { mutableStateOf("") }
    var selectedDiet by remember { mutableStateOf(DietType.NO_DIET) }
    var dietExpanded by remember { mutableStateOf(false) }
    var selectedFasting by remember { mutableStateOf(com.matchpoint.myaidietapp.model.FastingPreset.NONE) }
    var fastingExpanded by remember { mutableStateOf(false) }
    var startExpanded by remember { mutableStateOf(false) }
    var selectedStartMinutes by remember { mutableStateOf<Int?>(null) }
    var weightUnit by remember { mutableStateOf(WeightUnit.LB) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "AI Food Coach",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "A diet assistant to help you manage your food list, scan groceries/menus, and generate recipes.",
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
            label = { Text("Current weight (optional, ${if (weightUnit == WeightUnit.KG) "kg" else "lb"})") }
        )

        OutlinedTextField(
            value = goalWeightText,
            onValueChange = { goalWeightText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Goal weight (optional, ${if (weightUnit == WeightUnit.KG) "kg" else "lb"})") }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Weight unit",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            val lbSelected = weightUnit == WeightUnit.LB
            if (lbSelected) {
                Button(onClick = { weightUnit = WeightUnit.LB }) { Text("lb") }
                OutlinedButton(onClick = { weightUnit = WeightUnit.KG }) { Text("kg") }
            } else {
                OutlinedButton(onClick = { weightUnit = WeightUnit.LB }) { Text("lb") }
                Button(onClick = { weightUnit = WeightUnit.KG }) { Text("kg") }
            }
        }
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

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Fasting schedule",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { fastingExpanded = !fastingExpanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(selectedFasting.label)
                    Text("▼")
                }
            }
            DropdownMenu(
                expanded = fastingExpanded,
                onDismissRequest = { fastingExpanded = false },
                modifier = Modifier.fillMaxWidth()
            ) {
                com.matchpoint.myaidietapp.model.FastingPreset.values().forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(preset.label) },
                        onClick = {
                            fastingExpanded = false
                            selectedFasting = preset
                            // If user turns fasting off, clear start
                            if (preset == com.matchpoint.myaidietapp.model.FastingPreset.NONE) {
                                selectedStartMinutes = null
                            }
                        }
                    )
                }
            }
        }

        if (selectedFasting != com.matchpoint.myaidietapp.model.FastingPreset.NONE &&
            selectedFasting.eatingWindowHours != null
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Eating window starts",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))

            fun mm(m: Int): String = "%02d:%02d".format(m / 60, m % 60)
            val startLabel = selectedStartMinutes?.let { mm(it) } ?: "Pick a start time"
            val options = listOf(
                6 * 60 to "Morning (06:00)",
                8 * 60 to "Morning (08:00)",
                10 * 60 to "Late morning (10:00)",
                12 * 60 to "Midday (12:00)",
                14 * 60 to "Afternoon (14:00)",
                16 * 60 to "Late afternoon (16:00)",
                18 * 60 to "Evening (18:00)"
            )

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { startExpanded = !startExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(startLabel)
                        Text("▼")
                    }
                }
                DropdownMenu(
                    expanded = startExpanded,
                    onDismissRequest = { startExpanded = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    options.forEach { (mins, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                startExpanded = false
                                selectedStartMinutes = mins
                            }
                        )
                    }
                }
            }
            Text(
                text = "Tip: pick a start time that won’t put your window in the middle of the night.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                val weight = weightText.toDoubleOrNull()
                val goal = goalWeightText.toDoubleOrNull()
                onComplete(
                    nameText.trim(),
                    goal,
                    selectedDiet,
                    weight,
                    weightUnit,
                    selectedFasting,
                    selectedStartMinutes
                )
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
    onOpenAddEntry: () -> Unit,
    onOpenGroceryScan: () -> Unit,
    onOpenMenuScan: () -> Unit,
    onGenerateMeal: () -> Unit,
    onSaveRecipe: (messageId: String) -> Unit,
    onConfirmGroceryAdd: () -> Unit,
    onDiscardGrocery: () -> Unit
) {
    var chatInput by remember { mutableStateOf("") }
    val nowMillis = System.currentTimeMillis()
    val mealDue = state.profile?.nextMealAtMillis?.let { nowMillis >= it } == true
    val fontSp = state.profile?.uiFontSizeSp?.coerceIn(12f, 40f) ?: 18f
    val vineHeight = 140.dp
    val density = LocalDensity.current
    var chatTopPx by remember { mutableStateOf(0) }

    Box(modifier = Modifier.fillMaxSize()) {
        // Title art as a background overlay (doesn't take layout space).
        // Draw it inside a short, clipped top band so it *looks* anchored to the top,
        // while hiding the PNG's extra transparent padding.
        val titleBandHeight = 200.dp
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .height(titleBandHeight)
                .clipToBounds()
        ) {
            Image(
                painter = painterResource(id = R.drawable.aidietassistanttext),
                contentDescription = "AI Diet Assistant",
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    // Keep it very large (~3x) but shift up so the visible text sits at the top.
                    // Now ~2x bigger than before, and slightly lower.
                    // ~50% larger than 1800dp
                    .height(2700.dp)
                    // Pull up a bit to reduce empty space above the visible art.
                    .offset(y = (-60).dp)
                    .alpha(0.92f),
                contentScale = ContentScale.Fit
            )
        }

        // Cute background wallpaper (subtle, behind everything)
        // Keep low alpha so it doesn't fight with readability.
        Image(
            painter = painterResource(id = R.drawable.tomato),
            contentDescription = null,
            modifier = Modifier
                .size(160.dp)
                .offset(x = (-36).dp, y = 90.dp)
                .rotate(-18f),
            alpha = 0.16f
        )
        Image(
            painter = painterResource(id = R.drawable.blueberries),
            contentDescription = null,
            modifier = Modifier
                .size(150.dp)
                .offset(x = 220.dp, y = 220.dp)
                .rotate(14f),
            alpha = 0.14f
        )
        Image(
            painter = painterResource(id = R.drawable.ic_food_peas),
            contentDescription = null,
            modifier = Modifier
                .size(170.dp)
                .offset(x = 205.dp, y = 560.dp)
                .rotate(-12f),
            alpha = 0.12f
        )
        // Keep bayleaf away from peas (both green).
        Image(
            painter = painterResource(id = R.drawable.bayleaf),
            contentDescription = null,
            modifier = Modifier
                .size(170.dp)
                .offset(x = (-24).dp, y = 520.dp)
                .rotate(16f),
            alpha = 0.10f
        )

        // Profile button (overlay, doesn't take layout space)
        IconButton(
            onClick = onOpenProfile,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 8.dp, end = 8.dp)
                .zIndex(1000f)
                // Ensure the icon button itself is large (otherwise the Image gets constrained).
                .size(68.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.carrot),
                contentDescription = "Profile",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
        ) {
        // Spacer to position the button stack under the background title art.
        // Increase slightly so there's more separation below the header.
        Spacer(modifier = Modifier.height(84.dp))

        // "Pyramid" actions: use TextButton (no bubble/border), wrap to content, centered.
        TextButton(
            onClick = onOpenAddEntry,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("+ Meal, Snack, Ingredient")
                Image(
                    painter = painterResource(id = R.drawable.forkknife),
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    contentScale = ContentScale.Fit
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        TextButton(
            onClick = onOpenGroceryScan,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Grocery Shopping Scan")
                Image(
                    painter = painterResource(id = R.drawable.grocery),
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    contentScale = ContentScale.Fit
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        TextButton(
            onClick = onGenerateMeal,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Generate Meal")
                Image(
                    painter = painterResource(id = R.drawable.salad),
                    contentDescription = null,
                    modifier = Modifier.size(53.dp),
                    contentScale = ContentScale.Fit
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        TextButton(
            onClick = onOpenMenuScan,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Menu Scan")
                Image(
                    painter = painterResource(id = R.drawable.menu),
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    contentScale = ContentScale.Fit
                )
            }
        }

        // Food list removed from AI Food Coach page (Home).
        // Access via Profile -> "View foods list".

        Spacer(modifier = Modifier.height(50.dp))

        // Chat history
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    chatTopPx = coords.positionInRoot().y.toInt()
                }
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                reverseLayout = true,
                // Ensure older messages (top) aren't hidden under the vine overlay.
                contentPadding = PaddingValues(top = vineHeight)
            ) {
            // Grocery scan actions (shown inline under the most recent message)
            state.pendingGrocery?.let { _ ->
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = onConfirmGroceryAdd,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Add")
                        }
                        OutlinedButton(
                            onClick = onDiscardGrocery,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Leave")
                        }
                    }
                }
            }
            // In reverseLayout, the first item is placed at the bottom.
            // Put the spinner first so it appears *below* the most recent message.
            if (state.isProcessing) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AiThinkingIndicator()
                    }
                }
            }
            items(state.messages.reversed()) { msg ->
                val isAi = msg.sender == MessageSender.AI
                val isRecipe = isAi && msg.kind == "RECIPE"
                val isSaved = isRecipe && state.savedRecipes.any { it.id == msg.id }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = if (isAi) Alignment.Start else Alignment.End
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isAi) Arrangement.Start else Arrangement.End
                    ) {
                        val annotated = MarkdownLite.toAnnotatedString(msg.text)
                        val bubbleColor = if (isAi) Color(0xFF0BEE3F) else Color(0xFFBD1CDD)
                        // Subtle fade across the bubble (gradient).
                        val bubbleBrush = if (isAi) {
                            // More noticeable fade (strong -> much lighter)
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0xFF0BEE3F),
                                    Color(0x590BEE3F) // ~35% alpha
                                )
                            )
                        } else {
                            // Reverse direction for user bubbles
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0x59BD1CDD), // ~35% alpha
                                    Color(0xFFBD1CDD)
                                )
                            )
                        }
                        val bubbleTextColor = if (isAi) Color(0xFF111111) else Color(0xFFFFFFFF)
                        Text(
                            text = annotated,
                            modifier = Modifier
                                .fillMaxWidth(0.84f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(bubbleBrush)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            color = bubbleTextColor,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = fontSp.sp,
                                lineHeight = (fontSp * 1.25f).sp
                            )
                        )
                    }

                    if (isRecipe) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (isAi) Arrangement.Start else Arrangement.End
                        ) {
                            if (isSaved) {
                                Text(
                                    text = "Saved",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                IconButton(
                                    onClick = { onSaveRecipe(msg.id) },
                                    // Make the tap target match the intended large icon size (~5x).
                                    // ~30% smaller
                                    // ~20% smaller than 168dp
                                    modifier = Modifier.size(134.dp)
                                ) {
                                    Image(
                                        painter = painterResource(id = R.drawable.btn_save),
                                        contentDescription = "Save recipe",
                                        // Big save icon (asset has transparent padding)
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        }

        Spacer(modifier = Modifier.height(8.dp))

        val p = state.profile
        val showWindowBar = p != null &&
            p.fastingPreset.name != "NONE" &&
            p.eatingWindowStartMinutes != null &&
            p.eatingWindowEndMinutes != null
        if (showWindowBar) {
            EatingWindowBar(profile = p)
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Freeform chat input with AI
        OutlinedTextField(
            value = chatInput,
            onValueChange = { chatInput = it },
            modifier = Modifier
                .fillMaxWidth(),
            label = { Text("Chat to the AI Food Coach…") },
            singleLine = true,
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
        }

        // Vine overlay: full-width, above everything, positioned to sit right on top of the chat box.
        if (chatTopPx > 0) {
            val vineScaleX = 1.2f
            val vineScaleY = 4f // ~2x more than the previous 1.18f
            val liftPx = with(density) { 16.dp.toPx() }.toInt()
            // Nudge down by ~0.75x the button icon height (icons are ~44dp).
            val downPx = with(density) { 90.dp.toPx() }.toInt()
            Image(
                painter = painterResource(id = R.drawable.vine),
                contentDescription = null,
                modifier = Modifier
                    .zIndex(999f)
                    .fillMaxWidth()
                    .height(vineHeight)
                    // Anchor near the chat's top edge so it "rests" on the chat box.
                    .offset { IntOffset(x = 0, y = chatTopPx - liftPx + downPx) }
                    // Stretch a bit more left/right AND top/bottom visually
                    .graphicsLayer(scaleX = vineScaleX, scaleY = vineScaleY),
                contentScale = ContentScale.FillBounds,
                alpha = 0.75f
            )
        }
    }
}

@Composable
private fun EatingWindowBar(profile: com.matchpoint.myaidietapp.model.UserProfile?) {
    // Recompose once per second so the bar visibly "slides" over time.
    val nowMinutes by produceState(initialValue = LocalTime.now().hour * 60 + LocalTime.now().minute) {
        while (true) {
            val t = LocalTime.now()
            value = t.hour * 60 + t.minute
            delay(1000L)
        }
    }

    val windowStart = profile?.eatingWindowStartMinutes
    val windowEnd = profile?.eatingWindowEndMinutes
    val hasWindow = profile != null &&
        profile.fastingPreset.name != "NONE" &&
        windowStart != null &&
        windowEnd != null

    fun isInsideWindow(now: Int, start: Int, end: Int): Boolean {
        return if (start <= end) {
            now in start until end
        } else {
            now >= start || now < end
        }
    }

    fun minutesUntil(now: Int, target: Int): Int {
        val day = 24 * 60
        val diff = (target - now + day) % day
        return diff
    }

    fun fmtDuration(mins: Int): String {
        val h = mins / 60
        val m = mins % 60
        return when {
            h <= 0 -> "${m}m"
            m <= 0 -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }

    val insideNow = if (!hasWindow) true else isInsideWindow(nowMinutes, windowStart!!, windowEnd!!)

    // Full bar represents 24 hours ahead (from "now" at the left edge).
    val horizon = 24 * 60

    // Compute next two toggle times (minutes from now) so we can draw 3 segments.
    // State toggles at windowStart and windowEnd (handles wrap-around windows too).
    val start = windowStart ?: 0
    val end = windowEnd ?: 0

    val nextToggleDelta: Int
    val secondToggleDelta: Int
    if (!hasWindow) {
        nextToggleDelta = horizon
        secondToggleDelta = horizon
    } else if (start == end) {
        nextToggleDelta = horizon
        secondToggleDelta = horizon
    } else if (start < end) {
        // Normal: inside between start..end
        if (insideNow) {
            nextToggleDelta = minutesUntil(nowMinutes, end)
            secondToggleDelta = nextToggleDelta + minutesUntil(end, start)
        } else {
            val toStart = if (nowMinutes < start) (start - nowMinutes) else minutesUntil(nowMinutes, start)
            nextToggleDelta = toStart
            secondToggleDelta = nextToggleDelta + (end - start)
        }
    } else {
        // Wrap: inside from start..midnight + 0..end
        if (insideNow) {
            nextToggleDelta = minutesUntil(nowMinutes, end)
            val toStartFromEnd = minutesUntil(end, start)
            secondToggleDelta = nextToggleDelta + toStartFromEnd
        } else {
            // Outside between end..start
            nextToggleDelta = (start - nowMinutes).coerceAtLeast(0)
            val insideDuration = minutesUntil(start, end)
            secondToggleDelta = nextToggleDelta + insideDuration
        }
    }

    val t1 = nextToggleDelta.coerceIn(0, horizon)
    val t2 = secondToggleDelta.coerceIn(0, horizon)
    val len0 = t1
    val len1 = (t2 - t1).coerceAtLeast(0)
    val len2 = (horizon - len0 - len1).coerceAtLeast(0)

    val f0 = len0.toFloat() / horizon.toFloat()
    val f1 = len1.toFloat() / horizon.toFloat()
    val f2 = len2.toFloat() / horizon.toFloat()

    // Fasting window display colors (high contrast).
    // Blue: #0000FF, Orange: #F04A00
    val blueFill = Color(0xFF0000FF)
    val orangeFill = Color(0xFFF04A00)
    val barTextColor = Color.White

    val state0Inside = insideNow
    val state1Inside = !state0Inside
    val state2Inside = state0Inside

    fun fillFor(isInside: Boolean) = if (isInside) orangeFill else blueFill
    fun labelFor(isInside: Boolean) = if (isInside) "Eating Window" else "Don’t Eat"

    val countdownText = if (!hasWindow) "No fasting window"
    else if (state0Inside) "Closes in ${fmtDuration(len0)}"
    else "Opens in ${fmtDuration(len0)}"

    val shape = RoundedCornerShape(12.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
    ) {
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
        ) {
            val w = size.width
            val h = size.height
            val w0 = w * f0
            val w1 = w * f1
            val w2 = w - w0 - w1
            drawRect(color = fillFor(state0Inside), size = androidx.compose.ui.geometry.Size(w0, h))
            drawRect(
                color = fillFor(state1Inside),
                topLeft = androidx.compose.ui.geometry.Offset(w0, 0f),
                size = androidx.compose.ui.geometry.Size(w1, h)
            )
            drawRect(
                color = fillFor(state2Inside),
                topLeft = androidx.compose.ui.geometry.Offset(w0 + w1, 0f),
                size = androidx.compose.ui.geometry.Size(w2.coerceAtLeast(0f), h)
            )
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .border(1.dp, MaterialTheme.colorScheme.outline, shape)
        )

        // Embedded countdown label (TextField-style).
        Text(
            text = countdownText,
            color = barTextColor,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 12.dp)
                .offset(y = (-8).dp)
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 6.dp)
        )

        // Moving labels: each is centered inside its segment and clips when too small.
        if (hasWindow) {
            Row(
                modifier = Modifier
                    .matchParentSize()
                    .clipToBounds()
            ) {
                if (f0 > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(f0),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = labelFor(state0Inside),
                            color = barTextColor,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip
                        )
                    }
                }
                if (f1 > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(f1),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = labelFor(state1Inside),
                            color = barTextColor,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip
                        )
                    }
                }
                if (f2 > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(f2),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = labelFor(state2Inside),
                            color = barTextColor,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip
                        )
                    }
                }
            }
        }
    }
}
