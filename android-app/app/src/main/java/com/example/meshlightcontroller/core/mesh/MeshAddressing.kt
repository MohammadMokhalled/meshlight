package com.example.meshlightcontroller.core.mesh

import android.annotation.SuppressLint
import no.nordicsemi.android.mesh.MeshNetwork

internal object MeshAddressing {
    fun allocateProvisioningAddress(network: MeshNetwork, elementCount: Int): Int {
        require(elementCount > 0) { "Light reported no mesh elements" }
        val provisioner = requireNotNull(network.selectedProvisioner)
        val address = network.nextAvailableUnicastAddress(elementCount, provisioner)
        check(address in 1..0x7FFF) { "No free unicast address range for this light" }
        // Nordic 3.5.0 validates a free address in startProvisioning(), but uses this
        // separately stored address in Provisioning Data. It initially equals 0x0001.
        check(network.assignUnicastAddress(address))
        return address
    }

    // The gateway already uses these provisioner APIs from the pinned Nordic 3.5.0 SDK.
    @SuppressLint("RestrictedApi")
    fun repairControllerAddress(network: MeshNetwork): Boolean {
        val provisioner = requireNotNull(network.selectedProvisioner)
        val address = requireNotNull(provisioner.provisionerAddress)
        val collision = network.nodes.any {
            !it.uuid.equals(provisioner.provisionerUuid, true) && it.hasUnicastAddress(address)
        }
        if (!collision) return false

        // Keep the already provisioned light and its device key at the old address.
        // Updating through the SDK also moves the controller node and persists it,
        // so address-based lookup can resolve replies to the light again.
        val replacement = network.nextAvailableUnicastAddress(1, provisioner)
        check(replacement in 1..0x7FFF) { "No free unicast address for the controller" }
        check(provisioner.assignProvisionerAddress(replacement))
        check(network.updateProvisioner(provisioner))
        return true
    }
}
