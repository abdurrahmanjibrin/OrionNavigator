package com.orion.app.data

/**
 * Data class representing a link between child and parent accounts
 */
data class UserLink(
    /** Child user's ID */
    val childId: String = "",
    /** Parent user's ID */
    val parentId: String = "",
    /** Timestamp when the link was created (Unix millis) */
    val linkedAt: Long = 0L,
    /** Whether the link is active */
    val isActive: Boolean = true
) {
    /** No-arg constructor for Firebase deserialization */
    constructor() : this("", "", 0L, true)
}
