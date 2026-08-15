package app.marlboroadvance.mpvex.ui.player

import android.content.Context
import app.marlboroadvance.mpvex.BuildConfig
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Installs the exact Shader Lab v6.1.1 workstation bundled in the APK into
 * mpvLab's private files directory. No shared-storage /mpv folder is required.
 *
 * Engine files are refreshed when the app build changes while user preset/state
 * files are preserved because extraction only overwrites files present in the
 * bundled engine archive.
 */
object ShaderLabRuntime {
  private const val ASSET_ZIP = "mpvlab/pixel9-mpv-shader-lab-workstation-v6.1.1.zip"
  private const val ENGINE_REVISION = "6.1.1-native-bridge-2"

  data class Paths(
    val root: File,
    val config: File,
    val controller: File,
    val inputConf: File,
    val template: File,
    val slotA: File,
    val slotB: File,
    val stateDir: File,
  )

  @Synchronized
  fun install(context: Context): Paths {
    val root = File(context.filesDir, "mpvlab")
    val marker = File(root, ".engine-version")
    val expectedMarker = "$ENGINE_REVISION:${BuildConfig.GIT_SHA}"

    val paths = paths(root)
    val complete =
      paths.config.isFile &&
        paths.controller.isFile &&
        paths.inputConf.isFile &&
        paths.template.isFile &&
        paths.slotA.isFile &&
        paths.slotB.isFile

    if (!complete || marker.readTextOrNull() != expectedMarker) {
      root.mkdirs()
      unzipBundledEngine(context, root)
      patchForPrivateRuntime(paths)
      paths.stateDir.mkdirs()
      marker.writeText(expectedMarker)
    }

    return paths
  }

  private fun paths(root: File) =
    Paths(
      root = root,
      config = File(root, "mpv.conf"),
      controller = File(root, "scripts/pixel9-shader-lab.lua"),
      inputConf = File(root, "input.conf"),
      template = File(root, "shaders/pixel9-perceptual-expansion-template.glsl.txt"),
      slotA = File(root, "shaders/pixel9-perceptual-expansion-runtime-a.glsl"),
      slotB = File(root, "shaders/pixel9-perceptual-expansion-runtime-b.glsl"),
      stateDir = File(root, "state"),
    )

  private fun unzipBundledEngine(context: Context, root: File) {
    val rootCanonical = root.canonicalFile
    context.assets.open(ASSET_ZIP).use { input ->
      ZipInputStream(input).use { zip ->
        while (true) {
          val entry = zip.nextEntry ?: break
          val target = File(root, entry.name).canonicalFile
          require(target.path == rootCanonical.path || target.path.startsWith(rootCanonical.path + File.separator)) {
            "Unsafe Shader Lab archive entry: ${entry.name}"
          }

          if (entry.isDirectory) {
            target.mkdirs()
          } else {
            target.parentFile?.mkdirs()
            target.outputStream().use { output -> zip.copyTo(output) }
          }
          zip.closeEntry()
        }
      }
    }
  }

  private fun patchForPrivateRuntime(paths: Paths) {
    // The uploaded workstation intentionally used /storage/emulated/0/mpv.
    // Replace that one deployment root everywhere with mpvLab's private root.
    val externalRoot = "/storage/emulated/0/mpv"
    listOf(paths.config, paths.controller, paths.inputConf).forEach { file ->
      if (file.isFile) {
        file.writeText(file.readText().replace(externalRoot, paths.root.absolutePath))
      }
    }

    // mpvLab explicitly loads the controller and input.conf through libmpv so
    // the internal mpv.conf must not load a second copy of the same script.
    if (paths.config.isFile) {
      val cleaned =
        paths.config
          .readLines()
          .filterNot { line ->
            val trimmed = line.trimStart()
            trimmed.startsWith("script=") || trimmed.startsWith("input-conf=")
          }.joinToString("\n", postfix = "\n")
      paths.config.writeText(cleaned)
    }

    patchControllerForNativeState(paths.controller)
  }

  private fun patchControllerForNativeState(controller: File) {
    if (!controller.isFile) return
    var text = controller.readText()
    if (text.contains("mpvLab native bridge v1")) return

    val marker = "local function phone_left()"
    require(text.contains(marker)) { "Unexpected Shader Lab v6.1.1 controller layout" }
    text = text.replace(marker, NATIVE_STATE_PUBLISHER + "\n\n" + marker)
    text += NATIVE_BRIDGE_TAIL
    controller.writeText(text)
  }

  private fun File.readTextOrNull(): String? =
    runCatching { if (isFile) readText() else null }.getOrNull()

  private val NATIVE_STATE_PUBLISHER =
    """
    -- mpvLab native bridge v1
    local function publish_native_state()
        local lines = {
            "__version=6.1.1",
            "__bank=" .. tostring(active_bank),
            "__bypassed=" .. (bypassed and "1" or "0"),
            "__preview=" .. (preview_active and "1" or "0"),
            "__sdr=" .. (is_sdr() and "1" or "0"),
            "__shader_slot=" .. ((last_good_path == SLOT_A) and "A" or "B"),
            "__swaps=" .. tostring(shader_apply_count),
        }
        for _, it in ipairs(items) do
            if it.kind ~= "action" then
                local v = B[it.key]
                if v ~= nil then
                    lines[#lines + 1] = it.key .. "=" .. string.format("%.17g", v)
                end
            end
        end
        for i = 1, 10 do
            lines[#lines + 1] = "__user" .. tostring(i) .. "=" .. (user_slot_exists[i] and "1" or "0")
        end
        mp.set_property("user-data/p9lab/native-state", table.concat(lines, "\n"))
    end
    """.trimIndent()

  private val NATIVE_BRIDGE_TAIL =
    """

    -- mpvLab native bridge v1 registrations
    mp.register_script_message("p9lab-native-reset-all", function()
        reset_all()
        publish_native_state()
    end)
    mp.register_script_message("p9lab-native-revert-video-start", function()
        revert_video_start()
        publish_native_state()
    end)
    mp.register_script_message("p9lab-user-clear", function(slot)
        B.USER_SLOT = clamp(tonumber(slot) or B.USER_SLOT, 1, 10)
        clear_user_preset(B.USER_SLOT)
        publish_native_state()
    end)
    mp.register_script_message("p9lab-native-save-state", function()
        save_state()
        publish_native_state()
    end)
    mp.register_script_message("p9lab-native-load-state", function()
        load_state()
        publish_native_state()
    end)
    mp.add_periodic_timer(0.20, publish_native_state)
    publish_native_state()
    """.trimIndent()
}
