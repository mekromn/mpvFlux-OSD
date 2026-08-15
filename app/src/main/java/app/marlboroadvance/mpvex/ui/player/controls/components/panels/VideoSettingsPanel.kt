package app.marlboroadvance.mpvex.ui.player.controls.components.panels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.ui.player.ShaderLabBridge
import app.marlboroadvance.mpvex.ui.player.ShaderLabBridge.Control
import app.marlboroadvance.mpvex.ui.player.controls.panelCardsColors
import app.marlboroadvance.mpvex.ui.theme.spacing
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * mpvLab native workstation backed by the bundled Shader Lab v6.1.1 Lua/GLSL
 * engine. Android owns touch/UI; the proven controller remains authoritative
 * for properties, shader generation, A/B swaps, presets and state.
 */
@Composable
fun VideoSettingsPanel(
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val values = remember { mutableStateMapOf<String, Double>() }
  ShaderLabBridge.controls.forEach { control ->
    if (!values.containsKey(control.key)) values[control.key] = control.default
  }

  var selectedGroup by remember { mutableStateOf("MASTER") }
  var backendReady by remember { mutableStateOf(false) }
  var isSdr by remember { mutableStateOf(true) }
  var bypassed by remember { mutableStateOf(false) }
  var backendPreview by remember { mutableStateOf(false) }
  var userSlots by remember { mutableStateOf(BooleanArray(10)) }
  var pendingConfirm by remember { mutableStateOf<ConfirmAction?>(null) }

  // Controller publishes its complete B-bank every 200 ms. This makes preset,
  // morph, TV-remote and state-load changes immediately visible in Compose.
  LaunchedEffect(Unit) {
    while (true) {
      val state = ShaderLabBridge.readState()
      if (state.isNotEmpty()) {
        backendReady = true
        isSdr = state["__sdr"] != "0"
        bypassed = state["__bypassed"] == "1"
        backendPreview = state["__preview"] == "1"
        ShaderLabBridge.controls.forEach { control ->
          state[control.key]?.toDoubleOrNull()?.let { values[control.key] = it }
        }
        userSlots = BooleanArray(10) { i -> state["__user${i + 1}"] == "1" }
      }
      delay(200)
    }
  }

  val holdInteraction = remember { MutableInteractionSource() }
  val holdOriginal by holdInteraction.collectIsPressedAsState()

  // Real press lifecycle: start preview on pointer-down and end it as soon as
  // Compose reports release/cancel. No delayed player long-press timer involved.
  LaunchedEffect(holdOriginal) {
    if (holdOriginal) ShaderLabBridge.previewStart() else ShaderLabBridge.previewEnd()
  }
  DisposableEffect(Unit) {
    onDispose { ShaderLabBridge.previewEnd() }
  }

  pendingConfirm?.let { confirm ->
    AlertDialog(
      onDismissRequest = { pendingConfirm = null },
      title = { Text(confirm.title) },
      text = { Text(confirm.message) },
      confirmButton = {
        Button(
          onClick = {
            pendingConfirm = null
            confirm.action()
          },
        ) { Text(confirm.confirmLabel) }
      },
      dismissButton = {
        TextButton(onClick = { pendingConfirm = null }) { Text("Cancel") }
      },
    )
  }

  DraggablePanel(
    modifier = modifier,
    header = {
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.spacing.medium)
            .padding(top = MaterialTheme.spacing.extraSmall),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "mpvLab Shader Lab",
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.SemiBold,
            )
            Text(
              text =
                when {
                  !backendReady -> "Starting bundled v6.1.1 engine…"
                  !isSdr -> "HDR source — SDR expansion controls are protected"
                  else -> "v6.1.1 engine • native workstation"
                },
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          IconButton(onClick = onDismissRequest) {
            Icon(Icons.Default.Close, contentDescription = "Close Shader Lab", modifier = Modifier.size(30.dp))
          }
        }

        ShaderLabCompareBar(
          backendReady = backendReady,
          bypassed = bypassed,
          holdOriginal = holdOriginal || backendPreview,
          holdInteraction = holdInteraction,
          onBypass = { ShaderLabBridge.bypass() },
          onRevert = {
            pendingConfirm = ConfirmAction(
              title = "Revert to video start?",
              message = "Restore every Shader Lab value to the state captured when this video started.",
              confirmLabel = "Revert",
              action = ShaderLabBridge::revertVideoStart,
            )
          },
        )

        ShaderLabGroupBar(selectedGroup) { selectedGroup = it }
      }
    },
  ) {
    Column(
      modifier = Modifier.padding(MaterialTheme.spacing.medium),
      verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
    ) {
      when (selectedGroup) {
        "PRESETS" -> PresetsSection(values, userSlots, backendReady, onConfirm = { pendingConfirm = it })
        "MORPH" -> MorphSection(values, backendReady)
        "SYSTEM" -> SystemSection(onConfirm = { pendingConfirm = it }, backendReady = backendReady)
        else -> {
          val controls = ShaderLabBridge.controls.filter { it.group == selectedGroup }
          ShaderLabSectionTitle(selectedGroup, groupSubtitle(selectedGroup))
          controls.forEach { control ->
            ShaderControlCard(control, values, enabled = backendReady && (isSdr || control.kind == ShaderLabBridge.Kind.CONTROLLER))
          }
          if (selectedGroup == "DIAGNOSTIC") {
            OutlinedButton(onClick = ShaderLabBridge::status, enabled = backendReady, modifier = Modifier.fillMaxWidth()) {
              Text("Show engine status")
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ShaderLabCompareBar(
  backendReady: Boolean,
  bypassed: Boolean,
  holdOriginal: Boolean,
  holdInteraction: MutableInteractionSource,
  onBypass: () -> Unit,
  onRevert: () -> Unit,
) {
  Card(
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.74f),
    ),
    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp),
  ) {
    Column(
      modifier = Modifier.padding(10.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
      ) {
        FilterChip(
          selected = bypassed,
          enabled = backendReady,
          onClick = onBypass,
          label = { Text(if (bypassed) "BYPASS ON" else "Bypass") },
        )

        Surface(
          shape = MaterialTheme.shapes.large,
          color = if (holdOriginal) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
          contentColor = if (holdOriginal) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
          modifier =
            Modifier
              .weight(1f)
              .clickable(
                enabled = backendReady,
                interactionSource = holdInteraction,
                indication = ripple(),
                onClick = {},
              ),
        ) {
          Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
          ) {
            Text(
              text = if (holdOriginal) "ORIGINAL" else "HOLD ORIGINAL",
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.Bold,
            )
          }
        }
      }

      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
          text =
            when {
              !backendReady -> "Waiting for the bundled controller"
              holdOriginal -> "Original video-start state — release to return"
              bypassed -> "Bypass locked for comparison"
              else -> "Hold Original for instant A/B; Bypass latches"
            },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRevert, enabled = backendReady) { Text("Revert") }
      }
    }
  }
}

@Composable
private fun ShaderLabGroupBar(
  selected: String,
  onSelect: (String) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    ShaderLabBridge.groups.forEach { group ->
      FilterChip(
        selected = selected == group,
        onClick = { onSelect(group) },
        label = { Text(groupDisplay(group)) },
      )
    }
  }
}

@Composable
private fun ShaderControlCard(
  control: Control,
  values: MutableMap<String, Double>,
  enabled: Boolean,
) {
  val value = values[control.key] ?: control.default
  val granularity = (values["TOUCH_GRANULARITY"] ?: 2.0).roundToInt().coerceIn(1, 3)
  val step = when (granularity) {
    1 -> control.fine
    3 -> control.coarse
    else -> control.normal
  }

  fun setValue(raw: Double) {
    val v = control.clamp(raw)
    values[control.key] = v
    ShaderLabBridge.set(control.key, v)
  }

  Card(colors = panelCardsColors(), modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(control.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(control.format(value), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
      }

      if (control.choices.isNotEmpty()) {
        Row(
          modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          control.choices.forEachIndexed { index, label ->
            FilterChip(
              selected = value.roundToInt() == index + if (control.key == "TOUCH_GRANULARITY") 1 else 0,
              enabled = enabled,
              onClick = { setValue((index + if (control.key == "TOUCH_GRANULARITY") 1 else 0).toDouble()) },
              label = { Text(label) },
            )
          }
        }
      } else {
        Slider(
          value = value.toFloat().coerceIn(control.min.toFloat(), control.max.toFloat()),
          onValueChange = { setValue(it.toDouble()) },
          valueRange = control.min.toFloat()..control.max.toFloat(),
          enabled = enabled,
          modifier = Modifier.fillMaxWidth(),
        )
      }

      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
      ) {
        OutlinedButton(
          onClick = { setValue(value - step) },
          enabled = enabled && value > control.min,
          modifier = Modifier.weight(1f),
        ) { Text("−${control.format(step).trimEnd('0').trimEnd('.')}") }
        OutlinedButton(
          onClick = { setValue(control.default) },
          enabled = enabled && value != control.default,
          modifier = Modifier.weight(1f),
        ) { Text("Reset") }
        OutlinedButton(
          onClick = { setValue(value + step) },
          enabled = enabled && value < control.max,
          modifier = Modifier.weight(1f),
        ) { Text("+${control.format(step).trimEnd('0').trimEnd('.')}") }
      }
    }
  }
}

@Composable
private fun PresetsSection(
  values: MutableMap<String, Double>,
  userSlots: BooleanArray,
  backendReady: Boolean,
  onConfirm: (ConfirmAction) -> Unit,
) {
  var userSlot = (values["USER_SLOT"] ?: 1.0).roundToInt().coerceIn(1, 10)
  ShaderLabSectionTitle("Presets", "10 user slots + 10 read-only built-ins")

  Text("User presets", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
  Row(
    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    (1..10).forEach { slot ->
      FilterChip(
        selected = userSlot == slot,
        enabled = backendReady,
        onClick = {
          userSlot = slot
          values["USER_SLOT"] = slot.toDouble()
          ShaderLabBridge.set("USER_SLOT", slot.toDouble())
        },
        label = { Text("U$slot${if (userSlots[slot - 1]) " •" else ""}") },
      )
    }
  }

  Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
    Button(
      onClick = { ShaderLabBridge.userLoad(userSlot) },
      enabled = backendReady && userSlots[userSlot - 1],
      modifier = Modifier.weight(1f),
    ) { Text("Load") }
    OutlinedButton(
      onClick = {
        onConfirm(
          ConfirmAction(
            "Save User $userSlot?",
            "Overwrite User $userSlot with the complete current Shader Lab tuning state.",
            "Save",
          ) { ShaderLabBridge.userSave(userSlot) },
        )
      },
      enabled = backendReady,
      modifier = Modifier.weight(1f),
    ) { Text("Save") }
    OutlinedButton(
      onClick = {
        onConfirm(
          ConfirmAction(
            "Clear User $userSlot?",
            "Delete the saved preset in User $userSlot.",
            "Clear",
          ) { ShaderLabBridge.userClear(userSlot) },
        )
      },
      enabled = backendReady && userSlots[userSlot - 1],
      modifier = Modifier.weight(1f),
    ) { Text("Clear") }
  }

  Text("Built-in presets", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
  ShaderLabBridge.builtInPresetNames.forEachIndexed { index, name ->
    Surface(
      shape = MaterialTheme.shapes.large,
      color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .82f),
      modifier =
        Modifier
          .fillMaxWidth()
          .clickable(enabled = backendReady) { ShaderLabBridge.builtinLoad(index + 1) },
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
      ) {
        Text("B${index + 1}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.size(12.dp))
        Text(name, style = MaterialTheme.typography.titleSmall)
      }
    }
  }
}

@Composable
private fun MorphSection(
  values: MutableMap<String, Double>,
  backendReady: Boolean,
) {
  ShaderLabSectionTitle("Morph", "Continuously blend between any built-in or user preset")
  ShaderLabBridge.controls.filter { it.group == "MORPH" }.forEach { control ->
    ShaderControlCard(control, values, backendReady)
  }
  val from = (values["MORPH_FROM"] ?: 1.0).roundToInt()
  val to = (values["MORPH_TO"] ?: 2.0).roundToInt()
  Text(
    "${presetRefName(from)} → ${presetRefName(to)}",
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

@Composable
private fun SystemSection(
  onConfirm: (ConfirmAction) -> Unit,
  backendReady: Boolean,
) {
  ShaderLabSectionTitle("System", "Video-start restore and complete workstation state")

  Button(
    onClick = {
      onConfirm(
        ConfirmAction(
          "Revert to video start?",
          "Restore all Lab tuning, properties and shader state captured at video start.",
          "Revert",
          ShaderLabBridge::revertVideoStart,
        ),
      )
    },
    enabled = backendReady,
    modifier = Modifier.fillMaxWidth(),
  ) { Text("Revert all to video-start state") }

  OutlinedButton(
    onClick = {
      onConfirm(
        ConfirmAction(
          "Reset to V3.1 baseline?",
          "Reset every tuning value to the V3.1 reference defaults.",
          "Reset all",
          ShaderLabBridge::resetAll,
        ),
      )
    },
    enabled = backendReady,
    modifier = Modifier.fillMaxWidth(),
  ) { Text("Reset all tuning to V3.1 baseline") }

  OutlinedButton(onClick = ShaderLabBridge::saveState, enabled = backendReady, modifier = Modifier.fillMaxWidth()) {
    Text("Save complete Lab state")
  }

  OutlinedButton(
    onClick = {
      onConfirm(
        ConfirmAction(
          "Load saved Lab state?",
          "Replace the current tuning with the complete previously saved Lab state.",
          "Load",
          ShaderLabBridge::loadState,
        ),
      )
    },
    enabled = backendReady,
    modifier = Modifier.fillMaxWidth(),
  ) { Text("Load complete Lab state") }
}

@Composable
private fun ShaderLabSectionTitle(title: String, subtitle: String) {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(groupDisplay(title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

private data class ConfirmAction(
  val title: String,
  val message: String,
  val confirmLabel: String,
  val action: () -> Unit,
)

private fun presetRefName(ref: Int): String {
  val safe = ref.coerceIn(1, 20)
  return if (safe <= 10) {
    "B$safe ${ShaderLabBridge.builtInPresetNames[safe - 1]}"
  } else {
    "U${safe - 10}"
  }
}

private fun groupDisplay(group: String): String =
  when (group) {
    "MPV" -> "MPV"
    "LUMA" -> "Luma"
    "CHROMA GATES" -> "Chroma Gates"
    "COLOR VOLUME" -> "Color Volume"
    "SKIN" -> "Skin"
    "GAMUT" -> "Gamut"
    "OUTPUT" -> "Output"
    "VIEW" -> "View"
    "PRESETS" -> "Presets"
    "MORPH" -> "Morph"
    "DIAGNOSTIC" -> "Diagnostic"
    "CONTROL" -> "Control"
    "MASTER" -> "Master"
    "SYSTEM" -> "System"
    else -> group
  }

private fun groupSubtitle(group: String): String =
  when (group) {
    "MASTER" -> "Virtual luma/chroma master scaling of the proven V3.1 math"
    "MPV" -> "Renderer properties including SDR intensity and saturation"
    "LUMA" -> "Pivot, contrast and highlight shaping"
    "CHROMA GATES" -> "Luma/saturation/shadow gates for chroma expansion"
    "COLOR VOLUME" -> "Base, midtone and highlight color-volume expansion"
    "SKIN" -> "Skin hue, luminance and chroma protection windows"
    "GAMUT" -> "RGB boundaries, safety margin and gamut iteration precision"
    "OUTPUT" -> "HDR-to-SDR compression"
    "VIEW" -> "Gamut/luma clipping diagnostics and curve graph mode"
    "DIAGNOSTIC" -> "Verify live A/B shader regeneration"
    "CONTROL" -> "Fine / normal / coarse adjustment granularity"
    else -> ""
  }
