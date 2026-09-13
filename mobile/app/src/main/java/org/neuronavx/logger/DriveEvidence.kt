package org.neuronavx.logger

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Explicit, closed-session export. Never scans or includes unrelated app files. */
object DriveEvidence {
    fun create(directory: File, cache: File, sessionId: Long): File {
        require(sessionId > 0) { "Invalid session" }
        val files = listOf("nav_drive_$sessionId.csv", "nav_diagnostics_$sessionId.csv", "nav_summary_$sessionId.json")
            .map { File(directory, it) }
        require(files.all { it.isFile && it.length() > 0 && it.canonicalFile.parentFile == directory.canonicalFile }) {
            "This ride is missing a raw log, diagnostics or summary. No incomplete bundle was shared."
        }
        val bytes = files.sumOf { it.length() }
        require(bytes <= 200L * 1024 * 1024) { "Ride exceeds the 200 MB share limit. Export it by USB instead." }
        require(files.last().length() <= 128000 && SessionRecord.parse(files.last().readText())?.id == sessionId) {
            "The saved summary does not match this ride."
        }
        val exportDirectory = File(cache, "drive-evidence").apply { check(mkdirs() || isDirectory) }
        require(exportDirectory.usableSpace > bytes + 10L * 1024 * 1024) { "Not enough free space to prepare this ride." }
        val output = File.createTempFile("Waynova_ride_${sessionId}_", ".zip", exportDirectory)
        try {
            ZipOutputStream(output.outputStream().buffered()).use { zip ->
                files.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            return output
        } catch (e: Exception) {
            output.delete() // Only this failed temporary export; original recordings stay intact.
            throw e
        }
    }
}
