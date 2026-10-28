package com.example.meshlightcontroller.data

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object BackupCipher {
    private const val Magic = 0x4D4C424B
    private const val MemoryKiB = 19 * 1024
    private const val Iterations = 2
    private const val Parallelism = 1
    private const val HeaderSize = 4 + 1 + 4 + 4 + 1 + 16 + 12
    private const val MaxSize = 16 * 1024 * 1024

    fun encrypt(plaintext: ByteArray, password: CharArray): ByteArray {
        require(password.size >= 8) { "Use a password with at least 8 characters" }
        require(plaintext.size <= MaxSize - HeaderSize - 16)
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val header = ByteArrayOutputStream().apply {
            DataOutputStream(this).use { stream ->
                stream.writeInt(Magic); stream.writeByte(1); stream.writeInt(MemoryKiB)
                stream.writeInt(Iterations); stream.writeByte(Parallelism); stream.write(salt); stream.write(nonce)
            }
        }.toByteArray()
        val key = derive(password, salt)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            header + cipher.doFinal(plaintext)
        } finally { key.fill(0) }
    }

    fun decrypt(archive: ByteArray, password: CharArray): ByteArray {
        require(archive.size in (HeaderSize + 16)..MaxSize) { "Invalid backup size" }
        val input = DataInputStream(archive.inputStream())
        require(input.readInt() == Magic && input.readUnsignedByte() == 1) { "Unsupported backup format" }
        require(input.readInt() == MemoryKiB && input.readInt() == Iterations && input.readUnsignedByte() == Parallelism) { "Unsupported backup parameters" }
        val salt = ByteArray(16).also(input::readFully)
        val nonce = ByteArray(12).also(input::readFully)
        val key = derive(password, salt)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(archive, 0, HeaderSize)
            cipher.doFinal(archive, HeaderSize, archive.size - HeaderSize)
        } finally { key.fill(0) }
    }

    fun isEncrypted(bytes: ByteArray) = bytes.size >= 4 && DataInputStream(bytes.inputStream()).readInt() == Magic

    private fun derive(password: CharArray, salt: ByteArray): ByteArray {
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(MemoryKiB).withIterations(Iterations).withParallelism(Parallelism).withSalt(salt).build()
        return ByteArray(32).also { key -> Argon2BytesGenerator().apply { init(parameters) }.generateBytes(password, key) }
    }
}
