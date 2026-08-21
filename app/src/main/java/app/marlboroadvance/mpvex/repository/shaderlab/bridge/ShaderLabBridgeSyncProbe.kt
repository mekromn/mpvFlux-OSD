package app.marlboroadvance.mpvex.repository.shaderlab.bridge

import java.io.File

internal fun interface ShaderLabBridgeSyncProbe {
  fun record(state: ShaderLabBackendState)
}

internal data object NoOpShaderLabBridgeSyncProbe : ShaderLabBridgeSyncProbe {
  override fun record(state: ShaderLabBackendState) = Unit
}

/**
 * R07 device-smoke proof that the Android MPV observer actually decoded a Lua
 * native-state snapshot. This is diagnostics only, lives under the canonical
 * user-visible logs directory, and never becomes a source of runtime state.
 */
internal class FileShaderLabBridgeSyncProbe(
  private val file: File = File("/storage/emulated/0/mpv/logs/shaderlab-r07-bridge-sync.txt"),
) : ShaderLabBridgeSyncProbe {
  private var lastSerial: Long = Long.MIN_VALUE

  @Synchronized
  override fun record(state: ShaderLabBackendState) {
    if (!state.ready || state.snapshotSerial == lastSerial) return
    lastSerial = state.snapshotSerial

    file.parentFile?.mkdirs()
    val temp = File(file.parentFile, ".${file.name}.tmp")
    val text = buildString {
      appendLine("status=PASS")
      appendLine("backend_version=${state.backendVersion.orEmpty()}")
      appendLine("snapshot_serial=${state.snapshotSerial}")
      appendLine("source_gamma=${state.sourceGamma.orEmpty()}")
      appendLine("source_kind=${state.sourceKind}")
      appendLine("sdr_eligible=${state.sdrEligible}")
      appendLine("active_bank=${state.activeBank}")
      appendLine("bypassed=${state.bypassed}")
      appendLine("preview_original=${state.previewOriginal}")
      appendLine("shader_slot=${state.shaderSlot}")
      appendLine("shader_swaps=${state.shaderSwapCount}")
      appendLine("apply_busy=${state.applyBusy}")
      appendLine("control_count=${state.values.size}")
      appendLine("user_preset_count=${state.userPresetOccupied.size}")
      appendLine("last_error=${state.lastError.orEmpty().replace('\n', ' ').replace('\r', ' ')}")
    }

    temp.writeText(text)
    if (!temp.renameTo(file)) {
      file.writeText(text)
      temp.delete()
    }
  }
}
