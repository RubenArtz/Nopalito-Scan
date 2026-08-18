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

package nopalito.app.ui.screens.export

import nopalito.app.ui.screens.history.ExportHistoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pruebas del mapeo persistencia -> ExportArtifact.
 *
 * Nota: se prueban las ramas FILE/FOLDER vía fromHistoryEntity porque sus
 * entradas son Strings (sin depender de android.net.Uri, que no se puede
 * construir en tests JVM puros sin Robolectric). La rama fromBundle usa la
 * misma lógica de ramificación y queda cubierta por pruebas instrumentadas.
 */
class ExportArtifactMapperTest {

    @Test
    fun `fromHistoryEntity maps FOLDER entity with children`() {
        val entity = ExportHistoryEntity(
            documentName = "Export",
            dateTime = 1_700_000_000_000,
            pageCount = 3,
            format = "JPEG",
            quality = "BALANCED",
            fileSizeBytes = 9000,
            resultType = "FOLDER",
            exportedItemCount = 3,
            childrenUris = "content://media/1\ncontent://media/2\ncontent://media/3",
        )

        val artifact = ExportArtifactMapper.fromHistoryEntity(entity)

        assertEquals(ExportArtifactType.FOLDER, artifact?.type)
        assertEquals(ExportFormat.JPEG, artifact?.format)
        assertEquals("Export", artifact?.displayName)
        assertEquals(3, artifact?.itemCount)
        assertEquals(3, artifact?.children?.size)
        // displayName se deriva del string guardado, no de la conversión a Uri
        assertEquals("2", artifact?.children?.get(1)?.displayName)
        assertEquals(ExportArtifactType.FILE, artifact?.children?.get(0)?.type)
    }

    @Test
    fun `fromHistoryEntity maps FILE entity`() {
        val entity = ExportHistoryEntity(
            documentName = "scan.pdf",
            dateTime = 1_700_000_000_000,
            pageCount = 1,
            format = "PDF",
            quality = "BALANCED",
            fileSizeBytes = 500,
            exportedFilePath = "content://media/downloads/scan.pdf",
        )
        val artifact = ExportArtifactMapper.fromHistoryEntity(entity)
        assertEquals(ExportArtifactType.FILE, artifact?.type)
        assertEquals(ExportFormat.PDF, artifact?.format)
        assertEquals("scan.pdf", artifact?.displayName)
        assertEquals(1, artifact?.itemCount)
    }

    @Test
    fun `fromHistoryEntity maps legacy FILE entity without resultType as FILE`() {
        // Entrada pre-migración: resultType por defecto "FILE" y sin destino guardado
        val entity = ExportHistoryEntity(
            documentName = "Legacy",
            dateTime = 1_700_000_000_000,
            pageCount = 1,
            format = "PDF",
            quality = "BALANCED",
            fileSizeBytes = 100,
        )
        // Sin exportedFilePath -> sin artefacto abrible (no rompe la UI)
        assertNull(ExportArtifactMapper.fromHistoryEntity(entity))
    }

    @Test
    fun `fromHistoryEntity maps DOCX format to WORD`() {
        val entity = ExportHistoryEntity(
            documentName = "doc.docx",
            dateTime = 1_700_000_000_000,
            pageCount = 1,
            format = "DOCX",
            quality = "BALANCED",
            fileSizeBytes = 700,
            exportedFilePath = "content://media/downloads/doc.docx",
        )
        val artifact = ExportArtifactMapper.fromHistoryEntity(entity)
        assertEquals(ExportFormat.WORD, artifact?.format)
    }
}
