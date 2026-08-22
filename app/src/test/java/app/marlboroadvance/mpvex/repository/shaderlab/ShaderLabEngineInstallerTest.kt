package app.marlboroadvance.mpvex.repository.shaderlab

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShaderLabEngineInstallerTest {
  @get:Rule
  val temporaryFolder = TemporaryFolder()

  private val json = Json { prettyPrint = true }

  @Test
  fun freshInstallPopulatesEngineRootsAndNeverWritesUserStateFromReferenceAssets() {
    val fixture = fixture()
    val preset = File(fixture.paths.presets, "user-slot-1.txt").apply {
      parentFile!!.mkdirs()
      writeText("keep-preset")
    }
    val state = File(fixture.paths.state, "existing-state.txt").apply {
      parentFile!!.mkdirs()
      writeText("keep-state")
    }

    val source = source(
      engineVersion = "test-1",
      files = linkedMapOf(
        "config/input.conf" to "input".toByteArray(),
        "config/mpv.conf" to "mpv".toByteArray(),
        "scripts/controller.lua" to "lua".toByteArray(),
        "shaders/template.glsl" to "shader".toByteArray(),
        "docs/README.txt" to "docs".toByteArray(),
        "misc/state/README.txt" to "reference-state-only".toByteArray(),
      ),
    )

    val result = installer(fixture, source).installOrRepair()

    assertSuccess(result, ShaderLabEngineInstallOutcome.INSTALLED)
    assertEquals("input", File(fixture.paths.config, "input.conf").readText())
    assertEquals("mpv", File(fixture.paths.config, "mpv.conf").readText())
    assertEquals("lua", File(fixture.paths.scripts, "controller.lua").readText())
    assertEquals("shader", File(fixture.paths.shaders, "template.glsl").readText())
    assertEquals(
      "docs",
      File(fixture.paths.engineMetadata, "reference/docs/README.txt").readText(),
    )
    assertEquals(
      "reference-state-only",
      File(fixture.paths.engineMetadata, "reference/misc/state/README.txt").readText(),
    )
    assertFalse(File(fixture.paths.state, "README.txt").exists())
    assertEquals("keep-preset", preset.readText())
    assertEquals("keep-state", state.readText())
    assertTrue(fixture.paths.engineVersionMarker.isFile)
    assertTrue(File(fixture.paths.engineMetadata, "engine-manifest.json").isFile)
    assertTrue(File(fixture.paths.logs, "shaderlab-installer.log").isFile)
  }

  @Test
  fun unchangedInstallDoesNotRewritePayload() {
    val fixture = fixture()
    val source = source(
      engineVersion = "test-1",
      files = linkedMapOf("scripts/controller.lua" to "lua".toByteArray()),
    )
    val engineInstaller = installer(fixture, source)

    assertSuccess(engineInstaller.installOrRepair(), ShaderLabEngineInstallOutcome.INSTALLED)
    val controller = File(fixture.paths.scripts, "controller.lua")
    val originalTimestamp = controller.lastModified()

    val second = engineInstaller.installOrRepair()

    val success = assertSuccess(second, ShaderLabEngineInstallOutcome.UNCHANGED)
    assertEquals(0, success.filesWritten)
    assertEquals(0, success.staleFilesRemoved)
    assertEquals(originalTimestamp, controller.lastModified())
  }

  @Test
  fun corruptManagedFileIsRepairedFromVerifiedBundle() {
    val fixture = fixture()
    val source = source(
      engineVersion = "test-1",
      files = linkedMapOf("scripts/controller.lua" to "known-good".toByteArray()),
    )
    val engineInstaller = installer(fixture, source)
    assertSuccess(engineInstaller.installOrRepair(), ShaderLabEngineInstallOutcome.INSTALLED)

    val controller = File(fixture.paths.scripts, "controller.lua")
    controller.writeText("CORRUPT")

    val repaired = engineInstaller.installOrRepair()

    val success = assertSuccess(repaired, ShaderLabEngineInstallOutcome.REPAIRED)
    assertTrue(success.filesWritten >= 1)
    assertEquals("known-good", controller.readText())
  }

  @Test
  fun upgradeRemovesOnlyPreviouslyManagedStaleFilesAndPreservesUntrackedAndUserFiles() {
    val fixture = fixture()
    val firstSource = source(
      engineVersion = "test-1",
      files = linkedMapOf(
        "config/mpv.conf" to "old-config".toByteArray(),
        "scripts/old-managed.lua" to "old".toByteArray(),
      ),
    )
    assertSuccess(installer(fixture, firstSource).installOrRepair(), ShaderLabEngineInstallOutcome.INSTALLED)

    val untrackedEngineFile = File(fixture.paths.scripts, "user-helper.lua").apply {
      writeText("do-not-delete")
    }
    val preset = File(fixture.paths.presets, "slot-4.txt").apply {
      writeText("preset")
    }
    val state = File(fixture.paths.state, "runtime-state.txt").apply {
      writeText("state")
    }

    val secondSource = source(
      engineVersion = "test-2",
      files = linkedMapOf(
        "config/mpv.conf" to "new-config".toByteArray(),
        "scripts/new-managed.lua" to "new".toByteArray(),
      ),
    )

    val updated = installer(fixture, secondSource).installOrRepair()

    val success = assertSuccess(updated, ShaderLabEngineInstallOutcome.UPDATED)
    assertTrue(success.staleFilesRemoved >= 1)
    assertFalse(File(fixture.paths.scripts, "old-managed.lua").exists())
    assertEquals("new", File(fixture.paths.scripts, "new-managed.lua").readText())
    assertEquals("new-config", File(fixture.paths.config, "mpv.conf").readText())
    assertEquals("do-not-delete", untrackedEngineFile.readText())
    assertEquals("preset", preset.readText())
    assertEquals("state", state.readText())
  }

  @Test
  fun manifestCannotTargetPresetsOrState() {
    val fixture = fixture()
    val preset = File(fixture.paths.presets, "slot-1.txt").apply {
      parentFile!!.mkdirs()
      writeText("safe")
    }
    val source = source(
      engineVersion = "bad",
      files = linkedMapOf("state/owned-by-engine.txt" to "forbidden".toByteArray()),
    )

    val result = installer(fixture, source).installOrRepair()

    assertTrue(result is ShaderLabEngineInstallState.Failure)
    assertEquals("safe", preset.readText())
    assertFalse(File(fixture.paths.state, "owned-by-engine.txt").exists())
  }

  @Test
  fun bundledHashMismatchFailsBeforeWritingPayload() {
    val fixture = fixture()
    val payload = "actual".toByteArray()
    val manifest = ShaderLabEngineManifest(
      canonicalWorkspace = ShaderLabWorkspacePaths.CANONICAL_ROOT_PATH,
      controlCatalogVersion = "test",
      engineVersion = "bad-hash",
      schemaVersion = 1,
      files = listOf(
        ShaderLabEngineManifestFile(
          bytes = payload.size.toLong(),
          path = "scripts/controller.lua",
          sha256 = sha256("different".toByteArray()),
        )
      ),
    )
    val source = MutableMapEngineSource(
      mutableMapOf(
        "engine-manifest.json" to json.encodeToString(manifest).toByteArray(),
        "scripts/controller.lua" to payload,
      )
    )

    val result = installer(fixture, source).installOrRepair()

    assertTrue(result is ShaderLabEngineInstallState.Failure)
    assertFalse(File(fixture.paths.scripts, "controller.lua").exists())
  }

  @Test
  fun schemaTransitionRequiresExplicitMigrationHook() {
    val fixture = fixture()
    val firstSource = source(
      engineVersion = "schema-1",
      schemaVersion = 1,
      files = linkedMapOf("scripts/controller.lua" to "v1".toByteArray()),
    )
    assertSuccess(installer(fixture, firstSource).installOrRepair(), ShaderLabEngineInstallOutcome.INSTALLED)

    val secondSource = source(
      engineVersion = "schema-2",
      schemaVersion = 2,
      files = linkedMapOf("scripts/controller.lua" to "v2".toByteArray()),
    )

    val withoutMigration = ShaderLabEngineInstaller(
      workspaceManager = fixture.manager,
      source = secondSource,
      supportedSchemaVersions = setOf(1, 2),
    ).installOrRepair()
    assertTrue(withoutMigration is ShaderLabEngineInstallState.Failure)
    assertEquals("v1", File(fixture.paths.scripts, "controller.lua").readText())

    var migrated = false
    val migration = object : ShaderLabEngineMigration {
      override fun supports(
        from: ShaderLabInstalledEngineMarker,
        to: ShaderLabEngineManifest,
      ): Boolean = from.schemaVersion == 1 && to.schemaVersion == 2

      override fun migrate(paths: ShaderLabWorkspacePaths) {
        migrated = true
        File(paths.engineMetadata, "migration-1-to-2.txt").writeText("done")
      }
    }

    val withMigration = ShaderLabEngineInstaller(
      workspaceManager = fixture.manager,
      source = secondSource,
      migrations = listOf(migration),
      supportedSchemaVersions = setOf(1, 2),
    ).installOrRepair()

    assertTrue(migrated)
    assertSuccess(withMigration, ShaderLabEngineInstallOutcome.UPDATED)
    assertEquals("v2", File(fixture.paths.scripts, "controller.lua").readText())
    assertTrue(File(fixture.paths.engineMetadata, "migration-1-to-2.txt").isFile)
  }

  private fun fixture(): Fixture {
    val paths = ShaderLabWorkspacePaths(temporaryFolder.newFolder("mpv-${System.nanoTime()}"))
    val manager = ShaderLabWorkspaceManager(
      paths = paths,
      preflightCheck = { ShaderLabWorkspacePreflight.Ready },
      actionIntentFactory = { null },
    )
    return Fixture(paths, manager)
  }

  private fun installer(
    fixture: Fixture,
    source: ShaderLabEngineSource,
  ): ShaderLabEngineInstaller =
    ShaderLabEngineInstaller(
      workspaceManager = fixture.manager,
      source = source,
      nowEpochMillis = { 1_787_246_700_000L },
    )

  private fun source(
    engineVersion: String,
    files: LinkedHashMap<String, ByteArray>,
    schemaVersion: Int = 1,
  ): MutableMapEngineSource {
    val manifest = ShaderLabEngineManifest(
      canonicalWorkspace = ShaderLabWorkspacePaths.CANONICAL_ROOT_PATH,
      controlCatalogVersion = "test-catalog",
      engineVersion = engineVersion,
      schemaVersion = schemaVersion,
      files = files.map { (path, bytes) ->
        ShaderLabEngineManifestFile(
          bytes = bytes.size.toLong(),
          path = path,
          sha256 = sha256(bytes),
        )
      },
    )
    val map = files.toMutableMap()
    map["engine-manifest.json"] = json.encodeToString(manifest).toByteArray()
    return MutableMapEngineSource(map)
  }

  private fun assertSuccess(
    state: ShaderLabEngineInstallState,
    expectedOutcome: ShaderLabEngineInstallOutcome,
  ): ShaderLabEngineInstallState.Success {
    assertTrue("Expected success but was $state", state is ShaderLabEngineInstallState.Success)
    state as ShaderLabEngineInstallState.Success
    assertEquals(expectedOutcome, state.outcome)
    return state
  }

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
      .digest(bytes)
      .joinToString("") { "%02x".format(it) }

  private data class Fixture(
    val paths: ShaderLabWorkspacePaths,
    val manager: ShaderLabWorkspaceManager,
  )

  private class MutableMapEngineSource(
    private val files: MutableMap<String, ByteArray>,
  ) : ShaderLabEngineSource {
    override fun read(relativePath: String): ByteArray =
      files[relativePath]?.copyOf() ?: error("Missing fake engine asset: $relativePath")
  }
}
