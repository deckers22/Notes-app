package com.deckers22.notes.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Note::class], version = 2, exportSchema = false)
abstract class NotesDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao

    companion object {
        /** Version 2 adds the checklist flag. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN checklist INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun create(context: Context): NotesDatabase =
            Room.databaseBuilder(context, NotesDatabase::class.java, "notes.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
