package org.ntust.app.tigerduck.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.sharing.ImportedClassTableStore
import org.ntust.app.tigerduck.data.sharing.ImportedPerson
import org.ntust.app.tigerduck.ui.AppScale
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.PageScaffold
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import javax.inject.Inject

/**
 * A Today or Week widget's configuration: whose class table it shows, the user's own or one
 * imported from a share code ([WidgetClassTable]). Android's counterpart of the iPhone's widget
 * editor. From Android 12 the launcher offers it on a placed widget and skips it on placement
 * (`reconfigurable|configuration_optional`); before, it opens on placement, and passes straight
 * through when there is no one to choose.
 */
@AndroidEntryPoint
class WidgetClassTableActivity : AppCompatActivity() {

    @Inject lateinit var appState: AppState
    @Inject lateinit var importedClassTables: ImportedClassTableStore
    @Inject lateinit var widgetUpdater: WidgetUpdater

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    // The sizes picked on the Display and text size page, as every screen of the app has them.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppScale.stored(newBase).wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        // Backing out keeps the widget's choice, and cancels a placement that asked for one.
        setResult(RESULT_CANCELED, result())
        // Exported for the launcher, so only an id of this app's Today or Week widgets is taken.
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)?.provider
        if (!WidgetClassTable.takesChoice(provider, packageName)) {
            finish()
            return
        }
        val glanceId = GlanceAppWidgetManager(this).getGlanceIdBy(appWidgetId)
        lifecycleScope.launch {
            val people = importedClassTables.load().people
            val stored = getAppWidgetState(this@WidgetClassTableActivity, PreferencesGlanceStateDefinition, glanceId)
            val chosen = WidgetClassTable.personOf(stored[WidgetClassTable.PersonKey], people)?.id
            if (people.isEmpty() && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                choose(glanceId, null)
                return@launch
            }
            enableEdgeToEdge()
            setContent {
                val dark = when (appState.themeMode) {
                    "dark" -> true
                    "light" -> false
                    else -> isSystemInDarkTheme()
                }
                TigerDuckAppTheme(
                    darkTheme = dark,
                    accentColor = appState.accentColor(dark),
                    onAccentColor = appState.accentOnColor(dark),
                    accentGradient = appState.accentGradient(dark),
                    useDynamicColor = appState.usesWallpaperColors,
                    visualPreset = appState.visualPreset,
                ) {
                    WidgetClassTableScreen(people, chosen, onChoose = { choose(glanceId, it) }, onBack = ::finish)
                }
            }
        }
    }

    /** Keeps [personId], null for the user's own, as the widget's choice, redraws it and closes. */
    private fun choose(glanceId: GlanceId, personId: String?) {
        lifecycleScope.launch {
            withContext(NonCancellable) {
                updateAppWidgetState(applicationContext, glanceId) { prefs ->
                    if (personId == null) prefs.remove(WidgetClassTable.PersonKey) else prefs[WidgetClassTable.PersonKey] = personId
                }
            }
            // Every widget, so the boundaries of the table it shows now are armed too.
            widgetUpdater.requestUpdate()
            setResult(RESULT_OK, result())
            finish()
        }
    }

    private fun result() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
}

/** The class tables a widget can show, the user's own first, with [chosen] (null for the user's own) marked. */
@Composable
internal fun WidgetClassTableScreen(
    people: List<ImportedPerson>,
    chosen: String?,
    onChoose: (String?) -> Unit,
    onBack: () -> Unit,
) {
    PageScaffold(title = stringResource(R.string.widget_class_table_choose), onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(top = 4.dp, bottom = 32.dp),
        ) {
            SectionHeader(stringResource(R.string.widget_class_table_choice))
            val options = listOf<Pair<String?, String>>(null to stringResource(R.string.widget_class_table_own)) +
                people.map { it.id to it.nickname }
            ContentCard {
                Column(Modifier.selectableGroup()) {
                    options.forEachIndexed { index, (id, name) ->
                        ClassTableChoiceRow(name, selected = id == chosen, onClick = { onChoose(id) })
                        if (index < options.lastIndex) HorizontalDivider(Modifier.padding(start = 56.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ClassTableChoiceRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
