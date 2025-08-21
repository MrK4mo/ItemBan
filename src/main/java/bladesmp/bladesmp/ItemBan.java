package bladesmp.bladesmp;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ItemBan extends JavaPlugin implements Listener {

    private final Map<UUID, Long> combatPlayers = new ConcurrentHashMap<>();
    private final Set<Material> bannedItems = new HashSet<>();
    private final Set<Material> combatRestrictedItems = new HashSet<>();
    private final Map<String, Set<Material>> regionBannedItems = new HashMap<>();
    private final Map<String, Set<Material>> regionCombatItems = new HashMap<>();
    private final Map<UUID, Boolean> playerMessagesEnabled = new ConcurrentHashMap<>();

    private int combatDuration;
    private boolean messagesEnabled = true;
    private boolean worldGuardEnabled = false;
    private boolean debugEnabled = false;
    private boolean logInteractions = false;
    private boolean worldGuardAvailable = false;

    @Override
    public void onEnable() {
        // WorldGuard-Verfügbarkeit prüfen
        checkWorldGuardAvailability();

        // Config erstellen/laden
        saveDefaultConfig();
        loadConfiguration();

        // Event Listener registrieren
        Bukkit.getPluginManager().registerEvents(this, this);

        // Task für Combat-Cleanup starten
        startCombatCleanupTask();

        getLogger().info("ItemBan Plugin erfolgreich geladen!");
        getLogger().info("Gebannte Items: " + bannedItems.size());
        getLogger().info("Combat-beschränkte Items: " + combatRestrictedItems.size());
        getLogger().info("Combat-Dauer: " + combatDuration + " Sekunden");
        getLogger().info("WorldGuard: " + (worldGuardAvailable && worldGuardEnabled ? "Aktiviert" : "Deaktiviert"));
        getLogger().info("Nachrichten: " + (messagesEnabled ? "Aktiviert" : "Deaktiviert"));
    }

    private void checkWorldGuardAvailability() {
        try {
            // Prüfe ob WorldGuard Plugin geladen ist
            if (getServer().getPluginManager().getPlugin("WorldGuard") != null) {
                // Versuche WorldGuard Klassen zu laden
                Class.forName("com.sk89q.worldguard.WorldGuard");
                Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
                Class.forName("com.sk89q.worldguard.protection.regions.RegionContainer");
                worldGuardAvailable = true;
                getLogger().info("WorldGuard erfolgreich erkannt - Integration aktiviert");
            } else {
                worldGuardAvailable = false;
                getLogger().info("WorldGuard Plugin nicht gefunden");
            }
        } catch (ClassNotFoundException e) {
            worldGuardAvailable = false;
            getLogger().warning("WorldGuard Plugin gefunden, aber Klassen nicht verfügbar: " + e.getMessage());
            getLogger().info("Regionen-Features deaktiviert");
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("itemban")) {
            return false;
        }

        if (!sender.hasPermission("itemban.admin")) {
            sender.sendMessage(getMessage("no-permission"));
            return true;
        }

        if (args.length == 0) {
            sendHelpMessage(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                reloadPlugin();
                sender.sendMessage(getMessage("plugin-reloaded"));
                break;

            case "info":
                sendInfoMessage(sender);
                break;

            case "toggle":
                if (sender instanceof Player) {
                    Player player = (Player) sender;
                    boolean enabled = !playerMessagesEnabled.getOrDefault(player.getUniqueId(), messagesEnabled);
                    playerMessagesEnabled.put(player.getUniqueId(), enabled);
                    sender.sendMessage("§6Nachrichten: " + (enabled ? "§aAktiviert" : "§cDeaktiviert"));
                } else {
                    sender.sendMessage("§cDieser Command ist nur für Spieler!");
                }
                break;

            case "combat":
                if (args.length >= 2) {
                    Player target = getServer().getPlayer(args[1]);
                    if (target == null) {
                        sender.sendMessage(getMessage("player-not-found"));
                    } else {
                        if (isInCombat(target)) {
                            long remaining = getRemainingCombatTime(target);
                            sender.sendMessage(getMessage("combat-status-yes")
                                    .replace("%player%", target.getName())
                                    .replace("%time%", String.valueOf(remaining)));
                        } else {
                            sender.sendMessage(getMessage("combat-status-no")
                                    .replace("%player%", target.getName()));
                        }
                    }
                } else {
                    sender.sendMessage("§c§lVerwendung: /itemban combat <player>");
                }
                break;

            default:
                sender.sendMessage("§c§lUnbekannter Befehl! Verwende /itemban für Hilfe");
                break;
        }

        return true;
    }

    private void sendHelpMessage(CommandSender sender) {
        sender.sendMessage(getMessage("help-header"));
        sender.sendMessage(getMessage("help-reload"));
        sender.sendMessage(getMessage("help-info"));
        sender.sendMessage(getMessage("help-combat"));
        sender.sendMessage(getMessage("help-toggle"));
    }

    private void sendInfoMessage(CommandSender sender) {
        sender.sendMessage(getMessage("info-header"));
        sender.sendMessage(getMessage("info-combat-duration").replace("%duration%", String.valueOf(combatDuration)));
        sender.sendMessage(getMessage("info-banned-items").replace("%count%", String.valueOf(bannedItems.size())));
        sender.sendMessage(getMessage("info-combat-items").replace("%count%", String.valueOf(combatRestrictedItems.size())));
        sender.sendMessage(getMessage("info-players-in-combat").replace("%count%", String.valueOf(combatPlayers.size())));
        sender.sendMessage(getMessage("info-worldguard").replace("%status%",
                worldGuardAvailable && worldGuardEnabled ? "§aAktiviert" : "§cDeaktiviert"));
        sender.sendMessage(getMessage("info-messages").replace("%status%",
                messagesEnabled ? "§aAktiviert" : "§cDeaktiviert"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("itemban") || !sender.hasPermission("itemban.admin")) {
            return Collections.emptyList();
        }

        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            List<String> subcommands = Arrays.asList("reload", "info", "combat", "toggle");
            for (String sub : subcommands) {
                if (sub.toLowerCase().startsWith(args[0].toLowerCase())) {
                    completions.add(sub);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("combat")) {
            // Player names für combat command
            getServer().getOnlinePlayers().forEach(player -> {
                if (player.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                    completions.add(player.getName());
                }
            });
        }

        return completions;
    }

    private void loadConfiguration() {
        reloadConfig();

        // Combat-Dauer laden
        combatDuration = getConfig().getInt("combat.duration", 10);

        // Message-Einstellungen laden
        messagesEnabled = getConfig().getBoolean("messages.enabled", true);

        // WorldGuard-Einstellungen laden
        worldGuardEnabled = getConfig().getBoolean("worldguard.enabled", true);

        // Debug-Einstellungen laden
        debugEnabled = getConfig().getBoolean("debug.enabled", false);
        logInteractions = getConfig().getBoolean("debug.log-interactions", false);

        // Gebannte Items laden
        bannedItems.clear();
        List<String> bannedList = getConfig().getStringList("banned-items");
        for (String item : bannedList) {
            try {
                Material material = Material.valueOf(item.toUpperCase());
                bannedItems.add(material);
            } catch (IllegalArgumentException e) {
                getLogger().warning("Unbekanntes Material in banned-items: " + item);
            }
        }

        // Combat-beschränkte Items laden
        combatRestrictedItems.clear();
        List<String> combatList = getConfig().getStringList("combat.restricted-items");
        for (String item : combatList) {
            try {
                Material material = Material.valueOf(item.toUpperCase());
                combatRestrictedItems.add(material);
            } catch (IllegalArgumentException e) {
                getLogger().warning("Unbekanntes Material in combat.restricted-items: " + item);
            }
        }

        // WorldGuard Regionen laden
        loadWorldGuardRegions();
    }

    private void loadWorldGuardRegions() {
        if (!worldGuardAvailable || !worldGuardEnabled) {
            return;
        }

        regionBannedItems.clear();
        regionCombatItems.clear();

        // Regionsspezifische gebannte Items laden
        if (getConfig().contains("worldguard.region-banned-items")) {
            for (String regionName : getConfig().getConfigurationSection("worldguard.region-banned-items").getKeys(false)) {
                Set<Material> materials = new HashSet<>();
                List<String> items = getConfig().getStringList("worldguard.region-banned-items." + regionName);

                for (String item : items) {
                    try {
                        Material material = Material.valueOf(item.toUpperCase());
                        materials.add(material);
                    } catch (IllegalArgumentException e) {
                        getLogger().warning("Unbekanntes Material in Region " + regionName + ": " + item);
                    }
                }

                if (!materials.isEmpty()) {
                    regionBannedItems.put(regionName.toLowerCase(), materials);
                    if (debugEnabled) {
                        getLogger().info("Geladene gebannte Items für Region '" + regionName + "': " + materials.size());
                    }
                }
            }
        }

        // Regionsspezifische Combat-Items laden
        if (getConfig().contains("worldguard.region-combat-items")) {
            for (String regionName : getConfig().getConfigurationSection("worldguard.region-combat-items").getKeys(false)) {
                Set<Material> materials = new HashSet<>();
                List<String> items = getConfig().getStringList("worldguard.region-combat-items." + regionName);

                for (String item : items) {
                    try {
                        Material material = Material.valueOf(item.toUpperCase());
                        materials.add(material);
                    } catch (IllegalArgumentException e) {
                        getLogger().warning("Unbekanntes Material in Region " + regionName + " Combat-Items: " + item);
                    }
                }

                if (!materials.isEmpty()) {
                    regionCombatItems.put(regionName.toLowerCase(), materials);
                    if (debugEnabled) {
                        getLogger().info("Geladene Combat-Items für Region '" + regionName + "': " + materials.size());
                    }
                }
            }
        }
    }

    @Override
    public void onDisable() {
        combatPlayers.clear();
        getLogger().info("ItemBan Plugin deaktiviert!");
    }

    private void startCombatCleanupTask() {
        try {
            // Versuche Folia Scheduler API zu verwenden
            if (isFoliaServer()) {
                // Folia Global Region Scheduler
                getServer().getGlobalRegionScheduler().runAtFixedRate(this, (task) -> {
                    long currentTime = System.currentTimeMillis();
                    combatPlayers.entrySet().removeIf(entry ->
                            currentTime - entry.getValue() > (combatDuration * 1000L));
                }, 20L, 20L); // Läuft jede Sekunde
                getLogger().info("Verwende Folia GlobalRegionScheduler für Combat-Cleanup");
            } else {
                // Standard Bukkit Scheduler für Paper/Spigot
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        long currentTime = System.currentTimeMillis();
                        combatPlayers.entrySet().removeIf(entry ->
                                currentTime - entry.getValue() > (combatDuration * 1000L));
                    }
                }.runTaskTimer(this, 20L, 20L);
                getLogger().info("Verwende Standard BukkitScheduler für Combat-Cleanup");
            }
        } catch (Exception e) {
            getLogger().warning("Konnte Combat-Cleanup Task nicht starten: " + e.getMessage());
            getLogger().warning("Combat-Timer werden nicht automatisch bereinigt - funktioniert trotzdem!");
        }
    }

    private boolean isFoliaServer() {
        try {
            // Prüfe ob Folia Klassen verfügbar sind
            Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        // Combat-Timer für beide Spieler setzen
        if (event.getDamager() instanceof Player) {
            Player damager = (Player) event.getDamager();
            combatPlayers.put(damager.getUniqueId(), System.currentTimeMillis());
        }

        if (event.getEntity() instanceof Player) {
            Player victim = (Player) event.getEntity();
            combatPlayers.put(victim.getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Material item = event.getMaterial();

        // Bypass-Permission prüfen
        if (bannedItems.contains(item) && player.hasPermission("itemban.bypass.banned")) {
            return;
        }
        if (combatRestrictedItems.contains(item) && player.hasPermission("itemban.bypass.combat")) {
            return;
        }

        // WorldGuard Regionen-Check
        if (worldGuardAvailable && worldGuardEnabled) {
            if (isItemBannedInRegion(player.getLocation(), item)) {
                event.setCancelled(true);
                sendMessage(player, "region-banned");
                return;
            }

            if (isInCombat(player) && isItemCombatRestrictedInRegion(player.getLocation(), item)) {
                event.setCancelled(true);
                long remainingTime = getRemainingCombatTime(player);
                sendMessage(player, "region-combat", remainingTime);
                return;
            }
        }

        // Prüfen ob Item komplett gebannt ist
        if (bannedItems.contains(item)) {
            event.setCancelled(true);
            sendMessage(player, "banned-item");
            return;
        }

        // Prüfen ob Spieler im Combat ist und Item restricted ist
        if (isInCombat(player) && combatRestrictedItems.contains(item)) {
            event.setCancelled(true);
            long remainingTime = getRemainingCombatTime(player);
            sendMessage(player, "combat-restriction", remainingTime);
        }
    }

    @EventHandler
    public void onPlayerItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        Material newItem = player.getInventory().getItem(event.getNewSlot()) != null ?
                player.getInventory().getItem(event.getNewSlot()).getType() : Material.AIR;

        // Bypass-Permission prüfen
        if (bannedItems.contains(newItem) && player.hasPermission("itemban.bypass.banned")) {
            return;
        }
        if (combatRestrictedItems.contains(newItem) && player.hasPermission("itemban.bypass.combat")) {
            return;
        }

        // WorldGuard Regionen-Check
        if (worldGuardAvailable && worldGuardEnabled) {
            if (isItemBannedInRegion(player.getLocation(), newItem)) {
                event.setCancelled(true);
                sendMessage(player, "region-banned");
                return;
            }

            if (isInCombat(player) && isItemCombatRestrictedInRegion(player.getLocation(), newItem)) {
                event.setCancelled(true);
                long remainingTime = getRemainingCombatTime(player);
                sendMessage(player, "region-combat", remainingTime);
                return;
            }
        }

        // Prüfen ob Item komplett gebannt ist
        if (bannedItems.contains(newItem)) {
            event.setCancelled(true);
            sendMessage(player, "banned-item");
            return;
        }

        // Prüfen ob Spieler im Combat ist und Item restricted ist
        if (isInCombat(player) && combatRestrictedItems.contains(newItem)) {
            event.setCancelled(true);
            long remainingTime = getRemainingCombatTime(player);
            sendMessage(player, "combat-restriction", remainingTime);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;

        Player player = (Player) event.getWhoClicked();

        if (event.getCurrentItem() != null) {
            Material item = event.getCurrentItem().getType();
            Location location = player.getLocation();

            // Bypass-Permission prüfen
            if (bannedItems.contains(item) && player.hasPermission("itemban.bypass.banned")) {
                return;
            }
            if (combatRestrictedItems.contains(item) && player.hasPermission("itemban.bypass.combat")) {
                return;
            }

            // WorldGuard Regionen-Check
            if (worldGuardAvailable && worldGuardEnabled) {
                if (isItemBannedInRegion(location, item)) {
                    event.setCancelled(true);
                    sendMessage(player, "region-banned");
                    return;
                }

                if (isInCombat(player) && isItemCombatRestrictedInRegion(location, item)) {
                    event.setCancelled(true);
                    long remainingTime = getRemainingCombatTime(player);
                    sendMessage(player, "region-combat", remainingTime);
                    return;
                }
            }

            // Prüfen ob Item komplett gebannt ist
            if (bannedItems.contains(item)) {
                event.setCancelled(true);
                sendMessage(player, "banned-item");
                return;
            }

            // Prüfen ob Spieler im Combat ist und Item restricted ist
            if (isInCombat(player) && combatRestrictedItems.contains(item)) {
                event.setCancelled(true);
                long remainingTime = getRemainingCombatTime(player);
                sendMessage(player, "combat-restriction", remainingTime);
            }
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        Material item = event.getItemDrop().getItemStack().getType();

        // Bypass-Permission prüfen
        if (bannedItems.contains(item) && player.hasPermission("itemban.bypass.banned")) {
            return;
        }
        if (combatRestrictedItems.contains(item) && player.hasPermission("itemban.bypass.combat")) {
            return;
        }

        // WorldGuard Regionen-Check
        if (worldGuardAvailable && worldGuardEnabled) {
            if (isItemBannedInRegion(player.getLocation(), item)) {
                event.setCancelled(true);
                sendMessage(player, "region-banned");
                return;
            }

            if (isInCombat(player) && isItemCombatRestrictedInRegion(player.getLocation(), item)) {
                event.setCancelled(true);
                long remainingTime = getRemainingCombatTime(player);
                sendMessage(player, "region-combat", remainingTime);
                return;
            }
        }

        // Prüfen ob Item komplett gebannt ist
        if (bannedItems.contains(item)) {
            event.setCancelled(true);
            sendMessage(player, "banned-item");
            return;
        }

        // Prüfen ob Spieler im Combat ist und Item restricted ist
        if (isInCombat(player) && combatRestrictedItems.contains(item)) {
            event.setCancelled(true);
            long remainingTime = getRemainingCombatTime(player);
            sendMessage(player, "combat-restriction", remainingTime);
        }
    }

    @EventHandler
    public void onPlayerPickupItem(PlayerPickupItemEvent event) {
        Player player = event.getPlayer();
        Material item = event.getItem().getItemStack().getType();

        // Bypass-Permission prüfen
        if (bannedItems.contains(item) && player.hasPermission("itemban.bypass.banned")) {
            return;
        }

        // WorldGuard Regionen-Check
        if (worldGuardAvailable && worldGuardEnabled) {
            if (isItemBannedInRegion(player.getLocation(), item)) {
                event.setCancelled(true);
                sendMessage(player, "region-banned");
                return;
            }
        }

        // Prüfen ob Item komplett gebannt ist
        if (bannedItems.contains(item)) {
            event.setCancelled(true);
            sendMessage(player, "banned-item");
        }
    }

    // WorldGuard Integration Methoden
    private boolean isItemBannedInRegion(Location location, Material item) {
        if (!worldGuardAvailable || !worldGuardEnabled) {
            return false;
        }

        try {
            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            RegionQuery query = container.createQuery();
            ApplicableRegionSet regions = query.getApplicableRegions(BukkitAdapter.adapt(location));

            for (ProtectedRegion region : regions) {
                String regionName = region.getId().toLowerCase();
                Set<Material> bannedInRegion = regionBannedItems.get(regionName);
                if (bannedInRegion != null && bannedInRegion.contains(item)) {
                    if (debugEnabled) {
                        getLogger().info("Item " + item + " ist in Region " + regionName + " gebannt");
                    }
                    return true;
                }
            }
        } catch (Exception e) {
            if (debugEnabled) {
                getLogger().warning("Fehler beim WorldGuard Region-Check: " + e.getMessage());
            }
        }

        return false;
    }

    private boolean isItemCombatRestrictedInRegion(Location location, Material item) {
        if (!worldGuardAvailable || !worldGuardEnabled) {
            return false;
        }

        try {
            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            RegionQuery query = container.createQuery();
            ApplicableRegionSet regions = query.getApplicableRegions(BukkitAdapter.adapt(location));

            for (ProtectedRegion region : regions) {
                String regionName = region.getId().toLowerCase();
                Set<Material> combatRestrictedInRegion = regionCombatItems.get(regionName);
                if (combatRestrictedInRegion != null && combatRestrictedInRegion.contains(item)) {
                    if (debugEnabled) {
                        getLogger().info("Item " + item + " ist in Region " + regionName + " combat-beschränkt");
                    }
                    return true;
                }
            }
        } catch (Exception e) {
            if (debugEnabled) {
                getLogger().warning("Fehler beim WorldGuard Combat-Region-Check: " + e.getMessage());
            }
        }

        return false;
    }

    // Nachrichten-System
    private void sendMessage(Player player, String messageKey) {
        sendMessage(player, messageKey, -1);
    }

    private void sendMessage(Player player, String messageKey, long remainingTime) {
        if (!shouldSendMessage(player)) {
            return;
        }

        String message = getMessage(messageKey);
        if (remainingTime > 0) {
            message += " " + getMessage("combat-remaining").replace("%time%", String.valueOf(remainingTime));
        }

        player.sendMessage(message);

        if (logInteractions) {
            getLogger().info("Nachricht an " + player.getName() + ": " + messageKey +
                    (remainingTime > 0 ? " (Combat: " + remainingTime + "s)" : ""));
        }
    }

    private boolean shouldSendMessage(Player player) {
        return playerMessagesEnabled.getOrDefault(player.getUniqueId(), messagesEnabled);
    }

    private String getMessage(String key) {
        String message = getConfig().getString("messages." + key);
        if (message == null) {
            // Fallback-Nachrichten
            switch (key) {
                case "banned-item": return "§c§lDieses Item ist nicht erlaubt!";
                case "combat-restriction": return "§c§lDu kannst dieses Item nicht im Combat verwenden!";
                case "combat-remaining": return "§7(%time%s verbleibend)";
                case "region-banned": return "§c§lDieses Item ist in dieser Region nicht erlaubt!";
                case "region-combat": return "§c§lDieses Item ist in dieser Region während Combat nicht erlaubt!";
                case "no-permission": return "§c§lKeine Berechtigung!";
                case "plugin-reloaded": return "§a§lItemBan Plugin wurde neu geladen!";
                case "player-not-found": return "§c§lSpieler nicht gefunden!";
                case "combat-status-yes": return "§e%player% §7ist im Combat (§c%time%s§7 verbleibend)";
                case "combat-status-no": return "§e%player% §7ist §anicht §7im Combat";
                case "help-header": return "§6§l=== ItemBan Hilfe ===";
                case "help-reload": return "§e/itemban reload §7- Plugin neu laden";
                case "help-info": return "§e/itemban info §7- Plugin Informationen anzeigen";
                case "help-combat": return "§e/itemban combat <player> §7- Combat-Status prüfen";
                case "help-toggle": return "§e/itemban toggle §7- Nachrichten an/aus";
                case "info-header": return "§6§l=== ItemBan Info ===";
                case "info-combat-duration": return "§eCombat-Dauer: §f%duration% Sekunden";
                case "info-banned-items": return "§eGebannte Items: §f%count%";
                case "info-combat-items": return "§eCombat-beschränkte Items: §f%count%";
                case "info-players-in-combat": return "§eSpieler im Combat: §f%count%";
                case "info-worldguard": return "§eWorldGuard: §f%status%";
                case "info-messages": return "§eNachrichten: §f%status%";
                default: return "§cUnbekannte Nachricht: " + key;
            }
        }
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    public boolean isInCombat(Player player) {
        Long combatTime = combatPlayers.get(player.getUniqueId());
        if (combatTime == null) return false;

        return (System.currentTimeMillis() - combatTime) < (combatDuration * 1000L);
    }

    public long getRemainingCombatTime(Player player) {
        Long combatTime = combatPlayers.get(player.getUniqueId());
        if (combatTime == null) return 0;

        long elapsed = System.currentTimeMillis() - combatTime;
        long remaining = (combatDuration * 1000L) - elapsed;
        return Math.max(0, remaining / 1000L);
    }

    // Reload-Command für Admins
    public void reloadPlugin() {
        loadConfiguration();
    }

    // Getter-Methoden für Commands
    public int getCombatDuration() {
        return combatDuration;
    }

    public Set<Material> getBannedItems() {
        return new HashSet<>(bannedItems);
    }

    public Set<Material> getCombatRestrictedItems() {
        return new HashSet<>(combatRestrictedItems);
    }

    public Map<UUID, Long> getCombatPlayers() {
        return new HashMap<>(combatPlayers);
    }
}