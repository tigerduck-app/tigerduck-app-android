// The dialog a course row opens.

package org.ntust.app.tigerduck.ui.screen.score

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.CourseGrade
import org.ntust.app.tigerduck.ui.component.TigerDuckDialog
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import kotlin.math.roundToInt

@Composable
internal fun CourseDetailDialog(course: CourseGrade, onDismiss: () -> Unit) {
    TigerDuckDialog(
        onDismissRequest = onDismiss,
        title = course.name,
        confirmText = stringResource(R.string.action_close),
        onConfirm = onDismiss,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                InfoLine(stringResource(R.string.score_info_course_code), course.code)
                InfoLine(stringResource(R.string.score_info_term), displayTerm(course.term))
                InfoLine(stringResource(R.string.score_info_credits), "${course.credits ?: 0}")
                creditTypeLabel(course.creditType)?.let {
                    InfoLine(
                        stringResource(R.string.score_info_credit_type),
                        it
                    )
                }
                course.geDimension?.let {
                    InfoLine(
                        stringResource(R.string.score_info_general_dimension),
                        it
                    )
                }
                InfoLine(stringResource(R.string.score_info_grade), gradeDescriptor(course).first)
                if (course.distanceLearning) {
                    InfoLine(
                        stringResource(R.string.score_info_teaching_method),
                        stringResource(R.string.score_distance_learning)
                    )
                }
                if (course.remark.isNotEmpty()) InfoLine(
                    stringResource(R.string.score_info_note),
                    course.remark
                )
            }
        },
    )
}
@Composable
private fun InfoLine(label: String, value: String) {
    Row {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
            modifier = Modifier.width(72.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}
