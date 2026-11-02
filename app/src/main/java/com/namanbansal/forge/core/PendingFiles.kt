package com.namanbansal.forge.core

/**
 * Hands files to a tool that is about to open (share sheet, "Next" chips). A tool's ViewModel
 * takes its files once on creation, so the hand-off survives navigation but not stale re-use.
 */
class PendingFiles {
    private val map = HashMap<String, List<PickedFile>>()

    @Synchronized fun put(toolCode: String, files: List<PickedFile>) { map[toolCode] = files }

    @Synchronized fun take(toolCode: String): List<PickedFile>? = map.remove(toolCode)
}
