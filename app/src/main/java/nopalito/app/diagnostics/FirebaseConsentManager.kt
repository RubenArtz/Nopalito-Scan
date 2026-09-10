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

package nopalito.app.diagnostics

import android.content.Context
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import nopalito.app.i18n.LegalConsent
import nopalito.app.i18n.isComplete

/**
 * Applies the user's legal decision to the Firebase SDKs.
 *
 * Analytics collection defaults to OFF in the manifest
 * (`firebase_analytics_collection_enabled`) and follows the onboarding
 * acceptance: [AppContainer][nopalito.app.AppContainer] observes
 * [nopalito.app.i18n.LegalConsentRepository.consent] and calls [apply] on
 * every change, which also covers version bumps invalidating a past
 * acceptance. Never throws.
 *
 * Crashlytics (fatal crash reports) is intentionally NOT gated: it is enabled
 * once in [enableCrashReporting] from `Application.onCreate` and never turned
 * off. Crash traces carry no usage data or PII (non-fatals are additionally
 * sanitized by [FirebaseCrashReporter]), and gating them on consent blinded
 * the console to exactly the crashes that prevent users from ever reaching
 * the consent screen.
 */
object FirebaseConsentManager {

    private const val TAG = "FirebaseConsent"

    /**
     * Arms fatal crash reporting immediately. Called first in
     * `Application.onCreate`, before any code that could throw.
     */
    fun enableCrashReporting() {
        try {
            FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true)
        } catch (e: Exception) {
            Log.d(TAG, "crashlytics enable failed: ${e.javaClass.simpleName}")
        }
    }

    fun apply(appContext: Context, granted: Boolean) {
        try {
            try {
                FirebaseAnalytics.getInstance(appContext).setAnalyticsCollectionEnabled(granted)
            } catch (e: Exception) {
                Log.d(TAG, "analytics toggle failed: ${e.javaClass.simpleName}")
            }
            Log.d(TAG, "analytics collection enabled=$granted (crash reporting always on)")
        } catch (e: Exception) {
            try {
                Log.d(TAG, "apply failed: ${e.javaClass.simpleName}")
            } catch (_: Exception) {
            }
        }
    }

    fun applyFromConsent(appContext: Context, consent: LegalConsent) {
        apply(appContext, consent.isComplete())
    }
}