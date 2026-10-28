package com.example.meshlightcontroller

import com.example.meshlightcontroller.data.BackupCipher
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.AEADBadTagException

class BackupCipherTest {
    @Test fun encryptedBackupRoundTripsUnicodeAndAuthenticatesContent() {
        val source = """{"name":"Õhtune valgus 🌙","version":1}""".toByteArray()
        val encrypted = BackupCipher.encrypt(source, "pässword-mesh".toCharArray())
        assertTrue(BackupCipher.isEncrypted(encrypted))
        assertFalse(encrypted.toString(Charsets.UTF_8).contains("Õhtune"))
        assertArrayEquals(source, BackupCipher.decrypt(encrypted, "pässword-mesh".toCharArray()))
        assertThrows(AEADBadTagException::class.java) { BackupCipher.decrypt(encrypted, "incorrect-password".toCharArray()) }
        val damaged = encrypted.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(AEADBadTagException::class.java) { BackupCipher.decrypt(damaged, "pässword-mesh".toCharArray()) }
        assertThrows(IllegalArgumentException::class.java) { BackupCipher.decrypt(encrypted.copyOf().also { it[5] = 127 }, "pässword-mesh".toCharArray()) }
    }
}
