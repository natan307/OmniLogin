package dev.omnilogin.util;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Scheduling that works on Folia (region threads), Paper and plain Spigot/Bukkit.
 * Everything that touches a player runs on that player's own thread.
 */
public interface Sched {

    /** A repeating task that can be stopped. */
    interface Handle {
        void cancel();
    }

    /** Runs blocking work (database, HTTP) off the game threads. */
    void async(Runnable task);

    /** Repeats a task on the player's thread. */
    Handle every(Player player, Runnable task, long delayTicks, long periodTicks);

    /** Runs a task on the player's thread after a delay. */
    void later(Player player, Runnable task, long delayTicks);

    /** Runs a task on the player's thread as soon as possible. */
    void run(Player player, Runnable task);

    static Sched create(Plugin plugin) {
        try {
            Class.forName("io.papermc.paper.threadedregions.scheduler.AsyncScheduler");
            return new PaperSched(plugin);
        } catch (ClassNotFoundException notPaper) {
            return new BukkitSched(plugin);
        }
    }
}
