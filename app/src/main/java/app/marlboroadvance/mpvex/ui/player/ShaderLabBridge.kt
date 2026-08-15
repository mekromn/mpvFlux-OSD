package app.marlboroadvance.mpvex.ui.player

import `is`.xyz.mpv.MPVLib
import java.util.Locale

/** Single authoritative Android -> v6.1.1 Shader Lab controller bridge. */
object ShaderLabBridge {
  enum class Kind { SHADER, PROPERTY, VIRTUAL, CONTROLLER, GRANULARITY, MORPH }

  data class Control(
    val group: String,
    val kind: Kind,
    val key: String,
    val label: String,
    val default: Double,
    val min: Double,
    val max: Double,
    val fine: Double,
    val normal: Double,
    val coarse: Double,
    val decimals: Int,
    val integer: Boolean = false,
    val percent: Boolean = false,
    val choices: List<String> = emptyList(),
  ) {
    fun clamp(value: Double): Double = value.coerceIn(min, max).let { if (integer) kotlin.math.round(it) else it }
    fun format(value: Double): String =
      when {
        percent -> String.format(Locale.US, "%.0f%%", value * 100.0)
        integer -> String.format(Locale.US, "%.0f", value)
        else -> String.format(Locale.US, "%.${decimals}f", value)
      }
  }

  val controls = listOf(
    c("DIAGNOSTIC", Kind.VIRTUAL, "SHADER_PROOF", "Shader reload proof (MAGENTA)", 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 0, integer = true),
    c("CONTROL", Kind.GRANULARITY, "TOUCH_GRANULARITY", "Adjustment granularity", 2.0, 1.0, 3.0, 1.0, 1.0, 1.0, 0, integer = true, choices = listOf("FINE", "NORMAL", "COARSE")),
    c("MASTER", Kind.VIRTUAL, "LUMA_MASTER", "Luma master", 1.0, 0.0, 2.0, .0025, .025, .10, 4),
    c("MASTER", Kind.VIRTUAL, "CHROMA_MASTER", "Chroma master", 1.0, 0.0, 3.0, .0025, .025, .10, 4),

    c("MPV", Kind.PROPERTY, "sdr-intensity", "SDR intensity", 4.16, .10, 12.0, .01, .05, .25, 2),
    c("MPV", Kind.PROPERTY, "brightness", "mpv brightness", 0.0, -100.0, 100.0, .25, 1.0, 5.0, 2),
    c("MPV", Kind.PROPERTY, "contrast", "mpv contrast", 0.0, -100.0, 100.0, .25, 1.0, 5.0, 2),
    c("MPV", Kind.PROPERTY, "gamma", "mpv gamma", 0.0, -100.0, 100.0, .25, 1.0, 5.0, 2),
    c("MPV", Kind.PROPERTY, "saturation", "mpv saturation", 0.0, -100.0, 100.0, .25, 1.0, 5.0, 2),
    c("MPV", Kind.PROPERTY, "hue", "mpv hue", 0.0, -100.0, 100.0, .25, 1.0, 5.0, 2),

    c("LUMA", Kind.SHADER, "LUMA_PIVOT", "Luma pivot", .18, .05, .50, .0005, .0025, .01, 4),
    c("LUMA", Kind.SHADER, "LUMA_CONTRAST", "Curve contrast", .28, -1.0, 1.5, .001, .005, .025, 4),
    c("LUMA", Kind.SHADER, "LUMA_HIGHLIGHT_START", "Highlight gate start", .22, 0.0, 1.0, .001, .005, .025, 4),
    c("LUMA", Kind.SHADER, "LUMA_HIGHLIGHT_END", "Highlight gate full", .92, 0.0, 1.0, .001, .005, .025, 4),
    c("LUMA", Kind.SHADER, "LUMA_HIGHLIGHT", "Highlight lift", .129, -.5, 1.0, .0005, .0025, .01, 4),

    c("CHROMA GATES", Kind.SHADER, "SAT_L_FLOOR", "Sat L floor", .080, .001, .50, .0005, .0025, .01, 4),
    c("CHROMA GATES", Kind.SHADER, "SAT_GATE_START", "Sat gate start", .025, 0.0, 1.0, .0005, .0025, .01, 4),
    c("CHROMA GATES", Kind.SHADER, "SAT_GATE_FULL", "Sat gate full", .260, 0.0, 1.5, .001, .005, .025, 4),
    c("CHROMA GATES", Kind.SHADER, "SHADOW_GATE_START", "Shadow gate start", .025, 0.0, .50, .0005, .0025, .01, 4),
    c("CHROMA GATES", Kind.SHADER, "SHADOW_GATE_FULL", "Shadow gate full", .120, 0.0, .75, .0005, .0025, .01, 4),

    c("COLOR VOLUME", Kind.SHADER, "MIDTONE_START", "Midtone start", .10, 0.0, 1.0, .001, .005, .025, 4),
    c("COLOR VOLUME", Kind.SHADER, "MIDTONE_FULL", "Midtone full", .30, 0.0, 1.0, .001, .005, .025, 4),
    c("COLOR VOLUME", Kind.SHADER, "MIDTONE_FADE_START", "Midtone fade start", .56, 0.0, 1.0, .001, .005, .025, 4),
    c("COLOR VOLUME", Kind.SHADER, "MIDTONE_FADE_END", "Midtone fade end", .80, 0.0, 1.0, .001, .005, .025, 4),
    c("COLOR VOLUME", Kind.SHADER, "BRIGHT_START", "Bright gate start", .34, 0.0, 1.0, .001, .005, .025, 4),
    c("COLOR VOLUME", Kind.SHADER, "BRIGHT_FULL", "Bright gate full", .90, 0.0, 1.0, .001, .005, .025, 4),
    c("COLOR VOLUME", Kind.SHADER, "BASE_CHROMA", "Base chroma", .0129, -.50, 1.50, .00025, .001, .005, 5),
    c("COLOR VOLUME", Kind.SHADER, "MID_CHROMA", "Mid chroma", .05375, -.50, 2.00, .00025, .001, .005, 5),
    c("COLOR VOLUME", Kind.SHADER, "BRIGHT_CHROMA", "Bright chroma", .252625, -.50, 3.00, .0005, .0025, .01, 6),

    c("SKIN", Kind.SHADER, "SKIN_RETAIN", "Skin boost retained", .22, 0.0, 1.0, .0025, .01, .05, 4),
    c("SKIN", Kind.SHADER, "SKIN_CENTER", "Skin hue center", .87, -3.14159265, 3.14159265, .0025, .01, .05, 4),
    c("SKIN", Kind.SHADER, "SKIN_HUE_INNER", "Skin hue inner", .24, 0.0, 3.14159265, .0025, .01, .05, 4),
    c("SKIN", Kind.SHADER, "SKIN_HUE_OUTER", "Skin hue outer", .72, 0.0, 3.14159265, .0025, .01, .05, 4),
    c("SKIN", Kind.SHADER, "SKIN_L_LOW_START", "Skin L low start", .28, 0.0, 1.0, .001, .005, .025, 4),
    c("SKIN", Kind.SHADER, "SKIN_L_LOW_FULL", "Skin L low full", .46, 0.0, 1.0, .001, .005, .025, 4),
    c("SKIN", Kind.SHADER, "SKIN_L_HIGH_START", "Skin L high start", .82, 0.0, 1.0, .001, .005, .025, 4),
    c("SKIN", Kind.SHADER, "SKIN_L_HIGH_END", "Skin L high end", .96, 0.0, 1.0, .001, .005, .025, 4),
    c("SKIN", Kind.SHADER, "SKIN_C_LOW_START", "Skin C low start", .018, 0.0, .50, .0005, .0025, .01, 4),
    c("SKIN", Kind.SHADER, "SKIN_C_LOW_FULL", "Skin C low full", .050, 0.0, .50, .0005, .0025, .01, 4),
    c("SKIN", Kind.SHADER, "SKIN_C_HIGH_START", "Skin C high start", .165, 0.0, .75, .0005, .0025, .01, 4),
    c("SKIN", Kind.SHADER, "SKIN_C_HIGH_END", "Skin C high end", .255, 0.0, .75, .0005, .0025, .01, 4),

    c("GAMUT", Kind.SHADER, "RGB_LOW", "RGB low boundary", .00005, 0.0, .02, .00001, .00005, .00025, 5),
    c("GAMUT", Kind.SHADER, "RGB_HIGH", "RGB high boundary", .99995, .98, 1.0, .00001, .00005, .00025, 5),
    c("GAMUT", Kind.SHADER, "GAMUT_MARGIN", "Gamut margin", .997, .90, 1.0, .0001, .0005, .0025, 4),
    c("GAMUT", Kind.SHADER, "GAMUT_ITERATIONS", "Gamut iterations", 7.0, 1.0, 12.0, 1.0, 1.0, 1.0, 0, integer = true),

    c("OUTPUT", Kind.SHADER, "SDR_COMPRESS", "HDR to SDR compression", 0.0, 0.0, 1.0, .01, .05, .10, 3, percent = true),
    c("VIEW", Kind.SHADER, "DEBUG_VIEW", "Clipping indicator", 0.0, 0.0, 3.0, 1.0, 1.0, 1.0, 0, integer = true, choices = listOf("OFF", "GAMUT", "LUMA", "BOTH")),
    c("VIEW", Kind.CONTROLLER, "GRAPH_VIEW", "Curve graph", 1.0, 0.0, 5.0, 1.0, 1.0, 1.0, 0, integer = true, choices = listOf("OFF", "AUTO", "TONE", "CHROMA", "MORPH", "HDR->SDR")),
    c("PRESETS", Kind.CONTROLLER, "USER_SLOT", "User preset slot", 1.0, 1.0, 10.0, 1.0, 1.0, 1.0, 0, integer = true),
    c("PRESETS", Kind.CONTROLLER, "BUILTIN_SLOT", "Built-in preset", 1.0, 1.0, 10.0, 1.0, 1.0, 1.0, 0, integer = true),
    c("MORPH", Kind.CONTROLLER, "MORPH_FROM", "Morph from preset", 1.0, 1.0, 20.0, 1.0, 1.0, 1.0, 0, integer = true),
    c("MORPH", Kind.CONTROLLER, "MORPH_TO", "Morph to preset", 2.0, 1.0, 20.0, 1.0, 1.0, 1.0, 0, integer = true),
    c("MORPH", Kind.MORPH, "MORPH_AMOUNT", "Preset morph", 0.0, 0.0, 1.0, .01, .05, .10, 3, percent = true),
  )

  val groups = listOf(
    "MASTER", "MPV", "LUMA", "CHROMA GATES", "COLOR VOLUME", "SKIN", "GAMUT",
    "OUTPUT", "VIEW", "PRESETS", "MORPH", "DIAGNOSTIC", "CONTROL", "SYSTEM",
  )

  val builtInPresetNames = listOf(
    "V3.1 Reference", "Natural Plus", "Vivid Clean", "Cinema", "Daylight Punch",
    "Dark Room", "Animation", "Skin Priority", "Highlight Pop", "SDR Safe",
  )

  fun set(key: String, value: Double) {
    val control = controls.firstOrNull { it.key == key } ?: return
    val clamped = control.clamp(value)
    MPVLib.command("script-message", "p9lab-set", key, String.format(Locale.US, "%.17g", clamped))
  }

  fun previewStart() = MPVLib.command("script-message", "p9lab-preview-start")
  fun previewEnd() = MPVLib.command("script-message", "p9lab-preview-end")
  fun bypass() = MPVLib.command("script-message", "p9lab-bypass")
  fun userSave(slot: Int) = MPVLib.command("script-message", "p9lab-user-save", slot.coerceIn(1, 10).toString())
  fun userLoad(slot: Int) = MPVLib.command("script-message", "p9lab-user-load", slot.coerceIn(1, 10).toString())
  fun userClear(slot: Int) = MPVLib.command("script-message", "p9lab-user-clear", slot.coerceIn(1, 10).toString())
  fun builtinLoad(slot: Int) = MPVLib.command("script-message", "p9lab-builtin-load", slot.coerceIn(1, 10).toString())
  fun morph(from: Int, to: Int, amount: Double) =
    MPVLib.command(
      "script-message",
      "p9lab-morph",
      from.coerceIn(1, 20).toString(),
      to.coerceIn(1, 20).toString(),
      String.format(Locale.US, "%.6f", amount.coerceIn(0.0, 1.0)),
    )
  fun revertVideoStart() = MPVLib.command("script-message", "p9lab-native-revert-video-start")
  fun resetAll() = MPVLib.command("script-message", "p9lab-native-reset-all")
  fun saveState() = MPVLib.command("script-message", "p9lab-native-save-state")
  fun loadState() = MPVLib.command("script-message", "p9lab-native-load-state")
  fun status() = MPVLib.command("script-message", "p9lab-status")

  fun readState(): Map<String, String> =
    MPVLib.getPropertyString("user-data/p9lab/native-state")
      ?.lineSequence()
      ?.mapNotNull { line ->
        val i = line.indexOf('=')
        if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
      }?.toMap()
      .orEmpty()

  private fun c(
    group: String,
    kind: Kind,
    key: String,
    label: String,
    default: Double,
    min: Double,
    max: Double,
    fine: Double,
    normal: Double,
    coarse: Double,
    decimals: Int,
    integer: Boolean = false,
    percent: Boolean = false,
    choices: List<String> = emptyList(),
  ) = Control(group, kind, key, label, default, min, max, fine, normal, coarse, decimals, integer, percent, choices)
}
