package `in`.wishkaro.app.ui.person

import `in`.wishkaro.app.data.remote.WishKaroApi
import `in`.wishkaro.app.platform.FileGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A person's photo on their own screen — picking it, showing it, removing it.
 *
 * ## This is not a new feature, it is a feature that was already half built
 *
 * `FR-PPL-04` lists a photo among the profile fields. Every piece of the machinery has existed
 * since M6 and only the poster studio used it:
 *
 *  - `person.photo_key` in `schema.prisma`, added in **M1**, under a comment saying *"the
 *    column exists now so the feature is a code change later rather than a migration"*;
 *  - `POST /v1/people/:id/photo`, `GET`, and `DELETE` — `WishKaroApi.uploadPhoto`, `photoUrl`
 *    and `deletePhoto`, whose own doc comment names **FR-PPL-04** first and FR-POS-01 second;
 *  - the system photo picker in `MainActivity`, which needs no permission at all;
 *  - M9's retention sweep, which already clears `photo_key` when the object it points at is
 *    swept.
 *
 * So the photo could be set only by opening the poster studio, and could be seen only on a
 * poster. **No server change** was needed to put it where it belongs, and this milestone does
 * not make one.
 *
 * ## Bytes, not a URL
 *
 * The same choice `PosterStore` makes, for the same reason: the URL is signed, and a signed URL
 * that lives long enough to be convenient is a capability sitting in an image cache. The bytes
 * are fetched once when the screen opens and held for as long as it is.
 *
 * ## The guard that matters
 *
 * Every completion checks that the person it was started for is still the person on screen.
 * `PosterStore` carries the same guard with the same comment — *"person A's photo must not
 * appear on person B"* — and this store is reachable by the same route that made it necessary
 * there: a two-pane layout where tapping a second person replaces the detail pane while the
 * first one's fetch is still in flight.
 */
class PersonPhotoStore(
    private val api: WishKaroApi,
    private val files: FileGateway,
    private val scope: CoroutineScope,
) {

    data class PhotoState(
        val personId: String? = null,
        /** The image itself, once fetched. Null means "none" or "not yet" — see [loaded]. */
        val bytes: ByteArray? = null,
        val uploading: Boolean = false,
        /**
         * False until the first fetch has answered.
         *
         * The same distinction the rest of this app keeps between "we have not looked" and
         * "there is nothing": offering "Add a photo" for the frame before the answer arrives
         * would be a button that changes under the user's finger.
         */
        val loaded: Boolean = false,
    ) {
        /** `ByteArray` has reference equality, so a data class holding one needs these. */
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PhotoState) return false
            return personId == other.personId &&
                uploading == other.uploading &&
                loaded == other.loaded &&
                bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int {
            var result = personId?.hashCode() ?: 0
            result = 31 * result + (bytes?.contentHashCode() ?: 0)
            result = 31 * result + uploading.hashCode()
            result = 31 * result + loaded.hashCode()
            return result
        }
    }

    private val _state = MutableStateFlow(PhotoState())
    val state: StateFlow<PhotoState> = _state.asStateFlow()

    /** Called when a person's screen opens. Idempotent for the same person. */
    fun open(personId: String) {
        if (_state.value.personId == personId) return
        _state.value = PhotoState(personId = personId)
        scope.launch { refresh(personId) }
    }

    fun pick() {
        val personId = _state.value.personId ?: return
        scope.launch {
            _state.value = _state.value.copy(uploading = true)
            val picked = files.pickPhoto()
            if (picked == null) {
                // Cancelled at the system picker, which is an ordinary outcome and not a
                // failure to report.
                update(personId) { it.copy(uploading = false) }
                return@launch
            }
            runCatching { api.uploadPhoto(personId, picked.bytes, picked.mimeType) }
            update(personId) { it.copy(uploading = false) }
            refresh(personId)
        }
    }

    fun remove() {
        val personId = _state.value.personId ?: return
        scope.launch {
            runCatching { api.deletePhoto(personId) }
            update(personId) { it.copy(bytes = null, loaded = true) }
        }
    }

    private suspend fun refresh(personId: String) {
        val url = runCatching { api.photoUrl(personId) }.getOrNull()
        val bytes = url
            ?.let { runCatching { api.downloadMedia(api.absoluteUrl(it)) }.getOrNull() }
        update(personId) { it.copy(bytes = bytes, loaded = true) }
    }

    /**
     * Apply a change only if the screen is still about the same person.
     *
     * Not a convenience: an upload and a fetch both cross a network, and in a two-pane layout
     * the user can open somebody else while either is in flight.
     */
    private fun update(personId: String, change: (PhotoState) -> PhotoState) {
        val current = _state.value
        if (current.personId != personId) return
        _state.value = change(current)
    }
}
