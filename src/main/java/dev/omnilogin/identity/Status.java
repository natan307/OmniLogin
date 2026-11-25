package dev.omnilogin.identity;

/** How a connection was identified by OmniLogin. */
public enum Status {
    /** Verified by the Microsoft/Mojang session server. */
    MICROSOFT,
    /** Verified by the Ely.by session server. */
    ELYBY,
    /** Not verified: a free name, joining as a cracked player. */
    CRACKED;

    public boolean isPremium() {
        return this != CRACKED;
    }
}
