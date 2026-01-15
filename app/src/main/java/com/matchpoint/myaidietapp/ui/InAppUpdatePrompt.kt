package com.matchpoint.myaidietapp.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.tasks.await

/**
 * Lightweight in-app update banner.
 *
 * - Shows "Update available" when Google Play reports an update.
 * - Uses FLEXIBLE updates (download in background), then prompts "Restart" when downloaded.
 * - No-op on non-Play installs (API will just report no update / throw).
 */
@Composable
fun InAppUpdatePrompt(modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val activity = ctx as? Activity ?: return

    val updateManager = remember { AppUpdateManagerFactory.create(ctx) }
    var updateAvailable by remember { mutableStateOf(false) }
    var updateDownloaded by remember { mutableStateOf(false) }
    var dismissedThisSession by remember { mutableStateOf(false) }

    fun refreshAvailability() {
        // Fire and forget (called from LaunchedEffect)
    }

    LaunchedEffect(Unit) {
        runCatching {
            val info = updateManager.appUpdateInfo.await()

            // If the user already started an update and the process was killed, resume it.
            val avail = info.updateAvailability()
            if (avail == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                updateAvailable = true
                dismissedThisSession = false
            } else if (avail == UpdateAvailability.UPDATE_AVAILABLE && info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
                updateAvailable = true
                dismissedThisSession = false
            } else {
                updateAvailable = false
            }

            if (info.installStatus() == InstallStatus.DOWNLOADED) {
                updateDownloaded = true
                updateAvailable = false
            }
        }
    }

    DisposableEffect(updateManager) {
        val listener = InstallStateUpdatedListener { state ->
            if (state.installStatus() == InstallStatus.DOWNLOADED) {
                updateDownloaded = true
                updateAvailable = false
            }
        }
        updateManager.registerListener(listener)
        onDispose {
            updateManager.unregisterListener(listener)
        }
    }

    if (dismissedThisSession) return

    // Banner overlay (top of screen)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        when {
            updateDownloaded -> {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Update ready", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                            Text(
                                "Restart the app to finish updating.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Button(onClick = { updateManager.completeUpdate() }) {
                            Text("Restart")
                        }
                    }
                }
            }
            updateAvailable -> {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Update available", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                            Text(
                                "Tap Update to download in the background.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { dismissedThisSession = true }) {
                                Text("Later")
                            }
                            Button(
                                onClick = {
                                    // Use the legacy API that doesn't require ActivityResult plumbing.
                                    // Safe: if the user cancels, Play just won't download the update.
                                    runCatching {
                                        val info = updateManager.appUpdateInfo.result
                                        updateManager.startUpdateFlowForResult(
                                            info,
                                            AppUpdateType.FLEXIBLE,
                                            activity,
                                            9117
                                        )
                                    }
                                }
                            ) {
                                Text("Update")
                            }
                        }
                    }
                }
            }
        }
    }
}

