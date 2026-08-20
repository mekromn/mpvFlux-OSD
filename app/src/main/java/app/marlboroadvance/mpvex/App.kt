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
import app.marlboroadvance.mpvex.repository.shaderlab.ShaderLabWorkspaceManager
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
  private val shaderLabWorkspaceManager: ShaderLabWorkspaceManager by inject()

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

    // Initialize the canonical Shader Lab workspace without blocking app startup.
    // Permission failures remain explicit state; no app-private fallback is used.
    applicationScope.launch(Dispatchers.IO) {
      when (val workspaceState = shaderLabWorkspaceManager.ensureWorkspace()) {
        is ShaderLabWorkspaceState.Available ->
          Log.i(
            "ShaderLabWorkspace",
            "Canonical workspace ready: ${workspaceState.paths.root.absolutePath}",
          )
        is ShaderLabWorkspaceState.PermissionRequired ->
          Log.w(
            "ShaderLabWorkspace",
            "Workspace permission required: ${workspaceState.reason}; action=${workspaceState.action}",
          )
        is ShaderLabWorkspaceState.Unavailable ->
          Log.w("ShaderLabWorkspace", "Workspace unavailable: ${workspaceState.reason}")
        is ShaderLabWorkspaceState.Failure ->
          Log.e(
            "ShaderLabWorkspace",
            "Workspace failure: ${workspaceState.reason}; type=${workspaceState.exceptionType}",
          )
        is ShaderLabWorkspaceState.Unchecked ->
          Log.d("ShaderLabWorkspace", "Workspace state remains unchecked")
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
        null, // Let MediaScanner detect all media types
      ) { path, uri ->
        android.util.Log.d("App", "Launch media scan completed for: $path")
        // Notify the app that media library may have changed
        MediaLibraryEvents.notifyChanged()
      }
      
      android.util.Log.d("App", "Triggered media scan on app launch")
    } catch (e: Exception) {
      android.util.Log.e("App", "Failed to trigger media scan on launch", e)
    }
  }
}
