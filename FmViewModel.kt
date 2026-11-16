package com.example.filemanager

import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class Kind { FOLDER, IMAGE, VIDEO, AUDIO, DOC, APK, ARCHIVE, OTHER }

enum class SortBy(val label: String) { NAME("Name"), SIZE("Size"), DATE("Date"), TYPE("Type") }

enum class Category(val label: String) {
    RECENT("Recent"), IMAGES("Images"), VIDEOS("Videos"), AUDIO("Audio"),
    DOCS("Documents"), APKS("APKs"), ARCHIVES("Archives");

    fun matches(f: File): Boolean = when (this) {
        RECENT -> true
        IMAGES -> f.kind() == Kind.IMAGE
        VIDEOS -> f.kind() == Kind.VIDEO
        AUDIO -> f.kind() == Kind.AUDIO
        DOCS -> f.kind() == Kind.DOC
        APKS -> f.kind() == Kind.APK
        ARCHIVES -> f.kind() == Kind.ARCHIVE
    }
}

private val IMG = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
private val VID = setOf("mp4", "mkv", "webm", "3gp", "avi", "mov", "m4v")
private val AUD = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus")
private val DOC = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "odt", "rtf", "csv", "epub")
private val ARC = setOf("zip", "rar", "7z", "tar", "gz")

fun File.kind(): Kind {
    if (isDirectory) return Kind.FOLDER
    val e = extension.lowercase()
    return when {
        e in IMG -> Kind.IMAGE
        e in VID -> Kind.VIDEO
        e in AUD -> Kind.AUDIO
        e in DOC -> Kind.DOC
        e == "apk" -> Kind.APK
        e in ARC -> Kind.ARCHIVE
        else -> Kind.OTHER
    }
}

fun fmtSize(b: Long): String {
    if (b < 1024) return "$b B"
    val u = arrayOf("KB", "MB", "GB", "TB")
    var v = b / 1024.0
    var i = 0
    while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
    return "%.1f %s".format(v, u[i])
}

sealed interface Dlg {
    object NewFolder : Dlg
    object NewFile : Dlg
    data class Rename(val f: File) : Dlg
    data class Delete(val fs: List<File>) : Dlg
    data class Details(val f: File) : Dlg
}

object FileOps {
    fun uniqueTarget(dir: File, name: String, isDir: Boolean): File {
        var t = File(dir, name)
        if (!t.exists()) return t
        val dot = name.lastIndexOf('.')
        val base = if (isDir || dot <= 0) name else name.substring(0, dot)
        val ext = if (isDir || dot <= 0) "" else name.substring(dot)
        var i = 1
        while (true) {
            t = File(dir, "$base ($i)$ext")
            if (!t.exists()) return t
            i++
        }
    }

    private fun insideSelf(src: File, dest: File): Boolean =
        src.isDirectory && (dest.canonicalPath + "/").startsWith(src.canonicalPath + "/")

    fun copy(src: File, destDir: File): Boolean {
        if (insideSelf(src, destDir)) return false
        return src.copyRecursively(uniqueTarget(destDir, src.name, src.isDirectory), overwrite = false)
    }

    fun move(src: File, destDir: File): Boolean {
        if (insideSelf(src, destDir)) return false
        if (src.parentFile?.canonicalPath == destDir.canonicalPath) return false
        val t = uniqueTarget(destDir, src.name, src.isDirectory)
        if (src.renameTo(t)) return true
        return src.copyRecursively(t, overwrite = false) && src.deleteRecursively()
    }

    fun zip(files: List<File>, out: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zos ->
            files.forEach { f ->
                val base = f.parentFile ?: return@forEach
                val seq: Sequence<File> = if (f.isDirectory) f.walkTopDown() else sequenceOf(f)
                seq.forEach { x ->
                    if (x.canonicalPath == out.canonicalPath) return@forEach
                    val rel = x.relativeTo(base).path
                    if (x.isDirectory) {
                        zos.putNextEntry(ZipEntry("$rel/")); zos.closeEntry()
                    } else {
                        zos.putNextEntry(ZipEntry(rel))
                        x.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
        }
    }

    fun unzip(zip: File, destDir: File) {
        destDir.mkdirs()
        val canon = destDir.canonicalPath
        ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val out = File(destDir, e.name)
                if (!out.canonicalPath.startsWith(canon + File.separator)) throw SecurityException("Bad zip entry")
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zis.copyTo(it) }
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
    }
}

private fun sortFiles(list: List<File>, by: SortBy, asc: Boolean): List<File> {
    val cmp: Comparator<File> = when (by) {
        SortBy.NAME -> compareBy<File>(String.CASE_INSENSITIVE_ORDER) { it.name }
        SortBy.SIZE -> compareBy<File> { it.length() }
        SortBy.DATE -> compareBy<File> { it.lastModified() }
        SortBy.TYPE -> compareBy<File>(String.CASE_INSENSITIVE_ORDER) { it.extension }
    }
    val c = if (asc) cmp else cmp.reversed()
    return list.sortedWith(compareByDescending<File> { it.isDirectory }.then(c))
}

class FmViewModel : ViewModel() {
    val root: File = Environment.getExternalStorageDirectory()

    var hasAccess by mutableStateOf(false)
    var inHome by mutableStateOf(true)
    var category by mutableStateOf<Category?>(null)
    var current by mutableStateOf(root)
    var files by mutableStateOf<List<File>>(emptyList())
    var selected by mutableStateOf<Set<File>>(emptySet())
    var showHidden by mutableStateOf(false)
    var grid by mutableStateOf(false)
    var sortBy by mutableStateOf(SortBy.NAME)
    var asc by mutableStateOf(true)
    var busy by mutableStateOf(false)
    var dialog by mutableStateOf<Dlg?>(null)

    var searching by mutableStateOf(false)
    var query by mutableStateOf("")
    var searchResults by mutableStateOf<List<File>>(emptyList())

    var clipboard by mutableStateOf<List<File>>(emptyList())
    var cutMode by mutableStateOf(false)

    val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    fun toast(s: String) { messages.tryEmit(s) }

    private var scanJob: Job? = null
    private var searchJob: Job? = null

    fun shown(): List<File> = if (searching && query.isNotBlank()) searchResults else files

    fun title(): String {
        val c = category
        return when {
            inHome -> "File Manager"
            c != null -> c.label
            current.absolutePath == root.absolutePath -> "Internal storage"
            else -> current.name
        }
    }

    // ---------- navigation ----------
    fun open(dir: File) {
        current = dir
        inHome = false
        category = null
        selected = emptySet()
        exitSearch()
        reload()
    }

    fun goHome() {
        inHome = true
        category = null
        selected = emptySet()
        exitSearch()
    }

    fun openCategory(c: Category) {
        inHome = false
        category = c
        selected = emptySet()
        exitSearch()
        files = emptyList()
        reload()
    }

    fun canGoBack() = selected.isNotEmpty() || searching || !inHome

    fun back() {
        when {
            selected.isNotEmpty() -> selected = emptySet()
            searching -> exitSearch()
            inHome -> {}
            category != null -> goHome()
            current.absolutePath == root.absolutePath -> goHome()
            else -> open(current.parentFile ?: root)
        }
    }

    // ---------- loading ----------
    fun reload() {
        if (!hasAccess) return
        scanJob?.cancel()
        val c = category
        val dir = current
        val hidden = showHidden
        val by = sortBy
        val a = asc
        scanJob = viewModelScope.launch {
            busy = true
            try {
                files = withContext(Dispatchers.IO) {
                    if (c == null) {
                        val l = (dir.listFiles()?.toList() ?: emptyList())
                            .filter { hidden || !it.name.startsWith(".") }
                        sortFiles(l, by, a)
                    } else {
                        val all = root.walkTopDown()
                            .onEnter { d -> !d.name.startsWith(".") && !(d.parentFile == root && d.name == "Android") }
                            .filter { it.isFile && (hidden || !it.name.startsWith(".")) }
                        if (c == Category.RECENT) {
                            all.toList().sortedByDescending { it.lastModified() }.take(100)
                        } else {
                            sortFiles(all.filter { c.matches(it) }.toList(), by, a)
                        }
                    }
                }
            } finally {
                busy = false
            }
        }
    }

    // ---------- selection ----------
    fun toggleSelect(f: File) {
        selected = if (f in selected) selected - f else selected + f
    }

    fun selectAll() { selected = shown().toSet() }

    // ---------- search ----------
    fun startSearch() { searching = true; query = ""; searchResults = emptyList() }

    fun exitSearch() {
        searching = false
        query = ""
        searchJob?.cancel()
        searchResults = emptyList()
    }

    fun onQuery(q: String) {
        query = q
        searchJob?.cancel()
        if (q.isBlank()) { searchResults = emptyList(); return }
        val dir = current
        val hidden = showHidden
        searchJob = viewModelScope.launch {
            delay(300)
            busy = true
            try {
                searchResults = withContext(Dispatchers.IO) {
                    dir.walkTopDown()
                        .onEnter { hidden || !it.name.startsWith(".") }
                        .filter { it != dir && it.name.contains(q, ignoreCase = true) }
                        .take(300)
                        .toList()
                }
            } finally {
                busy = false
            }
        }
    }

    // ---------- operations ----------
    private fun runBg(block: () -> String) {
        viewModelScope.launch {
            busy = true
            val msg = try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                "Failed: ${e.message}"
            }
            busy = false
            toast(msg)
            reload()
            if (searching) onQuery(query)
        }
    }

    fun copySelected(cut: Boolean) {
        clipboard = selected.toList()
        cutMode = cut
        toast("${clipboard.size} item(s) ready — open a folder and tap Paste")
        selected = emptySet()
    }

    fun paste() {
        val items = clipboard
        val dest = current
        val cut = cutMode
        clipboard = emptyList()
        runBg {
            var ok = 0
            items.forEach { s ->
                val done = if (cut) FileOps.move(s, dest) else FileOps.copy(s, dest)
                if (done) ok++
            }
            (if (cut) "Moved " else "Copied ") + "$ok of ${items.size}"
        }
    }

    fun delete(fs: List<File>) {
        selected = emptySet()
        runBg { "Deleted ${fs.count { it.deleteRecursively() }} item(s)" }
    }

    fun rename(f: File, name: String) {
        selected = emptySet()
        runBg {
            val t = File(f.parentFile, name)
            when {
                name.contains('/') -> "Invalid name"
                t.exists() -> "A file with that name already exists"
                f.renameTo(t) -> "Renamed"
                else -> "Rename failed"
            }
        }
    }

    fun createFolder(name: String) {
        val dir = current
        runBg { if (File(dir, name).mkdirs()) "Folder created" else "Could not create folder" }
    }

    fun createFile(name: String) {
        val dir = current
        runBg {
            val f = File(dir, name)
            if (!f.exists() && f.createNewFile()) "File created" else "Could not create file"
        }
    }

    fun zipSelected() {
        val items = selected.toList()
        val dir = current
        selected = emptySet()
        runBg {
            val name = if (items.size == 1) items[0].nameWithoutExtension + ".zip" else "Archive.zip"
            val out = FileOps.uniqueTarget(dir, name, false)
            FileOps.zip(items, out)
            "Created ${out.name}"
        }
    }

    fun unzip(z: File) {
        selected = emptySet()
        runBg {
            val dest = FileOps.uniqueTarget(z.parentFile ?: root, z.nameWithoutExtension, true)
            FileOps.unzip(z, dest)
            "Extracted to ${dest.name}"
        }
    }
}
