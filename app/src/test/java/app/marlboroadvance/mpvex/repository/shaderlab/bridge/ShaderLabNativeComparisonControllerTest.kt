package app.marlboroadvance.mpvex.repository.shaderlab.bridge

import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlCatalog
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShaderLabNativeComparisonControllerTest {
  @Test
  fun bypassAndPreviewNeverMutateShaderList() {
    val mpv = FakeCompareMpvTransport()
    val resident = ShaderLabResidentGpuTransport(mpv)
    resident.initialize(ShaderLabControlCatalog.defaults(), ShaderLabSourceKind.SDR)
    val compare = ShaderLabNativeComparisonController(mpv, resident)
    compare.captureVideoStart(ShaderLabControlCatalog.defaults(), ShaderLabSourceKind.SDR)
    mpv.commands.clear()

    compare.toggleBypass(ShaderLabSourceKind.SDR)
    compare.toggleBypass(ShaderLabSourceKind.SDR)
    compare.setPreviewOriginal(true, ShaderLabSourceKind.SDR)
    compare.setPreviewOriginal(false, ShaderLabSourceKind.SDR)

    assertFalse(mpv.commands.any { it.firstOrNull() == "change-list" })
    val optionWrites = mpv.commands.filter { it.take(2) == listOf("set", "options/glsl-shader-opts") }
    assertTrue(optionWrites.any { it.last().contains("R08_BYPASS=1") })
    assertTrue(optionWrites.any { it.last().contains("R08_BYPASS=0") })
  }

  @Test
  fun originalViewPreservesTunedPictureBankWhileObserversReportOriginalValues() {
    val mpv = FakeCompareMpvTransport().apply {
      doubles[ShaderLabControlId.MPV_BRIGHTNESS.legacyKey] = 0.0
    }
    val resident = ShaderLabResidentGpuTransport(mpv)
    resident.initialize(ShaderLabControlCatalog.defaults(), ShaderLabSourceKind.SDR)
    val compare = ShaderLabNativeComparisonController(mpv, resident)
    compare.captureVideoStart(ShaderLabControlCatalog.defaults(), ShaderLabSourceKind.SDR)

    compare.setTunedValue(ShaderLabControlId.MPV_BRIGHTNESS, 20.0)
    assertEquals(20.0, mpv.doubles.getValue("brightness"))

    compare.toggleBypass(ShaderLabSourceKind.SDR)
    assertEquals(0.0, mpv.doubles.getValue("brightness"))

    compare.setTunedValue(ShaderLabControlId.MPV_BRIGHTNESS, 35.0)
    compare.adoptObservedTunedValue(ShaderLabControlId.MPV_BRIGHTNESS, 0.0)
    val overlaid = compare.overlayTunedValues(ShaderLabControlCatalog.defaults())
    assertEquals(35.0, overlaid.getValue(ShaderLabControlId.MPV_BRIGHTNESS))
    assertEquals(0.0, mpv.doubles.getValue("brightness"))

    compare.toggleBypass(ShaderLabSourceKind.SDR)
    assertEquals(35.0, mpv.doubles.getValue("brightness"))
  }

  @Test
  fun nonSdrCompareIsAStableNoOp() {
    val mpv = FakeCompareMpvTransport()
    val resident = ShaderLabResidentGpuTransport(mpv)
    resident.initialize(ShaderLabControlCatalog.defaults(), ShaderLabSourceKind.HDR_PQ)
    val compare = ShaderLabNativeComparisonController(mpv, resident)
    mpv.commands.clear()

    val state = compare.toggleBypass(ShaderLabSourceKind.HDR_PQ)

    assertFalse(state.bypassed)
    assertFalse(state.previewOriginal)
    assertTrue(mpv.commands.isEmpty())
  }
}

private class FakeCompareMpvTransport : ShaderLabMpvTransport {
  val commands = mutableListOf<List<String>>()
  val doubles = linkedMapOf<String, Double>()
  private val strings = linkedMapOf<String, String>()

  init {
    ShaderLabNativeComparisonController.PICTURE_PROPERTY_SPECS.forEach { spec ->
      doubles[spec.id.legacyKey] = spec.defaultValue
    }
    strings[ShaderLabResidentGpuTransport.GLSL_SHADERS_PROPERTY] =
      ShaderLabResidentGpuTransport.RESIDENT_SHADER_PATH
  }

  override fun attach(listener: (String, ShaderLabMpvValue) -> Unit) = Unit
  override fun detach() = Unit
  override fun observeString(property: String) = Unit
  override fun observeDouble(property: String) = Unit
  override fun getString(property: String): String? = strings[property]
  override fun getDouble(property: String): Double? = doubles[property]

  override fun command(vararg args: String) {
    val command = args.toList()
    commands += command
    when {
      command.size >= 3 && command[0] == "set" -> {
        val key = command[1]
        val value = command[2]
        if (key == ShaderLabResidentGpuTransport.GLSL_SHADER_OPTS_PROPERTY) {
          strings[key] = value
          strings[ShaderLabResidentGpuTransport.GLSL_SHADER_OPTS_BARE_PROPERTY] = value
        } else {
          value.toDoubleOrNull()?.let { doubles[key] = it }
        }
      }
      command.size >= 4 && command[0] == "change-list" && command[1] == ShaderLabResidentGpuTransport.GLSL_SHADERS_LIST_OPTION -> {
        val action = command[2]
        val path = command[3]
        val current = strings[ShaderLabResidentGpuTransport.GLSL_SHADERS_PROPERTY].orEmpty()
        val entries = current.split(':').filter { it.isNotBlank() }.toMutableList()
        when (action) {
          "append" -> if (path !in entries) entries += path
          "remove" -> entries.removeAll { it == path }
        }
        strings[ShaderLabResidentGpuTransport.GLSL_SHADERS_PROPERTY] = entries.joinToString(":")
      }
    }
  }
}
