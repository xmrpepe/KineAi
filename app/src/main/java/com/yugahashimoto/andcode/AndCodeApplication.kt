package com.yugahashimoto.andcode

import android.content.Context
import com.kineai.local.KineApp
import com.yugahashimoto.andcode.core.diagnostics.CrashLog
import com.yugahashimoto.andcode.core.lifecycle.AppForeground
import com.yugahashimoto.andcode.core.lifecycle.ProcessLifecycleAppForeground
import com.yugahashimoto.andcode.core.locale.AppLanguage
import com.yugahashimoto.andcode.core.notification.RuntimeNotificationHelper
import com.yugahashimoto.andcode.core.runtime.RuntimeWorkTracker
import com.yugahashimoto.andcode.data.connection.SecureSettingsRepository
import com.yugahashimoto.andcode.data.repository.AndroidRuntimeActivityMessages
import com.yugahashimoto.andcode.data.repository.AndroidRuntimeCatalogMessages
import com.yugahashimoto.andcode.data.repository.ProviderCatalogCache
import com.yugahashimoto.andcode.data.repository.RuntimeActivityRepository
import com.yugahashimoto.andcode.data.repository.RuntimeCatalogRepository
import com.yugahashimoto.andcode.data.repository.SessionAutoArchiver
import com.yugahashimoto.andcode.data.settings.AppPreferencesRepository
import com.yugahashimoto.andcode.runtime.RuntimeRegistry
import com.yugahashimoto.andcode.runtime.local.KineLocalTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import java.io.File

/**
 * AndCode's application object, reduced to what a single on-device runtime needs. It extends
 * [KineApp], which owns the Kinetic engine, model library, downloads and diagnostics.
 */
class AndCodeApplication : KineApp() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var secureSettings: SecureSettingsRepository
        private set
    lateinit var preferences: AppPreferencesRepository
        private set
    lateinit var runtimeWork: RuntimeWorkTracker
        private set
    lateinit var appForeground: AppForeground
        private set
    lateinit var runtimeRegistry: RuntimeRegistry
        private set
    lateinit var catalogRepository: RuntimeCatalogRepository
        private set
    lateinit var activityRepository: RuntimeActivityRepository
        private set
    lateinit var notifications: RuntimeNotificationHelper
        private set
    lateinit var terminalSessions: com.yugahashimoto.andcode.runtime.local.TerminalSessionFactory
        private set

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.applyTo(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        appForeground = ProcessLifecycleAppForeground.install()
        runtimeWork = RuntimeWorkTracker()
        secureSettings = SecureSettingsRepository(this)
        preferences = AppPreferencesRepository(secureSettings)
        notifications = RuntimeNotificationHelper(this)
        terminalSessions = com.yugahashimoto.andcode.runtime.local.ShellTerminalSessionFactory(paths.projects)
        runtimeRegistry = RuntimeRegistry(KineLocalTarget(this))
        catalogRepository =
            RuntimeCatalogRepository(
                registry = runtimeRegistry,
                scope = applicationScope,
                providerCache =
                    ProviderCatalogCache(
                        directory = File(filesDir, "catalog-cache"),
                        json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true },
                    ),
                messages = AndroidRuntimeCatalogMessages(this),
            )
        activityRepository =
            RuntimeActivityRepository(
                registry = runtimeRegistry,
                scope = applicationScope,
                onPermissionAsked = { request, title, runtimeId -> notifications.notifyPermission(request, title, runtimeId) },
                onPermissionResolved = notifications::cancelPermission,
                onSessionIdle = { sessionId, title, runtimeId -> notifications.notifySessionComplete(sessionId, title, runtimeId) },
                onSessionError = { sessionId, message, _, runtimeId -> notifications.notifySessionError(sessionId, message, runtimeId) },
                onQuestionAsked = { request, title, runtimeId -> notifications.notifyQuestion(request, title, runtimeId) },
                unreadStore = secureSettings,
                messages = AndroidRuntimeActivityMessages(this),
            )
        SessionAutoArchiver(
            registry = runtimeRegistry,
            catalog = catalogRepository,
            activeSessionIds = { activityRepository.state.value.activeSessionIds },
            preferences = preferences.state,
            scope = applicationScope,
        ).start()
    }
}
