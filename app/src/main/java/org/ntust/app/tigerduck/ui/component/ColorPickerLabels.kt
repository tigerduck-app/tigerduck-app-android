package org.ntust.app.tigerduck.ui.component

import androidx.annotation.StringRes
import org.ntust.app.tigerduck.R

/**
 * What [ColorPickerSheet] calls itself, its Custom section and each preset swatch, as the
 * iPhone's `ColorPickerSheet.Labels`: a course's color and a club's have keys of their own.
 * The swatch's label takes the swatch's number, from 1.
 */
data class ColorPickerLabels(@param:StringRes val title: Int, @param:StringRes val custom: Int, @param:StringRes val swatch: Int) {
    companion object {
        val Course = ColorPickerLabels(R.string.color_picker_title, R.string.color_picker_custom, R.string.a11y_course_color_swatch)

        val Club = ColorPickerLabels(
            R.string.class_table_club_color_picker_title,
            R.string.class_table_club_color_custom_label,
            R.string.a11y_class_table_club_color_swatch,
        )
    }
}
