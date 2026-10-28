package com.example.meshlightcontroller

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.meshlightcontroller.core.model.*
import java.util.UUID
import com.example.meshlightcontroller.data.AppDatabase
import com.example.meshlightcontroller.data.InstallationStore
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InstallationDatabaseTest {
    @Test fun largeHomeUsesAndroidSQLiteWithoutOversizedCursorRows() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val database=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        try {
            val nodes=List(20) {number ->
                NodeInfo(UUID.nameUUIDFromBytes("large-$number".toByteArray()).toString(),number+2,"Light $number",
                    capabilities=Capabilities(1,339,0x3F,100),stage=SetupStage.READY,
                    leds=(0 until 339).associateWith {Observation(LedState(Rgb(it%256,number*11,90),72,true))})
            }
            val edits=nodes.flatMap {node -> node.leds.keys.map {PresetEdit(LedRef(node.uuid,it),LightingPatch(Rgb(10,20,30),72,true))}}
            val home=Installation(networkId="large",name="Large home",nodes=nodes,
                presets=List(6) {PresetInfo("look-$it","Look $it",edits)})
            val store=InstallationStore(database,Gson())
            assertTrue(store.encode(home).length>2*1024*1024)
            store.write(home)
            assertEquals(home,store.read("large"))
        } finally {database.close()}
    }

    @Test fun independentNetworksSurviveDatabaseReopen() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="metadata-test-${System.nanoTime()}.db"
        fun open()=Room.databaseBuilder(context,AppDatabase::class.java,name).build()
        try {
            val first=open()
            val store=InstallationStore(first,Gson())
            store.write(Installation(networkId="home",name="Home"))
            store.write(Installation(networkId="studio",name="Studio"))
            first.close()
            val reopened=open()
            val restored=InstallationStore(reopened,Gson())
            assertEquals("Home",restored.read("home")?.name)
            assertEquals("Studio",restored.read("studio")?.name)
            reopened.close()
        } finally {context.deleteDatabase(name)}
    }
}
