package com.droidlink.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.droidlink.data.local.SecurityEventDao
import com.droidlink.data.local.SecurityEventEntity
import com.droidlink.data.local.TransferDao
import com.droidlink.domain.model.AppSettings
import com.droidlink.domain.model.SecurityEvent
import com.droidlink.domain.model.ThemeMode
import com.droidlink.domain.model.TransferRecord
import com.droidlink.domain.repository.SecurityLogRepository
import com.droidlink.domain.repository.SettingsRepository
import com.droidlink.domain.repository.TransferRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TransferRepositoryImpl(private val dao: TransferDao) : TransferRepository {
    override val transfers: Flow<List<TransferRecord>> = dao.observe().map { l -> l.map { it.toDomain() } }
    override suspend fun upsert(record: TransferRecord) = dao.upsert(record.toEntity())
    override suspend fun clear() = dao.clear()
}

class SecurityLogRepositoryImpl(
    private val dao: SecurityEventDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : SecurityLogRepository {
    override val events: Flow<List<SecurityEvent>> = dao.observe().map { l -> l.map { it.toDomain() } }

    /** Never pass secrets, tokens or typed text as [detail]. */
    override suspend fun log(type: String, detail: String) {
        dao.insert(SecurityEventEntity(timestamp = clock(), type = type, detail = detail.take(300)))
        dao.trim()
    }

    override suspend fun clear() = dao.clear()
}

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "droidlink_settings")

class SettingsRepositoryImpl(context: Context) : SettingsRepository {
    private val store = context.applicationContext.settingsStore

    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val REAUTH = booleanPreferencesKey("reauth")
        val APP_LOCK = booleanPreferencesKey("app_lock")
        val FPS = intPreferencesKey("fps")
        val QUALITY = intPreferencesKey("quality")
        val ONBOARDING = booleanPreferencesKey("onboarding_done")
    }

    override val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            themeMode = p[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            requireReauthForApproval = p[Keys.REAUTH] ?: true,
            appLockEnabled = p[Keys.APP_LOCK] ?: false,
            defaultFps = p[Keys.FPS] ?: 12,
            defaultQuality = p[Keys.QUALITY] ?: 60,
            onboardingDone = p[Keys.ONBOARDING] ?: false,
        )
    }

    override suspend fun setThemeMode(mode: ThemeMode) { store.edit { it[Keys.THEME] = mode.name } }
    override suspend fun setRequireReauth(value: Boolean) { store.edit { it[Keys.REAUTH] = value } }
    override suspend fun setAppLock(value: Boolean) { store.edit { it[Keys.APP_LOCK] = value } }
    override suspend fun setStreamDefaults(fps: Int, quality: Int) {
        store.edit {
            it[Keys.FPS] = fps.coerceIn(2, 30)
            it[Keys.QUALITY] = quality.coerceIn(20, 95)
        }
    }
    override suspend fun setOnboardingDone() { store.edit { it[Keys.ONBOARDING] = true } }
}
