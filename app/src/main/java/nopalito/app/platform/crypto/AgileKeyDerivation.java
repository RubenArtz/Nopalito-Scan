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

/**
 * Key derivation of the agile ECMA-376 Part 2 §3.3.4.13 (MS-OFFCRYPTO) encryption.
 * <p>
 * Algorithm verified against the reference implementation that decrypts real Word
 * files (msoffcrypto-tool, ecma376_agile.py):
 * H0      = SHA512(salt || password_utf16le)
 * H(i+1)  = SHA512(LE32(i) || H(i))            (spinCount iterations)
 * key     = SHA512(Hn || blockKey)[0:keyBytes]
 * iv      = SHA512(salt || blockKey)[0:ivBytes]  (package segments and integrity)
 * <p>
 * NOTE: the real implementation does NOT apply 0x36 padding in the key derivation
 * (it only appears in the normalization of IVs shorter than blockSize, which never
 * happens with SHA-512). It is deliberately omitted so Word accepts the file.
 */
final class AgileKeyDerivation {
    private AgileKeyDerivation() {
    }

    /**
     * Iterated hash: first round SHA512(salt || password_utf16le), then SHA512(LE32(i) || h).
     */
    static byte[] deriveIteratedHash(String password, byte[] salt, int spinCount) {
        byte[] h = CryptoUtil.sha512(salt, CryptoUtil.utf16le(password));
        for (int i = 0; i < spinCount; i++) {
            h = CryptoUtil.sha512(CryptoUtil.le32(i), h);
        }
        return h;
    }

    /**
     * Final key = SHA512(h || blockKey)[0:keyBytes].
     */
    static byte[] deriveKey(byte[] iteratedHash, byte[] blockKey) {
        return CryptoUtil.first(CryptoUtil.sha512(iteratedHash, blockKey), 32);
    }

    /**
     * IV per blockKey = SHA512(salt || blockKey)[0:ivBytes].
     */
    static byte[] deriveIv(byte[] salt, byte[] blockKey) {
        return CryptoUtil.first(CryptoUtil.sha512(salt, blockKey), 16);
    }
}