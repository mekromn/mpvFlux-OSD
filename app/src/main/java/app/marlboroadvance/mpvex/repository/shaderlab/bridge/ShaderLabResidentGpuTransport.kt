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
 * Ordinary slider movement is deliberately the shortest possible path:
 * one bare `glsl-shader-opts` property write and no synchronous read-back.
 * The production R08 probe performs verification after the gesture settles.
 * This mirrors the proven direct-property path used by mpv picture controls
 * and keeps JNI/property reads off the pointer-event hot path.
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
   * Publish the complete resident parameter set with one mpv write. Verification
   * is intentionally debounced outside this function so dragging cannot stall
   * playback on a synchronous JNI/property round-trip.
   */
  fun publish(values: Map<ShaderLabControlId, Double>) {
    val normalized = ShaderLabControlCatalog.normalizeValues(values)
    val nextOptions = encodeOptions(normalized)
    setOptionsFast(optionsForView(nextOptions))
    lastGoodValues = normalized
    lastGoodOptions = nextOptions
    authoritative = true
  }

  /**
   * Legacy preset/state actions may still alter Lua's value bank. Adopt that
   * bank at an explicit compatibility boundary, then publish it through the
   * same resident path. This is not a pointer-event hot path, so verify the
   * accepted option set synchronously here.
   */
  fun adoptLegacyValues(values: Map<ShaderLabControlId, Double>, sourceKind: ShaderLabSourceKind) {
    val previousValues = lastGoodValues
    val previousOptions = lastGoodOptions
    val previousAuthoritative = authoritative
    val nextValues = ShaderLabControlCatalog.normalizeValues(values)
    val nextOptions = encodeOptions(nextValues)

    try {
      lastGoodValues = nextValues
      lastGoodOptions = nextOptions
      authoritative = true

      if (
        sourceKind == ShaderLabSourceKind.SDR &&
          attachedSourceKind == ShaderLabSourceKind.SDR &&
          residentShaderIsAttached()
      ) {
        setAndVerifyOptions(optionsForView(nextOptions))
      } else {
        reconcileSource(sourceKind, force = true)
      }
    } catch (error: Throwable) {
      lastGoodValues = previousValues
      lastGoodOptions = previousOptions
      authoritative = previousAuthoritative

      if (
        sourceKind == ShaderLabSourceKind.SDR &&
          attachedSourceKind == ShaderLabSourceKind.SDR &&
          residentShaderIsAttached()
      ) {
        runCatching { setAndVerifyOptions(optionsForView(previousOptions)) }
      }
      throw error
    }
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
   * Comparison state is a private uniform branch, never a shader-list mutation.
   * Keep this latency-sensitive path to the same single bare property write.
   */
  fun setOriginalView(active: Boolean, sourceKind: ShaderLabSourceKind) {
    if (originalViewActive == active && sourceKind == attachedSourceKind) return
    originalViewActive = active
    if (sourceKind == ShaderLabSourceKind.SDR && residentShaderIsAttached()) {
      setOptionsFast(optionsForView(lastGoodOptions))
    } else {
      reconcileSource(sourceKind)
    }
  }

  fun reconcileSource(sourceKind: ShaderLabSourceKind, force: Boolean = false) {
    if (!force && sourceKind == attachedSourceKind) return

    when (sourceKind) {
      ShaderLabSourceKind.SDR -> {
        // Legacy generated runtime slots must never compete with R08.
        removeManagedShader(LEGACY_RUNTIME_A_PATH)
        removeManagedShader(LEGACY_RUNTIME_B_PATH)

        // Publish and verify before first attachment. Once attached, ordinary
        // PARAM/bypass changes use the no-readback fast path above.
        setAndVerifyOptions(optionsForView(lastGoodOptions))
        ensureResidentShaderAttached()
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

  private fun optionsForView(base: String): String =
    "$base,$INTERNAL_BYPASS_PARAM=${if (originalViewActive) 1 else 0}"

  /** Canonical runtime option property, matching the working mpv picture-control write path. */
  private fun setOptionsFast(options: String) {
    transport.command("set", GLSL_SHADER_OPTS_BARE_PROPERTY, options)
  }

  private fun setAndVerifyOptions(options: String) {
    setOptionsFast(options)
    verifyShaderOptions(options)
  }

  private fun ensureResidentShaderAttached() {
    if (residentShaderIsAttached()) return
    transport.command("change-list", GLSL_SHADERS_LIST_OPTION, "append", RESIDENT_SHADER_PATH)
    verifyResidentShaderAttached()
  }

  private fun residentShaderIsAttached(): Boolean {
    val shaderList =
      transport.getString(GLSL_SHADERS_PROPERTY)
        ?: transport.getString(GLSL_SHADERS_LIST_OPTION)
        ?: return false
    return shaderList.contains(RESIDENT_SHADER_PATH)
  }

  private fun verifyShaderOptions(expected: String) {
    val actual =
      transport.getString(GLSL_SHADER_OPTS_BARE_PROPERTY)
        ?: transport.getString(GLSL_SHADER_OPTS_PROPERTY)
        ?: run {
          if (
            transport is ShaderLabR08ProbedMpvTransport ||
              transport is ShaderLabR08DebouncedProbedMpvTransport
          ) {
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
    check(residentShaderIsAttached()) {
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
    // An absent path may report an error. That is harmless here.
    runCatching { transport.command("change-list", GLSL_SHADERS_LIST_OPTION, "remove", path) }
  }

  companion object {
    /** `options/...` remains useful as a read-back alias. */
    const val GLSL_SHADER_OPTS_PROPERTY = "options/glsl-shader-opts"
    /** Bare property is the canonical live-write path. */
    const val GLSL_SHADER_OPTS_BARE_PROPERTY = "glsl-shader-opts"
    const val GLSL_SHADERS_PROPERTY = "options/glsl-shaders"
    const val GLSL_SHADERS_LIST_OPTION = "glsl-shaders"
    const val INTERNAL_BYPASS_PARAM = "R08_BYPASS"
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
