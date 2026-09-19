package com.example.cbzmerger

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = this
        setContent { MaterialTheme { App(ctx) } }
    }
}

data class LogLine(val text: String)

@Composable
fun App(ctx: Context) {
    val scope = rememberCoroutineScope()
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var pickedName by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val logs = remember { mutableStateListOf<LogLine>() }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            pickedUri = uri
            pickedName = uri.lastPathSegment ?: "unknown"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("CBZ 合并器", style = MaterialTheme.typography.headlineSmall)
        Text("选一个漫画文件夹（里面是 第N话.cbz），按话数排序合并成一个 CBZ。", style = MaterialTheme.typography.bodySmall)

        Button(onClick = { picker.launch(null) }, enabled = !running) {
            Text(if (pickedUri == null) "选择漫画文件夹" else "重新选择")
        }
        if (pickedName.isNotEmpty()) {
            Text("已选：$pickedName", style = MaterialTheme.typography.bodyMedium)
        }

        Button(
            onClick = {
                val uri = pickedUri ?: return@Button
                running = true
                logs.clear()
                scope.launch {
                    try {
                        mergeCbz(ctx, uri, pickedName) { line ->
                            logs.add(LogLine(line))
                        }
                    } catch (e: Exception) {
                        logs.add(LogLine("错误：${e.message}"))
                    } finally {
                        running = false
                    }
                }
            },
            enabled = pickedUri != null && !running
        ) {
            Text(if (running) "合并中…" else "开始合并")
        }

        Divider()

        logs.forEach { Text(it.text, style = MaterialTheme.typography.bodySmall) }
    }
}

suspend fun mergeCbz(
    ctx: Context,
    dirUri: Uri,
    dirName: String,
    log: (String) -> Unit
) = withContext(Dispatchers.IO) {
    val dir = DocumentFile.fromTreeUri(ctx, dirUri)
        ?: throw IllegalStateException("无法打开目录")

    val files = dir.listFiles().filter {
        it.isFile && it.name?.endsWith(".cbz", ignoreCase = true) == true
    }
    if (files.isEmpty()) {
        log("目录里没有 .cbz 文件")
        return@withContext
    }

    data class Item(val file: DocumentFile, val num: Double?, val name: String)

    val items = files.map { f ->
        val name = f.name ?: ""
        val base = name.substringBeforeLast(".")
        val m = Regex("第([0-9]+(?:\\.[0-9]+)?)话").find(base)
        val num = m?.groupValues?.get(1)?.toDoubleOrNull()
        Item(f, num, name)
    }.sortedWith(compareBy(
        { it.num == null },
        { it.num ?: 0.0 },
        { it.name }
    ))

    log("找到 ${items.size} 个 CBZ，排序如下：")
    items.forEach { log("  ${it.name}") }

    val mergedDir = dir.findFile("merged") ?: dir.createDirectory("merged")
        ?: throw IllegalStateException("无法创建 merged 目录")
    val outName = "${dirName}.cbz"
    mergedDir.findFile(outName)?.delete()
    val outFile = mergedDir.createFile("application/zip", outName)
        ?: throw IllegalStateException("无法创建输出文件")

    val outStream = BufferedOutputStream(ctx.contentResolver.openOutputStream(outFile.uri)!!)
    val zos = ZipOutputStream(outStream)

    try {
        var index = 0
        for (item in items) {
            index++
            val prefix = String.format("%03d_%s", index, item.name.substringBeforeLast("."))
            log("[$index/${items.size}] 处理 ${item.name}")

            val inputStream = ctx.contentResolver.openInputStream(item.file.uri) ?: continue
            val zis = ZipInputStream(BufferedInputStream(inputStream))
            var entry: ZipEntry? = zis.nextEntry
            var imgCount = 0
            while (entry != null) {
                if (!entry.isDirectory) {
                    val entryName = entry.name
                    val lower = entryName.lowercase()
                    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                        lower.endsWith(".png") || lower.endsWith(".webp") ||
                        lower.endsWith(".gif") || lower.endsWith(".bmp")
                    ) {
                        imgCount++
                        val newName = "$prefix/${entryName.substringAfterLast('/')}"
                        zos.putNextEntry(ZipEntry(newName))
                        val buf = ByteArray(8192)
                        var n = zis.read(buf)
                        while (n > 0) {
                            zos.write(buf, 0, n)
                            n = zis.read(buf)
                        }
                        zos.closeEntry()
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
            zis.close()
            log("     -> $imgCount 张图")
        }
        zos.finish()
        log("完成：merged/$outName")
    } finally {
        zos.close()
        outStream.close()
    }
}
