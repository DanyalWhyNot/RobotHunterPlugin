package me.danyul.robot;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.bukkit.enchantments.Enchantment;

import java.util.*;
import java.util.stream.Collectors;

public class PlayerControlledRobotHunterPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final String SHOP_TITLE = ChatColor.DARK_AQUA + "Robot Hunter Shop";

    // Robot max HP: 30.0 = 15 hearts
    private static final double ROBOT_MAX_HP = 30.0;

    // Attack cooldown so robot can’t spam hits (ms)
    private static final long ATTACK_COOLDOWN_MS = 600L;

    // Active hunters (supports MULTIPLE hunters)
    private final Set<UUID> hunters = new HashSet<>();
    // Hunter -> robot armor stand UUID
    private final Map<UUID, UUID> hunterRobot = new HashMap<>();
    // Robot entity -> hunter UUID
    private final Map<UUID, UUID> robotOwner = new HashMap<>();
    // Hunter original spawn location
    private final Map<UUID, Location> hunterSpawn = new HashMap<>();
    // Robot HP tracking
    private final Map<UUID, Double> robotHealth = new HashMap<>();
    // Last attack times (for cooldown)
    private final Map<UUID, Long> lastAttackTime = new HashMap<>();

    // When the game started (ms since epoch)
    private long gameStartTime = -1L;

    // Current runner (speedrunner) for compass tracking
    private UUID runnerId = null;

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
        startCompassTask();

        getLogger().info("PlayerControlledRobotHunter enabled.");
    }

    @Override
    public void onDisable() {
        // Clean up robots on disable
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

    private void setupShopItems() {
        // Wooden kit (0s)
        shopItems.add(new ShopItem(
                Material.WOODEN_SWORD,
                ChatColor.GREEN + "Wooden Kit",
                0,
                Arrays.asList(
                        ChatColor.GRAY + "Instant unlock.",
                        ChatColor.YELLOW + "Includes:",
                        ChatColor.GRAY + "- Wooden sword & axe",
                        ChatColor.GRAY + "- Leather armor",
                        ChatColor.GRAY + "- Blocks & utility items"
                ),
                kitWithExtras(
                        new ItemStack(Material.WOODEN_SWORD),
                        new ItemStack(Material.WOODEN_AXE),
                        new ItemStack(Material.LEATHER_HELMET),
                        new ItemStack(Material.LEATHER_CHESTPLATE),
                        new ItemStack(Material.LEATHER_LEGGINGS),
                        new ItemStack(Material.LEATHER_BOOTS)
                )
        ));

        // Stone kit (600s / 10 min)
        shopItems.add(new ShopItem(
                Material.STONE_SWORD,
                ChatColor.AQUA + "Stone Kit",
                600,
                Arrays.asList(
                        ChatColor.GRAY + "Unlocks after 10 minutes.",
                        ChatColor.YELLOW + "Includes:",
                        ChatColor.GRAY + "- Stone sword & axe",
                        ChatColor.GRAY + "- Chainmail armor",
                        ChatColor.GRAY + "- Blocks & utility items"
                ),
                kitWithExtras(
                        new ItemStack(Material.STONE_SWORD),
                        new ItemStack(Material.STONE_AXE),
                        new ItemStack(Material.CHAINMAIL_HELMET),
                        new ItemStack(Material.CHAINMAIL_CHESTPLATE),
                        new ItemStack(Material.CHAINMAIL_LEGGINGS),
                        new ItemStack(Material.CHAINMAIL_BOOTS)
                )
        ));

        // Iron kit (2400s / 40 min)
        shopItems.add(new ShopItem(
                Material.IRON_SWORD,
                ChatColor.WHITE + "Iron Kit",
                2400,
                Arrays.asList(
                        ChatColor.GRAY + "Unlocks after 40 minutes.",
                        ChatColor.YELLOW + "Includes:",
                        ChatColor.GRAY + "- Iron sword & axe",
                        ChatColor.GRAY + "- Full iron armor",
                        ChatColor.GRAY + "- Blocks & utility items"
                ),
                kitWithExtras(
                        new ItemStack(Material.IRON_SWORD),
                        new ItemStack(Material.IRON_AXE),
                        new ItemStack(Material.IRON_HELMET),
                        new ItemStack(Material.IRON_CHESTPLATE),
                        new ItemStack(Material.IRON_LEGGINGS),
                        new ItemStack(Material.IRON_BOOTS)
                )
        ));

        // Diamond kit (3600s / 60 min)
        shopItems.add(new ShopItem(
                Material.DIAMOND_SWORD,
                ChatColor.BLUE + "Diamond Kit",
                3600,
                Arrays.asList(
                        ChatColor.GRAY + "Unlocks after 60 minutes.",
                        ChatColor.YELLOW + "Includes:",
                        ChatColor.GRAY + "- Diamond sword & axe",
                        ChatColor.GRAY + "- Full diamond armor",
                        ChatColor.GRAY + "- Blocks & utility items"
                ),
                kitWithExtras(
                        new ItemStack(Material.DIAMOND_SWORD),
                        new ItemStack(Material.DIAMOND_AXE),
                        new ItemStack(Material.DIAMOND_HELMET),
                        new ItemStack(Material.DIAMOND_CHESTPLATE),
                        new ItemStack(Material.DIAMOND_LEGGINGS),
                        new ItemStack(Material.DIAMOND_BOOTS)
                )
        ));
    }

    // Common extras added to EVERY kit (blocks & utility, no food)
    private List<ItemStack> getCommonExtras() {
        List<ItemStack> extras = new ArrayList<>();
        extras.add(new ItemStack(Material.COBBLESTONE, 64));
        extras.add(new ItemStack(Material.COBBLESTONE, 64));
        extras.add(new ItemStack(Material.OAK_PLANKS, 64));
        extras.add(new ItemStack(Material.OAK_PLANKS, 64));
        extras.add(new ItemStack(Material.LADDER, 32));
        extras.add(new ItemStack(Material.WATER_BUCKET, 1));
        extras.add(new ItemStack(Material.LAVA_BUCKET, 1));
        extras.add(new ItemStack(Material.SHIELD, 1));
        extras.add(new ItemStack(Material.ENDER_PEARL, 4));
        return extras;
    }

    // Helper to merge base kit items + extras
    private List<ItemStack> kitWithExtras(ItemStack... base) {
        List<ItemStack> result = new ArrayList<>(Arrays.asList(base));
        result.addAll(getCommonExtras());
        return result;
    }

    // ------------------------------------------------------------------------
    // ROBOT SYNC (position + slowness + invis)
    // ------------------------------------------------------------------------

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

                    // Robot stays on top of the hunter's position
                    Location loc = hunter.getLocation().clone();
                    robot.teleport(loc);

                    // Hunter becomes invisible, non-collidable, invulnerable, and slow
                    hunter.setInvisible(true);
                    hunter.setCollidable(false);
                    hunter.setInvulnerable(true); // immune to all damage
                    if (!hunter.hasPotionEffect(PotionEffectType.SLOWNESS)) {
                        hunter.addPotionEffect(new PotionEffect(
                                PotionEffectType.SLOWNESS,
                                Integer.MAX_VALUE,
                                0, // Slowness I
                                false, false, false
                        ));
                    }
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    // ------------------------------------------------------------------------
    // COMPASS TASK (runner tracking, enchanted compass)
    // ------------------------------------------------------------------------

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

                    // Ensure hunter has a compass
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
                        cMeta.setLodestoneTracked(false); // use custom lodestone position

                        // Make compass look enchanted/glowing
                    Enchantment glow = Enchantment.getByName("UNBREAKING");
                    if (glow != null) {
                        cMeta.addEnchant(glow, 1, true);
                        cMeta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                    }


                        compass.setItemMeta(cMeta);
                    }
                }
            }
        }.runTaskTimer(this, 20L, 20L); // update every second
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
            sender.sendMessage(ChatColor.YELLOW + "Usage: /robothunter <sethunter|setrunner|clearhunters|shop>");
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
        hunters.add(id); // supports multiple hunters

        if (gameStartTime == -1L) {
            gameStartTime = System.currentTimeMillis();
        }

        hunterSpawn.put(id, p.getLocation().clone());
        removeRobot(id);

        ArmorStand robot = spawnRobotArmorStand(p.getLocation());
        hunterRobot.put(id, robot.getUniqueId());
        robotOwner.put(robot.getUniqueId(), id);

        robotHealth.put(id, ROBOT_MAX_HP);

        p.setInvisible(true);
        p.setCollidable(false);
        p.setInvulnerable(true); // hunter is totally immune
        p.addPotionEffect(new PotionEffect(
                PotionEffectType.SLOWNESS,
                Integer.MAX_VALUE,
                0, // Slowness I
                false, false, false
        ));

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
            as.setInvulnerable(false); // allow damage events
            as.setRemoveWhenFarAway(false);
        });

        // Make it look more "robot" with a skeleton head
        stand.getEquipment().setHelmet(new ItemStack(Material.SKELETON_SKULL));

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
        robotHealth.remove(hunterId);
    }

    private void clearAllHunters() {
        for (UUID id : new HashSet<>(hunters)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.setInvisible(false);
                p.setCollidable(true);
                p.setInvulnerable(false);
                p.removePotionEffect(PotionEffectType.SLOWNESS);
            }
            removeRobot(id);
            lastAttackTime.remove(id);
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

    // Hunter should never get hungry (keep bar always full, looks like no hunger)
    @EventHandler
    public void onFoodChange(FoodLevelChangeEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        if (!hunters.contains(p.getUniqueId())) return;

        e.setCancelled(true);
        p.setFoodLevel(20);
        p.setSaturation(20f);
    }

    // Hunter takes no damage from anything (including fall)
    @EventHandler
    public void onHunterDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        if (!hunters.contains(p.getUniqueId())) return;

        e.setCancelled(true);
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

            // Armor only on robot
            if (m.name().endsWith("_HELMET")) {
                robot.getEquipment().setHelmet(reward.clone());
            } else if (m.name().endsWith("_CHESTPLATE")) {
                robot.getEquipment().setChestplate(reward.clone());
            } else if (m.name().endsWith("_LEGGINGS")) {
                robot.getEquipment().setLeggings(reward.clone());
            } else if (m.name().endsWith("_BOOTS")) {
                robot.getEquipment().setBoots(reward.clone());
            } else {
                // Weapons / extras:
                // weapon to robot main hand if empty, otherwise give to hunter inventory
                if (m.name().endsWith("_SWORD") || m.name().endsWith("_AXE")) {
                    ItemStack current = robot.getEquipment().getItemInMainHand();
                    if (current == null || current.getType() == Material.AIR) {
                        robot.getEquipment().setItemInMainHand(reward.clone());
                    } else {
                        p.getInventory().addItem(reward.clone());
                    }
                } else {
                    p.getInventory().addItem(reward.clone());
                }
            }
        }

        p.sendMessage(ChatColor.GREEN + "Applied " + ChatColor.stripColor(si.name) + " to your robot and inventory.");
    }

    // Robot (armor stand) takes all damage; custom 15-heart HP
    @EventHandler
    public void onRobotDamage(EntityDamageEvent e) {
        Entity entity = e.getEntity();
        if (!(entity instanceof ArmorStand)) return;

        UUID entityId = entity.getUniqueId();
        if (!robotOwner.containsKey(entityId)) return;

        UUID hunterId = robotOwner.get(entityId);
        if (!hunters.contains(hunterId)) return;

        double current = robotHealth.getOrDefault(hunterId, ROBOT_MAX_HP);
        double newHp = current - e.getFinalDamage();
        e.setCancelled(true); // handle damage ourselves

        if (newHp <= 0) {
            robotHealth.put(hunterId, ROBOT_MAX_HP);
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

       // Robot attack: buffed damage with cooldown, uses vanilla hit detection
    @EventHandler
    public void onRobotAttack(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player)) return;

        Player hunter = (Player) e.getDamager();
        if (!hunters.contains(hunter.getUniqueId())) return;

        // Don't let hunters damage each other
        if (e.getEntity() instanceof Player &&
                hunters.contains(((Player) e.getEntity()).getUniqueId())) {
            e.setCancelled(true);
            return;
        }

        long now = System.currentTimeMillis();
        long last = lastAttackTime.getOrDefault(hunter.getUniqueId(), 0L);
        if (now - last < ATTACK_COOLDOWN_MS) {
            // still on cooldown
            e.setCancelled(true);
            return;
        }
        lastAttackTime.put(hunter.getUniqueId(), now);

        // Base damage: bare hand = 3
        double damage = 3.0;

        // Look at what the ROBOT is holding, not the hunter
        UUID robotId = hunterRobot.get(hunter.getUniqueId());
        if (robotId != null) {
            Entity robotEntity = Bukkit.getEntity(robotId);
            if (robotEntity instanceof ArmorStand) {
                ArmorStand robot = (ArmorStand) robotEntity;
                ItemStack weapon = robot.getEquipment().getItemInMainHand();
                if (weapon != null) {
                    String name = weapon.getType().name();
                    if (name.contains("WOODEN_SWORD")) damage = 6.0;
                    else if (name.contains("STONE_SWORD")) damage = 7.0;
                    else if (name.contains("IRON_SWORD")) damage = 8.0;
                    else if (name.contains("DIAMOND_SWORD")) damage = 9.0;
                }
            }
        }

        // Apply our custom damage
        e.setDamage(damage);
        hunter.playSound(hunter.getLocation(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 1f, 1f);
    }
        lastAttackTime.put(p.getUniqueId(), now);

        UUID robotId = hunterRobot.get(p.getUniqueId());
        if (robotId == null) return;
        Entity robotEntity = Bukkit.getEntity(robotId);
        if (robotEntity == null) return;

        Location eye = robotEntity.getLocation().clone();
        Vector dir = eye.getDirection().normalize();

        Entity target = null;
        double bestDist = 3.0;

        // Search for a target in front of the robot
        for (Entity nearby : robotEntity.getNearbyEntities(3, 3, 3)) {
            if (nearby instanceof Player && hunters.contains(nearby.getUniqueId())) {
                // Don't hit other robot hunters / yourself
                continue;
            }
            if (!(nearby instanceof LivingEntity)) continue;

            Vector to = nearby.getLocation().toVector().subtract(eye.toVector());
            double proj = to.dot(dir);
            if (proj < 0 || proj > 3) continue;

            Vector closest = eye.toVector().add(dir.clone().multiply(proj));
            double dist = closest.distance(nearby.getLocation().toVector());
            if (dist <= 1.5 && proj < bestDist) {
                bestDist = proj;
                target = nearby;
            }
        }

        if (target instanceof LivingEntity) {
            LivingEntity le = (LivingEntity) target;

            // Bare hand = 3, swords buffed: 6 / 7 / 8 / 9
            double damage = 3.0;

            if (robotEntity instanceof ArmorStand) {
                ArmorStand robot = (ArmorStand) robotEntity;
                ItemStack weapon = robot.getEquipment().getItemInMainHand();
                if (weapon != null) {
                    String name = weapon.getType().name();
                    if (name.contains("WOODEN_SWORD")) damage = 6.0;
                    else if (name.contains("STONE_SWORD")) damage = 7.0;
                    else if (name.contains("IRON_SWORD")) damage = 8.0;
                    else if (name.contains("DIAMOND_SWORD")) damage = 9.0;
                }
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

        e.getPlayer().setInvisible(false);
        e.getPlayer().setCollidable(true);
        e.getPlayer().setInvulnerable(false);
        e.getPlayer().removePotionEffect(PotionEffectType.SLOWNESS);

        removeRobot(id);
        hunters.remove(id);
        lastAttackTime.remove(id);
    }

    // ------------------------------------------------------------------------
    // TAB COMPLETE
    // ------------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!sender.hasPermission("robothunter.use")) return Collections.emptyList();

        if (args.length == 1) {
            return Arrays.asList("sethunter", "setrunner", "clearhunters", "shop").stream()
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
