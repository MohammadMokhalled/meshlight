package no.nordicsemi.android.mesh

import android.app.Application
import com.example.meshlightcontroller.core.mesh.MeshAddressing
import no.nordicsemi.android.mesh.transport.ProvisionedMeshNode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MeshAddressingTest {
    private fun network(lastAddress: Int = 0x199A): MeshNetwork {
        val mesh = MeshNetwork(UUID.randomUUID().toString())
        mesh.addNetKey(mesh.createNetworkKey())
        mesh.addAppKey(mesh.createAppKey())
        val provisioner = mesh.createProvisioner("Controller",
            AllocatedUnicastRange(1, lastAddress), AllocatedGroupRange(0xC000, 0xCC9A),
            AllocatedSceneRange(1, 0x3333))
        provisioner.assignProvisionerAddress(1)
        mesh.addProvisioner(provisioner)
        mesh.selectProvisioner(provisioner)
        return mesh
    }

    private fun light(mesh: MeshNetwork, address: Int, count: Int = 1): ProvisionedMeshNode {
        val identity = Provisioner(UUID.randomUUID().toString(), listOf(AllocatedUnicastRange(1, 0x7FFF)),
            emptyList(), emptyList(), mesh.meshUUID)
        identity.assignProvisionerAddress(address)
        return ProvisionedMeshNode(identity, mesh.netKeys, mesh.appKeys).apply {
            elements = (address until address + count).associateWith {
                identity.assignProvisionerAddress(it)
                ProvisionedMeshNode(identity, mesh.netKeys, mesh.appKeys).elements.getValue(it)
            }
        }
    }

    @Test fun firstLightDoesNotReuseTheControllerAddress() {
        val mesh = network()
        assertEquals(1, mesh.unicastAddress) // Nordic's initial Provisioning Data address.
        assertEquals(2, MeshAddressing.allocateProvisioningAddress(mesh, 1))
        assertEquals(2, mesh.unicastAddress)
        assertEquals(1, mesh.selectedProvisioner.provisionerAddress)
        assertNull(mesh.getNode(mesh.unicastAddress))
    }

    @Test fun allocationUsesTheActualElementCountAndRecomputesForEachLight() {
        val mesh = network()
        mesh.addNode(light(mesh, 3))
        assertEquals(2, MeshAddressing.allocateProvisioningAddress(mesh, 1))
        assertEquals(4, MeshAddressing.allocateProvisioningAddress(mesh, 2))
        mesh.addNode(light(mesh, 4, 2))
        assertEquals(6, MeshAddressing.allocateProvisioningAddress(mesh, 2))
    }

    @Test fun allocationHonorsExcludedAddressesAndFailsBeforeProvisioningWhenFull() {
        val mesh = network(3)
        mesh.networkExclusions[mesh.ivIndex.ivIndex] = listOf(2)
        assertEquals(3, MeshAddressing.allocateProvisioningAddress(mesh, 1))
        mesh.addNode(light(mesh, 3))
        assertThrows(IllegalStateException::class.java) { MeshAddressing.allocateProvisioningAddress(mesh, 1) }
        assertEquals(3, mesh.unicastAddress)
    }

    @Test fun repairKeepsTheProvisionedLightAndCredentialsAndMovesOnlyTheController() {
        val mesh = network()
        val controller = mesh.selectedProvisioner
        val provisioned = light(mesh, 1, 2)
        // Reproduce the duplicate addresses stored by the previous provisioning path.
        mesh.nodes.add(provisioned)
        val networkKey = mesh.primaryNetworkKey.key.copyOf()
        val deviceKey = provisioned.deviceKey.copyOf()
        assertNotSame(provisioned, mesh.getNode(1))

        assertTrue(MeshAddressing.repairControllerAddress(mesh))

        assertSame(controller, mesh.selectedProvisioner)
        assertEquals(3, controller.provisionerAddress)
        assertEquals(3, mesh.getNode(controller.provisionerUuid).unicastAddress)
        assertSame(provisioned, mesh.getNode(1))
        assertSame(provisioned, mesh.getNode(2))
        assertArrayEquals(deviceKey, provisioned.deviceKey)
        assertArrayEquals(networkKey, mesh.primaryNetworkKey.key)
        assertEquals(4, MeshAddressing.allocateProvisioningAddress(mesh, 1))
        assertFalse(MeshAddressing.repairControllerAddress(mesh))
    }

    @Test fun healthyControllerKeepsItsAddressAndSequence() {
        val mesh = network()
        val local = mesh.getNode(mesh.selectedProvisioner.provisionerUuid)
        local.sequenceNumber = 42
        mesh.addNode(light(mesh, 2))
        assertFalse(MeshAddressing.repairControllerAddress(mesh))
        assertSame(local, mesh.getNode(1))
        assertEquals(42, local.sequenceNumber)
    }
}
