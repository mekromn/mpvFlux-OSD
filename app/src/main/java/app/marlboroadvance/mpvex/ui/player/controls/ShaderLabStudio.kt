package app.marlboroadvance.mpvex.ui.player.controls

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.nativeKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.repository.shaderlab.bridge.MpvShaderLabBridge
import app.marlboroadvance.mpvex.repository.shaderlab.bridge.ShaderLabBackendState
import app.marlboroadvance.mpvex.repository.shaderlab.bridge.ShaderLabSourceKind
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabActionId
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabActionSpec
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlCatalog
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlId
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlSpec
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabGroup
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabPresetId
import app.marlboroadvance.mpvex.repository.shaderlab.command.ShaderLabCommand
import app.marlboroadvance.mpvex.repository.shaderlab.command.ShaderLabCommandApi
import org.koin.compose.koinInject
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * First production-oriented Shader Lab surface.
 *
 * Unlike the R08 debug harness, this is a native Compose editor. Like the
 * player's native brightness slider, every slider movement is applied
 * immediately. The destination is the resident vo=gpu PARAM set rather than
 * WindowManager brightness, so there is no Lua round-trip, debounce/apply
 * phase, shader regeneration, or shader-list detach/reattach.
 *
 * Visibility is owned by [ShaderLabUiController] because the player controls
 * and this XML-hosted overlay are separate Compose trees.
 */
@Composable
fun ShaderLabStudioOverlay(
  modifier: Modifier = Modifier,
) {
  val bridge = koinInject<MpvShaderLabBridge>()
  val commandApi = koinInject<ShaderLabCommandApi>()
  val uiController = koinInject<ShaderLabUiController>()
  val backend by bridge.state.collectAsState()
  val visible by uiController.visible.collectAsState()

  AnimatedVisibility(
    visible = visible,
    modifier = modifier.width(430.dp),
  ) {
    ShaderLabStudioPanel(
      backend = backend,
      commandApi = commandApi,
      onClose = uiController::close,
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShaderLabStudioPanel(
  backend: ShaderLabBackendState,
  commandApi: ShaderLabCommandApi,
  onClose: () -> Unit,
) {
  val visibleControls = remember {
    ShaderLabControlCatalog.controls.filterNot { it.id == ShaderLabControlId.SHADER_PROOF }
  }
  val groups = remember {
    ShaderLabControlCatalog.groupOrder.filter { group ->
      visibleControls.any { it.group == group } || ShaderLabControlCatalog.actions.any { it.group == group }
    }
  }
  var selectedGroup by remember { mutableStateOf(ShaderLabGroup.MASTER) }
  var pendingConfirmation by remember { mutableStateOf<ShaderLabActionId?>(null) }

  val editingEnabled =
    backend.ready &&
      backend.sourceKind == ShaderLabSourceKind.SDR &&
      !backend.bypassed &&
      !backend.previewOriginal

  Card(
    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
    shape = RoundedCornerShape(24.dp),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
    ),
  ) {
    Column {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column {
          Text("SHADER LAB", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
          Text(
            when {
              backend.lastError != null -> "ERROR • ${backend.lastError}"
              backend.previewOriginal -> "ORIGINAL HOLD • RESIDENT"
              backend.bypassed -> "ORIGINAL • RESIDENT"
              backend.ready -> "LIVE • ${backend.sourceKind.name.replace('_', '-')} • NATIVE"
              backend.connected -> "SYNCING"
              else -> "OFFLINE"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onClose) { Text("CLOSE") }
      }

      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedButton(
          onClick = { commandApi.execute(ShaderLabCommand.ToggleBypass) },
          modifier = Modifier.weight(1f),
        ) {
          Text(if (backend.bypassed) "ORIGINAL" else "BYPASS")
        }
        HoldOriginalButton(commandApi = commandApi, active = backend.previewOriginal, modifier = Modifier.weight(1f))
      }

      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
      ) {
        groups.forEach { group ->
          FilterChip(
            selected = group == selectedGroup,
            onClick = {
              selectedGroup = group
              pendingConfirmation = null
            },
            label = { Text(prettyGroup(group)) },
          )
        }
      }

      HorizontalDivider()

      Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        if (selectedGroup in CURVE_GROUPS) {
          ShaderCurveEditor(
            group = selectedGroup,
            values = backend.values,
            enabled = editingEnabled,
            onValueChange = { id, value ->
              commandApi.execute(ShaderLabCommand.SetValue(id, value))
            },
          )
        }

        val controls = visibleControls.filter { it.group == selectedGroup }
        controls.forEach { spec ->
          ShaderLabSliderRow(
            spec = spec,
            backendValue = backend.values[spec.id] ?: spec.defaultValue,
            enabled = editingEnabled,
            onValueChange = { value ->
              if (spec.id == ShaderLabControlId.MORPH_AMOUNT) {
                val from = presetRef(backend.values[ShaderLabControlId.MORPH_FROM] ?: 1.0)
                val to = presetRef(backend.values[ShaderLabControlId.MORPH_TO] ?: 2.0)
                commandApi.execute(ShaderLabCommand.Morph(from, to, value))
              } else {
                commandApi.execute(ShaderLabCommand.SetValue(spec.id, value))
              }
            },
          )
        }

        ShaderLabControlCatalog.actions.filter { it.group == selectedGroup }.forEach { action ->
          val armed = pendingConfirmation == action.id
          OutlinedButton(
            onClick = {
              if (action.destructive && !armed) {
                pendingConfirmation = action.id
              } else {
                actionCommand(action, backend)?.let(commandApi::execute)
                pendingConfirmation = null
              }
            },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(if (armed) "CONFIRM • ${action.label}" else action.label)
          }
        }

        if (!editingEnabled && (controls.isNotEmpty() || selectedGroup in CURVE_GROUPS)) {
          Text(
            when {
              backend.bypassed || backend.previewOriginal -> "Realtime controls are paused while viewing the original image."
              backend.sourceKind != ShaderLabSourceKind.SDR -> "Shader Lab expansion controls are SDR-only."
              else -> "Waiting for Shader Lab backend."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

@Composable
private fun HoldOriginalButton(
  commandApi: ShaderLabCommandApi,
  active: Boolean,
  modifier: Modifier = Modifier,
) {
  var remoteHeld by remember { mutableStateOf(false) }

  OutlinedButton(
    onClick = {},
    modifier = modifier
      .onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        val isHoldKey =
          native.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            native.keyCode == KeyEvent.KEYCODE_ENTER ||
            native.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            native.keyCode == KeyEvent.KEYCODE_SPACE
        if (!isHoldKey) {
          false
        } else {
          when (native.action) {
            KeyEvent.ACTION_DOWN -> {
              if (!remoteHeld) {
                remoteHeld = true
                commandApi.execute(ShaderLabCommand.PreviewOriginalStart)
              }
              true
            }
            KeyEvent.ACTION_UP -> {
              if (remoteHeld) {
                remoteHeld = false
                commandApi.execute(ShaderLabCommand.PreviewOriginalEnd)
              }
              true
            }
            else -> false
          }
        }
      }
      .pointerInput(commandApi) {
        awaitEachGesture {
          val down = awaitFirstDown(requireUnconsumed = false)
          down.consume()
          commandApi.execute(ShaderLabCommand.PreviewOriginalStart)
          waitForUpOrCancellation()
          commandApi.execute(ShaderLabCommand.PreviewOriginalEnd)
        }
      },
  ) {
    Text(if (active) "ORIGINAL" else "HOLD ORIGINAL")
  }
}

@Composable
private fun ShaderLabSliderRow(
  spec: ShaderLabControlSpec,
  backendValue: Double,
  enabled: Boolean,
  onValueChange: (Double) -> Unit,
) {
  var localValue by remember(spec.id) { mutableDoubleStateOf(spec.clamp(backendValue)) }
  var dragging by remember(spec.id) { mutableStateOf(false) }

  LaunchedEffect(backendValue, dragging) {
    if (!dragging) localValue = spec.clamp(backendValue)
  }

  Column(modifier = Modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(modifier = Modifier.weight(1f)) {
        Text(spec.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Text(
          valueLabel(spec, localValue),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      TextButton(
        onClick = {
          localValue = spec.defaultValue
          onValueChange(spec.defaultValue)
        },
        enabled = enabled,
      ) { Text("RESET") }
    }

    Slider(
      value = localValue.toFloat(),
      onValueChange = { raw ->
        dragging = true
        localValue = spec.clamp(raw.toDouble())
        onValueChange(localValue)
      },
      onValueChangeFinished = { dragging = false },
      valueRange = spec.minValue.toFloat()..spec.maxValue.toFloat(),
      steps = if (spec.integer) max(0, (spec.maxValue - spec.minValue).roundToInt() - 1) else 0,
      enabled = enabled,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

@Composable
private fun ShaderCurveEditor(
  group: ShaderLabGroup,
  values: Map<ShaderLabControlId, Double>,
  enabled: Boolean,
  onValueChange: (ShaderLabControlId, Double) -> Unit,
) {
  val primary = MaterialTheme.colorScheme.primary
  val secondary = MaterialTheme.colorScheme.tertiary
  val grid = MaterialTheme.colorScheme.outlineVariant
  val identity = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
  val handle = MaterialTheme.colorScheme.onSurface
  val isLuma = group == ShaderLabGroup.LUMA
  val isChroma = group == ShaderLabGroup.CHROMA_GATES || group == ShaderLabGroup.COLOR_VOLUME
  val editable = enabled && (isLuma || isChroma)

  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(
      when {
        isLuma -> "LIVE TONE CURVE • drag left / middle / right handles"
        isChroma -> "LIVE CHROMA CURVE • base / mid / bright handles"
        else -> "LIVE MASTER CURVES"
      },
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(170.dp)
        .pointerInput(group, editable) {
          if (!editable) return@pointerInput
          var active: ShaderLabControlId? = null
          var dragValue: Double? = null
          detectDragGestures(
            onDragStart = { pos ->
              active = if (isLuma) {
                when {
                  pos.x < size.width / 3f -> ShaderLabControlId.LUMA_PIVOT
                  pos.x < size.width * 2f / 3f -> ShaderLabControlId.LUMA_CONTRAST
                  else -> ShaderLabControlId.LUMA_HIGHLIGHT
                }
              } else {
                when {
                  pos.x < size.width / 3f -> ShaderLabControlId.BASE_CHROMA
                  pos.x < size.width * 2f / 3f -> ShaderLabControlId.MID_CHROMA
                  else -> ShaderLabControlId.BRIGHT_CHROMA
                }
              }
              active?.let { id ->
                val spec = ShaderLabControlCatalog.spec(id)
                dragValue = values[id] ?: spec.defaultValue
              }
            },
            onDragEnd = {
              active = null
              dragValue = null
            },
            onDragCancel = {
              active = null
              dragValue = null
            },
          ) { change, dragAmount ->
            change.consume()
            val id = active ?: return@detectDragGestures
            val spec = ShaderLabControlCatalog.spec(id)
            val current = dragValue ?: values[id] ?: spec.defaultValue
            val next = if (id == ShaderLabControlId.LUMA_PIVOT) {
              spec.clamp(change.position.x / size.width * (spec.maxValue - spec.minValue) + spec.minValue)
            } else {
              spec.clamp(current - dragAmount.y / size.height * (spec.maxValue - spec.minValue) * 0.55)
            }
            dragValue = next
            onValueChange(id, next)
          }
        },
    ) {
      Canvas(Modifier.matchParentSize()) {
        val w = size.width
        val h = size.height
        for (i in 1..3) {
          drawLine(grid, Offset(w * i / 4f, 0f), Offset(w * i / 4f, h), strokeWidth = 1f)
          drawLine(grid, Offset(0f, h * i / 4f), Offset(w, h * i / 4f), strokeWidth = 1f)
        }
        drawLine(identity, Offset(0f, h), Offset(w, 0f), strokeWidth = 2f)

        val tone = Path()
        val chroma = Path()
        for (i in 0..96) {
          val x = i / 96.0
          val toneY = toneCurve(values, x)
          val chromaY = ((chromaCurve(values, x) - 0.75) / 0.75).coerceIn(0.0, 1.0)
          val px = (x * w).toFloat()
          val pyTone = ((1.0 - toneY) * h).toFloat()
          val pyChroma = ((1.0 - chromaY) * h).toFloat()
          if (i == 0) {
            tone.moveTo(px, pyTone)
            chroma.moveTo(px, pyChroma)
          } else {
            tone.lineTo(px, pyTone)
            chroma.lineTo(px, pyChroma)
          }
        }
        drawPath(tone, primary, style = Stroke(width = 4f))
        drawPath(chroma, secondary, style = Stroke(width = 4f))

        if (isLuma) {
          val pivot = (values[ShaderLabControlId.LUMA_PIVOT] ?: 0.18).coerceIn(0.0, 1.0)
          val xs = listOf(pivot, 0.5, 0.85)
          xs.forEach { x ->
            val y = toneCurve(values, x)
            drawCircle(handle, radius = 8f, center = Offset((x * w).toFloat(), ((1.0 - y) * h).toFloat()))
          }
        } else if (isChroma) {
          val ids = listOf(ShaderLabControlId.BASE_CHROMA, ShaderLabControlId.MID_CHROMA, ShaderLabControlId.BRIGHT_CHROMA)
          val xs = listOf(0.17, 0.50, 0.84)
          ids.zip(xs).forEach { (id, x) ->
            val spec = ShaderLabControlCatalog.spec(id)
            val v = (values[id] ?: spec.defaultValue).coerceIn(spec.minValue, spec.maxValue)
            val yn = (v - spec.minValue) / (spec.maxValue - spec.minValue)
            drawCircle(handle, radius = 8f, center = Offset((x * w).toFloat(), ((1.0 - yn) * h).toFloat()))
          }
        }
      }
    }
  }
}

private fun actionCommand(
  action: ShaderLabActionSpec,
  state: ShaderLabBackendState,
): ShaderLabCommand? =
  when (action.id) {
    ShaderLabActionId.BYPASS -> ShaderLabCommand.ToggleBypass
    ShaderLabActionId.PREVIEW_TOGGLE_FALLBACK -> ShaderLabCommand.TogglePreviewOriginalFallback
    ShaderLabActionId.LOAD_USER -> ShaderLabCommand.LoadUserPreset(
      ShaderLabPresetId.User((state.values[ShaderLabControlId.USER_SLOT] ?: 1.0).roundToInt().coerceIn(1, 10)),
    )
    ShaderLabActionId.SAVE_USER -> ShaderLabCommand.SaveUserPreset(
      ShaderLabPresetId.User((state.values[ShaderLabControlId.USER_SLOT] ?: 1.0).roundToInt().coerceIn(1, 10)),
    )
    ShaderLabActionId.CLEAR_USER -> ShaderLabCommand.ClearUserPreset(
      ShaderLabPresetId.User((state.values[ShaderLabControlId.USER_SLOT] ?: 1.0).roundToInt().coerceIn(1, 10)),
    )
    ShaderLabActionId.LOAD_BUILTIN -> ShaderLabCommand.LoadBuiltInPreset(
      ShaderLabPresetId.BuiltIn((state.values[ShaderLabControlId.BUILTIN_SLOT] ?: 1.0).roundToInt().coerceIn(1, 10)),
    )
    ShaderLabActionId.REVERT_VIDEO_START -> ShaderLabCommand.RevertVideoStart
    ShaderLabActionId.RESET_ALL -> ShaderLabCommand.ResetAll
    ShaderLabActionId.SAVE_STATE -> ShaderLabCommand.SaveState
    ShaderLabActionId.LOAD_STATE -> ShaderLabCommand.LoadState
  }

private fun presetRef(value: Double): ShaderLabPresetId {
  val ref = value.roundToInt().coerceIn(1, 20)
  return if (ref <= 10) ShaderLabPresetId.BuiltIn(ref) else ShaderLabPresetId.User(ref - 10)
}

private fun valueLabel(spec: ShaderLabControlSpec, value: Double): String {
  if (spec.choices.isNotEmpty()) {
    val index = value.roundToInt().coerceIn(0, spec.choices.lastIndex)
    return "${spec.choices[index]}  •  ${spec.format(value)}"
  }
  return spec.format(value)
}

private fun prettyGroup(group: ShaderLabGroup): String = group.name.replace('_', ' ')

private fun v(values: Map<ShaderLabControlId, Double>, id: ShaderLabControlId): Double =
  values[id] ?: ShaderLabControlCatalog.spec(id).defaultValue

private fun smooth(edge0: Double, edge1: Double, x: Double): Double {
  if (edge1 <= edge0) return if (x >= edge1) 1.0 else 0.0
  val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
  return t * t * (3.0 - 2.0 * t)
}

private fun toneCurve(values: Map<ShaderLabControlId, Double>, y0: Double): Double {
  val y = y0.coerceIn(0.0, 1.0)
  val master = v(values, ShaderLabControlId.LUMA_MASTER)
  val contrast = v(values, ShaderLabControlId.LUMA_CONTRAST) * master
  val highlight = v(values, ShaderLabControlId.LUMA_HIGHLIGHT) * master
  val pivot = v(values, ShaderLabControlId.LUMA_PIVOT)
  val hiGate = smooth(
    v(values, ShaderLabControlId.LUMA_HIGHLIGHT_START),
    v(values, ShaderLabControlId.LUMA_HIGHLIGHT_END),
    y,
  )
  val tuned = (y + contrast * (y - pivot) * y * (1.0 - y) + highlight * hiGate * y * (1.0 - y))
    .coerceIn(0.0, 1.0)
  val compress = v(values, ShaderLabControlId.SDR_COMPRESS)
  return tuned * (1.0 - compress) + y * compress
}

private fun chromaCurve(values: Map<ShaderLabControlId, Double>, y0: Double): Double {
  val y = y0.coerceIn(0.0, 1.0)
  val shadow = smooth(v(values, ShaderLabControlId.SHADOW_GATE_START), v(values, ShaderLabControlId.SHADOW_GATE_FULL), y)
  val mid = smooth(v(values, ShaderLabControlId.MIDTONE_START), v(values, ShaderLabControlId.MIDTONE_FULL), y) *
    (1.0 - smooth(v(values, ShaderLabControlId.MIDTONE_FADE_START), v(values, ShaderLabControlId.MIDTONE_FADE_END), y))
  val bright = smooth(v(values, ShaderLabControlId.BRIGHT_START), v(values, ShaderLabControlId.BRIGHT_FULL), y)
  val master = v(values, ShaderLabControlId.CHROMA_MASTER)
  val boost = master * (
    v(values, ShaderLabControlId.BASE_CHROMA) +
      v(values, ShaderLabControlId.MID_CHROMA) * mid +
      v(values, ShaderLabControlId.BRIGHT_CHROMA) * bright
    )
  val compress = v(values, ShaderLabControlId.SDR_COMPRESS)
  return 1.0 + boost * shadow * (1.0 - compress)
}

private val CURVE_GROUPS = setOf(
  ShaderLabGroup.MASTER,
  ShaderLabGroup.LUMA,
  ShaderLabGroup.CHROMA_GATES,
  ShaderLabGroup.COLOR_VOLUME,
)
