package com.pedrolopes.ttsing.data.news

/**
 * The list a story was opened from, so playback can carry on to the next one when it ends —
 * the news equivalent of a book reading on into its next chapter.
 *
 * A snapshot of ids in the order the list showed them: one feed's stories, one topic's, or
 * everything. "Next" then means exactly the story the listener would have tapped next, rather
 * than some order the playback service invents. In memory only: playback keeps the process
 * alive while it lasts, and a queue that outlived the session would be stale anyway.
 */
class NewsQueue {

    @Volatile
    private var ids: List<String> = emptyList()

    fun set(ids: List<String>) {
        this.ids = ids.toList()
    }

    /**
     * The first story after [currentId] that [isUnread] accepts, or null at the end of the
     * list. Already-heard stories are skipped, as is everything before the current one: going
     * back up the list would replay the news the listener chose to skip.
     */
    suspend fun nextAfter(currentId: String, isUnread: suspend (String) -> Boolean): String? {
        val snapshot = ids
        val start = snapshot.indexOf(currentId)
        if (start < 0) return null
        for (id in snapshot.subList(start + 1, snapshot.size)) {
            if (isUnread(id)) return id
        }
        return null
    }
}
