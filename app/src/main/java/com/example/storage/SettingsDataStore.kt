package com.example.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.core.model.AutoDeleteOption
import com.example.core.model.GroupCreationPolicy
import com.example.core.model.MeshSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.meshPrefsDataStore by preferencesDataStore(name = "offlinemesh_settings")

class SettingsDataStore(private val context: Context) {

    private object Keys {
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val AUTO_DELETE = stringPreferencesKey("auto_delete")
        val GROUP_CREATION_POLICY = stringPreferencesKey("group_creation_policy")
        val DEFAULT_TTL = intPreferencesKey("default_ttl")
        val BACKGROUND_RELAY = booleanPreferencesKey("background_relay")
        val ALLOW_RELAY_FORWARDING = booleanPreferencesKey("allow_relay_forwarding")
    }

    val settingsFlow: Flow<MeshSettings> = context.meshPrefsDataStore.data.map { prefs ->
        val autoDelete = runCatching {
            AutoDeleteOption.valueOf(prefs[Keys.AUTO_DELETE] ?: AutoDeleteOption.OFF.name)
        }.getOrDefault(AutoDeleteOption.OFF)

        val groupPolicy = runCatching {
            GroupCreationPolicy.valueOf(
                prefs[Keys.GROUP_CREATION_POLICY] ?: GroupCreationPolicy.ANYONE.name
            )
        }.getOrDefault(GroupCreationPolicy.ANYONE)

        MeshSettings(
            displayName = prefs[Keys.DISPLAY_NAME] ?: "Mesh Node",
            autoDeleteOption = autoDelete,
            groupCreationPolicy = groupPolicy,
            defaultTtl = (prefs[Keys.DEFAULT_TTL] ?: 5).coerceIn(1, 7),
            backgroundRelayEnabled = prefs[Keys.BACKGROUND_RELAY] ?: true,
            allowRelayForwarding = prefs[Keys.ALLOW_RELAY_FORWARDING] ?: true
        )
    }

    suspend fun updateDisplayName(name: String) {
        context.meshPrefsDataStore.edit { it[Keys.DISPLAY_NAME] = name.trim().ifBlank { "Mesh Node" } }
    }

    suspend fun updateAutoDelete(option: AutoDeleteOption) {
        context.meshPrefsDataStore.edit { it[Keys.AUTO_DELETE] = option.name }
    }

    suspend fun updateGroupCreationPolicy(policy: GroupCreationPolicy) {
        context.meshPrefsDataStore.edit { it[Keys.GROUP_CREATION_POLICY] = policy.name }
    }

    suspend fun updateDefaultTtl(ttl: Int) {
        context.meshPrefsDataStore.edit { it[Keys.DEFAULT_TTL] = ttl.coerceIn(1, 7) }
    }

    suspend fun updateBackgroundRelay(enabled: Boolean) {
        context.meshPrefsDataStore.edit { it[Keys.BACKGROUND_RELAY] = enabled }
    }

    suspend fun updateAllowRelayForwarding(enabled: Boolean) {
        context.meshPrefsDataStore.edit { it[Keys.ALLOW_RELAY_FORWARDING] = enabled }
    }
}
