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

package nopalito.app.ui

import nopalito.app.ui.screens.tools.CompressTool

sealed class Screen {
    sealed class Main : Screen() {
        object Camera : Main()
        object EditImage : Main()
        data class Document(val initialPage: Int = 0) : Main()
        object Export : Main()
        object ResumeScan : Main()
    }

    sealed class Overlay : Screen() {
        object About : Overlay()
        object Libraries : Overlay()
        object Settings : Overlay()
        object OcrLanguages : Overlay()
        object History : Overlay()
        object QrHistory : Overlay()
        object NopalitoScanCloud : Overlay()
        object Tools : Overlay()
        data class ToolCompress(val tool: CompressTool) : Overlay()
        object PasswordProtect : Overlay()
        object Convert : Overlay()
        object Extract : Overlay()
        object Reorder : Overlay()
        object DeletePages : Overlay()
        object OrganizePages : Overlay()
        object QrGenerator : Overlay()
        object Stats : Overlay()
    }
}

data class Navigation(
    val toCameraScreen: () -> Unit,
    val toEditImageScreen: () -> Unit,
    val toDocumentScreen: () -> Unit,
    val toExportScreen: () -> Unit,
    val toAboutScreen: () -> Unit,
    val toLibrariesScreen: () -> Unit,
    val toSettingsScreen: (() -> Unit)?,
    val toOcrLanguagesScreen: () -> Unit,
    val toHistoryScreen: () -> Unit,
    /** Navigate to the QR/barcode scan history. */
    val toQrHistoryScreen: () -> Unit,
    /** Navigate to Nopalito Scan Cloud. Redirects to login screen inside CloudHost if unauthenticated. */
    val toCloudScreen: () -> Unit,
    /** Navigate to the Tools section. */
    val toToolsScreen: () -> Unit,
    /** Navigate to a single compression tool. */
    val toToolCompress: (CompressTool) -> Unit,
    /** Switch the current tool in place, replacing the stack entry (no extra back step). */
    val switchTool: (CompressTool) -> Unit,
    /** Navigate to the "Protect with password" tool. */
    val toPasswordProtectScreen: () -> Unit,
    /** Navigate to the "Convert document" tool. */
    val toConvertScreen: () -> Unit,
    /** Navigate to the "Extract PDF pages" tool. */
    val toExtractScreen: () -> Unit,
    /** Navigate to the "Reorder PDF pages" tool. */
    val toReorderScreen: () -> Unit,
    /** Navigate to the "Delete PDF pages" tool. */
    val toDeletePagesScreen: () -> Unit,
    /** Navigate to the "Organize PDF pages" tool (reorder + delete combined). */
    val toOrganizePagesScreen: () -> Unit,
    /** Navigate to the QR generator tool. */
    val toQrGeneratorScreen: () -> Unit,
    /** Navigate to statistics screen. */
    val toStatsScreen: () -> Unit,
    val back: () -> Unit,
    val shouldDisplayBackButton: () -> Boolean,
)

@ConsistentCopyVisibility
data class NavigationState private constructor(val stack: List<Screen>, val root: Screen.Main) {

    companion object {
        fun initial(): NavigationState {
            val root = Screen.Main.Camera
            return NavigationState(listOf(root), root)
        }
    }

    val current: Screen get() = stack.last()

    fun navigateTo(destination: Screen): NavigationState {
        return if (destination is Screen.Overlay) {
            copy(stack = stack + destination)
        } else {
            copy(stack = listOf(destination))
        }
    }

    /** Replaces the current top screen without growing the back stack (e.g. switching tools). */
    fun navigateToReplacingCurrent(destination: Screen): NavigationState {
        return copy(stack = stack.dropLast(1) + destination)
    }

    fun navigateBack(): NavigationState {
        return when (current) {
            root -> this // Back handled by system
            is Screen.Main.ResumeScan -> this // Back handled by system
            is Screen.Main.Camera -> this // Back handled by system
            is Screen.Main.Document -> copy(stack = listOf(Screen.Main.Camera))
            is Screen.Main.EditImage -> copy(stack = listOf(Screen.Main.Document()))
            is Screen.Main.Export -> copy(stack = listOf(Screen.Main.Camera))
            is Screen.Overlay -> copy(stack = stack.dropLast(1))
        }
    }
}