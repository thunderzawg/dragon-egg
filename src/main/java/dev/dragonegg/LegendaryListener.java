package dev.dragonegg;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Allay;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

public class LegendaryListener implements Listener {

    private final LegendaryManager m;

    public LegendaryListener(LegendaryManager m) {
        this.m = m;
    }

    private boolean prot(ItemStack s) {
        return m.isLegendaryItem(s);
    }

    private static boolean isBundle(ItemStack s) {
        return s != null && s.getType().name().endsWith("BUNDLE");
    }

    private static void warn(Player p) {
        p.sendActionBar(Component.text("Legendary items can't be stored or placed - only dropped.", NamedTextColor.RED));
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

        if (clickedTop && prot(cursor)) block = true;
        if (click.isShiftClick() && !clickedTop && realContainer && prot(current)) block = true;
        if (clickedTop && click == ClickType.NUMBER_KEY) {
            int btn = e.getHotbarButton();
            if (btn >= 0 && prot(p.getInventory().getItem(btn))) block = true;
        }
        if (clickedTop && click == ClickType.SWAP_OFFHAND && prot(p.getInventory().getItemInOffHand())) block = true;
        if ((prot(cursor) && isBundle(current)) || (isBundle(cursor) && prot(current))) block = true;

        if (block) {
            e.setCancelled(true);
            warn(p);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!prot(e.getOldCursor())) return;
        int topSize = e.getView().getTopInventory().getSize();
        for (int raw : e.getRawSlots()) {
            if (raw < topSize) {
                e.setCancelled(true);
                if (e.getWhoClicked() instanceof Player p) warn(p);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent e) {
        if (prot(e.getItem().getItemStack())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent e) {
        Entity t = e.getRightClicked();
        if (!(t instanceof ItemFrame || t instanceof ArmorStand || t instanceof Allay)) return;
        ItemStack held = e.getPlayer().getInventory().getItem(e.getHand());
        if (prot(held)) {
            e.setCancelled(true);
            warn(e.getPlayer());
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        ItemStack item = e.getItem();
        if (item == null) return;
        Action a = e.getAction();
        boolean right = a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK;
        if (!right) return;

        Legendary t = m.typeOf(item);

        // decorated pots and shelves
        if (t != null && a == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null) {
            Material bm = e.getClickedBlock().getType();
            if (bm == Material.DECORATED_POT || bm.name().endsWith("_SHELF")) {
                e.setUseInteractedBlock(Event.Result.DENY);
                warn(e.getPlayer());
            }
        }

        // the Fork is a melee weapon: no throwing / riptide. Normal tridents are banned.
        if (t == Legendary.FORK || (t == null && item.getType() == Material.TRIDENT && m.banTridents())) {
            e.setUseItemInHand(Event.Result.DENY);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        ItemStack s = e.getItem().getItemStack();
        if (m.isLegendaryItem(s)) {
            if (m.isStale(s)) {
                e.setCancelled(true);
                e.getItem().remove();
                return;
            }
            // mobs (zombies, allays, foxes...) may never take a legendary item
            if (!(e.getEntity() instanceof Player)) e.setCancelled(true);
        } else if (m.isBannedNormal(s)) {
            e.setCancelled(true);
            e.getItem().remove();
        }
    }

    // ------------------------------------------------------------------ indestructible, only one copy

    @EventHandler
    public void onItemSpawn(ItemSpawnEvent e) {
        Item i = e.getEntity();
        ItemStack s = i.getItemStack();
        if (m.isLegendaryItem(s)) {
            if (m.isStale(s)) {
                e.setCancelled(true);
                return;
            }
            m.protectItem(i);
        } else if (m.isBannedNormal(s)) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) {
            if (en instanceof Item i) {
                ItemStack s = i.getItemStack();
                if (m.isLegendaryItem(s)) {
                    if (m.isStale(s)) i.remove();
                    else m.protectItem(i);
                } else if (m.isBannedNormal(s)) {
                    i.remove();
                }
            } else if (m.isGriffin(en)) {
                if (m.isStaleGriffin(en)) en.remove();
                else m.setGriffinUuid(en.getUniqueId());
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        m.sweepPlayer(e.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDeath(EntityDeathEvent e) {
        // e.g. drowned dropping a normal trident
        e.getDrops().removeIf(m::isBannedNormal);
    }

    // ------------------------------------------------------------------ Griffin

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGriffinDamage(EntityDamageEvent e) {
        if (m.isGriffin(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (e.getInventory().getHolder() instanceof AbstractHorse h && m.isGriffin(h)) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ weapon powers

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player att)) return;
        ItemStack held = att.getInventory().getItemInMainHand();
        Legendary t = m.typeOf(held);
        if (t == Legendary.FORK) {
            if (e.getEntity() instanceof Player vic && vic.isBlocking()) {
                m.breakShield(vic);
            }
        } else if (t == Legendary.HAMMER) {
            if (e.getEntity() instanceof LivingEntity victim && e.getFinalDamage() > 0) {
                m.applyHammer(victim);
            }
        }
    }

    // ------------------------------------------------------------------ bans

    private boolean bannedCraft(Material t) {
        return (m.banNetherite() && t.name().startsWith("NETHERITE"))
                || (m.banMaces() && t == Material.MACE)
                || (m.banTridents() && t == Material.TRIDENT);
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        CraftingInventory inv = e.getInventory();
        ItemStack r = inv.getResult();
        if (r != null && bannedCraft(r.getType())) {
            inv.setResult(null);
        }
    }

    @EventHandler
    public void onCrafter(CrafterCraftEvent e) {
        ItemStack r = e.getResult();
        if (r != null && bannedCraft(r.getType())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onPrepareSmithing(PrepareSmithingEvent e) {
        if (!m.banNetherite()) return;
        ItemStack tpl = e.getInventory().getInputTemplate();
        if (tpl != null && tpl.getType() == Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE) {
            e.setResult(null);
        }
    }

    // ------------------------------------------------------------------ Knockback II ban

    private static boolean hasKnockback2(ItemStack s) {
        if (s == null || s.getType().isAir()) return false;
        if (s.getEnchantmentLevel(Enchantment.KNOCKBACK) >= 2) return true;
        if (s.getItemMeta() instanceof EnchantmentStorageMeta esm) {
            return esm.getStoredEnchantLevel(Enchantment.KNOCKBACK) >= 2;
        }
        return false;
    }

    // enchanting table: Knockback II is capped down to Knockback I
    @EventHandler
    public void onEnchant(EnchantItemEvent e) {
        if (!m.banKnockback2()) return;
        Integer lvl = e.getEnchantsToAdd().get(Enchantment.KNOCKBACK);
        if (lvl != null && lvl >= 2) {
            e.getEnchantsToAdd().put(Enchantment.KNOCKBACK, 1);
        }
    }

    // anvil: no result if it would give Knockback II (books and combining two Knockback I included)
    @EventHandler
    public void onAnvil(PrepareAnvilEvent e) {
        if (!m.banKnockback2()) return;
        if (hasKnockback2(e.getResult())) {
            e.setResult(null);
        }
    }
}
