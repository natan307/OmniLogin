package dev.omnilogin.auth;

import java.util.Locale;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /register, /login, /changepassword and the admin command /omniauth.
 */
public class AuthCommands implements CommandExecutor {

    private static final String ADMIN_PERMISSION = "omnilogin.admin";

    private final AuthManager manager;

    public AuthCommands(AuthManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if ("omniauth".equals(name)) {
            return admin(sender, args);
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }

        Player player = (Player) sender;
        switch (name) {
            case "register":
                if (args.length == 2) {
                    manager.register(player, args[0], args[1]);
                } else {
                    player.sendMessage(manager.message("usage-register"));
                }
                return true;
            case "login":
                if (args.length == 1) {
                    manager.login(player, args[0]);
                } else {
                    player.sendMessage(manager.message("usage-login"));
                }
                return true;
            case "changepassword":
                if (args.length == 2) {
                    manager.changePassword(player, args[0], args[1]);
                } else {
                    player.sendMessage(manager.message("usage-changepassword"));
                }
                return true;
            default:
                return false;
        }
    }

    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage("You do not have permission.");
            return true;
        }

        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if ("unregister".equals(action) && args.length == 2) {
            manager.adminUnregister(sender, args[1]);
        } else if ("setpassword".equals(action) && args.length == 3) {
            manager.adminSetPassword(sender, args[1], args[2]);
        } else {
            sender.sendMessage("Usage: /omniauth unregister <name> | /omniauth setpassword <name> <password>");
        }

        return true;
    }
}
