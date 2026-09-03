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

package nopalito.app.screens.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.tom_roush.pdfbox.cos.COSArray;
import com.tom_roush.pdfbox.cos.COSBase;
import com.tom_roush.pdfbox.cos.COSDictionary;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.cos.COSObject;
import com.tom_roush.pdfbox.cos.COSStream;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission;
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy;

import org.junit.Test;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * Reproduce el flujo compressPdf con contraseña: crear → recompresar → proteger → guardar → reabrir.
 */
public class PdfProtectSmokeTest {

    private static final Set<COSName> COMPRESSED_FILTERS = new HashSet<>() {{
        add(COSName.FLATE_DECODE);
        add(COSName.LZW_DECODE);
        add(COSName.DCT_DECODE);
        add(COSName.CCITTFAX_DECODE);
        add(COSName.JBIG2_DECODE);
        add(COSName.JPX_DECODE);
    }};

    private static void recompressUncompressedStreams(COSBase root, Set<COSObject> visited) {
        if (root instanceof COSStream) {
            COSStream s = (COSStream) root;
            try {
                java.util.List<COSName> names = new java.util.ArrayList<>();
                COSBase filters = s.getFilters();
                if (filters instanceof COSName) {
                    names.add((COSName) filters);
                } else if (filters instanceof COSArray) {
                    for (COSBase f : (COSArray) filters) {
                        if (f instanceof COSName) names.add((COSName) f);
                    }
                }
                boolean compressed = false;
                for (COSName f : names) {
                    if (COMPRESSED_FILTERS.contains(f)) {
                        compressed = true;
                        break;
                    }
                }
                if (!compressed) {
                    byte[] data;
                    try (java.io.InputStream in = s.createInputStream()) {
                        data = readAll(in);
                    }
                    s.createOutputStream(COSName.FLATE_DECODE);
                    try (java.io.OutputStream os = s.createOutputStream()) {
                        os.write(data);
                    }
                }
            } catch (Exception ignored) {
            }
        } else if (root instanceof COSObject) {
            if (visited.add((COSObject) root)) {
                recompressUncompressedStreams(((COSObject) root).getObject(), visited);
            }
        } else if (root instanceof COSArray) {
            for (COSBase b : (COSArray) root) {
                recompressUncompressedStreams(b, visited);
            }
        } else if (root instanceof COSDictionary) {
            for (COSBase b : ((COSDictionary) root).getValues()) {
                recompressUncompressedStreams(b, visited);
            }
        }
    }

    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @Test
    public void fullCompressPdfFlow_withPassword() throws Exception {
        File in = File.createTempFile("pdf_in", ".pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.A4));
            doc.save(in);
        }

        File out = File.createTempFile("pdf_out", ".pdf");
        try (PDDocument doc = PDDocument.load(in, "")) {
            recompressUncompressedStreams(doc.getDocumentCatalog().getCOSObject(), new HashSet<>());
            StandardProtectionPolicy policy = new StandardProtectionPolicy("pass", "pass", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            doc.save(out);
        }

        try (PDDocument doc = PDDocument.load(out, "pass")) {
            assertEquals(1, doc.getNumberOfPages());
            assertTrue(doc.isEncrypted());
        }
        in.delete();
        out.delete();
    }
}
