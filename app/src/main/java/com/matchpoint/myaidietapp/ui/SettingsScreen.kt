package com.matchpoint.myaidietapp.ui

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matchpoint.myaidietapp.model.DietType
import com.matchpoint.myaidietapp.model.FastingPreset
import com.matchpoint.myaidietapp.model.UserProfile
import com.matchpoint.myaidietapp.model.WeightUnit

@Composable
fun SettingsScreen(
    profile: UserProfile,
    isProcessing: Boolean,
    errorText: String?,
    onBack: () -> Unit,
    onToggleShowFoodIcons: (Boolean) -> Unit,
    onSetFontSizeSp: (Float) -> Unit,
    onUpdateWeightUnit: (WeightUnit) -> Unit,
    onDietChange: (DietType) -> Unit,
    onUpdateFastingPreset: (FastingPreset) -> Unit,
    onUpdateEatingWindowStart: (Int) -> Unit,
    onDeleteAccount: (String) -> Unit
) {
    val ctx = LocalContext.current
    val tomatoId = remember {
        // User asked for "tomatoe" preview; your actual drawable is ic_food_tomato.webp.
        // We'll try a few common variants.
        val candidates = listOf("ic_food_tomatoe", "ic_food_tomato", "ic_food_tomatoes", "ic_food_tomato_sauce")
        candidates.firstNotNullOfOrNull { name ->
            val id = ctx.resources.getIdentifier(name, "drawable", ctx.packageName)
            id.takeIf { it != 0 }
        }
    }

    var deleteDialog by remember { mutableStateOf(false) }
    var deletePassword by remember { mutableStateOf("") }

    // Slider: live preview locally; persist on release.
    var fontSize by remember(profile.uiFontSizeSp) { mutableStateOf(profile.uiFontSizeSp.coerceIn(12f, 40f)) }
    var dietExpanded by remember { mutableStateOf(false) }
    var fastExpanded by remember { mutableStateOf(false) }
    var startExpanded by remember { mutableStateOf(false) }

    if (deleteDialog) {
        AlertDialog(
            onDismissRequest = { if (!isProcessing) deleteDialog = false },
            title = { Text("Delete account") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "This will permanently delete your account and your saved data (foods, photos, chat history).",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (!errorText.isNullOrBlank()) {
                        Text(
                            text = errorText,
                            color = Color(0xFFB00020),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    OutlinedTextField(
                        value = deletePassword,
                        onValueChange = { deletePassword = it },
                        label = { Text("Confirm password") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    if (isProcessing) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier, strokeWidth = 2.dp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { onDeleteAccount(deletePassword) },
                    enabled = !isProcessing && deletePassword.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB00020))
                ) { Text("Delete") }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { deleteDialog = false },
                    enabled = !isProcessing
                ) { Text("Cancel") }
            }
        )
    }

    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            if (!errorText.isNullOrBlank()) {
                Text(
                    text = errorText,
                    color = Color(0xFFB00020),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // --- Icons toggle with tomato preview ---
            Text(
                text = "Display",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(text = "Show food icons", style = MaterialTheme.typography.bodyMedium)
                    if (profile.showFoodIcons && tomatoId != null) {
                        Image(
                            painter = painterResource(id = tomatoId),
                            contentDescription = "Food icons preview",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                Switch(
                    checked = profile.showFoodIcons,
                    onCheckedChange = { onToggleShowFoodIcons(it) },
                    enabled = !isProcessing
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // --- Font size slider with live preview ---
            Text(
                text = "Font size",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "${fontSize.toInt()} sp",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = fontSize,
                onValueChange = { fontSize = it.coerceIn(12f, 40f) },
                valueRange = 12f..40f,
                enabled = !isProcessing,
                onValueChangeFinished = {
                    // Persist once per drag to avoid spamming Firestore.
                    onSetFontSizeSp(fontSize.coerceIn(12f, 40f))
                }
            )
            Text(
                text = "Preview: This is what recipes and chat will look like.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = fontSize.sp)
            )

            // --- Units ---
            Text(
                text = "Units",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Weight unit", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val lbSelected = profile.weightUnit == WeightUnit.LB
                    if (lbSelected) {
                        Button(onClick = { onUpdateWeightUnit(WeightUnit.LB) }, enabled = !isProcessing) { Text("lb") }
                        OutlinedButton(onClick = { onUpdateWeightUnit(WeightUnit.KG) }, enabled = !isProcessing) { Text("kg") }
                    } else {
                        OutlinedButton(onClick = { onUpdateWeightUnit(WeightUnit.LB) }, enabled = !isProcessing) { Text("lb") }
                        Button(onClick = { onUpdateWeightUnit(WeightUnit.KG) }, enabled = !isProcessing) { Text("kg") }
                    }
                }
            }

            // --- Diet & fasting ---
            Text(
                text = "Diet & fasting",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            // Diet type dropdown
            val selectedDiet = profile.dietType
            val selectedLabel = when (selectedDiet) {
                DietType.NO_DIET -> "No Diet"
                else -> selectedDiet.name.lowercase().replaceFirstChar { it.uppercase() }
            }
            Text(
                text = "Diet type",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { dietExpanded = !dietExpanded },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isProcessing
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(selectedLabel)
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
                                onDietChange(type)
                            }
                        )
                    }
                }
            }

            // Fasting preset dropdown
            Text(
                text = "Fasting schedule",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { fastExpanded = !fastExpanded },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isProcessing
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(profile.fastingPreset.label)
                        Text("▼")
                    }
                }
                DropdownMenu(
                    expanded = fastExpanded,
                    onDismissRequest = { fastExpanded = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FastingPreset.values().forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.label) },
                            onClick = {
                                fastExpanded = false
                                onUpdateFastingPreset(preset)
                            }
                        )
                    }
                }
            }

            // Window start preference (only when fasting is enabled)
            if (profile.fastingPreset != FastingPreset.NONE && profile.fastingPreset.eatingWindowHours != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Eating window starts",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )

                fun mm(m: Int): String = "%02d:%02d".format(m / 60, m % 60)
                val startMin = profile.eatingWindowStartMinutes
                val endMin = profile.eatingWindowEndMinutes
                val currentLabel = if (startMin != null && endMin != null) "${mm(startMin)}–${mm(endMin)}" else "Set start time"

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
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isProcessing
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(currentLabel)
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
                                    onUpdateEatingWindowStart(mins)
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

            // --- Danger zone ---
            Text(
                text = "Account",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Button(
                onClick = {
                    deletePassword = ""
                    deleteDialog = true
                },
                enabled = !isProcessing,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB00020))
            ) {
                Text("Delete account")
            }
        }
    }
}


