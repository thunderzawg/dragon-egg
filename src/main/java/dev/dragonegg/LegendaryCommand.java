package dev.dragonegg;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class LegendaryCommand implements TabExecutor {

    private final LegendaryManager m;

    public LegendaryCommand(LegendaryManager m) {
        this.m = m;
    }

    private static void say(CommandSender s, String text, NamedTextColor c) {
        s.sendMessage(Component.text(text, c));
    }

    private void usage(CommandSender s) {
        say(s, "/legendary spawn <tunic|fork|griffin|hammer> [x y z] [now]", NamedTextColor.YELLOW);
        say(s, "/legendary cancel <tunic|fork|griffin|hammer|all>", NamedTextColor.YELLOW);
        say(s, "/legendary status", NamedTextColor.YELLOW);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "spawn" -> spawn(sender, args);
            case "cancel" -> cancel(sender, args);
            case "status" -> {
                for (String line : m.statusLines()) say(sender, line, NamedTextColor.GOLD);
            }
            default -> usage(sender);
        }
        return true;
    }

    private void spawn(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            say(sender, "Run this in game so I know which world to use.", NamedTextColor.RED);
            return;
        }
        if (args.length < 2) {
            usage(sender);
            return;
        }
        Legendary l = Legendary.byId(args[1]);
        if (l == null) {
            say(sender, "Unknown legendary: " + args[1], NamedTextColor.RED);
            return;
        }

        boolean now = args[args.length - 1].equalsIgnoreCase("now");
        int end = now ? args.length - 1 : args.length;

        Location loc = p.getLocation();
        if (end == 5) {
            try {
                double x = Double.parseDouble(args[2]);
                double y = Double.parseDouble(args[3]);
                double z = Double.parseDouble(args[4]);
                loc = new Location(p.getWorld(), x, y, z);
            } catch (NumberFormatException ex) {
                say(sender, "Coordinates must be numbers.", NamedTextColor.RED);
                return;
            }
        } else if (end != 2) {
            usage(sender);
            return;
        }

        int secs = now ? 0 : m.countdownSeconds();
        m.schedule(l, loc, secs);
        if (now) {
            say(sender, l.display + " spawned.", NamedTextColor.GREEN);
        } else {
            say(sender, l.display + " countdown started (" + secs + "s). Running the command again restarts it.", NamedTextColor.GREEN);
        }
    }

    private void cancel(CommandSender sender, String[] args) {
        if (args.length < 2) {
            usage(sender);
            return;
        }
        if (args[1].equalsIgnoreCase("all")) {
            say(sender, "Cancelled " + m.cancelAll() + " countdown(s).", NamedTextColor.GREEN);
            return;
        }
        Legendary l = Legendary.byId(args[1]);
        if (l == null) {
            say(sender, "Unknown legendary: " + args[1], NamedTextColor.RED);
            return;
        }
        boolean ok = m.cancel(l);
        say(sender, ok ? "Countdown cancelled." : "No countdown running for that one.",
                ok ? NamedTextColor.GREEN : NamedTextColor.RED);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("spawn", "cancel", "status")) {
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("spawn") || args[0].equalsIgnoreCase("cancel"))) {
            for (Legendary l : Legendary.values()) {
                if (l.id.startsWith(args[1].toLowerCase())) out.add(l.id);
            }
            if (args[0].equalsIgnoreCase("cancel") && "all".startsWith(args[1].toLowerCase())) out.add("all");
        } else if (args.length >= 3 && args[0].equalsIgnoreCase("spawn") && "now".startsWith(args[args.length - 1].toLowerCase())) {
            out.add("now");
        }
        return out;
    }
}
