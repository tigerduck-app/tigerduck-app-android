package org.ntust.app.tigerduck.liveactivity

/**
 * The Live Update's detail lines: the room, the instructor and the time,
 * in that order, leaving out whichever is missing. Plain text, with no emoji
 * in front of each line.
 *
 * [withSubtitle] false leaves out the last, for a surface that already shows
 * the content text, which ends with the same subtitle.
 */
internal object LiveUpdateDetails {
    fun lines(snapshot: LiveActivitySnapshot, withSubtitle: Boolean = true): List<String> =
        listOfNotNull(
            snapshot.locationText,
            snapshot.instructor,
            snapshot.subtitle.takeIf { withSubtitle && it.isNotBlank() },
        )
}
