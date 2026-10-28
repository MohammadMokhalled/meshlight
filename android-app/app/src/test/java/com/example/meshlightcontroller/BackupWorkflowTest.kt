package com.example.meshlightcontroller

import android.app.Application
import android.net.Uri
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class BackupWorkflowTest {
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
        } finally {scope.cancel();database.close();file.delete()}
    }
}
