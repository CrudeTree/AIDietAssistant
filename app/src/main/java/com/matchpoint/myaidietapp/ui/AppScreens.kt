@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.matchpoint.myaidietapp.ui

import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.matchpoint.myaidietapp.ui.FoodIconResolver
import com.matchpoint.myaidietapp.logic.RecipeParser
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.ktx.Firebase
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
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
import androidx.compose.ui.unit.DpSize
import com.matchpoint.myaidietapp.billing.RevenueCatEvent
import com.matchpoint.myaidietapp.billing.RevenueCatPackages
import com.matchpoint.myaidietapp.billing.RevenueCatRepository
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.Offerings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.core.Animatable
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import com.matchpoint.myaidietapp.data.ReviewPromptManager
import androidx.compose.material3.Slider

private enum class Screen {
    HOME,
    PROFILE,
    SETTINGS,
    FOOD_LIST,
    RECIPES,
    ADD_ENTRY,
    ADD_FOOD_CATEGORY,
    TEXT_FOOD_ENTRY,
    PHOTO_CAPTURE,
    MEAL_LOG,
    GROCERY_SCAN,
    MENU_SCAN,
    CHOOSE_PLAN,
    RECIPE_DETAIL,
    DAILY_PLAN_RECIPE_DETAIL
}

enum class BillingCycle {
    MONTHLY,
    YEARLY
}

private data class PurchaseRequest(
    val tier: com.matchpoint.myaidietapp.model.SubscriptionTier,
    val cycle: BillingCycle
)

private fun findDesiredRevenueCatPackage(
    offerings: Offerings?,
    selectedTier: com.matchpoint.myaidietapp.model.SubscriptionTier,
    billingCycle: BillingCycle
): com.revenuecat.purchases.Package? {
    val activeOffering = offerings?.current ?: offerings?.all?.values?.firstOrNull() ?: return null

    val desiredPackageId = when (selectedTier) {
        com.matchpoint.myaidietapp.model.SubscriptionTier.REGULAR ->
            if (billingCycle == BillingCycle.YEARLY) RevenueCatPackages.BASIC_ANNUAL else RevenueCatPackages.BASIC_MONTHLY
        com.matchpoint.myaidietapp.model.SubscriptionTier.PRO ->
            if (billingCycle == BillingCycle.YEARLY) RevenueCatPackages.PREMIUM_ANNUAL else RevenueCatPackages.PREMIUM_MONTHLY
        else -> null
    } ?: return null

    val pkgs = activeOffering.availablePackages

    fun pkgMatches(pkg: com.revenuecat.purchases.Package): Boolean {
        val pkgId = runCatching { pkg.identifier }.getOrNull()
        val productId = runCatching { pkg.product.id }.getOrNull()
        if (pkgId == desiredPackageId || productId == desiredPackageId) return true

        // Fallback heuristic: match by keywords when identifiers differ.
        val ident = (pkgId ?: "").lowercase()
        val prod = (productId ?: "").lowercase()
        val cycleOk = if (billingCycle == BillingCycle.YEARLY) {
            ident.contains("annual") || ident.contains("year") || prod.contains("annual") || prod.contains("year")
        } else {
            ident.contains("month") || prod.contains("month")
        }
        val tierOk = when (selectedTier) {
            com.matchpoint.myaidietapp.model.SubscriptionTier.REGULAR ->
                ident.contains("basic") || ident.contains("regular") || prod.contains("basic") || prod.contains("regular")
            com.matchpoint.myaidietapp.model.SubscriptionTier.PRO ->
                ident.contains("premium") || ident.contains("pro") || prod.contains("premium") || prod.contains("pro")
            else -> false
        }
        return tierOk && cycleOk
    }

    return pkgs.firstOrNull { pkgMatches(it) }
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
    // Pick wallpaper once per app launch to avoid jank when navigating between screens.
    val wallpaperSeed = remember { kotlin.random.Random.nextInt() }
    val backStack = remember { androidx.compose.runtime.mutableStateListOf<Screen>() }
    var textEntryMode by remember { mutableStateOf(TextEntryMode.MEAL) }
    var foodListFilter by remember { mutableStateOf<String?>(null) }
    var lockedPhotoCategories by remember { mutableStateOf<Set<String>?>(null) }
    var pendingPlanNotice by remember { mutableStateOf<String?>(null) }
    var pendingPurchase by remember { mutableStateOf<PurchaseRequest?>(null) }
    var purchaseInFlight by remember { mutableStateOf(false) }
    var openRecipeId by remember { mutableStateOf<String?>(null) }
    var openDailyPlanMealId by remember { mutableStateOf<String?>(null) }
    // Photo-based food capture no longer uses a quantity (always 1).
    // One-shot flag: open the popular ingredient picker the next time Home renders.
    var pendingOpenPopularPicker by rememberSaveable { mutableStateOf(false) }

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

    // Intentionally do NOT reroll wallpaper per screen to keep navigation smooth.

    fun goHomeClear() {
        backStack.clear()
        screen = Screen.HOME
    }

    // Ensure the landing page is always Home when auth state changes.
    // This prevents "sticking" on Profile/Settings after sign-out -> sign-up.
    LaunchedEffect(authUid) {
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
        // Global in-app update banner (only shows on Play-installed builds when an update is available)
        Box(modifier = Modifier.fillMaxSize()) {
            InAppUpdatePrompt(modifier = Modifier.align(Alignment.TopCenter))

            // Main app content below
            Box(modifier = Modifier.fillMaxSize()) {
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
                            // Show the beginner picker on first entry after auth (it will self-gate to once).
                            pendingOpenPopularPicker = true
                            // Analytics: track successful sign-in
                            Firebase.analytics.logEvent(FirebaseAnalytics.Event.LOGIN, null)
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
                            pendingOpenPopularPicker = true
                            // Make the post-auth landing deterministic (Home).
                            backStack.clear()
                            screen = Screen.HOME
                            // Analytics: track successful account creation
                            Firebase.analytics.logEvent(FirebaseAnalytics.Event.SIGN_UP, null)
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

        // RevenueCat is used for UI display, but server enforces quotas and writes tier back to Firestore.
        val rc = remember { RevenueCatRepository() }
        val rcOfferings by rc.offerings.collectAsState()
        val rcCustomerInfo by rc.customerInfo.collectAsState()
        LaunchedEffect(authUid) {
            val uid = authUid ?: return@LaunchedEffect
            // On fresh dev machines, REVENUECAT_API_KEY may be unset; RevenueCatRepository will emit a
            // non-fatal error event. Avoid spamming refresh calls in that case by gating here too.
            if (com.matchpoint.myaidietapp.BuildConfig.REVENUECAT_API_KEY.isNotBlank()) {
                // Important: use Firebase uid as RevenueCat app user id so the client and server
                // (which verifies via RevenueCat REST API) see the same entitlements.
                rc.logIn(uid)
                rc.refresh()
            }
        }

        // Handle purchase results globally so the UI updates everywhere (no intermediate payment screen).
        LaunchedEffect(rc) {
            rc.events.collect { e ->
                when (e) {
                    is RevenueCatEvent.Error -> {
                        purchaseInFlight = false
                        // Keep the user on the plan page if they're there, and show the message.
                        pendingPlanNotice = e.message
                    }
                    is RevenueCatEvent.TierUpdated -> {
                        if (purchaseInFlight && e.tier != com.matchpoint.myaidietapp.model.SubscriptionTier.FREE) {
                            purchaseInFlight = false
                            pendingPlanNotice = null
                            goHomeClear()
                        }
                    }
                }
            }
        }

        // If user tapped "Upgrade/Change plan", launch the Google Play purchase sheet immediately
        // once offerings are available.
        LaunchedEffect(pendingPurchase, rcOfferings, authUid) {
            val req = pendingPurchase ?: return@LaunchedEffect
            val uid = authUid ?: run {
                pendingPlanNotice = "Please sign in to manage subscriptions."
                pendingPurchase = null
                return@LaunchedEffect
            }

            if (com.matchpoint.myaidietapp.BuildConfig.REVENUECAT_API_KEY.isBlank()) {
                pendingPlanNotice = "Subscriptions unavailable on this build (missing REVENUECAT_API_KEY)."
                pendingPurchase = null
                return@LaunchedEffect
            }

            // Ensure we are logged into the canonical user id.
            rc.logIn(uid)

            val act = context as? android.app.Activity
            if (act == null) {
                pendingPlanNotice = "Unable to start billing: no Activity."
                pendingPurchase = null
                return@LaunchedEffect
            }

            val offerings = rcOfferings
            if (offerings == null) {
                pendingPlanNotice = "Loading subscriptions…"
                rc.refresh()
                return@LaunchedEffect
            }

            val pkg = findDesiredRevenueCatPackage(
                offerings = offerings,
                selectedTier = req.tier,
                billingCycle = req.cycle
            )
            if (pkg == null) {
                pendingPlanNotice = "Unable to load the selected plan. Please try again in a moment."
                pendingPurchase = null
                return@LaunchedEffect
            }

            pendingPurchase = null
            pendingPlanNotice = null
            purchaseInFlight = true
            rc.purchase(act, pkg)
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
            screen == Screen.ADD_FOOD_CATEGORY && state.profile != null -> AddFoodCategoryScreen(
                enabled = !state.isProcessing,
                onPickMeals = {
                    foodListFilter = "MEAL"
                    navigate(Screen.FOOD_LIST)
                },
                onPickSnacks = {
                    foodListFilter = "SNACK"
                    navigate(Screen.FOOD_LIST)
                },
                onPickIngredients = {
                    foodListFilter = "INGREDIENT"
                    navigate(Screen.FOOD_LIST)
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
                displayTier = currentTierForPlanUi,
                savedRecipes = state.savedRecipes,
                onBack = { popOrHome() },
                onRemoveFood = { vm.removeFoodItem(it) },
                onAutoPilotChange = { vm.setAutoPilotEnabled(it) },
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
                onOpenSettings = { navigate(Screen.SETTINGS) },
                wallpaperSeed = wallpaperSeed
            )
            screen == Screen.SETTINGS && state.profile != null -> SettingsScreen(
                profile = state.profile!!,
                isProcessing = state.isProcessing,
                errorText = state.error,
                onBack = { popOrHome() },
                onGoHome = { goHomeClear() },
                onToggleShowFoodIcons = { show -> vm.updateShowFoodIcons(show) },
                onToggleShowWallpaperFoodIcons = { show -> vm.updateShowWallpaperFoodIcons(show) },
                onToggleShowVineOverlay = { show -> vm.updateShowVineOverlay(show) },
                onSetFontSizeSp = { sp -> vm.updateUiFontSizeSp(sp) },
                onUpdateWeightUnit = { unit -> vm.updateWeightUnit(unit) },
                onUpdateWeightGoal = { goal -> vm.updateWeightGoal(goal) },
                onDietChange = { vm.updateDiet(it) },
                onUpdateFastingPreset = { vm.updateFastingPreset(it) },
                onUpdateEatingWindowStart = { vm.updateEatingWindowStart(it) },
                onDeleteAccount = { password -> vm.deleteAccount(password) },
                wallpaperSeed = wallpaperSeed
            )
            screen == Screen.RECIPES -> RecipesScreen(
                recipes = state.savedRecipes,
                onBack = { popOrHome() },
                onOpenRecipe = { recipe ->
                    openRecipeId = recipe.id
                    navigate(Screen.RECIPE_DETAIL)
                },
                onDeleteRecipe = { recipeId ->
                    vm.deleteSavedRecipe(recipeId)
                },
                titleFontStyle = state.profile?.recipeTitleFontStyle
                    ?: com.matchpoint.myaidietapp.model.RecipeTitleFontStyle.VINTAGE_COOKBOOK,
                onSetTitleFontStyle = { style ->
                    vm.updateRecipeTitleFontStyle(style)
                },
                wallpaperSeed = wallpaperSeed,
                showWallpaperFoodIcons = state.profile?.showWallpaperFoodIcons != false
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
            screen == Screen.DAILY_PLAN_RECIPE_DETAIL && state.profile != null -> run {
                val meal = openDailyPlanMealId?.let { id -> state.dailyPlanMeals.firstOrNull { it.id == id } }
                if (meal == null) {
                    popOrHome()
                    Unit
                } else {
                    val parsed = RecipeParser.parse(meal.recipeText)
                    val tmp = com.matchpoint.myaidietapp.model.SavedRecipe(
                        id = "dailyplan:${meal.id}",
                        sourceMessageId = null,
                        createdAt = com.google.firebase.Timestamp.now(),
                        title = meal.title.ifBlank { parsed.title.ifBlank { "Meal" } },
                        text = meal.recipeText,
                        ingredients = parsed.ingredients
                    )
                    RecipeDetailScreen(
                        recipe = tmp,
                        profile = state.profile!!,
                        onBack = { popOrHome() },
                        onSave = { vm.saveDailyPlanMeal(meal.id) }
                    )
                }
            }
            screen == Screen.CHOOSE_PLAN && state.profile != null -> ChoosePlanScreen(
                currentTier = currentTierForPlanUi,
                currentCycle = currentCycleForPlanUi,
                notice = pendingPlanNotice,
                onClose = { popOrHome() },
                onPickPlan = { tier, cycle ->
                    pendingPlanNotice = null
                    pendingPurchase = PurchaseRequest(tier = tier, cycle = cycle)
                },
                onManageSubscription = {
                    // Cancellation/downgrade is managed by Google Play.
                    // Send user to the Play subscriptions management screen.
                    //
                    // Include the currently-active subscription id (sku) if we can infer it, so the
                    // page can't appear empty due to filtering/account confusion.
                    //
                    // Note: RevenueCat's activeSubscriptions often uses the form "subscriptionId:basePlanId".
                    // The Play URL expects the subscriptionId portion (before ':').
                    val activeSku = runCatching {
                        rcCustomerInfo?.activeSubscriptions?.firstOrNull()
                            ?.substringBefore(':')
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                    }.getOrNull()

                    val uri = android.net.Uri.parse(
                        if (activeSku != null) {
                            "https://play.google.com/store/account/subscriptions?package=${context.packageName}&sku=$activeSku"
                        } else {
                            "https://play.google.com/store/account/subscriptions?package=${context.packageName}"
                        }
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
                onAddIngredients = { names ->
                    vm.addFoodItemsBatch(names = names, categories = setOf("INGREDIENT"))
                },
                onMarkBeginnerIngredientsPickerSeen = { vm.markBeginnerIngredientsPickerSeen() },
                onRemoveFood = { vm.removeFoodItem(it) },
                onIntroDone = { vm.markIntroDone() },
                onConfirmMeal = { vm.confirmMealConsumed() },
                onSendChat = { vm.sendFreeformMessage(it) },
                onOpenProfile = { navigate(Screen.PROFILE) },
                onOpenAddEntry = { navigate(Screen.ADD_FOOD_CATEGORY) },
                onOpenGroceryScan = { navigate(Screen.GROCERY_SCAN) },
                onOpenMenuScan = { navigate(Screen.MENU_SCAN) },
                onGenerateMeal = { required, targetCalories, strictOnly -> vm.generateMeal(required, targetCalories, strictOnly) },
                onGenerateDailyPlan = { target, count, savedOnly -> vm.generateDailyPlan(target, count, savedOnly) },
                onCompleteDailyPlanMeal = { id -> vm.completeDailyPlanMeal(id) },
                onOpenDailyPlanMeal = { id ->
                    openDailyPlanMealId = id
                    navigate(Screen.DAILY_PLAN_RECIPE_DETAIL)
                },
                onClearDailyPlan = { vm.clearDailyPlan() },
                onSaveDailyPlanMeal = { id -> vm.saveDailyPlanMeal(id) },
                onSaveRecipe = { messageId -> vm.saveRecipeFromMessage(messageId) },
                onConfirmGroceryAdd = { vm.confirmAddPendingGrocery() },
                onDiscardGrocery = { vm.discardPendingGrocery() },
                onNewChat = { vm.newChat() },
                onSelectChat = { chatId -> vm.selectChat(chatId) },
                onDeleteChat = { chatId -> vm.deleteChat(chatId) },
                wallpaperSeed = wallpaperSeed,
                autoOpenPopularIngredientsPicker = pendingOpenPopularPicker,
                onAutoOpenPopularIngredientsPickerConsumed = { pendingOpenPopularPicker = false }
            )
        }
            }
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
    isProcessing: Boolean = false,
    errorText: String? = null,
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
            .verticalScroll(rememberScrollState())
            // Keep the bottom button visible above the nav bar / keyboard during onboarding.
            .navigationBarsPadding()
            .imePadding(),
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

        if (!errorText.isNullOrBlank()) {
            Text(
                text = errorText,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

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
            enabled = !isProcessing,
            modifier = Modifier.align(Alignment.End)
        ) {
            if (isProcessing) {
                androidx.compose.material3.CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text("Saving…")
            } else {
                Text("Lock it in")
            }
        }
    }
}

@Composable
fun HomeScreen(
    state: UiState,
    onAddFood: (String, Int, String?, String?) -> Unit,
    onAddIngredients: (List<String>) -> Unit,
    onMarkBeginnerIngredientsPickerSeen: () -> Unit,
    onRemoveFood: (String) -> Unit,
    onIntroDone: () -> Unit,
    onConfirmMeal: () -> Unit,
    onSendChat: (String) -> Unit,
    onOpenProfile: () -> Unit,
    onOpenAddEntry: () -> Unit,
    onOpenGroceryScan: () -> Unit,
    onOpenMenuScan: () -> Unit,
    onGenerateMeal: (requiredIngredients: List<String>, targetCalories: Int?, strictOnly: Boolean) -> Unit,
    onGenerateDailyPlan: (targetCalories: Int?, mealCount: Int, savedRecipesOnly: Boolean) -> Unit,
    onCompleteDailyPlanMeal: (mealId: String) -> Unit,
    onOpenDailyPlanMeal: (mealId: String) -> Unit,
    onClearDailyPlan: () -> Unit,
    onSaveDailyPlanMeal: (mealId: String) -> Unit,
    onSaveRecipe: (messageId: String) -> Unit,
    onConfirmGroceryAdd: () -> Unit,
    onDiscardGrocery: () -> Unit,
    onNewChat: () -> Unit,
    onSelectChat: (chatId: String) -> Unit,
    onDeleteChat: (chatId: String) -> Unit,
    wallpaperSeed: Int,
    autoOpenPopularIngredientsPicker: Boolean = false,
    onAutoOpenPopularIngredientsPickerConsumed: () -> Unit = {}
) {
    var chatInput by remember { mutableStateOf("") }
    var chatFullscreen by rememberSaveable { mutableStateOf(false) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var pendingDeleteChatId by rememberSaveable { mutableStateOf<String?>(null) }
    var showBeginnerIngredientsPicker by rememberSaveable { mutableStateOf(false) }
    var showExistingIngredientsPicker by rememberSaveable { mutableStateOf(false) }
    var showDailyPlanPopup by rememberSaveable { mutableStateOf(false) }
    var showDailyPlanOverlay by rememberSaveable { mutableStateOf(false) }
    var showScanPicker by rememberSaveable { mutableStateOf(false) }
    var showReviewPrompt by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val reviewPromptManager = remember(context) { ReviewPromptManager(context.applicationContext) }
    val homeTutorialManager = remember(context) { HomeTutorialManager(context.applicationContext) }
    var showHomeTutorial by rememberSaveable { mutableStateOf(false) }
    val nowMillis = System.currentTimeMillis()
    val mealDue = state.profile?.nextMealAtMillis?.let { nowMillis >= it } == true
    val fontSp = state.profile?.uiFontSizeSp?.coerceIn(12f, 40f) ?: 18f
    val vineHeight = 140.dp
    val density = LocalDensity.current
    var chatTopPx by remember { mutableStateOf(0) }

    // One-time Home tutorial anchors (rects in root coordinates)
    var rectScan by remember { mutableStateOf<Rect?>(null) }
    var rectProfile by remember { mutableStateOf<Rect?>(null) }
    var rectAdd by remember { mutableStateOf<Rect?>(null) }
    var rectGen by remember { mutableStateOf<Rect?>(null) }
    var rectDaily by remember { mutableStateOf<Rect?>(null) }
    var rectChatHistory by remember { mutableStateOf<Rect?>(null) }
    var rectChatInput by remember { mutableStateOf<Rect?>(null) }
    var rectFasting by remember { mutableStateOf<Rect?>(null) }
    // Keep track of AI messages that finished "typing" so we don't re-animate when scrolling.
    // Must survive leaving/re-entering HomeScreen, otherwise messages re-type every time you navigate back.
    val typedAiDoneIds = rememberSaveable(
        state.activeChatId,
        saver = listSaver(
            // Explicitly type the parameter so Kotlin doesn't infer it as `Any`
            // (which would make extension functions like `toList()` / `map()` unavailable).
            save = { stateList: List<String> -> stateList.toList() },
            restore = { restored -> mutableStateListOf<String>().also { it.addAll(restored) } }
        )
    ) {
        mutableStateListOf()
    }

    // Expire the daily plan at end-of-day.
    val todayId = java.time.LocalDate.now().toString()
    LaunchedEffect(todayId, state.dailyPlanDayId) {
        if (state.dailyPlanDayId != null && state.dailyPlanDayId != todayId) {
            onClearDailyPlan()
            showDailyPlanOverlay = false
        }
    }

    // Trigger the review prompt once eligible. Manager ensures it won't spam.
    val foodCount = state.profile?.foodItems?.size ?: 0
    val recipeCount = state.savedRecipes.size
    LaunchedEffect(foodCount, recipeCount, state.messages.size, state.activeChatId) {
        if (!showReviewPrompt && reviewPromptManager.shouldShowPrompt(foodCount, recipeCount)) {
            reviewPromptManager.markShown()
            showReviewPrompt = true
        }
    }

    // One-time Home tutorial (doesn't show in fullscreen chat).
    LaunchedEffect(chatFullscreen) {
        if (!chatFullscreen && homeTutorialManager.shouldShow()) {
            showHomeTutorial = true
        }
    }

    // Beginner-only: show the popular ingredients picker once, on first entry.
    LaunchedEffect(autoOpenPopularIngredientsPicker, chatFullscreen, state.profile?.hasSeenBeginnerIngredientsPicker) {
        if (chatFullscreen || !autoOpenPopularIngredientsPicker) return@LaunchedEffect
        val alreadySeen = state.profile?.hasSeenBeginnerIngredientsPicker == true
        if (!alreadySeen) {
            showBeginnerIngredientsPicker = true
        }
        onAutoOpenPopularIngredientsPickerConsumed()
    }

    // When chat is fullscreen, Android back should minimize back to normal Home.
    BackHandler(enabled = chatFullscreen) {
        chatFullscreen = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Top-left scan button (overlay, doesn't take layout space)
        if (!chatFullscreen) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    // Move down to align roughly with the action buttons (near "Generate Recipe").
                    .padding(start = 8.dp, top = 124.dp)
                    .onGloballyPositioned { coords -> rectScan = coords.boundsInRoot() }
                    .zIndex(1200f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "AI Evaluate\nFood",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(72.dp)
                )
                IconButton(
                    onClick = { showScanPicker = true },
                    // ~75% of the previous size (96dp -> 72dp)
                    modifier = Modifier.size(72.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.camera),
                        contentDescription = "Scan item",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }

        if (showReviewPrompt) {
            AlertDialog(
                onDismissRequest = { showReviewPrompt = false },
                title = { Text("Leave a review?") },
                text = {
                    Text("If you’re enjoying AI Diet Assistant, would you mind leaving a quick review on Google Play?")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showReviewPrompt = false
                            // Never ask again after they choose to leave a review.
                            reviewPromptManager.disableForever()
                            val pkg = context.packageName
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("market://details?id=$pkg")
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            try {
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                // Fallback to https if Play Store app isn't available.
                                val web = android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://play.google.com/store/apps/details?id=$pkg")
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(web)
                            }
                        }
                    ) { Text("Leave review") }
                },
                dismissButton = {
                    Row {
                        TextButton(
                            onClick = {
                                showReviewPrompt = false
                                reviewPromptManager.deferOneWeek()
                            }
                        ) { Text("Later") }
                        TextButton(
                            onClick = {
                                showReviewPrompt = false
                                reviewPromptManager.disableForever()
                            }
                        ) { Text("No thanks") }
                    }
                }
            )
        }

        if (!chatFullscreen) {
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

            // Random wallpaper icons (ic_food_*): rerolls each time you enter this screen.
            if (state.profile?.showWallpaperFoodIcons != false) {
                RandomFoodWallpaper(seed = wallpaperSeed, count = 24, baseAlpha = 0.12f)
            }

            // Profile button (overlay, doesn't take layout space)
            IconButton(
                onClick = onOpenProfile,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 8.dp, end = 8.dp)
                    .onGloballyPositioned { coords -> rectProfile = coords.boundsInRoot() }
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
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
        ) {
        if (!chatFullscreen) {
            // Spacer to position the button stack under the background title art.
            // Compressed to give chat more vertical space.
            Spacer(modifier = Modifier.height(44.dp))

            // Add to list
            TextButton(
                onClick = onOpenAddEntry,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .onGloballyPositioned { coords -> rectAdd = coords.boundsInRoot() },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 4.dp)
            ) {
                // Keep consistent sizing with the other action buttons.
                val iconSize = 66.dp
                val iconGap = 8.dp
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Reserve the same space on the left as the (gap + icon) on the right
                    // so the label stays centered while the button still wraps content.
                    Spacer(modifier = Modifier.width(iconSize + iconGap))
                    Text("Add To List", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.width(iconGap))
                    Image(
                        painter = painterResource(id = R.drawable.checkmark),
                        contentDescription = null,
                        modifier = Modifier.size(iconSize),
                        contentScale = ContentScale.Fit
                    )
                }
            }

            Spacer(modifier = Modifier.height(0.dp))

            // Generate meal (pick from YOUR existing ingredients list)
            TextButton(
                onClick = {
                    showExistingIngredientsPicker = true
                },
                enabled = !state.isProcessing,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .onGloballyPositioned { coords -> rectGen = coords.boundsInRoot() },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 4.dp)
            ) {
                val iconSize = 66.dp
                val iconGap = 8.dp
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.width(iconSize + iconGap))
                    Text("Generate Meal", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.width(iconGap))
                    Image(
                        painter = painterResource(id = R.drawable.genmeal),
                        contentDescription = null,
                        modifier = Modifier.size(iconSize),
                        contentScale = ContentScale.Fit
                    )
                }
            }

            // Daily plan button + carrot indicator (carrot appears only while a plan exists today).
            Box(modifier = Modifier.fillMaxWidth()) {
                val hasPlanToday = state.dailyPlanMeals.isNotEmpty() && (state.dailyPlanDayId == null || state.dailyPlanDayId == todayId)
                if (hasPlanToday) {
                    IconButton(
                        onClick = { showDailyPlanOverlay = true },
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .size(96.dp)
                            .zIndex(2f)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_food_carrot2),
                            contentDescription = "Open daily plan",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
                TextButton(
                    onClick = { showDailyPlanPopup = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 4.dp),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .onGloballyPositioned { coords -> rectDaily = coords.boundsInRoot() }
                        .zIndex(1f)
                ) {
                    val iconSize = 66.dp
                    val iconGap = 8.dp
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(modifier = Modifier.width(iconSize + iconGap))
                        Text("Daily Plan", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.width(iconGap))
                        Image(
                            painter = painterResource(id = R.drawable.daily_plan),
                            contentDescription = null,
                            modifier = Modifier.size(iconSize),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(1.dp))

            // Push the chat (and vine overlay, which anchors to the chat top) down to make room
            // for the larger buttons above.
            Spacer(modifier = Modifier.height(18.dp))
        } else {
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Food list removed from AI Food Coach page (Home).
        // Access via Profile -> "View foods list".

        // Chat history
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // Thin transparent outline so the chat box feels like a contained surface.
                // Only in normal mode (fullscreen chat should feel edge-to-edge).
                .then(
                    if (!chatFullscreen) {
                        Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.18f),
                                shape = RoundedCornerShape(18.dp)
                            )
                            // Above wallpaper, below vine (vine uses zIndex 999f).
                            .zIndex(50f)
                            .padding(2.dp)
                    } else {
                        Modifier
                    }
                )
                .onGloballyPositioned { coords ->
                    chatTopPx = coords.positionInRoot().y.toInt()
                    rectChatHistory = coords.boundsInRoot()
                }
        ) {
            // History drawer (only in fullscreen): slides in from the right
            if (chatFullscreen && historyOpen) {
                // Scrim
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.35f))
                        .zIndex(2500f)
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { historyOpen = false })
                        }
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxHeight()
                        .width(280.dp)
                        .background(MaterialTheme.colorScheme.surface)
                        .zIndex(2600f)
                        .padding(12.dp)
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Chats", fontWeight = FontWeight.SemiBold)
                            TextButton(onClick = { historyOpen = false }) { Text("Close") }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = {
                                historyOpen = false
                                onNewChat()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("New chat")
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            state.chatSessions.forEach { c ->
                                val isActive = c.id == state.activeChatId
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = {
                                                historyOpen = false
                                                onSelectChat(c.id)
                                            },
                                            onLongClick = {
                                                // Don't allow deleting the protected default chat.
                                                if (c.id != "default") {
                                                    pendingDeleteChatId = c.id
                                                }
                                            }
                                        )
                                        .padding(vertical = 10.dp, horizontal = 8.dp)
                                ) {
                                    Text(
                                        text = c.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
                                        color = if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Long-press delete confirm
            pendingDeleteChatId?.let { chatId ->
                val chatTitle = state.chatSessions.firstOrNull { it.id == chatId }?.title ?: "Chat"
                AlertDialog(
                    onDismissRequest = { pendingDeleteChatId = null },
                    title = { Text("Delete chat?") },
                    text = { Text("Delete \"$chatTitle\"? This cannot be undone.") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                pendingDeleteChatId = null
                                onDeleteChat(chatId)
                            }
                        ) { Text("Delete") }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingDeleteChatId = null }) { Text("Cancel") }
                    }
                )
            }

            val iconColor = Color.Black

            // History button (3 lines) in upper-left, only in fullscreen
            if (chatFullscreen) {
                FilledIconButton(
                    onClick = { historyOpen = true },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 6.dp, start = 6.dp)
                        .zIndex(2000f)
                        .size(36.dp)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize().padding(9.dp)) {
                        val w = size.width
                        val h = size.height
                        val stroke = 2.6f
                        val y1 = h * 0.25f
                        val y2 = h * 0.50f
                        val y3 = h * 0.75f
                        drawLine(
                            color = iconColor,
                            start = androidx.compose.ui.geometry.Offset(0f, y1),
                            end = androidx.compose.ui.geometry.Offset(w, y1),
                            strokeWidth = stroke
                        )
                        drawLine(
                            color = iconColor,
                            start = androidx.compose.ui.geometry.Offset(0f, y2),
                            end = androidx.compose.ui.geometry.Offset(w, y2),
                            strokeWidth = stroke
                        )
                        drawLine(
                            color = iconColor,
                            start = androidx.compose.ui.geometry.Offset(0f, y3),
                            end = androidx.compose.ui.geometry.Offset(w, y3),
                            strokeWidth = stroke
                        )
                    }
                }
            }

            // Exit fullscreen toggle (dash) stays upper-right (only shown in fullscreen).
            if (chatFullscreen) {
                FilledIconButton(
                    onClick = {
                        // Closing fullscreen should also close the drawer.
                        historyOpen = false
                        chatFullscreen = false
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 6.dp, end = 6.dp)
                        .zIndex(2000f)
                        .size(36.dp)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize().padding(9.dp)) {
                        val stroke = 2.8f
                        val pad = 1.2f
                        // dash
                        drawLine(
                            color = iconColor,
                            start = androidx.compose.ui.geometry.Offset(pad, size.height / 2f),
                            end = androidx.compose.ui.geometry.Offset(size.width - pad, size.height / 2f),
                            strokeWidth = stroke
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                reverseLayout = true,
                // Ensure older messages (top) aren't hidden under the vine overlay.
                contentPadding = PaddingValues(top = if (chatFullscreen) 0.dp else vineHeight)
            ) {
            // Grocery scan actions (shown inline under the most recent message)
            state.pendingGrocery?.let { _ ->
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                            AlphaHitImageButton(
                                resId = R.drawable.btn_add,
                                // ~15% smaller than 170x64
                                size = DpSize(width = 145.dp, height = 54.dp),
                                contentDescription = "Add",
                                enabled = !state.isProcessing,
                                onClick = onConfirmGroceryAdd
                            )
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
                val isSaved = isRecipe && state.savedRecipes.any { it.id == msg.id || it.sourceMessageId == msg.id }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = if (isAi) Alignment.Start else Alignment.End
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isAi) Arrangement.Start else Arrangement.End
                    ) {
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
                        // Use full width so the chat bubble doesn't look like it has extra right padding.
                        val bubbleModifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(bubbleBrush)
                            .padding(horizontal = 12.dp, vertical = 10.dp)

                        if (isRecipe) {
                            // Chat recipe view: show plain text only (no food icons).
                            val annotated = MarkdownLite.toAnnotatedString(msg.text)
                            Text(
                                text = annotated,
                                modifier = bubbleModifier,
                                color = bubbleTextColor,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = fontSp.sp,
                                    lineHeight = (fontSp * 1.25f).sp
                                )
                            )
                        } else {
                            if (isAi) {
                                TypewriterAiText(
                                    messageId = msg.id,
                                    fullText = msg.text,
                                    typedDoneIds = typedAiDoneIds,
                                    wpm = 4000,
                                    modifier = bubbleModifier,
                                    color = bubbleTextColor,
                                    fontSp = fontSp
                                )
                            } else {
                                val annotated = MarkdownLite.toAnnotatedString(msg.text)
                                Text(
                                    text = annotated,
                                    modifier = bubbleModifier,
                                    color = bubbleTextColor,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = fontSp.sp,
                                        lineHeight = (fontSp * 1.25f).sp
                                    )
                                )
                            }
                        }
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
                                    modifier = Modifier.size(268.dp)
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

                    // (Intentionally no first-run action buttons here; keep chat clean.
                    // The main action after an AI recipe remains the existing big Save button.)
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
            Box(modifier = Modifier.onGloballyPositioned { coords -> rectFasting = coords.boundsInRoot() }) {
                EatingWindowBar(profile = p)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Freeform chat input with AI
        OutlinedTextField(
            value = chatInput,
            onValueChange = { chatInput = it },
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coords -> rectChatInput = coords.boundsInRoot() },
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
        val showVine = state.profile?.showVineOverlay == true
        if (!chatFullscreen && chatTopPx > 0) {
            // Enter fullscreen toggle (square) should always show (independent of vine visibility).
            FilledIconButton(
                onClick = { chatFullscreen = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    // Place at the top-right corner of the chat box (same spot it used to be),
                    // but as a top-level overlay so it can appear above the vine when vine is enabled.
                    .offset { IntOffset(x = 0, y = chatTopPx) }
                    .padding(top = 6.dp, end = 6.dp)
                    .zIndex(5000f)
                    .size(36.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize().padding(9.dp)) {
                    val stroke = 2.8f
                    val pad = 1.2f
                    drawRect(
                        color = Color.Black,
                        topLeft = androidx.compose.ui.geometry.Offset(pad, pad),
                        size = androidx.compose.ui.geometry.Size(size.width - 2 * pad, size.height - 2 * pad),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
                    )
                }
            }
        }

        if (showVine && !chatFullscreen && chatTopPx > 0) {
            // ~10% wider than before
            // Restore original vine scale; we instead space the chat downward to make room for buttons.
            val vineScaleX = 1.32f
            val vineScaleY = 4f
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
                    .offset {
                        // Relative nudge: +5px X and +10px Y from the previous tweak.
                        val dx = with(density) { (-5).dp.toPx() }.toInt()
                        // Move up by ~30px (negative Y).
                        val dy = with(density) { (-10).dp.toPx() }.toInt()
                        IntOffset(x = dx, y = chatTopPx - liftPx + downPx + dy)
                    }
                    // Stretch a bit more left/right AND top/bottom visually
                    .graphicsLayer(scaleX = vineScaleX, scaleY = vineScaleY),
                contentScale = ContentScale.FillBounds,
                alpha = 1f
            )
        }
    }

    // One-time Home tutorial overlay (dim + highlight + Next).
    // Only show when nothing else modal is open, to avoid stacking/confusion.
    if (showHomeTutorial &&
        !chatFullscreen &&
        !showReviewPrompt &&
        !showBeginnerIngredientsPicker &&
        !showExistingIngredientsPicker &&
        !showDailyPlanPopup &&
        !showDailyPlanOverlay
    ) {
        // Union rect for the "entire chat area" (history + input).
        val chatWholeRect = run {
            val a = rectChatHistory
            val b = rectChatInput
            if (a == null || b == null) null
            else Rect(
                left = minOf(a.left, b.left),
                top = minOf(a.top, b.top),
                right = maxOf(a.right, b.right),
                bottom = maxOf(a.bottom, b.bottom)
            )
        }

        // If the user doesn't have a fasting window enabled, fake an example bar just above the chat input
        // so they still understand what the feature looks like.
        val fastingExampleRect = run {
            if (rectFasting != null) return@run rectFasting
            val input = rectChatInput ?: return@run null
            val padPx = with(density) { 8.dp.toPx() }
            val hPx = with(density) { 28.dp.toPx() }
            val bottom = (input.top - padPx).coerceAtLeast(0f)
            val top = (bottom - hPx).coerceAtLeast(0f)
            Rect(left = input.left, top = top, right = input.right, bottom = bottom)
        }

        val fastingIsSet = (state.profile != null &&
            state.profile.fastingPreset.name != "NONE" &&
            state.profile.eatingWindowStartMinutes != null &&
            state.profile.eatingWindowEndMinutes != null)

        CoachMarkOverlay(
            steps = listOf(
                CoachStep(
                    title = "Step 1: Add Food",
                    body = "Add to List\nStart by adding foods you eat. This can be ingredients, snacks, or full meals. Everything else in the app builds from this.",
                    targetRect = { rectAdd }
                ),
                CoachStep(
                    title = "Step 2: Scan with AI",
                    body = "AI Evaluate Food\nUse your camera to scan food items or menus. The AI analyzes nutrition and provides a health score.",
                    targetRect = { rectScan }
                ),
                CoachStep(
                    title = "Step 3: Create Recipes",
                    body = "Generate Recipe\nCreate recipes using foods from your list or anything you want to cook. Save your favorite recipes to reuse later.",
                    targetRect = { rectGen }
                ),
                CoachStep(
                    title = "Step 4: Plan Your Day",
                    body = "Daily Plan\nGenerate a daily meal plan with 1 to 3 meals based on your calorie goal. You can also choose to plan your day using only your saved recipes.",
                    targetRect = { rectDaily }
                ),
                CoachStep(
                    title = "Step 5: Use Chat",
                    body = "Chat\nChat can see the foods you have added and the calories you have logged today. Get advice, add foods, track calories, or generate recipes all in one place.",
                    targetRect = { chatWholeRect },
                    cardPosition = CoachCardPosition.TOP
                ),
                CoachStep(
                    title = "Step 6: Manage Everything",
                    body = "Profile\nView your food list and saved recipes. Manage fasting settings and customize app options like text size or food icons.",
                    targetRect = { rectProfile }
                ),
                CoachStep(
                    title = "Step 7: Fasting Eating Window",
                    body = if (fastingIsSet) {
                        "This bar updates over time and shows when you can eat in orange and when you are fasting in blue."
                    } else {
                        "Set a fasting schedule in Profile to enable this bar. Orange shows eating time and blue shows fasting."
                    },
                    targetRect = { fastingExampleRect },
                    cardPosition = CoachCardPosition.TOP
                )
            ),
            onDone = {
                showHomeTutorial = false
                homeTutorialManager.markDone()
            },
            modifier = Modifier.zIndex(10000f)
        )
    }

    if (showBeginnerIngredientsPicker) {
        PopularIngredientsPickerDialog(
            enabled = !state.isProcessing,
            resolveIcon = { name -> FoodIconResolver.resolveFoodIconResId(context, name, allowFuzzy = true) },
            onDismiss = {
                showBeginnerIngredientsPicker = false
                onMarkBeginnerIngredientsPickerSeen()
            },
            onGenerate = { picked, targetCalories ->
                showBeginnerIngredientsPicker = false
                // Add selected ingredients to the user's list (as INGREDIENT), then generate meal immediately.
                onAddIngredients(picked)
                onMarkBeginnerIngredientsPickerSeen()
                onGenerateMeal(picked, targetCalories, false)
            }
        )
    }

    if (showExistingIngredientsPicker) {
        val candidates = remember(state.profile?.foodItems) {
            state.profile?.foodItems.orEmpty()
                .filter { item -> item.categories.any { it.trim().equals("INGREDIENT", ignoreCase = true) } }
                .mapNotNull { it.name.trim().ifBlank { null } }
                .distinctBy { it.lowercase() }
                .sortedBy { it.lowercase() }
        }
        ExistingIngredientsPickerDialog(
            candidates = candidates,
            enabled = !state.isProcessing,
            resolveIcon = { name -> FoodIconResolver.resolveFoodIconResId(context, name, allowFuzzy = true) },
            onDismiss = { showExistingIngredientsPicker = false },
            onGenerate = { picked, targetCalories, strictOnly ->
                showExistingIngredientsPicker = false
                onGenerateMeal(picked, targetCalories, strictOnly)
            }
        )
    }

    if (showDailyPlanPopup) {
        DailyPlanPopup(
            hasSavedRecipes = state.savedRecipes.isNotEmpty(),
            enabled = !state.isProcessing,
            onDismiss = { showDailyPlanPopup = false },
            onGenerate = { target, count, savedOnly ->
                showDailyPlanPopup = false
                onGenerateDailyPlan(target, count, savedOnly)
            }
        )
    }

    if (showDailyPlanOverlay) {
        val total = state.dailyPlanTotalCount.coerceAtLeast(state.dailyPlanMeals.size).coerceAtLeast(1)
        val completed = (total - state.dailyPlanMeals.size).coerceAtLeast(0)
        val progressTarget = completed.toFloat() / total.toFloat()
        val progressAnim = androidx.compose.animation.core.animateFloatAsState(
            targetValue = progressTarget.coerceIn(0f, 1f),
            animationSpec = androidx.compose.animation.core.tween(durationMillis = 450),
            label = "dailyPlanProgress"
        )

        // Fullscreen overlay: tap outside the blocks to dismiss.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .zIndex(6000f)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { showDailyPlanOverlay = false })
                }
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                state.dailyPlanMeals.take(3).forEach { m ->
                    val preview = m.recipeText
                        .trim()
                        .replace("\r", "")
                        .lineSequence()
                        .take(3)
                        .joinToString("\n")
                        .take(180)
                        .ifBlank { m.title }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            // Prevent outside-tap dismiss when tapping inside the block.
                            .pointerInput(Unit) { detectTapGestures(onTap = { /* handled by children */ }) }
                            .height(86.dp)
                    ) {
                        // 80%: open recipe detail
                        Box(
                            modifier = Modifier
                                .weight(0.8f)
                                .fillMaxHeight()
                                .clickable { onOpenDailyPlanMeal(m.id) }
                                .padding(12.dp)
                        ) {
                            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = m.title.ifBlank { "Meal" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "${m.estimatedCalories} cal",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = preview,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        // 20%: Done
                        Box(
                            modifier = Modifier
                                .weight(0.2f)
                                .fillMaxHeight()
                                .background(Color(0xFF1E88E5))
                                .clickable {
                                    onCompleteDailyPlanMeal(m.id)
                                    if (state.dailyPlanMeals.size <= 1) {
                                        // Last meal completed: hide overlay; carrot will disappear because list is empty.
                                        showDailyPlanOverlay = false
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Done", fontWeight = FontWeight.SemiBold, color = Color.White)
                        }
                    }
                }

                // Progress bar
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progressAnim.value)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
        }
    }

    if (showScanPicker) {
        Dialog(onDismissRequest = { showScanPicker = false }) {
            Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 8.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Evaluate",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { showScanPicker = false },
                            modifier = Modifier.padding(start = 8.dp)
                        ) { Text("Close") }
                    }

                    val canTap = true
                    val alpha = if (canTap) 1f else 0.45f

                    Image(
                        painter = painterResource(id = R.drawable.btn_food_item),
                        contentDescription = "Food Item",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                            .clickable(enabled = canTap) {
                                showScanPicker = false
                                onOpenGroceryScan()
                            },
                        contentScale = ContentScale.Fit,
                        alpha = alpha
                    )
                    Image(
                        painter = painterResource(id = R.drawable.btn_menu),
                        contentDescription = "Menu",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                            .clickable(enabled = canTap) {
                                showScanPicker = false
                                onOpenMenuScan()
                            },
                        contentScale = ContentScale.Fit,
                        alpha = alpha
                    )
                }
            }
        }
    }
}


@Composable
private fun DailyPlanPopup(
    hasSavedRecipes: Boolean,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onGenerate: (targetCalories: Int?, mealCount: Int, savedRecipesOnly: Boolean) -> Unit
) {
    var mealCount by rememberSaveable { mutableStateOf(2) }
    var preset by rememberSaveable { mutableStateOf("Maintain") }
    var sliderValue by rememberSaveable { mutableStateOf(2000f) }
    var savedOnly by rememberSaveable { mutableStateOf(false) }
    val green = Color(0xFF22C55E)

    fun presetValue(): Int = when (preset) {
        "Cut" -> 1600
        "Bulk" -> 2400
        else -> 2000
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 8.dp) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Daily Plan",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.padding(start = 8.dp)
                    ) { Text("Close") }
                }

                Text("Meals", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 2, 3).forEach { n ->
                        val selected = mealCount == n
                        Button(
                            onClick = { mealCount = n },
                            enabled = enabled,
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                            )
                        ) { Text("$n") }
                    }
                }

                Text("Calorie target", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Cut", "Maintain", "Bulk").forEach { p ->
                        val selected = preset == p
                        Button(
                            onClick = {
                                preset = p
                                sliderValue = presetValue().toFloat()
                            },
                            enabled = enabled,
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                            )
                        ) { Text(p) }
                    }
                }
                Text(
                    text = "${sliderValue.toInt()} calories",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    valueRange = 800f..3000f,
                    steps = 0,
                    enabled = enabled,
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = green,
                        activeTrackColor = green,
                        activeTickColor = green,
                        inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        inactiveTickColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Saved recipes only", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (hasSavedRecipes) "Only use recipes you’ve saved."
                            else "Save at least 1 recipe to enable this.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = savedOnly,
                        onCheckedChange = { savedOnly = it },
                        enabled = enabled && hasSavedRecipes
                    )
                }

                val target = sliderValue.toInt().coerceIn(800, 3000)

                val canGenerate = enabled
                val alpha = if (canGenerate) 1f else 0.45f
                androidx.compose.foundation.Image(
                    painter = painterResource(id = R.drawable.generate_plan),
                    contentDescription = "Generate",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .clickable(enabled = canGenerate) {
                            onGenerate(target, mealCount, savedOnly && hasSavedRecipes)
                        },
                    contentScale = ContentScale.Fit,
                    alpha = alpha
                )
            }
        }
    }
}

// GenerateRecipePopup removed (replaced by PopularIngredientsPickerDialog).

@Composable
private fun IngredientDropdownField(
    label: String,
    value: String,
    placeholder: String,
    candidates: List<String>,
    selectedOther: Set<String>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    resolveIcon: (String) -> Int?,
    onSelect: (String) -> Unit,
    onClear: (() -> Unit)?
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f)) {
                val interactionSource = remember { MutableInteractionSource() }
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        enabled = true,
                        readOnly = true,
                        singleLine = true,
                        placeholder = { Text(placeholder) }
                    )
                    // Transparent overlay: OutlinedTextField can swallow clicks, so force-open the menu.
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null
                            ) { onExpandedChange(true) }
                    )
                }

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { onExpandedChange(false) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val filtered = candidates.filter { it !in selectedOther }
                    if (filtered.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No items found") },
                            onClick = { onExpandedChange(false) },
                            enabled = false
                        )
                    } else {
                        filtered.forEach { name ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        val iconId = resolveIcon(name)
                                        if (iconId != null) {
                                            Image(
                                                painter = painterResource(id = iconId),
                                                contentDescription = null,
                                                modifier = Modifier.size(22.dp),
                                                contentScale = ContentScale.Fit
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                        } else {
                                            Spacer(modifier = Modifier.width(30.dp))
                                        }
                                        Text(name)
                                    }
                                },
                                onClick = { onSelect(name) }
                            )
                        }
                    }
                }
            }

            if (onClear != null) {
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onClear) { Text("X") }
            }
        }
    }
}

@Composable
private fun TypewriterAiText(
    messageId: String,
    fullText: String,
    typedDoneIds: MutableList<String>,
    wpm: Int,
    modifier: Modifier,
    color: Color,
    fontSp: Float
) {
    // Skip animation for very long messages to avoid "forever typing" (e.g., recipes already handled elsewhere).
    // Keep it simple: only animate if it's not too large.
    val shouldAnimate = fullText.length <= 2200
    val isDone = !shouldAnimate || typedDoneIds.contains(messageId)

    if (isDone) {
        val annotated = MarkdownLite.toAnnotatedString(fullText)
        Text(
            text = annotated,
            modifier = modifier,
            color = color,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = fontSp.sp,
                lineHeight = (fontSp * 1.25f).sp
            )
        )
        return
    }

    var shownChars by remember(messageId) { mutableStateOf(0) }
    LaunchedEffect(messageId, fullText) {
        val total = fullText.length
        shownChars = 0

        // Approximate: ~6 chars per word (5 letters + space).
        val cps = (wpm * 6f) / 60f
        val start = System.nanoTime()
        while (shownChars < total) {
            withFrameNanos { frameNanos ->
                val elapsedSec = (frameNanos - start).toDouble() / 1_000_000_000.0
                val target = (elapsedSec * cps).toInt().coerceIn(0, total)
                shownChars = target
            }
        }
        if (!typedDoneIds.contains(messageId)) typedDoneIds.add(messageId)
    }

    Text(
        text = fullText.take(shownChars),
        modifier = modifier,
        color = color,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontSize = fontSp.sp,
            lineHeight = (fontSp * 1.25f).sp
        )
    )
}

@Composable
private fun HoldToActivateTextButton(
    text: String,
    iconResId: Int,
    iconSize: Dp,
    enabled: Boolean,
    holdMillis: Long,
    onActivate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    var isHolding by remember { mutableStateOf(false) }
    var activatedThisHold by remember { mutableStateOf(false) }

    // Mirror TextButton visuals (no container).
    val contentColor = if (enabled) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.55f)
            .pointerInput(enabled, holdMillis) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        isHolding = true
                        activatedThisHold = false
                        // Restart animation each press.
                        var job: Job? = null
                        job = scope.launch {
                            progress.snapTo(0f)
                            progress.animateTo(
                                1f,
                                animationSpec = tween(
                                    durationMillis = holdMillis.toInt().coerceAtLeast(150),
                                    easing = LinearEasing
                                )
                            )
                            if (isHolding && !activatedThisHold) {
                                activatedThisHold = true
                                onActivate()
                            }
                        }

                        val released = tryAwaitRelease()
                        isHolding = false
                        // Cancel animation if user released early.
                        if (!activatedThisHold) {
                            job?.cancel()
                        }
                        // Reset ring quickly after release (whether activated or not).
                        runCatching {
                            job?.cancelAndJoin()
                        }
                        progress.snapTo(0f)
                    }
                )
            }
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(text, color = contentColor)

        // Icon + clockwise hold ring.
        Box(
            modifier = Modifier.size(iconSize),
            contentAlignment = Alignment.Center
        ) {
            // Progress ring (only visible while holding / progressing).
            val ringAlpha = if (isHolding) 1f else 0f
            Canvas(modifier = Modifier.matchParentSize().alpha(ringAlpha)) {
                val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                val inset = stroke.width / 2f
                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                val topLeft = Offset(inset, inset)
                // faint track
                drawArc(
                    color = contentColor.copy(alpha = 0.25f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke
                )
                // progress
                drawArc(
                    color = contentColor.copy(alpha = 0.95f),
                    startAngle = -90f,
                    sweepAngle = progress.value.coerceIn(0f, 1f) * 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke
                )
            }

            Image(
                painter = painterResource(id = iconResId),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Fit
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
