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

import java.io.*;
import java.util.*;

/**
 * Decrypts a password-protected .docx (agile ECMA-376 CFB). Used by the tests
 * (round-trip and real Word oracle) and reusable to "remove the password".
 */
public final class DocxDecryptor {
    private static final int ENDOFCHAIN = 0xFFFFFFFE;
    private static final int FREESECT = 0xFFFFFFFF;
    private static final int NOSTREAM = 0xFFFFFFFF;

    // ── Minimal CFB reader ────────────────────────────────────────────────────
    private static final byte[] MAGIC = {
            (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

    private DocxDecryptor() {
    }

    public static byte[] decrypt(File encrypted, String password) throws IOException {
        byte[] cfb;
        try (InputStream in = new FileInputStream(encrypted)) {
            cfb = CryptoUtil.readAll(in);
        }
        return decryptBytes(cfb, password);
    }

    public static byte[] decryptBytes(byte[] cfb, String password) {
        Map<String, byte[]> streams = readCfbStreams(cfb);
        byte[] encInfoStream = streams.get("EncryptionInfo");
        byte[] encPackage = streams.get("EncryptedPackage");
        if (encInfoStream == null || encPackage == null) {
            throw new CryptoException("Missing EncryptionInfo/EncryptedPackage stream");
        }

        AgileEncryptionInfo info = AgileEncryptionInfo.parse(encInfoStream);

        byte[] h = AgileKeyDerivation.deriveIteratedHash(password, info.passwordSalt, info.spinCount);
        byte[] k3 = AgileKeyDerivation.deriveKey(h, AgileEncryptionInfo.BLOCK_KEY_ENCRYPTED_KEY_VALUE);
        byte[] secretKey = CryptoUtil.aesCbcDecrypt(k3, info.passwordSalt, info.encryptedKeyValue);

        // Verifies the password with the verifier (fails fast if incorrect).
        byte[] k1 = AgileKeyDerivation.deriveKey(h, AgileEncryptionInfo.BLOCK_KEY_VERIFIER_HASH_INPUT);
        byte[] k2 = AgileKeyDerivation.deriveKey(h, AgileEncryptionInfo.BLOCK_KEY_VERIFIER_HASH_VALUE);
        byte[] verifierInput = CryptoUtil.aesCbcDecrypt(k1, info.passwordSalt, info.encryptedVerifierHashInput);
        byte[] expected = CryptoUtil.aesCbcDecrypt(k2, info.passwordSalt, info.encryptedVerifierHashValue);
        if (!Arrays.equals(expected, CryptoUtil.sha512(verifierInput))) {
            throw new CryptoException("Wrong password");
        }

        long len = CryptoUtil.readLe64(encPackage, 0);
        byte[] plainPadded = decryptPayload(encPackage, secretKey, info.keyDataSalt);
        if (len < 0 || len > plainPadded.length) {
            throw new CryptoException("Corrupt encrypted package");
        }
        return CryptoUtil.first(plainPadded, (int) len);
    }

    /**
     * Decrypts the segments (skipping the plain 8-byte length prefix).
     */
    static byte[] decryptPayload(byte[] enc, byte[] key, byte[] salt) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int segment = 0;
        for (int off = 8; off < enc.length; off += 4096) {
            int len = Math.min(4096, enc.length - off);
            byte[] chunk = CryptoUtil.slice(enc, off, len);
            byte[] iv = AgileKeyDerivation.deriveIv(salt, CryptoUtil.le32(segment));
            byte[] dec = CryptoUtil.aesCbcDecrypt(key, iv, chunk);
            out.write(dec, 0, dec.length);
            segment++;
        }
        return out.toByteArray();
    }

    private static Map<String, byte[]> readCfbStreams(byte[] cfb) {
        if (cfb.length < 512) throw new CryptoException("Not a CFB file");
        for (int i = 0; i < 8; i++) {
            if (cfb[i] != MAGIC[i]) throw new CryptoException("Not a CFB file");
        }
        int sectorSize = 1 << CryptoUtil.readLe16(cfb, 30);
        int miniSectorSize = 1 << CryptoUtil.readLe16(cfb, 32);
        int firstDirSector = CryptoUtil.readLe32(cfb, 48);
        int miniCutoff = CryptoUtil.readLe32(cfb, 56);
        int firstMiniFatSector = CryptoUtil.readLe32(cfb, 60);
        int firstDifatSector = CryptoUtil.readLe32(cfb, 68);
        int numDifatSectors = CryptoUtil.readLe32(cfb, 72);

        int totalSectors = (cfb.length - sectorSize) / sectorSize;

        // FAT (flat array: entry i = sector i). FAT sectors are read in DIFAT order.
        int[] fat = new int[totalSectors];
        Arrays.fill(fat, FREESECT);
        List<Integer> fatSectorList = new ArrayList<>();
        for (int i = 0; i < 109; i++) {
            int s = CryptoUtil.readLe32(cfb, 76 + i * 4);
            if (s == NOSTREAM || s == ENDOFCHAIN) break;
            fatSectorList.add(s);
        }
        int difatSector = firstDifatSector;
        for (int d = 0; d < numDifatSectors && difatSector != ENDOFCHAIN && difatSector != FREESECT; d++) {
            int base = sectorSize + difatSector * sectorSize;
            for (int i = 0; i < sectorSize / 4 - 1; i++) {
                int s = CryptoUtil.readLe32(cfb, base + i * 4);
                if (s == NOSTREAM || s == ENDOFCHAIN) break;
                fatSectorList.add(s);
            }
            difatSector = CryptoUtil.readLe32(cfb, base + sectorSize - 4);
        }
        for (int k = 0; k < fatSectorList.size(); k++) {
            int s = fatSectorList.get(k);
            if (s < 0 || s >= totalSectors) continue;
            int base = sectorSize + s * sectorSize;
            for (int i = 0; i < sectorSize / 4; i++) {
                int idx = k * (sectorSize / 4) + i;
                if (idx < totalSectors) fat[idx] = CryptoUtil.readLe32(cfb, base + i * 4);
            }
        }

        // Directory (chain from firstDirSector).
        byte[] dirBytes = readFatChain(cfb, sectorSize, fat, firstDirSector);
        List<DirRec> entries = new ArrayList<>();
        for (int off = 0; off + 128 <= dirBytes.length; off += 128) {
            int type = dirBytes[off + 66] & 0xFF;
            if (type == 0) break;
            DirRec rec = new DirRec();
            rec.type = type;
            rec.startSector = CryptoUtil.readLe32(dirBytes, off + 116);
            rec.size = CryptoUtil.readLe64(dirBytes, off + 120);
            int nameLen = CryptoUtil.readLe16(dirBytes, off + 64);
            int chars = (nameLen > 2) ? (nameLen - 2) / 2 : 0;
            StringBuilder name = new StringBuilder();
            for (int i = 0; i < chars; i++) {
                name.append((char) ((dirBytes[off + 2 * i] & 0xFF) | ((dirBytes[off + 2 * i + 1] & 0xFF) << 8)));
            }
            rec.name = name.toString();
            entries.add(rec);
        }

        // Mini stream: root data (FAT chain from root.startSector).
        DirRec root = null;
        for (DirRec r : entries) {
            if (r.type == 5) {
                root = r;
                break;
            }
        }
        byte[] miniStream = new byte[0];
        int[] miniFat = new int[0];
        if (root != null && root.size > 0) {
            miniStream = readFatChain(cfb, sectorSize, fat, root.startSector);
            if (miniStream.length > root.size) miniStream = Arrays.copyOf(miniStream, (int) root.size);
            if (firstMiniFatSector != ENDOFCHAIN && firstMiniFatSector != FREESECT) {
                byte[] miniFatBytes = readFatChain(cfb, sectorSize, fat, firstMiniFatSector);
                miniFat = new int[miniFatBytes.length / 4];
                for (int i = 0; i < miniFat.length; i++) {
                    miniFat[i] = CryptoUtil.readLe32(miniFatBytes, i * 4);
                }
            }
        }

        Map<String, byte[]> out = new HashMap<>();
        for (DirRec r : entries) {
            if (r.type != 2) continue;
            byte[] data;
            if (r.name.equals("EncryptedPackage") || r.size >= miniCutoff) {
                data = readFatChain(cfb, sectorSize, fat, r.startSector);
                if (data.length > r.size) data = Arrays.copyOf(data, (int) r.size);
            } else {
                data = readMiniChain(miniStream, miniFat, miniSectorSize, r.startSector, r.size);
            }
            out.put(r.name, data);
        }
        return out;
    }

    private static byte[] readFatChain(byte[] cfb, int sectorSize, int[] fat, int start) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int s = start;
        int guard = 0;
        while (s >= 0 && s < fat.length && guard++ < fat.length + 2) {
            int base = sectorSize + s * sectorSize;
            if (base + sectorSize > cfb.length) break;
            out.write(cfb, base, sectorSize);
            s = fat[s];
        }
        return out.toByteArray();
    }

    private static byte[] readMiniChain(byte[] miniStream, int[] miniFat,
                                        int miniSectorSize, int start, long size) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int s = start;
        int guard = 0;
        while (s >= 0 && s < miniFat.length
                && guard++ < miniFat.length + 2 && out.size() < size) {
            int base = s * miniSectorSize;
            if (base + miniSectorSize > miniStream.length) break;
            int n = (int) Math.min(miniSectorSize, size - out.size());
            out.write(miniStream, base, n);
            s = miniFat[s];
        }
        return out.toByteArray();
    }

    private static final class DirRec {
        String name;
        int type;
        int startSector;
        long size;
    }
}
