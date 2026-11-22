package dev.hearthold.clan;

/** Clan action error; the message is shown to the player. */
public final class ClanException extends Exception {
    public ClanException(String message) {
        super(message);
    }
}
