package app.marlboroadvance.mpvex.repository.shaderlab

import java.io.File

/**
 * Canonical user-visible Shader Lab workspace.
 *
 * The root is deliberately shared storage rather than app-private storage so
 * users can inspect, edit, back up, and replace mpv files directly.
 */
data class ShaderLabWorkspacePaths(
  val root: File,
) {
  val config: File = File(root, "config")
  val scripts: File = File(root, "scripts")
  val shaders: File = File(root, "shaders")
  val shaderRuntime: File = File(shaders, "runtime")
  val presets: File = File(root, "presets")
  val state: File = File(root, "state")
  val logs: File = File(root, "logs")

  /** Engine bookkeeping is isolated from user presets/state. */
  val engineMetadata: File = File(root, ".mpvlab/engine")
  val engineVersionMarker: File = File(engineMetadata, "version.json")

  val requiredDirectories: List<File>
    get() = listOf(
      root,
      config,
      scripts,
      shaders,
      shaderRuntime,
      presets,
      state,
      logs,
      engineMetadata,
    )

  /**
   * R04 may replace/repair files only inside these roots when updating the
   * bundled engine. User-owned roots below are explicitly excluded.
   */
  val engineOwnedRoots: List<File>
    get() = listOf(config, scripts, shaders, engineMetadata)

  /** These roots must survive engine/app updates without replacement. */
  val userOwnedRoots: List<File>
    get() = listOf(presets, state)

  companion object {
    const val CANONICAL_ROOT_PATH = "/storage/emulated/0/mpv"

    fun canonical(): ShaderLabWorkspacePaths =
      ShaderLabWorkspacePaths(File(CANONICAL_ROOT_PATH))
  }
}

enum class ShaderLabWorkspaceAction {
  OPEN_ALL_FILES_ACCESS_SETTINGS,
}

sealed interface ShaderLabWorkspaceState {
  val paths: ShaderLabWorkspacePaths

  data class Unchecked(
    override val paths: ShaderLabWorkspacePaths,
  ) : ShaderLabWorkspaceState

  data class Available(
    override val paths: ShaderLabWorkspacePaths,
  ) : ShaderLabWorkspaceState

  data class PermissionRequired(
    override val paths: ShaderLabWorkspacePaths,
    val reason: String,
    val action: ShaderLabWorkspaceAction,
  ) : ShaderLabWorkspaceState

  data class Unavailable(
    override val paths: ShaderLabWorkspacePaths,
    val reason: String,
  ) : ShaderLabWorkspaceState

  data class Failure(
    override val paths: ShaderLabWorkspacePaths,
    val reason: String,
    val exceptionType: String? = null,
  ) : ShaderLabWorkspaceState
}

internal sealed interface ShaderLabWorkspacePreflight {
  data object Ready : ShaderLabWorkspacePreflight

  data class PermissionRequired(
    val reason: String,
    val action: ShaderLabWorkspaceAction,
  ) : ShaderLabWorkspacePreflight

  data class Unavailable(
    val reason: String,
  ) : ShaderLabWorkspacePreflight
}
