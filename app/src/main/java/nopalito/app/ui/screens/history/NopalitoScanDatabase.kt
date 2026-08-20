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

package nopalito.app.ui.screens.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import nopalito.app.ui.screens.qr.QrScanDao
import nopalito.app.ui.screens.qr.QrScanEntity

@Database(
    entities = [ExportHistoryEntity::class, QrScanEntity::class],
    version = 8,
    exportSchema = false
)
abstract class NopalitoScanDatabase : RoomDatabase() {
    abstract fun exportHistoryDao(): ExportHistoryDao
    abstract fun qrScanDao(): QrScanDao

    companion object {
        @Volatile
        private var INSTANCE: NopalitoScanDatabase? = null

        /**
         * v2 -> v3: columns for multiple exports (container folder)
         * and cloud grouping by exportId.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE export_history ADD COLUMN resultType TEXT NOT NULL DEFAULT 'FILE'"
                )
                db.execSQL("ALTER TABLE export_history ADD COLUMN exportedFolderUri TEXT")
                db.execSQL(
                    "ALTER TABLE export_history ADD COLUMN exportedItemCount INTEGER NOT NULL DEFAULT 1"
                )
                db.execSQL("ALTER TABLE export_history ADD COLUMN childrenUris TEXT")
                db.execSQL("ALTER TABLE export_history ADD COLUMN exportId TEXT")
            }
        }

        /**
         * v3 -> v4: app-private backup copies of the exported file/folder so
         * the history can preview/restore them even after the file is deleted
         * from Downloads.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE export_history ADD COLUMN backupPath TEXT")
                db.execSQL("ALTER TABLE export_history ADD COLUMN backupDirPath TEXT")
            }
        }

        /** v4 -> v5: QR/barcode scan history. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS qr_scans (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "content TEXT NOT NULL, " +
                            "dateTime INTEGER NOT NULL, " +
                            "format TEXT, " +
                            "imagePath TEXT)"
                )
            }
        }

        /** v5 -> v6: serialized parsed type of each QR scan (for interactive history). */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE qr_scans ADD COLUMN typeData TEXT")
            }
        }

        /** v6 -> v7: generation recipe so generated QRs can be re-downloaded in any format. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE qr_scans ADD COLUMN designJson TEXT")
            }
        }

        /** v7 -> v8: cloud-sync flag so a generated QR is pushed to the cloud only once. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE qr_scans ADD COLUMN cloudSynced INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getDatabase(context: Context): NopalitoScanDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NopalitoScanDatabase::class.java,
                    "nopalitoscan_database"
                )
                    .addMigrations(
                        MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                        MIGRATION_6_7, MIGRATION_7_8
                    )
                    .fallbackToDestructiveMigration(false)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}