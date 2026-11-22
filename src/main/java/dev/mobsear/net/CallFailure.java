package dev.mobsear.net;

/** Fixed, private outcomes of a numbered call. Only the caller can receive these. */
public enum CallFailure {
    NO_ELIGIBLE_MOB, NO_RECENT_VOICE, COOLDOWN, GAME_MODE, INPUT_BLOCKED
}
