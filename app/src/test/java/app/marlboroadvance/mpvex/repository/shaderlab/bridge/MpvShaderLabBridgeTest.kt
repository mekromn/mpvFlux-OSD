package app.marlboroadvance.mpvex.repository.shaderlab.bridge

import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlId
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabPresetId
import app.marlboroadvance.mpvex.repository.shaderlab.command.ShaderLabCommand
import app.marlboroadvance.mpvex.repository.shaderlab.command.ShaderLabCommandApi
import app.marlboroadvance.mpvex.repository.shaderlab.command.ShaderLabCommandResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvShaderLabBridgeTest {
  @Test
  fun attachObservesNativeStateGammaAndAllLiveMpvPropertiesAndRequestsHandshake() {
    val transport = FakeTransport()
    val bridge = MpvShaderLabBridge(transport)

    bridge.attach()

    assertTrue(bridge.state.value.connected)
    assertTrue(MpvShaderLabBridge.NATIVE_STATE_PROPERTY in transport.stringProperties)
    assertTrue(MpvShaderLabBridge.SOURCE_GAMMA_PROPERTY in transport.stringProperties)
    assertEquals(
      setOf("sdr-intensity", "brightness", "contrast", "gamma", "saturation", "hue"),
      transport.doubleProperties,
    )
    assertTrue(
      transport.commands.contains(
        listOf("load-script", MpvShaderLabBridge.CONTROLLER_PATH),
      ),
    )
    assertEquals(
      listOf("script-message", "p9lab-native-state"),
      transport.commands.last(),
    )
  }

  @Test
  fun attachReusesExistingNativePublisherWithoutLoadingDuplicateController() {
    val transport = FakeTransport().apply {
      strings[MpvShaderLabBridge.NATIVE_STATE_PROPERTY] =
        "__ready=1\n__version=6.1.1-r07-state-1\n__serial=7"
    }
    val bridge = MpvShaderLabBridge(transport)

    bridge.attach()

    assertTrue(bridge.state.value.ready)
    assertEquals(7L, bridge.state.value.snapshotSerial)
    assertFalse(
      transport.commands.contains(
        listOf("load-script", MpvShaderLabBridge.CONTROLLER_PATH),
      ),
    )
    assertTrue(
      transport.commands.none { it == listOf("script-message", "p9lab-native-state") },
    )
  }

  @Test
  fun enginePreparationFailureSurfacesAsBackendErrorBeforeTransportAttach() {
    val transport = FakeTransport()
    val bridge = MpvShaderLabBridge(
      transport = transport,
      prepareEngine = { error("synthetic engine preparation failure") },
    )

    bridge.attach()

    assertFalse(bridge.state.value.connected)
    assertEquals("synthetic engine preparation failure", bridge.state.value.lastError)
    assertTrue(transport.commands.isEmpty())
  }

  @Test
  fun nativeSnapshotDecodesTypedObservableStateAndControlValues() {
    val transport = FakeTransport()
    val bridge = MpvShaderLabBridge(transport)
    bridge.attach()

    transport.emitText(
      MpvShaderLabBridge.NATIVE_STATE_PROPERTY,
      """
      __ready=1
      __version=6.1.1-r07-state-1
      __serial=42
      __bank=A
      __bypassed=1
      __preview=1
      __sdr=1
      __source_gamma=bt.1886
      __shader_slot=B
      __swaps=17
      __apply_busy=1
      __error=synthetic backend warning
      __user1=1
      __user2=0
      __user10=1
      brightness=12.5
      LUMA_CONTRAST=0.333
      USER_SLOT=10
      """.trimIndent(),
    )

    val state = bridge.state.value
    assertTrue(state.connected)
    assertTrue(state.ready)
    assertEquals("6.1.1-r07-state-1", state.backendVersion)
    assertEquals(42L, state.snapshotSerial)
    assertEquals(ShaderLabBank.A, state.activeBank)
    assertTrue(state.bypassed)
    assertTrue(state.previewOriginal)
    assertTrue(state.sdrEligible)
    assertEquals(ShaderLabSourceKind.SDR, state.sourceKind)
    assertEquals("bt.1886", state.sourceGamma)
    assertEquals(ShaderLabShaderSlot.B, state.shaderSlot)
    assertEquals(17L, state.shaderSwapCount)
    assertTrue(state.applyBusy)
    assertEquals("synthetic backend warning", state.lastError)
    assertEquals(setOf(1, 10), state.userPresetOccupied)
    assertEquals(12.5, state.values.getValue(ShaderLabControlId.MPV_BRIGHTNESS), 0.0)
    assertEquals(0.333, state.values.getValue(ShaderLabControlId.LUMA_CONTRAST), 0.0)
    assertEquals(10.0, state.values.getValue(ShaderLabControlId.USER_SLOT), 0.0)
  }

  @Test
  fun externalMpvPropertyObservationUpdatesStateImmediatelyWithoutSnapshotPolling() {
    val transport = FakeTransport()
    val bridge = MpvShaderLabBridge(transport)
    bridge.attach()

    transport.emitNumber("brightness", 18.25)
    transport.emitNumber("sdr-intensity", 4.75)

    assertEquals(18.25, bridge.state.value.values.getValue(ShaderLabControlId.MPV_BRIGHTNESS), 0.0)
    assertEquals(4.75, bridge.state.value.values.getValue(ShaderLabControlId.SDR_INTENSITY), 0.0)
    assertEquals(1, transport.commands.count { it.getOrNull(1) == "p9lab-native-state" })
  }

  @Test
  fun sourceGammaObservationClassifiesSdrPqHlgAndNotReady() {
    val transport = FakeTransport()
    val bridge = MpvShaderLabBridge(transport)
    bridge.attach()

    transport.emitText(MpvShaderLabBridge.SOURCE_GAMMA_PROPERTY, "bt.1886")
    assertEquals(ShaderLabSourceKind.SDR, bridge.state.value.sourceKind)
    assertTrue(bridge.state.value.sdrEligible)

    transport.emitText(MpvShaderLabBridge.SOURCE_GAMMA_PROPERTY, "pq")
    assertEquals(ShaderLabSourceKind.HDR_PQ, bridge.state.value.sourceKind)
    assertFalse(bridge.state.value.sdrEligible)

    transport.emitText(MpvShaderLabBridge.SOURCE_GAMMA_PROPERTY, "hlg")
    assertEquals(ShaderLabSourceKind.HDR_HLG, bridge.state.value.sourceKind)
    assertFalse(bridge.state.value.sdrEligible)

    transport.emitText(MpvShaderLabBridge.SOURCE_GAMMA_PROPERTY, "")
    assertEquals(ShaderLabSourceKind.NOT_READY, bridge.state.value.sourceKind)
  }

  @Test
  fun semanticBackendMapsEveryCommandFamilyToNativeLuaMessages() {
    val transport = FakeTransport()
    val bridge = MpvShaderLabBridge(transport)
    bridge.attach()
    transport.commands.clear()

    bridge.setValues(
      linkedMapOf(
        ShaderLabControlId.MPV_BRIGHTNESS to 4.25,
        ShaderLabControlId.LUMA_CONTRAST to 0.31,
      ),
    )
    bridge.toggleBypass()
    bridge.setPreviewOriginal(true)
    bridge.setPreviewOriginal(false)
    bridge.togglePreviewOriginalFallback()
    bridge.saveUserPreset(ShaderLabPresetId.User(3))
    bridge.loadUserPreset(ShaderLabPresetId.User(4))
    bridge.clearUserPreset(ShaderLabPresetId.User(5))
    bridge.loadBuiltInPreset(ShaderLabPresetId.BuiltIn(6))
    bridge.morph(ShaderLabPresetId.BuiltIn(2), ShaderLabPresetId.User(7), 0.375)
    bridge.revertVideoStart()
    bridge.resetAll()
    bridge.saveState()
    bridge.loadState()

    assertTrue(transport.commands.contains(listOf("script-message", "p9lab-native-set", "brightness", "4.2500000000000000")))
    assertTrue(transport.commands.any { it.take(4) == listOf("script-message", "p9lab-native-set", "LUMA_CONTRAST", "0.31000000000000000") })
    assertTrue(transport.commands.contains(listOf("script-message", "p9lab-native-bypass")))
    assertTrue(transport.commands.contains(listOf("script-message", "p9lab-native-preview-start")))
    assertTrue(transport.commands.contains(listOf("script-message", "p9lab-native-preview-end")))
    assertTrue(transport.commands.contains(listOf("script-message", "p9lab-native-preview-toggle")))
    assertTrue(transport.commands.contains(listOf("script-message", "p9lab-native-user-save", "3")))
    assertTrue(transport.commands.contains(listOf("script-message", "p9lab-native-user-load", "4")))
    assertTrue(transport.commands.contains(listOf("script-message", "p9lab-native-user-clear", "5")))
    assertTrue(transport.commands.contains(listOf("script-message", "p9lab-native-builtin-load", "6")))
    assertTrue(transport.commands.any { it.take(4) == listOf("script-message", "p9lab-native-morph", "2", "17") })
    assertTrue(transport.commands.contains(listOf("script-message", "p9lab-native-revert-video-start")))
    assertTrue(transport.commands.contains(listOf("script-message", "p9lab-native-reset-all")))
    assertTrue(transport.commands.contains(listOf("script-message", "p9lab-native-save-state")))
    assertTrue(transport.commands.contains(listOf("script-message", "p9lab-native-load-state")))
  }

  @Test
  fun commandTransportFailureBecomesR06TypedFailureAndObservableBackendError() {
    val transport = FakeTransport()
    val bridge = MpvShaderLabBridge(transport)
    bridge.attach()
    transport.failCommands = true

    val result = ShaderLabCommandApi(bridge).execute(ShaderLabCommand.ToggleBypass)

    assertTrue(result is ShaderLabCommandResult.Failed)
    assertEquals("synthetic transport failure", bridge.state.value.lastError)
    assertFalse(bridge.state.value.applyBusy)
  }

  @Test
  fun detachClearsConnectionReadinessWithoutDestroyingLastKnownValues() {
    val transport = FakeTransport()
    val bridge = MpvShaderLabBridge(transport)
    bridge.attach()
    transport.emitText(
      MpvShaderLabBridge.NATIVE_STATE_PROPERTY,
      "__ready=1\nbrightness=9.0",
    )

    bridge.detach()

    assertFalse(bridge.state.value.connected)
    assertFalse(bridge.state.value.ready)
    assertEquals(9.0, bridge.state.value.values.getValue(ShaderLabControlId.MPV_BRIGHTNESS), 0.0)
    assertEquals(1, transport.detachCount)
  }

  private class FakeTransport : ShaderLabMpvTransport {
    var listener: ((String, ShaderLabMpvValue) -> Unit)? = null
    val stringProperties = linkedSetOf<String>()
    val doubleProperties = linkedSetOf<String>()
    val commands = mutableListOf<List<String>>()
    val strings = mutableMapOf<String, String>()
    val doubles = mutableMapOf<String, Double>()
    var detachCount = 0
    var failCommands = false

    override fun attach(listener: (String, ShaderLabMpvValue) -> Unit) {
      this.listener = listener
    }

    override fun detach() {
      detachCount += 1
      listener = null
    }

    override fun observeString(property: String) {
      stringProperties += property
    }

    override fun observeDouble(property: String) {
      doubleProperties += property
    }

    override fun getString(property: String): String? = strings[property]

    override fun getDouble(property: String): Double? = doubles[property]

    override fun command(vararg args: String) {
      if (failCommands) error("synthetic transport failure")
      commands += args.toList()
    }

    fun emitText(property: String, value: String) {
      strings[property] = value
      listener?.invoke(property, ShaderLabMpvValue.Text(value))
    }

    fun emitNumber(property: String, value: Double) {
      doubles[property] = value
      listener?.invoke(property, ShaderLabMpvValue.Number(value))
    }
  }
}
