package fr.brume

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/** Base SQLite locale : les blocs de cellules et les villes. */
class Store(ctx: Context) : SQLiteOpenHelper(ctx, "brume.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE chunks(k INTEGER PRIMARY KEY, b BLOB NOT NULL)")
        db.execSQL("CREATE TABLE cities(id TEXT PRIMARY KEY, json TEXT NOT NULL, n INTEGER NOT NULL)")
        createDistricts(db)
    }

    private fun createDistricts(db: SQLiteDatabase) {
        // json vide + id « none:<commune> » = commune vérifiée, sans découpage
        db.execSQL("CREATE TABLE IF NOT EXISTS districts(id TEXT PRIMARY KEY, city TEXT NOT NULL, json TEXT NOT NULL, n INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createDistricts(db)
    }

    fun load(chunks: MutableMap<Long, ByteArray>, cities: MutableMap<String, City>, districts: MutableMap<String, List<City>>) {
        val db = readableDatabase
        db.rawQuery("SELECT k, b FROM chunks", null).use { c ->
            while (c.moveToNext()) chunks[c.getLong(0)] = c.getBlob(1)
        }
        db.rawQuery("SELECT json, n FROM cities", null).use { c ->
            while (c.moveToNext()) {
                try {
                    val city = City.fromJson(JSONObject(c.getString(0)))
                    city.n = c.getLong(1)
                    cities[city.id] = city
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
        val tmp = HashMap<String, ArrayList<City>>()
        db.rawQuery("SELECT city, json, n FROM districts", null).use { c ->
            while (c.moveToNext()) {
                val list = tmp.getOrPut(c.getString(0)) { ArrayList() }
                val js = c.getString(1)
                if (js.isEmpty()) continue
                try { val d = City.fromJson(JSONObject(js)); d.n = c.getLong(2); list.add(d) } catch (e: Exception) { e.printStackTrace() }
            }
        }
        for ((k, v) in tmp) districts[k] = v
    }

    fun save(chunks: List<Pair<Long, ByteArray>>, newCities: List<City>, counts: List<Pair<String, Long>>,
             newDistricts: List<Pair<String, List<City>>>, dCounts: List<Pair<String, Long>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((k, b) in chunks) {
                val v = ContentValues()
                v.put("k", k); v.put("b", b)
                db.insertWithOnConflict("chunks", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            }
            for (c in newCities) {
                val v = ContentValues()
                v.put("id", c.id); v.put("json", c.toJson().toString()); v.put("n", c.n)
                db.insertWithOnConflict("cities", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            }
            for ((id, n) in counts) {
                val v = ContentValues()
                v.put("n", n)
                db.update("cities", v, "id = ?", arrayOf(id))
            }
            for ((city, list) in newDistricts) {
                db.delete("districts", "city = ?", arrayOf(city))
                if (list.isEmpty()) {
                    val v = ContentValues()
                    v.put("id", "none:$city"); v.put("city", city); v.put("json", ""); v.put("n", 0L)
                    db.insertWithOnConflict("districts", null, v, SQLiteDatabase.CONFLICT_REPLACE)
                }
                for (d in list) {
                    val v = ContentValues()
                    v.put("id", d.id); v.put("city", city); v.put("json", d.toJson().toString()); v.put("n", d.n)
                    db.insertWithOnConflict("districts", null, v, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            for ((id, n) in dCounts) {
                val v = ContentValues()
                v.put("n", n)
                db.update("districts", v, "id = ?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clearAll() {
        val db = writableDatabase
        db.execSQL("DELETE FROM chunks")
        db.execSQL("DELETE FROM cities")
        db.execSQL("DELETE FROM districts")
    }
}
