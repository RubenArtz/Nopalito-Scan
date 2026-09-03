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

package nopalito.app.ui.screens.tools.passwordprotect

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import nopalito.app.platform.crypto.DocxEncryptor
import java.io.File

/**
 * Real file protection logic.
 *
 * - PDF: encrypted with PDFBox (StandardProtectionPolicy, 128-bit), exactly
 *   like the compressor protects its outputs.
 * - Word: encrypted with [DocxEncryptor] (CFB/OLE2 with ECMA-376 agile
 *   encryption, compatible with Microsoft Word).
 *
 * This layer is independent of Android (it receives local [File]s), so it can
 * be tested with pure JVM, like `CompressionEngine` in the compressor.
 */
object PasswordProtectEngine {

    /**
     * Protects [input] with [password], writing the result to [output].
     * @throws com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
     *   when the input PDF is already encrypted and cannot be opened.
     * @throws nopalito.app.platform.crypto.CryptoException
     *   when the input Word file is not a valid ZIP/OOXML (e.g. it is already
     *   an encrypted CFB).
     */
    fun protect(fileType: ProtectedFileType, input: File, password: String, output: File) {
        when (fileType) {
            ProtectedFileType.PDF -> protectPdf(input, password, output)
            ProtectedFileType.WORD -> DocxEncryptor.encrypt(input, password, output)
        }
    }

    /** Protects [input] into a temp file with the proper output extension. */
    fun protectToTemp(fileType: ProtectedFileType, input: File, password: String): File {
        val ext = when (fileType) {
            ProtectedFileType.PDF -> "pdf"
            ProtectedFileType.WORD -> "docx"
        }
        val output = File(input.parentFile, "protected_${System.currentTimeMillis()}.$ext")
        protect(fileType, input, password, output)
        return output
    }

    private fun protectPdf(input: File, password: String, output: File) {
        PDDocument.load(input).use { doc ->
            val policy = StandardProtectionPolicy(password, password, AccessPermission())
            policy.encryptionKeyLength = 128
            doc.protect(policy)
            doc.save(output)
        }
    }
}