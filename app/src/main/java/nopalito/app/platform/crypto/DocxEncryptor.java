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

/**
 * Encrypts a .docx with a password using agile ECMA-376 Part 2 §3.3.4.13 encryption
 * (MS-OFFCRYPTO). The result is a Compound File Binary (CFB/OLE2) that Word opens
 * asking for the password, with the EncryptionInfo and EncryptedPackage streams.
 * <p>
 * Flow: package in 4096-byte AES-256-CBC segments with a per-segment IV
 * (SHA512(keyDataSalt ‖ LE32(i))[:16]), HMAC-SHA512 integrity, and password key
 * derivation with the constant blockKeys.
 */
public final class DocxEncryptor {
    private DocxEncryptor() {
    }

    /**
     * Encrypts [docx] with [password] and writes the resulting CFB to [output].
     */
    public static void encrypt(File docx, String password, File output) throws IOException {
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Password cannot be empty");
        }
        byte[] docxBytes;
        try (InputStream in = new FileInputStream(docx)) {
            docxBytes = CryptoUtil.readAll(in);
        }
        byte[] cfb = encryptBytes(docxBytes, password);
        try (OutputStream out = new FileOutputStream(output)) {
            out.write(cfb);
        }
    }

    public static byte[] encryptBytes(byte[] docxBytes, String password) {
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Password cannot be empty");
        }
        // Input validation at the trust boundary: must be a ZIP/OOXML.
        if (docxBytes.length < 4
                || docxBytes[0] != 'P' || docxBytes[1] != 'K'
                || (docxBytes[2] & 0xFF) != 0x03 || (docxBytes[3] & 0xFF) != 0x04) {
            throw new CryptoException("Input is not a ZIP/OOXML file (missing PK signature)");
        }
        try {
            AgileEncryptionInfo info = new AgileEncryptionInfo();
            info.passwordSalt = CryptoUtil.random(AgileEncryptionInfo.SALT_SIZE);
            info.keyDataSalt = CryptoUtil.random(AgileEncryptionInfo.SALT_SIZE);

            byte[] h = AgileKeyDerivation.deriveIteratedHash(password, info.passwordSalt, info.spinCount);
            byte[] k1 = AgileKeyDerivation.deriveKey(h, AgileEncryptionInfo.BLOCK_KEY_VERIFIER_HASH_INPUT);
            byte[] k2 = AgileKeyDerivation.deriveKey(h, AgileEncryptionInfo.BLOCK_KEY_VERIFIER_HASH_VALUE);
            byte[] k3 = AgileKeyDerivation.deriveKey(h, AgileEncryptionInfo.BLOCK_KEY_ENCRYPTED_KEY_VALUE);

            // Password verifier (validates the key when opening in Word).
            byte[] verifierInput = CryptoUtil.random(AgileEncryptionInfo.SALT_SIZE);
            byte[] verifierHash = CryptoUtil.sha512(verifierInput);
            info.encryptedVerifierHashInput = CryptoUtil.aesCbcEncrypt(k1, info.passwordSalt, verifierInput);
            info.encryptedVerifierHashValue = CryptoUtil.aesCbcEncrypt(k2, info.passwordSalt, verifierHash);

            // Document key (AES-256) that encrypts the package.
            byte[] secretKey = CryptoUtil.random(32);
            info.encryptedKeyValue = CryptoUtil.aesCbcEncrypt(k3, info.passwordSalt, secretKey);

            // Encrypted package: [plain LE64 len][4096-byte AES-256-CBC segments].
            byte[] encryptedPackage = encryptPayload(docxBytes, secretKey, info.keyDataSalt);

            // HMAC-SHA512 integrity (key and value encrypted with the document key).
            byte[] hmacKey = CryptoUtil.random(AgileEncryptionInfo.HASH_SIZE);
            byte[] hmacValue = CryptoUtil.hmacSha512(hmacKey, encryptedPackage);
            byte[] iv1 = AgileKeyDerivation.deriveIv(
                    info.keyDataSalt, AgileEncryptionInfo.BLOCK_KEY_DATA_INTEGRITY_1);
            byte[] iv2 = AgileKeyDerivation.deriveIv(
                    info.keyDataSalt, AgileEncryptionInfo.BLOCK_KEY_DATA_INTEGRITY_2);
            info.encryptedHmacKey = CryptoUtil.aesCbcEncrypt(secretKey, iv1, hmacKey);
            info.encryptedHmacValue = CryptoUtil.aesCbcEncrypt(secretKey, iv2, hmacValue);

            byte[] encryptionInfo = info.toEncryptionInfoBytes();
            return CfbWriter.write(encryptedPackage, encryptionInfo);
        } catch (OutOfMemoryError e) {
            throw new CryptoException("Not enough memory to encrypt the document", e);
        }
    }

    /**
     * Encrypts [plain] in independent 4096-byte segments. The 8-byte prefix
     * (original length in little-endian) is written in clear; the last segment is
     * zero-padded to a multiple of 16.
     */
    static byte[] encryptPayload(byte[] plain, byte[] key, byte[] salt) {
        byte[] out = new byte[8 + pad16(plain.length)];
        System.arraycopy(CryptoUtil.le64(plain.length), 0, out, 0, 8);
        int p = 8;
        int segment = 0;
        for (int off = 0; off < plain.length; off += 4096) {
            int len = Math.min(4096, plain.length - off);
            byte[] chunk = new byte[pad16(len)];
            System.arraycopy(plain, off, chunk, 0, len);
            byte[] iv = AgileKeyDerivation.deriveIv(salt, CryptoUtil.le32(segment));
            byte[] enc = CryptoUtil.aesCbcEncrypt(key, iv, chunk);
            System.arraycopy(enc, 0, out, p, enc.length);
            p += enc.length;
            segment++;
        }
        return out;
    }

    private static int pad16(int len) {
        return (len + 15) / 16 * 16;
    }
}
