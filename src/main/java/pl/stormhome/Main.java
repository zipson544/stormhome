package pl.stormhome;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class Main extends JavaPlugin implements Listener {

    private static final String PREFIX = "&8[&6Domki&8] ";
    private static final int[] HOME_SLOTS = {19, 20, 21, 22, 23, 24, 25};
    private static final int BACK_SLOT = 40;
    private static final int COUNTDOWN = 3;
    private static final int EC_COUNT = 5;
    private static final int EC_SIZE = 45;
    private static final int[] EC_SLOTS = {20, 21, 22, 23, 24};

    private static final Material[] ICONS = {
            Material.LIME_BED, Material.GRASS_BLOCK, Material.OAK_LOG, Material.CHEST, Material.ENDER_CHEST,
            Material.CRAFTING_TABLE, Material.FURNACE, Material.ANVIL, Material.ENCHANTING_TABLE, Material.BEACON,
            Material.DIAMOND_BLOCK, Material.EMERALD_BLOCK, Material.GOLD_BLOCK, Material.IRON_BLOCK,
            Material.NETHERITE_BLOCK, Material.OBSIDIAN, Material.TNT, Material.NETHER_STAR,
            Material.DIAMOND_SWORD, Material.DIAMOND_PICKAXE, Material.ENDER_PEARL, Material.GOLDEN_APPLE,
            Material.COMPASS, Material.CLOCK, Material.BOOK, Material.BLAZE_ROD, Material.CAKE
    };

    enum Type { HOMES, MANAGE, ICONS, TRASH, EC_MENU, EC }

    static class Gui implements InventoryHolder {
        final Type type;
        final int home;
        Inventory inv;
        Gui(Type type, int home) { this.type = type; this.home = home; }
        @Override public Inventory getInventory() { return inv; }
    }

    static class Tp {
        BukkitTask task;
        Location origin;
    }

    private final Map<UUID, Tp> pending = new HashMap<>();
    private File dataFile;
    private YamlConfiguration data;
    private File ecFile;
    private YamlConfiguration ecData;

    @Override
    public void onEnable() {
        dataFile = new File(getDataFolder(), "homes.yml");
        getDataFolder().mkdirs();
        data = YamlConfiguration.loadConfiguration(dataFile);
        ecFile = new File(getDataFolder(), "enderchests.yml");
        ecData = YamlConfiguration.loadConfiguration(ecFile);
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        for (Player pl : Bukkit.getOnlinePlayers()) {
            if (pl.getOpenInventory().getTopInventory().getHolder() instanceof Gui) pl.closeInventory();
        }
        save();
        saveEcFile();
    }

    private void saveEcFile() {
        try {
            ecData.save(ecFile);
        } catch (IOException e) {
            getLogger().severe("Nie udalo sie zapisac enderchests.yml: " + e.getMessage());
        }
    }

    private void save() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Nie udalo sie zapisac homes.yml: " + e.getMessage());
        }
    }

    // ---------- helpers ----------

    private static Component c(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s)
                .decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack item(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(c(name));
        if (lore.length > 0) {
            List<Component> l = new ArrayList<>();
            for (String s : lore) l.add(c(s));
            meta.lore(l);
        }
        it.setItemMeta(meta);
        return it;
    }

    private void msg(Player p, String s) {
        p.sendMessage(c(PREFIX + s));
    }

    /** 0 = gracz, 1 = vip, 2 = svip, 3 = gvip */
    private int level(Player p) {
        if (p.hasPermission("stormhome.gvip")) return 3;
        if (p.hasPermission("stormhome.svip")) return 2;
        if (p.hasPermission("stormhome.vip")) return 1;
        return 0;
    }

    private int limit(Player p) {
        return new int[]{2, 3, 5, 7}[level(p)];
    }

    private int ecAllowed(Player p) {
        return 2 + level(p);
    }

    private static String ecRank(int n) {
        return switch (n) {
            case 3 -> "&aVIP";
            case 4 -> "&bSVIP";
            case 5 -> "&6GVIP";
            default -> "&7GRACZ";
        };
    }

    private String path(Player p, int n) {
        return "homes." + p.getUniqueId() + "." + n;
    }

    private ConfigurationSection home(Player p, int n) {
        return data.getConfigurationSection(path(p, n));
    }

    private Material iconOf(ConfigurationSection s) {
        Material m = Material.matchMaterial(s.getString("icon", "LIME_BED"));
        return m == null ? Material.LIME_BED : m;
    }

    // ---------- commands ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Tylko dla graczy.");
            return true;
        }
        switch (cmd.getName().toLowerCase()) {
            case "home" -> {
                if (!p.hasPermission("stormhome.home")) { msg(p, "&cBrak uprawnien."); return true; }
                if (args.length > 0) {
                    try {
                        int n = Integer.parseInt(args[0]);
                        if (n < 1 || n > 7 || n > limit(p)) { msg(p, "&cNie masz dostepu do tego domku."); return true; }
                        if (home(p, n) == null) { msg(p, "&cDomek #" + n + " nie jest zapisany."); return true; }
                        startTeleport(p, n);
                    } catch (NumberFormatException e) {
                        msg(p, "&cUzycie: /home [numer]");
                    }
                    return true;
                }
                openHomes(p);
            }
            case "kosz" -> {
                if (!p.hasPermission("stormhome.kosz")) { msg(p, "&cBrak uprawnien."); return true; }
                Gui g = new Gui(Type.TRASH, 0);
                g.inv = Bukkit.createInventory(g, 54, c("&8Kosz"));
                p.openInventory(g.inv);
            }
            case "ec" -> {
                if (!p.hasPermission("stormhome.ec")) { msg(p, "&cBrak uprawnien."); return true; }
                openEcMenu(p);
            }
            case "repairall" -> {
                if (!p.hasPermission("stormhome.repairall")) { msg(p, "&cBrak uprawnien."); return true; }
                ItemStack[] contents = p.getInventory().getContents();
                int n = 0;
                for (int i = 0; i < contents.length; i++) {
                    ItemStack it = contents[i];
                    if (it == null || it.getType().isAir()) continue;
                    ItemMeta meta = it.getItemMeta();
                    if (meta instanceof Damageable d && d.getDamage() > 0) {
                        d.setDamage(0);
                        it.setItemMeta(meta);
                        contents[i] = it;
                        n++;
                    }
                }
                p.getInventory().setContents(contents);
                msg(p, n == 0 ? "&7Nie masz nic do naprawy." : "&aNaprawiono &f" + n + " &aprzedmiotow (razem ze zbroja).");
            }
        }
        return true;
    }

    // ---------- GUI ----------

    private void openHomes(Player p) {
        Gui g = new Gui(Type.HOMES, 0);
        Inventory inv = Bukkit.createInventory(g, 45, c("&8Twoje domki"));
        g.inv = inv;
        int lim = limit(p);
        for (int n = 1; n <= 7; n++) {
            int slot = HOME_SLOTS[n - 1];
            ConfigurationSection s = home(p, n);
            if (s != null) {
                List<String> lore = new ArrayList<>();
                lore.add("&7» Kliknij &fLEWYM&7, aby sie przeteleportowac");
                lore.add("&7» Kliknij &fPRAWYM&7, aby zarzadzac domkiem");
                if (s.getBoolean("coords", false)) {
                    lore.add("");
                    lore.add("&7Swiat: &f" + s.getString("world"));
                    lore.add("&7XYZ: &f" + (int) s.getDouble("x") + " " + (int) s.getDouble("y") + " " + (int) s.getDouble("z"));
                }
                inv.setItem(slot, item(iconOf(s), "&eDomek #" + n, lore.toArray(new String[0])));
            } else if (n <= lim) {
                inv.setItem(slot, item(Material.WHITE_BED, "&eDomek #" + n,
                        "&7» Kliknij &fLEWYM&7, aby zapisac lokalizacje"));
            } else {
                inv.setItem(slot, item(Material.RED_BED, "&eDomek #" + n,
                        "&c» Nie posiadasz wystarczajacej rangi"));
            }
        }
        inv.setItem(BACK_SLOT, item(Material.BARRIER, "&cZamknij"));
        p.openInventory(inv);
    }

    private void openManage(Player p, int n) {
        ConfigurationSection s = home(p, n);
        if (s == null) { openHomes(p); return; }
        Gui g = new Gui(Type.MANAGE, n);
        Inventory inv = Bukkit.createInventory(g, 45, c("&8Domek #" + n));
        g.inv = inv;
        inv.setItem(21, item(Material.STRUCTURE_VOID, "&6Zmien ikonke",
                "&7» Mozesz wybrac ikonke domku", "", "&7» Kliknij &fLEWYM&7, aby zmienic"));
        boolean coords = s.getBoolean("coords", false);
        inv.setItem(22, item(Material.IRON_NUGGET, "&6Wyswietlanie koordynatow",
                "&7» Aktualnie: " + (coords ? "&atak" : "&cnie"), "", "&7» Kliknij &fLEWYM&7, aby zmienic"));
        inv.setItem(23, item(Material.RED_TERRACOTTA, "&cUsun domek",
                "&7» Kliknij &fLEWYM&7, aby usunac domek"));
        inv.setItem(BACK_SLOT, item(Material.BARRIER, "&cPowrot"));
        p.openInventory(inv);
    }

    private void openIcons(Player p, int n) {
        Gui g = new Gui(Type.ICONS, n);
        Inventory inv = Bukkit.createInventory(g, 45, c("&8Ikonka domku #" + n));
        g.inv = inv;
        for (int i = 0; i < ICONS.length; i++) {
            inv.setItem(i, item(ICONS[i], "&eWybierz ikonke", "&7» Kliknij &fLEWYM&7, aby wybrac"));
        }
        inv.setItem(BACK_SLOT, item(Material.BARRIER, "&cPowrot"));
        p.openInventory(inv);
    }

    // ---------- Enderchest (5 stron) ----------

    private void openEcMenu(Player p) {
        Gui g = new Gui(Type.EC_MENU, 0);
        Inventory inv = Bukkit.createInventory(g, 45, c("&8Enderchest"));
        g.inv = inv;
        ItemStack red = item(Material.RED_STAINED_GLASS_PANE, " ");
        ItemStack white = item(Material.WHITE_STAINED_GLASS_PANE, " ");
        for (int s = 0; s < 45; s++) {
            int row = s / 9, col = s % 9;
            if (row == 0 || row == 4 || col == 0 || col == 8) {
                inv.setItem(s, ((row + col) % 2 == 0) ? red : white);
            }
        }
        int allowed = ecAllowed(p);
        for (int n = 1; n <= EC_COUNT; n++) {
            if (n <= allowed) {
                inv.setItem(EC_SLOTS[n - 1], item(Material.ENDER_CHEST, "&dEnderchest #" + n,
                        "&7» Enderchest dostepny od rangi " + ecRank(n),
                        "&7» Miejsce: &f" + EC_SIZE + " slotow",
                        "",
                        "&a» Kliknij &fLEWYM&a, aby otworzyc!"));
            } else {
                inv.setItem(EC_SLOTS[n - 1], item(Material.ENDER_CHEST, "&dEnderchest #" + n,
                        "&7» Enderchest dostepny od rangi " + ecRank(n),
                        "&7» Miejsce: &f" + EC_SIZE + " slotow",
                        "",
                        "&c» Aby zyskac dostep, zakup range pod /portfel"));
            }
        }
        p.openInventory(inv);
    }

    private String ecPath(Player p, int n) {
        return "ec." + p.getUniqueId() + "." + n;
    }

    private void openEc(Player p, int n) {
        if (n < 1 || n > EC_COUNT) return;
        if (n > ecAllowed(p)) {
            msg(p, "&cAby zyskac dostep do Enderchesta #" + n + ", zakup range pod /portfel");
            return;
        }
        Gui g = new Gui(Type.EC, n);
        Inventory inv = Bukkit.createInventory(g, 54, c("&8EnderChest"));
        g.inv = inv;

        List<?> raw = ecData.getList(ecPath(p, n));
        if (raw != null) {
            for (int i = 0; i < raw.size() && i < EC_SIZE; i++) {
                if (raw.get(i) instanceof ItemStack is) inv.setItem(i, is);
            }
        } else if (n == 1) {
            // jednorazowa migracja zawartosci zwyklego enderchesta (27 slotow)
            ItemStack[] vanilla = p.getEnderChest().getContents();
            boolean any = false;
            for (int i = 0; i < vanilla.length && i < EC_SIZE; i++) {
                if (vanilla[i] != null && !vanilla[i].getType().isAir()) {
                    inv.setItem(i, vanilla[i]);
                    any = true;
                }
            }
            if (any) {
                saveEc(p, 1, inv);
                p.getEnderChest().clear();
            }
        }

        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int s = EC_SIZE; s < 54; s++) inv.setItem(s, filler);
        if (n > 1) inv.setItem(45, item(Material.RED_DYE, "&cPoprzednia strona"));
        inv.setItem(49, item(Material.BARRIER, "&cPowrot do menu"));
        if (n < EC_COUNT) inv.setItem(53, item(Material.LIME_DYE, "&aNastepna strona"));
        p.openInventory(inv);
    }

    private void saveEc(Player p, int n, Inventory inv) {
        List<ItemStack> list = new ArrayList<>();
        for (int i = 0; i < EC_SIZE; i++) list.add(inv.getItem(i));
        ecData.set(ecPath(p, n), list);
        saveEcFile();
    }

    private void handleEcClick(InventoryClickEvent e, Gui g) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) { e.setCancelled(true); return; }
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getRawSlot();
        if (slot < EC_SIZE) return;
        e.setCancelled(true);
        if (!e.isLeftClick()) return;
        int n = g.home;
        if (slot == 45 && n > 1) openEc(p, n - 1);
        else if (slot == 53 && n < EC_COUNT) openEc(p, n + 1);
        else if (slot == 49) openEcMenu(p);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        if (e.getClickedBlock() == null || e.getClickedBlock().getType() != Material.ENDER_CHEST) return;
        Player p = e.getPlayer();
        if (p.isSneaking() && e.getItem() != null) return; // pozwol stawiac bloki
        e.setCancelled(true);
        if (p.hasPermission("stormhome.ec")) openEcMenu(p);
    }

    // ---------- teleport z odliczaniem ----------

    private void startTeleport(Player p, int n) {
        ConfigurationSection s = home(p, n);
        if (s == null) return;
        World w = Bukkit.getWorld(s.getString("world", ""));
        if (w == null) { msg(p, "&cSwiat tego domku nie istnieje."); return; }
        Location target = new Location(w, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));

        cancelTp(p.getUniqueId());
        p.closeInventory();

        Tp tp = new Tp();
        tp.origin = p.getLocation();
        final int[] left = {COUNTDOWN};
        tp.task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!p.isOnline()) { cancelTp(p.getUniqueId()); return; }
                if (left[0] <= 0) {
                    pending.remove(p.getUniqueId());
                    cancel();
                    p.teleport(target);
                    msg(p, "&aPrzeteleportowano do domku &f#" + n + "&a.");
                    return;
                }
                p.sendActionBar(c("&fZostaniesz przeteleportowany za &a" + left[0] + "s&f!"));
                p.sendMessage(c("&fZostaniesz przeteleportowany za &a" + left[0] + "s&f!"));
                left[0]--;
            }
        }.runTaskTimer(this, 0L, 20L);
        pending.put(p.getUniqueId(), tp);
    }

    private void cancelTp(UUID id) {
        Tp tp = pending.remove(id);
        if (tp != null && tp.task != null) tp.task.cancel();
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Tp tp = pending.get(e.getPlayer().getUniqueId());
        if (tp == null || e.getTo() == null) return;
        Location a = tp.origin, b = e.getTo();
        if (a.getBlockX() != b.getBlockX() || a.getBlockY() != b.getBlockY() || a.getBlockZ() != b.getBlockZ()) {
            cancelTp(e.getPlayer().getUniqueId());
            msg(e.getPlayer(), "&cTeleportacja anulowana, poruszyles sie.");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cancelTp(e.getPlayer().getUniqueId());
    }

    // ---------- eventy GUI ----------

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Gui g)) return;
        if (g.type == Type.TRASH) return;
        if (g.type == Type.EC) {
            for (int s : e.getRawSlots()) {
                if (s >= EC_SIZE && s < 54) { e.setCancelled(true); return; }
            }
            return;
        }
        e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof Gui eg && eg.type == Type.EC
                && e.getPlayer() instanceof Player ep) {
            saveEc(ep, eg.home, e.getInventory());
            return;
        }
        if (e.getInventory().getHolder() instanceof Gui g && g.type == Type.TRASH) {
            boolean had = false;
            for (ItemStack it : e.getInventory().getContents()) {
                if (it != null && !it.getType().isAir()) { had = true; break; }
            }
            e.getInventory().clear();
            if (had && e.getPlayer() instanceof Player p) {
                msg(p, "&7Zawartosc kosza zostala usunieta.");
            }
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Gui g)) return;
        if (g.type == Type.TRASH) return; // kosz dziala jak zwykly pojemnik
        if (g.type == Type.EC) { handleEcClick(e, g); return; }
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;

        int slot = e.getRawSlot();
        boolean left = e.isLeftClick();
        boolean right = e.isRightClick();

        switch (g.type) {
            case HOMES -> {
                if (slot == BACK_SLOT) { p.closeInventory(); return; }
                int idx = -1;
                for (int i = 0; i < HOME_SLOTS.length; i++) if (HOME_SLOTS[i] == slot) idx = i;
                if (idx < 0) return;
                int n = idx + 1;
                ConfigurationSection s = home(p, n);
                if (s != null) {
                    if (left) startTeleport(p, n);
                    else if (right) openManage(p, n);
                } else if (n <= limit(p) && left) {
                    Location l = p.getLocation();
                    String base = path(p, n);
                    data.set(base + ".world", l.getWorld().getName());
                    data.set(base + ".x", l.getX());
                    data.set(base + ".y", l.getY());
                    data.set(base + ".z", l.getZ());
                    data.set(base + ".yaw", (double) l.getYaw());
                    data.set(base + ".pitch", (double) l.getPitch());
                    data.set(base + ".coords", false);
                    save();
                    msg(p, "&aZapisano domek &f#" + n + "&a.");
                    openHomes(p);
                }
            }
            case MANAGE -> {
                if (!left) return;
                int n = g.home;
                if (slot == BACK_SLOT) openHomes(p);
                else if (slot == 21) openIcons(p, n);
                else if (slot == 22) {
                    String key = path(p, n) + ".coords";
                    data.set(key, !data.getBoolean(key, false));
                    save();
                    openManage(p, n);
                } else if (slot == 23) {
                    data.set(path(p, n), null);
                    save();
                    msg(p, "&cUsunieto domek &f#" + n + "&c.");
                    openHomes(p);
                }
            }
            case EC_MENU -> {
                for (int i = 0; i < EC_SLOTS.length; i++) {
                    if (EC_SLOTS[i] == slot && left) openEc(p, i + 1);
                }
            }
            case ICONS -> {
                if (!left) return;
                int n = g.home;
                if (slot == BACK_SLOT) { openManage(p, n); return; }
                if (slot >= 0 && slot < ICONS.length && home(p, n) != null) {
                    data.set(path(p, n) + ".icon", ICONS[slot].name());
                    save();
                    msg(p, "&aZmieniono ikonke domku &f#" + n + "&a.");
                    openManage(p, n);
                }
            }
            default -> { }
        }
    }
}
