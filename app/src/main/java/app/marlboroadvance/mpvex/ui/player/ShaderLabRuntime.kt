package app.marlboroadvance.mpvex.ui.player

import android.content.Context
import app.marlboroadvance.mpvex.BuildConfig
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipInputStream

/**
 * Installs the exact Shader Lab v6.1.1 workstation bundled in the APK into
 * mpvLab's private files directory. No shared-storage /mpv folder is required.
 *
 * The source workstation ZIP is stored as small Base64 asset chunks because
 * some repository/API paths can silently truncate binary payloads. We rebuild
 * it byte-for-byte at runtime and require the known SHA-256 before extraction.
 *
 * Engine files are refreshed when the app build changes while user preset/state
 * files are preserved because extraction only overwrites files present in the
 * bundled engine archive.
 */
object ShaderLabRuntime {
  private const val ENGINE_REVISION = "6.1.1-native-bridge-4"
  private const val PAYLOAD_SHA256 = "e498dfebbec204b264fb00bf5a39f9df70ecec6f87bc34fdc224cfc14653dcc6"

  private val payloadParts =
    listOf(
      "mpvlab/payload/workstation.b64.00",
      "mpvlab/payload/workstation.b64.01",
      "mpvlab/payload/workstation.b64.02",
      "mpvlab/payload/workstation.b64.03",
      "mpvlab/payload/workstation.b64.04",
      "mpvlab/payload/workstation.b64.05",
      "mpvlab/payload/workstation.b64.06",
      "mpvlab/payload/workstation.b64.07",
    )

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

  private fun loadBundledArchive(context: Context): ByteArray {
    val encoded =
      buildString {
        payloadParts.forEach { part ->
          context.assets.open(part).bufferedReader().use { reader ->
            reader.forEachLine { line ->
              line.forEach { ch -> if (!ch.isWhitespace()) append(ch) }
            }
          }
        }
      }

    val decoded = Base64.getDecoder().decode(encoded)
    val digest =
      MessageDigest
        .getInstance("SHA-256")
        .digest(decoded)
        .joinToString(separator = "") { byte ->
          (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    require(digest == PAYLOAD_SHA256) {
      "Bundled Shader Lab payload failed SHA-256 verification: $digest"
    }
    return decoded
  }

  private fun unzipBundledEngine(context: Context, root: File) {
    val rootCanonical = root.canonicalFile
    val archive = loadBundledArchive(context)

    ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
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

  private fun patchForPrivateRuntime(paths: Paths) {
    val externalRoot = "/storage/emulated/0/mpv"
    listOf(paths.config, paths.controller, paths.inputConf).forEach { file ->
      if (file.isFile) {
        file.writeText(file.readText().replace(externalRoot, paths.root.absolutePath))
      }
    }

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
    if (text.contains("mpvLab native bridge v2")) return

    val previewGate = "if not ui_visible or preview_active or not is_sdr() then return end"
    require(text.contains(previewGate)) { "Unexpected Shader Lab v6.1.1 preview layout" }
    text = text.replace(previewGate, "if preview_active or not is_sdr() then return end")

    val marker = "local function phone_left()"
    require(text.contains(marker)) { "Unexpected Shader Lab v6.1.1 controller layout" }
    text = text.replace(marker, NATIVE_STATE_PUBLISHER + "\n\n" + marker)

    val setRegistration = "mp.register_script_message(\"p9lab-set\",set_by_key)"
    require(text.contains(setRegistration)) { "Unexpected Shader Lab v6.1.1 set registration" }
    text = text.replace(setRegistration, "mp.register_script_message(\"p9lab-set\",native_set_by_key)")

    text += NATIVE_BRIDGE_TAIL
    controller.writeText(text)
  }

  private fun File.readTextOrNull(): String? =
    runCatching { if (isFile) readText() else null }.getOrNull()

  private val NATIVE_STATE_PUBLISHER =
    """
    -- mpvLab native bridge v2
    local native_last_error = ""
    local native_apply_busy = false
    local native_shader_dirty = false
    local native_shader_timer = nil

    local function publish_native_state()
        local lines = {
            "__version=6.1.1",
            "__bank=" .. tostring(active_bank),
            "__bypassed=" .. (bypassed and "1" or "0"),
            "__preview=" .. (preview_active and "1" or "0"),
            "__sdr=" .. (is_sdr() and "1" or "0"),
            "__shader_slot=" .. ((last_good_path == SLOT_A) and "A" or "B"),
            "__swaps=" .. tostring(shader_apply_count),
            "__apply_busy=" .. (native_apply_busy and "1" or "0"),
            "__shader_dirty=" .. (native_shader_dirty and "1" or "0"),
            "__error=" .. tostring(native_last_error or ""):gsub("[\r\n]", " "),
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

    local function native_flush_shader()
        native_shader_timer = nil
        if not native_shader_dirty or native_apply_busy then return end
        if preview_active or bypassed then
            publish_native_state()
            return
        end

        native_shader_dirty = false
        native_apply_busy = true
        local ok, err = apply_shader(B)
        native_apply_busy = false
        if ok then
            native_last_error = ""
        else
            native_last_error = tostring(err or "unknown shader apply failure")
            msg.error("mpvLab shader apply failed: " .. native_last_error)
            mp.osd_message("mpvLab shader error:\n" .. native_last_error, 4)
        end
        publish_native_state()

        if native_shader_dirty and not native_shader_timer then
            native_shader_timer = mp.add_timeout(0.008, native_flush_shader)
        end
    end

    local function native_schedule_shader()
        native_shader_dirty = true
        if not native_shader_timer and not native_apply_busy then
            native_shader_timer = mp.add_timeout(0.008, native_flush_shader)
        end
    end

    local function native_set_by_key(key, value)
        local it = by_key[key]
        local num = tonumber(value)
        if not it or not num or it.kind == "action" then return end

        B[key] = round_if_needed(clamp(num, it.min, it.max), it.integer)
        selected = it.index

        if it.kind == "controller" then
            publish_native_state()
            return
        elseif it.kind == "morph" then
            apply_morph()
            publish_native_state()
            return
        elseif it.kind == "granularity" then
            step_mode = B[key]
            sync_granularity_item()
            publish_native_state()
            return
        end

        enforce_order(B, key)
        if it.kind == "property" then
            if not unsupported_properties[key] then
                local ok, err = mp.set_property_number(key, B[key])
                if not ok then
                    unsupported_properties[key] = true
                    native_last_error = "Property unavailable: " .. tostring(key) .. " (" .. tostring(err) .. ")"
                else
                    native_last_error = ""
                end
            end
            publish_native_state()
        else
            native_schedule_shader()
            publish_native_state()
        end
    end
    """.trimIndent()

  private val NATIVE_BRIDGE_TAIL =
    """

    -- mpvLab native bridge v2 registrations
    mp.register_script_message("p9lab-native-set", native_set_by_key)
    mp.register_script_message("p9lab-native-flush", function()
        if native_shader_timer then
            native_shader_timer:kill()
            native_shader_timer = nil
        end
        native_flush_shader()
    end)
    mp.register_script_message("p9lab-native-reset-all", function()
        reset_all()
        native_last_error = ""
        publish_native_state()
    end)
    mp.register_script_message("p9lab-native-revert-video-start", function()
        revert_video_start()
        native_last_error = ""
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
    mp.add_periodic_timer(1.00, publish_native_state)
    publish_native_state()
    """.trimIndent()
}
