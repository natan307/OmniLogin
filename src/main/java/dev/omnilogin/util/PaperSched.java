package dev.omnilogin.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Scheduler for Paper, Purpur and Folia (entity and async schedulers). */
final class PaperSched implements Sched {

    private final Plugin plugin;

    PaperSched(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void async(Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, scheduled -> task.run());
    }

    @Override
    public Handle every(Player player, Runnable task, long delayTicks, long periodTicks) {
        ScheduledTask scheduled = player.getScheduler()
                .runAtFixedRate(plugin, t -> task.run(), null, Math.max(1L, delayTicks), periodTicks);
        return () -> {
            if (scheduled != null) {
                scheduled.cancel();
            }
        };
    }

    @Override
    public void later(Player player, Runnable task, long delayTicks) {
        player.getScheduler().runDelayed(plugin, t -> task.run(), null, Math.max(1L, delayTicks));
    }

    @Override
    public void run(Player player, Runnable task) {
        player.getScheduler().run(plugin, t -> task.run(), null);
    }
}
