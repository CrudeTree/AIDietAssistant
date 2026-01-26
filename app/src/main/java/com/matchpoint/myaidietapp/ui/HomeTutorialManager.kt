package com.matchpoint.myaidietapp.ui

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf

class HomeTutorialManager(context: Context) {
    private val prefs = context.getSharedPreferences("home_tutorial", Context.MODE_PRIVATE)

    // Compose-backed state so UI can react immediately (Next/Skip, etc.)
    private val doneState = mutableStateOf(prefs.getBoolean(KEY_DONE, false))
    private val requestShowState = mutableStateOf(prefs.getBoolean(KEY_REQUEST_SHOW, false))
    private val stepState = mutableIntStateOf(prefs.getInt(KEY_STEP, 0))
    // Analytics: highest tutorial step index the user has reached (monotonic).
    private val maxStepState = mutableIntStateOf(prefs.getInt(KEY_MAX_STEP, 0))
    private val skipConfirmActionState = mutableStateOf<(() -> Unit)?>(null)

    /**
     * The tutorial shows when explicitly requested (Settings -> Show Tutorial),
     * or after sign-up (we request it programmatically).
     */
    fun shouldShow(): Boolean = !doneState.value && requestShowState.value

    fun requestShow() {
        prefs.edit()
            .putBoolean(KEY_REQUEST_SHOW, true)
            .putInt(KEY_STEP, 0)
            .apply()
        requestShowState.value = true
        stepState.intValue = 0
    }

    fun requestReplay() {
        // Allow replay even after it has been completed.
        prefs.edit()
            .remove(KEY_DONE)
            .putBoolean(KEY_REQUEST_SHOW, true)
            .putInt(KEY_STEP, 0)
            .apply()
        doneState.value = false
        requestShowState.value = true
        stepState.intValue = 0
    }

    fun step(): Int = stepState.intValue

    fun maxStepReached(): Int = maxStepState.intValue

    fun setStep(step: Int) {
        val v = step.coerceAtLeast(0)
        prefs.edit().putInt(KEY_STEP, v).apply()
        stepState.intValue = v
        if (v > maxStepState.intValue) {
            prefs.edit().putInt(KEY_MAX_STEP, v).apply()
            maxStepState.intValue = v
        }
    }

    fun requestSkipConfirm(onConfirmExit: () -> Unit) {
        skipConfirmActionState.value = onConfirmExit
    }

    fun pendingSkipConfirmAction(): (() -> Unit)? = skipConfirmActionState.value

    fun clearSkipConfirm() {
        skipConfirmActionState.value = null
    }

    fun markDone() {
        prefs.edit()
            .putBoolean(KEY_DONE, true)
            .remove(KEY_REQUEST_SHOW)
            .remove(KEY_STEP)
            .apply()
        doneState.value = true
        requestShowState.value = false
        stepState.intValue = 0
    }

    fun resetForTesting() {
        prefs.edit()
            .remove(KEY_DONE)
            .remove(KEY_REQUEST_SHOW)
            .apply()
        doneState.value = false
        requestShowState.value = false
        stepState.intValue = 0
    }

    private companion object {
        private const val KEY_DONE = "done_v1"
        private const val KEY_REQUEST_SHOW = "request_show_v1"
        private const val KEY_STEP = "step_v1"
        private const val KEY_MAX_STEP = "max_step_v1"
    }
}

