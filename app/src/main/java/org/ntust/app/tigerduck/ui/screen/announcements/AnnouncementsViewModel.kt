package org.ntust.app.tigerduck.ui.screen.announcements

import androidx.lifecycle.ViewModel
import org.ntust.app.tigerduck.data.model.Announcement
import org.ntust.app.tigerduck.data.model.MockData
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

@HiltViewModel
class AnnouncementsViewModel @Inject constructor(
    private val prefs: AppPreferences
) : ViewModel() {

    private val _announcements = MutableStateFlow<List<Announcement>>(emptyList())
    val announcements: StateFlow<List<Announcement>> = _announcements

    private val _selectedDepartments = MutableStateFlow<Set<String>>(emptySet())
    val selectedDepartments: StateFlow<Set<String>> = _selectedDepartments

    private val _searchText = MutableStateFlow("")
    val searchText: StateFlow<String> = _searchText

    private val _filteredAnnouncements = MutableStateFlow<List<Announcement>>(emptyList())
    val filteredAnnouncements: StateFlow<List<Announcement>> = _filteredAnnouncements

    private val _departments = MutableStateFlow<List<String>>(emptyList())
    val departments: StateFlow<List<String>> = _departments

    fun load() {
        _announcements.value = MockData.announcements
        if (prefs.rememberAnnouncementFilter) {
            _selectedDepartments.value = prefs.savedAnnouncementDepartments
        }
        refilter()
    }

    fun setSearchText(text: String) {
        _searchText.value = text
        refilter()
    }

    fun toggleDepartment(dept: String) {
        val current = _selectedDepartments.value.toMutableSet()
        if (dept in current) current.remove(dept) else current.add(dept)
        _selectedDepartments.value = current
        if (prefs.rememberAnnouncementFilter) {
            prefs.savedAnnouncementDepartments = current
        }
        refilter()
    }

    private fun refilter() {
        val all = _announcements.value
        _departments.value = all.map { it.department }.toSet().sorted()

        var result = all
        val selected = _selectedDepartments.value
        if (selected.isNotEmpty()) {
            result = result.filter { it.department in selected }
        }
        val search = _searchText.value
        if (search.isNotBlank()) {
            result = result.filter {
                it.title.contains(search, ignoreCase = true) ||
                it.summary.contains(search, ignoreCase = true)
            }
        }
        _filteredAnnouncements.value = result.sortedByDescending { it.publishDate }
    }
}
