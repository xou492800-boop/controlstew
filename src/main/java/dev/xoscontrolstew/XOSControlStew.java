package dev.xoscontrolstew;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.EntityEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.meta.SuspiciousStewMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class XOSControlStew extends JavaPlugin implements Listener, CommandExecutor {

    // Only these two players can use anything in this plugin.
    private static final Set<String> ALLOWED = Set.of("xo1u", "dsbv");
    private static final long DURATION_MS = 5 * 60 * 1000L;

    private NamespacedKey stewKey;
    private File pendingDir;

    /** The controller's own state, restored when they let go. */
    private static class Saved {
        ItemStack[] items = new ItemStack[41];
        GameMode gm = GameMode.SURVIVAL;
        double health = 20;
        int food = 20;
        float sat = 5f;
        int level = 0;
        float exp = 0f;
        boolean allowFlight = false;
        boolean flying = false;
    }

    /** player who drank the stew -> expiry time (ms) */
    private final Map<UUID, Long> infected = new HashMap<>();
    private final Map<UUID, UUID> controllerToVictim = new HashMap<>();
    private final Map<UUID, UUID> victimToController = new HashMap<>();
    private final Map<UUID, Saved> savedCtl = new HashMap<>();
    /** victim's original {walkSpeed, flySpeed} */
    private final Map<UUID, float[]> savedSpeeds = new HashMap<>();

    /** true while the plugin itself performs an action *as* the victim */
    private boolean bypass = false;

    @Override
    public void onEnable() {
        stewKey = new NamespacedKey(this, "control_stew");
        pendingDir = new File(getDataFolder(), "pending");
        pendingDir.mkdirs();

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("controlstew").setExecutor(this);

        // expire effects every second
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            long now = System.currentTimeMillis();
            for (UUID id : new ArrayList<>(infected.keySet())) {
                if (infected.get(id) <= now) {
                    release(id);
                    infected.remove(id);
                }
            }
        }, 20L, 20L);

        // body, stats and inventory mirroring, once per tick
        Bukkit.getScheduler().runTaskTimer(this, this::syncAll, 1L, 1L);
    }

    @Override
    public void onDisable() {
        for (UUID id : new ArrayList<>(controllerToVictim.keySet())) release(id);
    }

    private boolean isAllowed(Player p) {
        return ALLOWED.contains(p.getName().toLowerCase());
    }

    private boolean isStew(ItemStack item) {
        if (item == null || item.getType() != Material.SUSPICIOUS_STEW || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(stewKey, PersistentDataType.BYTE);
    }

    private boolean isControlled(Player p) {
        return victimToController.containsKey(p.getUniqueId());
    }

    private boolean isActive(UUID id) {
        Long exp = infected.get(id);
        return exp != null && exp > System.currentTimeMillis();
    }

    private Player victimOf(Player controller) {
        UUID v = controllerToVictim.get(controller.getUniqueId());
        return v == null ? null : Bukkit.getPlayer(v);
    }

    private Player controllerOf(Player victim) {
        UUID c = victimToController.get(victim.getUniqueId());
        return c == null ? null : Bukkit.getPlayer(c);
    }

    // ------------------------------------------------------------ command

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p) || !isAllowed(p)) {
            sender.sendMessage(Component.text("Unknown command. Type \"/help\" for help.", NamedTextColor.WHITE));
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("release")) {
            if (controllerToVictim.containsKey(p.getUniqueId())) {
                release(p.getUniqueId());
                p.sendActionBar(Component.text("Released.", NamedTextColor.GRAY));
            }
            return true;
        }

        // no stews while you're wearing someone else's inventory
        if (controllerToVictim.containsKey(p.getUniqueId())) {
            p.sendActionBar(Component.text("/controlstew release", NamedTextColor.GRAY));
            return true;
        }

        ItemStack stew = new ItemStack(Material.SUSPICIOUS_STEW);
        SuspiciousStewMeta meta = (SuspiciousStewMeta) stew.getItemMeta();
        meta.addCustomEffect(new PotionEffect(PotionEffectType.SATURATION, 1, 0), true);
        meta.getPersistentDataContainer().set(stewKey, PersistentDataType.BYTE, (byte) 1);
        stew.setItemMeta(meta);
        p.getInventory().addItem(stew);
        return true;
    }

    // ------------------------------------------------------------ drinking

    @EventHandler
    public void onDrink(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        if (isControlled(p)) { e.setCancelled(true); return; }
        if (!isStew(e.getItem())) return;

        infected.put(p.getUniqueId(), System.currentTimeMillis() + DURATION_MS);
        Bukkit.getScheduler().runTaskLater(this, () ->
                p.sendActionBar(Component.text("you feel it enter your bones.", NamedTextColor.BLACK)), 1L);
    }

    // ------------------------------------------------------------ GUI

    private static class Gui implements InventoryHolder {
        final Map<Integer, UUID> slots = new HashMap<>();
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    @EventHandler
    public void onUseStew(PlayerInteractEvent e) {
        Player p = e.getPlayer();

        if (isControlled(p) && !bypass) { e.setCancelled(true); return; }

        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!p.isSneaking() || !isAllowed(p) || !isStew(p.getInventory().getItemInMainHand())) return;

        e.setCancelled(true);
        openGui(p);
    }

    private void openGui(Player controller) {
        Gui holder = new Gui();
        Inventory inv = Bukkit.createInventory(holder, 54,
                Component.text("Stewed", NamedTextColor.DARK_GRAY));
        holder.inv = inv;

        int slot = 0;
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> en : infected.entrySet()) {
            if (slot >= 54) break;
            Player t = Bukkit.getPlayer(en.getKey());
            if (t == null || en.getValue() <= now) continue;

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(t);
            meta.displayName(Component.text(t.getName(), NamedTextColor.WHITE)
                    .decoration(TextDecoration.ITALIC, false));
            long secs = (en.getValue() - now) / 1000;
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(String.format("%d:%02d left", secs / 60, secs % 60), NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            if (victimToController.containsKey(t.getUniqueId()))
                lore.add(Component.text("Already controlled", NamedTextColor.RED)
                        .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            head.setItemMeta(meta);

            inv.setItem(slot, head);
            holder.slots.put(slot, t.getUniqueId());
            slot++;
        }
        controller.openInventory(inv);
    }

    @EventHandler
    public void onInvClick(InventoryClickEvent e) {
        // victims can't touch inventories
        if (e.getWhoClicked() instanceof Player who && isControlled(who) && !bypass) {
            e.setCancelled(true);
            return;
        }
        if (!(e.getView().getTopInventory().getHolder() instanceof Gui gui)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player controller) || !isAllowed(controller)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;

        UUID target = gui.slots.get(e.getSlot());
        if (target == null) return;
        Player victim = Bukkit.getPlayer(target);
        if (victim == null || !isActive(target)) return;
        if (victimToController.containsKey(target) || victim.equals(controller)) return;

        startControl(controller, victim);
    }

    // ------------------------------------------------------------ control core

    private static ItemStack[] cloneAll(ItemStack[] in) {
        ItemStack[] out = new ItemStack[in.length];
        for (int i = 0; i < in.length; i++) out[i] = in[i] == null ? null : in[i].clone();
        return out;
    }

    @SuppressWarnings("deprecation")
    private static void setHealthSafe(Player p, double h) {
        p.setHealth(Math.max(0.5, Math.min(h, p.getMaxHealth())));
    }

    private Saved capture(Player c) {
        Saved s = new Saved();
        s.items = cloneAll(c.getInventory().getContents());
        s.gm = c.getGameMode();
        s.health = c.getHealth();
        s.food = c.getFoodLevel();
        s.sat = c.getSaturation();
        s.level = c.getLevel();
        s.exp = c.getExp();
        s.allowFlight = c.getAllowFlight();
        s.flying = c.isFlying();
        return s;
    }

    private void applySaved(Player c, Saved s, boolean stats) {
        c.getInventory().setContents(cloneAll(s.items));
        if (!stats) return;
        c.setGameMode(s.gm);
        c.setAllowFlight(s.allowFlight);
        if (s.flying && s.allowFlight) c.setFlying(true);
        setHealthSafe(c, s.health);
        c.setFoodLevel(s.food);
        c.setSaturation(s.sat);
        c.setLevel(s.level);
        c.setExp(s.exp);
    }

    private void startControl(Player c, Player v) {
        release(c.getUniqueId()); // drop any previous target
        c.closeInventory();       // returns cursor / crafting items before we snapshot

        // remember who we really are (also on disk, in case of a crash)
        Saved mine = capture(c);
        savedCtl.put(c.getUniqueId(), mine);
        writePending(c.getUniqueId(), mine);

        // become them
        c.teleport(v.getLocation());
        c.getInventory().setContents(cloneAll(v.getInventory().getContents()));
        c.getInventory().setHeldItemSlot(v.getInventory().getHeldItemSlot());
        c.setGameMode(v.getGameMode());
        setHealthSafe(c, v.getHealth());
        c.setFoodLevel(v.getFoodLevel());
        c.setSaturation(v.getSaturation());
        c.setLevel(v.getLevel());
        c.setExp(v.getExp());
        if (v.getAllowFlight()) {
            c.setAllowFlight(true);
            if (v.isFlying()) c.setFlying(true);
        }

        // stop the victim's own client from fighting the mirrored movement
        savedSpeeds.put(v.getUniqueId(), new float[]{v.getWalkSpeed(), v.getFlySpeed()});
        v.setWalkSpeed(0f);
        v.setFlySpeed(0f);
        v.addPotionEffect(new PotionEffect(PotionEffectType.JUMP, PotionEffect.INFINITE_DURATION,
                128, false, false, false));

        controllerToVictim.put(c.getUniqueId(), v.getUniqueId());
        victimToController.put(v.getUniqueId(), c.getUniqueId());
        c.sendActionBar(Component.text("Controlling " + v.getName()
                + "  (/controlstew release)", NamedTextColor.GRAY));
    }

    private void release(UUID id) {
        release(id, true);
    }

    private void release(UUID id, boolean restoreControllerStats) {
        UUID controllerId, victimId;
        if (controllerToVictim.containsKey(id)) {
            controllerId = id;
            victimId = controllerToVictim.get(id);
        } else if (victimToController.containsKey(id)) {
            victimId = id;
            controllerId = victimToController.get(id);
        } else return;

        controllerToVictim.remove(controllerId);
        victimToController.remove(victimId);

        Player c = Bukkit.getPlayer(controllerId);
        Player v = Bukkit.getPlayer(victimId);

        if (c != null) c.closeInventory(); // puts cursor/crafting items back BEFORE the final sync

        // FINAL SYNC: the victim keeps exactly what you left them with (anti-dupe)
        if (c != null && v != null) {
            v.getInventory().setContents(cloneAll(c.getInventory().getContents()));
            v.getInventory().setHeldItemSlot(c.getInventory().getHeldItemSlot());
            if (c.getHealth() > 0) setHealthSafe(v, c.getHealth());
            v.setFoodLevel(c.getFoodLevel());
            v.setSaturation(c.getSaturation());
            v.setLevel(c.getLevel());
            v.setExp(c.getExp());
        }

        // give the controller their own stuff back
        Saved saved = savedCtl.remove(controllerId);
        if (c != null && saved != null) applySaved(c, saved, restoreControllerStats);
        deletePending(controllerId);

        // un-freeze the victim
        float[] speeds = savedSpeeds.remove(victimId);
        if (v != null) {
            v.setWalkSpeed(speeds == null ? 0.2f : speeds[0]);
            v.setFlySpeed(speeds == null ? 0.1f : speeds[1]);
            v.removePotionEffect(PotionEffectType.JUMP);
        }
    }

    @SuppressWarnings("deprecation")
    private void syncAll() {
        for (Map.Entry<UUID, UUID> en : new ArrayList<>(controllerToVictim.entrySet())) {
            Player c = Bukkit.getPlayer(en.getKey());
            Player v = Bukkit.getPlayer(en.getValue());
            if (c == null || v == null) { release(en.getKey()); continue; }
            if (c.isDead() || v.isDead()) continue;

            // body: only teleport when something actually changed
            Location to = c.getLocation();
            if (!sameSpot(to, v.getLocation()))
                v.teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN);
            if (v.isSneaking() != c.isSneaking()) v.setSneaking(c.isSneaking());
            if (v.isSprinting() != c.isSprinting()) v.setSprinting(c.isSprinting());
            if (v.isSwimming() != c.isSwimming()) v.setSwimming(c.isSwimming());
            if (v.isGliding() != c.isGliding()) v.setGliding(c.isGliding());
            if (c.isFlying() != v.isFlying() && (!c.isFlying() || v.getAllowFlight()))
                v.setFlying(c.isFlying());
            v.setFallDistance(c.getFallDistance());

            // stats
            double hp = Math.min(c.getHealth(), v.getMaxHealth());
            if (hp > 0 && Math.abs(v.getHealth() - hp) > 0.001) v.setHealth(hp);
            if (v.getFoodLevel() != c.getFoodLevel()) v.setFoodLevel(c.getFoodLevel());
            if (v.getSaturation() != c.getSaturation()) v.setSaturation(c.getSaturation());
            if (v.getLevel() != c.getLevel()) v.setLevel(c.getLevel());
            if (Math.abs(v.getExp() - c.getExp()) > 0.0001f) v.setExp(c.getExp());

            // inventory: only write when it differs
            ItemStack[] cc = c.getInventory().getContents();
            if (!Arrays.equals(cc, v.getInventory().getContents()))
                v.getInventory().setContents(cloneAll(cc));
            int slot = c.getInventory().getHeldItemSlot();
            if (v.getInventory().getHeldItemSlot() != slot) v.getInventory().setHeldItemSlot(slot);
        }
    }

    private static boolean sameSpot(Location a, Location b) {
        return a.getWorld() == b.getWorld()
                && a.distanceSquared(b) < 1.0E-4
                && Math.abs(a.getYaw() - b.getYaw()) < 0.01f
                && Math.abs(a.getPitch() - b.getPitch()) < 0.01f;
    }

    /** After something ran as the victim, pull the result back to the controller. */
    private void pullBack(Player c, Player v) {
        c.getInventory().setContents(cloneAll(v.getInventory().getContents()));
        c.getInventory().setHeldItemSlot(v.getInventory().getHeldItemSlot());
        c.setFoodLevel(v.getFoodLevel());
        c.setSaturation(v.getSaturation());
        c.setLevel(v.getLevel());
        c.setExp(v.getExp());
        if (v.getHealth() > 0) setHealthSafe(c, v.getHealth());
        if (c.getGameMode() != v.getGameMode()) c.setGameMode(v.getGameMode());
        if (c.getWorld() != v.getWorld() || c.getLocation().distanceSquared(v.getLocation()) > 0.01)
            c.teleport(v.getLocation());
    }

    // ------------------------------------------------------------ crash safety

    private File pendingFile(UUID id) {
        return new File(pendingDir, id + ".yml");
    }

    private void writePending(UUID id, Saved s) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("size", s.items.length);
        for (int i = 0; i < s.items.length; i++) if (s.items[i] != null) y.set("i" + i, s.items[i]);
        y.set("gm", s.gm.name());
        y.set("health", s.health);
        y.set("food", s.food);
        y.set("sat", (double) s.sat);
        y.set("level", s.level);
        y.set("exp", (double) s.exp);
        y.set("allowFlight", s.allowFlight);
        y.set("flying", s.flying);
        try { y.save(pendingFile(id)); } catch (IOException ex) { getLogger().warning("Could not save " + id); }
    }

    private void deletePending(UUID id) {
        File f = pendingFile(id);
        if (f.exists()) f.delete();
    }

    /** If the server died mid-control, give the controller their real state back on join. */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        File f = pendingFile(p.getUniqueId());
        if (!f.exists() || controllerToVictim.containsKey(p.getUniqueId())) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        Saved s = new Saved();
        s.items = new ItemStack[y.getInt("size", 41)];
        for (int i = 0; i < s.items.length; i++) s.items[i] = y.getItemStack("i" + i);
        try { s.gm = GameMode.valueOf(y.getString("gm", "SURVIVAL")); } catch (IllegalArgumentException ignored) { }
        s.health = y.getDouble("health", 20);
        s.food = y.getInt("food", 20);
        s.sat = (float) y.getDouble("sat", 5);
        s.level = y.getInt("level", 0);
        s.exp = (float) y.getDouble("exp", 0);
        s.allowFlight = y.getBoolean("allowFlight", false);
        s.flying = y.getBoolean("flying", false);
        applySaved(p, s, true);
        f.delete();
    }

    // ------------------------------------------------------------ controller actions

    /**
     * Same-tick mirroring: the victim's body is moved the instant yours is, so other players
     * see normal walking (the client derives the leg animation from the position changes).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onControllerMove(PlayerMoveEvent e) {
        Player v = victimOf(e.getPlayer());
        Location to = e.getTo();
        if (v == null || to == null) return;
        if (!sameSpot(to, v.getLocation()))
            v.teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    @EventHandler
    public void onControllerSneak(PlayerToggleSneakEvent e) {
        Player v = victimOf(e.getPlayer());
        if (v != null) v.setSneaking(e.isSneaking());
    }

    @EventHandler
    public void onControllerSprint(PlayerToggleSprintEvent e) {
        Player v = victimOf(e.getPlayer());
        if (v != null) v.setSprinting(e.isSprinting());
    }

    @EventHandler
    public void onControllerHotbar(PlayerItemHeldEvent e) {
        Player v = victimOf(e.getPlayer());
        if (v != null) v.getInventory().setHeldItemSlot(e.getNewSlot());
    }

    @EventHandler
    public void onControllerSwing(PlayerAnimationEvent e) {
        Player v = victimOf(e.getPlayer());
        if (v != null) v.swingMainHand();
    }

    /** Your chat is their chat. */
    @EventHandler
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();

        if (isControlled(p)) {
            if (bypass) return;
            e.setCancelled(true);
            p.sendMessage(Component.text("The stew controls you..", NamedTextColor.DARK_RED));
            return;
        }

        Player v = victimOf(p);
        if (v == null) return;
        e.setCancelled(true);
        String msg = PlainTextComponentSerializer.plainText().serialize(e.message());
        Bukkit.getScheduler().runTask(this, () -> {
            bypass = true;
            try { v.chat(msg); } finally { bypass = false; }
        });
    }

    /** Your commands are their commands. */
    @EventHandler
    public void onCommandRun(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();

        if (isControlled(p)) {
            if (bypass) return;
            e.setCancelled(true);
            p.sendMessage(Component.text("The stew controls you..", NamedTextColor.DARK_RED));
            return;
        }

        Player v = victimOf(p);
        if (v == null) return;
        String msg = e.getMessage();
        String lower = msg.toLowerCase();
        if (lower.equals("/controlstew") || lower.startsWith("/controlstew ")) return; // allow /controlstew release

        e.setCancelled(true);
        bypass = true;
        try { v.performCommand(msg.substring(1)); } finally { bypass = false; }
        if (controllerToVictim.containsKey(p.getUniqueId())) pullBack(p, v);
    }

    /** PvP: your hits are their hits. */
    @EventHandler
    public void onDamageDealt(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player damager)) return;

        if (isControlled(damager) && !bypass) { e.setCancelled(true); return; }

        Player v = victimOf(damager);
        if (v == null) return;
        e.setCancelled(true);
        Entity target = e.getEntity();
        if (target.equals(v)) return;
        bypass = true;
        try { v.attack(target); } finally { bypass = false; }
    }

    /** Hits landed on the victim's body are taken by you (your armor, your health, your knockback). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onVictimDamaged(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player v) || !isControlled(v)) return;
        e.setCancelled(true);
        Player c = controllerOf(v);
        if (c == null) return;
        // environmental damage already hits your body in the same spot; only redirect entity hits
        if (e instanceof EntityDamageByEntityEvent ede && !ede.getDamager().equals(c)) {
            c.damage(ede.getDamage(), ede.getDamager());
        }
    }

    /** If you would die as them, THEY die and you walk away unharmed. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onControllerDamaged(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player c)) return;
        Player v = victimOf(c);
        if (v == null) return;

        boolean totem = c.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                || c.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING;
        if (e.getFinalDamage() >= c.getHealth() + c.getAbsorptionAmount() && !totem) {
            e.setCancelled(true);
            release(c.getUniqueId());   // victim receives your final inventory, you get yours back
            v.setHealth(0);             // ...and they die with it
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @SuppressWarnings("deprecation")
    public void onControllerHurt(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player c)) return;
        Player v = victimOf(c);
        if (v != null) v.playEffect(EntityEffect.HURT); // others see them flinch
    }

    /** Breaking: done by the victim. */
    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (isControlled(p) && !bypass) { e.setCancelled(true); return; }

        Player v = victimOf(p);
        if (v == null) return;
        e.setCancelled(true);
        Block b = e.getBlock();
        bypass = true;
        try { v.breakBlock(b); } finally { bypass = false; }
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (isControlled(e.getPlayer()) && !bypass) e.setCancelled(true);
    }

    @EventHandler
    public void onControllerDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (controllerToVictim.containsKey(p.getUniqueId())) {
            // fallback (shouldn't normally happen): you keep your own stuff, nothing drops
            e.getDrops().clear();
            e.setKeepInventory(true);
            release(p.getUniqueId(), false);
        } else if (isControlled(p)) {
            // the victim died (e.g. a command run as them): let go cleanly
            release(p.getUniqueId());
        }
    }

    // ------------------------------------------------------------ locking the victim

    @EventHandler
    public void onVictimPickup(EntityPickupItemEvent e) {
        // the controller's body picks things up instead
        if (e.getEntity() instanceof Player p && isControlled(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onVictimDrop(PlayerDropItemEvent e) {
        if (isControlled(e.getPlayer()) && !bypass) e.setCancelled(true);
    }

    @EventHandler
    public void onVictimSwap(PlayerSwapHandItemsEvent e) {
        if (isControlled(e.getPlayer()) && !bypass) e.setCancelled(true);
    }

    @EventHandler
    public void onVictimHeld(PlayerItemHeldEvent e) {
        if (isControlled(e.getPlayer()) && !bypass) e.setCancelled(true);
    }

    @EventHandler
    public void onVictimInteractEntity(PlayerInteractEntityEvent e) {
        if (isControlled(e.getPlayer()) && !bypass) e.setCancelled(true);
    }

    @EventHandler
    public void onVictimOpen(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player p && isControlled(p) && !bypass) e.setCancelled(true);
    }

    // ------------------------------------------------------------ cleanup

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        release(e.getPlayer().getUniqueId()); // does the final sync before their data is saved
    }
}
