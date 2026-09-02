package org.neuronavx.logger.nav

import android.content.Context
import android.net.Uri
import android.os.StatFs
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Owns the single-file Greater Noida basemap installed in private app storage. */
object OfflineMapStore {
    private const val ASSET_PATH = "offline_map/greater_noida.pmtiles"
    private const val DIRECTORY = "offline_maps"
    private const val FILE_NAME = "greater_noida.pmtiles"
    private const val COPY_BUFFER_BYTES = 256 * 1024
    private const val FREE_SPACE_MARGIN_BYTES = 32L * 1024L * 1024L

    data class InstalledMap(val file: File, val bytes: Long)

    fun installed(context: Context): InstalledMap? {
        val file = targetFile(context)
        return if (isPmtilesV3(file)) InstalledMap(file, file.length()) else null
    }

    /** Seed the app from the bundled, legally redistributable Protomaps/OSM city cutout. */
    fun ensureBundled(
        context: Context,
        onProgress: (percent: Int) -> Unit = {},
    ): InstalledMap {
        installed(context)?.let { return it }
        val expected = context.assets.openFd(ASSET_PATH).length
        context.assets.open(ASSET_PATH).use { input ->
            return install(context, input, expected, onProgress)
        }
    }

    /** Replace the current region with a user-selected Protomaps v3 PMTiles archive. */
    fun import(
        context: Context,
        uri: Uri,
        onProgress: (percent: Int) -> Unit = {},
    ): InstalledMap {
        val resolver = context.contentResolver
        val expected = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        val input = resolver.openInputStream(uri)
            ?: error("The selected file could not be opened")
        input.use { return install(context, it, expected, onProgress) }
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.0f KB".format(bytes / 1024.0)
    }

    private fun install(
        context: Context,
        input: InputStream,
        expectedBytes: Long,
        onProgress: (percent: Int) -> Unit,
    ): InstalledMap {
        val target = targetFile(context)
        target.parentFile?.mkdirs()
        if (expectedBytes > 0L) {
            val available = StatFs(target.parentFile!!.absolutePath).availableBytes
            require(available >= expectedBytes + FREE_SPACE_MARGIN_BYTES) {
                "Not enough storage: ${formatBytes(expectedBytes + FREE_SPACE_MARGIN_BYTES)} required"
            }
        }

        val temporary = File(target.parentFile, ".$FILE_NAME.part")
        Files.deleteIfExists(temporary.toPath())
        try {
            var copied = 0L
            var lastPercent = -1
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    copied += count
                    if (expectedBytes > 0L) {
                        val percent = ((copied * 100L) / expectedBytes).toInt().coerceIn(0, 100)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                }
                output.fd.sync()
            }
            require(isPmtilesV3(temporary)) {
                "This is not a valid PMTiles v3 archive"
            }
            try {
                Files.move(
                    temporary.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING
                )
            }
            onProgress(100)
            return InstalledMap(target, target.length())
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    private fun targetFile(context: Context): File =
        File(File(context.filesDir, DIRECTORY), FILE_NAME)

    private fun isPmtilesV3(file: File): Boolean {
        if (!file.isFile || file.length() < 127L) return false
        return runCatching {
            file.inputStream().buffered().use { input ->
                val header = ByteArray(8)
                if (input.read(header) != header.size) return@use false
                header.copyOfRange(0, 7).decodeToString() == "PMTiles" && header[7].toInt() == 3
            }
        }.getOrDefault(false)
    }
}
