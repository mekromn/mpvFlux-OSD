package app.marlboroadvance.mpvex.ui.player.controls

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
 * Native R08 Shader Lab Studio.
 *
 * The surface is intentionally adaptive instead of using a fixed-width debug
 * card. Landscape receives a persistent navigation rail plus a wide editor;
 * compact/portrait layouts keep group navigation horizontal. Comparison stays
 * pinned so bypass/original never disappear while tuning a long control group.
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

  BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    val wideStudio = maxWidth >= 760.dp && maxHeight >= 360.dp

    AnimatedVisibility(
      visible = visible,
      modifier = Modifier.fillMaxSize(),
    ) {
      Box(Modifier.fillMaxSize()) {
        val panelModifier = if (wideStudio) {
          Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .widthIn(min = 640.dp, max = 760.dp)
            .padding(12.dp)
        } else {
          Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .heightIn(min = 360.dp, max = maxHeight - 8.dp)
            .padding(8.dp)
        }

        ShaderLabStudioPanel(
          backend = backend,
          commandApi = commandApi,
          wideStudio = wideStudio,
          onClose = uiController::close,
          modifier = panelModifier,
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShaderLabStudioPanel(
  backend: ShaderLabBackendState,
  commandApi: ShaderLabCommandApi,
  wideStudio: Boolean,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
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
    modifier = modifier,
    shape = RoundedCornerShape(if (wideStudio) 26.dp else 24.dp),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.975f),
    ),
  ) {
    Column(Modifier.fillMaxSize()) {
      StudioHeader(backend = backend, onClose = onClose)
      ComparisonDock(backend = backend, commandApi = commandApi)
      HorizontalDivider()

      if (wideStudio) {
        Row(Modifier.fillMaxSize()) {
          StudioNavigationRail(
            groups = groups,
            selectedGroup = selectedGroup,
            onSelect = {
              selectedGroup = it
              pendingConfirmation = null
            },
            modifier = Modifier.width(174.dp).fillMaxHeight(),
          )
          HorizontalDivider(modifier = Modifier.width(1.dp).fillMaxHeight())
          StudioEditorPane(
            group = selectedGroup,
            backend = backend,
            commandApi = commandApi,
            visibleControls = visibleControls,
            editingEnabled = editingEnabled,
            pendingConfirmation = pendingConfirmation,
            onPendingConfirmation = { pendingConfirmation = it },
            modifier = Modifier.weight(1f).fillMaxHeight(),
          )
        }
      } else {
        CompactGroupStrip(
          groups = groups,
          selectedGroup = selectedGroup,
          onSelect = {
            selectedGroup = it
            pendingConfirmation = null
          },
        )
        HorizontalDivider()
        StudioEditorPane(
          group = selectedGroup,
          backend = backend,
          commandApi = commandApi,
          visibleControls = visibleControls,
          editingEnabled = editingEnabled,
          pendingConfirmation = pendingConfirmation,
          onPendingConfirmation = { pendingConfirmation = it },
          modifier = Modifier.fillMaxSize(),
        )
      }
    }
  }
}

@Composable
private fun StudioHeader(
  backend: ShaderLabBackendState,
  onClose: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 12.dp, bottom = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          "SHADER LAB",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Black,
        )
        Spacer(Modifier.width(10.dp))
        BackendStatusPill(backend)
      }
      Text(
        studioStatusText(backend),
        style = MaterialTheme.typography.labelMedium,
        color = if (backend.lastError != null) {
          MaterialTheme.colorScheme.error
        } else {
          MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
    TextButton(onClick = onClose) { Text("CLOSE") }
  }
}

@Composable
private fun BackendStatusPill(backend: ShaderLabBackendState) {
  val container = when {
    backend.lastError != null -> MaterialTheme.colorScheme.errorContainer
    backend.ready -> MaterialTheme.colorScheme.primaryContainer
    backend.connected -> MaterialTheme.colorScheme.secondaryContainer
    else -> MaterialTheme.colorScheme.surfaceVariant
  }
  val label = when {
    backend.lastError != null -> "ERROR"
    backend.previewOriginal -> "ORIGINAL"
    backend.bypassed -> "BYPASS"
    backend.ready -> "LIVE"
    backend.connected -> "SYNC"
    else -> "OFFLINE"
  }

  Surface(
    shape = RoundedCornerShape(100.dp),
    color = container,
  ) {
    Text(
      label,
      modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.Bold,
    )
  }
}

@Composable
private fun ComparisonDock(
  backend: ShaderLabBackendState,
  commandApi: ShaderLabCommandApi,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    OutlinedButton(
      onClick = { commandApi.execute(ShaderLabCommand.ToggleBypass) },
      modifier = Modifier.weight(1f).height(48.dp),
    ) {
      Text(if (backend.bypassed) "RETURN TO TUNED" else "BYPASS")
    }
    HoldOriginalButton(
      commandApi = commandApi,
      active = backend.previewOriginal,
      modifier = Modifier.weight(1f).height(48.dp),
    )
  }
}

@Composable
private fun StudioNavigationRail(
  groups: List<ShaderLabGroup>,
  selectedGroup: ShaderLabGroup,
  onSelect: (ShaderLabGroup) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(7.dp),
  ) {
    Text(
      "WORKSPACE",
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
    )

    groups.forEach { group ->
      val selected = group == selectedGroup
      if (selected) {
        Button(
          onClick = { onSelect(group) },
          modifier = Modifier.fillMaxWidth(),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
        ) {
          Text(prettyGroup(group), modifier = Modifier.fillMaxWidth())
        }
      } else {
        OutlinedButton(
          onClick = { onSelect(group) },
          modifier = Modifier.fillMaxWidth(),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
        ) {
          Text(prettyGroup(group), modifier = Modifier.fillMaxWidth())
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactGroupStrip(
  groups: List<ShaderLabGroup>,
  selectedGroup: ShaderLabGroup,
  onSelect: (ShaderLabGroup) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(7.dp),
  ) {
    groups.forEach { group ->
      FilterChip(
        selected = group == selectedGroup,
        onClick = { onSelect(group) },
        label = { Text(prettyGroup(group)) },
      )
    }
  }
}

@Composable
private fun StudioEditorPane(
  group: ShaderLabGroup,
  backend: ShaderLabBackendState,
  commandApi: ShaderLabCommandApi,
  visibleControls: List<ShaderLabControlSpec>,
  editingEnabled: Boolean,
  pendingConfirmation: ShaderLabActionId?,
  onPendingConfirmation: (ShaderLabActionId?) -> Unit,
  modifier: Modifier = Modifier,
) {
  val controls = visibleControls.filter { it.group == group }
  val actions = ShaderLabControlCatalog.actions.filter { action ->
    action.group == group && action.id != ShaderLabActionId.BYPASS && action.id != ShaderLabActionId.PREVIEW_TOGGLE_FALLBACK
  }

  Column(
    modifier = modifier.verticalScroll(rememberScrollState()).padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    GroupHeader(group = group, controlCount = controls.size, actionCount = actions.size)

    backend.lastError?.let { error ->
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(14.dp),
      ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
          Text("BACKEND ERROR", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
          Text(error, style = MaterialTheme.typography.bodySmall)
        }
      }
    }

    if (group in CURVE_GROUPS) {
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)),
        shape = RoundedCornerShape(18.dp),
      ) {
        ShaderCurveEditor(
          group = group,
          values = backend.values,
          enabled = editingEnabled,
          onValueChange = { id, value ->
            commandApi.execute(ShaderLabCommand.SetValue(id, value))
          },
          modifier = Modifier.padding(12.dp),
        )
      }
    }

    controls.forEach { spec ->
      ShaderLabSliderCard(
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

    if (group == ShaderLabGroup.COMPARE && actions.isEmpty()) {
      Text(
        "Bypass and press-and-hold original stay pinned above so comparison is always one action away.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    actions.forEach { action ->
      val armed = pendingConfirmation == action.id
      if (armed) {
        Button(
          onClick = {
            actionCommand(action, backend)?.let(commandApi::execute)
            onPendingConfirmation(null)
          },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("CONFIRM • ${action.label}")
        }
      } else {
        OutlinedButton(
          onClick = {
            if (action.destructive) {
              onPendingConfirmation(action.id)
            } else {
              actionCommand(action, backend)?.let(commandApi::execute)
              onPendingConfirmation(null)
            }
          },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(action.label)
        }
      }
    }

    if (!editingEnabled && (controls.isNotEmpty() || group in CURVE_GROUPS)) {
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
      ) {
        Text(
          when {
            backend.bypassed || backend.previewOriginal -> "Realtime tuning is paused while the original image is visible."
            backend.sourceKind != ShaderLabSourceKind.SDR -> "Shader Lab expansion controls are SDR-only for this R08 path."
            backend.connected -> "Shader Lab is connected and waiting for the native state handshake."
            else -> "Waiting for the Shader Lab MPV bridge."
          },
          modifier = Modifier.padding(12.dp),
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
  }
}

@Composable
private fun GroupHeader(
  group: ShaderLabGroup,
  controlCount: Int,
  actionCount: Int,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.Top,
  ) {
    Column(Modifier.weight(1f)) {
      Text(
        prettyGroup(group),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
      Text(
        groupSubtitle(group),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Surface(
      shape = RoundedCornerShape(100.dp),
      color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
      Text(
        buildString {
          append(controlCount)
          append(if (controlCount == 1) " control" else " controls")
          if (actionCount > 0) append(" • $actionCount action${if (actionCount == 1) "" else "s"}")
        },
        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
        style = MaterialTheme.typography.labelSmall,
      )
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
        val isHoldKey = event.key == Key.DirectionCenter || event.key == Key.Enter
        if (!isHoldKey) {
          false
        } else {
          when (event.type) {
            KeyEventType.KeyDown -> {
              if (!remoteHeld) {
                remoteHeld = true
                commandApi.execute(ShaderLabCommand.PreviewOriginalStart)
              }
              true
            }
            KeyEventType.KeyUp -> {
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
    Text(if (active) "ORIGINAL — RELEASE" else "HOLD ORIGINAL")
  }
}

@Composable
private fun ShaderLabSliderCard(
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

  fun applyValue(value: Double) {
    localValue = spec.clamp(value)
    onValueChange(localValue)
  }

  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)),
    shape = RoundedCornerShape(16.dp),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            spec.label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            "${spec.id.legacyKey} • normal step ${spec.format(spec.normalStep)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = MaterialTheme.colorScheme.primaryContainer,
        ) {
          Text(
            valueLabel(spec, localValue),
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
          )
        }
        TextButton(
          onClick = { applyValue(spec.defaultValue) },
          enabled = enabled,
          contentPadding = PaddingValues(horizontal = 8.dp),
        ) { Text("RESET") }
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedButton(
          onClick = { applyValue(localValue - spec.normalStep) },
          enabled = enabled,
          modifier = Modifier.width(46.dp).height(40.dp),
          contentPadding = PaddingValues(0.dp),
        ) { Text("−", style = MaterialTheme.typography.titleMedium) }

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
          modifier = Modifier.weight(1f),
        )

        OutlinedButton(
          onClick = { applyValue(localValue + spec.normalStep) },
          enabled = enabled,
          modifier = Modifier.width(46.dp).height(40.dp),
          contentPadding = PaddingValues(0.dp),
        ) { Text("+", style = MaterialTheme.typography.titleMedium) }
      }
    }
  }
}

@Composable
private fun ShaderCurveEditor(
  group: ShaderLabGroup,
  values: Map<ShaderLabControlId, Double>,
  enabled: Boolean,
  onValueChange: (ShaderLabControlId, Double) -> Unit,
  modifier: Modifier = Modifier,
) {
  val primary = MaterialTheme.colorScheme.primary
  val secondary = MaterialTheme.colorScheme.tertiary
  val grid = MaterialTheme.colorScheme.outlineVariant
  val identity = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
  val handle = MaterialTheme.colorScheme.onSurface
  val isLuma = group == ShaderLabGroup.LUMA
  val isChroma = group == ShaderLabGroup.CHROMA_GATES || group == ShaderLabGroup.COLOR_VOLUME
  val editable = enabled && (isLuma || isChroma)

  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(
          when {
            isLuma -> "LIVE TONE CURVE"
            isChroma -> "LIVE CHROMA CURVE"
            else -> "LIVE MASTER CURVES"
          },
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.Bold,
        )
        Text(
          when {
            isLuma -> "Drag left / middle / right zones for pivot, contrast and highlight."
            isChroma -> "Drag base / mid / bright regions vertically for live chroma shaping."
            else -> "Luma and chroma transfer preview from the current resident values."
          },
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Surface(
        shape = RoundedCornerShape(100.dp),
        color = if (editable) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
      ) {
        Text(
          if (editable) "DIRECT MANIPULATION" else "PREVIEW",
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          style = MaterialTheme.typography.labelSmall,
        )
      }
    }

    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(150.dp)
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
      Canvas(Modifier.fillMaxSize()) {
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
            val value = (values[id] ?: spec.defaultValue).coerceIn(spec.minValue, spec.maxValue)
            val yn = (value - spec.minValue) / (spec.maxValue - spec.minValue)
            drawCircle(handle, radius = 8f, center = Offset((x * w).toFloat(), ((1.0 - yn) * h).toFloat()))
          }
        }
      }
    }

    Row(Modifier.fillMaxWidth()) {
      Text("SHADOWS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(Modifier.weight(1f))
      Text("MIDTONES", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(Modifier.weight(1f))
      Text("HIGHLIGHTS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val offset = spec.minValue.roundToInt()
    val index = (value.roundToInt() - offset).coerceIn(0, spec.choices.lastIndex)
    return "${spec.choices[index]} • ${spec.format(value)}"
  }
  return spec.format(value)
}

private fun studioStatusText(backend: ShaderLabBackendState): String =
  when {
    backend.lastError != null -> backend.lastError
    backend.previewOriginal -> "Press-and-hold original • release to return to tuned output"
    backend.bypassed -> "Original image bypass • resident shader remains loaded"
    backend.ready -> {
      val source = backend.sourceKind.name.replace('_', '-')
      val version = backend.backendVersion?.let { " • $it" }.orEmpty()
      "LIVE • $source • NATIVE$version"
    }
    backend.connected -> "MPV bridge connected • synchronizing Shader Lab state"
    else -> "Shader Lab bridge offline"
  }

private fun groupSubtitle(group: ShaderLabGroup): String =
  when (group) {
    ShaderLabGroup.MASTER -> "Global luma/chroma shaping with a combined transfer preview."
    ShaderLabGroup.MPV -> "Native mpv picture controls and Pixel SDR intensity."
    ShaderLabGroup.LUMA -> "Pivot, contrast and highlight rolloff shaping."
    ShaderLabGroup.CHROMA_GATES -> "Protect shadows and low-saturation regions before expansion."
    ShaderLabGroup.COLOR_VOLUME -> "Luminance-aware base, midtone and bright chroma expansion."
    ShaderLabGroup.SKIN -> "Hue, luma and chroma protection around skin-tone regions."
    ShaderLabGroup.GAMUT -> "Boundary margin and gamut-search quality controls."
    ShaderLabGroup.OUTPUT -> "Final output compression and delivery shaping."
    ShaderLabGroup.VIEW -> "Graph and presentation modes for visual inspection."
    ShaderLabGroup.PRESETS -> "Built-in and user preset storage/recall."
    ShaderLabGroup.MORPH -> "Continuously interpolate between two preset endpoints."
    ShaderLabGroup.DIAGNOSTIC -> "Gamut/luma clipping and renderer diagnostics."
    ShaderLabGroup.CONTROL -> "Controller behavior and adjustment granularity."
    ShaderLabGroup.COMPARE -> "Instant tuned/original comparison controls."
    ShaderLabGroup.SYSTEM -> "State management, video-start restore and global reset."
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
