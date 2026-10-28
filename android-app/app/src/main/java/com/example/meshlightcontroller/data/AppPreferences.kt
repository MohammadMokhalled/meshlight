package com.example.meshlightcontroller.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.meshPreferences by preferencesDataStore("appearance")
enum class ThemeChoice { SYSTEM, LIGHT, DARK }
data class UiPreferences(
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    val dynamicColor: Boolean = false,
    val demo: Boolean = false,
    val networkId: String = "",
    val companyId: Int = 0xFFFF,
    val modelId: Int = 1,
    val transferredNetworks: Set<String> = emptySet(),
    val lastRealNetwork: String = ""
)

@Singleton
class AppPreferences @Inject constructor(@ApplicationContext private val context: Context) {
    private object Keys {
        val theme = stringPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic")
        val demo = booleanPreferencesKey("demo")
        val network = stringPreferencesKey("network")
        val lastRealNetwork = stringPreferencesKey("last_real_network")
        val company = intPreferencesKey("company")
        val model = intPreferencesKey("model")
        val transferred = stringSetPreferencesKey("transferred")
    }
    val flow = context.meshPreferences.data.map { data ->
        UiPreferences(
            ThemeChoice.entries.firstOrNull { it.name == data[Keys.theme] } ?: ThemeChoice.SYSTEM,
            data[Keys.dynamic] ?: false, data[Keys.demo] ?: false, data[Keys.network].orEmpty(),
            data[Keys.company] ?: 0xFFFF, data[Keys.model] ?: 1, data[Keys.transferred] ?: emptySet(), data[Keys.lastRealNetwork].orEmpty())
    }
    suspend fun appearance(theme: ThemeChoice, dynamic: Boolean) {
        context.meshPreferences.edit { it[Keys.theme] = theme.name; it[Keys.dynamic] = dynamic }
    }
    suspend fun session(demo: Boolean, networkId: String) {
        context.meshPreferences.edit { data ->
            if (demo) {
                if (data[Keys.demo] != true) data[Keys.lastRealNetwork] = data[Keys.network].orEmpty()
            } else data[Keys.lastRealNetwork] = networkId
            data[Keys.demo] = demo
            data[Keys.network] = networkId
        }
    }
    suspend fun transfer(networkId: String, disabled: Boolean) {
        context.meshPreferences.edit { data ->
            val current = data[Keys.transferred] ?: emptySet()
            data[Keys.transferred] = if (disabled) current + networkId else current - networkId
        }
    }
    suspend fun vendor(company: Int, model: Int) {
        require(company in 0..0xFFFF && model in 0..0xFFFF)
        context.meshPreferences.edit { it[Keys.company] = company; it[Keys.model] = model }
    }
}
