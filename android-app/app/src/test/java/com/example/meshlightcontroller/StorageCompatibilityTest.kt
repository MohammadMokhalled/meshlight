package com.example.meshlightcontroller

import android.app.Application
import androidx.room.Room
import com.example.meshlightcontroller.core.mesh.DemoMeshGateway
import com.example.meshlightcontroller.data.*
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[24,36],application=Application::class)
class StorageCompatibilityTest {
    @Test fun encryptedBackupAndRoomMetadataRoundTripOnSupportedAndroidVersions() = runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val database=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        try {
            val store=InstallationStore(database,Gson())
            val installation=DemoMeshGateway().installation()
            val serialized=store.encode(installation)
            val encrypted=BackupCipher.encrypt(serialized.toByteArray(),"A portable password".toCharArray())
            val restored=store.decode(BackupCipher.decrypt(encrypted,"A portable password".toCharArray()).toString(Charsets.UTF_8))
            assertEquals(installation,restored)
            store.write(restored)
            assertEquals(restored,store.read(restored.networkId))
        } finally {database.close()}
    }

    @Test fun sequenceWatermarkSurvivesRecreationAndNeverMovesBackwards() = runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val guard=SequenceGuard(context)
        guard.beforeTransmit("test-network",0x1234,3,410)
        guard.beforeTransmit("test-network",0x1234,3,409)
        assertEquals(410,SequenceGuard(context).floor("test-network",0x1234,3))
        assertEquals(0,guard.floor("test-network",0x1234,4))
        assertEquals(0,guard.floor("test-network",0x1235,3))
        guard.claimOwner("test-network","fresh-controller")
        assertEquals("fresh-controller",SequenceGuard(context).owner("test-network"))
        try {
            guard.beforeTransmit("test-network",0x1234,3,0x1000000)
            fail("Exhausted 24-bit sequence numbers must never be transmitted")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }
}
