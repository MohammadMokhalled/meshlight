package com.example.meshlightcontroller.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.meshlightcontroller.core.model.*
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Entity(tableName = "installations")
data class InstallationRecord(@PrimaryKey val id: String, val name: String, val json: String)

@Entity(tableName = "installation_parts", primaryKeys = ["networkId", "part"],
    foreignKeys = [ForeignKey(entity = InstallationRecord::class, parentColumns = ["id"],
        childColumns = ["networkId"], onDelete = ForeignKey.CASCADE)])
data class InstallationPart(val networkId: String, val part: Int, val json: String)

@Dao
interface InstallationDao {
    // Do not project a legacy full snapshot into one CursorWindow row.
    @Query("SELECT id,name,CASE WHEN json='@parts:1' THEN json ELSE '' END AS json FROM installations")
    fun observe(): Flow<List<InstallationRecord>>
    @Query("SELECT id,name,CASE WHEN json='@parts:1' THEN json ELSE '' END AS json FROM installations WHERE id=:id")
    suspend fun get(id: String): InstallationRecord?
    @Query("SELECT length(json) FROM installations WHERE id=:id")
    suspend fun legacyLength(id: String): Int
    @Query("SELECT substr(json,:start,:length) FROM installations WHERE id=:id")
    suspend fun legacyPart(id: String, start: Int, length: Int): String
    @Query("SELECT * FROM installation_parts WHERE networkId=:id ORDER BY part")
    suspend fun parts(id: String): List<InstallationPart>
    @Upsert suspend fun put(record: InstallationRecord)
    @Upsert suspend fun putParts(parts: List<InstallationPart>)
    @Query("DELETE FROM installation_parts WHERE networkId=:id")
    suspend fun deleteParts(id: String)
    @Query("DELETE FROM installations WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(entities = [InstallationRecord::class, InstallationPart::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun installations(): InstallationDao
    companion object {
        val Migration1To2 = object : Migration(1,2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS installation_parts (networkId TEXT NOT NULL, part INTEGER NOT NULL, json TEXT NOT NULL, PRIMARY KEY(networkId,part), FOREIGN KEY(networkId) REFERENCES installations(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }
    }
}

@Singleton
class InstallationStore @Inject constructor(private val database: AppDatabase, private val gson: Gson) {
    val records get() = database.installations().observe()
    suspend fun read(id: String): Installation? = withContext(Dispatchers.IO) {
        database.withTransaction {
            val dao = database.installations()
            val record = dao.get(id) ?: return@withTransaction null
            val json = if (record.json == "@parts:1") {
                val parts = dao.parts(id)
                require(parts.isNotEmpty() && parts.withIndex().all { it.index == it.value.part }) { "Saved home is incomplete" }
                parts.joinToString("") { it.json }
            } else buildString {
                // SQLite substr/length count characters, so legacy Unicode stays intact.
                val length = dao.legacyLength(id)
                for (start in 1..length step 32768) append(dao.legacyPart(id,start,32768))
            }
            decode(json)
        }
    }
    suspend fun write(value: Installation) = withContext(Dispatchers.IO) {
        val json = encode(value)
        val parts = mutableListOf<InstallationPart>()
        var start = 0
        while (start < json.length) {
            var end = (start + 32768).coerceAtMost(json.length)
            if (end < json.length && json[end - 1].isHighSurrogate() && json[end].isLowSurrogate()) end--
            parts += InstallationPart(value.networkId,parts.size,json.substring(start,end))
            start = end
        }
        database.withTransaction {
            val dao = database.installations()
            dao.put(InstallationRecord(value.networkId,value.name,"@parts:1"))
            dao.deleteParts(value.networkId)
            dao.putParts(parts)
        }
    }
    fun encode(value: Installation): String = gson.toJson(value)
    fun decode(text: String): Installation {
        val root = JsonParser.parseString(text).asJsonObject
        if (!root.has("favoriteColors")) root.add("favoriteColors",JsonArray())
        if (!root.has("recentColors")) root.add("recentColors",JsonArray())
        root.getAsJsonArray("rooms")?.forEach {
            if (!it.asJsonObject.has("controlFailures")) it.asJsonObject.add("controlFailures",JsonObject())
        }
        return gson.fromJson(root,Installation::class.java).also { validate(it) }
    }

    fun validate(value: Installation) {
        require(value.version == 1 && value.networkId.isNotBlank() && value.networkId.length <= 80 && value.name.isNotBlank() && value.name.length <= 256)
        require(value.nodes.size <= 1000 && value.nodes.map { it.uuid }.distinct().size == value.nodes.size)
        require(value.nodes.map { it.address }.distinct().size == value.nodes.size)
        value.nodes.forEach { node ->
            require(node.uuid.isNotBlank() && node.address in 1..0x7FFF && node.name.isNotBlank())
            require(node.companyId in 0..0xFFFF && node.modelId in 0..0xFFFF)
            node.capabilities?.let { require(it.version == 1 && it.ledCount in 1..339 && it.maxIntensity in 1..100) }
            (node.leds.values + listOfNotNull(node.aggregate)).forEach {
                require(it.state.intensity in 0..100)
                require(listOf(it.state.rgb.red, it.state.rgb.green, it.state.rgb.blue).all { channel -> channel in 0..255 })
            }
            node.leds.keys.forEach { require(it in 0 until (node.capabilities?.ledCount ?: 0)) }
        }
        require(value.rooms.map { it.address }.distinct().size == value.rooms.size)
        value.rooms.forEach { require(it.address in 0xC000..0xFEFF && it.name.isNotBlank()) }
        fun reference(ref: LedRef) {
            require(ref.nodeUuid.isNotBlank() && ref.index in 0 until 339)
        }
        value.zones.forEach { zone -> require(zone.name.isNotBlank()); zone.leds.forEach(::reference) }
        value.presets.forEach { preset ->
            require(preset.name.isNotBlank() && preset.edits.map { it.ref }.distinct().size == preset.edits.size)
            preset.edits.forEach { edit ->
                reference(edit.ref)
                require(!edit.patch.isEmpty && (edit.patch.intensity == null || edit.patch.intensity in 0..100))
                edit.patch.rgb?.let { require(listOf(it.red, it.green, it.blue).all { channel -> channel in 0..255 }) }
            }
        }
    }
}
