package app.droidnative.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.droidnative.R
import app.droidnative.apps.GlBackend
import app.droidnative.apps.MouseMode
import app.droidnative.apps.Resolution
import app.droidnative.apps.Rotation
import kotlin.math.roundToInt

/** Dropdown over [options]; label of null is [defaultLabel] when given. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Choice(
    label: String,
    value: T,
    options: List<T>,
    text: @Composable (T) -> String,
    onChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { if (enabled) open = it }, modifier) {
        OutlinedTextField(text(value), {}, Modifier.fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
            readOnly = true, enabled = enabled, singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) })
        ExposedDropdownMenu(open, { open = false }) {
            for (o in options) DropdownMenuItem({ Text(text(o)) }, { onChange(o); open = false })
        }
    }
}

/**
 * Dropdown over a long list: typing into the field filters [options] by
 * [search] (default: the shown text), case-insensitively, by every word.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SearchChoice(
    label: String,
    value: T,
    options: List<T>,
    text: (T) -> String,
    onChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    search: (T) -> String = text,
) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val words = query.lowercase().split(' ').filter { it.isNotEmpty() }
    val shown = options.filter { o -> search(o).lowercase().let { s -> words.all { it in s } } }
    fun close() { open = false; query = ""; focus.clearFocus() }
    ExposedDropdownMenuBox(open, { if (it) open = true else close() }, modifier) {
        OutlinedTextField(if (open) query else text(value), { query = it; open = true },
            Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
            singleLine = true,
            label = { Text(label) },
            placeholder = { Text(stringResource(R.string.search_hint, text(value))) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) })
        ExposedDropdownMenu(open, { close() }) {
            if (shown.isEmpty()) DropdownMenuItem({ Text(stringResource(R.string.search_no_match)) }, {}, enabled = false)
            for (o in shown) DropdownMenuItem({ Text(text(o)) }, { onChange(o); close() })
        }
    }
}

/**
 * Resolution: pixels of the short side or a percentage. Empty means
 * [emptyLabel] (native, or the global setting). [onChange] gets valid
 * values only (null for empty).
 */
@Composable
fun ResolutionField(value: String?, shortSide: Int, longSide: Int, emptyLabel: String,
                    onChange: (String?) -> Unit) {
    var text by remember { mutableStateOf(value ?: "") }
    val factor = Resolution.factor(text, shortSide)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(text, { new ->
                text = new.filter { it.isDigit() || it == '%' || it == '.' }.take(6)
                if (text.isEmpty()) onChange(null)
                else if (Resolution.isValid(text, shortSide)) onChange(text)
            },
            Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.resolution)) },
            placeholder = { Text(emptyLabel) },
            singleLine = true,
            isError = factor == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            supportingText = {
                Text(if (factor != null) stringResource(R.string.resolution_hint)
                     else stringResource(R.string.resolution_invalid, Resolution.MIN_PIXELS, shortSide))
            })
        // Scrolls instead of squeezing the chips in narrow dialogs
        Row(Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (preset in listOf("", "75%", "50%", "720", "480")) {
                if (preset.isNotEmpty() && !Resolution.isValid(preset, shortSide)) continue
                FilterChip(text == preset, {
                        text = preset
                        onChange(preset.ifEmpty { null })
                    },
                    { Text(preset.ifEmpty { stringResource(R.string.resolution_default) }) })
            }
        }
        if (factor != null && text.isNotEmpty()) {
            Text(stringResource(R.string.resolution_result,
                    "${(longSide * factor).roundToInt()}x${(shortSide * factor).roundToInt()}", "${longSide}x$shortSide"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

val DPI_CHOICES = listOf(72, 96, 120, 144, 168, 192, 216, 240, 288, 336, 384)

@Composable
fun DpiChoice(value: Int?, defaultLabel: String, onChange: (Int?) -> Unit) {
    Choice(stringResource(R.string.dpi), value, listOf<Int?>(null) + DPI_CHOICES,
        { it?.let { d -> stringResource(R.string.dpi_value, d, d / 96f) } ?: defaultLabel }, onChange)
}

@Composable
fun mouseModeLabel(m: MouseMode?, default: String): String = when (m) {
    null -> default
    MouseMode.TOUCH -> stringResource(R.string.mouse_touch)
    MouseMode.MOUSE -> stringResource(R.string.mouse_mouse)
    MouseMode.TOUCHPAD -> stringResource(R.string.mouse_touchpad)
}

@Composable
fun backendLabel(b: GlBackend?): String = when (b) {
    null, GlBackend.AUTO -> stringResource(R.string.backend_auto)
    GlBackend.ZINK -> stringResource(R.string.backend_zink)
    GlBackend.GL4ES -> stringResource(R.string.backend_gl4es)
    GlBackend.LLVMPIPE -> stringResource(R.string.backend_llvmpipe)
}

@Composable
fun rotationLabel(r: Rotation?): String = when (r) {
    null, Rotation.AUTO -> stringResource(R.string.rotation_auto)
    Rotation.PORTRAIT -> stringResource(R.string.rotation_portrait)
    Rotation.LANDSCAPE -> stringResource(R.string.rotation_landscape)
}

/** Padding used by the settings forms. */
val FormPadding = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
