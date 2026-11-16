@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.example.filemanager

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StatFs
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------- helpers

fun Kind.icon(): ImageVector = when (this) {
    Kind.FOLDER -> Icons.Default.Folder
    Kind.IMAGE -> Icons.Default.Image
    Kind.VIDEO -> Icons.Default.Movie
    Kind.AUDIO -> Icons.Default.MusicNote
    Kind.DOC -> Icons.Default.Description
    Kind.APK -> Icons.Default.Android
    Kind.ARCHIVE -> Icons.Default.FolderZip
    Kind.OTHER -> Icons.Default.InsertDriveFile
}

fun Category.icon(): ImageVector = when (this) {
    Category.RECENT -> Icons.Default.History
    Category.IMAGES -> Icons.Default.Image
    Category.VIDEOS -> Icons.Default.Movie
    Category.AUDIO -> Icons.Default.MusicNote
    Category.DOCS -> Icons.Default.Description
    Category.APKS -> Icons.Default.Android
    Category.ARCHIVES -> Icons.Default.FolderZip
}

private fun fileUri(ctx: Context, f: File): Uri =
    FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", f)

fun openFile(ctx: Context, f: File) {
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
    val i = Intent(Intent.ACTION_VIEW)
        .setDataAndType(fileUri(ctx, f), mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        ctx.startActivity(i)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "No app found to open this file", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(ctx, "Cannot open: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

fun shareFiles(ctx: Context, files: List<File>) {
    val uris = ArrayList(files.filter { it.isFile }.map { fileUri(ctx, it) })
    if (uris.isEmpty()) {
        Toast.makeText(ctx, "Select files (not folders) to share", Toast.LENGTH_SHORT).show()
        return
    }
    val i = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    }
    i.type = "*/*"
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, "Share"))
}

// ---------------------------------------------------------------- root

@Composable
fun Root(vm: FmViewModel) {
    if (!vm.hasAccess) {
        PermissionScreen()
        return
    }
    val ctx = LocalContext.current
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snack.showSnackbar(it) } }
    BackHandler(enabled = vm.canGoBack()) { vm.back() }

    Scaffold(
        topBar = { TopBar(vm) },
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            if (vm.clipboard.isNotEmpty() && !vm.inHome && vm.category == null) PasteBar(vm)
        },
        floatingActionButton = {
            if (!vm.inHome && vm.category == null && vm.selected.isEmpty() && !vm.searching) NewFab(vm)
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (vm.inHome) HomeScreen(vm) else Browser(vm, ctx)
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
    Dialogs(vm)
}

@Composable
fun PermissionScreen() {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Folder, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("All files access needed", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "To browse and manage your files, allow \"All files access\" for this app, then come back.",
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = {
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                )
            } catch (e: Exception) {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }) { Text("Grant access") }
    }
}

// ---------------------------------------------------------------- top bars

@Composable
fun TopBar(vm: FmViewModel) {
    when {
        vm.selected.isNotEmpty() -> SelectionBar(vm)
        vm.searching -> SearchBar(vm)
        else -> TopAppBar(
            title = { Text(vm.title(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                if (!vm.inHome) IconButton(onClick = { vm.back() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
            },
            actions = {
                if (!vm.inHome) {
                    if (vm.category == null) {
                        IconButton(onClick = { vm.startSearch() }) { Icon(Icons.Default.Search, "Search") }
                    }
                    IconButton(onClick = { vm.grid = !vm.grid }) {
                        Icon(if (vm.grid) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView, "View")
                    }
                    SortMenu(vm)
                    MoreMenu(vm)
                }
            }
        )
    }
}

@Composable
fun SearchBar(vm: FmViewModel) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.requestFocus() }
    TopAppBar(
        title = {
            TextField(
                value = vm.query,
                onValueChange = { vm.onQuery(it) },
                placeholder = { Text("Search in this folder") },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(fr)
            )
        },
        navigationIcon = {
            IconButton(onClick = { vm.exitSearch() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") }
        }
    )
}

@Composable
fun SortMenu(vm: FmViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortBy.values().forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.label) },
                    leadingIcon = { if (vm.sortBy == s) Icon(Icons.Default.Check, null) },
                    onClick = { vm.sortBy = s; open = false; vm.reload() }
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (vm.asc) "Ascending" else "Descending") },
                onClick = { vm.asc = !vm.asc; open = false; vm.reload() }
            )
        }
    }
}

@Composable
fun MoreMenu(vm: FmViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Show hidden files") },
                leadingIcon = { if (vm.showHidden) Icon(Icons.Default.Check, null) },
                onClick = { vm.showHidden = !vm.showHidden; open = false; vm.reload() }
            )
            DropdownMenuItem(
                text = { Text("Select all") },
                onClick = { open = false; vm.selectAll() }
            )
            DropdownMenuItem(
                text = { Text("Refresh") },
                onClick = { open = false; vm.reload() }
            )
            DropdownMenuItem(
                text = { Text("Home") },
                onClick = { open = false; vm.goHome() }
            )
        }
    }
}

@Composable
fun SelectionBar(vm: FmViewModel) {
    val ctx = LocalContext.current
    var more by remember { mutableStateOf(false) }
    val sel = vm.selected.toList()
    val single = sel.singleOrNull()
    TopAppBar(
        title = { Text("${sel.size} selected") },
        navigationIcon = {
            IconButton(onClick = { vm.selected = emptySet() }) { Icon(Icons.Default.Close, "Clear") }
        },
        actions = {
            IconButton(onClick = { vm.selectAll() }) { Icon(Icons.Default.SelectAll, "Select all") }
            IconButton(onClick = { vm.copySelected(false) }) { Icon(Icons.Default.ContentCopy, "Copy") }
            IconButton(onClick = { vm.copySelected(true) }) { Icon(Icons.Default.ContentCut, "Cut") }
            IconButton(onClick = { vm.dialog = Dlg.Delete(sel) }) { Icon(Icons.Default.Delete, "Delete") }
            Box {
                IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    if (single != null) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
                            onClick = { more = false; vm.dialog = Dlg.Rename(single) }
                        )
                        DropdownMenuItem(
                            text = { Text("Details") },
                            leadingIcon = { Icon(Icons.Default.Info, null) },
                            onClick = { more = false; vm.dialog = Dlg.Details(single) }
                        )
                        if (single.isFile && single.extension.equals("zip", true)) {
                            DropdownMenuItem(
                                text = { Text("Extract here") },
                                leadingIcon = { Icon(Icons.Default.Unarchive, null) },
                                onClick = { more = false; vm.unzip(single) }
                            )
                        }
                    }
                    DropdownMenuItem(
                        text = { Text("Compress (ZIP)") },
                        leadingIcon = { Icon(Icons.Default.Archive, null) },
                        onClick = { more = false; vm.zipSelected() }
                    )
                    DropdownMenuItem(
                        text = { Text("Share") },
                        leadingIcon = { Icon(Icons.Default.Share, null) },
                        onClick = { more = false; shareFiles(ctx, sel) }
                    )
                }
            }
        }
    )
}

@Composable
fun NewFab(vm: FmViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        FloatingActionButton(onClick = { open = true }) { Icon(Icons.Default.Add, "New") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("New folder") },
                leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                onClick = { open = false; vm.dialog = Dlg.NewFolder }
            )
            DropdownMenuItem(
                text = { Text("New file") },
                leadingIcon = { Icon(Icons.Default.InsertDriveFile, null) },
                onClick = { open = false; vm.dialog = Dlg.NewFile }
            )
        }
    }
}

@Composable
fun PasteBar(vm: FmViewModel) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${vm.clipboard.size} item(s) • ${if (vm.cutMode) "Move" else "Copy"}",
                Modifier.weight(1f)
            )
            TextButton(onClick = { vm.clipboard = emptyList() }) { Text("Cancel") }
            Button(onClick = { vm.paste() }) {
                Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Paste here")
            }
        }
    }
}

// ---------------------------------------------------------------- home

@Composable
fun HomeScreen(vm: FmViewModel) {
    val sf = remember { StatFs(vm.root.path) }
    val total = sf.totalBytes
    val free = sf.availableBytes
    val used = total - free

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(Modifier.fillMaxWidth().clickable { vm.open(vm.root) }) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PhoneAndroid, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Internal storage", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { if (total > 0) used.toFloat() / total else 0f },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "${fmtSize(used)} used of ${fmtSize(total)}  •  ${fmtSize(free)} free",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Text("Categories", style = MaterialTheme.typography.titleSmall)
        Category.values().toList().chunked(3).forEach { rowCats ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowCats.forEach { c ->
                    Card(Modifier.weight(1f).clickable { vm.openCategory(c) }) {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(c.icon(), null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(6.dp))
                            Text(c.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                    }
                }
                repeat(3 - rowCats.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        Text("Quick access", style = MaterialTheme.typography.titleSmall)
        listOf("Download", "DCIM", "Documents", "Pictures", "Music", "Movies")
            .map { File(vm.root, it) }
            .filter { it.isDirectory }
            .forEach { d ->
                ListItem(
                    headlineContent = { Text(d.name) },
                    leadingContent = { Icon(Icons.Default.Folder, null, tint = Color(0xFFFFB300)) },
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { vm.open(d) }
                )
            }
    }
}

// ---------------------------------------------------------------- browser

@Composable
fun Browser(vm: FmViewModel, ctx: Context) {
    val click: (File) -> Unit = { f ->
        when {
            vm.selected.isNotEmpty() -> vm.toggleSelect(f)
            f.isDirectory -> vm.open(f)
            else -> openFile(ctx, f)
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (vm.category == null && !vm.searching) Breadcrumbs(vm)
        val list = vm.shown()
        if (list.isEmpty() && !vm.busy) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (vm.searching) "No results" else "Empty",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (vm.grid) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(100.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp, 8.dp, 8.dp, 88.dp)
            ) {
                items(list, key = { it.absolutePath }) { f ->
                    FileCell(f, f in vm.selected, { click(f) }, { vm.toggleSelect(f) })
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                items(list, key = { it.absolutePath }) { f ->
                    FileRow(f, f in vm.selected, { click(f) }, { vm.toggleSelect(f) })
                }
            }
        }
    }
}

@Composable
fun Breadcrumbs(vm: FmViewModel) {
    val rootPath = vm.root.absolutePath
    val crumbs = generateSequence(vm.current) { if (it.absolutePath == rootPath) null else it.parentFile }
        .toList().reversed()
    val scroll = rememberScrollState()
    LaunchedEffect(vm.current) { scroll.scrollTo(scroll.maxValue) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        crumbs.forEachIndexed { i, f ->
            if (i > 0) Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { vm.open(f) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if (f.absolutePath == rootPath) "Internal" else f.name, maxLines = 1)
            }
        }
    }
}

@Composable
fun Thumb(f: File, size: Dp) {
    val k = f.kind()
    Box(Modifier.size(size).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        if (k == Kind.IMAGE || k == Kind.VIDEO) {
            AsyncImage(
                model = f,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            val tint = when (k) {
                Kind.FOLDER -> Color(0xFFFFB300)
                Kind.APK -> Color(0xFF3DDC84)
                else -> MaterialTheme.colorScheme.primary
            }
            Icon(k.icon(), null, Modifier.size(size * 0.7f), tint = tint)
        }
    }
}

private val dateFmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

@Composable
fun FileRow(f: File, sel: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val bg = if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val sub = if (f.isDirectory) {
        val n = f.list()?.size ?: 0
        "$n item(s)  •  ${dateFmt.format(Date(f.lastModified()))}"
    } else {
        "${fmtSize(f.length())}  •  ${dateFmt.format(Date(f.lastModified()))}"
    }
    Row(
        Modifier.fillMaxWidth().background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumb(f, 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                sub, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
            )
        }
        if (sel) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun FileCell(f: File, sel: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val bg = if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Column(
        Modifier.padding(4.dp).clip(RoundedCornerShape(12.dp)).background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(8.dp).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Thumb(f, 72.dp)
        Spacer(Modifier.height(4.dp))
        Text(
            f.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center
        )
    }
}

// ---------------------------------------------------------------- dialogs

@Composable
fun Dialogs(vm: FmViewModel) {
    when (val d = vm.dialog) {
        null -> {}
        Dlg.NewFolder -> NameDialog("New folder", "", { vm.dialog = null }) {
            vm.dialog = null; vm.createFolder(it)
        }
        Dlg.NewFile -> NameDialog("New file", "", { vm.dialog = null }) {
            vm.dialog = null; vm.createFile(it)
        }
        is Dlg.Rename -> NameDialog("Rename", d.f.name, { vm.dialog = null }) {
            vm.dialog = null; vm.rename(d.f, it)
        }
        is Dlg.Delete -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            title = { Text("Delete") },
            text = { Text("Delete ${d.fs.size} item(s)? This cannot be undone.") },
            confirmButton = { TextButton(onClick = { vm.dialog = null; vm.delete(d.fs) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { vm.dialog = null }) { Text("Cancel") } }
        )
        is Dlg.Details -> DetailsDialog(d.f) { vm.dialog = null }
    }
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onOk(text.trim()) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun DetailsDialog(f: File, onDismiss: () -> Unit) {
    val size by produceState(initialValue = -1L, f) {
        value = withContext(Dispatchers.IO) {
            if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Name: ${f.name}")
                Text("Path: ${f.parent}")
                Text("Type: ${if (f.isDirectory) "Folder" else f.extension.ifEmpty { "File" }}")
                Text("Size: ${if (size < 0) "calculating…" else fmtSize(size)}")
                if (f.isDirectory) Text("Items: ${f.list()?.size ?: 0}")
                Text("Modified: ${dateFmt.format(Date(f.lastModified()))}")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
