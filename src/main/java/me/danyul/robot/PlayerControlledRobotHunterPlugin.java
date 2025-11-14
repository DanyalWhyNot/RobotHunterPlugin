package me.danyul.robot;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Minecraft Speedrunner VS Player-Controlled Robot Hunter
 *
 * Hunter controls an armor-stand “robot” while their real body is invisible.
 * - WASD + mouse controls still work normally.
 * - Everyone sees the armor stand moving and fighting.
 * - Hunter has custom HP (10 hearts = 20 HP) with a boss bar.
 * - If robot dies, it respawns at original spawn.
 * - Hunger disabled for hunter.
 * - Gear is unlocked over time via /robothunter shop.
 */
public class PlayerControlledRobotHunterPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final String SHOP_TITLE = ChatColor.DARK_AQUA + "Robot Hunter Shop";

    // Active hunters
    private final Set<UUID> hunters = new HashSet<>();
    // Hunter -> robot armor stand UUID
    private final Map<UUID, UUID> hunterRobot = new HashMap<>();
    // Robot entity -> hunter UUID
    private final Map<UUID, UUID> robotOwner = new HashMap<>();
    // Hunter original spawn location
    private final Map<UUID, Location> hunterSpawn = new HashMap<>();
    // Custom robot HP (0–20)
    private final Map<UUID, Double> robotHealth = new HashMap<>();
    // Boss bars
    private final Map<UUID, BossBar> hunterBars = new HashMap<>();

    // When the game started (ms since epoch)
    private long gameStartTime = -1L;

    // Shop item definition
    private static class ShopItem {
        final Material icon;
        final String name;
        final long unlockSeconds;
        final List<String> lore;
        final List<ItemStack> rewards;

        ShopItem(Material icon, String name, long unlockSeconds, List<String> lore, List<ItemStack> rewards) {
            this.icon = icon;
            this.name = name;
            this.unlockSeconds = unlockSeconds;
            this.lore = lore;
            this.rewards = rewards;
        }
    }

    private final List<ShopItem> shopItems = new ArrayList<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("robothunter")).setExecutor(this);
        Objects.requireNonNull(getCommand("robothunter")).setTabCompleter(this);

        setupShopItems();
        startRobotSyncTask();

        getLogger().info("PlayerControlledRobotHunter enabled.");
    }

    @Override
    public void onDisable() {
        for (BossBar bar : hunterBars.values()) {
            bar.removeAll();
        }
        hunterBars.clear();

        for (UUID robotId : new HashSet<>(robotOwner.keySet())) {
            Entity e = Bukkit.getEntity(robotId);
            if (e != null) {
                e.remove();
            }
        }
        robotOwner.clear();
        hunterRobot.clear();

        getLogger().info("PlayerControlledRobotHunter disabled.");
    }

    // ------------------------------------------------------------------------
    // SHOP SETUP
    // ------------------------------------------------------------------------

    /**
     * Define kits and unlock times:
     *  - Wooden: 0s (instant)
     *  - Stone: 600s (10 min)
     *  - Iron: 2400s (40 min)
     *  - Diamond: 3600s (60 min)
     *  No netherite kit.
     */
    private void setupShopItems() {
        // 0s - Wooden kit
        shopItems.add(new ShopItem(
                Material.WOODEN_SWORD,
                ChatColor.GREEN + "Wooden Kit",
                0,
                Arrays.asList(
                        ChatColor.GRAY + "Instant unlock.",
                        ChatColor.YELLOW + "Includes:",
                        ChatColor.GRAY + "- Wooden sword & axe",
                        ChatColor.GRAY + "- Leather armor"
                ),
                Arrays.asList(
                        new ItemStack(Material.WOODEN_SWORD),
                        new ItemStack(Material.WOODEN_AXE),
                        new ItemStack(Material.LEATHER_HELMET),
                        new ItemStack(Material.LEATHER_CHESTPLATE),
                        new ItemStack(Material.LEATHER_LEGGINGS),
                        new ItemStack(Material.LEATHER_BOOTS)
                )
        ));

        // 600s (10 min) - Stone kit
        shopItems.add(new ShopItem(
                Material.STONE_SWORD,
                ChatColor.AQUA + "Stone Kit",
                600,
                Arrays.asList(
                        ChatColor.GRAY + "Unlocks after 10 minutes.",
                        ChatColor.YELLOW + "Includes:",
                        ChatColor.GRAY + "- Stone sword & axe",
                        ChatColor.GRAY + "- Chainmail armor"
                ),
                Arrays.asList(
                        new ItemStack(Material.STONE_SWORD),
                        new ItemStack(Material.STONE_AXE),
                        new ItemStack(Material.CHAINMAIL_HELMET),
                        new ItemStack(Material.CHAINMAIL_CHESTPLATE),
                        new ItemStack(Material.CHAINMAIL_LEGGINGS),
                        new ItemStack(Material.CHAINMAIL_BOOTS)
                )
        ));

        // 2400s (40 min) - Iron kit
        shopItems.add(new ShopItem(
                Material.IRON_SWORD,
                ChatColor.WHITE + "Iron Kit",
                2400,
                Arrays.asList(
                        ChatColor.GRAY + "Unlocks after 40 minutes.",
                        ChatColor.YELLOW + "Includes:",
                        ChatColor.GRAY + "- Iron sword & axe",
                        ChatColor.GRAY + "- Full iron armor"
                ),
                Arrays.asList(
                        new ItemStack(Material.IRON_SWORD),
                        new ItemStack(Material.IRON_AXE),
                        new ItemStack(Material.IRON_HELMET),
                        new ItemStack(Material.IRON_CHESTPLATE),
                        new ItemStack(Material.IRON_LEGGINGS),
                        new ItemStack(Material.IRON_BOOTS)
                )
        ));

        // 3600s (60 min) - Diamond kit
        shopItems.add(new ShopItem(
                Material.DIAMOND_SWORD,
                ChatColor.BLUE + "Diamond Kit",
                3600,
                Arrays.asList(
                        ChatColor.GRAY + "Unlocks after 60 minutes.",
                        ChatColor.YELLOW + "Includes:",
                        ChatColor.GRAY + "- Diamond sword & axe",
                        ChatColor.GRAY + "- Full diamond armor"
                ),
                Arrays.asList(
                        new ItemStack(Material.DIAMOND_SWORD),
                        new ItemStack(Material.DIAMOND_AXE),
                        new ItemStack(Material.DIAMOND_HELMET),
                        new ItemStack(Material.DIAMOND_CHESTPLATE),
                        new ItemStack(Material.DIAMOND_LEGGINGS),
                        new ItemStack(Material.DIAMOND_BOOTS)
                )
        ));
    }

    // ------------------------------------------------------------------------
    // ROBOT SYNC
    // ------------------------------------------------------------------------

    /**
     * Repeating task that keeps each hunter's robot on top of them,
     * and keeps the hunter invisible/non-collidable/invulnerable.
     */
    private void startRobotSyncTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (UUID hunterId : hunters) {
                    Player hunter = Bukkit.getPlayer(hunterId);
                    if (hunter == null || !hunter.isOnline()) {
                        continue;
                    }

                    UUID robotId = hunterRobot.get(hunterId);
                    if (robotId == null) continue;

                    Entity e = Bukkit.getEntity(robotId);
                    if (!(e instanceof ArmorStand)) continue;
                    ArmorStand robot = (ArmorStand) e;

                    // Sync robot to player position
                    Location loc = hunter.getLocation().clone();
                    robot.teleport(loc);

                    // Make hunter invisible / non-collidable / invulnerable
                    hunter.setInvisible(true);
                    hunter.setCollidable(false);
                    hunter.setInvulnerable(true);

                    // Update boss bar
                    BossBar bar = hunterBars.get(hunterId);
                    if (bar != null) {
                        double hp = robotHealth.getOrDefault(hunterId, 20.0);
                        double progress = Math.max(0.0, Math.min(1.0, hp / 20.0));
                        bar.setProgress(progress);
                        bar.setTitle(ChatColor.RED + "Robot Hunter HP: " + (int) Math.ceil(hp) + " / 20");
                    }
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    // ------------------------------------------------------------------------
    // COMMANDS
    // ------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("robothunter.use")) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /robothunter <sethunter|clearhunters|shop>");
            return true;
        }

        if (args[0].equalsIgnoreCase("sethunter")) {
            if (!(sender instanceof Player) && args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Console must specify a player: /robothunter sethunter <player>");
                return true;
            }

            Player target;
            if (args.length >= 2) {
                target = Bukkit.getPlayer(args[1]);
            } else {
                target = (Player) sender;
            }

            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player not found.");
                return true;
            }

            setHunter(target);
            sender.sendMessage(ChatColor.GREEN + "Set " + target.getName() + " as Robot Hunter.");
            return true;
        }

        if (args[0].equalsIgnoreCase("clearhunters")) {
            clearAllHunters();
            sender.sendMessage(ChatColor.YELLOW + "Cleared all robot hunters.");
            return true;
        }

        if (args[0].equalsIgnoreCase("shop")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Only players can use the shop.");
                return true;
            }
            Player p = (Player) sender;
            if (!hunters.contains(p.getUniqueId())) {
                p.sendMessage(ChatColor.RED + "You are not a robot hunter.");
                return true;
            }
            openShop(p);
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "Unknown subcommand.");
        return true;
    }

    private void setHunter(Player p) {
        UUID id = p.getUniqueId();
        hunters.add(id);

        if (gameStartTime == -1L) {
            gameStartTime = System.currentTimeMillis();
        }

        // Save spawn
        hunterSpawn.put(id, p.getLocation().clone());

        // Remove existing robot if any
        removeRobot(id);

        // Spawn new robot
        ArmorStand robot = spawnRobotArmorStand(p.getLocation());
        hunterRobot.put(id, robot.getUniqueId());
        robotOwner.put(robot.getUniqueId(), id);

        // Init HP + boss bar
        robotHealth.put(id, 20.0);
        BossBar bar = Bukkit.createBossBar(
                ChatColor.RED + "Robot Hunter HP: 20 / 20",
                BarColor.RED,
                BarStyle.SOLID
        );
        bar.addPlayer(p);
        hunterBars.put(id, bar);

        // Hunter visibility / collision
        p.setInvisible(true);
        p.setCollidable(false);
        p.setInvulnerable(true);

        p.sendMessage(ChatColor.AQUA + "You are now controlling the Robot Hunter!");
    }

    private ArmorStand spawnRobotArmorStand(Location loc) {
        World w = loc.getWorld();
        ArmorStand stand = w.spawn(loc, ArmorStand.class, as -> {
            as.setCustomName(ChatColor.RED + "Robot Hunter");
            as.setCustomNameVisible(true);
            as.setArms(true);
            as.setBasePlate(false);
            as.setVisible(true);
            as.setSmall(false);
            as.setGravity(true);
            as.setCanTick(true);
        });
        return stand;
    }

    private void removeRobot(UUID hunterId) {
        UUID robotId = hunterRobot.remove(hunterId);
        if (robotId != null) {
            robotOwner.remove(robotId);
            Entity e = Bukkit.getEntity(robotId);
            if (e != null) {
                e.remove();
            }
        }
    }

    private void clearAllHunters() {
        for (UUID id : new HashSet<>(hunters)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.setInvisible(false);
                p.setCollidable(true);
                p.setInvulnerable(false);
            }
            removeRobot(id);
            BossBar bar = hunterBars.remove(id);
            if (bar != null) {
                bar.removeAll();
            }
            robotHealth.remove(id);
        }
        hunters.clear();
    }

    private void openShop(Player p) {
        Inventory inv = Bukkit.createInventory(p, 9, SHOP_TITLE);

        long elapsed = (gameStartTime == -1L)
                ? 0
                : (System.currentTimeMillis() - gameStartTime) / 1000L;

        for (int i = 0; i < shopItems.size() && i < 9; i++) {
            ShopItem si = shopItems.get(i);
            ItemStack item = new ItemStack(si.icon);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(si.name);

                List<String> lore = new ArrayList<>(si.lore);
                if (elapsed >= si.unlockSeconds) {
                    lore.add(ChatColor.GREEN + "Unlocked!");
                    lore.add(ChatColor.GRAY + "Click to apply to robot.");
                } else {
                    long remaining = si.unlockSeconds - elapsed;
                    lore.add(ChatColor.RED + "Locked: " + remaining + "s remaining.");
                }
                meta.setLore(lore);
                item.setItemMeta(meta);
            }
            inv.setItem(i, item);
        }

        p.openInventory(inv);
    }

    // ------------------------------------------------------------------------
    // EVENTS
    // ------------------------------------------------------------------------

    @EventHandler
    public void onFoodChange(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player) {
            Player p = (Player) e.getEntity();
            if (hunters.contains(p.getUniqueId())) {
                e.setCancelled(true);
                p.setFoodLevel(20);
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();
        if (!hunters.contains(p.getUniqueId())) return;

        if (!e.getView().getTitle().equals(SHOP_TITLE)) return;

        e.setCancelled(true);
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null || meta.getDisplayName() == null) return;

        String name = meta.getDisplayName();

        ShopItem si = shopItems.stream()
                .filter(s -> s.name.equals(name))
                .findFirst()
                .orElse(null);
        if (si == null) return;

        long elapsed = (gameStartTime == -1L)
                ? 0
                : (System.currentTimeMillis() - gameStartTime) / 1000L;

        if (elapsed < si.unlockSeconds) {
            long remaining = si.unlockSeconds - elapsed;
            p.sendMessage(ChatColor.RED + "This kit is still locked for " + remaining + " more seconds.");
            return;
        }

        UUID hunterId = p.getUniqueId();
        UUID robotId = hunterRobot.get(hunterId);
        if (robotId == null) {
            p.sendMessage(ChatColor.RED + "Robot entity missing.");
            return;
        }
        Entity eRobot = Bukkit.getEntity(robotId);
        if (!(eRobot instanceof ArmorStand)) {
            p.sendMessage(ChatColor.RED + "Robot is not an armor stand.");
            return;
        }
        ArmorStand robot = (ArmorStand) eRobot;

        for (ItemStack reward : si.rewards) {
            if (reward == null) continue;
            Material m = reward.getType();

            // Armor goes on robot AND player
            if (m.name().endsWith("_HELMET")) {
                robot.getEquipment().setHelmet(reward.clone());
                p.getInventory().setItem(EquipmentSlot.HEAD, reward.clone());
            } else if (m.name().endsWith("_CHESTPLATE")) {
                robot.getEquipment().setChestplate(reward.clone());
                p.getInventory().setItem(EquipmentSlot.CHEST, reward.clone());
            } else if (m.name().endsWith("_LEGGINGS")) {
                robot.getEquipment().setLeggings(reward.clone());
                p.getInventory().setItem(EquipmentSlot.LEGS, reward.clone());
            } else if (m.name().endsWith("_BOOTS")) {
                robot.getEquipment().setBoots(reward.clone());
                p.getInventory().setItem(EquipmentSlot.FEET, reward.clone());
            } else {
                // Weapons: main hand if empty, otherwise just give to player
                if (robot.getEquipment().getItemInMainHand() == null ||
                        robot.getEquipment().getItemInMainHand().getType() == Material.AIR) {
                    robot.getEquipment().setItemInMainHand(reward.clone());
                    p.getInventory().setItemInMainHand(reward.clone());
                } else {
                    p.getInventory().addItem(reward.clone());
                }
            }
        }

        p.sendMessage(ChatColor.GREEN + "Applied " + ChatColor.stripColor(si.name) + " to your robot.");
    }

    @EventHandler
    public void onRobotDamage(EntityDamageEvent e) {
        Entity entity = e.getEntity();
        UUID entityId = entity.getUniqueId();
        if (!robotOwner.containsKey(entityId)) return;

        UUID hunterId = robotOwner.get(entityId);
        if (!hunters.contains(hunterId)) return;

        double current = robotHealth.getOrDefault(hunterId, 20.0);
        double newHp = current - e.getFinalDamage();
        e.setCancelled(true); // handle damage ourselves

        if (newHp <= 0) {
            robotHealth.put(hunterId, 20.0);
            Player hunter = Bukkit.getPlayer(hunterId);
            Location spawn = hunterSpawn.getOrDefault(
                    hunterId,
                    entity.getWorld().getSpawnLocation()
            );

            if (hunter != null) {
                hunter.sendMessage(ChatColor.RED + "Your robot died! Respawning at original spawn.");
                hunter.teleport(spawn);
            }

            entity.teleport(spawn);
        } else {
            robotHealth.put(hunterId, newHp);
        }
    }

    @EventHandler
    public void onRobotAttack(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player)) return;

        Player p = (Player) e.getDamager();
        if (!hunters.contains(p.getUniqueId())) return;

        // Cancel normal damage and do our own raycast from robot
        e.setCancelled(true);

        UUID robotId = hunterRobot.get(p.getUniqueId());
        if (robotId == null) return;
        Entity robot = Bukkit.getEntity(robotId);
        if (robot == null) return;

        Location eye = robot.getLocation().clone();
        Vector dir = eye.getDirection().normalize();

        Entity target = null;
        double bestDist = 3.0;

        for (Entity nearby : robot.getNearbyEntities(3, 3, 3)) {
            if (nearby instanceof Player && hunters.contains(nearby.getUniqueId())) {
                // Don’t hit other hunters / yourself
                continue;
            }
            if (!(nearby instanceof LivingEntity)) continue;

            Vector to = nearby.getLocation().toVector().subtract(eye.toVector());
            double proj = to.dot(dir);
            if (proj < 0 || proj > 3) continue;

            // Distance from line
            Vector closest = eye.toVector().add(dir.clone().multiply(proj));
            double dist = closest.distance(nearby.getLocation().toVector());
            if (dist <= 1.5 && proj < bestDist) {
                bestDist = proj;
                target = nearby;
            }
        }

        if (target instanceof LivingEntity) {
            LivingEntity le = (LivingEntity) target;
            double damage = 4.0; // base

            ItemStack weapon = p.getInventory().getItemInMainHand();
            if (weapon != null) {
                String name = weapon.getType().name();
                if (name.contains("WOODEN_SWORD")) damage = 4.0;
                else if (name.contains("STONE_SWORD")) damage = 5.0;
                else if (name.contains("IRON_SWORD")) damage = 6.0;
                else if (name.contains("DIAMOND_SWORD")) damage = 7.0;
            }

            le.damage(damage, p);
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 1f, 1f);
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (!hunters.contains(p.getUniqueId())) return;

        Location spawn = hunterSpawn.get(p.getUniqueId());
        if (spawn != null) {
            e.setRespawnLocation(spawn);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (!hunters.contains(id)) return;

        removeRobot(id);
        BossBar bar = hunterBars.remove(id);
        if (bar != null) {
            bar.removeAll();
        }
        robotHealth.remove(id);
        hunters.remove(id);
    }

    // ------------------------------------------------------------------------
    // TAB COMPLETE
    // ------------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!sender.hasPermission("robothunter.use")) return Collections.emptyList();

        if (args.length == 1) {
            return Arrays.asList("sethunter", "clearhunters", "shop").stream()
                    .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("sethunter")) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }

        return Collections.emptyList();
    }
}
