package com.example.meshlightcontroller

import android.app.Application
import android.net.Uri
import androidx.room.Room
import com.example.meshlightcontroller.core.mesh.*
import com.example.meshlightcontroller.core.model.*
import com.example.meshlightcontroller.data.*
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSerializer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class BackupWorkflowTest {
    @Test fun importWaitsForInFlightAutosaveAndKeepsRestoredMetadata() = runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val seed=DemoMeshGateway().installation().copy(networkId=FixtureNetworkId)
        val base=DemoMeshGateway()
        val mergeStarted=CompletableDeferred<Unit>()
        val releaseMerge=CountDownLatch(1)
        val holdImportedMerge=AtomicBoolean(false)
        val snapshot=base.network.value!!
        val nodes=object : AbstractList<GatewayNode>() {
            override val size: Int get() {
                if (holdImportedMerge.compareAndSet(true,false)) {
                    mergeStarted.complete(Unit)
                    check(releaseMerge.await(10,TimeUnit.SECONDS)) {"Network merge gate timed out"}
                }
                return snapshot.nodes.size
            }
            override fun get(index: Int)=snapshot.nodes[index]
        }
        val radio=object : MeshGateway by base {
            override val network=MutableStateFlow<GatewayNetwork?>(snapshot.copy(id=FixtureNetworkId,nodes=nodes))
            override val connection=MutableStateFlow(ConnectionStatus.DISCONNECTED)
            override fun setForeground(active: Boolean)=Unit
            override fun setSessionEnabled(enabled: Boolean)=Unit
            override suspend fun networks()=emptyList<NetworkChoice>()
            override suspend fun importCdb(json: String) {
                CdbValidator.validate(json)
                holdImportedMerge.set(true)
            }
            override suspend fun disconnect()=Unit
        }
        val started=CompletableDeferred<Unit>()
        val release=CountDownLatch(1)
        val blockNextSave=AtomicBoolean(false)
        val plain=Gson()
        val gson=GsonBuilder().registerTypeAdapter(Installation::class.java,
            JsonSerializer<Installation> { value,_,_ ->
                if (blockNextSave.compareAndSet(true,false)) {
                    started.complete(Unit)
                    check(release.await(10,TimeUnit.SECONDS)) {"Autosave gate timed out"}
                }
                plain.toJsonTree(value)
            }).create()
        val database=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        val store=InstallationStore(database,gson)
        store.write(seed)
        val preferences=AppPreferences(context)
        preferences.session(false,FixtureNetworkId)
        preferences.transfer(FixtureNetworkId,true)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val file=File(context.cacheDir,"autosave-race.json").apply {writeText(fixtureCdb())}
        blockNextSave.set(true)
        val repository=LightingRepository(radio,DemoMeshGateway(),store,preferences,scope)
        try {
            withTimeout(5000) {started.await()}
            val importing=BackupService(context,repository,Gson()).import(Uri.fromFile(file),charArrayOf())
            try {
                val completed=withTimeoutOrNull(1000) {importing.join(); true} ?: false
                assertFalse("Import must wait for the older autosave before replacing metadata",completed)
            } finally {release.countDown()}
            try {
                withTimeout(5000) {mergeStarted.await()}
                assertTrue("The home must remain loading until its SDK nodes have been merged",repository.state.value.loading)
            } finally {releaseMerge.countDown()}
            withTimeout(5000) {importing.join()}
            val restored=withTimeout(5000) {repository.state.first {!it.loading&&!it.readOnly}}
            assertEquals(seed.nodes.size,restored.installation!!.nodes.size)
            assertTrue("Plain CDB import must not retain old observations",restored.installation!!.nodes.all {it.aggregate==null&&it.leds.isEmpty()})
            assertTrue("An older autosave must not overwrite imported metadata",store.read(FixtureNetworkId)!!.nodes.all {it.aggregate==null&&it.leds.isEmpty()})
        } finally {
            release.countDown()
            releaseMerge.countDown()
            scope.coroutineContext[Job]!!.cancelAndJoin()
            database.close()
            file.delete()
        }
    }

    @Test fun transferWritesEncryptedFileBeforeDisablingControlAndRestoreNeverOverwritesLiveNetwork() = runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val seed=DemoMeshGateway().installation().copy(networkId=FixtureNetworkId)
        val base=DemoMeshGateway()
        val choices=mutableListOf(NetworkChoice(FixtureNetworkId,"Home"))
        var importCalls=0
        val radio=object : MeshGateway by base {
            override val network=MutableStateFlow<GatewayNetwork?>(base.network.value!!.copy(id=FixtureNetworkId))
            override val connection=MutableStateFlow(ConnectionStatus.DISCONNECTED)
            override fun setForeground(active: Boolean)=Unit
            override fun setSessionEnabled(enabled: Boolean)=Unit
            override suspend fun networks()=choices.toList()
            override suspend fun exportCdb()=fixtureCdb()
            override suspend fun importCdb(json: String) {CdbValidator.validate(json);importCalls++}
            override suspend fun disconnect() {connection.value=ConnectionStatus.DISCONNECTED}
        }
        val database=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        val store=InstallationStore(database,Gson())
        store.write(seed)
        val preferences=AppPreferences(context)
        preferences.session(false,FixtureNetworkId)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val repository=LightingRepository(radio,DemoMeshGateway(),store,preferences,scope)
        val service=BackupService(context,repository,Gson())
        val file=File(context.cacheDir,"transfer.meshlight")
        try {
            withTimeout(5000) {repository.state.first {it.installation?.nodes?.size==4}}
            val password="Portable backup password".toCharArray()
            service.export(Uri.fromFile(file),password,transfer=true).join()
            assertTrue(file.length()>0)
            assertTrue(BackupCipher.isEncrypted(file.readBytes()))
            assertTrue(password.all {it=='\u0000'})
            withTimeout(5000) {repository.state.first {it.readOnly}}

            val rejectedPassword="Portable backup password".toCharArray()
            service.import(Uri.fromFile(file),rejectedPassword).join()
            assertEquals(0,importCalls)
            assertTrue(repository.state.value.operation!!.failures.isNotEmpty())
            assertTrue(rejectedPassword.all {it=='\u0000'})

            choices.clear()
            service.import(Uri.fromFile(file),"Portable backup password".toCharArray()).join()
            assertEquals(1,importCalls)
            withTimeout(5000) {repository.state.first {!it.readOnly&&it.installation?.networkId==FixtureNetworkId}}
            assertTrue(repository.state.value.installation!!.nodes.all {it.aggregate?.receivedAt==0L&&it.drafts.isEmpty()})
            assertEquals(seed.zones,repository.state.value.installation!!.zones)
        } finally {scope.coroutineContext[Job]!!.cancelAndJoin();database.close();file.delete()}
    }
}
