package app.marlboroadvance.mpvex.repository.shaderlab.bridge

import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlCatalog
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlId
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShaderLabResidentGpuTransportTest {
  @Test
  fun residentCatalogContainsEveryShaderControlAndBothMastersExactlyOnce() {
    val expected =
      ShaderLabControlCatalog.controls
        .filter {
          it.kind == ShaderLabControlKind.SHADER ||
            it.id == ShaderLabControlId.LUMA_MASTER ||
            it.id == ShaderLabControlId.CHROMA_MASTER
        }
        .map { it.id }

    assertEquals(39, expected.size)
    assertEquals(expected, ShaderLabResidentGpuTransport.RESIDENT_CONTROL_SPECS.map { it.id })
    assertEquals(expected.toSet(), ShaderLabResidentGpuTransport.RESIDENT_CONTROL_IDS)
  }

  @Test
  fun completeOptionEncodingUsesAllParamsHighPrecisionAndIntegerSyntax() {
    val values = ShaderLabControlCatalog.defaults().toMutableMap().apply {
      this[ShaderLabControlId.LUMA_CONTRAST] = 0.3101234567890123
      this[ShaderLabControlId.GAMUT_ITERATIONS] = 11.0
      this[ShaderLabControlId.DEBUG_VIEW] = 3.0
    }

    val encoded = ShaderLabResidentGpuTransport.encodeOptions(values)
    val pairs = encoded.split(',')
    val keys = pairs.map { it.substringBefore('=') }

    assertEquals(39, pairs.size)
    assertEquals(39, keys.toSet().size)
    assertEquals(ShaderLabResidentGpuTransport.RESIDENT_CONTROL_SPECS.map { it.id.legacyKey }, keys)
    assertTrue(encoded.contains("LUMA_CONTRAST=0.310123456789012"))
    assertTrue(encoded.contains("GAMUT_ITERATIONS=11"))
    assertTrue(encoded.contains("DEBUG_VIEW=3"))
    assertFalse(encoded.contains("GAMUT_ITERATIONS=11.0"))
    assertFalse(encoded.contains("DEBUG_VIEW=3.0"))
  }

  @Test
  fun normalPublishChangesOnlyShaderOptsAndNeverTouchesShaderList() {
    val transport = FakeTransport()
    val gpu = ShaderLabResidentGpuTransport(transport)
    gpu.initialize(ShaderLabControlCatalog.defaults(), ShaderLabSourceKind.NOT_READY)
    transport.commands.clear()

    val changed = ShaderLabControlCatalog.defaults().toMutableMap().apply {
      this[ShaderLabControlId.BRIGHT_CHROMA] = 0.3333333333333333
    }
    gpu.publish(changed)

    assertEquals(1, transport.commands.size)
    assertEquals("set", transport.commands.single()[0])
    assertEquals(ShaderLabResidentGpuTransport.GLSL_SHADER_OPTS_PROPERTY, transport.commands.single()[1])
    assertFalse(transport.commands.any { it.firstOrNull() == "change-list" })
  }

  @Test
  fun synchronousPublishFailureAttemptsLastKnownGoodRollback() {
    val transport = FakeTransport()
    val gpu = ShaderLabResidentGpuTransport(transport)
    val initial = ShaderLabControlCatalog.defaults()
    gpu.initialize(initial, ShaderLabSourceKind.NOT_READY)
    gpu.publish(initial)
    val previous = transport.commands.last()[2]

    val changed = initial.toMutableMap().apply {
      this[ShaderLabControlId.LUMA_HIGHLIGHT] = 0.5
    }
    transport.failNextSet = true

    val result = runCatching { gpu.publish(changed) }

    assertTrue(result.isFailure)
    assertEquals(previous, transport.commands.last()[2])
  }

  @Test
  fun sourceAndComparisonBoundariesOwnManagedShaderAttachment() {
    val transport = FakeTransport()
    val gpu = ShaderLabResidentGpuTransport(transport)
    gpu.initialize(ShaderLabControlCatalog.defaults(), ShaderLabSourceKind.SDR)

    assertTrue(
      transport.commands.contains(
        listOf("change-list", "glsl-shaders", "append", ShaderLabResidentGpuTransport.RESIDENT_SHADER_PATH),
      ),
    )

    transport.commands.clear()
    gpu.setOriginalView(true, ShaderLabSourceKind.SDR)
    assertFalse(transport.commands.any { it.getOrNull(2) == "append" })
    assertTrue(transport.commands.any { it == listOf("change-list", "glsl-shaders", "remove", ShaderLabResidentGpuTransport.RESIDENT_SHADER_PATH) })

    transport.commands.clear()
    gpu.setOriginalView(false, ShaderLabSourceKind.SDR)
    assertTrue(transport.commands.any { it.getOrNull(2) == "append" && it.getOrNull(3) == ShaderLabResidentGpuTransport.RESIDENT_SHADER_PATH })

    transport.commands.clear()
    gpu.reconcileSource(ShaderLabSourceKind.HDR_PQ)
    assertFalse(transport.commands.any { it.getOrNull(2) == "append" })
    assertTrue(transport.commands.any { it.getOrNull(3) == ShaderLabResidentGpuTransport.RESIDENT_SHADER_PATH })
  }

  private class FakeTransport : ShaderLabMpvTransport {
    val commands = mutableListOf<List<String>>()
    var failNextSet = false

    override fun attach(listener: (String, ShaderLabMpvValue) -> Unit) = Unit
    override fun detach() = Unit
    override fun observeString(property: String) = Unit
    override fun observeDouble(property: String) = Unit
    override fun getString(property: String): String? = null
    override fun getDouble(property: String): Double? = null

    override fun command(vararg args: String) {
      if (failNextSet && args.getOrNull(0) == "set" && args.getOrNull(1) == ShaderLabResidentGpuTransport.GLSL_SHADER_OPTS_PROPERTY) {
        failNextSet = false
        throw IllegalStateException("synthetic resident set failure")
      }
      commands += args.toList()
    }
  }
}
