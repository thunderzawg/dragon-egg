package dev.dragonegg;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class LegendaryManager {

    private final JavaPlugin plugin;
    private final NamespacedKey keyId;
    private final NamespacedKey keySerial;
    private final File dataFile;
    private final YamlConfiguration data;
    private final Map<Legendary, Pending> pending = new EnumMap<>(Legendary.class);

    // water-walking state for the Griffin's rider
    private Player fakeViewer;
    private final Set<Location> fakeBlocks = new HashSet<>();
    private int griffinTicks = 0;

    private static final class Pending {
        Location loc;
        int remaining;
        BukkitTask task;
    }

    public LegendaryManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.keyId = new NamespacedKey(plugin, "legendary_id");
        this.keySerial = new NamespacedKey(plugin, "legendary_serial");

        plugin.saveDefaultConfig();
        plugin.getDataFolder().mkdirs();
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
        this.data = YamlConfiguration.loadConfiguration(dataFile);

        Bukkit.getPluginManager().registerEvents(new LegendaryListener(this), plugin);

        LegendaryCommand cmd = new LegendaryCommand(this);
        PluginCommand pc = plugin.getCommand("legendary");
        if (pc != null) {
            pc.setExecutor(cmd);
            pc.setTabCompleter(cmd);
        }

        Bukkit.getScheduler().runTaskTimer(plugin, this::effectTick, 20L, 10L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::sweepTick, 40L, 20L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::griffinTick, 40L, 2L);
    }

    public void shutdown() {
        resetFake();
        for (Pending p : pending.values()) {
            if (p.task != null) p.task.cancel();
        }
        pending.clear();
    }

    // ------------------------------------------------------------------ config

    public boolean banNetherite() { return plugin.getConfig().getBoolean("ban.netherite-crafting", true); }
    public boolean banTridents() { return plugin.getConfig().getBoolean("ban.normal-tridents", true); }
    public boolean banMaces() { return plugin.getConfig().getBoolean("ban.normal-maces", true); }
    public boolean banKnockback2() { return plugin.getConfig().getBoolean("ban.knockback-2", true); }
    public int countdownSeconds() { return plugin.getConfig().getInt("countdown-seconds", 600); }
    private boolean announceCoords() { return plugin.getConfig().getBoolean("announce-coordinates", true); }

    // ------------------------------------------------------------------ text helpers

    private static Component txt(String s, NamedTextColor c) {
        return Component.text(s, c).decoration(TextDecoration.ITALIC, false);
    }

    private static Component bold(String s, NamedTextColor c) {
        return Component.text(s, c).decorate(TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false);
    }

    private String where(Location loc) {
        if (!announceCoords() || loc.getWorld() == null) return "";
        return " at " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ()
                + " (" + loc.getWorld().getName() + ")";
    }

    private static String timeText(int sec) {
        if (sec >= 60 && sec % 60 == 0) {
            int min = sec / 60;
            return min + (min == 1 ? " minute" : " minutes");
        }
        return sec + (sec == 1 ? " second" : " seconds");
    }

    private void broadcast(Component c) {
        Bukkit.getServer().sendMessage(c);
    }

    // ------------------------------------------------------------------ tagging

    public String idOf(ItemStack s) {
        if (s == null || s.getType().isAir() || !s.hasItemMeta()) return null;
        return s.getItemMeta().getPersistentDataContainer().get(keyId, PersistentDataType.STRING);
    }

    public Legendary typeOf(ItemStack s) {
        return Legendary.byId(idOf(s));
    }

    public boolean isLegendaryItem(ItemStack s) {
        return typeOf(s) != null;
    }

    public boolean isLegendary(ItemStack s, Legendary l) {
        return typeOf(s) == l;
    }

    private int serialOf(ItemStack s) {
        Integer v = s.getItemMeta().getPersistentDataContainer().get(keySerial, PersistentDataType.INTEGER);
        return v == null ? -1 : v;
    }

    public int currentSerial(Legendary l) {
        return data.getInt("serial." + l.id, 0);
    }

    private int bumpSerial(Legendary l) {
        int n = currentSerial(l) + 1;
        data.set("serial." + l.id, n);
        saveData();
        return n;
    }

    /** an old copy that was replaced when an operator spawned a new one */
    public boolean isStale(ItemStack s) {
        Legendary l = typeOf(s);
        return l != null && serialOf(s) != currentSerial(l);
    }

    public boolean isGriffin(Entity e) {
        return e instanceof AbstractHorse
                && Legendary.GRIFFIN.id.equals(e.getPersistentDataContainer().get(keyId, PersistentDataType.STRING));
    }

    public boolean isStaleGriffin(Entity e) {
        if (!isGriffin(e)) return false;
        Integer v = e.getPersistentDataContainer().get(keySerial, PersistentDataType.INTEGER);
        return v == null || v != currentSerial(Legendary.GRIFFIN);
    }

    public void setGriffinUuid(UUID id) {
        data.set("griffin.uuid", id.toString());
        saveData();
    }

    public boolean isBannedNormal(ItemStack s) {
        if (s == null) return false;
        Material t = s.getType();
        if (t != Material.TRIDENT && t != Material.MACE) return false;
        if (isLegendaryItem(s)) return false;
        return (t == Material.TRIDENT && banTridents()) || (t == Material.MACE && banMaces());
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save data.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ item / horse creation

    public ItemStack createItem(Legendary l, int serial) {
        Material mat;
        switch (l) {
            case TUNIC -> mat = Material.NETHERITE_CHESTPLATE;
            case FORK -> mat = Material.TRIDENT;
            case HAMMER -> mat = Material.MACE;
            default -> throw new IllegalArgumentException("not an item: " + l);
        }

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setUnbreakable(true);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(keyId, PersistentDataType.STRING, l.id);
        meta.getPersistentDataContainer().set(keySerial, PersistentDataType.INTEGER, serial);

        switch (l) {
            case TUNIC -> {
                meta.displayName(bold("The Legendary Tunic", NamedTextColor.GOLD));
                meta.lore(List.of(
                        txt("Forged in the heart of the Nether.", NamedTextColor.GRAY),
                        txt("Wearer is forever immune to fire.", NamedTextColor.RED),
                        txt("Legendary - only one exists.", NamedTextColor.DARK_PURPLE)));
                if (meta instanceof ArmorMeta am) {
                    TrimMaterial tm = Registry.TRIM_MATERIAL.get(NamespacedKey.minecraft("gold"));
                    TrimPattern tp = Registry.TRIM_PATTERN.get(NamespacedKey.minecraft("silence"));
                    if (tm != null && tp != null) am.setTrim(new ArmorTrim(tm, tp));
                }
            }
            case FORK -> {
                meta.displayName(bold("FORK", NamedTextColor.AQUA));
                meta.lore(List.of(
                        txt("Strikes like an axe, swings like a sword.", NamedTextColor.GRAY),
                        txt("Shatters shields.", NamedTextColor.AQUA),
                        txt("Legendary - only one exists.", NamedTextColor.DARK_PURPLE)));
                // diamond axe damage (9) with diamond sword speed (1.6)
                meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(
                        new NamespacedKey(plugin, "fork_damage"), 8.0,
                        AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
                meta.addAttributeModifier(Attribute.ATTACK_SPEED, new AttributeModifier(
                        new NamespacedKey(plugin, "fork_speed"), -2.4,
                        AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
            }
            case HAMMER -> {
                meta.displayName(bold("THE HAMMER", NamedTextColor.RED));
                meta.lore(List.of(
                        txt("Those it strikes glow and rise.", NamedTextColor.GRAY),
                        txt("Legendary - only one exists.", NamedTextColor.DARK_PURPLE)));
            }
            default -> { }
        }
        item.setItemMeta(meta);
        return item;
    }

    private Horse spawnGriffin(Location loc, int serial) {
        World w = loc.getWorld();
        return w.spawn(loc, Horse.class, h -> {
            h.setAdult();
            h.setTamed(true);
            h.setColor(Horse.Color.WHITE);
            h.setStyle(Horse.Style.NONE);
            h.customName(bold("Griffin", NamedTextColor.GOLD));
            h.setCustomNameVisible(true);
            h.setGlowing(true);
            h.setInvulnerable(true);
            h.setPersistent(true);
            h.setRemoveWhenFarAway(false);

            AttributeInstance speed = h.getAttribute(Attribute.MOVEMENT_SPEED);
            if (speed != null) speed.setBaseValue(0.3375);
            AttributeInstance jump = h.getAttribute(Attribute.JUMP_STRENGTH);
            if (jump != null) jump.setBaseValue(1.0);
            AttributeInstance hp = h.getAttribute(Attribute.MAX_HEALTH);
            if (hp != null) hp.setBaseValue(30.0);
            h.setHealth(30.0);

            h.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, 0, false, false, false));

            h.getInventory().setSaddle(new ItemStack(Material.SADDLE));
            Material am = Material.matchMaterial("NETHERITE_HORSE_ARMOR");
            if (am == null) am = Material.DIAMOND_HORSE_ARMOR;
            ItemStack armor = new ItemStack(am);
            ItemMeta am2 = armor.getItemMeta();
            am2.setUnbreakable(true);
            armor.setItemMeta(am2);
            h.getInventory().setArmor(armor);

            h.getPersistentDataContainer().set(keyId, PersistentDataType.STRING, Legendary.GRIFFIN.id);
            h.getPersistentDataContainer().set(keySerial, PersistentDataType.INTEGER, serial);
        });
    }

    public void protectItem(Item i) {
        i.setInvulnerable(true);
        i.setUnlimitedLifetime(true);
        i.setGlowing(true);
    }

    // ------------------------------------------------------------------ spawning / countdown

    public void schedule(Legendary l, Location loc, int seconds) {
        cancel(l);
        if (seconds <= 0) {
            spawnNow(l, loc);
            return;
        }
        Pending ps = new Pending();
        ps.loc = loc.clone();
        ps.remaining = seconds;
        pending.put(l, ps);
        announceCountdown(l, ps);

        ps.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            ps.remaining--;
            if (ps.remaining <= 0) {
                ps.task.cancel();
                pending.remove(l);
                spawnNow(l, ps.loc);
                return;
            }
            int s = ps.remaining;
            if ((s >= 60 && s % 60 == 0) || s == 30 || s == 10 || s <= 5) {
                announceCountdown(l, ps);
            }
        }, 20L, 20L);
    }

    private void announceCountdown(Legendary l, Pending ps) {
        broadcast(Component.text("[Legendary] ", NamedTextColor.DARK_PURPLE)
                .append(Component.text(l.display, NamedTextColor.GOLD))
                .append(Component.text(" will appear in " + timeText(ps.remaining) + where(ps.loc) + "!", NamedTextColor.YELLOW)));
    }

    public boolean cancel(Legendary l) {
        Pending p = pending.remove(l);
        if (p == null) return false;
        if (p.task != null) p.task.cancel();
        return true;
    }

    public int cancelAll() {
        int n = 0;
        for (Legendary l : Legendary.values()) {
            if (cancel(l)) n++;
        }
        return n;
    }

    public List<String> statusLines() {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (Legendary l : Legendary.values()) {
            Pending p = pending.get(l);
            String s = l.display + ": ";
            if (p != null) {
                Location loc = p.loc;
                s += "spawning in " + timeText(p.remaining) + " at " + loc.getBlockX() + " " + loc.getBlockY() + " "
                        + loc.getBlockZ() + " (" + (loc.getWorld() == null ? "?" : loc.getWorld().getName()) + ")";
            } else if (currentSerial(l) == 0) {
                s += "never spawned";
            } else {
                s += "spawned (copy #" + currentSerial(l) + ")";
            }
            out.add(s);
        }
        return out;
    }

    public void spawnNow(Legendary l, Location loc) {
        World w = loc.getWorld();
        if (w == null) return;
        w.getChunkAt(loc); // make sure the chunk is loaded

        int serial = bumpSerial(l);
        purgeAll(); // the previous copy vanishes wherever it is

        if (l == Legendary.GRIFFIN) {
            Horse h = spawnGriffin(loc, serial);
            setGriffinUuid(h.getUniqueId());
        } else {
            Item drop = w.dropItem(loc, createItem(l, serial));
            drop.setVelocity(new Vector(0, 0, 0));
            protectItem(drop);
        }
        w.strikeLightningEffect(loc);

        broadcast(Component.text("[Legendary] ", NamedTextColor.DARK_PURPLE)
                .append(Component.text(l.display, NamedTextColor.GOLD))
                .append(Component.text(" has appeared" + where(loc) + "!", NamedTextColor.YELLOW)));
    }

    // ------------------------------------------------------------------ cleaning up old / banned copies

    public void sweepPlayer(Player p) {
        PlayerInventory inv = p.getInventory();
        int size = inv.getSize();
        for (int i = 0; i < size; i++) {
            ItemStack s = inv.getItem(i);
            if (s == null || s.getType().isAir()) continue;
            if (isStale(s) || isBannedNormal(s)) {
                inv.setItem(i, null);
            }
        }
        ItemStack cur = p.getItemOnCursor();
        if (cur != null && !cur.getType().isAir() && (isStale(cur) || isBannedNormal(cur))) {
            p.setItemOnCursor(null);
        }
    }

    public void purgeAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            sweepPlayer(p);
        }
        for (World w : Bukkit.getWorlds()) {
            for (Item i : w.getEntitiesByClass(Item.class)) {
                if (isStale(i.getItemStack())) i.remove();
            }
            for (AbstractHorse h : w.getEntitiesByClass(AbstractHorse.class)) {
                if (isStaleGriffin(h)) h.remove();
            }
        }
    }

    private void sweepTick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            sweepPlayer(p);
        }
    }

    // ------------------------------------------------------------------ powers

    private void effectTick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            ItemStack chest = p.getInventory().getChestplate();
            boolean wearing = isLegendary(chest, Legendary.TUNIC) && !isStale(chest);
            PotionEffect cur = p.getPotionEffect(PotionEffectType.FIRE_RESISTANCE);
            if (wearing) {
                if (cur == null || cur.getDuration() != PotionEffect.INFINITE_DURATION) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE,
                            PotionEffect.INFINITE_DURATION, 0, false, false, true));
                }
            } else if (cur != null && cur.getDuration() == PotionEffect.INFINITE_DURATION && cur.getAmplifier() == 0) {
                p.removePotionEffect(PotionEffectType.FIRE_RESISTANCE);
            }
        }
    }

    public void breakShield(Player victim) {
        String mode = plugin.getConfig().getString("shield-mode", "destroy");
        if ("disable".equalsIgnoreCase(mode)) {
            victim.setCooldown(Material.SHIELD, 20 * 10);
            return;
        }
        PlayerInventory inv = victim.getInventory();
        if (inv.getItemInOffHand().getType() == Material.SHIELD) {
            inv.setItemInOffHand(new ItemStack(Material.AIR));
        } else if (inv.getItemInMainHand().getType() == Material.SHIELD) {
            inv.setItemInMainHand(new ItemStack(Material.AIR));
        } else {
            return;
        }
        victim.getWorld().playSound(victim.getLocation(), Sound.ITEM_SHIELD_BREAK, 1.0f, 1.0f);
    }

    public void applyHammer(LivingEntity victim) {
        int glow = plugin.getConfig().getInt("hammer.glowing-seconds", 10);
        int lev = plugin.getConfig().getInt("hammer.levitation-seconds", 3);
        victim.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, glow * 20, 0, false, true, true));
        victim.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, lev * 20, 0, false, true, true));
    }

    // ------------------------------------------------------------------ Griffin: water walking, void safety

    private void griffinTick() {
        String s = data.getString("griffin.uuid");
        if (s == null) {
            resetFake();
            return;
        }
        Entity ent;
        try {
            ent = Bukkit.getEntity(UUID.fromString(s));
        } catch (IllegalArgumentException ex) {
            return;
        }
        if (!(ent instanceof AbstractHorse horse) || !horse.isValid()) {
            resetFake();
            return;
        }

        if (horse.getLocation().getY() < horse.getWorld().getMinHeight()) {
            horse.setVelocity(new Vector(0, 0, 0));
            horse.teleport(horse.getWorld().getSpawnLocation().add(0.5, 1, 0.5));
            return;
        }

        Player rider = null;
        for (Entity p : horse.getPassengers()) {
            if (p instanceof Player pl) {
                rider = pl;
                break;
            }
        }
        if (rider == null) {
            resetFake();
            // nobody riding: float up instead of sinking
            if (horse.getLocation().getBlock().getType() == Material.WATER) {
                Vector v = horse.getVelocity();
                horse.setVelocity(new Vector(v.getX(), 0.3, v.getZ()));
            }
            return;
        }
        walkOnWater(rider, horse);
    }

    /** Shows the rider invisible solid blocks on the water surface so the horse can run across it. */
    private void walkOnWater(Player rider, AbstractHorse horse) {
        if (fakeViewer != null && !fakeViewer.equals(rider)) resetFake();
        fakeViewer = rider;

        Location hl = horse.getLocation();
        World w = hl.getWorld();
        int baseY = (int) Math.floor(hl.getY() + 0.05);
        int cx = hl.getBlockX();
        int cz = hl.getBlockZ();

        Set<Location> next = new HashSet<>();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                for (int dy = 1; dy <= 2; dy++) {
                    Block b = w.getBlockAt(x, baseY - dy, z);
                    if (b.getType() == Material.WATER) {
                        boolean surface = dy == 1 || w.getBlockAt(x, baseY - dy + 1, z).getType() != Material.WATER;
                        if (surface) next.add(b.getLocation());
                        break;
                    }
                    if (!b.isPassable()) break;
                }
            }
        }

        boolean refresh = (++griffinTicks % 5) == 0;
        for (Location loc : next) {
            if (refresh || !fakeBlocks.contains(loc)) {
                rider.sendBlockChange(loc, Material.BARRIER.createBlockData());
            }
        }
        for (Location loc : fakeBlocks) {
            if (!next.contains(loc)) {
                rider.sendBlockChange(loc, loc.getBlock().getBlockData());
            }
        }
        fakeBlocks.clear();
        fakeBlocks.addAll(next);
    }

    private void resetFake() {
        if (fakeViewer != null && fakeViewer.isOnline()) {
            for (Location loc : fakeBlocks) {
                fakeViewer.sendBlockChange(loc, loc.getBlock().getBlockData());
            }
        }
        fakeBlocks.clear();
        fakeViewer = null;
    }
}
