package org.ntust.app.tigerduck.ui.screen.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.ntust.app.tigerduck.files.ArchiveLimits
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The archive viewer's cache folder: what it keeps when files taken out pass its limit. */
class ArchiveCacheTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `past its limit the folder drops the files taken out longest ago, never the newest`() {
        val folder = temp.newFolder("session")
        val files = (0 until 4).map { index ->
            folder.resolve("item-$index.bin").apply {
                writeBytes(ByteArray(100))
                setLastModified(1_000_000L + index * 1_000)
            }
        }

        ArchiveCache.trim(folder, newest = files[3], maxBytes = 250)

        assertEquals(listOf("item-2.bin", "item-3.bin"), folder.list()!!.sorted())
    }

    @Test
    fun `a sweep leaves every folder an open viewer holds, however old`() {
        val root = temp.newFolder("archive_entries")
        val day = 24L * 60 * 60 * 1000
        fun folder(name: String) = root.resolve(name).apply { mkdirs(); setLastModified(0) }
        val parent = folder("parent")
        val child = folder("child")
        val left = folder("left")
        // The parent viewer is open, and the child's, whose archive was taken out into the parent's folder.
        ArchiveCache.open(parent)
        ArchiveCache.open(child)
        ArchiveCache.open(parent)
        try {
            ArchiveCache.sweep(root, keep = child, now = 10 * day)

            assertTrue(parent.exists())
            assertTrue(child.exists())
            assertFalse(left.exists())
        } finally {
            ArchiveCache.close(parent)
            ArchiveCache.close(parent)
            ArchiveCache.close(child)
        }
    }

    @Test
    fun `a folder is let go only when no open viewer holds it any more`() {
        val folder = temp.newFolder("held")
        ArchiveCache.open(folder)
        ArchiveCache.open(folder)

        assertFalse(ArchiveCache.close(folder))
        assertTrue(ArchiveCache.close(folder))
    }

    @Test
    fun `an archive taken out of another is found in that viewer's folder, and nowhere else`() {
        val cache = temp.newFolder("cache")

        assertEquals(cache.resolve("archive_entries/abc"), ArchiveCache.folderHolding("/archive_entries/abc/item-3.zip", cache))
        assertNull(ArchiveCache.folderHolding("/moodle_downloads/abc/notes.zip", cache))
        assertNull(ArchiveCache.folderHolding("/archive_entries/../moodle_downloads/x.zip", cache))
        assertNull(ArchiveCache.folderHolding("/archive_entries/item-3.zip", cache))
        assertNull(ArchiveCache.folderHolding(null, cache))
    }

    @Test
    fun `a stream's copy and the largest file taken out fit the folder's limit together`() {
        assertTrue(ArchiveCache.MAX_STREAM_COPY_BYTES + ArchiveLimits().maxEntryBytes <= ArchiveCache.MAX_FOLDER_BYTES)
    }

    @Test
    fun `the newest is kept even when it alone passes the limit`() {
        val folder = temp.newFolder("session")
        val big = folder.resolve("item-0.bin").apply { writeBytes(ByteArray(500)) }

        ArchiveCache.trim(folder, newest = big, maxBytes = 250)

        assertEquals(listOf("item-0.bin"), folder.list()!!.toList())
    }
}
