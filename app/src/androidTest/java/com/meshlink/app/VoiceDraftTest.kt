package com.meshlink.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meshlink.app.audio.VoiceDraft
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Requires a microphone and explicit microphone permission on the test device. */
@RunWith(AndroidJUnit4::class)
class VoiceDraftTest {
    @Test fun recordDecodePreviewAndCleanup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE))
        val draft = VoiceDraft(context)
        try {
            draft.start()
            Thread.sleep(5000)
            draft.finish()
            val completed = draft.completedFile()
            draft.finish() // Idempotent: no second MediaRecorder.stop or deletion.
            assertTrue(completed.isFile)
            assertTrue(draft.ready)
            assertTrue(draft.durationMs in 500..30000)
            draft.toggle()
            Thread.sleep(200)
            draft.pause()
            repeat(3) { draft.toggle(); Thread.sleep(100); draft.pause() }
            assertEquals(completed,draft.completedFile()) // Same finalized file for Send after playback.
            assertTrue(completed.length() > 0)
            assertFalse(draft.playing)
        } finally { draft.close() }
        assertTrue(File(context.cacheDir, "voice-drafts").listFiles().orEmpty().isEmpty())
    }
    @Test fun cancellationReleasesRecorderAndDeletesDraft() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        val draft = VoiceDraft(context)
        try { draft.start() } finally { draft.close(); draft.close() }
        assertTrue(File(context.cacheDir, "voice-drafts").listFiles().orEmpty().isEmpty())
    }
}
