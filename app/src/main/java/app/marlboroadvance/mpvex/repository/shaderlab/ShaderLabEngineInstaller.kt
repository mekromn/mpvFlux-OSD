package app.marlboroadvance.mpvex.repository.shaderlab

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Result of reconciling the bundled Shader Lab engine with the canonical workspace. */
enum class ShaderLabEngineInstallOutcome {
  INSTALLED,
  UPDATED,
  REPAIRED,
  UNCHANGED,
}

sealed interface ShaderLabEngineInstallState {
  data object Idle : ShaderLabEngineInstallState

  data class Success(
    val outcome: ShaderLabEngineInstallOutcome,
    val engineVersion: String,
    val schemaVersion: Int,
    val filesWritten: Int,
    val staleFilesRemoved: Int,
    val filesVerified: Int,
  ) : ShaderLabEngineInstallState

  data class Blocked(
    val workspaceState: ShaderLabWorkspaceState,
  ) : ShaderLabEngineInstallState

  data class Failure(
    val reason: String,
    val exceptionType: String? = null,
  ) : ShaderLabEngineInstallState
}

@Serializable
internal data class ShaderLabEngineManifest(
  val canonicalWorkspace: String,
  val controlCatalogVersion: String,
  val engineVersion: String,
  val schemaVersion: Int,
  val files: List<ShaderLabEngineManifestFile>,
)

@Serializable
internal data class ShaderLabEngineManifestFile(
  val bytes: Long,
  val path: String,
  val sha256: String,
)

@Serializable
internal data class ShaderLabInstalledEngineMarker(
  val engineVersion: String,
  val schemaVersion: Int,
  val controlCatalogVersion: String,
  val manifestSha256: String,
  val installedAtEpochMillis: Long,
  val managedFiles: List<ShaderLabInstalledManagedFile>,
)

@Serializable
internal data class ShaderLabInstalledManagedFile(
  val destination: String,
  val sha256: String,
)

/**
 * Explicit migration hook for a future engine/schema transition.
 *
 * Same-schema engine revisions do not require a hook. A schema transition is
 * rejected unless one registered migration explicitly accepts the old marker
 * and new manifest.
 */
internal interface ShaderLabEngineMigration {
  fun supports(
    from: ShaderLabInstalledEngineMarker,
    to: ShaderLabEngineManifest,
  ): Boolean

  fun migrate(paths: ShaderLabWorkspacePaths)
}

internal fun interface ShaderLabEngineSource {
  fun read(relativePath: String): ByteArray
}

private class AndroidShaderLabEngineSource(
  private val assets: AssetManager,
) : ShaderLabEngineSource {
  override fun read(relativePath: String): ByteArray =
    assets.open("$ASSET_ROOT/$relativePath").use { it.readBytes() }

  companion object {
    private const val ASSET_ROOT = "mpvlab/source"
  }
}

/**
 * Installs and repairs the readable bundled Shader Lab engine in the canonical
 * /storage/emulated/0/mpv workspace.
 *
 * Safety rules:
 * - only manifest-listed engine files are managed;
 * - presets/ and state/ are never installation destinations;
 * - docs/misc reference material is isolated under .mpvlab/engine/reference;
 * - every bundled asset is hash/size verified before any write;
 * - payload and marker writes use same-directory temp files and atomic replace
 *   where supported, with a rollback-capable fallback;
 * - stale files are removed only when the previous installer marker proves
 *   they were managed by this installer;
 * - a missing/corrupt managed file is repaired on the next initialization.
 */
class ShaderLabEngineInstaller internal constructor(
  private val workspaceManager: ShaderLabWorkspaceManager,
  private val source: ShaderLabEngineSource,
  private val migrations: List<ShaderLabEngineMigration> = emptyList(),
  private val supportedSchemaVersions: Set<Int> = setOf(CURRENT_SCHEMA_VERSION),
  private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
  constructor(
    context: Context,
    workspaceManager: ShaderLabWorkspaceManager,
  ) : this(
    workspaceManager = workspaceManager,
    source = AndroidShaderLabEngineSource(context.assets),
  )

  private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
  }

  private val _state = MutableStateFlow<ShaderLabEngineInstallState>(ShaderLabEngineInstallState.Idle)
  val state: StateFlow<ShaderLabEngineInstallState> = _state.asStateFlow()

  fun installOrRepair(): ShaderLabEngineInstallState {
    val workspaceState = workspaceManager.ensureWorkspace()
    if (workspaceState !is ShaderLabWorkspaceState.Available) {
      return ShaderLabEngineInstallState.Blocked(workspaceState).also { _state.value = it }
    }

    val result = runCatching {
      reconcile(workspaceState.paths)
    }.getOrElse { error ->
      ShaderLabEngineInstallState.Failure(
        reason = "Shader Lab engine install/update failed: ${error.message ?: "unknown error"}",
        exceptionType = error::class.java.name,
      )
    }

    recordDiagnostic(workspaceState.paths, result)
    _state.value = result
    return result
  }

  private fun reconcile(paths: ShaderLabWorkspacePaths): ShaderLabEngineInstallState.Success {
    val manifestBytes = source.read(MANIFEST_ASSET)
    val manifestSha256 = sha256(manifestBytes)
    val manifest = json.decodeFromString<ShaderLabEngineManifest>(
      manifestBytes.toString(Charsets.UTF_8)
    )
    validateManifest(manifest)

    val plannedFiles = buildInstallPlan(paths, manifest, manifestBytes, manifestSha256)
    verifyBundledAssets(plannedFiles)

    val markerRead = readInstalledMarker(paths)
    val previousMarker = markerRead.marker

    runMigrationsIfRequired(previousMarker, manifest, paths)

    val currentDestinations = plannedFiles.mapTo(linkedSetOf()) { it.destinationRelative }
    val staleFiles = previousMarker
      ?.managedFiles
      .orEmpty()
      .filterNot { it.destination in currentDestinations }
      .map { managed -> resolveRecordedManagedFile(paths, managed.destination) }
      .filter { it.exists() }

    val filesNeedingWrite = plannedFiles.filterNot { planned ->
      planned.destination.isFile &&
        planned.destination.length() == planned.bytes.size.toLong() &&
        sha256(planned.destination.readBytes()) == planned.sha256
    }

    val markerMatchesTarget =
      previousMarker != null &&
        previousMarker.engineVersion == manifest.engineVersion &&
        previousMarker.schemaVersion == manifest.schemaVersion &&
        previousMarker.controlCatalogVersion == manifest.controlCatalogVersion &&
        previousMarker.manifestSha256 == manifestSha256

    filesNeedingWrite.forEach { planned ->
      atomicWrite(planned.destination, planned.bytes)
      check(sha256(planned.destination.readBytes()) == planned.sha256) {
        "Post-write hash verification failed for ${planned.destination.absolutePath}"
      }
    }

    var staleRemoved = 0
    staleFiles.forEach { stale ->
      check(isEngineOwnedDestination(paths, stale)) {
        "Refusing to remove stale path outside engine-owned roots: ${stale.absolutePath}"
      }
      check(stale.delete() || !stale.exists()) {
        "Could not remove stale managed engine file: ${stale.absolutePath}"
      }
      staleRemoved += 1
      pruneEmptyEngineParents(paths, stale.parentFile)
    }

    plannedFiles.forEach { planned ->
      check(planned.destination.isFile) {
        "Managed engine file is missing after reconciliation: ${planned.destination.absolutePath}"
      }
      check(planned.destination.length() == planned.bytes.size.toLong()) {
        "Managed engine file size mismatch after reconciliation: ${planned.destination.absolutePath}"
      }
      check(sha256(planned.destination.readBytes()) == planned.sha256) {
        "Managed engine file hash mismatch after reconciliation: ${planned.destination.absolutePath}"
      }
    }

    val marker = ShaderLabInstalledEngineMarker(
      engineVersion = manifest.engineVersion,
      schemaVersion = manifest.schemaVersion,
      controlCatalogVersion = manifest.controlCatalogVersion,
      manifestSha256 = manifestSha256,
      installedAtEpochMillis = if (markerMatchesTarget && filesNeedingWrite.isEmpty() && staleRemoved == 0) {
        previousMarker!!.installedAtEpochMillis
      } else {
        nowEpochMillis()
      },
      managedFiles = plannedFiles.map { planned ->
        ShaderLabInstalledManagedFile(
          destination = planned.destinationRelative,
          sha256 = planned.sha256,
        )
      },
    )

    if (!markerMatchesTarget || filesNeedingWrite.isNotEmpty() || staleRemoved > 0 || markerRead.corrupt) {
      atomicWrite(
        paths.engineVersionMarker,
        json.encodeToString(marker).toByteArray(Charsets.UTF_8),
      )
    }

    val outcome = when {
      previousMarker == null -> ShaderLabEngineInstallOutcome.INSTALLED
      !markerMatchesTarget -> ShaderLabEngineInstallOutcome.UPDATED
      filesNeedingWrite.isNotEmpty() || staleRemoved > 0 || markerRead.corrupt ->
        ShaderLabEngineInstallOutcome.REPAIRED
      else -> ShaderLabEngineInstallOutcome.UNCHANGED
    }

    return ShaderLabEngineInstallState.Success(
      outcome = outcome,
      engineVersion = manifest.engineVersion,
      schemaVersion = manifest.schemaVersion,
      filesWritten = filesNeedingWrite.size,
      staleFilesRemoved = staleRemoved,
      filesVerified = plannedFiles.size,
    )
  }

  private fun validateManifest(manifest: ShaderLabEngineManifest) {
    check(manifest.canonicalWorkspace == ShaderLabWorkspacePaths.CANONICAL_ROOT_PATH) {
      "Engine manifest targets ${manifest.canonicalWorkspace}; expected ${ShaderLabWorkspacePaths.CANONICAL_ROOT_PATH}"
    }
    check(manifest.engineVersion.isNotBlank()) { "Engine version is blank" }
    check(manifest.controlCatalogVersion.isNotBlank()) { "Control catalog version is blank" }
    check(manifest.schemaVersion in supportedSchemaVersions) {
      "Unsupported Shader Lab engine schema ${manifest.schemaVersion}; supported=$supportedSchemaVersions"
    }
    check(manifest.files.isNotEmpty()) { "Engine manifest contains no files" }

    val duplicates = manifest.files.groupBy { it.path }.filterValues { it.size > 1 }.keys
    check(duplicates.isEmpty()) { "Engine manifest contains duplicate paths: $duplicates" }

    manifest.files.forEach { file ->
      validateRelativeAssetPath(file.path)
      check(file.bytes >= 0) { "Negative byte count for ${file.path}" }
      check(file.sha256.matches(SHA256_REGEX)) { "Invalid SHA-256 for ${file.path}" }
    }
  }

  private fun buildInstallPlan(
    paths: ShaderLabWorkspacePaths,
    manifest: ShaderLabEngineManifest,
    manifestBytes: ByteArray,
    manifestSha256: String,
  ): List<PlannedEngineFile> {
    val payloadFiles = manifest.files.map { entry ->
      val destination = resolveManifestDestination(paths, entry.path)
      PlannedEngineFile(
        assetPath = entry.path,
        destination = destination,
        destinationRelative = relativeToWorkspace(paths, destination),
        bytes = source.read(entry.path),
        expectedBytes = entry.bytes,
        sha256 = entry.sha256.lowercase(),
      )
    }

    val localManifest = File(paths.engineMetadata, MANIFEST_ASSET)
    val manifestPlan = PlannedEngineFile(
      assetPath = MANIFEST_ASSET,
      destination = localManifest,
      destinationRelative = relativeToWorkspace(paths, localManifest),
      bytes = manifestBytes,
      expectedBytes = manifestBytes.size.toLong(),
      sha256 = manifestSha256,
    )

    return payloadFiles + manifestPlan
  }

  private fun verifyBundledAssets(plannedFiles: List<PlannedEngineFile>) {
    plannedFiles.forEach { planned ->
      check(planned.bytes.size.toLong() == planned.expectedBytes) {
        "Bundled asset size mismatch for ${planned.assetPath}: ${planned.bytes.size} != ${planned.expectedBytes}"
      }
      val actualHash = sha256(planned.bytes)
      check(actualHash == planned.sha256) {
        "Bundled asset hash mismatch for ${planned.assetPath}: $actualHash != ${planned.sha256}"
      }
    }
  }

  private fun resolveManifestDestination(
    paths: ShaderLabWorkspacePaths,
    manifestPath: String,
  ): File {
    validateRelativeAssetPath(manifestPath)
    val firstSegment = manifestPath.substringBefore('/')
    val destination = when (firstSegment) {
      "config", "scripts", "shaders" -> File(paths.root, manifestPath)
      "docs", "misc" -> File(paths.engineMetadata, "reference/$manifestPath")
      else -> error("Manifest path is not an engine-owned install class: $manifestPath")
    }.canonicalFile

    check(isEngineOwnedDestination(paths, destination)) {
      "Manifest destination escapes engine-owned roots: $manifestPath -> ${destination.absolutePath}"
    }
    check(paths.userOwnedRoots.none { destination.isInside(it.canonicalFile) }) {
      "Manifest destination overlaps a user-owned root: $manifestPath"
    }
    return destination
  }

  private fun resolveRecordedManagedFile(
    paths: ShaderLabWorkspacePaths,
    destinationRelative: String,
  ): File {
    validateRelativeAssetPath(destinationRelative)
    val destination = File(paths.root, destinationRelative).canonicalFile
    check(isEngineOwnedDestination(paths, destination)) {
      "Recorded managed destination escapes engine-owned roots: $destinationRelative"
    }
    check(destination != paths.engineVersionMarker.canonicalFile) {
      "Installer marker cannot manage/delete itself"
    }
    return destination
  }

  private fun isEngineOwnedDestination(
    paths: ShaderLabWorkspacePaths,
    destination: File,
  ): Boolean {
    val canonicalDestination = destination.canonicalFile
    return paths.engineOwnedRoots.any { root -> canonicalDestination.isInside(root.canonicalFile) }
  }

  private fun relativeToWorkspace(paths: ShaderLabWorkspacePaths, file: File): String {
    val rootPath = paths.root.canonicalFile.toPath()
    val filePath = file.canonicalFile.toPath()
    check(filePath.startsWith(rootPath)) {
      "Managed file is outside the canonical workspace: ${file.absolutePath}"
    }
    return rootPath.relativize(filePath).toString().replace(File.separatorChar, '/')
  }

  private fun validateRelativeAssetPath(path: String) {
    check(path.isNotBlank()) { "Empty engine asset path" }
    check(!path.startsWith('/')) { "Absolute engine asset path is forbidden: $path" }
    check('\\' !in path) { "Backslash engine asset path is forbidden: $path" }
    val segments = path.split('/')
    check(segments.none { it.isBlank() || it == "." || it == ".." }) {
      "Unsafe engine asset path: $path"
    }
  }

  private fun readInstalledMarker(paths: ShaderLabWorkspacePaths): MarkerRead {
    val markerFile = paths.engineVersionMarker
    if (!markerFile.isFile) return MarkerRead(marker = null, corrupt = false)

    return runCatching {
      MarkerRead(
        marker = json.decodeFromString<ShaderLabInstalledEngineMarker>(markerFile.readText()),
        corrupt = false,
      )
    }.getOrElse {
      MarkerRead(marker = null, corrupt = true)
    }
  }

  private fun runMigrationsIfRequired(
    previousMarker: ShaderLabInstalledEngineMarker?,
    target: ShaderLabEngineManifest,
    paths: ShaderLabWorkspacePaths,
  ) {
    if (previousMarker == null) return
    if (previousMarker.schemaVersion == target.schemaVersion) {
      migrations.filter { it.supports(previousMarker, target) }.forEach { it.migrate(paths) }
      return
    }

    check(target.schemaVersion > previousMarker.schemaVersion) {
      "Engine schema downgrade is not supported: ${previousMarker.schemaVersion} -> ${target.schemaVersion}"
    }

    val migration = migrations.singleOrNull { it.supports(previousMarker, target) }
      ?: error(
        "No explicit Shader Lab engine migration is registered for schema " +
          "${previousMarker.schemaVersion} -> ${target.schemaVersion}"
      )
    migration.migrate(paths)
  }

  private fun atomicWrite(target: File, bytes: ByteArray) {
    val parent = target.parentFile ?: error("Target has no parent: ${target.absolutePath}")
    check(parent.mkdirs() || parent.isDirectory) {
      "Could not create engine destination directory: ${parent.absolutePath}"
    }

    val temp = File(parent, ".${target.name}.${UUID.randomUUID()}.tmp")
    val backup = File(parent, ".${target.name}.${UUID.randomUUID()}.bak")

    try {
      FileOutputStream(temp).use { output ->
        output.write(bytes)
        output.flush()
        output.fd.sync()
      }

      try {
        Files.move(
          temp.toPath(),
          target.toPath(),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING,
        )
      } catch (_: AtomicMoveNotSupportedException) {
        rollbackCapableReplace(temp, target, backup)
      } catch (_: UnsupportedOperationException) {
        rollbackCapableReplace(temp, target, backup)
      }
    } finally {
      if (temp.exists()) temp.delete()
      if (backup.exists()) backup.delete()
    }
  }

  private fun rollbackCapableReplace(temp: File, target: File, backup: File) {
    var movedOldTarget = false
    try {
      if (target.exists()) {
        check(target.renameTo(backup)) {
          "Could not stage previous engine file for replacement: ${target.absolutePath}"
        }
        movedOldTarget = true
      }
      check(temp.renameTo(target)) {
        "Could not move verified temp file into place: ${target.absolutePath}"
      }
      if (movedOldTarget) {
        check(backup.delete() || !backup.exists()) {
          "Could not remove engine-file backup: ${backup.absolutePath}"
        }
      }
    } catch (error: Throwable) {
      if (!target.exists() && movedOldTarget && backup.exists()) {
        backup.renameTo(target)
      }
      throw error
    }
  }

  private fun pruneEmptyEngineParents(paths: ShaderLabWorkspacePaths, start: File?) {
    var current = start?.canonicalFile
    val stopRoots = paths.engineOwnedRoots.map { it.canonicalFile }.toSet()
    while (current != null && current !in stopRoots && isEngineOwnedDestination(paths, current)) {
      val children = current.listFiles() ?: break
      if (children.isNotEmpty() || !current.delete()) break
      current = current.parentFile?.canonicalFile
    }
  }

  private fun recordDiagnostic(
    paths: ShaderLabWorkspacePaths,
    result: ShaderLabEngineInstallState,
  ) {
    val line = when (result) {
      is ShaderLabEngineInstallState.Success ->
        "${nowEpochMillis()} outcome=${result.outcome} engine=${result.engineVersion} schema=${result.schemaVersion} " +
          "written=${result.filesWritten} removed=${result.staleFilesRemoved} verified=${result.filesVerified}"
      is ShaderLabEngineInstallState.Blocked ->
        "${nowEpochMillis()} blocked workspace=${result.workspaceState::class.java.simpleName}"
      is ShaderLabEngineInstallState.Failure ->
        "${nowEpochMillis()} failure type=${result.exceptionType ?: "unknown"} reason=${result.reason.replace('\n', ' ')}"
      ShaderLabEngineInstallState.Idle -> "${nowEpochMillis()} idle"
    }

    runCatching {
      if (paths.logs.mkdirs() || paths.logs.isDirectory) {
        File(paths.logs, INSTALL_LOG_FILE).appendText("$line\n", Charsets.UTF_8)
      }
    }
    Log.d(TAG, line)
  }

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
      .digest(bytes)
      .joinToString("") { "%02x".format(it) }

  private fun File.isInside(root: File): Boolean {
    val rootPath = root.canonicalFile.toPath()
    val filePath = canonicalFile.toPath()
    return filePath.startsWith(rootPath) && filePath != rootPath
  }

  private data class PlannedEngineFile(
    val assetPath: String,
    val destination: File,
    val destinationRelative: String,
    val bytes: ByteArray,
    val expectedBytes: Long,
    val sha256: String,
  )

  private data class MarkerRead(
    val marker: ShaderLabInstalledEngineMarker?,
    val corrupt: Boolean,
  )

  companion object {
    private const val TAG = "ShaderLabInstaller"
    private const val MANIFEST_ASSET = "engine-manifest.json"
    private const val INSTALL_LOG_FILE = "shaderlab-installer.log"
    private const val CURRENT_SCHEMA_VERSION = 1
    private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")
  }
}
