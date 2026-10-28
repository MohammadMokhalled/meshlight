package com.example.meshlightcontroller.data

import android.content.Context
import android.net.Uri
import com.example.meshlightcontroller.core.model.*
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class BackupEnvelope(val version: Int, val meshCdb: String, val installation: Installation)

@Singleton
class BackupService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: LightingRepository,
    private val gson: Gson
) {
    fun export(uri: Uri, password: CharArray?, transfer: Boolean = false) =
        repository.startOperation(if (transfer) OperationKind.TRANSFER else OperationKind.EXPORT) {
            try {
                check(!state.value.demo) { "Leave demo mode to export a real network" }
                checkEditable()
                scheduler.cancel()
                gateway.disconnect()
                saveNow()
                val metadata = installation()
                val cdb = gateway.exportCdb()
                val output = withContext(Dispatchers.Default) {
                    if (password == null) cdb.toByteArray(Charsets.UTF_8)
                    else {
                        val plaintext = gson.toJson(BackupEnvelope(1, cdb, metadata)).toByteArray(Charsets.UTF_8)
                        try { BackupCipher.encrypt(plaintext, password) }
                        finally { plaintext.fill(0) }
                    }
                }
                try {
                    withContext(Dispatchers.IO) {
                        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { stream ->
                            stream.write(output)
                            stream.flush()
                        }
                    }
                } finally { output.fill(0) }
                if (transfer) preferences.transfer(metadata.networkId, true)
                advance()
            } finally { password?.fill('\u0000') }
        }

    fun import(uri: Uri, password: CharArray) = repository.startOperation(OperationKind.IMPORT) {
        try {
            check(!state.value.demo) { "Leave demo mode to import a real network" }
            val bytes = withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openInputStream(uri)).use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        require(output.size() + read <= 16 * 1024 * 1024) { "Backup is too large" }
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
            }
            val encrypted = BackupCipher.isEncrypted(bytes)
            val decoded = try {
                withContext(Dispatchers.Default) {
                    if (encrypted) BackupCipher.decrypt(bytes, password) else bytes.copyOf()
                }
            } finally { bytes.fill(0) }
            try {
                val text = decoded.toString(Charsets.UTF_8)
                val envelope = if (encrypted) withContext(Dispatchers.Default) {
                    gson.fromJson(text, BackupEnvelope::class.java).also { value ->
                        require(value.version == 1)
                        store.validate(value.installation)
                        require(CdbValidator.validate(value.meshCdb).replace("-", "")
                            .equals(value.installation.networkId.replace("-", ""), true))
                    }
                } else null
                val json = envelope?.meshCdb ?: text
                val id = withContext(Dispatchers.Default) { CdbValidator.validate(json) }
                val known = gateway.networks()
                require(known.none { it.id.replace("-", "").equals(id.replace("-", ""), true) }) {
                    "This network already exists on this phone. Switch to it; an import must not overwrite a live network."
                }
                gateway.importCdb(json)
                val network = requireNotNull(gateway.network.value)
                val metadata = envelope?.installation?.copy(networkId = network.id,
                    nodes = envelope.installation.nodes.map { it.copy(
                        aggregate = it.aggregate?.copy(receivedAt = 0),
                        leds = it.leds.mapValues { led -> led.value.copy(receivedAt = 0) },
                        drafts = emptyMap(), lastError = null, transition = null) })
                    ?: Installation(networkId = network.id, name = network.name)
                restoreInstallation(metadata)
                advance()
            } finally { decoded.fill(0) }
        } finally { password.fill('\u0000') }
    }

    fun diagnostics(uri: Uri) = repository.startOperation(OperationKind.EXPORT) {
        val installation = state.value.installation
        val text = buildString {
            appendLine("MeshLight diagnostics")
            appendLine("Demo: ${state.value.demo}")
            appendLine("Connection: ${state.value.connection}")
            appendLine("Lights: ${installation?.nodes?.size ?: 0}")
            installation?.nodes?.forEach {
                appendLine("0x%04X stage=%s LEDs=%s last=%s".format(it.address, it.stage,
                    it.capabilities?.ledCount ?: "unknown", it.aggregate?.receivedAt ?: 0))
            }
            state.value.diagnostics.forEach(::appendLine)
        }
        withContext(Dispatchers.IO) {
            requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(text) }
        }
        advance()
    }
}
