package org.neuronavx.logger

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class DriveEvidenceTest {
    private fun fixture(test: (File, File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val temporary = Files.createTempDirectory(context.cacheDir.toPath(), "evidence-test-").toFile()
        try {
            val source = File(temporary, "source").apply { mkdirs() }
            val cache = File(temporary, "cache").apply { mkdirs() }
            File(source,"nav_drive_123.csv").writeText("synthetic raw")
            File(source,"nav_diagnostics_123.csv").writeText("synthetic diagnostics")
            File(source,"nav_summary_123.json").writeText("""{"session_id":123}""")
            test(source,cache)
        } finally { temporary.deleteRecursively() }
    }
    @Test fun exportsOnlyTheSelectedRideAndPreservesOriginals() = fixture { source, cache ->
        File(source,"unrelated_secret.txt").writeText("never export")
        File(source,"nav_drive_456.csv").writeText("another session")
        val zip = DriveEvidence.create(source,cache,123)
        ZipFile(zip).use { archive ->
            assertEquals(setOf("nav_drive_123.csv","nav_diagnostics_123.csv","nav_summary_123.json"),
                archive.entries().asSequence().map { it.name }.toSet())
            assertEquals("synthetic raw",archive.getInputStream(archive.getEntry("nav_drive_123.csv")).bufferedReader().readText())
        }
        assertEquals("synthetic raw",File(source,"nav_drive_123.csv").readText())
    }
    @Test fun missingOrMismatchedEvidenceFailsWithoutABundle() = fixture { source, cache ->
        File(source,"nav_summary_123.json").writeText("""{"session_id":456}""")
        assertTrue(runCatching { DriveEvidence.create(source,cache,123) }.isFailure)
        File(source,"nav_summary_123.json").delete()
        assertTrue(runCatching { DriveEvidence.create(source,cache,123) }.isFailure)
        assertFalse(File(cache,"drive-evidence").exists())
    }
}
