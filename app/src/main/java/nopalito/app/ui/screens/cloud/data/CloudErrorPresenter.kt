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

package nopalito.app.ui.screens.cloud.data

import android.content.Context
import androidx.annotation.StringRes
import nopalito.app.R
import nopalito.app.i18n.AppLocaleOverride
import nopalito.app.i18n.stringFor
import nopalito.app.ui.screens.cloud.data.CloudErrorPresenter.message
import nopalito.app.ui.screens.cloud.data.CloudErrorPresenter.resolve
import nopalito.app.ui.screens.cloud.network.LogoutException

/**
 * Centralizes how a cloud failure is turned into a user-facing localized
 * message, enforcing the flow:
 *
 * `HTTP/body → ErrorParser → ApiException → ErrorCodeMapper → @StringRes →
 * localized string → UI`
 *
 * ViewModels must call [message]/[resolve] instead of reading `e.message`,
 * `e.localizedMessage`, `body.message` or `backendMessage` directly. The
 * backend message is never the first choice.
 *
 * Fallback order ([resolve]):
 * 1. specific error code;
 * 2. code group;
 * 3. HTTP status;
 * 4. [R.string.error_unknown];
 * 5. only for legacy responses without a code and without a localized
 *    resolution: the sanitized [ApiException.backendMessage];
 * 6. otherwise the caller-provided generic/screen resource.
 *
 * Network / non-API errors map to the existing connection resources
 * ([R.string.cloud_connection_error] / [R.string.cloud_connection_timeout]);
 * everything else falls back to [defaultRes]. [ApiException.details] is never
 * rendered as raw JSON here.
 */
object CloudErrorPresenter {

    /** The outcome of resolving a throwable to something the UI can show. */
    sealed class Resolved {
        /** A resource to resolve, formatted with the exception's typed details. */
        data class Res(@param:StringRes val resId: Int) : Resolved()

        /** A sanitized legacy backend message (no localized resource applies). */
        data class Legacy(val text: String) : Resolved()
    }

    /**
     * Pure decision: maps a [Throwable] to a [Resolved] message following the
     * documented fallback order. Testable on the JVM (no Context needed).
     */
    fun resolve(throwable: Throwable?, @StringRes defaultRes: Int): Resolved {
        if (throwable == null) return Resolved.Res(defaultRes)
        if (throwable is ApiException) {
            val resId = ErrorCodeMapper.resolveResId(throwable)
            // Rules 1-3: specific code / group / status resolved by the mapper.
            if (resId != R.string.error_unknown) return Resolved.Res(resId)
            // Rule 5: legacy response without a code and without any localized
            // resolution -> sanitized backend message.
            if (throwable.code == null && !throwable.backendMessage.isNullOrBlank()) {
                val sanitized = sanitizeLegacy(throwable.backendMessage)
                if (sanitized != null) return Resolved.Legacy(sanitized)
            }
            // Rules 4 / 6: error_unknown resource or the screen generic.
            return Resolved.Res(defaultRes)
        }
        return when (throwable) {
            is java.net.SocketTimeoutException -> Resolved.Res(R.string.cloud_connection_timeout)
            is java.net.UnknownHostException -> Resolved.Res(R.string.cloud_connection_error)
            is java.net.ConnectException -> Resolved.Res(R.string.cloud_connection_error)
            is LogoutException -> Resolved.Res(defaultRes) // logout signal, not a message
            is java.io.IOException -> Resolved.Res(R.string.cloud_connection_error)
            else -> Resolved.Res(defaultRes)
        }
    }

    /**
     * Produces the final localized string. [defaultRes] is the screen-specific
     * generic used when nothing more specific applies. Typed details from an
     * [ApiException] are interpolated into the resolved template (if any), but
     * never rendered as raw JSON.
     */
    fun message(context: Context, throwable: Throwable?, @StringRes defaultRes: Int): String {
        return when (val resolved = resolve(throwable, defaultRes)) {
            is Resolved.Legacy -> resolved.text
            is Resolved.Res -> {
                val template = context.stringFor(resolved.resId, AppLocaleOverride.locale)
                if (throwable is ApiException) {
                    val formatted = ErrorCodeMapper.format(template, throwable.details)
                    if (formatted.args.isEmpty()) formatted.pattern else ErrorCodeMapper.apply(
                        formatted.pattern,
                        formatted.args
                    )
                } else {
                    template
                }
            }
        }
    }

    /**
     * Defensive sanitizer for legacy [ApiException.backendMessage] before any
     * display: strips HTML, control characters, URLs, bearer tokens, file
     * paths, stack frames; collapses whitespace and caps the length. Input from
     * [ErrorParser] is already HTML/control-free, this is the presentation
     * layer safety net.
     */
    fun sanitizeLegacy(text: String?): String? {
        if (text == null) return null
        var s = text
        s = s.replace(Regex("""<[^>]*>"""), " ")
        s = s.replace(Regex("""\p{Cntrl}"""), "")
        s = s.replace(Regex("""https?://\S+"""), " ")
        s = s.replace(Regex("""Bearer\s+[A-Za-z0-9._~+/=-]+""", RegexOption.IGNORE_CASE), " ")
        s = s.replace(Regex("""(?:[A-Za-z]:[\\/]|[/~])[^\s]*"""), " ")
        s = s.replace(Regex("""at\s+[A-Za-z_$][\w.$]*\([^)]*:\d+\)"""), " ")
        s = s.replace(Regex("""\s+"""), " ").trim()
        if (s.length > 200) s = s.take(200) + "…"
        return s.takeIf { it.isNotBlank() }
    }
}