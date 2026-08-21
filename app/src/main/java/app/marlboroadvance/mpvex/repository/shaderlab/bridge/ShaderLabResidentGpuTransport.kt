package app.marlboroadvance.mpvex.repository.shaderlab.bridge

import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlCatalog
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlId
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlKind
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlSpec
import java.util.Locale
import kotlin.math.roundToLong

/**
 * R08 resident vo=gpu parameter transport.
 *
 * Normal tuning writes one complete glsl-shader-opts value. It never writes a
 * shader file and never changes the glsl-shaders list. Shader attachment is
 * reconciled only when the playback source classification changes or when the
 * legacy Lua compatibility path intentionally changed a runtime shader.
 */
internal class ShaderLabResidentGpuTransport(
  private val transport: ShaderLabMpvTransport,
) {
  private var authoritative = false
  private var attachedSourceKind = ShaderLabSourceKind.NOT_READY
  private var lastGoodValues = ShaderLabControlCatalog.normalizeValues(ShaderLabControlCatalog.defaults())
  private var lastGoodOptions = encodeOptions(lastGoodValues)

  fun isAuthoritative(): Boolean = authoritative

  fun isResidentControl(id: ShaderLabControlId): Boolean = id in RESIDENT_CONTROL_IDS

  fun initialize(values: Map<ShaderLabControlId, Double>, sourceKind: ShaderLabSourceKind) {
    lastGoodValues = ShaderLabControlCatalog.normalizeValues(values)
    lastGoodOptions = encodeOptions(lastGoodValues)
    authoritative = true
    reconcileSource(sourceKind, force = true)
  }

  /**
   * Publish the complete resident parameter set. The old complete set is
   * restored on a synchronous transport failure; no GLSL file is touched.
   */
  fun publish(values: Map<ShaderLabControlId, Double>) {
    val normalized = ShaderLabControlCatalog.normalizeValues(values)
    val nextOptions = encodeOptions(normalized)
    val previousOptions = lastGoodOptions
    try {
      transport.command("set", GLSL_SHADER_OPTS_PROPERTY, nextOptions)
    } catch (error: Throwable) {
      runCatching { transport.command("set", GLSL_SHADER_OPTS_PROPERTY, previousOptions) }
      throw error
    }
    lastGoodValues = normalized
    lastGoodOptions = nextOptions
    authoritative = true
  }

  /**
   * Legacy preset/state actions may still alter Lua's value bank. Adopt that
   * bank only at an explicit compatibility boundary, then restore the single
   * resident shader as the active SDR shader.
   */
  fun adoptLegacyValues(values: Map<ShaderLabControlId, Double>, sourceKind: ShaderLabSourceKind) {
    lastGoodValues = ShaderLabControlCatalog.normalizeValues(values)
    lastGoodOptions = encodeOptions(lastGoodValues)
    authoritative = true
    reconcileSource(sourceKind, force = true)
  }

  /** Keeps Android-authoritative resident values from being overwritten by an old Lua snapshot. */
  fun overlayResidentValues(values: Map<ShaderLabControlId, Double>): Map<ShaderLabControlId, Double> {
    if (!authoritative) return values
    val merged = values.toMutableMap()
    RESIDENT_CONTROL_IDS.forEach { id ->
      lastGoodValues[id]?.let { merged[id] = it }
    }
    return merged
  }

  fun reconcileSource(sourceKind: ShaderLabSourceKind, force: Boolean = false) {
    if (!force && sourceKind == attachedSourceKind) return

    when (sourceKind) {
      ShaderLabSourceKind.SDR -> {
        // Remove only Shader Lab's legacy runtime slots; unrelated user shaders
        // remain untouched. Reattach the resident file once at this boundary.
        removeManagedShader(LEGACY_RUNTIME_A_PATH)
        removeManagedShader(LEGACY_RUNTIME_B_PATH)
        removeManagedShader(RESIDENT_SHADER_PATH)
        transport.command("change-list", "glsl-shaders", "append", RESIDENT_SHADER_PATH)
        transport.command("set", GLSL_SHADER_OPTS_PROPERTY, lastGoodOptions)
      }
      ShaderLabSourceKind.HDR_PQ,
      ShaderLabSourceKind.HDR_HLG -> {
        // SDR expansion must not survive an HDR source transition.
        removeManagedShader(RESIDENT_SHADER_PATH)
        removeManagedShader(LEGACY_RUNTIME_A_PATH)
        removeManagedShader(LEGACY_RUNTIME_B_PATH)
      }
      ShaderLabSourceKind.NOT_READY,
      ShaderLabSourceKind.UNKNOWN -> Unit
    }

    attachedSourceKind = sourceKind
  }

  fun onDetached() {
    attachedSourceKind = ShaderLabSourceKind.NOT_READY
  }

  private fun removeManagedShader(path: String) {
    // mpv may report an absent list entry as an error. That is harmless here.
    runCatching { transport.command("change-list", "glsl-shaders", "remove", path) }
  }

  companion object {
    const val GLSL_SHADER_OPTS_PROPERTY = "glsl-shader-opts"
    const val RESIDENT_SHADER_PATH =
      "/storage/emulated/0/mpv/shaders/pixel9-perceptual-expansion-resident-v3.1.glsl"
    const val LEGACY_RUNTIME_A_PATH =
      "/storage/emulated/0/mpv/shaders/pixel9-perceptual-expansion-runtime-a.glsl"
    const val LEGACY_RUNTIME_B_PATH =
      "/storage/emulated/0/mpv/shaders/pixel9-perceptual-expansion-runtime-b.glsl"

    val RESIDENT_CONTROL_SPECS: List<ShaderLabControlSpec> =
      ShaderLabControlCatalog.controls.filter { spec ->
        spec.kind == ShaderLabControlKind.SHADER ||
          spec.id == ShaderLabControlId.LUMA_MASTER ||
          spec.id == ShaderLabControlId.CHROMA_MASTER
      }

    val RESIDENT_CONTROL_IDS: Set<ShaderLabControlId> = RESIDENT_CONTROL_SPECS.mapTo(linkedSetOf()) { it.id }

    fun encodeOptions(values: Map<ShaderLabControlId, Double>): String {
      val normalized = ShaderLabControlCatalog.normalizeValues(values)
      return RESIDENT_CONTROL_SPECS.joinToString(",") { spec ->
        val value = normalized.getValue(spec.id)
        "${spec.id.legacyKey}=${formatParameterValue(spec, value)}"
      }
    }

    private fun formatParameterValue(spec: ShaderLabControlSpec, value: Double): String {
      val clamped = spec.clamp(value)
      return if (spec.integer) {
        clamped.roundToLong().toString()
      } else {
        String.format(Locale.US, "%.17g", clamped)
      }
    }
  }
}
