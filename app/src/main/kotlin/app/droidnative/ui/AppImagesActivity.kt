package app.droidnative.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.droidnative.R
import app.droidnative.apps.AppImages
import app.droidnative.apps.AppImages.Install
import app.droidnative.apps.LinuxApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Manage AppImages" from the Install tab: installs AppImages picked with
 * the system file picker and lists the installed ones, searchable, to
 * change their title and icon or remove them (apps/AppImages.kt).
 */
class AppImagesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DroidNativeTheme { AppImagesScreen(onBack = ::finish) } }
    }

    companion object {
        fun intent(context: Context) = Intent(context, AppImagesActivity::class.java)
    }
}

private class ImageRow(val item: AppImages.Item, val icon: ImageBitmap?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppImagesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val images = remember { AppImages(context) }
    val install by AppImages.install.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose {}
    }
    // A finished install changes the list
    LaunchedEffect(install is Install.Done) { refresh++ }
    var rows by remember { mutableStateOf<List<ImageRow>?>(null) }
    LaunchedEffect(refresh) {
        rows = withContext(Dispatchers.IO) { load(context, images) }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !AppImages.install(context, uri))
            Toast.makeText(context, R.string.appimage_busy, Toast.LENGTH_SHORT).show()
    }
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<ImageRow?>(null) }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.install_appimage_title)) },
            navigationIcon = {
                IconButton(onBack) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back))
                }
            })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(vertical = 8.dp)) {
            item {
                val busy = install is Install.Copying || install is Install.Unpacking
                // AppImages have no registered MIME type on most phones
                Button({ pick.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    enabled = !busy) {
                    Text(stringResource(R.string.appimage_install))
                }
            }
            install?.let { item { InstallCard(it) } }
            item {
                OutlinedTextField(query, { query = it },
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                    placeholder = { Text(stringResource(R.string.appimage_search)) },
                    singleLine = true,
                    leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton({ query = "" }) {
                            Icon(painterResource(R.drawable.ic_clear), contentDescription = null)
                        }
                    })
            }
            val list = rows
            if (list != null) {
                val shown = list.filter { it.item.entry.matches(query) || it.item.id.contains(query.trim(), true) }
                if (shown.isEmpty()) item {
                    Text(stringResource(if (list.isEmpty()) R.string.appimage_empty else R.string.appimage_no_match),
                        Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(shown, key = { it.item.id }) { row ->
                    Row(Modifier.fillMaxWidth().clickable { editing = row }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        RowIcon(row, 40.dp)
                        Column {
                            Text(row.item.entry.name, style = MaterialTheme.typography.bodyLarge)
                            Text(row.item.id, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    editing?.let { row ->
        EditSheet(row, images, onDismiss = { editing = null }, onChanged = {
            editing = null
            refresh++
        })
    }
}

private fun load(context: Context, images: AppImages): List<ImageRow> {
    val apps = LinuxApps(context)
    val items = images.list()
    // SVG-only icons need one rendering pass in the guest
    apps.convertIcons(items.map { it.entry })
    return items.map { ImageRow(it, apps.icon(it.entry)?.asImageBitmap()) }
}

/** Progress or result of the current install. */
@Composable
private fun InstallCard(state: Install) {
    OutlinedCard(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                is Install.Copying -> {
                    Text(stringResource(R.string.appimage_copying, state.name))
                    if (state.fraction >= 0f) LinearProgressIndicator({ state.fraction }, Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is Install.Unpacking -> {
                    Text(stringResource(R.string.appimage_unpacking, state.name))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is Install.Done -> DismissRow(stringResource(R.string.appimage_installed, state.name), null)
                is Install.Failed -> DismissRow(stringResource(R.string.appimage_failed, state.name), state.message)
            }
        }
    }
}

@Composable
private fun DismissRow(title: String, detail: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = if (detail != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(AppImages::dismiss) { Icon(painterResource(R.drawable.ic_clear), contentDescription = null) }
    }
}

@Composable
private fun RowIcon(row: ImageRow, size: Dp) {
    if (row.icon != null) {
        Image(row.icon, contentDescription = null, Modifier.size(size))
    } else {
        Box(Modifier.size(size).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center) {
            Text(row.item.entry.name.take(1).uppercase(), color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Title, icon and removal of one AppImage. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditSheet(row: ImageRow, images: AppImages, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val item = row.item
    var title by remember(item.id) { mutableStateOf(item.entry.name) }
    var working by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    // Runs a dn-appimage change off the main thread, then closes the sheet
    fun change(action: () -> Unit) {
        working = true
        scope.launch {
            val error = withContext(Dispatchers.IO) { runCatching(action).exceptionOrNull() }
            working = false
            if (error != null) Toast.makeText(context, context.getString(R.string.appimage_change_failed,
                item.entry.name, error.message ?: ""), Toast.LENGTH_LONG).show()
            onChanged()
        }
    }

    val pickIcon = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bitmap = AppImages.readImage(context, uri)
        if (bitmap == null) Toast.makeText(context, R.string.appimage_icon_unreadable, Toast.LENGTH_LONG).show()
        else change { images.setIcon(item.id, bitmap) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RowIcon(row, 48.dp)
            Column {
                Text(item.entry.name, style = MaterialTheme.typography.titleLarge)
                Text(item.id, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(title, { title = it.replace("\n", "").take(80) }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.appimage_title)) },
                supportingText = { Text(stringResource(R.string.appimage_title_hint)) },
                singleLine = true, enabled = !working)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ change { images.rename(item.id, title) } },
                    enabled = !working && title.trim() != item.entry.name) {
                    Text(stringResource(R.string.save))
                }
                OutlinedButton({ pickIcon.launch(arrayOf("image/*")) }, enabled = !working) {
                    Text(stringResource(R.string.appimage_icon_change))
                }
                if (item.customIcon) TextButton({ change { images.setIcon(item.id, null) } }, enabled = !working) {
                    Text(stringResource(R.string.appimage_icon_reset))
                }
            }
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton({ confirmRemove = true }, enabled = !working) {
                Text(stringResource(R.string.appimage_remove), color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmRemove) AlertDialog(onDismissRequest = { confirmRemove = false },
        title = { Text(stringResource(R.string.appimage_remove_title, item.entry.name)) },
        text = { Text(stringResource(R.string.appimage_remove_text)) },
        confirmButton = {
            TextButton({
                confirmRemove = false
                change { images.remove(item.id) }
            }) { Text(stringResource(R.string.appimage_remove), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton({ confirmRemove = false }) { Text(stringResource(android.R.string.cancel)) } })
}
