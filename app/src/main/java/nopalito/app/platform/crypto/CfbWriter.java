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

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal Compound File Binary (OLE2/CFB) writer for an encrypted OOXML document
 * container. It replicates the structure and the DataSpaces directory tree of the
 * msoffcrypto-tool reference (ecma376_encrypted.py), which is what Word expects:
 * - 512-byte header (magic D0 CF 11 E0 A1 B1 1A E1).
 * - FAT + DIFAT (for large files > ~7 MB) + mini-FAT + mini stream.
 * - 11 directory entries: Root, EncryptedPackage, \x06DataSpaces (with its
 * Version/DataSpaceMap/... streams), and EncryptionInfo.
 */
final class CfbWriter {
    static final int SECTOR_SIZE = 512;
    static final int MINI_SECTOR_SIZE = 64;
    static final int MINI_CUTOFF = 4096;
    static final int FIRST_DIFAT = 109;
    static final int FATSECT = 0xFFFFFFFD;
    static final int DIFSECT = 0xFFFFFFFC;
    static final int ENDOFCHAIN = 0xFFFFFFFE;
    static final int FREESECT = 0xFFFFFFFF;
    static final int NOSTREAM = 0xFFFFFFFF;
    static final byte[] MAGIC = {
            (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
    // Directory indices (same order as the reference).
    static final int I_ROOT = 0, I_ENCRYPTED_PACKAGE = 1, I_DATA_SPACES = 2, I_VERSION = 3,
            I_DATA_SPACE_MAP = 4, I_DATA_SPACE_INFO = 5, I_STRONG_ENC_DATA_SPACE = 6,
            I_TRANSFORM_INFO = 7, I_STRONG_ENC_TRANSFORM = 8, I_PRIMARY = 9,
            I_ENCRYPTION_INFO = 10;
    static final int DIR_COUNT = 11;
    static final int TYPE_STORAGE = 1, TYPE_STREAM = 2, TYPE_ROOT = 5;
    static final int COLOR_RED = 0, COLOR_BLACK = 1;

    private CfbWriter() {
    }

    static byte[] write(byte[] encryptedPackage, byte[] encryptionInfo) {
        DirEntry[] dirs = buildDirectories(encryptedPackage, encryptionInfo);
        Layout layout = new Layout();
        setSectorLocations(dirs, layout);
        detectSectorNum(layout);

        dirs[I_ROOT].startSector = layout.miniFatDataPos();
        dirs[I_ROOT].content = new byte[MINI_SECTOR_SIZE * layout.miniFatNum];
        dirs[I_ENCRYPTED_PACKAGE].startSector = layout.encryptionPackagePos();

        byte[] out = new byte[SECTOR_SIZE + layout.totalSectors() * SECTOR_SIZE];
        writeHeader(out, layout);
        writeDifat(out, layout);
        writeFat(out, layout);
        writeMiniFat(out, layout);
        writeDirectory(out, dirs, layout);
        writeContent(out, dirs, layout);
        return out;
    }

    // ── Directory ──────────────────────────────────────────────────────────────

    private static DirEntry[] buildDirectories(byte[] pkg, byte[] encInfo) {
        DirEntry[] d = new DirEntry[DIR_COUNT];
        d[I_ROOT] = new DirEntry("Root Entry", TYPE_ROOT, COLOR_RED, NOSTREAM, NOSTREAM, I_ENCRYPTION_INFO, null);
        d[I_ENCRYPTED_PACKAGE] = new DirEntry("EncryptedPackage", TYPE_STREAM, COLOR_RED, NOSTREAM, NOSTREAM, NOSTREAM, pkg);
        d[I_DATA_SPACES] = new DirEntry("\u0006DataSpaces", TYPE_STORAGE, COLOR_RED, NOSTREAM, NOSTREAM, I_DATA_SPACE_MAP, null);
        d[I_VERSION] = new DirEntry("Version", TYPE_STREAM, COLOR_BLACK, NOSTREAM, NOSTREAM, NOSTREAM, defaultVersion());
        d[I_DATA_SPACE_MAP] = new DirEntry("DataSpaceMap", TYPE_STREAM, COLOR_BLACK, I_VERSION, I_DATA_SPACE_INFO, NOSTREAM, defaultDataSpaceMap());
        d[I_DATA_SPACE_INFO] = new DirEntry("DataSpaceInfo", TYPE_STORAGE, COLOR_BLACK, NOSTREAM, I_TRANSFORM_INFO, I_STRONG_ENC_DATA_SPACE, null);
        d[I_STRONG_ENC_DATA_SPACE] = new DirEntry("StrongEncryptionDataSpace", TYPE_STREAM, COLOR_BLACK, NOSTREAM, NOSTREAM, NOSTREAM, defaultStrongEncryptionDataSpace());
        d[I_TRANSFORM_INFO] = new DirEntry("TransformInfo", TYPE_STORAGE, COLOR_RED, NOSTREAM, NOSTREAM, I_STRONG_ENC_TRANSFORM, null);
        d[I_STRONG_ENC_TRANSFORM] = new DirEntry("StrongEncryptionTransform", TYPE_STORAGE, COLOR_BLACK, NOSTREAM, NOSTREAM, I_PRIMARY, null);
        d[I_PRIMARY] = new DirEntry("\u0006Primary", TYPE_STREAM, COLOR_BLACK, NOSTREAM, NOSTREAM, NOSTREAM, defaultPrimary());
        d[I_ENCRYPTION_INFO] = new DirEntry("EncryptionInfo", TYPE_STREAM, COLOR_BLACK, I_DATA_SPACES, I_ENCRYPTED_PACKAGE, NOSTREAM, encInfo);
        return d;
    }

    /**
     * Fixed blobs of the DataSpaces tree (replicated from the reference; verified lengths).
     */
    private static byte[] defaultVersion() {
        byte[] name = CryptoUtil.utf16le("Microsoft.Container.DataSpaces");
        return CryptoUtil.concat(CryptoUtil.le32(name.length), name,
                CryptoUtil.le32(1), CryptoUtil.le32(1), CryptoUtil.le32(1));
    }

    private static byte[] defaultDataSpaceMap() {
        byte[] pkg = CryptoUtil.utf16le("EncryptedPackage");
        byte[] ds = CryptoUtil.utf16le("StrongEncryptionDataSpace");
        return CryptoUtil.concat(
                CryptoUtil.le32(8), CryptoUtil.le32(1), CryptoUtil.le32(104),
                CryptoUtil.le32(1), CryptoUtil.le32(0), CryptoUtil.le32(pkg.length), pkg,
                CryptoUtil.le32(ds.length), ds, new byte[]{0, 0});
    }

    private static byte[] defaultStrongEncryptionDataSpace() {
        byte[] name = CryptoUtil.utf16le("StrongEncryptionTransform");
        return CryptoUtil.concat(
                CryptoUtil.le32(8), CryptoUtil.le32(1), CryptoUtil.le32(name.length),
                name, new byte[]{0, 0});
    }

    private static byte[] defaultPrimary() {
        byte[] featureId = CryptoUtil.utf16le("{FF9A3F03-56EF-4613-BDD5-5A41C1D07246}");
        byte[] name = CryptoUtil.utf16le("Microsoft.Container.EncryptionTransform");
        return CryptoUtil.concat(
                CryptoUtil.le32(88), CryptoUtil.le32(1), CryptoUtil.le32(featureId.length), featureId,
                CryptoUtil.le32(name.length), name, new byte[]{0, 0},
                CryptoUtil.le32(1), CryptoUtil.le32(1), CryptoUtil.le32(1),
                CryptoUtil.le32(0), CryptoUtil.le32(0), CryptoUtil.le32(0),
                CryptoUtil.le32(4));
    }

    // ── Sector layout ──────────────────────────────────────────────────────

    private static void setSectorLocations(DirEntry[] dirs, Layout layout) {
        int pos = 0;
        for (DirEntry d : dirs) {
            if (d.type == TYPE_STREAM && !d.name.equals("EncryptedPackage")) {
                int n = CryptoUtil.ceilDiv(d.content.length, MINI_SECTOR_SIZE);
                layout.miniFatSectors.add(n);
                d.startSector = pos;
                pos += n;
            }
        }
        layout.miniFatNum = pos;
        layout.miniFatDataSectorNum = CryptoUtil.ceilDiv(layout.miniFatNum, SECTOR_SIZE / MINI_SECTOR_SIZE);
        if (CryptoUtil.ceilDiv(layout.miniFatDataSectorNum, SECTOR_SIZE / 4) > 1) {
            throw new CryptoException("CFB layout too large (mini stream)");
        }
        layout.directoryEntrySectorNum = CryptoUtil.ceilDiv(DIR_COUNT, SECTOR_SIZE / 128);
        layout.encryptionPackageSectorNum = CryptoUtil.ceilDiv(dirs[I_ENCRYPTED_PACKAGE].content.length, SECTOR_SIZE);
    }

    private static void detectSectorNum(Layout layout) {
        int numInFat = SECTOR_SIZE / 4;
        int difat = 0, fat = 0;
        for (int i = 0; i < 10; i++) {
            int a = CryptoUtil.ceilDiv(difat + fat + layout.contentSectorNum(), numInFat);
            int b = (a <= FIRST_DIFAT) ? 0 : CryptoUtil.ceilDiv(a - FIRST_DIFAT, numInFat - 1);
            if (b == difat && a == fat) {
                layout.fatSectorNum = fat;
                layout.difatSectorNum = difat;
                return;
            }
            difat = b;
            fat = a;
        }
        throw new CryptoException("Cannot determine CFB sector counts");
    }

    private static void writeHeader(byte[] out, Layout layout) {
        System.arraycopy(MAGIC, 0, out, 0, 8);
        put16(out, 24, 0x003E);   // minor version
        put16(out, 26, 3);        // major version
        put16(out, 28, 0xFFFE);   // byte order little-endian
        put16(out, 30, 9);        // sector shift
        put16(out, 32, 6);        // mini sector shift
        put32(out, 40, 0);                            // nº directory sectors (v3 = 0)
        put32(out, 44, layout.fatSectorNum);
        put32(out, 48, layout.directoryEntryPos());
        put32(out, 52, 0);                            // transaction signature
        put32(out, 56, MINI_CUTOFF);                  // 4096
        put32(out, 60, layout.miniFatPos());
        put32(out, 64, layout.numMiniFatSectors);
        put32(out, 68, layout.difatSectorNum > 0 ? layout.difatPos() : ENDOFCHAIN);
        put32(out, 72, layout.difatSectorNum);
        int p = 76;
        int n = Math.min(layout.fatSectorNum, FIRST_DIFAT);
        for (int i = 0; i < n; i++) {
            put32(out, p, layout.fatPos() + i);
            p += 4;
        }
        for (int i = n; i < FIRST_DIFAT; i++) {
            put32(out, p, NOSTREAM);
            p += 4;
        }
    }

    // ── Writing ───────────────────────────────────────────────────────────────

    private static void writeDifat(byte[] out, Layout layout) {
        if (layout.difatSectorNum < 1) return;
        int v = FIRST_DIFAT + layout.difatSectorNum;
        for (int i = 0; i < layout.difatSectorNum; i++) {
            int off = SECTOR_SIZE + (layout.difatPos() + i) * SECTOR_SIZE;
            int p = off;
            for (int j = 0; j < SECTOR_SIZE / 4 - 1; j++) {
                put32(out, p, v);
                p += 4;
                v += 1;
                if (v > layout.difatSectorNum + layout.fatSectorNum) {
                    while (p < off + SECTOR_SIZE - 4) {
                        put32(out, p, FREESECT);
                        p += 4;
                    }
                    put32(out, p, ENDOFCHAIN);
                    return;
                }
            }
            p = off + SECTOR_SIZE - 4;
            put32(out, p, layout.difatPos() + i + 1);
        }
    }

    private static void writeFat(byte[] out, Layout layout) {
        List<Integer> entries = new ArrayList<>();
        for (int i = 0; i < layout.difatSectorNum; i++) entries.add(DIFSECT);
        for (int i = 0; i < layout.fatSectorNum; i++) entries.add(FATSECT);
        entries.add(layout.numMiniFatSectors);
        entries.add(layout.directoryEntrySectorNum);
        entries.add(layout.miniFatDataSectorNum);
        entries.add(layout.encryptionPackageSectorNum);
        int off = SECTOR_SIZE + layout.fatPos() * SECTOR_SIZE;
        writeFatEntries(out, off, entries, layout.fatSectorNum * SECTOR_SIZE);
    }

    private static void writeMiniFat(byte[] out, Layout layout) {
        int off = SECTOR_SIZE + layout.miniFatPos() * SECTOR_SIZE;
        writeFatEntries(out, off, layout.miniFatSectors, layout.numMiniFatSectors * SECTOR_SIZE);
    }

    /**
     * Writes sector chains: a positive integer = consecutive chain + ENDOFCHAIN; a special value = literal.
     */
    private static void writeFatEntries(byte[] out, int offset, List<Integer> entries, int blockSize) {
        int v = 0;
        int maxN = blockSize / 4;
        int p = offset;
        for (int e : entries) {
            if (e >= 0) {
                for (int j = 1; j < e; j++) {
                    v += 1;
                    if (v > maxN) throw new CryptoException("FAT overflow");
                    put32(out, p, v);
                    p += 4;
                }
                if (v == maxN) throw new CryptoException("FAT overflow");
                put32(out, p, ENDOFCHAIN);
                p += 4;
            } else {
                if (v == maxN) throw new CryptoException("FAT overflow");
                put32(out, p, e);
                p += 4;
            }
            v += 1;
        }
        while (p < offset + blockSize) {
            put32(out, p, FREESECT);
            p += 4;
        }
    }

    private static void writeDirectory(byte[] out, DirEntry[] dirs, Layout layout) {
        int off = SECTOR_SIZE + layout.directoryEntryPos() * SECTOR_SIZE;
        for (int i = 0; i < dirs.length; i++) {
            dirs[i].writeTo(out, off + i * 128);
        }
    }

    private static void writeContent(byte[] out, DirEntry[] dirs, Layout layout) {
        for (DirEntry d : dirs) {
            if (d.content.length == 0) continue;
            if (d.type == TYPE_ROOT) {
                // The mini stream starts at offset 0 of its data region.
                int off = SECTOR_SIZE + layout.miniFatDataPos() * SECTOR_SIZE;
                System.arraycopy(d.content, 0, out, off, d.content.length);
            } else if (d.type == TYPE_STREAM && !d.name.equals("EncryptedPackage")) {
                // Small streams (other than the package) live in the mini stream.
                int off = SECTOR_SIZE + layout.miniFatDataPos() * SECTOR_SIZE + d.startSector * MINI_SECTOR_SIZE;
                System.arraycopy(d.content, 0, out, off, d.content.length);
            } else {
                // EncryptedPackage always goes in the normal FAT (even if < 4096).
                int off = SECTOR_SIZE + d.startSector * SECTOR_SIZE;
                System.arraycopy(d.content, 0, out, off, d.content.length);
            }
        }
    }

    private static void put16(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
    }

    // ── Directory entry ───────────────────────────────────────────────────

    private static void put32(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    private static void put64(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) b[off + i] = (byte) (v >> (8 * i));
    }

    private static final class Layout {
        int miniFatNum;
        int miniFatDataSectorNum;
        List<Integer> miniFatSectors = new ArrayList<>();
        int numMiniFatSectors = 1;
        int difatSectorNum = 0;
        int fatSectorNum = 0;
        int directoryEntrySectorNum;
        int encryptionPackageSectorNum;

        int difatPos() {
            return 0;
        }

        int fatPos() {
            return difatPos() + difatSectorNum;
        }

        int miniFatPos() {
            return fatPos() + fatSectorNum;
        }

        int directoryEntryPos() {
            return miniFatPos() + numMiniFatSectors;
        }

        int miniFatDataPos() {
            return directoryEntryPos() + directoryEntrySectorNum;
        }

        int encryptionPackagePos() {
            return miniFatDataPos() + miniFatDataSectorNum;
        }

        int contentSectorNum() {
            return numMiniFatSectors + directoryEntrySectorNum + miniFatDataSectorNum + encryptionPackageSectorNum;
        }

        int totalSectors() {
            return difatSectorNum + fatSectorNum + contentSectorNum();
        }
    }

    private static final class DirEntry {
        final String name;
        final int type;
        final int color;
        final int leftId;
        final int rightId;
        final int childId;
        int startSector;
        byte[] content = new byte[0];

        DirEntry(String name, int type, int color, int leftId, int rightId, int childId, byte[] content) {
            this.name = name;
            this.type = type;
            this.color = color;
            this.leftId = leftId;
            this.rightId = rightId;
            this.childId = childId;
            if (content != null) this.content = content;
        }

        void writeTo(byte[] out, int off) {
            byte[] name16 = CryptoUtil.utf16le(name);
            int nameSize = name16.length + 2; // includes the null terminator
            System.arraycopy(name16, 0, out, off, name16.length);
            for (int i = nameSize; i < 64; i++) out[off + i] = 0;
            put16(out, off + 64, nameSize);
            out[off + 66] = (byte) type;
            out[off + 67] = (byte) color;
            put32(out, off + 68, leftId);
            put32(out, off + 72, rightId);
            put32(out, off + 76, childId);
            put32(out, off + 96, 0);      // state bits
            put32(out, off + 116, startSector);
            put64(out, off + 120, content.length);
        }
    }
}
