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
class DemoSessionTest {
    @Test fun leavingDemoRestoresTheChosenHomeAndDoesNotAdoptAnUnclaimedSdkNetwork() = runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val database=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        val preferences=AppPreferences(context)
        preferences.session(false,"")
        val original=DemoMeshGateway()
        val radio=object : MeshGateway by original {
            override val network=MutableStateFlow<GatewayNetwork?>(GatewayNetwork(FixtureNetworkId,"SDK default",emptyList(),emptyList()))
            override fun setForeground(active: Boolean)=Unit
            override fun setSessionEnabled(enabled: Boolean)=Unit
            override suspend fun selectNetwork(id: String)=Unit
            override suspend fun networks()=listOf(NetworkChoice(FixtureNetworkId,"Home"))
            override suspend fun disconnect()=Unit
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val repository=LightingRepository(radio,DemoMeshGateway(),InstallationStore(database,Gson()),preferences,scope)
        try {
            withTimeout(5000) {repository.state.first {!it.loading}}
            assertNull(repository.state.value.installation)
            repository.enterDemo()
            withTimeout(5000) {repository.state.first {it.demo&&!it.loading}}
            repository.leaveDemo()
            withTimeout(5000) {repository.state.first {!it.demo&&!it.loading}}
            assertNull(repository.state.value.installation)

            preferences.session(false,FixtureNetworkId)
            withTimeout(5000) {repository.state.first {it.installation?.networkId==FixtureNetworkId&&!it.loading}}
            repository.enterDemo()
            withTimeout(5000) {repository.state.first {it.demo&&!it.loading}}
            assertEquals(FixtureNetworkId,preferences.flow.first().lastRealNetwork)
            repository.leaveDemo()
            withTimeout(5000) {repository.state.first {!it.demo&&!it.loading}}
            assertEquals(FixtureNetworkId,repository.state.value.installation?.networkId)
        } finally {scope.cancel();database.close()}
    }
}
