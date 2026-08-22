package app.marlboroadvance.mpvex.repository.shaderlab.bridge

import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlCatalog
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlId
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlKind
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlSpec
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * R08 resident vo=gpu parameter transport.
 *
 * Normal tuning writes one complete glsl-shader-opts value. It never writes a
 * shader file and never changes the glsl-shaders list. Shader attachment is
 * reconciled only when playback classification/comparison state changes or
 * when the legacy Lua compatibility path intentionally changed a runtime shader.
 *
 * Runtime option writes deliberately target mpv's explicit options/ property
 * namespace. The original R08 test build used the bare option name and updated
 * Android state optimistically; on-device testing showed values moving while
 * the rendered image stayed unchanged. Every production resident publish is now
 * read back from libmpv before Android accepts it as authoritative.
 */
internal class ShaderLabResidentGpuTransport(
  private val transport: ShaderLabMpvTransport,
) {
  private var authoritative = false
  private var attachedSourceKind = ShaderLabSourceKind.NOT_READY
  private var originalViewActive = false
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
   * restored on a synchronous transport/read-back failure; no GLSL file is touched.
   */
  fun publish(values: Map<ShaderLabControlId, Double>) {
    val normalized = ShaderLabControlCatalog.normalizeValues(values)
    val nextOptions = encodeOptions(normalized)
    val previousOptions = lastGoodOptions
    try {
      setShaderOptions(nextOptions)
      verifyShaderOptions(nextOptions)
    } catch (error: Throwable) {
      runCatching {
        setShaderOptions(previousOptions)
        verifyShaderOptions(previousOptions)
      }
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

  /**
   * Lua still owns original-property capture/restore for bypass and hold preview.
   * Mirror that comparison state for the resident shader itself.
   */
  fun setOriginalView(active: Boolean, sourceKind: ShaderLabSourceKind) {
    if (originalViewActive == active && sourceKind == attachedSourceKind) return
    originalViewActive = active
    reconcileSource(sourceKind, force = true)
  }

  fun reconcileSource(sourceKind: ShaderLabSourceKind, force: Boolean = false) {
    if (!force && sourceKind == attachedSourceKind) return

    when (sourceKind) {
      ShaderLabSourceKind.SDR -> {
        removeManagedShader(LEGACY_RUNTIME_A_PATH)
        removeManagedShader(LEGACY_RUNTIME_B_PATH)
        removeManagedShader(RESIDENT_SHADER_PATH)
        if (!originalViewActive) {
          // Set PARAM values before attaching the resident shader. vo=gpu reads
          // glsl-shader-opts while constructing the hook, so this ordering also
          // gives us a correct initial frame even on builds where a later option
          // mutation would not recreate an already-loaded hook.
          setShaderOptions(lastGoodOptions)
          verifyShaderOptions(lastGoodOptions)
          transport.command("change-list", GLSL_SHADERS_LIST_OPTION, "append", RESIDENT_SHADER_PATH)
          verifyResidentShaderAttached()
        }
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
    originalViewActive = false
  }

  private fun setShaderOptions(options: String) {
    transport.command("set", GLSL_SHADER_OPTS_PROPERTY, options)
  }

  private fun verifyShaderOptions(expected: String) {
    val actual =
      transport.getString(GLSL_SHADER_OPTS_PROPERTY)
        ?: transport.getString(GLSL_SHADER_OPTS_BARE_PROPERTY)
        ?: run {
          if (transport is ShaderLabR08ProbedMpvTransport) {
            error("mpv did not expose a glsl-shader-opts read-back after resident PARAM publish")
          }
          return
        }

    val expectedValues = parseOptions(expected)
    val actualValues = parseOptions(actual)
    expectedValues.forEach { (key, expectedValue) ->
      val actualValue = actualValues[key]
        ?: error("mpv glsl-shader-opts read-back is missing $key")
      val tolerance = max(1e-12, abs(expectedValue) * 1e-12)
      check(abs(actualValue - expectedValue) <= tolerance) {
        "mpv glsl-shader-opts rejected $key=$expectedValue (read back $actualValue)"
      }
    }
  }

  private fun verifyResidentShaderAttached() {
    val shaderList =
      transport.getString(GLSL_SHADERS_PROPERTY)
        ?: transport.getString(GLSL_SHADERS_LIST_OPTION)
        ?: run {
          if (transport is ShaderLabR08ProbedMpvTransport) {
            error("mpv did not expose a glsl-shaders read-back after resident shader attach")
          }
          return
        }
    check(shaderList.contains(RESIDENT_SHADER_PATH)) {
      "Resident shader was not attached by mpv: $RESIDENT_SHADER_PATH"
    }
  }

  private fun parseOptions(options: String): Map<String, Double> =
    options
      .split(',')
      .mapNotNull { token ->
        val key = token.substringBefore('=', missingDelimiterValue = "").trim()
        val value = token.substringAfter('=', missingDelimiterValue = "").trim().toDoubleOrNull()
        if (key.isBlank() || value == null) null else key to value
      }
      .toMap()

  private fun removeManagedShader(path: String) {
    // mpv may report an absent list entry as an error. That is harmless here.
    runCatching { transport.command("change-list", GLSL_SHADERS_LIST_OPTION, "remove", path) }
  }

  companion object {
    /** Explicit runtime option-property path; preferred over the bare option name. */
    const val GLSL_SHADER_OPTS_PROPERTY = "options/glsl-shader-opts"
    const val GLSL_SHADER_OPTS_BARE_PROPERTY = "glsl-shader-opts"
    const val GLSL_SHADERS_PROPERTY = "options/glsl-shaders"
    const val GLSL_SHADERS_LIST_OPTION = "glsl-shaders"
    const val RESIDENT_SHADER_PATH =
      "/storage/emulated/0/mpv/shaders/pixel9-perceptual-expansion-resident-v3.1.glsl"
    const val LEGACY_RUNTIME_A_PATH =
      "/storage/emulated/0/mpv/shaders/pixel9-perceptual-expansion-runtime-a.glsl"
    const val LEGACY_RUNTIME_B_PATH =
      "/storage/emulated/0/mpv/shaders/pixel9-perceptual-expansion-runtime-b.glsl"

    val RESIDENT_CONTROL_SPECS: List<ShaderLabControlSpec> =
      ShaderLabControlCatalog.controls.filter { spec ->
        spec.kind == ShaderLabControlKind.SHADER ||
          spec.id == ShaderLabControlId.SHADER_PROOF ||
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
