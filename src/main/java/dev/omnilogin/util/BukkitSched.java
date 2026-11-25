package dev.omnilogin.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** Scheduler for Spigot and Bukkit. */
final class BukkitSched implements Sched {

    private final Plugin plugin;

    BukkitSched(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void async(Runnable task) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    @Override
    public Handle every(Player player, Runnable task, long delayTicks, long periodTicks) {
        BukkitTask bukkit = Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
        return bukkit::cancel;
    }

    @Override
    public void later(Player player, Runnable task, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
    }

    @Override
    public void run(Player player, Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }
}
