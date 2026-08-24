package app.marlboroadvance.mpvex.repository.shaderlab

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShaderLabWorkspaceManagerTest {
  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @Test
  fun canonicalLayoutUsesRequiredPaths() {
    val paths = ShaderLabWorkspacePaths.canonical()

    assertEquals("/storage/emulated/0/mpv", paths.root.path)
    assertEquals("/storage/emulated/0/mpv/config", paths.config.path)
    assertEquals("/storage/emulated/0/mpv/scripts", paths.scripts.path)
    assertEquals("/storage/emulated/0/mpv/shaders", paths.shaders.path)
    assertEquals("/storage/emulated/0/mpv/shaders/runtime", paths.shaderRuntime.path)
    assertEquals("/storage/emulated/0/mpv/presets", paths.presets.path)
    assertEquals("/storage/emulated/0/mpv/state", paths.state.path)
    assertEquals("/storage/emulated/0/mpv/logs", paths.logs.path)
  }

  @Test
  fun ensureWorkspaceCreatesStructureAndReturnsAvailable() {
    val root = File(temporaryFolder.root, "mpv")
    val paths = ShaderLabWorkspacePaths(root)
    val manager = readyManager(paths)

    val result = manager.ensureWorkspace()

    assertTrue(result is ShaderLabWorkspaceState.Available)
    paths.requiredDirectories.forEach { directory ->
      assertTrue("Missing ${directory.path}", directory.isDirectory)
    }
  }

  @Test
  fun ensureWorkspacePreservesExistingUserPresetAndState() {
    val root = File(temporaryFolder.root, "mpv")
    val paths = ShaderLabWorkspacePaths(root)
    assertTrue(paths.presets.mkdirs())
    assertTrue(paths.state.mkdirs())

    val preset = File(paths.presets, "my-reference.json").apply {
      writeText("user-preset-do-not-touch")
    }
    val stateFile = File(paths.state, "session.json").apply {
      writeText("user-state-do-not-touch")
    }

    val result = readyManager(paths).ensureWorkspace()

    assertTrue(result is ShaderLabWorkspaceState.Available)
    assertEquals("user-preset-do-not-touch", preset.readText())
    assertEquals("user-state-do-not-touch", stateFile.readText())
  }

  @Test
  fun permissionRequiredIsNonDestructiveAndDoesNotRelocate() {
    val root = File(temporaryFolder.root, "mpv")
    val paths = ShaderLabWorkspacePaths(root)
    val manager = ShaderLabWorkspaceManager(
      paths = paths,
      preflightCheck = {
        ShaderLabWorkspacePreflight.PermissionRequired(
          reason = "grant access",
          action = ShaderLabWorkspaceAction.OPEN_ALL_FILES_ACCESS_SETTINGS,
        )
      },
      actionIntentFactory = { null },
    )

    val result = manager.ensureWorkspace()

    assertTrue(result is ShaderLabWorkspaceState.PermissionRequired)
    assertFalse(root.exists())
    assertEquals(root, result.paths.root)
  }

  @Test
  fun engineOwnershipNeverIncludesUserPresetOrStateRoots() {
    val paths = ShaderLabWorkspacePaths(File(temporaryFolder.root, "mpv"))

    assertFalse(paths.engineOwnedRoots.contains(paths.presets))
    assertFalse(paths.engineOwnedRoots.contains(paths.state))
    assertTrue(paths.userOwnedRoots.contains(paths.presets))
    assertTrue(paths.userOwnedRoots.contains(paths.state))
  }

  private fun readyManager(paths: ShaderLabWorkspacePaths) =
    ShaderLabWorkspaceManager(
      paths = paths,
      preflightCheck = { ShaderLabWorkspacePreflight.Ready },
      actionIntentFactory = { null },
    )
}
