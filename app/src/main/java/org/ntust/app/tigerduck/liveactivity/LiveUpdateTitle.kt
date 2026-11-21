package org.ntust.app.tigerduck.liveactivity

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TtsSpan
import android.text.style.UnderlineSpan
import org.ntust.app.tigerduck.R

/**
 * The Live Update's title: [snapshot]'s, and for a class with an assessment the nearest a
 * notification comes to the app's dashed underline. A notification draws its text in the
 * system's colors and takes no dashes, so the title is underlined in its own color; TalkBack
 * reads `course_change_assessment` in [localized]'s language right after it. As the iPhone Live
 * Activity's `CourseName`.
 */
internal fun liveUpdateTitle(snapshot: LiveActivitySnapshot, localized: Context): CharSequence {
    if (!snapshot.assessment) return snapshot.title
    val spoken = "${snapshot.title}, ${localized.getString(R.string.course_change_assessment)}"
    return SpannableString(snapshot.title).apply {
        setSpan(UnderlineSpan(), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(TtsSpan.TextBuilder(spoken).build(), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
