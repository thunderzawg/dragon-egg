package dev.dragonegg;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Allay;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

public class DragonEggPlugin extends JavaPlugin implements Listener {

    /** Amplifier 1 = level II */
    private static final int AMP = 1;

    private static final PotionEffectType[] TYPES = {
            PotionEffectType.STRENGTH,
            PotionEffectType.SPEED,
            PotionEffectType.FIRE_RESISTANCE,
            PotionEffectType.WEAVING
    };

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);

        // give/remove powers twice a second
        getServer().getScheduler().runTaskTimer(this, this::tickPlayers, 20L, 10L);
        // stop dropped eggs from being lost in the void
        getServer().getScheduler().runTaskTimer(this, this::voidGuard, 40L, 10L);

        // protect eggs that are already lying around
        for (World w : Bukkit.getWorlds()) {
            for (Item i : w.getEntitiesByClass(Item.class)) {
                protect(i);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static boolean isEgg(ItemStack s) {
        return s != null && s.getType() == Material.DRAGON_EGG;
    }

    private static boolean isBundle(ItemStack s) {
        return s != null && s.getType().name().endsWith("BUNDLE");
    }

    private static boolean isOurs(PotionEffect e) {
        return e.getDuration() == PotionEffect.INFINITE_DURATION && e.getAmplifier() == AMP;
    }

    private static void warn(Player p) {
        p.sendActionBar(Component.text("The Dragon Egg can't be stored or placed - only dropped.", NamedTextColor.RED));
    }

    private static void protect(Item i) {
        if (isEgg(i.getItemStack())) {
            i.setInvulnerable(true);       // fire, lava, cactus, explosions...
            i.setUnlimitedLifetime(true);  // never despawns
        }
    }

    // ------------------------------------------------------------------ powers

    private void tickPlayers() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getInventory().contains(Material.DRAGON_EGG)) {
                for (PotionEffectType t : TYPES) {
                    PotionEffect cur = p.getPotionEffect(t);
                    if (cur == null || !isOurs(cur)) {
                        p.addPotionEffect(new PotionEffect(t, PotionEffect.INFINITE_DURATION, AMP, false, false, true));
                    }
                }
            } else {
                for (PotionEffectType t : TYPES) {
                    PotionEffect cur = p.getPotionEffect(t);
                    if (cur != null && isOurs(cur)) {
                        p.removePotionEffect(t);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ no storing

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        Inventory top = e.getView().getTopInventory();
        Inventory clicked = e.getClickedInventory();
        boolean clickedTop = clicked != null && clicked.equals(top);
        boolean realContainer = top.getType() != InventoryType.CRAFTING;

        ItemStack cursor = e.getCursor();
        ItemStack current = e.getCurrentItem();
        ClickType click = e.getClick();

        boolean block = false;

        // egg on cursor placed into the top inventory (chest, ender chest, shulker, crafting grid...)
        if (clickedTop && isEgg(cursor)) block = true;

        // shift-click egg from own inventory into an open container
        if (click.isShiftClick() && !clickedTop && realContainer && isEgg(current)) block = true;

        // number key swap with a slot of the top inventory
        if (clickedTop && click == ClickType.NUMBER_KEY) {
            int btn = e.getHotbarButton();
            if (btn >= 0 && isEgg(p.getInventory().getItem(btn))) block = true;
        }

        // offhand (F) swap with a slot of the top inventory
        if (clickedTop && click == ClickType.SWAP_OFFHAND && isEgg(p.getInventory().getItemInOffHand())) block = true;

        // bundles
        if ((isEgg(cursor) && isBundle(current)) || (isBundle(cursor) && isEgg(current))) block = true;

        if (block) {
            e.setCancelled(true);
            warn(p);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!isEgg(e.getOldCursor())) return;
        int topSize = e.getView().getTopInventory().getSize();
        for (int raw : e.getRawSlots()) {
            if (raw < topSize) {
                e.setCancelled(true);
                if (e.getWhoClicked() instanceof Player p) warn(p);
                return;
            }
        }
    }

    // hoppers / hopper minecarts sucking up a dropped egg
    @EventHandler(ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent e) {
        if (isEgg(e.getItem().getItemStack())) e.setCancelled(true);
    }

    // item frames, armor stands, allays
    @EventHandler(ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent e) {
        Entity t = e.getRightClicked();
        if (!(t instanceof ItemFrame || t instanceof ArmorStand || t instanceof Allay)) return;
        ItemStack held = e.getPlayer().getInventory().getItem(e.getHand());
        if (isEgg(held)) {
            e.setCancelled(true);
            warn(e.getPlayer());
        }
    }

    // decorated pots and shelves
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        if (!isEgg(e.getItem())) return;
        Material m = e.getClickedBlock().getType();
        if (m == Material.DECORATED_POT || m.name().endsWith("_SHELF")) {
            e.setUseInteractedBlock(Event.Result.DENY);
            warn(e.getPlayer());
        }
    }

    // can't place it as a block either
    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (isEgg(e.getItemInHand())) {
            e.setCancelled(true);
            warn(e.getPlayer());
        }
    }

    // ------------------------------------------------------------------ indestructible

    @EventHandler
    public void onItemSpawn(ItemSpawnEvent e) {
        protect(e.getEntity());
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) {
            if (en instanceof Item i) protect(i);
        }
    }

    // egg *block* (e.g. the one in the End) survives explosions too
    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> b.getType() == Material.DRAGON_EGG);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> b.getType() == Material.DRAGON_EGG);
    }

    private void voidGuard() {
        for (World w : Bukkit.getWorlds()) {
            for (Item i : w.getEntitiesByClass(Item.class)) {
                if (isEgg(i.getItemStack()) && i.getLocation().getY() < w.getMinHeight()) {
                    i.setVelocity(new Vector(0, 0, 0));
                    i.teleport(w.getSpawnLocation().add(0.5, 1, 0.5));
                }
            }
        }
    }
}
