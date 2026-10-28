package com.example.meshlightcontroller.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SequenceGuard @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("transmit_watermarks", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    fun owner(networkId: String): String? = preferences.getString("owner/$networkId", null)
    suspend fun claimOwner(networkId: String, uuid: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(preferences.edit().putString("owner/$networkId", uuid).commit()) {
                "Unable to save the local controller identity"
            }
        }
    }
    fun floor(networkId: String, source: Int, iv: Int): Int = preferences.getInt("$networkId/$source/$iv", 0)
    suspend fun beforeTransmit(networkId: String, source: Int, iv: Int, nextSequence: Int) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(nextSequence in 0..0xFFFFFF) { "Mesh sequence space exhausted" }
            val key = "$networkId/$source/$iv"
            val previous = preferences.getInt(key, 0)
            if (nextSequence > previous) check(preferences.edit().putInt(key, nextSequence).commit()) {
                "Unable to persist mesh sequence watermark"
            }
        }
    }
}
