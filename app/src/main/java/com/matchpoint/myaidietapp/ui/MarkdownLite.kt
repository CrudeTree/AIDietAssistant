package com.matchpoint.myaidietapp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * Very small markdown-ish renderer intended for LLM chat output.
 *
 * Supports:
 * - **bold**
 * - *italic*
 * - `inline code`
 *
 * Everything else is left as plain text.
 */
object MarkdownLite {
    /**
     * Fix a few common "LLM punctuation" artifacts so the text reads well.
     */
    fun normalize(raw: String): String {
        var s = raw
        // "-**437 minutes**" looks weird. Prefer an em dash.
        s = s.replace(Regex("\\s-\\*\\*"), " — **")
        s = s.replace(Regex("-\\*\\*"), " — **")
        // Avoid accidental "right now-**" (missing space)
        s = s.replace(Regex("([a-zA-Z0-9])—"), "$1 —")
        return s
    }

    @Composable
    fun toAnnotatedString(text: String): AnnotatedString {
        val base = normalize(text)

        val boldStyle = SpanStyle(fontWeight = FontWeight.SemiBold)
        val italicStyle = SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
        val codeStyle = SpanStyle(
            fontFamily = FontFamily.Monospace,
            background = MaterialTheme.colorScheme.surface,
            color = MaterialTheme.colorScheme.onSurface
        )

        return parse(base, boldStyle, italicStyle, codeStyle)
    }

    private fun parse(
        input: String,
        boldStyle: SpanStyle,
        italicStyle: SpanStyle,
        codeStyle: SpanStyle
    ): AnnotatedString {
        // Simple state machine. Good enough for chat; not a full markdown parser.
        var i = 0
        var bold = false
        var italic = false
        var code = false

        return buildAnnotatedString {
            fun pushStyles() {
                if (code) pushStyle(codeStyle)
                else {
                    if (bold) pushStyle(boldStyle)
                    if (italic) pushStyle(italicStyle)
                }
            }

            fun popStyles() {
                // Pop in reverse order of pushing.
                if (code) pop()
                else {
                    if (italic) pop()
                    if (bold) pop()
                }
            }

            // We manage styles by rebuilding them on every toggle to keep it simple and predictable.
            while (i < input.length) {
                // Inline code: toggles on single backticks.
                if (input[i] == '`') {
                    // Close/open code mode
                    if (code) {
                        // close code
                        pop()
                        code = false
                    } else {
                        // entering code: close any current bold/italic styles first
                        if (italic) { pop(); italic = false }
                        if (bold) { pop(); bold = false }
                        code = true
                        pushStyle(codeStyle)
                    }
                    i += 1
                    continue
                }

                // Bold: ** toggles (only when not in code)
                if (!code && i + 1 < input.length && input[i] == '*' && input[i + 1] == '*') {
                    // toggle bold
                    if (bold) {
                        pop()
                        bold = false
                    } else {
                        bold = true
                        pushStyle(boldStyle)
                    }
                    i += 2
                    continue
                }

                // Italic: * toggles (only when not in code, and not "**")
                if (!code && input[i] == '*') {
                    if (italic) {
                        pop()
                        italic = false
                    } else {
                        italic = true
                        pushStyle(italicStyle)
                    }
                    i += 1
                    continue
                }

                append(input[i])
                i += 1
            }

            // Close any dangling styles to avoid leaking stack.
            if (code) pop()
            if (italic) pop()
            if (bold) pop()
        }
    }
}


