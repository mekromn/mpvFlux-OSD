package app.marlboroadvance.mpvex.repository.shaderlab

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import app.marlboroadvance.mpvex.BuildConfig
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns access policy and non-destructive initialization for the canonical
 * /storage/emulated/0/mpv workspace.
 *
 * This service never relocates Shader Lab files to app-private storage.
 */
class ShaderLabWorkspaceManager internal constructor(
  val paths: ShaderLabWorkspacePaths,
  private val preflightCheck: () -> ShaderLabWorkspacePreflight,
  private val actionIntentFactory: (ShaderLabWorkspaceAction) -> Intent?,
) {
  constructor(context: Context) : this(
    paths = ShaderLabWorkspacePaths.canonical(),
    preflightCheck = { androidPreflight() },
    actionIntentFactory = { action -> createAndroidActionIntent(context, action) },
  )

  private val _state = MutableStateFlow<ShaderLabWorkspaceState>(
    ShaderLabWorkspaceState.Unchecked(paths)
  )
  val state: StateFlow<ShaderLabWorkspaceState> = _state.asStateFlow()

  /** Re-evaluates access without creating or changing files. */
  fun refreshAccessState(): ShaderLabWorkspaceState {
    val result = preflightToState(preflightCheck())
    _state.value = result
    return result
  }

  /**
   * Creates the required directory structure and performs a read/write probe.
   * Existing files are never replaced or edited by this method.
   */
  fun ensureWorkspace(): ShaderLabWorkspaceState {
    when (val preflight = preflightCheck()) {
      ShaderLabWorkspacePreflight.Ready -> Unit
      else -> {
        val result = preflightToState(preflight)
        _state.value = result
        return result
      }
    }

    val result = runCatching {
      paths.requiredDirectories.forEach(::ensureDirectory)
      verifyOwnershipSeparation()
      performReadWriteProbe()
      ShaderLabWorkspaceState.Available(paths)
    }.getOrElse { error ->
      ShaderLabWorkspaceState.Failure(
        paths = paths,
        reason = "Canonical Shader Lab workspace could not be created/read/written at ${paths.root.absolutePath}: ${error.message ?: "unknown I/O error"}",
        exceptionType = error::class.java.name,
      )
    }

    _state.value = result
    return result
  }

  /** Intent for the action carried by [ShaderLabWorkspaceState.PermissionRequired]. */
  fun createActionIntent(action: ShaderLabWorkspaceAction): Intent? =
    actionIntentFactory(action)

  private fun preflightToState(preflight: ShaderLabWorkspacePreflight): ShaderLabWorkspaceState =
    when (preflight) {
      ShaderLabWorkspacePreflight.Ready -> ShaderLabWorkspaceState.Unchecked(paths)
      is ShaderLabWorkspacePreflight.PermissionRequired ->
        ShaderLabWorkspaceState.PermissionRequired(
          paths = paths,
          reason = preflight.reason,
          action = preflight.action,
        )
      is ShaderLabWorkspacePreflight.Unavailable ->
        ShaderLabWorkspaceState.Unavailable(paths, preflight.reason)
    }

  private fun ensureDirectory(directory: File) {
    if (directory.exists()) {
      check(directory.isDirectory) {
        "Expected directory but found a file: ${directory.absolutePath}"
      }
      return
    }

    check(directory.mkdirs() || directory.isDirectory) {
      "Could not create directory: ${directory.absolutePath}"
    }
  }

  private fun verifyOwnershipSeparation() {
    val enginePaths = paths.engineOwnedRoots.map { it.canonicalFile }
    val userPaths = paths.userOwnedRoots.map { it.canonicalFile }

    check(enginePaths.none { engine -> userPaths.any { user -> engine == user } }) {
      "Shader Lab engine-owned and user-owned roots overlap"
    }
  }

  private fun performReadWriteProbe() {
    val probe = File(paths.engineMetadata, ".rw-probe-${UUID.randomUUID()}.tmp")
    val token = "chrovelo-mpvlab-${UUID.randomUUID()}"

    try {
      probe.outputStream().buffered().use { output ->
        output.write(token.toByteArray(Charsets.UTF_8))
        output.flush()
      }

      val roundTrip = probe.readText(Charsets.UTF_8)
      check(roundTrip == token) {
        "Workspace read/write probe returned different data"
      }
    } finally {
      if (probe.exists() && !probe.delete()) {
        throw IllegalStateException("Workspace probe file could not be removed: ${probe.absolutePath}")
      }
    }
  }

  companion object {
    private fun androidPreflight(): ShaderLabWorkspacePreflight {
      if (BuildConfig.SCOPED_STORAGE_ONLY) {
        return ShaderLabWorkspacePreflight.Unavailable(
          "This build is restricted to scoped storage and cannot own the canonical ${ShaderLabWorkspacePaths.CANONICAL_ROOT_PATH} workspace. Use the standard Chrovelo build for Shader Lab."
        )
      }

      val storageState = Environment.getExternalStorageState()
      if (storageState != Environment.MEDIA_MOUNTED) {
        val reason = if (storageState == Environment.MEDIA_MOUNTED_READ_ONLY) {
          "Shared storage is mounted read-only. Shader Lab requires read/write access to ${ShaderLabWorkspacePaths.CANONICAL_ROOT_PATH}."
        } else {
          "Shared storage is not writable (state=$storageState). Shader Lab requires ${ShaderLabWorkspacePaths.CANONICAL_ROOT_PATH}."
        }
        return ShaderLabWorkspacePreflight.Unavailable(reason)
      }

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
        return ShaderLabWorkspacePreflight.PermissionRequired(
          reason = "Allow 'All files access' so Chrovelo can create and maintain ${ShaderLabWorkspacePaths.CANONICAL_ROOT_PATH}. No app-private fallback will be used.",
          action = ShaderLabWorkspaceAction.OPEN_ALL_FILES_ACCESS_SETTINGS,
        )
      }

      return ShaderLabWorkspacePreflight.Ready
    }

    private fun createAndroidActionIntent(
      context: Context,
      action: ShaderLabWorkspaceAction,
    ): Intent? =
      when (action) {
        ShaderLabWorkspaceAction.OPEN_ALL_FILES_ACCESS_SETTINGS -> {
          if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            null
          } else {
            val appSpecific = Intent(
              Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
              Uri.parse("package:${context.packageName}"),
            )
            if (appSpecific.resolveActivity(context.packageManager) != null) {
              appSpecific
            } else {
              Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            }
          }
        }
      }
  }
}
