package org.ntust.app.tigerduck.network.moodle

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.network.MoodleWebserviceError
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseSummary

/** Moodle's answers as the course screens read them. */
class MoodleJsonTest {

    private fun json(text: String) = JsonParser.parseString(text)

    @Test
    fun `forums keep the counts and a plain-text intro`() {
        val forums = MoodleJson.forums(
            json(
                """
                [{"id":11,"course":5,"type":"news","name":"Announcements","cmid":101,
                  "numdiscussions":3,"unreadpostscount":1,"intro":"<p>From the <b>teacher</b></p>"},
                 {"id":12,"course":5,"type":"general","name":"Q&amp;A","cmid":102,"intro":""},
                 {"name":"no id"}]
                """,
            ),
        )
        assertEquals(2, forums.size)
        assertTrue(forums[0].isNews)
        assertEquals(3, forums[0].discussionCount)
        assertEquals(1, forums[0].unreadPostCount)
        assertEquals("From the teacher", forums[0].intro)
        assertEquals("Q&A", forums[1].name)
        assertNull("an empty intro reads as none", forums[1].intro)
    }

    @Test
    fun `a discussion keeps its message, a preview, its attachments and author`() {
        val discussions = MoodleJson.discussions(
            json(
                """
                {"discussions":[{"id":900,"discussion":77,"name":"Midterm","message":"<p>Room <b>TR-313</b></p>",
                  "created":1760000000,"timemodified":1760003600,"pinned":true,"numunread":"2","numreplies":0,
                  "userid":501,"userfullname":"T0001 @ Wang",
                  "attachments":[{"filename":"a.pdf","filesize":10,"fileurl":"https://moodle2.ntust.edu.tw/webservice/pluginfile.php/1/a.pdf","mimetype":"application/pdf"},
                                 {"filesize":1}]}],
                 "warnings":[]}
                """,
            ),
        )
        val d = discussions.single()
        assertEquals(77, d.id)
        assertEquals(900, d.postId)
        assertEquals("Room TR-313", d.preview)
        assertTrue(d.isPinned)
        assertEquals("a number sent as text still counts", 2, d.unreadCount)
        assertEquals(501, d.authorId)
        assertEquals("a.pdf", d.attachments.single().name)
    }

    @Test
    fun `grade items leave out what is not graded and keep feedback as Markdown`() {
        val items = MoodleJson.gradeItems(
            json(
                """
                {"usergrades":[{"userid":7,"gradeitems":[
                  {"id":1,"itemname":"HW1","itemtype":"mod","itemmodule":"assign","cmid":31,"graderaw":95.0,
                   "gradeformatted":"95.00","grademax":100,"feedback":"<p>Nice <em>work</em></p>"},
                  {"id":2,"itemname":"HW2","itemtype":"mod","itemmodule":"assign","graderaw":null,"gradeformatted":"-","grademax":100},
                  {"id":3,"itemname":"","itemtype":"course","graderaw":88.5,"gradeformatted":"88.50","grademax":100}
                ]},{"userid":8,"gradeitems":[]}]}
                """,
            ),
            userId = 7,
        )
        assertEquals(listOf(1, 2, 3), items.map { it.id })
        assertEquals("95.00", items[0].grade)
        assertEquals("Nice *work*", items[0].feedback)
        assertNull("an ungraded item has no grade", items[1].grade)
        assertTrue(items[2].isCourseTotal)
    }

    @Test
    fun `members keep only their own pictures and drop unreadable fields`() {
        val members = MoodleJson.members(
            json(
                """
                [{"id":1,"fullname":"B11300001 @ Amy","idnumber":" B11300001 ","department":"",
                  "email":"amy@mail.ntust.edu.tw","groups":[{"name":"Team <b>A</b>"}],
                  "roles":[{"roleid":5,"name":"","shortname":"student","sortorder":0}],
                  "profileimageurl":"https://moodle2.ntust.edu.tw/pluginfile.php/9/user/icon/moove/f1?rev=3",
                  "description":"<p>Hi</p>","interests":"tea, ,cats",
                  "customfields":[{"type":"text","name":"Nickname","value":"A"},{"type":"checkbox","name":"Agree","value":"1"}]},
                 {"id":2,"fullname":"Teacher","profileimageurl":"https://moodle2.ntust.edu.tw/theme/image.php/moove/core/1/u/f1"}]
                """,
            ),
        )
        val amy = members[0]
        assertEquals("B11300001", amy.idNumber)
        assertEquals(listOf("Team A"), amy.groups)
        assertEquals("student", amy.roles.single().shortName)
        assertEquals(listOf("tea", "cats"), amy.interests)
        assertEquals("Nickname", amy.fields.single().name)
        assertTrue(amy.pictureUrl!!.contains("/user/icon/"))
        assertNull("Moodle's default picture is never downloaded", members[1].pictureUrl)
    }

    @Test
    fun `the summary of the course asked about, or none`() {
        val answer = json(
            """{"courses":[{"id":5,"fullname":"Data Structures","shortname":"DS","idnumber":"1151CS3005301",
                 "summary":"<p>Weekly <a href=\"/mod/page/view.php?id=1\">notes</a></p>"}]}""",
        )
        val summary = MoodleJson.summary(answer, courseId = 5)
        assertEquals("Weekly notes", summary.text)
        assertEquals(listOf("Data Structures", "DS", "1151CS3005301"), summary.names)
        assertEquals(MoodleCourseSummary.EMPTY, MoodleJson.summary(answer, courseId = 6))
    }

    @Test
    fun `site info without a user id is malformed`() {
        assertThrows(MoodleWebserviceError.MalformedResponse::class.java) {
            MoodleJson.siteInfo(json("""{"functions":[]}"""))
        }
    }

    @Test
    fun `only pictures under the site's user icons are a member's own`() {
        assertTrue(MoodleJson.isOwnPicture("https://moodle2.ntust.edu.tw/webservice/pluginfile.php/9/user/icon/f1"))
        assertFalse(MoodleJson.isOwnPicture("https://evil.example/pluginfile.php/9/user/icon/f1"))
        assertFalse(MoodleJson.isOwnPicture("http://moodle2.ntust.edu.tw/pluginfile.php/9/user/icon/f1"))
    }

    @Test
    fun `a thread's posts keep whom they answer, their state and their attachments`() {
        val posts = MoodleJson.posts(
            json(
                """
                {"posts":[
                  {"id":1,"subject":"Re: <b>HW3</b>","message":"<p>First</p>","timecreated":1700000000,
                   "author":{"id":9,"fullname":"B1 @ Wang"},"hasparent":false,"parentid":0,"unread":true,
                   "attachments":[{"filename":"a.pdf","filepath":"/","filesize":3,
                     "url":"https://moodle2.ntust.edu.tw/pluginfile.php/1/mod_forum/attachment/1/a.pdf",
                     "mimetype":"application/pdf","timemodified":1700000001}]},
                  {"id":2,"subject":"Re: HW3","message":"<p>Removed</p>","timecreated":0,
                   "author":{},"hasparent":true,"parentid":1,"isdeleted":true},
                  {"id":3,"hasparent":true,"parentid":0},
                  {"subject":"no id"}
                ]}
                """,
            ),
        )
        assertEquals(listOf(1, 2, 3), posts.map { it.id })
        assertNull(posts[0].parentId)
        assertEquals("Re: HW3", posts[0].subject)
        assertEquals(1700000000L, posts[0].created)
        assertEquals("B1 @ Wang", posts[0].authorName)
        assertEquals(9, posts[0].authorId)
        assertTrue(posts[0].isUnread)
        assertEquals("a.pdf", posts[0].attachments.single().name)
        assertEquals(1, posts[1].parentId)
        assertTrue(posts[1].isDeleted)
        assertNull("no time reads as none", posts[1].created)
        assertNull(posts[1].authorName)
        assertNull("a parent id of 0 is none", posts[2].parentId)
    }

    @Test
    fun `materials keep the sections in order with their files, folders and links`() {
        val materials = MoodleJson.materials(
            json(
                """
                [{"id":10,"name":"General","modules":[
                   {"id":101,"instance":7,"modname":"resource","name":"Syllabus","contents":[
                     {"type":"file","filename":"syllabus.pdf","filepath":"/","filesize":10,
                      "fileurl":"https://moodle2.ntust.edu.tw/webservice/pluginfile.php/1/mod_resource/content/1/syllabus.pdf",
                      "mimetype":"application/pdf","timemodified":1700000000},
                     {"type":"file","filename":"extra.css","filepath":"/","filesize":1,
                      "fileurl":"https://moodle2.ntust.edu.tw/webservice/pluginfile.php/1/mod_resource/content/1/extra.css"}]},
                   {"id":102,"instance":8,"modname":"forum","name":"Q&amp;A"},
                   {"id":103,"instance":9,"modname":"url","name":"Slides site","contents":[
                     {"type":"url","filename":"Slides","fileurl":"https://slides.example.com/ds"}]},
                   {"id":104,"instance":10,"modname":"url","name":"Script","contents":[
                     {"type":"url","filename":"x","fileurl":"javascript:alert(1)"}]}]},
                 {"id":11,"name":"<b>Week 1</b>","modules":[
                   {"id":105,"instance":11,"modname":"folder","name":"Labs","contents":[
                     {"type":"file","filename":"lab1.zip","filepath":"/a/","filesize":5,
                      "fileurl":"https://moodle2.ntust.edu.tw/webservice/pluginfile.php/2/mod_folder/content/0/a/lab1.zip"},
                     {"type":"file","filename":"lab2.zip","filepath":"/b/","filesize":6,
                      "fileurl":"https://moodle2.ntust.edu.tw/webservice/pluginfile.php/2/mod_folder/content/0/b/lab2.zip"}]},
                   {"id":106,"instance":12,"modname":"resource","name":"Hidden","uservisible":false,"contents":[
                     {"type":"file","filename":"h.pdf","fileurl":"https://moodle2.ntust.edu.tw/webservice/pluginfile.php/3/h.pdf"}]}]},
                 {"id":12,"name":"Closed","uservisible":false,"modules":[
                   {"id":107,"instance":13,"modname":"resource","name":"R","contents":[
                     {"type":"file","filename":"r.pdf","fileurl":"https://moodle2.ntust.edu.tw/webservice/pluginfile.php/4/r.pdf"}]}]},
                 {"id":13,"name":"Empty","modules":[{"id":108,"instance":14,"modname":"quiz","name":"Q"}]}]
                """,
            ),
        )
        assertEquals(listOf(10, 11), materials.sections.map { it.id })
        assertEquals("Week 1", materials.sections[1].name)
        val general = materials.sections[0].materials
        assertEquals(listOf(101, 103), general.map { it.id })
        assertEquals("a resource shows its first file", listOf("syllabus.pdf"), general[0].files.map { it.name })
        assertEquals(7, general[0].instance)
        assertEquals("https://slides.example.com/ds", general[1].link)
        assertTrue(general[1].files.isEmpty())
        val labs = materials.sections[1].materials.single()
        assertEquals("folder", labs.module)
        assertEquals(listOf("/a/", "/b/"), labs.files.map { it.path })
        assertEquals("files and links, a folder by its files", 4, org.ntust.app.tigerduck.data.moodle.MoodleMaterials.count(materials))
    }
}
