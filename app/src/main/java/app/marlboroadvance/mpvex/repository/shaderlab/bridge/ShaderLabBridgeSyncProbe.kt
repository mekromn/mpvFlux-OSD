package app.marlboroadvance.mpvex.repository.shaderlab.bridge

import java.io.File

internal fun interface ShaderLabBridgeSyncProbe {
  fun record(state: ShaderLabBackendState)

  fun stage(name: String, detail: String = "") = Unit
}

internal data object NoOpShaderLabBridgeSyncProbe : ShaderLabBridgeSyncProbe {
  override fun record(state: ShaderLabBackendState) = Unit
}

/**
 * R07 device-smoke proof and staged activation diagnostic. The file is created
 * as soon as bridge activation begins, then atomically replaced with PASS once
 * Android decodes a real Lua native-state snapshot.
 */
internal class FileShaderLabBridgeSyncProbe(
  private val file: File = File("/storage/emulated/0/mpv/logs/shaderlab-r07-bridge-sync.txt"),
) : ShaderLabBridgeSyncProbe {
  private var lastSerial: Long = Long.MIN_VALUE

  @Synchronized
  override fun stage(name: String, detail: String) {
    writeAtomically(
      buildString {
        appendLine("status=WAITING")
        appendLine("stage=$name")
        appendLine("detail=${detail.replace('\n', ' ').replace('\r', ' ')}")
      },
    )
  }

  @Synchronized
  override fun record(state: ShaderLabBackendState) {
    if (!state.ready || state.snapshotSerial == lastSerial) return
    lastSerial = state.snapshotSerial

    writeAtomically(
      buildString {
        appendLine("status=PASS")
        appendLine("stage=snapshot_received")
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
      },
    )
  }

  private fun writeAtomically(text: String) {
    file.parentFile?.mkdirs()
    val temp = File(file.parentFile, ".${file.name}.tmp")
    temp.writeText(text)
    if (!temp.renameTo(file)) {
      file.writeText(text)
      temp.delete()
    }
  }
}
