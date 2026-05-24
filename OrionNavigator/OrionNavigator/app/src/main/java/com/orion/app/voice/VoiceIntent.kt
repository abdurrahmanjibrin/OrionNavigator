package com.orion.app.voice

/**
 * All supported voice command intents for Orion Navigator.
 * Used by both local keyword matching and Gemini NLU.
 */
enum class VoiceIntent {
    // Home screen navigation
    OPEN_LIVE_LOCATION,
    OPEN_NAVIGATION,
    OPEN_CAMERA,
    LOGOUT,

    // Camera AI commands
    DESCRIBE_SCENE,
    TOGGLE_SOUND_ON,
    TOGGLE_SOUND_OFF,

    // Direction/Navigation commands
    CHECK_LOCATION,
    SELECT_DESTINATION,
    START_NAVIGATION,
    STOP_NAVIGATION,

    // Live Location commands
    TOGGLE_LOCATION_ON,
    TOGGLE_LOCATION_OFF,

    // Global commands
    GO_BACK,
    HELP,

    // Fallback
    UNKNOWN
}
