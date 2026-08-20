package app.marlboroadvance.mpvex

import android.app.Application
import android.util.Log
import app.marlboroadvance.mpvex.database.repository.VideoMetadataCacheRepository
import app.marlboroadvance.mpvex.di.DatabaseModule
import app.marlboroadvance.mpvex.di.FileManagerModule
import app.marlboroadvance.mpvex.di.PreferencesModule
import app.marlboroadvance.mpvex.di.ShaderLabModule
import app.marlboroadvance.mpvex.presentation.crash.CrashActivity
import app.marlboroadvance.mpvex.presentation.crash.GlobalExceptionHandler
import app.marlboroadvance.mpvex.repository.shaderlab.ShaderLabEngineInstallState
import app.marlboroadvance.mpvex.repository.shaderlab.ShaderLabEngineInstaller
import app.marlboroadvance.mpvex.repository.shaderlab.ShaderLabWorkspaceState
import app.marlboroadvance.mpvex.utils.media.MediaLibraryEvents
import `is`.xyz.mpv.FastThumbnails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.context.startKoin

@OptIn(KoinExperimentalAPI::class)
class App : Application() {
  private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val metadataCache: VideoMetadataCacheRepository by inject()
  private val shaderLabEngineInstaller: ShaderLabEngineInstaller by inject()

  override fun onCreate() {
    super.onCreate()

    // Initialize Koin
    startKoin {
      androidContext(this@App)
      modules(
        PreferencesModule,
        DatabaseModule,
        FileManagerModule,
        ShaderLabModule,
        app.marlboroadvance.mpvex.di.domainModule,
      )
    }

    Thread.setDefaultUncaughtExceptionHandler(GlobalExceptionHandler(applicationContext, CrashActivity::class.java))

    FastThumbnails.initialize(this)

    // R03 owns canonical workspace access. R04 immediately reconciles the
    // versioned engine only when that canonical workspace is available.
    // There is deliberately no app-private fallback.
    applicationScope.launch(Dispatchers.IO) {
      when (val installState = shaderLabEngineInstaller.installOrRepair()) {
        is ShaderLabEngineInstallState.Success ->
          Log.i(
            "ShaderLabInstaller",
            "Engine ${installState.outcome}: version=${installState.engineVersion}, " +
              "schema=${installState.schemaVersion}, written=${installState.filesWritten}, " +
              "removed=${installState.staleFilesRemoved}, verified=${installState.filesVerified}",
          )
        is ShaderLabEngineInstallState.Blocked ->
          when (val workspaceState = installState.workspaceState) {
            is ShaderLabWorkspaceState.PermissionRequired ->
              Log.w(
                "ShaderLabInstaller",
                "Workspace permission required: ${workspaceState.reason}; action=${workspaceState.action}",
              )
            is ShaderLabWorkspaceState.Unavailable ->
              Log.w("ShaderLabInstaller", "Workspace unavailable: ${workspaceState.reason}")
            is ShaderLabWorkspaceState.Failure ->
              Log.e(
                "ShaderLabInstaller",
                "Workspace failure: ${workspaceState.reason}; type=${workspaceState.exceptionType}",
              )
            is ShaderLabWorkspaceState.Available ->
              Log.w("ShaderLabInstaller", "Installer reported blocked despite available workspace")
            is ShaderLabWorkspaceState.Unchecked ->
              Log.d("ShaderLabInstaller", "Workspace remains unchecked")
          }
        is ShaderLabEngineInstallState.Failure ->
          Log.e(
            "ShaderLabInstaller",
            "Engine install failure: ${installState.reason}; type=${installState.exceptionType}",
          )
        ShaderLabEngineInstallState.Idle ->
          Log.d("ShaderLabInstaller", "Engine installer remains idle")
      }
    }

    // Perform cache maintenance on app startup (non-blocking)
    applicationScope.launch {
      runCatching {
        metadataCache.performMaintenance()
      }
    }

    // Trigger media scan on app launch to detect new videos
    applicationScope.launch {
      runCatching {
        triggerMediaScanOnLaunch()
      }
    }
  }

  /**
   * Trigger a media scan on app launch to ensure MediaStore is up-to-date
   * This helps detect videos added by external apps while the app was closed
   */
  private fun triggerMediaScanOnLaunch() {
    try {
      val externalStorage = android.os.Environment.getExternalStorageDirectory()

      android.media.MediaScannerConnection.scanFile(
        this,
        arrayOf(externalStorage.absolutePath),
        null, // Let MediaScanner detect all media types.
      ) { path, uri ->
        android.util.Log.d("App", "Launch media scan completed for: $path")
        // Notify the app that media library may have changed.
        MediaLibraryEvents.notifyChanged()
      }

      android.util.Log.d("App", "Triggered media scan on app launch")
    } catch (e: Exception) {
      android.util.Log.e("App", "Failed to trigger media scan on launch", e)
    }
  }
}
