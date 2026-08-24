package app.marlboroadvance.mpvex.repository.shaderlab.catalog

import java.util.Locale
import kotlin.math.round

enum class ShaderLabControlId(val legacyKey: String) {
  SHADER_PROOF("SHADER_PROOF"),
  TOUCH_GRANULARITY("TOUCH_GRANULARITY"),
  LUMA_MASTER("LUMA_MASTER"),
  CHROMA_MASTER("CHROMA_MASTER"),
  SDR_INTENSITY("sdr-intensity"),
  MPV_BRIGHTNESS("brightness"),
  MPV_CONTRAST("contrast"),
  MPV_GAMMA("gamma"),
  MPV_SATURATION("saturation"),
  MPV_HUE("hue"),
  LUMA_PIVOT("LUMA_PIVOT"),
  LUMA_CONTRAST("LUMA_CONTRAST"),
  LUMA_HIGHLIGHT_START("LUMA_HIGHLIGHT_START"),
  LUMA_HIGHLIGHT_END("LUMA_HIGHLIGHT_END"),
  LUMA_HIGHLIGHT("LUMA_HIGHLIGHT"),
  SAT_L_FLOOR("SAT_L_FLOOR"),
  SAT_GATE_START("SAT_GATE_START"),
  SAT_GATE_FULL("SAT_GATE_FULL"),
  SHADOW_GATE_START("SHADOW_GATE_START"),
  SHADOW_GATE_FULL("SHADOW_GATE_FULL"),
  MIDTONE_START("MIDTONE_START"),
  MIDTONE_FULL("MIDTONE_FULL"),
  MIDTONE_FADE_START("MIDTONE_FADE_START"),
  MIDTONE_FADE_END("MIDTONE_FADE_END"),
  BRIGHT_START("BRIGHT_START"),
  BRIGHT_FULL("BRIGHT_FULL"),
  BASE_CHROMA("BASE_CHROMA"),
  MID_CHROMA("MID_CHROMA"),
  BRIGHT_CHROMA("BRIGHT_CHROMA"),
  SKIN_RETAIN("SKIN_RETAIN"),
  SKIN_CENTER("SKIN_CENTER"),
  SKIN_HUE_INNER("SKIN_HUE_INNER"),
  SKIN_HUE_OUTER("SKIN_HUE_OUTER"),
  SKIN_L_LOW_START("SKIN_L_LOW_START"),
  SKIN_L_LOW_FULL("SKIN_L_LOW_FULL"),
  SKIN_L_HIGH_START("SKIN_L_HIGH_START"),
  SKIN_L_HIGH_END("SKIN_L_HIGH_END"),
  SKIN_C_LOW_START("SKIN_C_LOW_START"),
  SKIN_C_LOW_FULL("SKIN_C_LOW_FULL"),
  SKIN_C_HIGH_START("SKIN_C_HIGH_START"),
  SKIN_C_HIGH_END("SKIN_C_HIGH_END"),
  RGB_LOW("RGB_LOW"),
  RGB_HIGH("RGB_HIGH"),
  GAMUT_MARGIN("GAMUT_MARGIN"),
  GAMUT_ITERATIONS("GAMUT_ITERATIONS"),
  SDR_COMPRESS("SDR_COMPRESS"),
  DEBUG_VIEW("DEBUG_VIEW"),
  GRAPH_VIEW("GRAPH_VIEW"),
  USER_SLOT("USER_SLOT"),
  BUILTIN_SLOT("BUILTIN_SLOT"),
  MORPH_FROM("MORPH_FROM"),
  MORPH_TO("MORPH_TO"),
  MORPH_AMOUNT("MORPH_AMOUNT"),
}

enum class ShaderLabGroup {
  MASTER,
  MPV,
  LUMA,
  CHROMA_GATES,
  COLOR_VOLUME,
  SKIN,
  GAMUT,
  OUTPUT,
  VIEW,
  PRESETS,
  MORPH,
  DIAGNOSTIC,
  CONTROL,
  COMPARE,
  SYSTEM,
}

enum class ShaderLabControlKind {
  SHADER,
  MPV_PROPERTY,
  VIRTUAL,
  CONTROLLER,
  GRANULARITY,
  MORPH,
}

enum class ShaderLabStepMode {
  FINE,
  NORMAL,
  COARSE,
}

enum class ShaderLabActionId(val legacyKey: String) {
  BYPASS("BYPASS_ACTION"),
  PREVIEW_TOGGLE_FALLBACK("PREVIEW_ACTION"),
  LOAD_USER("LOAD_USER"),
  SAVE_USER("SAVE_USER"),
  CLEAR_USER("CLEAR_USER"),
  LOAD_BUILTIN("LOAD_BUILTIN"),
  REVERT_VIDEO_START("REVERT_VIDEO_START"),
  RESET_ALL("RESET_ALL_MENU"),
  SAVE_STATE("SAVE_STATE_MENU"),
  LOAD_STATE("LOAD_STATE_MENU"),
}

sealed interface ShaderLabPresetId {
  data class User(val slot: Int) : ShaderLabPresetId {
    init {
      require(slot in 1..10) { "User preset slot must be 1..10: $slot" }
    }
  }

  data class BuiltIn(val slot: Int) : ShaderLabPresetId {
    init {
      require(slot in 1..10) { "Built-in preset slot must be 1..10: $slot" }
    }
  }

  data object VideoStart : ShaderLabPresetId
}

data class ShaderLabBuiltInPreset(
  val id: ShaderLabPresetId.BuiltIn,
  val name: String,
)

data class ShaderLabControlSpec(
  val id: ShaderLabControlId,
  val group: ShaderLabGroup,
  val kind: ShaderLabControlKind,
  val label: String,
  val defaultValue: Double,
  val minValue: Double,
  val maxValue: Double,
  val fineStep: Double,
  val normalStep: Double,
  val coarseStep: Double,
  val decimals: Int,
  val integer: Boolean = false,
  val percent: Boolean = false,
  val choices: List<String> = emptyList(),
  val presetEligible: Boolean = true,
) {
  init {
    require(label.isNotBlank())
    require(minValue.isFinite() && maxValue.isFinite() && defaultValue.isFinite())
    require(minValue <= maxValue)
    require(defaultValue in minValue..maxValue)
    require(fineStep > 0.0 && normalStep > 0.0 && coarseStep > 0.0)
    require(decimals >= 0)
    require(choices.isEmpty() || integer) { "Choice controls must use integer indices: $id" }
  }

  fun clamp(value: Double): Double {
    val bounded = value.coerceIn(minValue, maxValue)
    return if (integer) round(bounded) else bounded
  }

  fun step(mode: ShaderLabStepMode): Double =
    when (mode) {
      ShaderLabStepMode.FINE -> fineStep
      ShaderLabStepMode.NORMAL -> normalStep
      ShaderLabStepMode.COARSE -> coarseStep
    }

  fun format(value: Double): String {
    val clamped = clamp(value)
    return when {
      percent -> String.format(Locale.US, "%.0f%%", clamped * 100.0)
      integer -> String.format(Locale.US, "%.0f", clamped)
      else -> String.format(Locale.US, "%.${decimals}f", clamped)
    }
  }
}

data class ShaderLabActionSpec(
  val id: ShaderLabActionId,
  val group: ShaderLabGroup,
  val label: String,
  val legacyAction: String,
  val destructive: Boolean,
)

sealed interface ShaderLabControlRelationship {
  data class OrderedPair(
    val lower: ShaderLabControlId,
    val upper: ShaderLabControlId,
    val minimumGap: Double,
  ) : ShaderLabControlRelationship

  data class ScaledBy(
    val target: ShaderLabControlId,
    val master: ShaderLabControlId,
  ) : ShaderLabControlRelationship
}

/**
 * Authoritative typed metadata for Shader Lab controls.
 *
 * R05 intentionally models data only. R06 owns the semantic command API and
 * later steps own UI and MPV/Lua transport.
 */
object ShaderLabControlCatalog {
  const val VERSION = "legacy-v6.1.1-typed-1"

  val groupOrder: List<ShaderLabGroup> = listOf(
    ShaderLabGroup.MASTER,
    ShaderLabGroup.MPV,
    ShaderLabGroup.LUMA,
    ShaderLabGroup.CHROMA_GATES,
    ShaderLabGroup.COLOR_VOLUME,
    ShaderLabGroup.SKIN,
    ShaderLabGroup.GAMUT,
    ShaderLabGroup.OUTPUT,
    ShaderLabGroup.VIEW,
    ShaderLabGroup.PRESETS,
    ShaderLabGroup.MORPH,
    ShaderLabGroup.DIAGNOSTIC,
    ShaderLabGroup.CONTROL,
    ShaderLabGroup.COMPARE,
    ShaderLabGroup.SYSTEM,
  )

  val controls: List<ShaderLabControlSpec> = listOf(
    spec(ShaderLabControlId.SHADER_PROOF, ShaderLabGroup.DIAGNOSTIC, ShaderLabControlKind.VIRTUAL, "Shader reload proof (MAGENTA)", 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 0, integer = true),
    spec(ShaderLabControlId.TOUCH_GRANULARITY, ShaderLabGroup.CONTROL, ShaderLabControlKind.GRANULARITY, "Adjustment granularity", 2.0, 1.0, 3.0, 1.0, 1.0, 1.0, 0, integer = true, choices = listOf("FINE", "NORMAL", "COARSE")),
    spec(ShaderLabControlId.LUMA_MASTER, ShaderLabGroup.MASTER, ShaderLabControlKind.VIRTUAL, "Luma master", 1.0, 0.0, 2.0, 0.0025, 0.025, 0.10, 4),
    spec(ShaderLabControlId.CHROMA_MASTER, ShaderLabGroup.MASTER, ShaderLabControlKind.VIRTUAL, "Chroma master", 1.0, 0.0, 3.0, 0.0025, 0.025, 0.10, 4),
    spec(ShaderLabControlId.SDR_INTENSITY, ShaderLabGroup.MPV, ShaderLabControlKind.MPV_PROPERTY, "SDR intensity", 4.16, 0.10, 12.0, 0.01, 0.05, 0.25, 2),
    spec(ShaderLabControlId.MPV_BRIGHTNESS, ShaderLabGroup.MPV, ShaderLabControlKind.MPV_PROPERTY, "mpv brightness", 0.0, -100.0, 100.0, 0.25, 1.0, 5.0, 2),
    spec(ShaderLabControlId.MPV_CONTRAST, ShaderLabGroup.MPV, ShaderLabControlKind.MPV_PROPERTY, "mpv contrast", 0.0, -100.0, 100.0, 0.25, 1.0, 5.0, 2),
    spec(ShaderLabControlId.MPV_GAMMA, ShaderLabGroup.MPV, ShaderLabControlKind.MPV_PROPERTY, "mpv gamma", 0.0, -100.0, 100.0, 0.25, 1.0, 5.0, 2),
    spec(ShaderLabControlId.MPV_SATURATION, ShaderLabGroup.MPV, ShaderLabControlKind.MPV_PROPERTY, "mpv saturation", 0.0, -100.0, 100.0, 0.25, 1.0, 5.0, 2),
    spec(ShaderLabControlId.MPV_HUE, ShaderLabGroup.MPV, ShaderLabControlKind.MPV_PROPERTY, "mpv hue", 0.0, -100.0, 100.0, 0.25, 1.0, 5.0, 2),
    spec(ShaderLabControlId.LUMA_PIVOT, ShaderLabGroup.LUMA, ShaderLabControlKind.SHADER, "Luma pivot", 0.18, 0.05, 0.50, 0.0005, 0.0025, 0.01, 4),
    spec(ShaderLabControlId.LUMA_CONTRAST, ShaderLabGroup.LUMA, ShaderLabControlKind.SHADER, "Curve contrast", 0.28, -1.0, 1.5, 0.001, 0.005, 0.025, 4),
    spec(ShaderLabControlId.LUMA_HIGHLIGHT_START, ShaderLabGroup.LUMA, ShaderLabControlKind.SHADER, "Highlight gate start", 0.22, 0.0, 1.0, 0.001, 0.005, 0.025, 4),
    spec(ShaderLabControlId.LUMA_HIGHLIGHT_END, ShaderLabGroup.LUMA, ShaderLabControlKind.SHADER, "Highlight gate full", 0.92, 0.0, 1.0, 0.001, 0.005, 0.025, 4),
    spec(ShaderLabControlId.LUMA_HIGHLIGHT, ShaderLabGroup.LUMA, ShaderLabControlKind.SHADER, "Highlight lift", 0.129, -0.5, 1.0, 0.0005, 0.0025, 0.01, 4),
    spec(ShaderLabControlId.SAT_L_FLOOR, ShaderLabGroup.CHROMA_GATES, ShaderLabControlKind.SHADER, "Sat L floor", 0.080, 0.001, 0.50, 0.0005, 0.0025, 0.01, 4),
    spec(ShaderLabControlId.SAT_GATE_START, ShaderLabGroup.CHROMA_GATES, ShaderLabControlKind.SHADER, "Sat gate start", 0.025, 0.0, 1.0, 0.0005, 0.0025, 0.01, 4),
    spec(ShaderLabControlId.SAT_GATE_FULL, ShaderLabGroup.CHROMA_GATES, ShaderLabControlKind.SHADER, "Sat gate full", 0.260, 0.0, 1.5, 0.001, 0.005, 0.025, 4),
    spec(ShaderLabControlId.SHADOW_GATE_START, ShaderLabGroup.CHROMA_GATES, ShaderLabControlKind.SHADER, "Shadow gate start", 0.025, 0.0, 0.50, 0.0005, 0.0025, 0.01, 4),
    spec(ShaderLabControlId.SHADOW_GATE_FULL, ShaderLabGroup.CHROMA_GATES, ShaderLabControlKind.SHADER, "Shadow gate full", 0.120, 0.0, 0.75, 0.0005, 0.0025, 0.01, 4),
    spec(ShaderLabControlId.MIDTONE_START, ShaderLabGroup.COLOR_VOLUME, ShaderLabControlKind.SHADER, "Midtone start", 0.10, 0.0, 1.0, 0.001, 0.005, 0.025, 4),
    spec(ShaderLabControlId.MIDTONE_FULL, ShaderLabGroup.COLOR_VOLUME, ShaderLabControlKind.SHADER, "Midtone full", 0.30, 0.0, 1.0, 0.001, 0.005, 0.025, 4),
    spec(ShaderLabControlId.MIDTONE_FADE_START, ShaderLabGroup.COLOR_VOLUME, ShaderLabControlKind.SHADER, "Midtone fade start", 0.56, 0.0, 1.0, 0.001, 0.005, 0.025, 4),
    spec(ShaderLabControlId.MIDTONE_FADE_END, ShaderLabGroup.COLOR_VOLUME, ShaderLabControlKind.SHADER, "Midtone fade end", 0.80, 0.0, 1.0, 0.001, 0.005, 0.025, 4),
    spec(ShaderLabControlId.BRIGHT_START, ShaderLabGroup.COLOR_VOLUME, ShaderLabControlKind.SHADER, "Bright gate start", 0.34, 0.0, 1.0, 0.001, 0.005, 0.025, 4),
    spec(ShaderLabControlId.BRIGHT_FULL, ShaderLabGroup.COLOR_VOLUME, ShaderLabControlKind.SHADER, "Bright gate full", 0.90, 0.0, 1.0, 0.001, 0.005, 0.025, 4),
    spec(ShaderLabControlId.BASE_CHROMA, ShaderLabGroup.COLOR_VOLUME, ShaderLabControlKind.SHADER, "Base chroma", 0.0129, -0.50, 1.50, 0.00025, 0.001, 0.005, 5),
    spec(ShaderLabControlId.MID_CHROMA, ShaderLabGroup.COLOR_VOLUME, ShaderLabControlKind.SHADER, "Mid chroma", 0.05375, -0.50, 2.00, 0.00025, 0.001, 0.005, 5),
    spec(ShaderLabControlId.BRIGHT_CHROMA, ShaderLabGroup.COLOR_VOLUME, ShaderLabControlKind.SHADER, "Bright chroma", 0.252625, -0.50, 3.00, 0.0005, 0.0025, 0.01, 6),
    spec(ShaderLabControlId.SKIN_RETAIN, ShaderLabGroup.SKIN, ShaderLabControlKind.SHADER, "Skin boost retained", 0.22, 0.0, 1.0, 0.0025, 0.01, 0.05, 4),
    spec(ShaderLabControlId.SKIN_CENTER, ShaderLabGroup.SKIN, ShaderLabControlKind.SHADER, "Skin hue center", 0.87, -3.14159265, 3.14159265, 0.0025, 0.01, 0.05, 4),
    spec(ShaderLabControlId.SKIN_HUE_INNER, ShaderLabGroup.SKIN, ShaderLabControlKind.SHADER, "Skin hue inner", 0.24, 0.0, 3.14159265, 0.0025, 0.01, 0.05, 4),
    spec(ShaderLabControlId.SKIN_HUE_OUTER, ShaderLabGroup.SKIN, ShaderLabControlKind.SHADER, "Skin hue outer", 0.72, 0.0, 3.14159265, 0.0025, 0.01, 0.05, 4),
    spec(ShaderLabControlId.SKIN_L_LOW_START, ShaderLabGroup.SKIN, ShaderLabControlKind.SHADER, "Skin L low start", 0.28, 0.0, 1.0, 0.001, 0.005, 0.025, 4),
    spec(ShaderLabControlId.SKIN_L_LOW_FULL, ShaderLabGroup.SKIN, ShaderLabControlKind.SHADER, "Skin L low full", 0.46, 0.0, 1.0, 0.001, 0.005, 0.025, 4),
    spec(ShaderLabControlId.SKIN_L_HIGH_START, ShaderLabGroup.SKIN, ShaderLabControlKind.SHADER, "Skin L high start", 0.82, 0.0, 1.0, 0.001, 0.005, 0.025, 4),
    spec(ShaderLabControlId.SKIN_L_HIGH_END, ShaderLabGroup.SKIN, ShaderLabControlKind.SHADER, "Skin L high end", 0.96, 0.0, 1.0, 0.001, 0.005, 0.025, 4),
    spec(ShaderLabControlId.SKIN_C_LOW_START, ShaderLabGroup.SKIN, ShaderLabControlKind.SHADER, "Skin C low start", 0.018, 0.0, 0.50, 0.0005, 0.0025, 0.01, 4),
    spec(ShaderLabControlId.SKIN_C_LOW_FULL, ShaderLabGroup.SKIN, ShaderLabControlKind.SHADER, "Skin C low full", 0.050, 0.0, 0.50, 0.0005, 0.0025, 0.01, 4),
    spec(ShaderLabControlId.SKIN_C_HIGH_START, ShaderLabGroup.SKIN, ShaderLabControlKind.SHADER, "Skin C high start", 0.165, 0.0, 0.75, 0.0005, 0.0025, 0.01, 4),
    spec(ShaderLabControlId.SKIN_C_HIGH_END, ShaderLabGroup.SKIN, ShaderLabControlKind.SHADER, "Skin C high end", 0.255, 0.0, 0.75, 0.0005, 0.0025, 0.01, 4),
    spec(ShaderLabControlId.RGB_LOW, ShaderLabGroup.GAMUT, ShaderLabControlKind.SHADER, "RGB low boundary", 0.00005, 0.0, 0.02, 0.00001, 0.00005, 0.00025, 5),
    spec(ShaderLabControlId.RGB_HIGH, ShaderLabGroup.GAMUT, ShaderLabControlKind.SHADER, "RGB high boundary", 0.99995, 0.98, 1.0, 0.00001, 0.00005, 0.00025, 5),
    spec(ShaderLabControlId.GAMUT_MARGIN, ShaderLabGroup.GAMUT, ShaderLabControlKind.SHADER, "Gamut margin", 0.997, 0.90, 1.0, 0.0001, 0.0005, 0.0025, 4),
    spec(ShaderLabControlId.GAMUT_ITERATIONS, ShaderLabGroup.GAMUT, ShaderLabControlKind.SHADER, "Gamut iterations", 7.0, 1.0, 12.0, 1.0, 1.0, 1.0, 0, integer = true),
    spec(ShaderLabControlId.SDR_COMPRESS, ShaderLabGroup.OUTPUT, ShaderLabControlKind.SHADER, "HDR to SDR compression", 0.0, 0.0, 1.0, 0.01, 0.05, 0.10, 3, percent = true),
    spec(ShaderLabControlId.DEBUG_VIEW, ShaderLabGroup.VIEW, ShaderLabControlKind.SHADER, "Clipping indicator", 0.0, 0.0, 3.0, 1.0, 1.0, 1.0, 0, integer = true, choices = listOf("OFF", "GAMUT", "LUMA", "BOTH"), presetEligible = false),
    spec(ShaderLabControlId.GRAPH_VIEW, ShaderLabGroup.VIEW, ShaderLabControlKind.CONTROLLER, "Curve graph", 1.0, 0.0, 5.0, 1.0, 1.0, 1.0, 0, integer = true, choices = listOf("OFF", "AUTO", "TONE", "CHROMA", "MORPH", "HDR->SDR"), presetEligible = false),
    spec(ShaderLabControlId.USER_SLOT, ShaderLabGroup.PRESETS, ShaderLabControlKind.CONTROLLER, "User preset slot", 1.0, 1.0, 10.0, 1.0, 1.0, 1.0, 0, integer = true, presetEligible = false),
    spec(ShaderLabControlId.BUILTIN_SLOT, ShaderLabGroup.PRESETS, ShaderLabControlKind.CONTROLLER, "Built-in preset", 1.0, 1.0, 10.0, 1.0, 1.0, 1.0, 0, integer = true, presetEligible = false),
    spec(ShaderLabControlId.MORPH_FROM, ShaderLabGroup.MORPH, ShaderLabControlKind.CONTROLLER, "Morph from preset", 1.0, 1.0, 20.0, 1.0, 1.0, 1.0, 0, integer = true, presetEligible = false),
    spec(ShaderLabControlId.MORPH_TO, ShaderLabGroup.MORPH, ShaderLabControlKind.CONTROLLER, "Morph to preset", 2.0, 1.0, 20.0, 1.0, 1.0, 1.0, 0, integer = true, presetEligible = false),
    spec(ShaderLabControlId.MORPH_AMOUNT, ShaderLabGroup.MORPH, ShaderLabControlKind.MORPH, "Preset morph", 0.0, 0.0, 1.0, 0.01, 0.05, 0.10, 3, percent = true, presetEligible = false),
  )

  val actions: List<ShaderLabActionSpec> = listOf(
    ShaderLabActionSpec(ShaderLabActionId.BYPASS, ShaderLabGroup.COMPARE, "One-touch bypass comparison", "bypass", false),
    ShaderLabActionSpec(ShaderLabActionId.PREVIEW_TOGGLE_FALLBACK, ShaderLabGroup.COMPARE, "Original preview toggle (fallback)", "preview-toggle", false),
    ShaderLabActionSpec(ShaderLabActionId.LOAD_USER, ShaderLabGroup.PRESETS, "Load selected user preset", "load-user", true),
    ShaderLabActionSpec(ShaderLabActionId.SAVE_USER, ShaderLabGroup.PRESETS, "Save current to user preset", "save-user", true),
    ShaderLabActionSpec(ShaderLabActionId.CLEAR_USER, ShaderLabGroup.PRESETS, "Clear selected user preset", "clear-user", true),
    ShaderLabActionSpec(ShaderLabActionId.LOAD_BUILTIN, ShaderLabGroup.PRESETS, "Load selected built-in preset", "load-builtin", true),
    ShaderLabActionSpec(ShaderLabActionId.REVERT_VIDEO_START, ShaderLabGroup.SYSTEM, "Revert all to video-start state", "revert-video-start", true),
    ShaderLabActionSpec(ShaderLabActionId.RESET_ALL, ShaderLabGroup.SYSTEM, "Reset all tuning to V3.1 baseline", "reset-all", true),
    ShaderLabActionSpec(ShaderLabActionId.SAVE_STATE, ShaderLabGroup.SYSTEM, "Save complete Lab state", "save-state", false),
    ShaderLabActionSpec(ShaderLabActionId.LOAD_STATE, ShaderLabGroup.SYSTEM, "Load complete Lab state", "load-state", true),
  )

  val relationships: List<ShaderLabControlRelationship> = listOf(
    ShaderLabControlRelationship.OrderedPair(ShaderLabControlId.LUMA_HIGHLIGHT_START, ShaderLabControlId.LUMA_HIGHLIGHT_END, 0.000001),
    ShaderLabControlRelationship.OrderedPair(ShaderLabControlId.SAT_GATE_START, ShaderLabControlId.SAT_GATE_FULL, 0.000001),
    ShaderLabControlRelationship.OrderedPair(ShaderLabControlId.SHADOW_GATE_START, ShaderLabControlId.SHADOW_GATE_FULL, 0.000001),
    ShaderLabControlRelationship.OrderedPair(ShaderLabControlId.MIDTONE_START, ShaderLabControlId.MIDTONE_FULL, 0.000001),
    ShaderLabControlRelationship.OrderedPair(ShaderLabControlId.MIDTONE_FADE_START, ShaderLabControlId.MIDTONE_FADE_END, 0.000001),
    ShaderLabControlRelationship.OrderedPair(ShaderLabControlId.BRIGHT_START, ShaderLabControlId.BRIGHT_FULL, 0.000001),
    ShaderLabControlRelationship.OrderedPair(ShaderLabControlId.SKIN_HUE_INNER, ShaderLabControlId.SKIN_HUE_OUTER, 0.000001),
    ShaderLabControlRelationship.OrderedPair(ShaderLabControlId.SKIN_L_LOW_START, ShaderLabControlId.SKIN_L_LOW_FULL, 0.000001),
    ShaderLabControlRelationship.OrderedPair(ShaderLabControlId.SKIN_L_HIGH_START, ShaderLabControlId.SKIN_L_HIGH_END, 0.000001),
    ShaderLabControlRelationship.OrderedPair(ShaderLabControlId.SKIN_C_LOW_START, ShaderLabControlId.SKIN_C_LOW_FULL, 0.000001),
    ShaderLabControlRelationship.OrderedPair(ShaderLabControlId.SKIN_C_HIGH_START, ShaderLabControlId.SKIN_C_HIGH_END, 0.000001),
    ShaderLabControlRelationship.OrderedPair(ShaderLabControlId.RGB_LOW, ShaderLabControlId.RGB_HIGH, 0.000001),
    ShaderLabControlRelationship.ScaledBy(ShaderLabControlId.LUMA_CONTRAST, ShaderLabControlId.LUMA_MASTER),
    ShaderLabControlRelationship.ScaledBy(ShaderLabControlId.LUMA_HIGHLIGHT, ShaderLabControlId.LUMA_MASTER),
    ShaderLabControlRelationship.ScaledBy(ShaderLabControlId.BASE_CHROMA, ShaderLabControlId.CHROMA_MASTER),
    ShaderLabControlRelationship.ScaledBy(ShaderLabControlId.MID_CHROMA, ShaderLabControlId.CHROMA_MASTER),
    ShaderLabControlRelationship.ScaledBy(ShaderLabControlId.BRIGHT_CHROMA, ShaderLabControlId.CHROMA_MASTER),
  )

  val builtInPresets: List<ShaderLabBuiltInPreset> = listOf(
    ShaderLabBuiltInPreset(ShaderLabPresetId.BuiltIn(1), "V3.1 Reference"),
    ShaderLabBuiltInPreset(ShaderLabPresetId.BuiltIn(2), "Natural Plus"),
    ShaderLabBuiltInPreset(ShaderLabPresetId.BuiltIn(3), "Vivid Clean"),
    ShaderLabBuiltInPreset(ShaderLabPresetId.BuiltIn(4), "Cinema"),
    ShaderLabBuiltInPreset(ShaderLabPresetId.BuiltIn(5), "Daylight Punch"),
    ShaderLabBuiltInPreset(ShaderLabPresetId.BuiltIn(6), "Dark Room"),
    ShaderLabBuiltInPreset(ShaderLabPresetId.BuiltIn(7), "Animation"),
    ShaderLabBuiltInPreset(ShaderLabPresetId.BuiltIn(8), "Skin Priority"),
    ShaderLabBuiltInPreset(ShaderLabPresetId.BuiltIn(9), "Highlight Pop"),
    ShaderLabBuiltInPreset(ShaderLabPresetId.BuiltIn(10), "SDR Safe"),
  )

  val byId: Map<ShaderLabControlId, ShaderLabControlSpec> = controls.associateBy { it.id }
  val byLegacyKey: Map<String, ShaderLabControlSpec> = controls.associateBy { it.id.legacyKey }
  val actionsByLegacyKey: Map<String, ShaderLabActionSpec> = actions.associateBy { it.id.legacyKey }

  init {
    require(controls.size == ShaderLabControlId.entries.size)
    require(byId.size == controls.size)
    require(byLegacyKey.size == controls.size)
    require(actions.size == ShaderLabActionId.entries.size)
    require(actionsByLegacyKey.size == actions.size)
    require(builtInPresets.map { it.id.slot } == (1..10).toList())
    validateRelationships()
  }

  fun spec(id: ShaderLabControlId): ShaderLabControlSpec = byId.getValue(id)

  fun defaults(): Map<ShaderLabControlId, Double> = controls.associate { it.id to it.defaultValue }

  /** Applies catalog relationships after clamping; the changed control wins an ordered-pair conflict. */
  fun normalizeValues(
    input: Map<ShaderLabControlId, Double>,
    changedId: ShaderLabControlId? = null,
  ): Map<ShaderLabControlId, Double> {
    val values = defaults().toMutableMap()
    input.forEach { (id, value) -> values[id] = spec(id).clamp(value) }

    relationships.filterIsInstance<ShaderLabControlRelationship.OrderedPair>().forEach { relation ->
      val lowerSpec = spec(relation.lower)
      val upperSpec = spec(relation.upper)
      val lower = values.getValue(relation.lower)
      val upper = values.getValue(relation.upper)
      if (lower >= upper) {
        if (changedId == relation.lower) {
          values[relation.lower] = lowerSpec.clamp(upper - relation.minimumGap)
        } else {
          values[relation.upper] = upperSpec.clamp(lower + relation.minimumGap)
        }
      }
    }
    return values
  }

  /** Resolves legacy virtual-master scaling without hard-coded control names. */
  fun effectiveBackendValue(
    id: ShaderLabControlId,
    values: Map<ShaderLabControlId, Double>,
  ): Double {
    val normalized = normalizeValues(values)
    val base = normalized.getValue(id)
    val scale = relationships
      .filterIsInstance<ShaderLabControlRelationship.ScaledBy>()
      .firstOrNull { it.target == id }
      ?: return base
    return base * normalized.getValue(scale.master)
  }

  private fun validateRelationships() {
    relationships.forEach { relation ->
      when (relation) {
        is ShaderLabControlRelationship.OrderedPair -> {
          require(relation.lower != relation.upper)
          require(relation.minimumGap > 0.0)
          require(byId.containsKey(relation.lower) && byId.containsKey(relation.upper))
        }
        is ShaderLabControlRelationship.ScaledBy -> {
          require(relation.target != relation.master)
          require(byId.containsKey(relation.target) && byId.containsKey(relation.master))
        }
      }
    }
  }

  private fun spec(
    id: ShaderLabControlId,
    group: ShaderLabGroup,
    kind: ShaderLabControlKind,
    label: String,
    defaultValue: Double,
    minValue: Double,
    maxValue: Double,
    fineStep: Double,
    normalStep: Double,
    coarseStep: Double,
    decimals: Int,
    integer: Boolean = false,
    percent: Boolean = false,
    choices: List<String> = emptyList(),
    presetEligible: Boolean = true,
  ): ShaderLabControlSpec = ShaderLabControlSpec(
    id = id,
    group = group,
    kind = kind,
    label = label,
    defaultValue = defaultValue,
    minValue = minValue,
    maxValue = maxValue,
    fineStep = fineStep,
    normalStep = normalStep,
    coarseStep = coarseStep,
    decimals = decimals,
    integer = integer,
    percent = percent,
    choices = choices,
    presetEligible = presetEligible,
  )
}
