package com.example.meshlightcontroller

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.example.meshlightcontroller.core.model.*
import com.example.meshlightcontroller.data.*
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

fun largeHome(): Installation {
    val nodes=List(20) { index ->
        val values=(0 until 339).associateWith { Observation(LedState(Rgb(it%256,index*11,140),72,true)) }
        NodeInfo(UUID.nameUUIDFromBytes("node-$index".toByteArray()).toString(),index+2,"Light $index",
            capabilities=Capabilities(1,339,0x3F,100),stage=SetupStage.READY,leds=values)
    }
    val edits=nodes.flatMap {node->node.leds.map {(index,state)->PresetEdit(LedRef(node.uuid,index),LightingPatch(state.state.rgb,72,true))}}
    return Installation(networkId="large",name="Large home",nodes=nodes,presets=List(6) {PresetInfo("preset-$it","Look $it",edits)})
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class LargeInstallationTest {
    @Test fun twentyLongStripsAndPresetsSurviveStorageAboveOneCursorWindow() = runBlocking {
        val database=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),AppDatabase::class.java).build()
        try {
            val store=InstallationStore(database,Gson())
            val original=largeHome()
            assertTrue(store.encode(original).length>2*1024*1024)
            store.write(original)
            assertEquals(original,store.read("large"))
        } finally {database.close()}
    }

    @Test fun schemaOneSnapshotsMigrateWithoutLosingUnicodeOrMeshMetadata() = runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val name="legacy-${System.nanoTime()}.db"
        val original=Installation(networkId="legacy",name="Earlier home ☀️")
        context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null).use {database->
            database.execSQL("CREATE TABLE installations (id TEXT NOT NULL, name TEXT NOT NULL, json TEXT NOT NULL, PRIMARY KEY(id))")
            database.execSQL("INSERT INTO installations VALUES (?,?,?)",arrayOf(original.networkId,original.name,Gson().toJson(original)))
            database.version=1
        }
        val database=Room.databaseBuilder(context,AppDatabase::class.java,name).addMigrations(AppDatabase.Migration1To2).build()
        try {
            val store=InstallationStore(database,Gson())
            assertEquals(original,store.read("legacy"))
            val updated=original.copy(name="Migrated home 🌿")
            store.write(updated)
            assertEquals(updated,store.read("legacy"))
        } finally {database.close();context.deleteDatabase(name)}
    }
}
