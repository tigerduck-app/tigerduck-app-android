package org.ntust.app.tigerduck.coursecatalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.ntust.app.tigerduck.clubs.ETaggedBody
import java.io.File

/** Each term's catalog in each language is kept whole with its ETag, and a copy that cannot be read is none. */
class CourseCatalogFilesTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a stored catalog comes back with its ETag, each language and term on its own`() {
        val files = CourseCatalogFiles(folder.root)
        val zh = ETaggedBody("""{"courses":[]}""", "W/\"zh\"")

        files.save(zh, "1151", "zh")
        files.save(ETaggedBody("{}\n{}", null), "1151", "en")

        assertEquals(zh, files.load("1151", "zh"))
        assertEquals(ETaggedBody("{}\n{}", null), files.load("1151", "en"))
        assertNull(files.load("1152", "zh"))
        assertTrue(folder.root.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `a newer copy replaces the stored one whole`() {
        val files = CourseCatalogFiles(folder.root)
        files.save(ETaggedBody("old", "W/\"1\""), "1151", "zh")

        files.save(ETaggedBody("new", "W/\"2\""), "1151", "zh")

        assertEquals(ETaggedBody("new", "W/\"2\""), files.load("1151", "zh"))
    }

    @Test
    fun `a term is named by its letters and digits alone`() {
        val files = CourseCatalogFiles(folder.root)

        files.save(ETaggedBody("{}", "W/\"1\""), "../1151", "zh")

        assertEquals(listOf("course_catalog_1151_zh.txt"), folder.root.list().orEmpty().toList())
        assertEquals(ETaggedBody("{}", "W/\"1\""), files.load("1151", "zh"))
    }

    @Test
    fun `a file without its ETag line is no copy`() {
        File(folder.root, "course_catalog_1151_zh.txt").writeText("{}")

        assertNull(CourseCatalogFiles(folder.root).load("1151", "zh"))
    }
}
