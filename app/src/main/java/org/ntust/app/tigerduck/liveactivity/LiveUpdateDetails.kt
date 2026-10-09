package org.ntust.app.tigerduck.liveactivity

/**
 * The Live Update's detail lines: the room, the instructor and the time,
 * in that order, leaving out whichever is missing. Plain text, with no emoji
 * in front of each line.
 */
internal object LiveUpdateDetails {
    fun lines(snapshot: LiveActivitySnapshot): List<String> = listOfNotNull(
        snapshot.locationText,
        snapshot.instructor,
        snapshot.subtitle.takeIf { it.isNotBlank() },
    )
}
