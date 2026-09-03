/*
 *
 * Copyright 2025-2026 The FairScan authors
 * Copyright 2026 Ruben Matias
 *
 * Modified by Ruben Matias in 2026.
 * This file is part of the Nopalito Scan fork.
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 *
 */

package nopalito.app.ui.screens.cloud.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import nopalito.app.NopalitoApp
import nopalito.app.R
import nopalito.app.i18n.AppLocaleOverride
import nopalito.app.i18n.stringFor
import nopalito.app.ui.screens.cloud.data.CloudErrorPresenter
import nopalito.app.ui.screens.cloud.data.CloudRepository
import nopalito.app.ui.screens.cloud.model.StorageUsage
import nopalito.app.ui.screens.cloud.security.BiometricUnlockOutcome
import kotlin.time.Duration.Companion.milliseconds

data class StorageUiState(
    val usage: StorageUsage? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,

    // Download folder (SAF tree URI used by every download destination).
    val downloadDirUri: String? = null,

    // Biometric unlock (toggle on this screen).
    val biometricEnabled: Boolean = false,
    val biometricBusy: Boolean = false,
    val biometricMessage: String? = null,
    val showWeakDialog: Boolean = false,

    // Email login-code toggle (user can enable/disable OTP on login).
    val loginCodeEnabled: Boolean = true,
    val loginCodeBusy: Boolean = false,
    val loginCodeMessage: String? = null,
    val loginCodeLoaded: Boolean = false,

    // Change password (inline, same view): code by email → set new password.
    val changeOpen: Boolean = false,
    val changeCode: String = "",
    val changeNewPassword: String = "",
    val changeConfirmPassword: String = "",
    val changeCodeSent: Boolean = false,
    val changeSending: Boolean = false,
    val changeSubmitting: Boolean = false,
    val changeSuccess: Boolean = false,
    // Confirmation after the code email goes out ("sent"/"resent") plus the
    // anti-spam countdown mirrored from the backend cooldown.
    val changeInfoMessage: String? = null,
    val changeResendCooldownSeconds: Int = 0,
    val changeError: String? = null,

    // Change email (inline, same view): code to new address → confirm.
    val emailChangeOpen: Boolean = false,
    val emailNewEmail: String = "",
    val emailCode: String = "",
    val emailCodeSent: Boolean = false,
    val emailSending: Boolean = false,
    val emailSubmitting: Boolean = false,
    val emailSuccess: Boolean = false,
    val emailInfoMessage: String? = null,
    val emailResendCooldownSeconds: Int = 0,
    val emailError: String? = null,

    // Profile header (email + display name shown at top of cloud settings)
    val profileEmail: String? = null,
    val profileDisplayName: String? = null,
    val profileLoading: Boolean = false,
    val profileGoogleId: String? = null,
    val profileAuthProvider: String? = null
)

class CloudStorageViewModel(
    private val repository: CloudRepository,
    private val application: Application,
) : ViewModel() {

    private val weakPreference by lazy {
        try {
            nopalito.app.ui.screens.cloud.security.BiometricWeakPreference.open(application)
        } catch (_: Exception) {
            // Fallback for unit tests with mock Application (no real SharedPreferences)
            val map = mutableMapOf<String, Any>()
            val fakePrefs = object : android.content.SharedPreferences {
                override fun getAll(): MutableMap<String, *> = map
                override fun getString(key: String?, defValue: String?): String? =
                    map[key] as? String ?: defValue

                @Suppress("UNCHECKED_CAST")
                override fun getStringSet(
                    key: String?,
                    defValues: MutableSet<String>?
                ): MutableSet<String>? = map[key] as? MutableSet<String> ?: defValues

                override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
                override fun getLong(key: String?, defValue: Long): Long =
                    map[key] as? Long ?: defValue

                override fun getFloat(key: String?, defValue: Float): Float =
                    map[key] as? Float ?: defValue

                override fun getBoolean(key: String?, defValue: Boolean): Boolean =
                    map[key] as? Boolean ?: defValue

                override fun contains(key: String?): Boolean = map.containsKey(key)
                override fun edit(): android.content.SharedPreferences.Editor =
                    object : android.content.SharedPreferences.Editor {
                        override fun putString(
                            k: String?,
                            v: String?
                        ): android.content.SharedPreferences.Editor {
                            if (k != null) {
                                if (v == null) map.remove(k) else map[k] = v
                            }; return this
                        }

                        override fun putStringSet(
                            k: String?,
                            v: MutableSet<String>?
                        ): android.content.SharedPreferences.Editor {
                            if (k != null) {
                                if (v == null) map.remove(k) else map[k] = v
                            }; return this
                        }

                        override fun putInt(
                            k: String?,
                            v: Int
                        ): android.content.SharedPreferences.Editor {
                            if (k != null) map[k] = v; return this
                        }

                        override fun putLong(
                            k: String?,
                            v: Long
                        ): android.content.SharedPreferences.Editor {
                            if (k != null) map[k] = v; return this
                        }

                        override fun putFloat(
                            k: String?,
                            v: Float
                        ): android.content.SharedPreferences.Editor {
                            if (k != null) map[k] = v; return this
                        }

                        override fun putBoolean(
                            k: String?,
                            v: Boolean
                        ): android.content.SharedPreferences.Editor {
                            if (k != null) map[k] = v; return this
                        }

                        override fun remove(k: String?): android.content.SharedPreferences.Editor {
                            if (k != null) map.remove(k); return this
                        }

                        override fun clear(): android.content.SharedPreferences.Editor {
                            map.clear(); return this
                        }

                        override fun commit(): Boolean = true
                        override fun apply() {}
                    }

                override fun registerOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
                override fun unregisterOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
            }
            nopalito.app.ui.screens.cloud.security.BiometricWeakPreference.from(fakePrefs)
        }
    }
    private val biometricChecker by lazy {
        try {
            nopalito.app.ui.screens.cloud.security.AndroidBiometricCapabilityChecker(application)
        } catch (_: Exception) {
            // Fallback checker that reports no weak fallback (safe for tests)
            nopalito.app.ui.screens.cloud.security.BiometricCapabilityChecker { _ -> nopalito.app.ui.screens.cloud.security.BiometricCapability.RESULT_ERROR_NO_HARDWARE }
        }
    }

    private fun isWeakAccepted(): Boolean = try {
        weakPreference.isWeakAccepted
    } catch (_: Exception) {
        false
    }

    private val _state = MutableStateFlow(
        StorageUiState(biometricEnabled = repository.isBiometricMode())
    )
    val state: StateFlow<StorageUiState> = _state.asStateFlow()

    private var changeCooldownJob: Job? = null
    private var emailChangeCooldownJob: Job? = null

    private val settingsRepository: nopalito.app.ui.screens.settings.SettingsRepository? by lazy {
        try {
            val ctx = try {
                application.applicationContext
            } catch (_: Exception) {
                application
            }
            (ctx as? NopalitoApp)?.appContainer?.settingsRepository
        } catch (_: Exception) {
            null
        }
    }

    init {
        refreshDownloadDir()
        loadLoginCodePreference()
        loadProfile()
        observeGlobalEntitlement()
        // Legacy bus kept as signal only — manager is authority, no network here to avoid loop
        viewModelScope.launch {
            try {
                nopalito.app.billing.BillingSyncBus.events.collect {
                    // No direct refresh; entitlementFlow already updates
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun observeGlobalEntitlement() {
        viewModelScope.launch {
            try {
                val ctx = try {
                    application.applicationContext ?: application as android.content.Context
                } catch (_: Exception) {
                    application as android.content.Context
                }
                val mgr = nopalito.app.billing.BillingEntitlementManager.getInstance(ctx)
                    ?: return@launch
                mgr.entitlementFlow.collect { ent ->
                    val usage = nopalito.app.ui.screens.cloud.model.StorageUsage(
                        plan = ent.plan,
                        limitBytes = ent.storageLimitBytes,
                        usedBytes = ent.storageUsedBytes ?: _state.value.usage?.usedBytes ?: 0L,
                        freeBytes = ent.storageAvailableBytes ?: _state.value.usage?.freeBytes
                        ?: ent.storageLimitBytes,
                        usedPercent = if (ent.storageLimitBytes > 0 && ent.storageUsedBytes != null) ((ent.storageUsedBytes * 100 / ent.storageLimitBytes).toInt()
                            .coerceIn(0, 100)) else _state.value.usage?.usedPercent ?: 0,
                        isPremium = ent.plan != "FREE"
                    )
                    _state.value =
                        _state.value.copy(usage = usage, isRefreshing = false, isLoading = false)
                }
            } catch (_: Exception) {
            }
        }
    }

    fun loadProfile() {
        val cachedEmail = try {
            repository.getCurrentUserEmail()
        } catch (_: Exception) {
            null
        }
        val cachedName = try {
            repository.getCurrentUserDisplayName()
        } catch (_: Exception) {
            null
        }
        if (!cachedEmail.isNullOrBlank()) {
            _state.value =
                _state.value.copy(profileEmail = cachedEmail, profileDisplayName = cachedName)
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(profileLoading = true)
            try {
                repository.getProfile().fold(
                    onSuccess = { user ->
                        if (user != null) {
                            _state.value = _state.value.copy(
                                profileEmail = user.email,
                                profileDisplayName = user.displayName,
                                profileGoogleId = user.googleId,
                                profileAuthProvider = user.authProvider,
                                profileLoading = false
                            )
                        } else {
                            _state.value = _state.value.copy(profileLoading = false)
                        }
                    },
                    onFailure = {
                        _state.value = _state.value.copy(profileLoading = false)
                    }
                )
            } catch (_: Exception) {
                _state.value = _state.value.copy(profileLoading = false)
            }
        }
    }

    private fun loadLoginCodePreference() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loginCodeBusy = true)
            try {
                repository.getPreferences().fold(
                    onSuccess = { pref ->
                        val enabled = pref.requireLoginCode ?: true
                        _state.value = _state.value.copy(
                            loginCodeEnabled = enabled,
                            loginCodeLoaded = true,
                            loginCodeBusy = false,
                            loginCodeMessage = null
                        )
                    },
                    onFailure = { e ->
                        _state.value = _state.value.copy(
                            loginCodeBusy = false,
                            loginCodeLoaded = true,
                            loginCodeMessage = CloudErrorPresenter.message(
                                application, e, R.string.error_unknown
                            )
                        )
                    }
                )
            } catch (_: Exception) {
                _state.value = _state.value.copy(loginCodeBusy = false, loginCodeLoaded = true)
            }
        }
    }

    fun toggleLoginCode() {
        val current = _state.value
        if (current.loginCodeBusy) return
        val newEnabled = !current.loginCodeEnabled
        viewModelScope.launch {
            _state.value = _state.value.copy(loginCodeBusy = true, loginCodeMessage = null)
            repository.updateLoginCodePreference(newEnabled).fold(
                onSuccess = { pref ->
                    val enabled = pref.requireLoginCode ?: newEnabled
                    _state.value = _state.value.copy(
                        loginCodeEnabled = enabled,
                        loginCodeBusy = false,
                        loginCodeMessage = application.stringFor(
                            if (enabled) R.string.cloud_login_code_enabled else R.string.cloud_login_code_disabled,
                            AppLocaleOverride.locale
                        )
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        loginCodeBusy = false,
                        loginCodeMessage = CloudErrorPresenter.message(
                            application, e, R.string.error_unknown
                        )
                    )
                }
            )
        }
    }

    fun refreshDownloadDir() {
        viewModelScope.launch {
            try {
                val uri = settingsRepository?.downloadDirUri?.first()
                _state.value = _state.value.copy(downloadDirUri = uri)
            } catch (_: Exception) {
            }
        }
    }

    /** Stores the SAF tree URI picked by the user; null resets to the default. */
    fun setDownloadDir(uri: String?) {
        viewModelScope.launch {
            try {
                settingsRepository?.setDownloadDirUri(uri)
            } catch (_: Exception) {
            }
            _state.value = _state.value.copy(downloadDirUri = uri)
        }
    }

    /**
     * Turns biometric unlock on/off. The repository owns the token migration
     * (normal prefs ↔ auth-bound blob); this only renders the outcome and
     * refuses to re-trigger while a prompt is in flight.
     *
     * Weak fallback: if STRONG unavailable but WEAK available and user has not
     * yet accepted WEAK, shows a warning dialog instead of directly enabling.
     * Existing STRONG users never see the dialog.
     */
    fun toggleBiometric() {
        val current = _state.value
        if (current.biometricBusy) return
        val enabling = !current.biometricEnabled
        if (enabling) {
            val shouldOfferWeak = try {
                nopalito.app.ui.screens.cloud.security.BiometricCapability
                    .shouldOfferWeakFallback(biometricChecker)
            } catch (_: Exception) {
                false // In tests with mock Application, BiometricManager may not be available
            }
            val weakAccepted = isWeakAccepted()
            if (shouldOfferWeak && !weakAccepted) {
                // Show warning: Class 2 face is less secure. User must choose.
                _state.value = current.copy(showWeakDialog = true)
                return
            }
            performToggleBiometric(true, shouldOfferWeak)
        } else {
            performToggleBiometric(false, allowWeak = false)
        }
    }

    /** User accepted WEAK in the warning dialog. */
    fun onWeakDialogAccept() {
        try {
            weakPreference.setAccepted()
        } catch (_: Exception) {
        }
        _state.value = _state.value.copy(showWeakDialog = false)
        performToggleBiometric(enabling = true, allowWeak = true)
    }

    /** User chose "Only PIN" — keep biometric disabled, dismiss dialog. */
    fun onWeakDialogUsePin() {
        _state.value = _state.value.copy(showWeakDialog = false, biometricMessage = null)
    }

    /** Dismiss dialog without action. */
    fun onWeakDialogDismiss() {
        _state.value = _state.value.copy(showWeakDialog = false)
    }

    private fun performToggleBiometric(enabling: Boolean, allowWeak: Boolean) {
        val current = _state.value
        if (current.biometricBusy) return
        _state.value =
            current.copy(biometricBusy = true, biometricMessage = null, showWeakDialog = false)
        repository.setBiometricEnabled(enabling, allowWeak) { outcome ->
            _state.value = when (outcome) {
                is BiometricUnlockOutcome.Enabled -> _state.value.copy(
                    biometricEnabled = true, biometricBusy = false, biometricMessage = null
                )

                is BiometricUnlockOutcome.Disabled -> _state.value.copy(
                    biometricEnabled = false, biometricBusy = false, biometricMessage = null
                )

                is BiometricUnlockOutcome.Cancelled,
                is BiometricUnlockOutcome.Unlocked,
                    -> _state.value.copy(biometricBusy = false)

                is BiometricUnlockOutcome.LockedOut -> _state.value.copy(
                    biometricBusy = false,
                    biometricMessage = application.stringFor(
                        R.string.cloud_biometric_unlock_locked_out,
                        AppLocaleOverride.locale
                    )
                )

                is BiometricUnlockOutcome.NotAvailable -> _state.value.copy(
                    biometricBusy = false,
                    biometricMessage = application.stringFor(
                        R.string.cloud_biometric_unlock_unavailable,
                        AppLocaleOverride.locale
                    )
                )

                is BiometricUnlockOutcome.NoSecureLockScreen -> _state.value.copy(
                    biometricBusy = false,
                    biometricMessage = application.stringFor(
                        R.string.cloud_biometric_unlock_no_screen_lock,
                        AppLocaleOverride.locale
                    )
                )

                is BiometricUnlockOutcome.Failed,
                is BiometricUnlockOutcome.KeyInvalidated,
                    -> _state.value.copy(
                    biometricBusy = false,
                    biometricMessage = application.stringFor(
                        R.string.cloud_biometric_toggle_failed,
                        AppLocaleOverride.locale
                    )
                )
            }
        }
    }

    fun refresh() {
        // Delegate storage refresh to global manager when available
        try {
            val mgr = nopalito.app.billing.BillingEntitlementManager.getInstance()
            if (mgr != null) {
                loadLoginCodePreference()
                loadProfile()
                _state.value = _state.value.copy(isRefreshing = true, errorMessage = null)
                mgr.refresh(force = true, reason = nopalito.app.billing.BillingRefreshReason.MANUAL)
                return
            }
        } catch (_: Exception) {
        }
        // Fallback direct (tests without manager)
        loadLoginCodePreference()
        loadProfile()
        viewModelScope.launch {
            _state.value = _state.value.copy(isRefreshing = true, errorMessage = null)
            repository.getStorageUsage().fold(
                onSuccess = { usage ->
                    // The limit/plan always come from the backend: the client
                    // only renders them.
                    _state.value =
                        _state.value.copy(usage = usage, isLoading = false, isRefreshing = false)
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = CloudErrorPresenter.message(
                            application,
                            e,
                            R.string.error_unknown
                        )
                    )
                }
            )
        }
    }

    fun toggleChangePassword() {
        changeCooldownJob?.cancel()
        _state.value = _state.value.copy(
            changeOpen = !_state.value.changeOpen,
            changeCode = "",
            changeNewPassword = "",
            changeConfirmPassword = "",
            changeCodeSent = false,
            changeSuccess = false,
            changeInfoMessage = null,
            changeResendCooldownSeconds = 0,
            changeError = null
        )
    }

    private inline fun updateChange(block: (StorageUiState) -> StorageUiState) {
        _state.value = block(_state.value)
    }

    private inline fun updateEmailChange(block: (StorageUiState) -> StorageUiState) {
        _state.value = block(_state.value)
    }

    fun updateChangeCode(code: String) =
        updateChange {
            it.copy(
                changeCode = code.filter { c -> c.isDigit() }.take(6),
                changeError = null
            )
        }

    fun updateChangeNewPassword(password: String) =
        updateChange { it.copy(changeNewPassword = password, changeError = null) }

    fun updateChangeConfirmPassword(confirm: String) =
        updateChange { it.copy(changeConfirmPassword = confirm, changeError = null) }

    /**
     * Sends a single-use verification code to the account email. Everything
     * (code + new password) stays inside this same Storage view. [isResend]
     * only picks the confirmation wording ("sent" vs "resent").
     */
    fun requestChangePasswordCode(isResend: Boolean = false) {
        // Anti-spam: ignore taps while a send is in flight or inside the
        // cooldown window mirrored from the backend.
        val current = _state.value
        if (current.changeSending || (isResend && current.changeResendCooldownSeconds > 0)) return
        val email = repository.getCurrentUserEmail()
        if (email.isNullOrBlank()) {
            updateChange {
                it.copy(
                    changeError = application.stringFor(
                        R.string.cloud_no_session,
                        AppLocaleOverride.locale
                    )
                )
            }
            return
        }
        viewModelScope.launch {
            updateChange { it.copy(changeSending = true, changeError = null) }
            repository.requestSetPasswordCode(email).fold(
                onSuccess = { data ->
                    // Explicit confirmation that the email went out; the exact
                    // wording never reveals anything about the account.
                    updateChange {
                        it.copy(
                            changeSending = false,
                            changeCodeSent = true,
                            changeInfoMessage = application.stringFor(
                                if (isResend) R.string.cloud_code_resent else R.string.cloud_code_sent_to_email,
                                AppLocaleOverride.locale
                            )
                        )
                    }
                    startChangeCooldown(data.resendAvailableInSeconds)
                },
                onFailure = { e ->
                    // A 429 RESEND_COOLDOWN / rate limit resolves to a localized
                    // message with the remaining seconds via CloudErrorPresenter.
                    updateChange {
                        it.copy(
                            changeSending = false,
                            changeCodeSent = isResend || it.changeCodeSent,
                            changeError = CloudErrorPresenter.message(
                                application,
                                e,
                                R.string.cloud_error_sending_code
                            )
                        )
                    }
                }
            )
        }
    }

    fun resendChangePasswordCode() {
        updateChange { it.copy(changeCode = "") }
        requestChangePasswordCode(isResend = true)
    }

    /** Ticks down the resend cooldown once per second until it reaches zero. */
    private fun startChangeCooldown(seconds: Int) {
        changeCooldownJob?.cancel()
        if (seconds <= 0) return
        changeCooldownJob = viewModelScope.launch {
            for (remaining in seconds downTo 1) {
                _state.value = _state.value.copy(changeResendCooldownSeconds = remaining)
                delay(1000.milliseconds)
            }
            _state.value = _state.value.copy(changeResendCooldownSeconds = 0)
        }
    }

    /**
     * Verifies the code and stores the new password hash. All sessions are
     * revoked by the backend â†’ the app returns to the sign-in screen.
     */
    fun submitChangePassword() {
        val email = repository.getCurrentUserEmail()
        if (email.isNullOrBlank()) {
            updateChange {
                it.copy(
                    changeError = application.stringFor(
                        R.string.cloud_no_session,
                        AppLocaleOverride.locale
                    )
                )
            }
            return
        }
        val st = _state.value
        val error = when {
            st.changeCode.length != 6 -> R.string.cloud_otp_six_digits
            st.changeNewPassword.length < CloudRegisterViewModel.MIN_PASSWORD_LENGTH -> R.string.cloud_password_too_short
            st.changeNewPassword != st.changeConfirmPassword -> R.string.cloud_passwords_mismatch
            else -> null
        }
        if (error != null) {
            updateChange {
                it.copy(
                    changeError = application.stringFor(
                        error,
                        AppLocaleOverride.locale
                    )
                )
            }
            return
        }

        viewModelScope.launch {
            updateChange { it.copy(changeSubmitting = true, changeError = null) }
            repository.setPassword(
                email,
                st.changeCode,
                st.changeNewPassword,
                st.changeConfirmPassword
            ).fold(
                onSuccess = {
                    updateChange {
                        it.copy(
                            changeSubmitting = false,
                            changeSuccess = true
                        )
                    }
                },
                onFailure = { e ->
                    updateChange {
                        it.copy(
                            changeSubmitting = false,
                            changeError = CloudErrorPresenter.message(
                                application,
                                e,
                                R.string.cloud_invalid_code
                            )
                        )
                    }
                }
            )
        }
    }

    // ===== Change Email =====

    fun toggleChangeEmail() {
        emailChangeCooldownJob?.cancel()
        _state.value = _state.value.copy(
            emailChangeOpen = !_state.value.emailChangeOpen,
            emailNewEmail = "",
            emailCode = "",
            emailCodeSent = false,
            emailSending = false,
            emailSubmitting = false,
            emailSuccess = false,
            emailInfoMessage = null,
            emailResendCooldownSeconds = 0,
            emailError = null
        )
    }

    fun updateEmailNewEmail(newEmail: String) =
        updateEmailChange { it.copy(emailNewEmail = newEmail.trim(), emailError = null) }

    fun updateEmailCode(code: String) =
        updateEmailChange {
            it.copy(
                emailCode = code.filter { c -> c.isDigit() }.take(6),
                emailError = null
            )
        }

    fun requestEmailChangeCode(isResend: Boolean = false) {
        val current = _state.value
        if (current.emailSending || (isResend && current.emailResendCooldownSeconds > 0)) return
        val newEmail = current.emailNewEmail.trim()
        if (newEmail.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(newEmail)
                .matches()
        ) {
            updateEmailChange {
                it.copy(
                    emailError = application.stringFor(
                        R.string.cloud_invalid_email,
                        AppLocaleOverride.locale
                    )
                )
            }
            return
        }
        // Use authoritative profileEmail (from GET /api/auth/me) with fallback to cached token email.
        // This avoids stale-cache false positive after a crash where DB is new but cache is old (e.g. ruben...->hola... crash).
        val currentEmail = current.profileEmail ?: repository.getCurrentUserEmail()
        if (!currentEmail.isNullOrBlank() && newEmail.lowercase() == currentEmail.lowercase()) {
            updateEmailChange {
                it.copy(
                    emailError = application.stringFor(
                        R.string.cloud_email_same_as_current,
                        AppLocaleOverride.locale
                    )
                )
            }
            return
        }
        viewModelScope.launch {
            updateEmailChange { it.copy(emailSending = true, emailError = null) }
            repository.requestEmailChangeCode(newEmail).fold(
                onSuccess = { data ->
                    updateEmailChange {
                        it.copy(
                            emailSending = false,
                            emailCodeSent = true,
                            emailInfoMessage = application.stringFor(
                                if (isResend) R.string.cloud_code_resent else R.string.cloud_email_change_code_sent,
                                AppLocaleOverride.locale
                            )
                        )
                    }
                    startEmailCooldown(data.resendAvailableInSeconds)
                },
                onFailure = { e ->
                    updateEmailChange {
                        it.copy(
                            emailSending = false,
                            emailCodeSent = isResend || it.emailCodeSent,
                            emailError = CloudErrorPresenter.message(
                                application,
                                e,
                                R.string.cloud_error_sending_code
                            )
                        )
                    }
                }
            )
        }
    }

    fun resendEmailChangeCode() {
        updateEmailChange { it.copy(emailCode = "") }
        requestEmailChangeCode(isResend = true)
    }

    private fun startEmailCooldown(seconds: Int) {
        emailChangeCooldownJob?.cancel()
        if (seconds <= 0) return
        emailChangeCooldownJob = viewModelScope.launch {
            for (remaining in seconds downTo 1) {
                _state.value = _state.value.copy(emailResendCooldownSeconds = remaining)
                delay(1000.milliseconds)
            }
            _state.value = _state.value.copy(emailResendCooldownSeconds = 0)
        }
    }

    fun submitEmailChange() {
        val st = _state.value
        val newEmail = st.emailNewEmail.trim()
        val error = when {
            newEmail.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(newEmail)
                .matches() -> R.string.cloud_invalid_email

            st.emailCode.length != 6 -> R.string.cloud_otp_six_digits
            else -> null
        }
        if (error != null) {
            updateEmailChange {
                it.copy(
                    emailError = application.stringFor(
                        error,
                        AppLocaleOverride.locale
                    )
                )
            }
            return
        }
        val currentEmail = st.profileEmail ?: repository.getCurrentUserEmail()
        if (!currentEmail.isNullOrBlank() && newEmail.lowercase() == currentEmail.lowercase()) {
            updateEmailChange {
                it.copy(
                    emailError = application.stringFor(
                        R.string.cloud_email_same_as_current,
                        AppLocaleOverride.locale
                    )
                )
            }
            return
        }
        viewModelScope.launch {
            updateEmailChange { it.copy(emailSubmitting = true, emailError = null) }
            repository.confirmEmailChange(newEmail, st.emailCode).fold(
                onSuccess = { data ->
                    updateEmailChange { it.copy(emailSubmitting = false, emailSuccess = true) }
                    // refresh header immediately
                    val updatedEmail = data.user?.email ?: newEmail
                    val updatedName = data.user?.displayName
                    _state.value = _state.value.copy(
                        profileEmail = updatedEmail,
                        profileDisplayName = updatedName ?: _state.value.profileDisplayName
                    )
                    // also re-fetch from server to get authoritative display_name
                    loadProfile()
                    // also need to refresh profile google linkage after email change? handled via loadProfile above
                },
                onFailure = { e ->
                    updateEmailChange {
                        it.copy(
                            emailSubmitting = false,
                            emailError = CloudErrorPresenter.message(
                                application,
                                e,
                                R.string.cloud_invalid_code
                            )
                        )
                    }
                }
            )
        }
    }
}
