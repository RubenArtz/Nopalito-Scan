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

import nopalito.app.R
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Document
import java.io.File
import java.io.IOException
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Block: Etapa 7 — end-to-end i18n integration across the five supported
 * locales. Drives the real chain
 *
 * `HTTP/body → ErrorParser → ApiException → CloudErrorPresenter →
 * ErrorCodeMapper → strings.xml (locale catalog) → visible localized message`
 *
 * without a Context (JVM): the final `getString` step is replaced by reading
 * the generated `strings.xml` catalog for the locale and applying
 * [ErrorCodeMapper.format]/[ErrorCodeMapper.apply] with the exception's typed
 * details — the same work `CloudErrorPresenter.message` performs in production.
 *
 * Asserts, per locale: correct catalog resolution, a real (non-blank,
 * per-locale) visible message, placeholder substitution, absence of raw code /
 * backend text / serialized details, and fallback for legacy / unknown inputs.
 */
class I18nIntegrationTest {

    /** locale tag -> catalog directory in app/src/main/res. */
    private val localeDirs = mapOf(
        "es-419" to "values-b+es+419",
        "en-US" to "values",
        "de-DE" to "values-de",
        "fr-FR" to "values-fr",
        "pt-BR" to "values-pt-rBR",
    )

    private val allDirs = localeDirs.values.toList()

    private fun parse(dir: String): Document {
        val file = File("src/main/res/$dir/strings.xml")
        assertTrue("missing strings.xml: ${file.absolutePath}", file.exists())
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        return factory.newDocumentBuilder().parse(file)
    }

    /** name -> text (trimmed, normalized whitespace). */
    private fun catalog(dir: String): Map<String, String> {
        val doc = parse(dir)
        val result = HashMap<String, String>()
        for (i in 0 until doc.getElementsByTagName("string").length) {
            val el = doc.getElementsByTagName("string").item(i)
            val name = el.attributes?.getNamedItem("name")?.nodeValue ?: continue
            result[name] = el.textContent.trim().replace(Regex("\\s+"), " ")
        }
        return result
    }

    private fun resourceNameOf(resId: Int): String? =
        R.string::class.java.fields.firstOrNull { it.getInt(null) == resId }?.name

    /** The localized text the app would show for [resId] in [dir], placeholders applied. */
    private fun localizedFor(dir: String, resId: Int, e: ApiException): String {
        val name = resourceNameOf(resId)
        assertNotNull("resId $resId must map to a resource name", name)
        val template = catalog(dir)[name!!]
        assertNotNull("resource '$name' missing in $dir", template)
        val formatted = ErrorCodeMapper.format(template!!, e.details)
        return if (formatted.args.isEmpty()) formatted.pattern else ErrorCodeMapper.apply(
            formatted.pattern,
            formatted.args
        )
    }

    /** Full pipeline for one locale: raw body -> the visible localized message. */
    private fun pipeline(
        dir: String,
        statusCode: Int,
        rawBody: String,
        defaultRes: Int = R.string.error_unknown,
    ): Pair<ApiException, String> {
        val e = ErrorParser.parse(statusCode, rawBody).toApiException { "status-fallback" }
        val resolved = CloudErrorPresenter.resolve(e, defaultRes)
        val message = when (resolved) {
            is CloudErrorPresenter.Resolved.Legacy -> resolved.text
            is CloudErrorPresenter.Resolved.Res -> localizedFor(dir, resolved.resId, e)
        }
        return e to message
    }

    private fun assertNoRawText(message: String, code: String?) {
        if (code != null) assertTrue("raw code '$code' leaked: '$message'", !message.contains(code))
        assertTrue("leftover {placeholder} in '$message'", !message.contains('{'))
        assertTrue("serialized details leaked in '$message'", !message.contains("LinkedTreeMap"))
        assertTrue("JSON braces leaked in '$message'", !message.contains('{'))
    }

    // ---- Known code with placeholders, per locale ----

    @Test
    fun resendCooldownIsLocalizedWithWaitSecondsInEveryLocale() {
        for ((tag, dir) in localeDirs) {
            val (e, message) = pipeline(
                dir,
                429,
                """{"error":{"code":"RESEND_COOLDOWN","details":{"waitSeconds":30}}}"""
            )
            assertEquals(RESEND_COOLDOWN, e.code)
            assertTrue("$tag: expected 30 in '$message'", message.contains("30"))
            assertTrue("$tag: leftover {waitSeconds} in '$message'", !message.contains("{waitSeconds}"))
            assertNoRawText(message, e.code)
        }
    }

    @Test
    fun quotaExceededUsesDedicatedLocalizedResourceInEveryLocale() {
        for ((tag, dir) in localeDirs) {
            val (e, message) = pipeline(dir, 400, """{"error":{"code":"QUOTA_EXCEEDED"}}""")
            assertEquals(ApiException.QUOTA_EXCEEDED, e.code)
            val expected = catalog(dir)["cloud_error_quota_exceeded"]!!
            assertEquals("$tag: quota message must be the dedicated localized text", expected, message)
            assertNoRawText(message, e.code)
        }
    }

    @Test
    fun pushTargetTooLargeResolvesByStatusAndNeverLeaksDetails() {
        for ((tag, dir) in localeDirs) {
            val (e, message) = pipeline(
                dir,
                400,
                """{"error":{"code":"PUSH_TARGET_TOO_LARGE","details":{"count":120,"max":100}}}"""
            )
            assertEquals("PUSH_TARGET_TOO_LARGE", e.code)
            // Not a specific/group code -> status resource; details stay typed, never serialized.
            assertEquals("$tag: status resource expected", catalog(dir)["cloud_error_400"], message)
            assertNoRawText(message, e.code)
            assertTrue("$tag: '120' must not leak (no placeholder for it)", !message.contains("120"))
        }
    }

    // ---- Validation fields ----

    @Test
    fun validationErrorProvidesTypedFieldsAndStatusMessageInEveryLocale() {
        for ((tag, dir) in localeDirs) {
            val body = """{"error":{"code":"VALIDATION_ERROR","fields":[
                {"field":"name","message":"Nombre obligatorio"},
                {"field":"email","message":"Email invalido"}]}}"""
            val response = ErrorParser.parse(400, body)
            val fields = response.fields
            assertNotNull("$tag: fields expected", fields)
            assertEquals("$tag: field name", "name", fields?.get(0)?.field)
            assertEquals("$tag: field message", "Nombre obligatorio", fields?.get(0)?.message)

            val (e, message) = pipeline(dir, 400, body)
            assertEquals("VALIDATION_ERROR", e.code)
            // VALIDATION_ERROR has no dedicated/group/status code -> status resource.
            assertEquals("$tag: status resource expected", catalog(dir)["cloud_error_400"], message)
            assertNoRawText(message, e.code)
        }
    }

    // ---- Status / unknown ----

    @Test
    fun unknownCodeFallsBackToLocalizedStatusResourceInEveryLocale() {
        for ((tag, dir) in localeDirs) {
            val (e, message) = pipeline(dir, 500, """{"error":{"code":"WEIRD_INTERNAL"}}""")
            assertEquals("WEIRD_INTERNAL", e.code)
            assertEquals("$tag: 500 resource expected", catalog(dir)["cloud_error_500"], message)
            assertNoRawText(message, e.code)
        }
    }

    @Test
    fun unknownCodeAndStatusFallBackToLocalizedErrorUnknownInEveryLocale() {
        for ((tag, dir) in localeDirs) {
            val (e, message) = pipeline(dir, 418, """{"error":{"code":"MYSTERY"}}""")
            assertEquals("MYSTERY", e.code)
            assertEquals("$tag: error_unknown expected", catalog(dir)["error_unknown"], message)
            assertNoRawText(message, e.code)
        }
    }

    // ---- Legacy response (no code): sanitized backend text, per locale fallback ----

    @Test
    fun legacyNoCodeShowsSanitizedBackendTextOnlyWhenNoLocalizedResolution() {
        for ((tag, dir) in localeDirs) {
            val (e, message) = pipeline(dir, 200, """{"message":"Legacy raw <b>server</b> text"}""")
            assertTrue("$tag: code must be null for legacy envelope", e.code == null)
            // No code + no localized resolution -> sanitized backend text (HTML stripped).
            assertEquals(tag, "Legacy raw server text", message)
            assertTrue("$tag: sanitized message must be visible non-blank", message.isNotBlank())
        }
    }

    @Test
    fun legacyWithOnlyStatusCodeFallsBackToLocalizedStatusResource() {
        for ((tag, dir) in localeDirs) {
            val (e, message) = pipeline(dir, 503, """{"message":"maintenance soon"}""")
            assertTrue(e.code == null)
            // No code, but there IS a localized status resource -> never the raw message.
            assertEquals("$tag: 503 resource expected", catalog(dir)["cloud_error_503"], message)
        }
    }

    // ---- Network error (no HTTP body) ----

    @Test
    fun networkErrorResolvesToLocalizedConnectionResourceInEveryLocale() {
        for (dir in allDirs) {
            val resolved = CloudErrorPresenter.resolve(IOException("timeout"), R.string.error_unknown)
            assertTrue(resolved is CloudErrorPresenter.Resolved.Res)
            val resId = (resolved as CloudErrorPresenter.Resolved.Res).resId
            val name = resourceNameOf(resId)
            val text = catalog(dir)[name!!]
            assertNotNull("$dir: connection resource '$name' missing", text)
            assertTrue("$dir: '$name' must be a non-blank visible message", text!!.isNotBlank())
        }
    }

    // ---- Locale differentiation ----

    @Test
    fun visibleMessagesActuallyDifferAcrossLocales() {
        // Proves real localization (not shared ids) for the same error.
        val texts = localeDirs.values.map { dir ->
            val (_, message) = pipeline(dir, 400, """{"error":{"code":"QUOTA_EXCEEDED"}}""")
            message
        }
        assertTrue("expected distinct localized texts, got $texts", texts.toSet().size >= 2)
    }

    // ---- Network vs HTTP are distinct paths ----

    @Test
    fun httpErrorAndNetworkErrorProduceDifferentResources() {
        val http = CloudErrorPresenter.resolve(
            ErrorParser.parse(401, """{"error":{"code":"X"}}""").toApiException { "f" },
            R.string.error_unknown
        )
        val network = CloudErrorPresenter.resolve(IOException("x"), R.string.error_unknown)
        assertTrue(http is CloudErrorPresenter.Resolved.Res)
        assertTrue(network is CloudErrorPresenter.Resolved.Res)
        assertNotEquals(
            (http as CloudErrorPresenter.Resolved.Res).resId,
            (network as CloudErrorPresenter.Resolved.Res).resId
        )
    }

    private companion object {
        const val RESEND_COOLDOWN = "RESEND_COOLDOWN"
    }
}