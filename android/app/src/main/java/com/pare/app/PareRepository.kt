package com.pare.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import java.io.ByteArrayOutputStream

/** Bounded rows avoid CursorWindow's per-row limit; every chunk commits atomically. */
class PareRepository(context:Context):SQLiteOpenHelper(context,"pare.db",null,2) {
    override fun onCreate(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE snapshot_chunks (id INTEGER PRIMARY KEY, content BLOB NOT NULL)")
        writeTo(db,SyncState())
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) {
        require(oldVersion==1 && newVersion==2)
        db.execSQL("CREATE TABLE snapshot_chunks (id INTEGER PRIMARY KEY, content BLOB NOT NULL)")
        // Slice inside SQLite so even an oversized v1 TEXT never enters a CursorWindow.
        val size=db.compileStatement("SELECT length(content) FROM snapshot WHERE id = 1").simpleQueryForLong()
        var start=1L;var index=0
        while(start<=size) {
            db.execSQL("INSERT INTO snapshot_chunks (id,content) SELECT ?,CAST(substr(content,?,32768) AS BLOB) FROM snapshot WHERE id=1",arrayOf<Any>(index++,start))
            start+=32768
        }
        db.execSQL("DROP TABLE snapshot")
    }
    @Synchronized fun read():SyncState = readFrom(readableDatabase)
    private fun readFrom(db:SQLiteDatabase):SyncState {
        val bytes=ByteArrayOutputStream()
        db.rawQuery("SELECT id,content FROM snapshot_chunks ORDER BY id",null).use {cursor ->
            var expected=0
            while(cursor.moveToNext()) {check(cursor.getInt(0)==expected++);bytes.write(cursor.getBlob(1))}
            check(expected>0) {"本地数据库快照缺失"}
        }
        return wire.decodeFromString(bytes.toString(Charsets.UTF_8.name()))
    }
    private fun writeTo(db:SQLiteDatabase,s:SyncState) {
        val data=wire.encodeToString(s).toByteArray(Charsets.UTF_8)
        db.delete("snapshot_chunks",null,null)
        var index=0
        for(start in data.indices step 65536) {
            val value=ContentValues().apply {put("id",index++);put("content",data.copyOfRange(start,minOf(start+65536,data.size)))}
            db.insertOrThrow("snapshot_chunks",null,value)
        }
    }
    @Synchronized fun update(transform:(SyncState)->SyncState):SyncState {
        val db=writableDatabase
        db.beginTransaction()
        try {
            val next=transform(readFrom(db));writeTo(db,next);db.setTransactionSuccessful();return next
        } finally {db.endTransaction()}
    }
}
