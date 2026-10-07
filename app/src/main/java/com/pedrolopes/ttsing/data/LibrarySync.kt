package com.pedrolopes.ttsing.data

/**
 * The cached books a scan proves are gone from the library folder. A book missing from the scan
 * is only gone if the folder it lived in was really listed: an unreadable tree (revoked
 * permission, a removed SD card, a provider hiccup) lists as empty rather than failing, and
 * must not wipe the library and all reading progress.
 *
 * [folderOf] maps each cached book id to its folder path; [unlistedFolders] holds the paths
 * that could not be read ("" is the library folder itself, so it shields everything).
 */
internal fun booksGone(
    folderOf: Map<String, String>,
    seen: Set<String>,
    unlistedFolders: Set<String>,
): Set<String> = folderOf
    .filter { (id, folder) -> id !in seen && unlistedFolders.none { isInFolder(folder, it) } }
    .keys

/** Whether [folder] is [dir] or lies anywhere beneath it. */
private fun isInFolder(folder: String, dir: String) =
    dir.isEmpty() || folder == dir || folder.startsWith("$dir/")

/** What the provider says when a move lands on a name already taken. */
internal fun isAlreadyExists(message: String?) = message?.contains("already exists", ignoreCase = true) == true

internal fun alreadyThereMessage(fileName: String, folder: String) =
    "A file named $fileName is already in ${folder.substringAfterLast('/').ifEmpty { "Library" }}"
