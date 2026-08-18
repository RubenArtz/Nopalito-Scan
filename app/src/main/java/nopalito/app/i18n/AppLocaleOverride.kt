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

package nopalito.app.i18n

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import java.util.*

/**
 * In-memory holder for the locale that must be applied to the Activity.
 * It is initialized on Application startup (from DataStore) and updated just
 * before [android.app.Activity.recreate] when the user changes the language.
 */
object AppLocaleOverride {
    @Volatile
    var locale: Locale = AppLanguage.default.locale

    /** Applies the override to a base context (used in attachBaseContext). */
    @Suppress("DEPRECATION")
    @SuppressLint("DiscouragedApi")
    fun applyTo(base: Context, locale: Locale): Context {
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}

/**
 * Localized string resolvers. Useful when a piece of UI must reflect a
 * *selected-but-not-yet-applied* language (e.g. the onboarding continue button).
 */
fun Context.stringFor(@StringRes resId: Int, locale: Locale, vararg args: Any): String {
    @Suppress("DEPRECATION")
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    val resources = createConfigurationContext(config).resources
    return if (args.isEmpty()) resources.getString(resId) else resources.getString(resId, *args)
}