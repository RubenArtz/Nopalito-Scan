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

package nopalito.app.platform.crypto;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.InputStream;

public class WordEncryptedOracleTest {

    @Test
    public void decryptOracleWordFile_producesValidZip() throws Exception {
        byte[] cfb;
        try (InputStream in = getClass().getResourceAsStream("/test_word_encrypted.docx")) {
            assertNotNull("oracle file not found in test resources", in);
            cfb = CryptoUtil.readAll(in);
        }
        byte[] recovered = DocxDecryptor.decryptBytes(cfb, "Test1234");
        assertTrue("recovered content must start with PK\\x03\\x04",
                recovered.length >= 4
                        && recovered[0] == 'P' && recovered[1] == 'K'
                        && (recovered[2] & 0xFF) == 0x03 && (recovered[3] & 0xFF) == 0x04);
    }
}