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

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

/**
 * Round-trip del cifrado ágil: encrypt → decrypt debe recuperar el ZIP byte a byte.
 */
public class DocxEncryptorTest {

    static byte[] sampleDocx() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(bos)) {
                zos.putNextEntry(new ZipEntry("[Content_Types].xml"));
                zos.write("<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>".getBytes());
                zos.closeEntry();
                zos.putNextEntry(new ZipEntry("word/document.xml"));
                zos.write("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p/></w:body></w:document>".getBytes());
                zos.closeEntry();
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    public void roundTrip_recoversExactZip() {
        byte[] zip = sampleDocx();
        byte[] cfb = DocxEncryptor.encryptBytes(zip, "Test1234");
        byte[] recovered = DocxDecryptor.decryptBytes(cfb, "Test1234");
        assertArrayEquals(zip, recovered);
    }

    @Test
    public void wrongPassword_throws() {
        byte[] zip = sampleDocx();
        byte[] cfb = DocxEncryptor.encryptBytes(zip, "Test1234");
        try {
            DocxDecryptor.decryptBytes(cfb, "wrong");
            fail("Expected CryptoException for wrong password");
        } catch (CryptoException expected) {
            // correcto
        }
    }

    @Test
    public void output_isCfbContainer() {
        byte[] cfb = DocxEncryptor.encryptBytes(sampleDocx(), "Test1234");
        byte[] magic = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        for (int i = 0; i < 8; i++) assertEquals(magic[i], cfb[i]);
    }

    @Test
    public void emptyPassword_rejected() {
        try {
            DocxEncryptor.encryptBytes(sampleDocx(), "");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // correcto
        }
    }
}
