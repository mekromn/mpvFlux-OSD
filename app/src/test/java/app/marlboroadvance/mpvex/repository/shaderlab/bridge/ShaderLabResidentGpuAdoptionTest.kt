package app.marlboroadvance.mpvex.repository.shaderlab.bridge

import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlCatalog
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShaderLabResidentGpuAdoptionTest {
  @Test
  fun failedLegacyAdoptionRollsBackWithoutShaderListChurn() {
    val transport = AdoptionFakeTransport()
    val gpu = ShaderLabResidentGpuTransport(transport)
    val initial = ShaderLabControlCatalog.defaults()
    gpu.initialize(initial, ShaderLabSourceKind.SDR)

    val previous = transport.strings.getValue(ShaderLabResidentGpuTransport.GLSL_SHADER_OPTS_PROPERTY)
    transport.commands.clear()

    val changed = initial.toMutableMap().apply {
      this[ShaderLabControlId.LUMA_CONTRAST] = 0.777
    }
    transport.corruptNextReadback = true

    val result = runCatching { gpu.adoptLegacyValues(changed, ShaderLabSourceKind.SDR) }

    assertTrue(result.isFailure)
    assertFalse(transport.commands.any { it.firstOrNull() == "change-list" })
    assertEquals(previous, transport.commands.last()[2])
    assertEquals(previous, transport.strings[ShaderLabResidentGpuTransport.GLSL_SHADER_OPTS_PROPERTY])
    assertEquals(
      initial[ShaderLabControlId.LUMA_CONTRAST],
      gpu.overlayResidentValues(changed)[ShaderLabControlId.LUMA_CONTRAST],
    )
  }

  private class AdoptionFakeTransport : ShaderLabMpvTransport {
    val commands = mutableListOf<List<String>>()
    val strings = mutableMapOf<String, String>()
    var corruptNextReadback = false

    override fun attach(listener: (String, ShaderLabMpvValue) -> Unit) = Unit
    override fun detach() = Unit
    override fun observeString(property: String) = Unit
    override fun observeDouble(property: String) = Unit

    override fun getString(property: String): String? {
      val value = strings[property]
      if (
        corruptNextReadback &&
          property == ShaderLabResidentGpuTransport.GLSL_SHADER_OPTS_PROPERTY
      ) {
        corruptNextReadback = false
        return "LUMA_CONTRAST=0.123"
      }
      return value
    }

    override fun getDouble(property: String): Double? = null

    override fun command(vararg args: String) {
      commands += args.toList()
      when {
        args.getOrNull(0) == "set" && args.size >= 3 -> {
          strings[args[1]] = args[2]
          if (args[1] == ShaderLabResidentGpuTransport.GLSL_SHADER_OPTS_PROPERTY) {
            strings[ShaderLabResidentGpuTransport.GLSL_SHADER_OPTS_BARE_PROPERTY] = args[2]
          }
        }

        args.getOrNull(0) == "change-list" &&
          args.getOrNull(1) == ShaderLabResidentGpuTransport.GLSL_SHADERS_LIST_OPTION &&
          args.size >= 4 -> {
          val current =
            strings[ShaderLabResidentGpuTransport.GLSL_SHADERS_PROPERTY]
              .orEmpty()
              .split(':')
              .filter { it.isNotBlank() }
              .toMutableList()
          when (args[2]) {
            "append" -> if (args[3] !in current) current += args[3]
            "remove" -> current.removeAll { it == args[3] }
          }
          val joined = current.joinToString(":")
          strings[ShaderLabResidentGpuTransport.GLSL_SHADERS_PROPERTY] = joined
          strings[ShaderLabResidentGpuTransport.GLSL_SHADERS_LIST_OPTION] = joined
        }
      }
    }
  }
}
