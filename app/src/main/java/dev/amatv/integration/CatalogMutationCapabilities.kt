package dev.amatv.integration

/**
 * Capabilities verified for this app's developer-token + Music-User-Token
 * transport. Library `canEdit` metadata alone does not grant private web APIs.
 */
data class CatalogMutationCapabilities(
    val removeFavorites: Boolean,
    val updatePlaylistMetadata: Boolean,
    val deletePlaylist: Boolean,
    val replaceOrReorderPlaylistTracks: Boolean,
    val updateFolderMetadata: Boolean = false,
    val deleteFolder: Boolean = false,
    val moveLibraryResources: Boolean = false,
    val movePlaylistFolders: Boolean = false,
    val removeLibrarySongs: Boolean = false,
) {
    companion object {
        val PublicMusicUserToken = CatalogMutationCapabilities(
            removeFavorites = false,
            updatePlaylistMetadata = false,
            deletePlaylist = false,
            replaceOrReorderPlaylistTracks = false,
        )
        // Updated per operation only after the root-owned temporary-resource probe passes.
        val VerifiedWebMusicUserToken = PublicMusicUserToken.copy(
            // management-write-01: exact name and ordered duplicate-preserving readbacks passed.
            updatePlaylistMetadata = true,
            replaceOrReorderPlaylistTracks = true,
            // management-recover-02: tombstone + original-container absence confirmed both deletions.
            deletePlaylist = true,
            deleteFolder = true, // Empty folders only; populated contents must be moved first.
            // management-remaining-01: exact folder name and both parent relationships read back.
            updateFolderMetadata = true,
            moveLibraryResources = true,
            movePlaylistFolders = true,
            // favorite-library-02: four writes, favorite restored false and new Library mapping removed.
            removeFavorites = true,
            removeLibrarySongs = true,
        )
    }
}

/** Accepted does not mean Apple's asynchronous favorite relation is visible yet. */
data class FavoriteMutationResult(val confirmedState: Boolean?) {
    val confirmed: Boolean get() = confirmedState == true
}

/** A write may have reached Apple; recover by reading, never automatically posting again. */
class FavoriteMutationUnconfirmedException(cause: Throwable) :
    IllegalStateException("Favorite state must be checked before another write", cause)
