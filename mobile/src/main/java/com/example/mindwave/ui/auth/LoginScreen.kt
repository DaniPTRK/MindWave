package com.example.mindwave.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.Image
import com.example.mindwave.R

/**
 * Login screen with email & password fields, error display, and navigation to registration.
 * Uses AuthViewModel for state management and authentication logic.
 */
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onNavigateToRegister: () -> Unit = {},
    vm: AuthViewModel = viewModel(),
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val passwordFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    fun doLogin() {
        focusManager.clearFocus()
        vm.login(email, password, onSuccess = onLoginSuccess)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.mindwave_logo),
            contentDescription = "MindWave logo",
            modifier = Modifier.size(140.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "MindWave",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "Navigate stressed waters.\nArrive at inner peace.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )

        Spacer(Modifier.height(48.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it; vm.clearError() },
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it; vm.clearError() },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = if (passwordVisible) VisualTransformation.None
                                   else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { doLogin() }),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (passwordVisible) "Hide password" else "Show password",
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(passwordFocus),
        )

        vm.errorMessage?.let { msg ->
            Spacer(Modifier.height(8.dp))
            Text(
                msg,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { doLogin() },
            enabled = email.isNotBlank() && password.isNotBlank() && !vm.isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
        ) {
            if (vm.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Sign in")
            }
        }

        Spacer(Modifier.height(12.dp))

        TextButton(onClick = onNavigateToRegister) {
            Text("Don't have an account? Create one")
        }
    }
}
