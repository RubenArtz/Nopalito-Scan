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

package nopalito.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Block: 8F — structural audit regression tests.
 *
 * Reads the Kotlin sources directly (JVM, no Android Context) and asserts that
 * the 8F fixes stay in place:
 *
 *  - the tool ViewModels never surface raw exception messages (no `e.message`
 *    fallbacks, no `exceptionOrNull()?.message`);
 *  - the QR generator and result screens render only localized labels (no raw
 *    security/style tokens, no `sync.message` toast detail);
 *  - logs never include backend bodies, exception messages or user content;
 *  - dates and sizes use locale-aware / locale-stable formatting (no fixed
 *    `dd/MM/yyyy` templates, no bare `"%.1f".format`).
 *
 * Working directory is the `app` module.
 */
class Stage8FStructuralTest {

    private val mainSrc = File("src/main/java")

    private fun sourceText(relativePath: String): String =
        File(mainSrc, relativePath).readText()

    private val toolViewModels = listOf(
        "nopalito/app/ui/screens/tools/ToolsViewModel.kt",
        "nopalito/app/ui/screens/tools/convert/ConvertViewModel.kt",
        "nopalito/app/ui/screens/tools/organizer/OrganizerViewModel.kt",
        "nopalito/app/ui/screens/tools/deletepages/DeletePagesViewModel.kt",
        "nopalito/app/ui/screens/tools/reorder/ReorderViewModel.kt",
        "nopalito/app/ui/screens/tools/extract/ExtractViewModel.kt",
        "nopalito/app/ui/screens/tools/passwordprotect/PasswordProtectViewModel.kt",
        "nopalito/app/ui/screens/tools/qrgenerator/QrGeneratorViewModel.kt",
        "nopalito/app/ui/screens/export/ExportViewModel.kt",
    )

    private fun assertNone(text: String, token: String, what: String) {
        assertFalse("$what: found '$token'", text.contains(token))
    }

    // ---- 1. No raw exception messages in the tool ViewModels ----

    @Test
    fun toolViewModelsNeverSurfaceExceptionMessages() {
        for (path in toolViewModels) {
            val text = sourceText(path)
            assertNone(text, "exceptionOrNull()?.message", path)
            assertNone(text, "e.message ?:", path)
            assertNone(text, "error.message ?:", path)
            assertNone(text, "throwable.message", path)
            assertNone(text, "FAILED(it.message)", path)
        }
    }

    @Test
    fun qrGeneratorViewModelUsesLocalizedErrors() {
        val text = sourceText("nopalito/app/ui/screens/tools/qrgenerator/QrGeneratorViewModel.kt")
        assertTrue("must resolve errors via CloudErrorPresenter", text.contains("CloudErrorPresenter.message(context"))
        assertTrue("content-required must be a resource", text.contains("R.string.qr_content_required"))
        assertTrue("generate-error must be a resource", text.contains("R.string.qr_generate_error"))
        assertFalse("hardcoded Spanish text", text.contains("Escribe un contenido"))
        assertFalse("hardcoded error text", text.contains("Error al generar"))
    }

    @Test
    fun cloudSyncFailureCarriesNoMessage() {
        val text = sourceText("nopalito/app/ui/screens/tools/qrgenerator/QrGeneratorViewModel.kt")
        assertFalse("FAILED must not carry a message", text.contains("FAILED(val message"))
    }

    @Test
    fun qrGeneratorScreenNeverRendersRawTokensOrSyncDetails() {
        val text = sourceText("nopalito/app/ui/screens/tools/qrgenerator/QrGeneratorScreen.kt")
        assertFalse("toast must not append sync.message", text.contains("sync.message"))
        assertFalse("security chips must not render raw token", text.contains("Text(sec)"))
        assertFalse("ChipRow must not render raw option", text.contains("Text(option)"))
    }

    @Test
    fun qrResultDialogLocalizesWifiSecurity() {
        val text = sourceText("nopalito/app/ui/screens/qr/QrResultDialog.kt")
        assertTrue("security must be localized", text.contains("wifiSecurityLabel(type.security)"))
        assertFalse("security must not be rendered raw", text.contains(", type.security)"))
    }

    // ---- 2. Logs never include bodies, messages or user content ----

    @Test
    fun fcmTokenSyncLogsAreSafe() {
        val text = sourceText("nopalito/app/push/FcmTokenSync.kt")
        assertNone(text, "errorBody()?.string()", "FcmTokenSync")
        assertNone(text, "\${e.message}", "FcmTokenSync")
    }

    @Test
    fun maintenanceViewModelLogsAreSafe() {
        val text = sourceText("nopalito/app/ui/screens/cloud/viewmodel/CloudMaintenanceViewModel.kt")
        assertNone(text, "\${e.message}", "CloudMaintenanceViewModel")
    }

    @Test
    fun scanUploaderLogsAreSafe() {
        val text = sourceText("nopalito/app/ui/screens/cloud/data/CloudScanUploader.kt")
        assertNone(text, "\${scan.content", "CloudScanUploader")
        assertNone(text, "\${it.message}", "CloudScanUploader")
    }

    @Test
    fun authInterceptorThrowsFixedMessages() {
        val text = sourceText("nopalito/app/ui/screens/cloud/network/AuthInterceptor.kt")
        assertNone(text, "Refresh failed: \${e.message}", "AuthInterceptor")
    }

    @Test
    fun exportScreenNeverShowsRawThrowableMessages() {
        val text = sourceText("nopalito/app/ui/screens/export/ExportScreen.kt")
        assertNone(text, "throwable.message", "ExportScreen")
    }

    // ---- 3. Locale-aware / locale-stable formats ----

    @Test
    fun historyScreensUseLocaleAwareDateFormats() {
        for (path in listOf(
            "nopalito/app/ui/screens/history/HistoryScreen.kt",
            "nopalito/app/ui/screens/cloud/screens/CloudQrHistoryScreen.kt",
        )) {
            val text = sourceText(path)
            assertNone(text, "SimpleDateFormat(\"dd/MM/yyyy", path)
            assertTrue("$path must use the locale-aware getDateTimeInstance", text.contains("getDateTimeInstance"))
        }
    }

    @Test
    fun dateEditorUsesLocaleAwarePattern() {
        val text = sourceText("nopalito/app/ui/screens/document/DateEditorDialog.kt")
        assertNone(text, "ofPattern(\"dd/MM/yyyy\")", "DateEditorDialog")
        assertTrue("must use ofLocalizedDate", text.contains("ofLocalizedDate"))
    }

    @Test
    fun trashCountdownIsFullyLocalized() {
        val text = sourceText("nopalito/app/ui/screens/cloud/screens/CloudTrashCountdown.kt")
        assertNone(text, "ofPattern(\"dd/MM/yyyy", "CloudTrashCountdown")
        assertNone(text, "\"<1m\"", "CloudTrashCountdown")
        assertNone(text, "\${days}d \${hours}h", "CloudTrashCountdown")
        assertTrue("must decompose via trashRemainingParts", text.contains("trashRemainingParts"))
        assertTrue("must format dates via ofLocalizedDateTime", text.contains("ofLocalizedDateTime"))
    }

    @Test
    fun cloudFileSizeUsesLocaleStableDecimalFormat() {
        val text = sourceText("nopalito/app/ui/screens/cloud/screens/CloudFileListScreen.kt")
        assertNone(text, "\"%.1f\".format", "CloudFileListScreen")
        assertNone(text, "\"%.2f\".format", "CloudFileListScreen")
        assertTrue("must use String.format with Locale.US", text.contains("String.format(Locale.US"))
    }

    @Test
    fun exportFilenameUsesLocaleStableTimestamp() {
        val text = sourceText("nopalito/app/ui/screens/export/ExportViewModel.kt")
        assertTrue(
            "filename timestamp must use Locale.US",
            text.contains("SimpleDateFormat(\"yyyy-MM-dd HH.mm.ss\", Locale.US)")
        )
        assertNone(text, "yyyy-MM-dd HH.mm.ss\", Locale.getDefault()", "ExportViewModel")
    }
}