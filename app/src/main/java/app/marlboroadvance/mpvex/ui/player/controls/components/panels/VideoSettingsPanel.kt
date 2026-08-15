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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.preferences.DecoderPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.ui.player.FilterPreset
import app.marlboroadvance.mpvex.ui.player.VideoFilters
import app.marlboroadvance.mpvex.ui.theme.spacing
import `is`.xyz.mpv.MPVLib
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/**
 * Native Shader Lab workstation.
 *
 * The first source-native pass intentionally owns all touch interaction inside
 * the panel so player gestures underneath cannot keep running after finger-up.
 * "Original" means the MPV state captured the first time Shader Lab is opened
 * for the current media item. Comparison never overwrites the saved lab values.
 */
@Composable
fun VideoSettingsPanel(
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val decoderPreferences = koinInject<DecoderPreferences>()
  ShaderLabSession.ensureForCurrentMedia(decoderPreferences)

  var selectedGroup by remember { mutableStateOf(ShaderLabGroup.COLOR) }
  var bypassed by remember { mutableStateOf(false) }
  val holdInteraction = remember { MutableInteractionSource() }
  val holdOriginal by holdInteraction.collectIsPressedAsState()
  val comparisonActive = bypassed || holdOriginal

  // Press-down switches to baseline; finger-up/cancel restores immediately.
  LaunchedEffect(holdOriginal, bypassed) {
    if (comparisonActive) {
      ShaderLabSession.applyBaseline()
    } else {
      ShaderLabSession.applyCurrent(decoderPreferences)
    }
  }

  // Never leave a temporary comparison active after closing the panel.
  DisposableEffect(Unit) {
    onDispose {
      ShaderLabSession.applyCurrent(decoderPreferences)
    }
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
          horizontalArrangement = Arrangement.SpaceBetween,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column {
            Text(
              text = "Shader Lab",
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.SemiBold,
            )
            Text(
              text = "Native live workstation",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Spacer(Modifier.weight(1f))
          IconButton(onClick = onDismissRequest) {
            Icon(Icons.Default.Close, contentDescription = "Close Shader Lab", modifier = Modifier.size(30.dp))
          }
        }

        ShaderLabCompareBar(
          bypassed = bypassed,
          holdOriginal = holdOriginal,
          holdInteraction = holdInteraction,
          onToggleBypass = { bypassed = !bypassed },
          onRevert = {
            ShaderLabSession.revertToBaseline(decoderPreferences)
            bypassed = false
          },
        )

        ShaderLabGroupBar(
          selected = selectedGroup,
          onSelect = { selectedGroup = it },
        )
      }
    },
  ) {
    Column(
      modifier = Modifier.padding(MaterialTheme.spacing.medium),
      verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
    ) {
      when (selectedGroup) {
        ShaderLabGroup.COLOR -> {
          ShaderLabSectionTitle("Color", "Live MPV color controls")
          ShaderFilterControl(VideoFilters.SATURATION, decoderPreferences, comparisonActive)
          ShaderFilterControl(VideoFilters.HUE, decoderPreferences, comparisonActive)
        }

        ShaderLabGroup.TONE -> {
          ShaderLabSectionTitle("Tone", "Brightness, contrast and transfer response")
          ShaderFilterControl(VideoFilters.BRIGHTNESS, decoderPreferences, comparisonActive)
          ShaderFilterControl(VideoFilters.CONTRAST, decoderPreferences, comparisonActive)
          ShaderFilterControl(VideoFilters.GAMMA, decoderPreferences, comparisonActive)
        }

        ShaderLabGroup.DETAIL -> {
          ShaderLabSectionTitle("Detail", "Precision detail and debanding")
          ShaderFilterControl(VideoFilters.SHARPNESS, decoderPreferences, comparisonActive)
          VideoSettingsDebandCard()
        }

        ShaderLabGroup.HDR -> {
          ShaderLabSectionTitle("HDR / Brightness", "Pixel expanded-brightness tuning")
          SdrIntensityControl(comparisonActive)
          Text(
            text = "SDR intensity is applied live. Your video-start value is preserved for Original / Bypass comparison.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        ShaderLabGroup.PRESETS -> {
          ShaderLabSectionTitle("Presets", "Built-in starting points; every value remains editable")
          ShaderPresetGrid(
            decoderPreferences = decoderPreferences,
            comparisonActive = comparisonActive,
          )
        }
      }
    }
  }
}

@Composable
private fun ShaderLabCompareBar(
  bypassed: Boolean,
  holdOriginal: Boolean,
  holdInteraction: MutableInteractionSource,
  onToggleBypass: () -> Unit,
  onRevert: () -> Unit,
) {
  Card(
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.74f),
    ),
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(top = 6.dp, bottom = 8.dp),
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
          onClick = onToggleBypass,
          label = { Text(if (bypassed) "BYPASS ON" else "Bypass") },
        )

        Surface(
          shape = MaterialTheme.shapes.large,
          color =
            if (holdOriginal) {
              MaterialTheme.colorScheme.primaryContainer
            } else {
              MaterialTheme.colorScheme.surfaceContainer
            },
          contentColor =
            if (holdOriginal) {
              MaterialTheme.colorScheme.onPrimaryContainer
            } else {
              MaterialTheme.colorScheme.onSurface
            },
          modifier =
            Modifier
              .weight(1f)
              .clickable(
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

      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text =
            when {
              holdOriginal -> "Previewing video-start state — release to return"
              bypassed -> "Video-start state locked for comparison"
              else -> "Touch-and-hold Original for instant A/B comparison"
            },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRevert) {
          Text("Revert to start")
        }
      }
    }
  }
}

@Composable
private fun ShaderLabGroupBar(
  selected: ShaderLabGroup,
  onSelect: (ShaderLabGroup) -> Unit,
) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .padding(bottom = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    ShaderLabGroup.entries.forEach { group ->
      FilterChip(
        selected = selected == group,
        onClick = { onSelect(group) },
        label = { Text(group.label) },
      )
    }
  }
}

@Composable
private fun ShaderLabSectionTitle(
  title: String,
  subtitle: String,
) {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text(
      subtitle,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun ShaderFilterControl(
  filter: VideoFilters,
  decoderPreferences: DecoderPreferences,
  comparisonActive: Boolean,
) {
  val value by filter.preference(decoderPreferences).collectAsState()

  fun setValue(newValue: Int) {
    val clamped = newValue.coerceIn(filter.min, filter.max)
    filter.preference(decoderPreferences).set(clamped)
    if (!comparisonActive) {
      MPVLib.setPropertyInt(filter.mpvProperty, clamped)
    }
  }

  Card(
    colors = panelCardsColors(),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = stringResource(filter.titleRes),
          style = MaterialTheme.typography.titleSmall,
          modifier = Modifier.weight(1f),
        )
        Text(
          text = value.toString(),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
        )
      }

      Slider(
        value = value.toFloat(),
        onValueChange = { setValue(it.roundToInt()) },
        valueRange = filter.min.toFloat()..filter.max.toFloat(),
        enabled = !comparisonActive,
        modifier = Modifier.fillMaxWidth(),
      )

      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
      ) {
        OutlinedButton(
          onClick = { setValue(value - 1) },
          enabled = !comparisonActive && value > filter.min,
          modifier = Modifier.weight(1f),
        ) {
          Text("−")
        }
        OutlinedButton(
          onClick = { setValue(0) },
          enabled = !comparisonActive && value != 0,
          modifier = Modifier.weight(1f),
        ) {
          Text("Reset")
        }
        OutlinedButton(
          onClick = { setValue(value + 1) },
          enabled = !comparisonActive && value < filter.max,
          modifier = Modifier.weight(1f),
        ) {
          Text("+")
        }
      }
    }
  }
}

@Composable
private fun SdrIntensityControl(comparisonActive: Boolean) {
  var value by remember(ShaderLabSession.mediaKey) {
    mutableStateOf(ShaderLabSession.currentSdrIntensity.toFloat())
  }

  fun setValue(newValue: Float) {
    val rounded = ((newValue * 20f).roundToInt() / 20f).coerceIn(0.5f, 6.0f)
    value = rounded
    ShaderLabSession.currentSdrIntensity = rounded.toDouble()
    if (!comparisonActive) {
      MPVLib.setPropertyDouble("sdr-intensity", rounded.toDouble())
    }
  }

  Card(
    colors = panelCardsColors(),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text("SDR Intensity", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(
          text = String.format("%.2f", value),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
        )
      }
      Slider(
        value = value,
        onValueChange = ::setValue,
        valueRange = 0.5f..6.0f,
        enabled = !comparisonActive,
      )
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        OutlinedButton(
          onClick = { setValue(value - 0.05f) },
          enabled = !comparisonActive && value > 0.5f,
          modifier = Modifier.weight(1f),
        ) { Text("−0.05") }
        OutlinedButton(
          onClick = { setValue(ShaderLabSession.baselineSdrIntensity.toFloat()) },
          enabled = !comparisonActive,
          modifier = Modifier.weight(1f),
        ) { Text("Start") }
        OutlinedButton(
          onClick = { setValue(value + 0.05f) },
          enabled = !comparisonActive && value < 6.0f,
          modifier = Modifier.weight(1f),
        ) { Text("+0.05") }
      }
    }
  }
}

@Composable
private fun ShaderPresetGrid(
  decoderPreferences: DecoderPreferences,
  comparisonActive: Boolean,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    FilterPreset.entries.forEach { preset ->
      Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.82f),
        modifier =
          Modifier
            .fillMaxWidth()
            .clickable(enabled = !comparisonActive) {
              applyPreset(preset, decoderPreferences, comparisonActive)
            },
      ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
          Text(preset.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
          Text(
            preset.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

private fun applyPreset(
  preset: FilterPreset,
  decoderPreferences: DecoderPreferences,
  comparisonActive: Boolean,
) {
  val values =
    mapOf(
      VideoFilters.BRIGHTNESS to preset.brightness,
      VideoFilters.SATURATION to preset.saturation,
      VideoFilters.CONTRAST to preset.contrast,
      VideoFilters.GAMMA to preset.gamma,
      VideoFilters.HUE to preset.hue,
      VideoFilters.SHARPNESS to preset.sharpness,
    )

  values.forEach { (filter, value) ->
    filter.preference(decoderPreferences).set(value)
    if (!comparisonActive) {
      MPVLib.setPropertyInt(filter.mpvProperty, value)
    }
  }
}

private enum class ShaderLabGroup(val label: String) {
  COLOR("Color"),
  TONE("Tone"),
  DETAIL("Detail"),
  HDR("HDR"),
  PRESETS("Presets"),
}

private object ShaderLabSession {
  var mediaKey: String = ""
    private set

  var baselineFilters: Map<VideoFilters, Int> = emptyMap()
    private set

  var baselineSdrIntensity: Double = 1.0
    private set

  var currentSdrIntensity: Double = 1.0

  fun ensureForCurrentMedia(decoderPreferences: DecoderPreferences) {
    val key =
      MPVLib.getPropertyString("path")
        ?: MPVLib.getPropertyString("media-title")
        ?: "<unknown>"

    if (key == mediaKey && baselineFilters.isNotEmpty()) return

    mediaKey = key
    baselineFilters =
      VideoFilters.entries.associateWith { filter ->
        MPVLib.getPropertyInt(filter.mpvProperty)
          ?: filter.preference(decoderPreferences).get()
      }

    baselineSdrIntensity = MPVLib.getPropertyDouble("sdr-intensity") ?: 1.0
    currentSdrIntensity = baselineSdrIntensity
  }

  fun applyBaseline() {
    baselineFilters.forEach { (filter, value) ->
      MPVLib.setPropertyInt(filter.mpvProperty, value)
    }
    MPVLib.setPropertyDouble("sdr-intensity", baselineSdrIntensity)
  }

  fun applyCurrent(decoderPreferences: DecoderPreferences) {
    VideoFilters.entries.forEach { filter ->
      MPVLib.setPropertyInt(filter.mpvProperty, filter.preference(decoderPreferences).get())
    }
    MPVLib.setPropertyDouble("sdr-intensity", currentSdrIntensity)
  }

  fun revertToBaseline(decoderPreferences: DecoderPreferences) {
    baselineFilters.forEach { (filter, value) ->
      filter.preference(decoderPreferences).set(value)
    }
    currentSdrIntensity = baselineSdrIntensity
    applyBaseline()
  }
}
