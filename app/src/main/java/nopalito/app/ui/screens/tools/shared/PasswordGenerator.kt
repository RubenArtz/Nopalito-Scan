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

package nopalito.app.ui.screens.tools.shared

import java.security.SecureRandom

/**
 * Strong password generator shared by tool features.
 *
 * Technical debt note: ExportViewModel and ToolsViewModel keep a private copy
 * of the same logic. This object is the source of truth for new tools;
 * migrating the existing ViewModels to this object is a future step with no
 * behavioral change.
 */
object PasswordGenerator {

    private const val CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#\$%^&*"
    private val random = SecureRandom()

    /** Generates a strong [length]-char password (upper/lowercase, digits and symbols). */
    fun generate(length: Int = 16): String = buildString {
        repeat(length) { append(CHARS[random.nextInt(CHARS.length)]) }
    }
}