package app.keyshelf

/** Session UI state only. Secret values are always read from the unlocked vault. */
class QuickSearchState {
    var query = ""
    var expandedId: String? = null
    var scrollY = 0
    var favoritesOnly = false
    fun clear() { query = ""; expandedId = null; scrollY = 0; favoritesOnly = false }
}
