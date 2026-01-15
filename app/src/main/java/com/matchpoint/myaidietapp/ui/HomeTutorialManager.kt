package com.matchpoint.myaidietapp.ui

import android.content.Context

class HomeTutorialManager(context: Context) {
    private val prefs = context.getSharedPreferences("home_tutorial", Context.MODE_PRIVATE)

    /**
     * We no longer auto-show the coachmark tutorial on first launch.
     * It only shows when explicitly requested (Settings -> Replay Tutorial, or How it Works).
     */
    fun shouldShow(): Boolean =
        !prefs.getBoolean(KEY_DONE, false) && prefs.getBoolean(KEY_REQUEST_SHOW, false)

    fun requestShow() {
        prefs.edit().putBoolean(KEY_REQUEST_SHOW, true).apply()
    }

    fun markDone() {
        prefs.edit()
            .putBoolean(KEY_DONE, true)
            .remove(KEY_REQUEST_SHOW)
            .apply()
    }

    fun resetForTesting() {
        prefs.edit()
            .remove(KEY_DONE)
            .remove(KEY_REQUEST_SHOW)
            .apply()
    }

    private companion object {
        private const val KEY_DONE = "done_v1"
        private const val KEY_REQUEST_SHOW = "request_show_v1"
    }
}

