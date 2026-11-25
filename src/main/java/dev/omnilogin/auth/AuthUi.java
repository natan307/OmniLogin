package dev.omnilogin.auth;

import org.bukkit.entity.Player;

/** A way to ask a player for their password (besides the chat commands). */
public interface AuthUi {

    /**
     * Shows the login or register form. Always called on the player's own thread.
     *
     * @param register true for the register form, false for the login form
     * @param error    a message to show above the form, or null
     */
    void show(Player player, boolean register, String error);
}
