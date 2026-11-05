package com.droidlink

import android.content.Context
import android.os.Build
import com.droidlink.data.local.AppDatabase
import com.droidlink.data.repository.AuthRepositoryImpl
import com.droidlink.data.repository.PairingRepositoryImpl
import com.droidlink.data.repository.SecurityLogRepositoryImpl
import com.droidlink.data.repository.SessionRepositoryImpl
import com.droidlink.data.repository.SettingsRepositoryImpl
import com.droidlink.data.repository.TransferRepositoryImpl
import com.droidlink.domain.model.AppSettings
import com.droidlink.domain.repository.AuthRepository
import com.droidlink.domain.repository.PairingRepository
import com.droidlink.domain.repository.SecurityLogRepository
import com.droidlink.domain.repository.SessionRepository
import com.droidlink.domain.repository.SettingsRepository
import com.droidlink.domain.repository.TransferRepository
import com.droidlink.domain.usecase.ApprovePairingUseCase
import com.droidlink.domain.usecase.ApproveSessionUseCase
import com.droidlink.domain.usecase.ClaimPairingCodeUseCase
import com.droidlink.domain.usecase.RequestSessionUseCase
import com.droidlink.domain.usecase.RevokePairingUseCase
import com.droidlink.network.ApiClient
import com.droidlink.network.NetworkMonitor
import com.droidlink.security.KeystoreManager
import com.droidlink.security.PrefsSecureKeyValue
import com.droidlink.security.Reauthenticator
import com.droidlink.security.SessionGuard
import com.droidlink.services.AndroidFileIo
import com.droidlink.services.DeviceInfoCollector
import com.droidlink.services.HostSessionController
import com.droidlink.services.PresenceCoordinator
import com.droidlink.services.RemoteInputExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual dependency container (keeps the project free of annotation-processing DI for easy builds). */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ---- security ------------------------------------------------------------------------------
    val keystore = KeystoreManager()
    private val secureStore = PrefsSecureKeyValue(appContext, keystore)
    val sessionGuard = SessionGuard()
    val reauthenticator = Reauthenticator()

    // ---- network -------------------------------------------------------------------------------
    private val restClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** WebSocket client: no read timeout, protocol-level pings detect dead connections. */
    val wsClient: OkHttpClient = restClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    val networkMonitor = NetworkMonitor(appContext)

    private val authImpl: AuthRepositoryImpl by lazy {
        AuthRepositoryImpl(secureStore, { api }, deviceModel = sanitizedModel())
    }
    val auth: AuthRepository get() = authImpl

    val api: ApiClient by lazy {
        ApiClient(restClient, baseUrl = { authImpl.currentServerUrl() }, tokenProvider = { force -> authImpl.accessToken(force) })
    }

    // ---- data ----------------------------------------------------------------------------------
    private val db: AppDatabase by lazy { AppDatabase.create(appContext) }
    val pairingRepository: PairingRepository by lazy { PairingRepositoryImpl(api, db.pairedDeviceDao()) }
    val sessionRepository: SessionRepository by lazy { SessionRepositoryImpl(api, db.connectionDao()) }
    val transferRepository: TransferRepository by lazy { TransferRepositoryImpl(db.transferDao()) }
    val securityLog: SecurityLogRepository by lazy { SecurityLogRepositoryImpl(db.securityEventDao()) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepositoryImpl(appContext) }

    /** droidlink://pair?... link received from the system (QR scanned with the camera app). */
    val incomingLink = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    @Volatile
    var currentSettings: AppSettings = AppSettings()
        private set

    // ---- use cases -----------------------------------------------------------------------------
    val claimPairingCode by lazy { ClaimPairingCodeUseCase(pairingRepository, securityLog) }
    val approvePairing by lazy { ApprovePairingUseCase(pairingRepository, securityLog) }
    val revokePairing by lazy { RevokePairingUseCase(pairingRepository, securityLog) }
    val requestSession by lazy { RequestSessionUseCase(sessionRepository, securityLog) }
    val approveSession by lazy { ApproveSessionUseCase(sessionRepository, securityLog) }

    // ---- services ------------------------------------------------------------------------------
    val fileIo by lazy { AndroidFileIo(appContext.contentResolver) }
    val deviceInfo by lazy { DeviceInfoCollector(appContext, networkMonitor) }
    val inputExecutor by lazy { RemoteInputExecutor(appContext, sessionGuard) }
    val hostController by lazy { HostSessionController(appContext, sessionGuard) }
    val presence by lazy { PresenceCoordinator(wsClient, auth, pairingRepository, sessionRepository, appScope) }

    init {
        settingsRepository.settings.onEach { currentSettings = it }.launchIn(appScope)
    }

    private fun sanitizedModel(): String {
        val cleaned = Build.MODEL.filter { it.isLetterOrDigit() || it in " _-.,()" }.take(64).trim()
        return cleaned.ifEmpty { "Android" }
    }
}
