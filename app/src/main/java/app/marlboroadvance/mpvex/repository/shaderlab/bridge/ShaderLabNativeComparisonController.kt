package app.marlboroadvance.mpvex.repository.shaderlab.bridge

import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlCatalog
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlId
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlKind
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlSpec
import java.util.Locale

/**
 * Owns R08 bypass / hold-original comparison without involving the legacy
 * shader-generation path.
 *
 * The resident shader never leaves glsl-shaders. Comparison flips only the
 * private R08_BYPASS PARAM and restores the direct mpv picture properties that
 * were present at video start. Tuned property values are kept separately, so
 * property-observer callbacks fired while ORIGINAL is visible cannot destroy
 * the live tuning bank.
 */
internal class ShaderLabNativeComparisonController(
  private val transport: ShaderLabMpvTransport,
  private val residentGpu: ShaderLabResidentGpuTransport,
) {
  data class State(
    val bypassed: Boolean = false,
    val previewOriginal: Boolean = false,
  ) {
    val originalViewActive: Boolean
      get() = bypassed || previewOriginal
  }

  private val originalProperties = linkedMapOf<ShaderLabControlId, Double>()
  private val tunedProperties = linkedMapOf<ShaderLabControlId, Double>()

  var state: State = State()
    private set

  fun captureVideoStart(
    fallbackValues: Map<ShaderLabControlId, Double>,
    sourceKind: ShaderLabSourceKind,
  ) {
    // A new video always starts in the tuned view. Do this without touching the
    // shader list; setOriginalView is only a private resident PARAM update.
    state = State()
    residentGpu.setOriginalView(false, sourceKind)

    originalProperties.clear()
    tunedProperties.clear()
    PICTURE_PROPERTY_SPECS.forEach { spec ->
      val direct = transport.getDouble(spec.id.legacyKey)
      val value = spec.clamp(direct ?: fallbackValues[spec.id] ?: spec.defaultValue)
      originalProperties[spec.id] = value
      tunedProperties[spec.id] = value
    }
  }

  fun seedTunedValues(values: Map<ShaderLabControlId, Double>) {
    PICTURE_PROPERTY_SPECS.forEach { spec ->
      values[spec.id]
        ?.takeIf { it.isFinite() }
        ?.let { tunedProperties[spec.id] = spec.clamp(it) }
    }
  }

  fun isPictureProperty(id: ShaderLabControlId): Boolean = id in PICTURE_PROPERTY_IDS

  fun setTunedValue(id: ShaderLabControlId, value: Double) {
    val spec = PICTURE_PROPERTY_BY_ID[id]
      ?: error("Not an mpv picture property: ${id.legacyKey}")
    val clamped = spec.clamp(value)
    tunedProperties[id] = clamped
    if (!state.originalViewActive) {
      setProperty(spec, clamped)
    }
  }

  fun adoptObservedTunedValue(id: ShaderLabControlId, value: Double) {
    if (state.originalViewActive || !value.isFinite()) return
    val spec = PICTURE_PROPERTY_BY_ID[id] ?: return
    tunedProperties[id] = spec.clamp(value)
  }

  fun overlayTunedValues(values: Map<ShaderLabControlId, Double>): Map<ShaderLabControlId, Double> {
    if (tunedProperties.isEmpty()) return values
    val merged = values.toMutableMap()
    tunedProperties.forEach { (id, value) -> merged[id] = value }
    return merged
  }

  fun toggleBypass(sourceKind: ShaderLabSourceKind): State {
    if (sourceKind != ShaderLabSourceKind.SDR || state.previewOriginal) return state
    return applyState(state.copy(bypassed = !state.bypassed), sourceKind)
  }

  fun setPreviewOriginal(active: Boolean, sourceKind: ShaderLabSourceKind): State {
    if (active && sourceKind != ShaderLabSourceKind.SDR) return state
    if (state.previewOriginal == active) return state
    return applyState(state.copy(previewOriginal = active), sourceKind)
  }

  fun togglePreviewOriginalFallback(sourceKind: ShaderLabSourceKind): State =
    setPreviewOriginal(!state.previewOriginal, sourceKind)

  fun forceTunedView(sourceKind: ShaderLabSourceKind): State =
    applyState(State(), sourceKind)

  fun onDetached() {
    state = State()
    originalProperties.clear()
    tunedProperties.clear()
  }

  private fun applyState(next: State, sourceKind: ShaderLabSourceKind): State {
    val wasOriginal = state.originalViewActive
    val willBeOriginal = next.originalViewActive

    if (wasOriginal == willBeOriginal) {
      state = next
      return state
    }

    if (willBeOriginal) {
      // Mark ORIGINAL authoritative before direct property writes. libmpv may
      // synchronously deliver property observers from command("set", ...), and
      // those callbacks must not overwrite the saved tuned bank with originals.
      state = next
      residentGpu.setOriginalView(true, sourceKind)
      applyProperties(originalProperties)
      return state
    }

    // Keep ORIGINAL authoritative while restoring tuned direct properties, so
    // any synchronous observer callbacks are ignored. Only after all tuned
    // properties are back do we expose the already-resident shader again.
    applyProperties(tunedProperties)
    residentGpu.setOriginalView(false, sourceKind)
    state = next
    return state
  }

  private fun applyProperties(values: Map<ShaderLabControlId, Double>) {
    PICTURE_PROPERTY_SPECS.forEach { spec ->
      values[spec.id]?.let { setProperty(spec, it) }
    }
  }

  private fun setProperty(spec: ShaderLabControlSpec, value: Double) {
    transport.command("set", spec.id.legacyKey, formatDouble(spec.clamp(value)))
  }

  companion object {
    val PICTURE_PROPERTY_SPECS: List<ShaderLabControlSpec> =
      ShaderLabControlCatalog.controls.filter { it.kind == ShaderLabControlKind.MPV_PROPERTY }

    val PICTURE_PROPERTY_IDS: Set<ShaderLabControlId> =
      PICTURE_PROPERTY_SPECS.mapTo(linkedSetOf()) { it.id }

    private val PICTURE_PROPERTY_BY_ID = PICTURE_PROPERTY_SPECS.associateBy { it.id }

    private fun formatDouble(value: Double): String = String.format(Locale.US, "%.17g", value)
  }
}
