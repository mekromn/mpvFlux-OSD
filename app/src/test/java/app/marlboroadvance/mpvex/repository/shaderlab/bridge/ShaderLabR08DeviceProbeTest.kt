package app.marlboroadvance.mpvex.repository.shaderlab.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShaderLabR08DeviceProbeTest {
  @Test
  fun residentPublishIsTimedReadBackAndForwardedUnchanged() {
    val delegate = FakeTransport()
    val probe = RecordingProbe()
    val times = ArrayDeque(listOf(100L, 160L, 220L))
    val transport =
      ShaderLabR08ProbedMpvTransport(
        delegate = delegate,
        probe = probe,
        nanoTime = { times.removeFirst() },
      )

    transport.command("set", ShaderLabResidentGpuTransport.GLSL_SHADER_OPTS_PROPERTY, "LUMA_CONTRAST=0.31")

    assertEquals(
      listOf("set", ShaderLabResidentGpuTransport.GLSL_SHADER_OPTS_PROPERTY, "LUMA_CONTRAST=0.31"),
      delegate.commands.single(),
    )
    val sample = probe.samples.single()
    assertEquals("LUMA_CONTRAST=0.31", sample.requestedOptions)
    assertEquals("LUMA_CONTRAST=0.31", sample.readbackOptions)
    assertEquals(60L, sample.commandLatencyNanos)
    assertEquals(120L, sample.setAndReadbackLatencyNanos)
  }

  @Test
  fun nonResidentCommandsAreNotReportedAsParameterPublishes() {
    val delegate = FakeTransport()
    val probe = RecordingProbe()
    val transport = ShaderLabR08ProbedMpvTransport(delegate = delegate, probe = probe)

    transport.command("set", "brightness", "4.25")

    assertTrue(probe.samples.isEmpty())
    assertEquals(listOf("set", "brightness", "4.25"), delegate.commands.single())
  }

  @Test
  fun shaderListMutationsAreReportedWithoutChangingTheCommand() {
    val delegate = FakeTransport()
    val probe = RecordingProbe()
    val transport = ShaderLabR08ProbedMpvTransport(delegate = delegate, probe = probe)

    transport.command("change-list", "glsl-shaders", "append", ShaderLabResidentGpuTransport.RESIDENT_SHADER_PATH)

    assertEquals(1, probe.shaderListMutations)
    assertEquals(
      listOf("change-list", "glsl-shaders", "append", ShaderLabResidentGpuTransport.RESIDENT_SHADER_PATH),
      delegate.commands.single(),
    )
  }

  private class RecordingProbe : ShaderLabR08ResidentProbe {
    val samples = mutableListOf<ShaderLabR08ResidentPublishSample>()
    var shaderListMutations = 0

    override fun shaderListMutation() {
      shaderListMutations += 1
    }

    override fun residentPublish(sample: ShaderLabR08ResidentPublishSample) {
      samples += sample
    }
  }

  private class FakeTransport : ShaderLabMpvTransport {
    val commands = mutableListOf<List<String>>()
    val strings = mutableMapOf<String, String>()
    val doubles = mutableMapOf<String, Double>()

    override fun attach(listener: (String, ShaderLabMpvValue) -> Unit) = Unit

    override fun detach() = Unit

    override fun observeString(property: String) = Unit

    override fun observeDouble(property: String) = Unit

    override fun getString(property: String): String? = strings[property]

    override fun getDouble(property: String): Double? = doubles[property]

    override fun command(vararg args: String) {
      commands += args.toList()
      if (args.getOrNull(0) == "set" && args.size >= 3) {
        strings[args[1]] = args[2]
      }
    }
  }
}
