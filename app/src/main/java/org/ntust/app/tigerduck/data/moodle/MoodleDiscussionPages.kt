package org.ntust.app.tigerduck.data.moodle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.network.moodle.MoodleCourseApi
import org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussionList
import org.ntust.app.tigerduck.network.model.moodle.MoodleTermForums

/**
 * One forum's discussions a page at a time. A refresh racing a page load could discard pages just
 * added, so list work runs one piece at a time, in the order asked. Loading a list never marks a
 * discussion read; only opening one does. Ported from iOS `MoodleDiscussionPages`.
 */
class MoodleDiscussionPages(
    val forumId: Int,
    val feed: MoodleFeed<MoodleDiscussionList>,
    /** Sorted by the latest post rather than the posting time, as a forum's are; announcements are not. */
    val byModified: Boolean,
    private val forums: MoodleFeed<MoodleTermForums>,
    private val api: MoodleCourseApi,
    private val scope: CoroutineScope,
) {
    /** The list on screen; null until something for this forum is cached or fetched. */
    val list: StateFlow<MoodleDiscussionList?> = feed.state
        .map { state -> state.value?.takeIf { it.forumId == forumId } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _pageFailure = MutableStateFlow<Throwable?>(null)

    /** The last next-page load's error, kept until a page loads, so the list can offer a retry. */
    val pageFailure: StateFlow<Throwable?> = _pageFailure.asStateFlow()

    val fetchedAtMs: Long?
        get() = feed.state.value.takeIf { it.value?.forumId == forumId }?.fetchedAtMs

    /** [list] as of now, read from the feed itself rather than the copy [list] trails it with. */
    private fun currentList(): MoodleDiscussionList? = feed.value?.takeIf { it.forumId == forumId }

    private val listWork = Mutex()
    private val refreshLock = Mutex()
    private var refreshing: Deferred<Unit>? = null

    /**
     * Fetches again every page shown so far. A complete answer also corrects the forum's counts,
     * so a list that emptied takes its section row with it. A refresh asked for while one is
     * queued or running joins it, so a pull during a timed refresh fetches once.
     */
    suspend fun refresh() {
        val job = refreshLock.withLock {
            refreshing?.takeIf { it.isActive } ?: scope.async { listWork.withLock { refreshInTurn() } }
                .also { refreshing = it }
        }
        job.await()
    }

    private suspend fun refreshInTurn() {
        val pages = maxOf(1, currentList()?.pageCount ?: 1)
        feed.refresh {
            var fresh = MoodleDiscussions.empty(forumId)
            if (MoodleCourseApi.DISCUSSIONS_FUNCTION !in api.functions()) return@refresh fresh
            do {
                fresh = MoodleDiscussions.append(fresh, api.discussions(forumId, fresh.pageCount))
            } while (fresh.hasMore && fresh.pageCount < pages)
            fresh
        }
        val state = feed.state.value
        val fresh = state.value?.takeIf { it.forumId == forumId } ?: return
        if (state.failure != null || fresh.hasMore) return
        _pageFailure.value = null
        forums.update { term ->
            term.copy(
                forums = term.forums.map { forum ->
                    if (forum.id != forumId) forum
                    else forum.copy(
                        discussionCount = fresh.discussions.size,
                        unreadPostCount = fresh.discussions.sumOf { it.unreadCount },
                    )
                },
            )
        }
    }

    /** The next page, when the list's end, [lastId], is on screen. */
    suspend fun loadMore(lastId: Int?) {
        listWork.withLock {
            val current = currentList() ?: return
            if (!current.hasMore || lastId != MoodleDiscussions.sorted(current, byModified).lastOrNull()?.id) return
            _isLoadingMore.value = true
            try {
                if (MoodleCourseApi.DISCUSSIONS_FUNCTION !in api.functions()) {
                    _pageFailure.value = null
                    feed.update { it.copy(hasMore = false) }
                    return
                }
                val page = api.discussions(forumId, current.pageCount)
                _pageFailure.value = null
                feed.update { latest ->
                    if (latest.forumId != current.forumId || latest.pageCount != current.pageCount) latest
                    else MoodleDiscussions.append(latest, page)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _pageFailure.value = e
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    /**
     * Clears [discussionId]'s unread mark here and on the forum's section row.
     *
     * The forum's count change is replayed onto a forums answer that was under way, and Moodle may
     * have logged the view before giving that answer, which then already leaves this discussion
     * out. So the count never drops below what the other listed discussions still hold unread:
     * subtracting twice would hide their dot.
     */
    fun markRead(discussionId: Int) {
        val list = currentList() ?: return
        val unread = list.discussions.firstOrNull { it.id == discussionId }?.unreadCount ?: 0
        if (unread <= 0) return
        val othersUnread = list.discussions.sumOf { if (it.id == discussionId) 0 else it.unreadCount }
        feed.update { current ->
            current.copy(discussions = current.discussions.map { if (it.id == discussionId) it.copy(unreadCount = 0) else it })
        }
        forums.update { term ->
            term.copy(
                forums = term.forums.map { forum ->
                    if (forum.id != forumId) forum
                    else forum.copy(
                        unreadPostCount = maxOf(
                            minOf(othersUnread, forum.unreadPostCount),
                            forum.unreadPostCount - unread,
                        ),
                    )
                },
            )
        }
    }
}
