package com.matchpoint.myaidietapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun AuthScreen(
    isProcessing: Boolean,
    error: String?,
    onSignIn: (email: String, password: String) -> Unit,
    onCreateAccount: (email: String, password: String) -> Unit
) {
    var isCreateMode by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    val canSubmit = email.isNotBlank() && password.length >= 6 &&
        (!isCreateMode || password == confirm) &&
        !isProcessing

    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = if (isCreateMode) "Create account" else "Sign in",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "Use email + password so your data and subscription can sync across devices.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(4.dp))

            OutlinedTextField(
                value = email,
                onValueChange = { email = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Email") },
                singleLine = true
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Password (min 6 chars)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            if (isCreateMode) {
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Confirm password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
            }

            if (!error.isNullOrBlank()) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Button(
                onClick = {
                    if (isCreateMode) onCreateAccount(email, password) else onSignIn(email, password)
                },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isCreateMode) "Create account" else "Sign in")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { isCreateMode = !isCreateMode }, enabled = !isProcessing) {
                    Text(if (isCreateMode) "Already have an account? Sign in" else "New here? Create account")
                }
            }
        }
    }
}


