package app.droidnative.overlay

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.droidnative.R
import app.droidnative.ui.Choice
import app.droidnative.ui.SearchChoice
import app.droidnative.ui.DroidNativeTheme
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Editor for a button layout. The canvas is the screen without system
 * bars and cutout, the area a window gets, so positions match; toolbar
 * and properties float on top.
 * Tap selects a button or joystick, drag moves it, pinch resizes it,
 * double tap opens its properties; swiping the panel down closes them. Saved when the
 * editor is left.
 */
class OverlayEditorActivity : ComponentActivity() {

    private lateinit var store: OverlayStore
    private var layout by mutableStateOf<OverlayLayout?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = OverlayStore(this)
        layout = intent.getStringExtra(EXTRA_LAYOUT)?.let { store.get(it) }
        if (layout == null) {
            finish()
            return
        }
        setContent {
            DroidNativeTheme {
                layout?.let { l -> Editor(l, { layout = it }, { finish() }) }
            }
        }
    }

    override fun onPause() {
        layout?.let { store.save(it) }
        super.onPause()
    }

    companion object {
        private const val EXTRA_LAYOUT = "layout"

        fun intent(context: Context, layoutId: String) =
            Intent(context, OverlayEditorActivity::class.java).putExtra(EXTRA_LAYOUT, layoutId)
    }
}

@Composable
private fun Editor(layout: OverlayLayout, onChange: (OverlayLayout) -> Unit, onDone: () -> Unit) {
    var canvas by remember { mutableStateOf(IntSize(1, 1)) }
    var selected by remember { mutableStateOf<String?>(null) }
    // Properties panel open for the selected button (double tap)
    var editing by remember { mutableStateOf(false) }
    var groupsOpen by remember { mutableStateOf(false) }
    var layoutOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    val density = LocalDensity.current.density
    // Gesture handlers outlive recompositions: they must see the latest layout
    val current by rememberUpdatedState(layout)

    fun update(b: OverlayButton) = onChange(current.copy(buttons = current.buttons.map { if (it.id == b.id) b else it }))

    Box(Modifier.fillMaxSize().background(Color.Black)
        .pointerInput(Unit) { detectTapGestures { selected = null; editing = false } }) {

        // Same area as the window: without system bars and cutout
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
            .background(Color(0xFF2B2F36)).onSizeChanged { canvas = it }) {
            for (b in layout.buttons) {
                val w = b.width * density
                val h = b.height * density
                val x = (b.x * canvas.width - w / 2).coerceIn(0f, (canvas.width - w).coerceAtLeast(0f))
                val y = (b.y * canvas.height - h / 2).coerceIn(0f, (canvas.height - h).coerceAtLeast(0f))
                val hiddenAtStart = b.group != null && layout.groups.firstOrNull { it.id == b.group }?.visible == false
                val isSelected = b.id == selected
                val shape = if (b.isJoystick) CircleShape else RoundedCornerShape(12.dp)
                Box(Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                    .size(b.width.dp, b.height.dp)
                    .alpha(if (hiddenAtStart) 0.5f else 1f)
                    .background(Color.Black.copy(alpha = layout.opacity / 2), shape)
                    .border(if (isSelected) 3.dp else 2.dp,
                        if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = layout.opacity),
                        shape)
                    .pointerInput(b.id) {
                        detectTapGestures(
                            onTap = { selected = b.id },
                            onDoubleTap = { selected = b.id; editing = true })
                    }
                    .pointerInput(b.id, canvas) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            selected = b.id
                            val cur = current.buttons.firstOrNull { it.id == b.id } ?: return@detectTransformGestures
                            val w = (cur.width * zoom).roundToInt().coerceIn(MIN_SIZE, MAX_SIZE)
                            if (cur.isJoystick) update(cur.copy(
                                x = (cur.x + pan.x / canvas.width).coerceIn(0f, 1f),
                                y = (cur.y + pan.y / canvas.height).coerceIn(0f, 1f),
                                width = w, height = w))
                            else update(cur.copy(
                                x = (cur.x + pan.x / canvas.width).coerceIn(0f, 1f),
                                y = (cur.y + pan.y / canvas.height).coerceIn(0f, 1f),
                                width = (cur.width * zoom).roundToInt().coerceIn(MIN_SIZE, MAX_SIZE),
                                height = (cur.height * zoom).roundToInt().coerceIn(MIN_SIZE, MAX_SIZE)))
                        }
                    },
                    contentAlignment = Alignment.Center) {
                    Text(b.label, color = Color.White, textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelLarge)
                    (b.action as? ButtonAction.Joystick)?.let { stick ->
                        // Direction labels near the rim, as in the window
                        val n = stick.outputs.size
                        for ((i, label) in stickLabels(stick).withIndex()) {
                            val a = Math.toRadians(stick.rotation + i * 360.0 / n)
                            val rim = b.width * 0.36f
                            Text(label, Modifier.offset((rim * sin(a)).toFloat().dp, (-rim * cos(a)).toFloat().dp),
                                color = Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }

        // Toolbar
        Surface(Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(8.dp),
            shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp, shadowElevation = 4.dp) {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onDone) { Text(stringResource(R.string.overlay_done)) }
                Box {
                    TextButton({ addOpen = true }) { Text(stringResource(R.string.overlay_add)) }
                    fun add(b: OverlayButton) {
                        onChange(layout.copy(buttons = layout.buttons + b))
                        selected = b.id
                        editing = false
                        addOpen = false
                    }
                    // Upper half: the properties panel covers the lower part
                    DropdownMenu(addOpen, { addOpen = false }) {
                        DropdownMenuItem({ Text(stringResource(R.string.overlay_add_button)) },
                            { add(OverlayButton(label = "Space", x = 0.5f, y = 0.3f)) })
                        for ((preset, name) in STICK_PRESETS) DropdownMenuItem({ Text(stringResource(name)) },
                            { add(OverlayButton(label = "", x = 0.5f, y = 0.3f, width = 140, height = 140, action = preset)) })
                    }
                }
                TextButton({ groupsOpen = true }) { Text(stringResource(R.string.overlay_groups)) }
                TextButton({ layoutOpen = true }) { Text(stringResource(R.string.overlay_layout)) }
            }
        }

        // Properties of the selected button
        layout.buttons.firstOrNull { it.id == selected }?.takeIf { editing }?.let { b ->
            Surface(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(8.dp)
                    .fillMaxWidth().heightIn(max = 360.dp),
                shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp, shadowElevation = 4.dp) {
                ButtonProperties(b, layout, ::update, onClose = { editing = false },
                    onDuplicate = {
                        val copy = b.copy(id = newId(), x = (b.x + 0.05f).coerceAtMost(1f), y = (b.y + 0.05f).coerceAtMost(1f))
                        onChange(layout.copy(buttons = layout.buttons + copy))
                        selected = copy.id
                    },
                    onDelete = {
                        onChange(layout.copy(buttons = layout.buttons.filter { it.id != b.id }))
                        selected = null
                        editing = false
                    })
            }
        }
    }

    if (groupsOpen) GroupsDialog(layout, onChange) { groupsOpen = false }
    if (layoutOpen) LayoutDialog(layout, onChange) { layoutOpen = false }
}

private enum class ActionKind(val label: Int) {
    KEYS(R.string.overlay_action_keys), MOUSE(R.string.overlay_action_mouse),
    WHEEL(R.string.overlay_action_wheel), KEYBOARD(R.string.overlay_action_keyboard),
    TOGGLE(R.string.overlay_action_toggle);

    companion object {
        fun of(a: ButtonAction) = when (a) {
            is ButtonAction.Keys -> KEYS
            is ButtonAction.Mouse -> MOUSE
            is ButtonAction.Wheel -> WHEEL
            is ButtonAction.Keyboard -> KEYBOARD
            is ButtonAction.ToggleGroup -> TOGGLE
            is ButtonAction.Joystick -> null
        }
    }
}

@Composable
private fun ButtonProperties(
    b: OverlayButton, layout: OverlayLayout, update: (OverlayButton) -> Unit,
    onClose: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit,
) {
    val closeDistance = with(LocalDensity.current) { 48.dp.toPx() }
    // Swiping down closes the panel: on the handle, or anywhere once the
    // content is scrolled to the top (the scroll leaves the rest over)
    val swipe = remember(onClose) {
        object : NestedScrollConnection {
            var pulled = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0) {
                    pulled += available.y
                    if (pulled > closeDistance) { pulled = 0f; onClose() }
                    return available
                }
                pulled = 0f
                return Offset.Zero
            }
        }
    }
    Column {
        var handlePull by remember { mutableStateOf(0f) }
        Box(Modifier.fillMaxWidth().padding(top = 8.dp)
            .pointerInput(onClose) {
                detectVerticalDragGestures(onDragStart = { handlePull = 0f }) { _, dy ->
                    handlePull += dy
                    if (handlePull > closeDistance) onClose()
                }
            }, contentAlignment = Alignment.Center) {
            Box(Modifier.padding(vertical = 6.dp).size(width = 36.dp, height = 4.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(2.dp)))
        }
        Column(Modifier.nestedScroll(swipe).verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(b.label, { update(b.copy(label = it.take(24))) }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.overlay_label)) }, singleLine = true)
            val stick = b.action as? ButtonAction.Joystick
            if (stick != null) JoystickProperties(b, stick, update)
            else ButtonActionProperties(b, layout, update)

            Choice(stringResource(R.string.overlay_member_of), b.group, listOf<String?>(null) + layout.groups.map { it.id },
                { id -> if (id == null) stringResource(R.string.overlay_always_visible)
                        else layout.groups.firstOrNull { it.id == id }?.name ?: "-" },
                { update(b.copy(group = it)) })

            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onDuplicate) { Text(stringResource(R.string.overlay_duplicate)) }
                TextButton(onDelete) {
                    Text(stringResource(R.string.overlay_delete_button), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun ButtonActionProperties(b: OverlayButton, layout: OverlayLayout, update: (OverlayButton) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.overlay_size, b.width, b.height), style = MaterialTheme.typography.bodySmall)
        Slider(b.width.toFloat(), { update(b.copy(width = it.roundToInt())) },
            valueRange = MIN_SIZE.toFloat()..MAX_SIZE.toFloat())
        Slider(b.height.toFloat(), { update(b.copy(height = it.roundToInt())) },
            valueRange = MIN_SIZE.toFloat()..MAX_SIZE.toFloat())

        Choice(stringResource(R.string.overlay_action), ActionKind.of(b.action) ?: ActionKind.KEYS, ActionKind.entries.toList(),
            { stringResource(it.label) }, { kind ->
                val action = when (kind) {
                    ActionKind.KEYS -> ButtonAction.Keys(listOf("space"))
                    ActionKind.MOUSE -> ButtonAction.Mouse(MouseButton.LEFT)
                    ActionKind.WHEEL -> ButtonAction.Wheel(true)
                    ActionKind.KEYBOARD -> ButtonAction.Keyboard
                    ActionKind.TOGGLE -> ButtonAction.ToggleGroup(layout.groups.firstOrNull()?.id ?: "")
                }
                if (ActionKind.of(b.action) != kind) update(b.copy(action = action))
            })

        when (val a = b.action) {
            is ButtonAction.Keys -> {
                val mods = Keys.Modifier.entries.filter { it.key in a.keys }
                val main = a.keys.firstOrNull { k -> Keys.Modifier.entries.none { it.key == k } }
                    ?: a.keys.firstOrNull() ?: "space"
                fun set(main: String, mods: List<Keys.Modifier>) =
                    update(b.copy(action = ButtonAction.Keys((mods.map { it.key } + main).distinct())))
                SearchChoice(stringResource(R.string.overlay_key), main, Keys.ALL.map { it.name },
                    { Keys.byName(it)?.label ?: it }, { set(it, mods) },
                    search = { n -> Keys.byName(n)?.let { Keys.searchText(it) } ?: n })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in Keys.Modifier.entries) {
                        FilterChip(m in mods, { set(main, if (m in mods) mods - m else mods + m) },
                            { Text(m.label) })
                    }
                }
            }
            is ButtonAction.Mouse -> Choice(stringResource(R.string.overlay_mouse_button), a.button,
                MouseButton.entries.toList(), {
                    stringResource(when (it) {
                        MouseButton.LEFT -> R.string.overlay_mouse_left
                        MouseButton.RIGHT -> R.string.overlay_mouse_right
                        MouseButton.MIDDLE -> R.string.overlay_mouse_middle
                    })
                }, { update(b.copy(action = ButtonAction.Mouse(it))) })
            is ButtonAction.Wheel -> Choice(stringResource(R.string.overlay_wheel_direction), a.up, listOf(true, false),
                { stringResource(if (it) R.string.overlay_wheel_up else R.string.overlay_wheel_down) },
                { update(b.copy(action = ButtonAction.Wheel(it))) })
            is ButtonAction.Keyboard -> {}
            is ButtonAction.ToggleGroup ->
                if (layout.groups.isEmpty()) Text(stringResource(R.string.overlay_no_groups),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                else Choice(stringResource(R.string.overlay_toggle_target), a.group, layout.groups.map { it.id },
                    { id -> layout.groups.firstOrNull { it.id == id }?.name ?: "-" },
                    { update(b.copy(action = ButtonAction.ToggleGroup(it))) })
            is ButtonAction.Joystick -> {}
        }
    }
}

private val STICK_PRESETS = listOf(
    ButtonAction.Joystick.WASD to R.string.overlay_stick_wasd,
    ButtonAction.Joystick.ARROWS to R.string.overlay_stick_arrows,
    ButtonAction.Joystick.SCROLL to R.string.overlay_stick_scroll,
)

private val STICK_PRESET_CHIPS = listOf(
    ButtonAction.Joystick.WASD to R.string.overlay_chip_wasd,
    ButtonAction.Joystick.ARROWS to R.string.overlay_chip_arrows,
    ButtonAction.Joystick.SCROLL to R.string.overlay_chip_scroll,
)

private val COMPASS = listOf(R.string.overlay_dir_up, R.string.overlay_dir_up_right, R.string.overlay_dir_right,
    R.string.overlay_dir_down_right, R.string.overlay_dir_down, R.string.overlay_dir_down_left,
    R.string.overlay_dir_left, R.string.overlay_dir_up_left)

@Composable
private fun directionName(angle: Int): String {
    val a = Math.floorMod(angle, 360)
    return if (a % 45 == 0) stringResource(COMPASS[a / 45]) else stringResource(R.string.overlay_dir_degrees, a)
}

/** Short labels of a joystick's directions, empty for unused ones. */
private fun stickLabels(stick: ButtonAction.Joystick): List<String> = stick.outputs.map { o ->
    when (o) {
        is StickOutput.Key -> Keys.byName(o.name)?.label ?: "?"
        is StickOutput.Wheel -> if (o.up) "^" else "v"
        null -> ""
    }
}

@Composable
private fun JoystickProperties(b: OverlayButton, stick: ButtonAction.Joystick, update: (OverlayButton) -> Unit) {
    fun set(s: ButtonAction.Joystick) = update(b.copy(action = s))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.overlay_stick_size, b.width), style = MaterialTheme.typography.bodySmall)
        Slider(b.width.toFloat(), { val w = it.roundToInt(); update(b.copy(width = w, height = w)) },
            valueRange = MIN_SIZE.toFloat()..MAX_SIZE.toFloat())

        Text(stringResource(R.string.overlay_stick_preset), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((preset, name) in STICK_PRESET_CHIPS)
                FilterChip(stick == preset, { set(preset) }, { Text(stringResource(name)) })
        }

        val n = stick.outputs.size
        Choice(stringResource(R.string.overlay_stick_directions), n,
            (ButtonAction.Joystick.MIN_DIRECTIONS..ButtonAction.Joystick.MAX_DIRECTIONS).toList(), { "$it" },
            { count -> set(stick.copy(outputs = List(count) { stick.outputs.getOrNull(it) })) })
        Choice(stringResource(R.string.overlay_stick_first), stick.rotation, (0 until 360 step 45).toList(),
            { directionName(it) }, { set(stick.copy(rotation = it)) })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.overlay_stick_multi))
                Text(stringResource(if (stick.multi) R.string.overlay_stick_multi_on else R.string.overlay_stick_multi_off),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(stick.multi, { set(stick.copy(multi = it)) }, Modifier.padding(start = 8.dp))
        }

        val none = stringResource(R.string.overlay_none)
        val wheelUp = stringResource(R.string.overlay_stick_wheel_up)
        val wheelDown = stringResource(R.string.overlay_stick_wheel_down)
        val options = listOf<StickOutput?>(null, StickOutput.Wheel(true), StickOutput.Wheel(false)) +
            Keys.ALL.map { StickOutput.Key(it.name) }
        fun label(o: StickOutput?) = when (o) {
            null -> none
            is StickOutput.Wheel -> if (o.up) wheelUp else wheelDown
            is StickOutput.Key -> Keys.byName(o.name)?.label ?: o.name
        }
        for (i in 0 until n) {
            SearchChoice(directionName(stick.rotation + (i * 360f / n).roundToInt()), stick.outputs[i], options,
                ::label, { o -> set(stick.copy(outputs = stick.outputs.toMutableList().also { it[i] = o })) },
                search = { o -> if (o is StickOutput.Key) Keys.byName(o.name)?.let { Keys.searchText(it) } ?: o.name
                                else label(o) + " mouse wheel scroll" })
        }
    }
}

@Composable
private fun GroupsDialog(layout: OverlayLayout, onChange: (OverlayLayout) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.overlay_groups)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.overlay_groups_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (g in layout.groups) {
                    fun set(n: ButtonGroup) = onChange(layout.copy(groups = layout.groups.map { if (it.id == g.id) n else it }))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(g.name, { set(g.copy(name = it.take(24))) }, Modifier.weight(1f),
                            singleLine = true, label = { Text(stringResource(R.string.overlay_group_name)) })
                        Switch(g.visible, { set(g.copy(visible = it)) }, Modifier.padding(start = 8.dp))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(if (g.visible) R.string.overlay_group_visible else R.string.overlay_group_hidden),
                            style = MaterialTheme.typography.bodySmall)
                        TextButton({
                            onChange(layout.copy(
                                groups = layout.groups.filter { it.id != g.id },
                                // Members become always visible; mode buttons lose their target
                                buttons = layout.buttons.map { b ->
                                    if (b.group == g.id) b.copy(group = null) else b
                                }))
                        }) { Text(stringResource(R.string.overlay_delete_group), color = MaterialTheme.colorScheme.error) }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.overlay_done)) } },
        dismissButton = {
            TextButton({
                val n = layout.groups.size + 1
                onChange(layout.copy(groups = layout.groups + ButtonGroup(name = "Group $n")))
            }) { Text(stringResource(R.string.overlay_add_group)) }
        })
}

@Composable
private fun LayoutDialog(layout: OverlayLayout, onChange: (OverlayLayout) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.overlay_layout)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(layout.name, { onChange(layout.copy(name = it.take(40))) }, Modifier.fillMaxWidth(),
                    singleLine = true, label = { Text(stringResource(R.string.overlay_layout_name)) })
                Text(stringResource(R.string.overlay_opacity, (layout.opacity * 100).roundToInt()))
                Slider(layout.opacity, { onChange(layout.copy(opacity = it)) }, valueRange = 0.1f..1f)
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.overlay_done)) } })
}

private const val MIN_SIZE = 24
private const val MAX_SIZE = 240
