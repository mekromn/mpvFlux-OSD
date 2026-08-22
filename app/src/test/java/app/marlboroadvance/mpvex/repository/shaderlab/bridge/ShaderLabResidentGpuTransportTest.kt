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
  fun activeSdrPublishSetsOptionsThenRefreshesSameResidentHook() {
    val transport = FakeTransport()
    val gpu = ShaderLabResidentGpuTransport(transport)
    gpu.initialize(ShaderLabControlCatalog.defaults(), ShaderLabSourceKind.SDR)
    transport.commands.clear()

    val changed = ShaderLabControlCatalog.defaults().toMutableMap().apply {
      this[ShaderLabControlId.BRIGHT_CHROMA] = 0.3333333333333333
    }
    gpu.publish(changed)

    assertTrue(
      transport.commands.any {
        it.getOrNull(0) == "set" &&
          it.getOrNull(1) == ShaderLabResidentGpuTransport.GLSL_SHADER_OPTS_PROPERTY
      },
    )
    assertTrue(
      transport.commands.any {
        it == listOf(
          "change-list",
          ShaderLabResidentGpuTransport.GLSL_SHADERS_LIST_OPTION,
          "remove",
          ShaderLabResidentGpuTransport.RESIDENT_SHADER_PATH,
        )
      },
    )
    assertTrue(
      transport.commands.any {
        it == listOf(
          "change-list",
          ShaderLabResidentGpuTransport.GLSL_SHADERS_LIST_OPTION,
          "append",
          ShaderLabResidentGpuTransport.RESIDENT_SHADER_PATH,
        )
      },
    )
    assertTrue(
      transport.strings.getValue(ShaderLabResidentGpuTransport.GLSL_SHADERS_PROPERTY)
        .contains(ShaderLabResidentGpuTransport.RESIDENT_SHADER_PATH),
    )
  }

  @Test
  fun originalViewPublishUpdatesStoredOptionsWithoutReattachingShader() {
    val transport = FakeTransport()
    val gpu = ShaderLabResidentGpuTransport(transport)
    gpu.initialize(ShaderLabControlCatalog.defaults(), ShaderLabSourceKind.SDR)
    gpu.setOriginalView(true, ShaderLabSourceKind.SDR)
    transport.commands.clear()

    val changed = ShaderLabControlCatalog.defaults().toMutableMap().apply {
      this[ShaderLabControlId.CHROMA_MASTER] = 0.0
    }
    gpu.publish(changed)

    assertTrue(transport.commands.any { it.firstOrNull() == "set" })
    assertFalse(transport.commands.any { it.getOrNull(2) == "append" })
    assertFalse(
      transport.strings.getValue(ShaderLabResidentGpuTransport.GLSL_SHADERS_PROPERTY)
        .contains(ShaderLabResidentGpuTransport.RESIDENT_SHADER_PATH),
    )
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
    assertEquals(previous, transport.strings[ShaderLabResidentGpuTransport.GLSL_SHADER_OPTS_PROPERTY])
  }

  @Test
  fun sourceAndComparisonBoundariesOwnManagedShaderAttachment() {
    val transport = FakeTransport()
    val gpu = ShaderLabResidentGpuTransport(transport)
    gpu.initialize(ShaderLabControlCatalog.defaults(), ShaderLabSourceKind.SDR)

    assertTrue(
      transport.commands.contains(
        listOf(
          "change-list",
          ShaderLabResidentGpuTransport.GLSL_SHADERS_LIST_OPTION,
          "append",
          ShaderLabResidentGpuTransport.RESIDENT_SHADER_PATH,
        ),
      ),
    )
    assertTrue(
      transport.strings.getValue(ShaderLabResidentGpuTransport.GLSL_SHADERS_PROPERTY)
        .contains(ShaderLabResidentGpuTransport.RESIDENT_SHADER_PATH),
    )

    transport.commands.clear()
    gpu.setOriginalView(true, ShaderLabSourceKind.SDR)
    assertFalse(transport.commands.any { it.getOrNull(2) == "append" })
    assertTrue(
      transport.commands.any {
        it == listOf(
          "change-list",
          ShaderLabResidentGpuTransport.GLSL_SHADERS_LIST_OPTION,
          "remove",
          ShaderLabResidentGpuTransport.RESIDENT_SHADER_PATH,
        )
      },
    )

    transport.commands.clear()
    gpu.setOriginalView(false, ShaderLabSourceKind.SDR)
    assertTrue(
      transport.commands.any {
        it.getOrNull(2) == "append" &&
          it.getOrNull(3) == ShaderLabResidentGpuTransport.RESIDENT_SHADER_PATH
      },
    )

    transport.commands.clear()
    gpu.reconcileSource(ShaderLabSourceKind.HDR_PQ)
    assertFalse(transport.commands.any { it.getOrNull(2) == "append" })
    assertTrue(transport.commands.any { it.getOrNull(3) == ShaderLabResidentGpuTransport.RESIDENT_SHADER_PATH })
  }

  @Test
  fun publishRejectsAReadbackThatDidNotAcceptTheRequestedValue() {
    val transport = FakeTransport()
    val gpu = ShaderLabResidentGpuTransport(transport)
    val initial = ShaderLabControlCatalog.defaults()
    gpu.initialize(initial, ShaderLabSourceKind.NOT_READY)
    gpu.publish(initial)

    transport.corruptNextReadback = true
    val changed = initial.toMutableMap().apply {
      this[ShaderLabControlId.LUMA_CONTRAST] = 0.777
    }

    val result = runCatching { gpu.publish(changed) }

    assertTrue(result.isFailure)
  }

  private class FakeTransport : ShaderLabMpvTransport {
    val commands = mutableListOf<List<String>>()
    val strings = mutableMapOf<String, String>()
    var failNextSet = false
    var corruptNextReadback = false

    override fun attach(listener: (String, ShaderLabMpvValue) -> Unit) = Unit
    override fun detach() = Unit
    override fun observeString(property: String) = Unit
    override fun observeDouble(property: String) = Unit

    override fun getString(property: String): String? {
      val value = strings[property]
      if (corruptNextReadback && property == ShaderLabResidentGpuTransport.GLSL_SHADER_OPTS_PROPERTY) {
        corruptNextReadback = false
        return "LUMA_CONTRAST=0.123"
      }
      return value
    }

    override fun getDouble(property: String): Double? = null

    override fun command(vararg args: String) {
      if (
        failNextSet &&
          args.getOrNull(0) == "set" &&
          args.getOrNull(1) == ShaderLabResidentGpuTransport.GLSL_SHADER_OPTS_PROPERTY
      ) {
        failNextSet = false
        throw IllegalStateException("synthetic resident set failure")
      }

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
