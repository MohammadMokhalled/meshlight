package com.example.meshlightcontroller

import android.app.Application
import androidx.room.Room
import com.example.meshlightcontroller.core.mesh.*
import com.example.meshlightcontroller.core.model.*
import com.example.meshlightcontroller.data.*
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class RepositoryWorkflowTest {
    private class Radio(private val delegate: DemoMeshGateway = DemoMeshGateway()) : MeshGateway by delegate {
        override val connection=MutableStateFlow(ConnectionStatus.DISCONNECTED)
        val writes=mutableListOf<Triple<String,Int,LightingPatch>>()
        var failMembership: String?=null
        var failWrites: String?=null
        var holdNextWrite: CompletableDeferred<Unit>?=null
        override fun setForeground(active: Boolean)=Unit
        override fun setSessionEnabled(enabled: Boolean)=Unit
        override suspend fun disconnect() {connection.value=ConnectionStatus.DISCONNECTED}
        override suspend fun connectProxy() {connection.value=ConnectionStatus.CONNECTED}
        override suspend fun write(node: NodeInfo,target: Int,patch: LightingPatch,acknowledged: Boolean): Observation? {
            writes+=Triple(node.uuid,target,patch)
            val hold=holdNextWrite
            holdNextWrite=null
            hold?.await()
            check(node.uuid!=failWrites) {"Test radio unreachable"}
            return delegate.write(node,target,patch,acknowledged)
        }
        override suspend fun roomMembership(node: NodeInfo,address: Int,add: Boolean) {
            check(node.uuid!=failMembership) {"Test light did not confirm its subscriptions"}
            delegate.roomMembership(node,address,add)
        }
    }

    @Test fun submittedEditsRecoverAfterLinkLossWhileOfflineChangesAndCancellationStayExplicit() = runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val database=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        val store=InstallationStore(database,Gson())
        val seed=DemoMeshGateway().installation()
        store.write(seed)
        val preferences=AppPreferences(context)
        preferences.session(false,"demo")
        val radio=Radio()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val repository=LightingRepository(radio,DemoMeshGateway(),store,preferences,scope)
        try {
            withTimeout(5000) {repository.state.first {it.installation?.nodes?.size==4}}
            repository.setForeground(true)
            repository.connect().join()
            val uuid=seed.nodes.first().uuid
            radio.holdNextWrite=CompletableDeferred()
            repository.edit(uuid,setOf(LedTarget.All),LightingPatch(intensity=19))
            repository.edit(uuid,setOf(1),LightingPatch(rgb=Rgb(12,90,210)))
            assertEquals(1,radio.writes.size)
            radio.connection.value=ConnectionStatus.DISCONNECTED
            assertEquals(2,repository.state.value.installation!!.nodes.first().drafts.size)
            radio.connectProxy()
            withTimeout(5000) {repository.state.first {it.pendingTargets.isEmpty() && it.installation!!.nodes.first().drafts.isEmpty()}}
            assertEquals(listOf(LedTarget.All,LedTarget.All,1),radio.writes.map {it.second})
            assertEquals(19,repository.state.value.installation!!.nodes.first().aggregate!!.state.intensity)
            assertNull(repository.state.value.error)

            radio.holdNextWrite=CompletableDeferred()
            repository.edit(uuid,setOf(LedTarget.All),LightingPatch(power=false))
            radio.connection.value=ConnectionStatus.DISCONNECTED
            repository.edit(uuid,setOf(LedTarget.All),LightingPatch(power=true))
            val count=radio.writes.size
            radio.connectProxy()
            assertEquals("Offline intent must not replay an older submitted value",count,radio.writes.size)
            assertEquals(true,repository.state.value.installation!!.nodes.first().drafts[LedTarget.All]?.power)
            repository.applyDrafts(uuid).join()
            assertTrue(repository.state.value.installation!!.nodes.first().drafts.isEmpty())

            radio.holdNextWrite=CompletableDeferred()
            repository.edit(uuid,setOf(LedTarget.All),LightingPatch(intensity=20))
            radio.connection.value=ConnectionStatus.DISCONNECTED
            repository.cancelOperation()
            val cancelledCount=radio.writes.size
            radio.connectProxy()
            assertEquals("Cancellation must clear reconnect work",cancelledCount,radio.writes.size)
        } finally {scope.cancel();database.close()}
    }

    @Test fun offlineDraftsRequireExplicitApplyAndPartialOperationsRemainRecoverable() = runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val database=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        val store=InstallationStore(database,Gson())
        val seed=DemoMeshGateway().installation()
        store.write(seed)
        val preferences=AppPreferences(context)
        preferences.session(false,"demo")
        val radio=Radio()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val repository=LightingRepository(radio,DemoMeshGateway(),store,preferences,scope)
        try {
            withTimeout(5000) {repository.state.first {it.installation?.nodes?.size==4}}
            val uuid=seed.nodes.first().uuid
            val rgb=Rgb(12,90,210)
            repository.edit(uuid,setOf(LedTarget.All),LightingPatch(rgb=rgb))
            assertTrue(radio.writes.isEmpty())
            assertEquals(rgb,repository.state.value.installation!!.nodes.first().drafts[LedTarget.All]?.rgb)
            repository.connect().join()
            assertTrue("Reconnection must not silently send offline edits",radio.writes.isEmpty())
            repository.applyDrafts(uuid).join()
            assertEquals(LightingPatch(rgb=rgb),radio.writes.single().third)
            assertTrue(repository.state.value.installation!!.nodes.first().drafts.isEmpty())
            assertEquals(72,repository.state.value.installation!!.nodes.first().aggregate!!.state.intensity)
            assertTrue(repository.state.value.installation!!.nodes.first().aggregate!!.state.power)

            val second=seed.nodes[1].uuid
            radio.failMembership=second
            repository.saveRoom("Quiet room",setOf(uuid,second)).join()
            var room=repository.state.value.installation!!.rooms.first {it.address==0xC002}
            assertEquals(setOf(uuid),room.confirmed)
            assertTrue(second in room.failures)
            radio.failMembership=null
            repository.retryRoom(0xC002).join()
            room=repository.state.value.installation!!.rooms.first {it.address==0xC002}
            assertEquals(room.members,room.confirmed)
            assertTrue(room.failures.isEmpty())

            repository.saveZone("Three LEDs",listOf(LedRef(second,0),LedRef(second,1),LedRef(second,2)),"test-zone")
            radio.failWrites=second
            repository.editZone("test-zone",LightingPatch(intensity=42)).join()
            val failed=repository.state.value.installation!!.nodes.first {it.uuid==second}
            assertEquals(setOf(0,1,2),failed.drafts.keys)
            assertEquals(3,repository.state.value.operation!!.failures.size)
            radio.failWrites=null
            repository.retryDrafts().join()
            assertTrue(repository.state.value.installation!!.nodes.first {it.uuid==second}.drafts.isEmpty())

            radio.connection.value=ConnectionStatus.DISCONNECTED
            repository.edit(second,setOf(0),LightingPatch(intensity=19))
            radio.connectProxy()
            repository.editZone("test-zone",LightingPatch(rgb=Rgb(90,40,180))).join()
            val remaining=repository.state.value.installation!!.nodes.first {it.uuid==second}
            assertEquals(LightingPatch(intensity=19),remaining.drafts[0])
            assertEquals(42,remaining.leds[0]!!.state.intensity)

            repository.forget(uuid).join()
            assertTrue(repository.state.value.installation!!.nodes.none {it.uuid==uuid})
            assertTrue(repository.state.value.installation!!.zones.first().invalidated)
            assertTrue(repository.state.value.installation!!.presets.first().invalidated)
            assertTrue(uuid in repository.state.value.installation!!.rooms.first {it.address==0xC002}.pendingRemoval)
            assertFalse(uuid in repository.state.value.installation!!.rooms.first {it.address==0xC001}.pendingRemoval)
            repository.saveNow()
            val restored=store.read("demo")!!
            assertEquals(repository.state.value.installation,restored)
        } finally {scope.cancel();database.close()}
    }
}
