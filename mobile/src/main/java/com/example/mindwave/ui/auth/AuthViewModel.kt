package com.example.mindwave.ui.auth

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mindwave.data.AuthRepository
import kotlinx.coroutines.launch

/**
 * ViewModel for login/register/logout flows, using AuthRepository to manage auth state
 * and expose loading/error states to the UI.
 */
class AuthViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = AuthRepository(app.applicationContext)

    var isLoading by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    fun isLoggedIn(): Boolean = repo.isLoggedIn()

    fun isOfflineUser(): Boolean = repo.isOfflineUser()

    /** Bypasses the server and starts a fully local anonymous session. */
    fun continueOffline(onSuccess: () -> Unit) {
        repo.loginOffline()
        onSuccess()
    }

    fun login(email: String, password: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            repo.login(email.trim(), password)
                .onSuccess { onSuccess() }
                .onFailure { errorMessage = it.message }
            isLoading = false
        }
    }

    fun register(email: String, password: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            repo.register(email.trim(), password)
                .onSuccess { onSuccess() }
                .onFailure { errorMessage = it.message }
            isLoading = false
        }
    }

    fun logout(onLoggedOut: () -> Unit) {
        viewModelScope.launch {
            repo.logout()
            // Notify watch that phone is logged out (watch should stop sending data)
            WatchAuthNotifier.notifyWatch(
                getApplication(),
                isLoggedIn = false,
                userEmail = ""
            )
            onLoggedOut()
        }
    }

    fun clearError() {
        errorMessage = null
    }
}
