package me.danyul.robot;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.stream.Collectors;

public class PlayerControlledRobotHunterPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private final Map<UUID, Ability> pendingAbility = new HashMap<>();

    private static final String ABILITY_GUI_TITLE = ChatColor.DARK_RED + "Robot Abilities";
    // melee hit cooldown so hunter can’t spam
    private static final long ATTACK_COOLDOWN_MS = 600L;

    // who is a hunter
    
    // hunter spawn points
    private final Map<UUID, Location> hunterSpawn = new HashMap<>();
    // last attack times
    private final Map<UUID, Long> lastAttackTime = new HashMap<>();

    // when the run actually starts (set by /robothunter start)
    private long gameStartTime = -1L;

    // runner to track
    private UUID runnerId = null;
    

    // state flags
    private final Set<UUID> cameraMode = new HashSet<>();
    private final Set<UUID> overdriveActive = new HashSet<>();
    private final Set<UUID> shieldActive = new HashSet<>();

    // helper to safely get potion effects by name (your API doesn’t expose constants)
    private PotionEffectType effect(String name) {
        PotionEffectType type = PotionEffectType.getByName(name);
        if (type == null) {
            throw new IllegalStateException("Unknown potion effect: " + name);
        }
        return type;
    }

    // ------------------------------------------------------------------ //
    // ABILITIES
    // ------------------------------------------------------------------ //

    private enum Ability {
        SPEED_OVERDRIVE("speed_overdrive", ChatColor.GREEN + "Speed Overdrive", Material.SUGAR, 300),
        ROCKET_JUMP("rocket_jump", ChatColor.AQUA + "Rocket Jump", Material.FIRE_CHARGE, 360),
        ZOOM_MODE("zoom_mode", ChatColor.YELLOW + "Zoom Mode", Material.SPYGLASS, 420),
        SONAR_SCAN("sonar_scan", ChatColor.BLUE + "Sonar Scan", Material.NAUTILUS_SHELL, 480),
        MINES("mines", ChatColor.GOLD + "Shock Mines", Material.TNT, 600),
        SHIELD("shield", ChatColor.DARK_AQUA + "Shield Mode", Material.SHIELD, 720),
        SECURITY_CAMERA("security_camera", ChatColor.DARK_GREEN + "Security Camera", Material.ENDER_EYE, 900),
        GRAPPLE("grapple", ChatColor.DARK_PURPLE + "Grapple Pull", Material.FISHING_ROD, 1020),
        DRONE_STRIKE("drone_strike", ChatColor.RED + "Drone Strike", Material.FIREWORK_ROCKET, 1200),
        THERMAL_VISION("thermal_vision", ChatColor.LIGHT_PURPLE + "Thermal Vision", Material.MAGMA_CREAM, 1320);

        final String key;
        final String displayName;
        final Material icon;
        final int defaultUnlockSeconds;

        Ability(String key, String displayName, Material icon, int defaultUnlockSeconds) {
            this.key = key;
            this.displayName = displayName;
            this.icon = icon;
            this.defaultUnlockSeconds = defaultUnlockSeconds;
        }
    }

    // unlock times (seconds after /robothunter start)
    private final Map<Ability, Integer> abilityUnlockSeconds = new EnumMap<>(Ability.class);
    // abilities used once per hunter
    private final Map<UUID, EnumSet<Ability>> usedAbilities = new HashMap<>();

    // ---------- mines ----------

    private static class Mine {
        final Location loc;
        final UUID ownerId;
        Mine(Location loc, UUID ownerId) {
            this.loc = loc;
            this.ownerId = ownerId;
        }
    }

    private final List<Mine> mines = new ArrayList<>();

    // ------------------------------------------------------------------ //
    // ENABLE / DISABLE
    // ------------------------------------------------------------------ //

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadAbilityConfig();

        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("robothunter")).setExecutor(this);
        Objects.requireNonNull(getCommand("robothunter")).setTabCompleter(this);

        startCompassTask();
        startMineCheckTask();

        getLogger().info("PlayerControlledRobotHunter enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("PlayerControlledRobotHunter disabled.");
    }

    private void loadAbilityConfig() {
        for (Ability ability : Ability.values()) {
            int unlock = getConfig().getInt(
                    "abilities." + ability.key + ".unlock_seconds",
                    ability.defaultUnlockSeconds
            );
            // never unlock before 5 minutes
            if (unlock < 300) unlock = 300;
            abilityUnlockSeconds.put(ability, unlock);
        }
    }

    // ------------------------------------------------------------------ //
    // COMPASS
    // ------------------------------------------------------------------ //

    private void startCompassTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (runnerId == null) return;
                Player runner = Bukkit.getPlayer(runnerId);
                if (runner == null || !runner.isOnline()) return;

                for (UUID hunterId : hunters) {
                    Player hunter = Bukkit.getPlayer(hunterId);
                    if (hunter == null || !hunter.isOnline()) continue;

                    ItemStack compass = null;
                    for (int i = 0; i < hunter.getInventory().getSize(); i++) {
                        ItemStack item = hunter.getInventory().getItem(i);
                        if (item != null && item.getType() == Material.COMPASS) {
                            compass = item;
                            break;
                        }
                    }
                    if (compass == null) {
                        compass = new ItemStack(Material.COMPASS);
                        hunter.getInventory().addItem(compass);
                    }

                    ItemMeta meta = compass.getItemMeta();
                    if (meta instanceof CompassMeta) {
                        CompassMeta cMeta = (CompassMeta) meta;
                        cMeta.setDisplayName(ChatColor.AQUA + "Runner Tracker");
                        cMeta.setLodestone(runner.getLocation());
                        cMeta.setLodestoneTracked(false);

                        Enchantment glow = Enchantment.getByName("UNBREAKING");
                        if (glow != null) {
                            cMeta.addEnchant(glow, 1, true);
                            cMeta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                        }
                        compass.setItemMeta(cMeta);
                    }
                }
            }
        }.runTaskTimer(this, 20L, 20L);
    }

    // ------------------------------------------------------------------ //
    // MINES CHECK
    // ------------------------------------------------------------------ //

    private void startMineCheckTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (runnerId == null) return;
                Player runner = Bukkit.getPlayer(runnerId);
                if (runner == null || !runner.isOnline()) return;

                Iterator<Mine> it = mines.iterator();
                while (it.hasNext()) {
                    Mine mine = it.next();
                    if (!mine.loc.getWorld().equals(runner.getWorld())) continue;
                    if (mine.loc.distanceSquared(runner.getLocation()) <= 1.5 * 1.5) {
                        runner.getWorld().playSound(mine.loc, Sound.ENTITY_CREEPER_PRIMED, 1f, 1.2f);
                        runner.addPotionEffect(new PotionEffect(effect("SLOWNESS"), 60, 1, false, true, true));
                        runner.damage(2.0);
                        it.remove();
                    }
                }
            }
        }.runTaskTimer(this, 5L, 5L);
    }

    // ------------------------------------------------------------------ //
    // COMMANDS
    // ------------------------------------------------------------------ //

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("robothunter.use")) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /robothunter <sethunter|setrunner|start|clearhunters|abilities>");
            return true;
        }

        // /robothunter sethunter [player]
        if (args[0].equalsIgnoreCase("sethunter")) {
            Player target;
            if (!(sender instanceof Player) && args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Console: /robothunter sethunter <player>");
                return true;
            }
            if (args.length >= 2) target = Bukkit.getPlayer(args[1]);
            else target = (Player) sender;

            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player not found.");
                return true;
            }
            setHunter(target);
            sender.sendMessage(ChatColor.GREEN + "Set " + target.getName() + " as Robot Hunter.");
            return true;
        }

        // /robothunter setrunner <player>
        if (args[0].equalsIgnoreCase("setrunner")) {
            if (args.length < 2) {
                sender.sendMessage(ChatColor.YELLOW + "Usage: /robothunter setrunner <player>");
                return true;
            }
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Runner player not found.");
                return true;
            }
            runnerId = target.getUniqueId();
            sender.sendMessage(ChatColor.AQUA + "Runner set to " + target.getName() + ".");
            return true;
        }

        // /robothunter start  -> start the run, timers begin now
        if (args[0].equalsIgnoreCase("start")) {
            gameStartTime = System.currentTimeMillis();
            usedAbilities.clear(); // reset one-time usage
            sender.sendMessage(ChatColor.GREEN + "Robot Hunter run started!");
            Bukkit.broadcastMessage(ChatColor.RED + "[RobotHunter] Run has started!");
            return true;
        }

        // /robothunter clearhunters
        if (args[0].equalsIgnoreCase("clearhunters")) {
            clearAllHunters();
            sender.sendMessage(ChatColor.YELLOW + "Cleared all robot hunters.");
            return true;
        }

        // /robothunter abilities
        if (args[0].equalsIgnoreCase("abilities")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Only players can use abilities.");
                return true;
            }
            Player p = (Player) sender;
            if (!hunters.contains(p.getUniqueId())) {
                p.sendMessage(ChatColor.RED + "You are not a robot hunter.");
                return true;
            }
            pendingAbility.remove(p.getUniqueId());
            openAbilityGui(p);
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "Unknown subcommand.");
        return true;
    }

    private void setHunter(Player p) {
        UUID id = p.getUniqueId();
        hunters.add(id);

        hunterSpawn.put(id, p.getLocation().clone());

        // base robot effect: always slow
        p.addPotionEffect(new PotionEffect(effect("SLOWNESS"), Integer.MAX_VALUE, 0, false, false, false));
        p.sendTitle(ChatColor.RED + "ROBOT ONLINE", ChatColor.GRAY + "You are the hunter.", 10, 40, 10);
        p.sendMessage(ChatColor.AQUA + "You are now the Robot Hunter!");
    }

    private void clearAllHunters() {
        for (UUID id : new HashSet<>(hunters)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.removePotionEffect(effect("SLOWNESS"));
                p.removePotionEffect(effect("SPEED"));
                p.removePotionEffect(effect("NIGHT_VISION"));
                p.removePotionEffect(effect("DAMAGE_RESISTANCE"));
            }
        }
        hunters.clear();
        lastAttackTime.clear();
        cameraMode.clear();
        overdriveActive.clear();
        shieldActive.clear();
        usedAbilities.clear();
        gameStartTime = -1L;
    }

    // ------------------------------------------------------------------ //
    // ABILITY GUI
    // ------------------------------------------------------------------ //

    private long getElapsedSeconds() {
        if (gameStartTime <= 0) return 0;
        return (System.currentTimeMillis() - gameStartTime) / 1000L;
    }

    private boolean hasUsedAbility(UUID hunterId, Ability ability) {
        EnumSet<Ability> set = usedAbilities.get(hunterId);
        return set != null && set.contains(ability);
    }

    private void markAbilityUsed(UUID hunterId, Ability ability) {
        usedAbilities.computeIfAbsent(hunterId, k -> EnumSet.noneOf(Ability.class)).add(ability);
    }

    private void openAbilityGui(Player p) {
        Inventory inv = Bukkit.createInventory(p, 9, ABILITY_GUI_TITLE);

        long elapsed = getElapsedSeconds();
        UUID hunterId = p.getUniqueId();

        Ability[] abilities = Ability.values();
        for (int i = 0; i < abilities.length && i < inv.getSize(); i++) {
            Ability ability = abilities[i];
            ItemStack item = new ItemStack(ability.icon);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ability.displayName);
                List<String> lore = new ArrayList<>();
                int unlock = abilityUnlockSeconds.getOrDefault(ability, ability.defaultUnlockSeconds);

                if (gameStartTime <= 0) {
                    lore.add(ChatColor.RED + "Run has not started yet.");
                    lore.add(ChatColor.GRAY + "Use /robothunter start");
                } else if (elapsed < unlock) {
                    long remainingUnlock = unlock - elapsed;
                    lore.add(ChatColor.RED + "Locked: " + remainingUnlock + "s until unlock");
                } else if (hasUsedAbility(hunterId, ability)) {
                    lore.add(ChatColor.DARK_GRAY + "Already used (one-time)");
                } else {
                    lore.add(ChatColor.GREEN + "Ready!");
                    lore.add(ChatColor.GRAY + "One-time use only.");
                }

                meta.setLore(lore);
                item.setItemMeta(meta);
            }
            inv.setItem(i, item);
        }

        p.openInventory(inv);
    }

    // click handling
    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();
        if (!hunters.contains(p.getUniqueId())) return;

        if (!e.getView().getTitle().equals(ABILITY_GUI_TITLE)) return;

        e.setCancelled(true);
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null || meta.getDisplayName() == null) return;

        Ability selected = getAbilityByDisplayName(meta.getDisplayName());
if (selected != null) {
    pendingAbility.put(p.getUniqueId(), selected);
    p.sendMessage(ChatColor.GREEN + "Selected: " + selected.displayName + ChatColor.GRAY + " (closes on GUI exit)");

    private Ability getAbilityByDisplayName(String name) {
    for (Ability a : Ability.values()) {
        if (a.displayName.equals(name)) return a;
    }
    return null;
}
}
    }

@EventHandler
public void onInventoryClose(org.bukkit.event.inventory.InventoryCloseEvent e) {
    if (!(e.getPlayer() instanceof Player)) return;

    Player p = (Player) e.getPlayer();
    if (!hunters.contains(p.getUniqueId())) return;

    if (!e.getView().getTitle().equals(ABILITY_GUI_TITLE)) return;

    UUID id = p.getUniqueId();

    // If no ability was selected, do nothing
    if (!pendingAbility.containsKey(id)) return;

    Ability selected = pendingAbility.remove(id);
    long elapsed = getElapsedSeconds();

    int unlock = abilityUnlockSeconds.getOrDefault(selected, selected.defaultUnlockSeconds);

    if (elapsed < unlock) {
        p.sendMessage(ChatColor.RED + "Ability is still locked for " + (unlock - elapsed) + " more seconds.");
        return;
    }

    if (hasUsedAbility(id, selected)) {
        p.sendMessage(ChatColor.RED + "You already used this ability (one-time).");
        return;
    }

    if (triggerAbility(p, selected)) {
        markAbilityUsed(id, selected);
    }
}

    private void handleAbilityClick(Player p, String displayName) {
        UUID hunterId = p.getUniqueId();
        Ability ability = Arrays.stream(Ability.values())
                .filter(a -> a.displayName.equals(displayName))
                .findFirst()
                .orElse(null);
        if (ability == null) return;

        long elapsed = getElapsedSeconds();
        if (gameStartTime <= 0) {
            p.sendMessage(ChatColor.RED + "The run hasn’t started yet. Use /robothunter start.");
            return;
        }

        int unlock = abilityUnlockSeconds.getOrDefault(ability, ability.defaultUnlockSeconds);
        if (elapsed < unlock) {
            long remaining = unlock - elapsed;
            p.sendMessage(ChatColor.RED + "Ability locked for " + remaining + " more seconds.");
            return;
        }

        if (hasUsedAbility(hunterId, ability)) {
            p.sendMessage(ChatColor.RED + "You already used this ability (one-time only).");
            return;
        }

        if (triggerAbility(p, ability)) {
            markAbilityUsed(hunterId, ability);
        }
    }

    // ------------------------------------------------------------------ //
    // ABILITY LOGIC
    // ------------------------------------------------------------------ //

    private boolean triggerAbility(Player hunter, Ability ability) {
        UUID hunterId = hunter.getUniqueId();
        Location loc = hunter.getLocation();

        switch (ability) {
            case SPEED_OVERDRIVE:
                overdriveActive.add(hunterId);
                hunter.removePotionEffect(effect("SLOWNESS"));
                hunter.addPotionEffect(new PotionEffect(effect("SPEED"), 20 * 5, 1, false, true, true));
                hunter.sendTitle(ChatColor.GREEN + "OVERDRIVE", ChatColor.GRAY + "Temporary speed boost!", 5, 40, 10);
                hunter.getWorld().playSound(loc, Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.2f);
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        overdriveActive.remove(hunterId);
                        hunter.removePotionEffect(effect("SPEED"));
                        hunter.addPotionEffect(new PotionEffect(effect("SLOWNESS"), Integer.MAX_VALUE, 0, false, false, false));
                    }
                }.runTaskLater(this, 20L * 5);
                return true;

            case ROCKET_JUMP:
                hunter.setVelocity(hunter.getVelocity().setY(1.0));
                hunter.getWorld().playSound(loc, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f, 1f);
                hunter.sendTitle(ChatColor.AQUA + "ROCKET JUMP", ChatColor.GRAY + "Up you go!", 5, 20, 10);
                return true;

            case ZOOM_MODE:
                hunter.addPotionEffect(new PotionEffect(effect("SLOWNESS"), 20 * 4, 3, false, false, false));
                hunter.addPotionEffect(new PotionEffect(effect("NIGHT_VISION"), 20 * 4, 0, false, false, false));
                hunter.sendTitle(ChatColor.YELLOW + "ZOOM MODE", ChatColor.GRAY + "Line up your shot...", 5, 40, 10);
                hunter.getWorld().playSound(loc, Sound.ITEM_SPYGLASS_USE, 1f, 1f);
                return true;

            case SONAR_SCAN:
                if (runnerId != null) {
                    Player runner = Bukkit.getPlayer(runnerId);
                    if (runner != null && runner.isOnline()) {
                        runner.addPotionEffect(new PotionEffect(effect("GLOWING"), 20 * 5, 0, false, false, true));
                        hunter.sendTitle(ChatColor.BLUE + "SONAR PING", ChatColor.GRAY + "Runner detected!", 5, 40, 10);
                        hunter.getWorld().playSound(loc, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.5f);
                    } else {
                        hunter.sendMessage(ChatColor.RED + "Runner not online.");
                        return false;
                    }
                } else {
                    hunter.sendMessage(ChatColor.RED + "No runner set.");
                    return false;
                }
                return true;

            case MINES:
                for (int i = 0; i < 3; i++) {
                    double dx = (Math.random() - 0.5) * 3.0;
                    double dz = (Math.random() - 0.5) * 3.0;
                    Location mLoc = loc.clone().add(dx, 0, dz);
                    mLoc.setY(loc.getY());
                    mines.add(new Mine(mLoc, hunterId));
                }
                hunter.getWorld().playSound(loc, Sound.BLOCK_PISTON_EXTEND, 1f, 0.8f);
                hunter.sendTitle(ChatColor.GOLD + "MINES DEPLOYED", ChatColor.GRAY + "Careful where they step...", 5, 40, 10);
                return true;

            case SHIELD:
                shieldActive.add(hunterId);
                hunter.addPotionEffect(new PotionEffect(effect("DAMAGE_RESISTANCE"), 20 * 5, 1, false, true, true));
                hunter.sendTitle(ChatColor.DARK_AQUA + "SHIELD ONLINE", ChatColor.GRAY + "Damage reduced.", 5, 40, 10);
                hunter.getWorld().playSound(loc, Sound.ITEM_SHIELD_BLOCK, 1f, 0.8f);
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        shieldActive.remove(hunterId);
                        hunter.removePotionEffect(effect("DAMAGE_RESISTANCE"));
                    }
                }.runTaskLater(this, 20L * 5);
                return true;

            case SECURITY_CAMERA:
                if (cameraMode.contains(hunterId)) {
                    hunter.sendMessage(ChatColor.RED + "Already in camera mode.");
                    return false;
                }
                cameraMode.add(hunterId);
                Location originalLoc = hunter.getLocation().clone();
                Location camLoc = originalLoc.clone().add(0, 15, 0);
                hunter.teleport(camLoc);
                hunter.setAllowFlight(true);
                hunter.setFlying(true);
                hunter.sendTitle(ChatColor.DARK_GREEN + "SECURITY CAMERA", ChatColor.GRAY + "Scanning area...", 10, 40, 10);
                hunter.getWorld().playSound(camLoc, Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.2f);

                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (!hunter.isOnline()) {
                            cameraMode.remove(hunterId);
                            return;
                        }
                        hunter.teleport(originalLoc);
                        hunter.setFlying(false);
                        cameraMode.remove(hunterId);
                        hunter.sendMessage(ChatColor.GRAY + "Exited camera mode.");
                    }
                }.runTaskLater(this, 20L * 5);
                return true;

            case GRAPPLE:
                if (runnerId != null) {
                    Player runner = Bukkit.getPlayer(runnerId);
                    if (runner != null && runner.isOnline() && runner.getWorld().equals(loc.getWorld())) {
                        double dist = runner.getLocation().distance(loc);
                        if (dist <= 30) {
                            Location rLoc = runner.getLocation();
                            org.bukkit.util.Vector v = loc.toVector().subtract(rLoc.toVector()).normalize().multiply(1.2);
                            v.setY(0.4);
                            runner.setVelocity(v);
                            runner.getWorld().playSound(rLoc, Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1f, 1f);
                            hunter.sendTitle(ChatColor.DARK_PURPLE + "GRAPPLE", ChatColor.GRAY + "Get over here!", 5, 40, 10);
                        } else {
                            hunter.sendMessage(ChatColor.RED + "Runner too far for grapple (max 30 blocks).");
                            return false;
                        }
                    } else {
                        hunter.sendMessage(ChatColor.RED + "Runner not online.");
                        return false;
                    }
                } else {
                    hunter.sendMessage(ChatColor.RED + "No runner set.");
                    return false;
                }
                return true;

            case DRONE_STRIKE:
                if (runnerId != null) {
                    Player runner = Bukkit.getPlayer(runnerId);
                    if (runner != null && runner.isOnline()) {
                        Location target = runner.getLocation().clone();
                        target.getWorld().playSound(target, Sound.ENTITY_PHANTOM_SWOOP, 1f, 0.5f);
                        hunter.sendTitle(ChatColor.RED + "DRONE STRIKE", ChatColor.GRAY + "Incoming!", 10, 40, 10);
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                target.getWorld().playSound(target, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f);
                                target.getWorld().createExplosion(target.getX(), target.getY(), target.getZ(), 1.5f, false, false);
                            }
                        }.runTaskLater(this, 20L * 2);
                    } else {
                        hunter.sendMessage(ChatColor.RED + "Runner not online.");
                        return false;
                    }
                } else {
                    hunter.sendMessage(ChatColor.RED + "No runner set.");
                    return false;
                }
                return true;

            case THERMAL_VISION:
                hunter.addPotionEffect(new PotionEffect(effect("NIGHT_VISION"), 20 * 8, 0, false, false, false));
                if (runnerId != null) {
                    Player runner = Bukkit.getPlayer(runnerId);
                    if (runner != null && runner.isOnline()) {
                        runner.addPotionEffect(new PotionEffect(effect("GLOWING"), 20 * 8, 0, false, false, true));
                    }
                }
                hunter.sendTitle(ChatColor.LIGHT_PURPLE + "THERMAL VISION", ChatColor.GRAY + "Targets highlighted.", 10, 40, 10);
                hunter.getWorld().playSound(loc, Sound.BLOCK_SCULK_SENSOR_CLICKING, 1f, 1f);
                return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ //
    // EVENTS
    // ------------------------------------------------------------------ //

    // keep hunter always full food & no hunger
    @EventHandler
    public void onFoodChange(FoodLevelChangeEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        if (!hunters.contains(p.getUniqueId())) return;

        e.setCancelled(true);
        p.setFoodLevel(20);
        p.setSaturation(20f);
    }

    // buff hunter melee a bit + cooldown
    @EventHandler
    public void onHunterAttack(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player)) return;
        Player hunter = (Player) e.getDamager();
        if (!hunters.contains(hunter.getUniqueId())) return;

        if (e.getEntity() instanceof Player && hunters.contains(((Player) e.getEntity()).getUniqueId())) {
            return;
        }

        long now = System.currentTimeMillis();
        long last = lastAttackTime.getOrDefault(hunter.getUniqueId(), 0L);
        if (now - last < ATTACK_COOLDOWN_MS) {
            e.setCancelled(true);
            return;
        }
        lastAttackTime.put(hunter.getUniqueId(), now);

        double damage = e.getDamage();
        ItemStack weapon = hunter.getInventory().getItemInMainHand();
        if (weapon != null) {
            String name = weapon.getType().name();
            if (name.contains("WOODEN_SWORD")) damage += 2.0;
            else if (name.contains("STONE_SWORD")) damage += 2.5;
            else if (name.contains("IRON_SWORD")) damage += 3.0;
            else if (name.contains("DIAMOND_SWORD")) damage += 3.5;
            else damage += 1.0;
        } else {
            damage += 1.0;
        }

        e.setDamage(damage);
        hunter.playSound(hunter.getLocation(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 1f, 1f);
    }

    // reapply robot effects after death
    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (!hunters.contains(id)) return;

        Location spawn = hunterSpawn.get(id);
        if (spawn != null) {
            e.setRespawnLocation(spawn);
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                p.removePotionEffect(effect("SPEED"));
                p.removePotionEffect(effect("NIGHT_VISION"));
                p.removePotionEffect(effect("DAMAGE_RESISTANCE"));
                p.addPotionEffect(new PotionEffect(
                        effect("SLOWNESS"),
                        Integer.MAX_VALUE,
                        0,
                        false,
                        false,
                        false
                ));
            }
        }.runTaskLater(this, 1L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (!hunters.contains(id)) return;

        e.getPlayer().removePotionEffect(effect("SLOWNESS"));
        e.getPlayer().removePotionEffect(effect("SPEED"));
        e.getPlayer().removePotionEffect(effect("NIGHT_VISION"));
        e.getPlayer().removePotionEffect(effect("DAMAGE_RESISTANCE"));

        hunters.remove(id);
        lastAttackTime.remove(id);
        cameraMode.remove(id);
        overdriveActive.remove(id);
        shieldActive.remove(id);
        usedAbilities.remove(id);
    }

    // ------------------------------------------------------------------ //
    // TAB COMPLETE
    // ------------------------------------------------------------------ //

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!sender.hasPermission("robothunter.use")) return Collections.emptyList();

        if (args.length == 1) {
            return Arrays.asList("sethunter", "setrunner", "start", "clearhunters", "abilities").stream()
                    .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("sethunter") || args[0].equalsIgnoreCase("setrunner"))) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }

        return Collections.emptyList();
    }
}
