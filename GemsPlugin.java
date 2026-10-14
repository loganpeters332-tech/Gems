package com.example.gems;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.*;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;

public class GemsPlugin extends JavaPlugin implements Listener, TabCompleter {

    // ------------------------------------------------------------------ gem types

    enum GemType {
        FIRE(Material.BLAZE_POWDER, ChatColor.RED, "Inferno Gem", 0,
                new String[]{"Fireball", "Flame Burst"}, new int[]{25, 50}),
        SPEED(Material.SUGAR, ChatColor.AQUA, "Velocity Gem", 0,
                new String[]{"Haste Rush", "Dash"}, new int[]{30, 20}),
        STRENGTH(Material.EMERALD, ChatColor.GREEN, "Titan Gem", 0,
                new String[]{"Titan Might", "Shockwave"}, new int[]{40, 35}),
        HEALING(Material.GHAST_TEAR, ChatColor.LIGHT_PURPLE, "Life Gem", 0,
                new String[]{"Mend", "Healing Aura"}, new int[]{30, 60}),
        ICE(Material.DIAMOND, ChatColor.BLUE, "Frost Gem", 0,
                new String[]{"Freeze", "Frost Armor"}, new int[]{35, 50}),
        GOLD(Material.GOLD_INGOT, ChatColor.GOLD, "Gold Gem", 1,
                new String[]{"Gold Beam", "Time Freeze", "Launch Slam (toggle, punch)"}, new int[]{45, 60, 40}),
        UPGRADED_GOLD(Material.NETHER_STAR, ChatColor.YELLOW, "Upgraded Gold Gem", 2,
                new String[]{"Radiant Beam", "Absolute Zero", "Meteor Slam (toggle, punch)"}, new int[]{35, 45, 30});

        final Material material;
        final ChatColor color;
        final String displayName;
        final int tier;
        final String[] abilities;
        final int[] cooldowns;

        GemType(Material m, ChatColor c, String n, int tier, String[] a, int[] cd) {
            this.material = m; this.color = c; this.displayName = n;
            this.tier = tier; this.abilities = a; this.cooldowns = cd;
        }

        boolean isBasic() { return tier == 0; }
        boolean isGold() { return tier >= 1; }
    }

    static class GemState {
        GemType type;
        int deaths = 0;
        int extra = 0;
        boolean broken = false;
        int repair = 0;

        GemState(GemType t) { type = t; }
    }

    private NamespacedKey gemKey, deathsKey, extraKey, brokenKey, repairKey, energyKey;
    private NamespacedKey goldRecipeKey, upgradeRecipeKey;

    private final Map<String, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Long> frozen = new HashMap<>();
    private final Set<UUID> armedSlam = new HashSet<>();
    private final Map<UUID, List<ItemStack>> returning = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        gemKey = new NamespacedKey(this, "gem_type");
        deathsKey = new NamespacedKey(this, "deaths");
        extraKey = new NamespacedKey(this, "extra");
        brokenKey = new NamespacedKey(this, "broken");
        repairKey = new NamespacedKey(this, "repair");
        energyKey = new NamespacedKey(this, "energy_item");
        goldRecipeKey = new NamespacedKey(this, "gold_gem");
        upgradeRecipeKey = new NamespacedKey(this, "upgraded_gold_gem");
        registerRecipes();
        getServer().getPluginManager().registerEvents(this, this);
        for (String c : List.of("gem")) {
            PluginCommand pc = getCommand(c);
            if (pc != null) pc.setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        Bukkit.removeRecipe(goldRecipeKey);
        Bukkit.removeRecipe(upgradeRecipeKey);
    }

    private int baseLives() { return getConfig().getInt("base-lives", 5); }
    private int maxExtra() { return getConfig().getInt("max-extra-lives", 5); }
    private int killsToHeal() { return getConfig().getInt("kills-to-heal", 5); }
    private int livesLeft(GemState s) { return Math.max(0, baseLives() + s.extra - s.deaths); }

    // ------------------------------------------------------------------ recipes

    private void registerRecipes() {
        // Gold gem: the 5 basic gems (verified in PrepareItemCraftEvent)
        ShapelessRecipe gold = new ShapelessRecipe(goldRecipeKey, createGem(new GemState(GemType.GOLD)));
        for (GemType t : GemType.values()) if (t.isBasic()) gold.addIngredient(t.material);
        Bukkit.addRecipe(gold);

        // Upgraded gold gem: gold gem + any 4 basic gems
        ShapelessRecipe up = new ShapelessRecipe(upgradeRecipeKey, createGem(new GemState(GemType.UPGRADED_GOLD)));
        up.addIngredient(GemType.GOLD.material);
        List<Material> basics = new ArrayList<>();
        for (GemType t : GemType.values()) if (t.isBasic()) basics.add(t.material);
        for (int i = 0; i < 4; i++) up.addIngredient(new RecipeChoice.MaterialChoice(basics));
        Bukkit.addRecipe(up);
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        if (!(e.getRecipe() instanceof ShapelessRecipe sr)) return;
        boolean gold = sr.getKey().equals(goldRecipeKey);
        boolean upgrade = sr.getKey().equals(upgradeRecipeKey);
        if (!gold && !upgrade) return;

        List<GemState> states = new ArrayList<>();
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (it == null || it.getType().isAir()) continue;
            GemState s = isGem(it) ? readState(it) : null;
            if (s == null) { e.getInventory().setResult(null); return; }
            states.add(s);
        }
        boolean ok = states.size() == 5 && states.stream().noneMatch(s -> s.broken);
        if (ok && gold) {
            Set<GemType> types = new HashSet<>();
            for (GemState s : states) if (s.type.isBasic()) types.add(s.type);
            ok = types.size() == 5;
        }
        if (ok && upgrade) {
            long golds = states.stream().filter(s -> s.type == GemType.GOLD).count();
            long basic = states.stream().filter(s -> s.type.isBasic()).count();
            ok = golds == 1 && basic == 4;
        }
        if (!ok) e.getInventory().setResult(null);
    }

    // ------------------------------------------------------------------ items / state

    private String txt(String s, ChatColor c) {
        return ChatColor.RESET.toString() + c + s;
    }

    private String bold(String s) {
        // s is RESET + color + text; insert BOLD after the color code
        return s.substring(0, 4) + ChatColor.BOLD + s.substring(4);
    }

    private boolean isGem(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(gemKey, PersistentDataType.STRING);
    }

    private boolean isEnergy(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(energyKey, PersistentDataType.BYTE);
    }

    private GemState readState(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        var pdc = it.getItemMeta().getPersistentDataContainer();
        String t = pdc.get(gemKey, PersistentDataType.STRING);
        if (t == null) return null;
        try {
            GemState s = new GemState(GemType.valueOf(t));
            s.deaths = pdc.getOrDefault(deathsKey, PersistentDataType.INTEGER, 0);
            s.extra = pdc.getOrDefault(extraKey, PersistentDataType.INTEGER, 0);
            s.broken = pdc.getOrDefault(brokenKey, PersistentDataType.BYTE, (byte) 0) == 1;
            s.repair = pdc.getOrDefault(repairKey, PersistentDataType.INTEGER, 0);
            return s;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private List<String> gemLore(GemState s) {
        List<String> l = new ArrayList<>();
        if (s.broken) {
            l.add(bold(txt("BROKEN", ChatColor.DARK_RED)));
            l.add(txt("Heal: " + s.repair + "/" + killsToHeal() + " kills", ChatColor.RED));
            l.add(txt("Kill players with gems to steal energy", ChatColor.GRAY));
        } else {
            l.add(txt("Lives left: " + livesLeft(s), ChatColor.GREEN));
            l.add(txt("Extra lives: +" + s.extra + "/" + maxExtra(), ChatColor.YELLOW));
            for (int i = 0; i < s.type.abilities.length; i++) {
                l.add(txt("/ability" + (i + 1) + " - " + s.type.abilities[i]
                        + " (" + s.type.cooldowns[i] + "s)", ChatColor.GRAY));
            }
        }
        return l;
    }

    private ItemStack createGem(GemState s) {
        ItemStack it = new ItemStack(s.type.material);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(bold(txt(s.type.displayName + (s.broken ? " (Broken)" : ""),
                s.broken ? ChatColor.DARK_GRAY : s.type.color)));
        m.setLore(gemLore(s));
        m.setEnchantmentGlintOverride(!s.broken);
        m.setMaxStackSize(1);
        m.setItemModel(new NamespacedKey("gems",
                (s.broken ? "broken_" : "") + s.type.name().toLowerCase()));
        var pdc = m.getPersistentDataContainer();
        pdc.set(gemKey, PersistentDataType.STRING, s.type.name());
        pdc.set(deathsKey, PersistentDataType.INTEGER, s.deaths);
        pdc.set(extraKey, PersistentDataType.INTEGER, s.extra);
        pdc.set(brokenKey, PersistentDataType.BYTE, (byte) (s.broken ? 1 : 0));
        pdc.set(repairKey, PersistentDataType.INTEGER, s.repair);
        it.setItemMeta(m);
        return it;
    }

    private ItemStack createEnergy() {
        ItemStack it = new ItemStack(Material.AMETHYST_SHARD);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(bold(txt("Gem Energy", ChatColor.LIGHT_PURPLE)));
        m.setLore(List.of(
                txt("Right-click to gain +1 extra life", ChatColor.GRAY),
                txt("on your gem (max +" + maxExtra() + ").", ChatColor.GRAY),
                txt("Heals a broken gem one step.", ChatColor.GRAY)));
        m.setEnchantmentGlintOverride(true);
        m.setItemModel(new NamespacedKey("gems", "gem_energy"));
        m.getPersistentDataContainer().set(energyKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    /** The gem a player is currently "using": held working gem, else best gem in inventory. -1 if none. */
    private int findActive(Player p) {
        PlayerInventory inv = p.getInventory();
        int held = inv.getHeldItemSlot();
        ItemStack h = inv.getItem(held);
        if (isGem(h)) {
            GemState s = readState(h);
            if (s != null && !s.broken) return held;
        }
        int best = -1, bestScore = -1;
        ItemStack[] c = inv.getContents();
        for (int i = 0; i < c.length; i++) {
            if (!isGem(c[i])) continue;
            GemState s = readState(c[i]);
            if (s == null) continue;
            int score = (s.broken ? 0 : 100) + s.type.tier;
            if (score > bestScore) { bestScore = score; best = i; }
        }
        return best;
    }

    // ------------------------------------------------------------------ death / kills

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        int slot = findActive(victim);
        if (slot < 0) return;

        PlayerInventory inv = victim.getInventory();
        GemState vs = readState(inv.getItem(slot));
        if (vs == null) return;
        boolean hadWorkingGem = !vs.broken;

        if (!vs.broken) {
            vs.deaths++;
            if (vs.deaths >= baseLives() + vs.extra) {
                vs.broken = true;
                vs.repair = 0;
                victim.sendMessage(txt("Your gem has BROKEN! Kill players with gems to steal energy and heal it.",
                        ChatColor.DARK_RED));
            } else {
                victim.sendMessage(txt("Gem lives left: " + livesLeft(vs), ChatColor.YELLOW));
            }
        }

        // Gems are kept on death (you can still drop them manually with Q)
        ItemStack[] contents = inv.getContents();
        if (e.getKeepInventory()) {
            inv.setItem(slot, createGem(vs));
        } else {
            e.getDrops().removeIf(this::isGem);
            List<ItemStack> keep = new ArrayList<>();
            for (int i = 0; i < contents.length; i++) {
                if (!isGem(contents[i])) continue;
                keep.add(i == slot ? createGem(vs) : contents[i].clone());
            }
            returning.put(victim.getUniqueId(), keep);
        }

        // Kill reward
        Player killer = victim.getKiller();
        if (killer != null && !killer.equals(victim) && hadWorkingGem) {
            int ks = findActive(killer);
            if (ks >= 0) {
                GemState kst = readState(killer.getInventory().getItem(ks));
                if (kst != null) {
                    if (kst.broken) {
                        killer.sendMessage(txt("You stole " + victim.getName() + "'s gem energy!",
                                ChatColor.LIGHT_PURPLE));
                        healStep(killer, ks, kst);
                    } else {
                        e.getDrops().add(createEnergy());
                        killer.sendMessage(txt("Your gem is healthy - " + victim.getName()
                                + "'s energy dropped as an item!", ChatColor.LIGHT_PURPLE));
                    }
                }
            }
        }
    }

    private void healStep(Player p, int slot, GemState s) {
        s.repair++;
        if (s.repair >= killsToHeal()) {
            s.broken = false;
            s.repair = 0;
            s.deaths = 0;
            p.sendMessage(txt("Your gem is fully healed and works again!", ChatColor.GREEN));
        } else {
            p.sendMessage(txt("Gem healing: " + s.repair + "/" + killsToHeal(), ChatColor.GOLD));
        }
        p.getInventory().setItem(slot, createGem(s));
    }

    @EventHandler
    public void onUseEnergy(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        if (!isEnergy(it)) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        int slot = findActive(p);
        if (slot < 0) { p.sendMessage(txt("You don't have a gem.", ChatColor.RED)); return; }
        GemState s = readState(p.getInventory().getItem(slot));
        if (s == null) return;
        if (s.broken) {
            it.setAmount(it.getAmount() - 1);
            healStep(p, slot, s);
            return;
        }
        if (s.extra >= maxExtra()) {
            p.sendMessage(txt("Your gem already has the max +" + maxExtra() + " extra lives.", ChatColor.RED));
            return;
        }
        it.setAmount(it.getAmount() - 1);
        s.extra++;
        p.getInventory().setItem(slot, createGem(s));
        p.sendMessage(txt("+1 extra life! (" + s.extra + "/" + maxExtra() + ")", ChatColor.GREEN));
        p.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, p.getLocation().add(0, 1, 0), 25, 0.5, 0.8, 0.5);
    }

    // ------------------------------------------------------------------ freeze + slam events

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Long until = frozen.get(e.getPlayer().getUniqueId());
        if (until == null) return;
        if (System.currentTimeMillis() > until) { frozen.remove(e.getPlayer().getUniqueId()); return; }
        Location f = e.getFrom(), to = e.getTo();
        if (to == null) return;
        if (f.getX() != to.getX() || f.getY() != to.getY() || f.getZ() != to.getZ()) {
            Location n = to.clone();
            n.setX(f.getX()); n.setY(f.getY()); n.setZ(f.getZ());
            e.setTo(n);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        List<ItemStack> items = returning.remove(e.getPlayer().getUniqueId());
        if (items == null) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> {
            for (ItemStack it : items) {
                Map<Integer, ItemStack> left = p.getInventory().addItem(it);
                for (ItemStack extra : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), extra);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        armedSlam.remove(e.getPlayer().getUniqueId());
        frozen.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPunch(EntityDamageByEntityEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(e.getDamager() instanceof Player p)) return;
        if (!(e.getEntity() instanceof LivingEntity target) || target instanceof ArmorStand) return;
        if (!armedSlam.contains(p.getUniqueId())) return;

        ItemStack held = p.getInventory().getItemInMainHand();
        if (!isGem(held)) return;
        GemState s = readState(held);
        if (s == null || s.broken || !s.type.isGold()) return;
        if (remaining(p, s.type, 3) > 0) return;

        setCooldown(p, s.type, 3);
        boolean up = s.type == GemType.UPGRADED_GOLD;
        Bukkit.getScheduler().runTask(this, () -> slam(p, target, up));
        p.sendMessage(txt(s.type.abilities[2] + "!", s.type.color));
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        String name = cmd.getName().toLowerCase();
        switch (name) {
            case "ability1", "ability2", "ability3" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
                useAbility(p, Integer.parseInt(name.substring(7)));
                return true;
            }
            case "gem" -> { return gemCommand(sender, a); }
        }
        return false;
    }

    private boolean gemCommand(CommandSender s, String[] a) {
        if (a.length == 0) { s.sendMessage("/gem <give|info|reset>"); return true; }
        switch (a[0].toLowerCase()) {
            case "info" -> {
                if (!(s instanceof Player p)) return true;
                int slot = findActive(p);
                if (slot < 0) { s.sendMessage("You have no gem."); return true; }
                GemState st = readState(p.getInventory().getItem(slot));
                s.sendMessage(st.type.displayName + " | deaths " + st.deaths + " | lives left " + livesLeft(st)
                        + " | extra +" + st.extra + " | "
                        + (st.broken ? "BROKEN (" + st.repair + "/" + killsToHeal() + ")" : "working"));
            }
            case "give" -> {
                if (!s.hasPermission("gems.admin")) { s.sendMessage("No permission."); return true; }
                if (a.length < 3) { s.sendMessage("/gem give <player> <type>"); return true; }
                Player target = Bukkit.getPlayerExact(a[1]);
                if (target == null) { s.sendMessage("Player not found."); return true; }
                GemType type;
                try { type = GemType.valueOf(a[2].toUpperCase()); }
                catch (Exception ex) { s.sendMessage("Types: " + Arrays.toString(GemType.values())); return true; }
                target.getInventory().addItem(createGem(new GemState(type)));
                s.sendMessage("Gave " + type + " gem to " + target.getName());
            }
            case "reset" -> {
                if (!s.hasPermission("gems.admin")) { s.sendMessage("No permission."); return true; }
                if (a.length < 2) { s.sendMessage("/gem reset <player>"); return true; }
                Player target = Bukkit.getPlayerExact(a[1]);
                if (target == null) { s.sendMessage("Player not found."); return true; }
                int slot = findActive(target);
                if (slot < 0) { s.sendMessage("They have no gem."); return true; }
                GemState st = readState(target.getInventory().getItem(slot));
                target.getInventory().setItem(slot, createGem(new GemState(st.type)));
                s.sendMessage("Reset " + target.getName() + "'s gem.");
            }
            default -> s.sendMessage("/gem <give|info|reset>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (!c.getName().equalsIgnoreCase("gem")) return List.of();
        if (a.length == 1) return List.of("give", "info", "reset");
        if (a.length == 2 && !a[0].equalsIgnoreCase("info"))
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        if (a.length == 3 && a[0].equalsIgnoreCase("give"))
            return Arrays.stream(GemType.values()).map(g -> g.name().toLowerCase()).toList();
        return List.of();
    }

    // ------------------------------------------------------------------ ability dispatch

    private String ck(Player p, GemType g, int n) { return p.getUniqueId() + ":" + g.name() + ":" + n; }

    private long remaining(Player p, GemType g, int n) {
        Long until = cooldowns.get(ck(p, g, n));
        if (until == null) return 0;
        return Math.max(0, until - System.currentTimeMillis());
    }

    private void setCooldown(Player p, GemType g, int n) {
        cooldowns.put(ck(p, g, n), System.currentTimeMillis() + g.cooldowns[n - 1] * 1000L);
    }

    private void useAbility(Player p, int n) {
        int slot = findActive(p);
        if (slot < 0) { p.sendMessage(txt("You need a gem in your inventory.", ChatColor.RED)); return; }
        GemState s = readState(p.getInventory().getItem(slot));
        if (s == null) return;
        if (s.broken) { p.sendMessage(txt("Your gem is broken! Kill players to heal it.", ChatColor.DARK_RED)); return; }
        if (n > s.type.abilities.length) {
            p.sendMessage(txt("Only Gold gems have /ability3.", ChatColor.RED));
            return;
        }

        // Ability 3 is a toggle: punch someone while holding the gold gem
        if (n == 3) {
            if (armedSlam.remove(p.getUniqueId())) {
                p.sendMessage(txt(s.type.abilities[2] + " OFF", ChatColor.RED));
            } else {
                armedSlam.add(p.getUniqueId());
                p.sendMessage(txt(s.type.abilities[2] + " ON - hold the gem and punch someone", ChatColor.GREEN));
            }
            return;
        }

        long rem = remaining(p, s.type, n);
        if (rem > 0) {
            p.sendMessage(txt("Cooldown: " + (rem / 1000 + 1) + "s", ChatColor.RED));
            return;
        }

        boolean ok = s.type.isGold() ? castGold(p, s.type, n) : castBasic(p, s.type, n);
        if (!ok) return;
        setCooldown(p, s.type, n);
        p.sendMessage(txt("Used " + s.type.abilities[n - 1] + "!", s.type.color));
    }

    // ------------------------------------------------------------------ helpers

    private List<LivingEntity> nearby(Player p, double r) {
        List<LivingEntity> out = new ArrayList<>();
        for (Entity e : p.getNearbyEntities(r, r, r))
            if (e instanceof LivingEntity le && !(e instanceof ArmorStand)) out.add(le);
        return out;
    }

    private void heal(Player p, double amount) {
        double max = p.getAttribute(Attribute.MAX_HEALTH).getValue();
        p.setHealth(Math.min(max, p.getHealth() + amount));
    }

    private void effect(LivingEntity e, PotionEffectType t, int ticks, int amp) {
        e.addPotionEffect(new PotionEffect(t, ticks, amp));
    }

    /** Armor-ignoring magic damage that credits the attacker with the kill. */
    private void hurt(LivingEntity target, double amount, Player attacker) {
        DamageSource src = DamageSource.builder(DamageType.MAGIC)
                .withCausingEntity(attacker).withDirectEntity(attacker).build();
        target.setNoDamageTicks(0);
        target.damage(amount, src);
    }

    // ------------------------------------------------------------------ basic gems

    private boolean castBasic(Player p, GemType g, int n) {
        World w = p.getWorld();
        Location loc = p.getLocation();
        switch (g) {
            case FIRE -> {
                if (n == 1) {
                    Fireball f = p.launchProjectile(Fireball.class);
                    f.setDirection(loc.getDirection());
                    f.setYield(2f);
                    f.setIsIncendiary(true);
                } else {
                    for (LivingEntity e : nearby(p, 6)) e.setFireTicks(120);
                    effect(p, PotionEffectType.FIRE_RESISTANCE, 200, 0);
                    w.spawnParticle(Particle.FLAME, loc.clone().add(0, 1, 0), 120, 3, 1, 3, 0.05);
                    w.playSound(loc, Sound.ITEM_FIRECHARGE_USE, 1f, 1f);
                }
            }
            case SPEED -> {
                if (n == 1) {
                    effect(p, PotionEffectType.SPEED, 200, 2);
                    effect(p, PotionEffectType.JUMP_BOOST, 200, 1);
                } else {
                    p.setVelocity(loc.getDirection().multiply(2.2).setY(0.3));
                    w.spawnParticle(Particle.CLOUD, loc, 30, 0.3, 0.3, 0.3, 0.05);
                }
            }
            case STRENGTH -> {
                if (n == 1) {
                    effect(p, PotionEffectType.STRENGTH, 200, 1);
                    effect(p, PotionEffectType.RESISTANCE, 200, 0);
                } else {
                    for (LivingEntity e : nearby(p, 6)) {
                        hurt(e, 6, p);
                        Vector v = e.getLocation().toVector().subtract(loc.toVector()).normalize().multiply(1.5).setY(0.5);
                        e.setVelocity(v);
                    }
                    w.spawnParticle(Particle.EXPLOSION, loc, 3, 1, 0.2, 1, 0);
                    w.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f);
                }
            }
            case HEALING -> {
                if (n == 1) {
                    heal(p, 10);
                    effect(p, PotionEffectType.REGENERATION, 160, 1);
                } else {
                    heal(p, 8);
                    effect(p, PotionEffectType.ABSORPTION, 200, 1);
                    for (LivingEntity e : nearby(p, 8)) {
                        if (e instanceof Player o) {
                            heal(o, 8);
                            effect(o, PotionEffectType.ABSORPTION, 200, 1);
                        }
                    }
                    w.spawnParticle(Particle.HEART, loc.clone().add(0, 1, 0), 30, 3, 1, 3, 0);
                }
            }
            case ICE -> {
                if (n == 1) {
                    for (LivingEntity e : nearby(p, 8)) {
                        effect(e, PotionEffectType.SLOWNESS, 100, 3);
                        e.setFreezeTicks(200);
                    }
                    w.spawnParticle(Particle.SNOWFLAKE, loc.clone().add(0, 1, 0), 120, 4, 1, 4, 0.02);
                } else {
                    effect(p, PotionEffectType.RESISTANCE, 200, 1);
                    for (LivingEntity e : nearby(p, 6)) {
                        effect(e, PotionEffectType.SLOWNESS, 60, 1);
                        effect(e, PotionEffectType.WEAKNESS, 160, 1);
                    }
                    w.spawnParticle(Particle.SNOWFLAKE, loc.clone().add(0, 1, 0), 80, 2, 1, 2, 0.02);
                }
            }
            default -> { return false; }
        }
        return true;
    }

    // ------------------------------------------------------------------ gold gems

    private boolean castGold(Player p, GemType g, int n) {
        boolean up = g == GemType.UPGRADED_GOLD;
        if (n == 1) { beam(p, up); return true; }
        if (n == 2) return timeFreeze(p, up);
        return false;
    }

    /** Beam: 6 hearts total (12 dmg) for Gold, 10 hearts total (20 dmg) for Upgraded, if you keep it on target. */
    private void beam(Player p, boolean up) {
        final double total = up ? 20 : 12;
        final int pulses = up ? 12 : 8;
        final double perPulse = total / pulses;
        final double range = up ? 50 : 30;
        final double size = up ? 1.0 : 0.5;
        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.5f);

        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead() || i >= pulses) { cancel(); return; }
                World w = p.getWorld();
                Location eye = p.getEyeLocation();
                Vector dir = eye.getDirection().normalize();
                RayTraceResult r = w.rayTrace(eye, dir, range, FluidCollisionMode.NEVER, true, size,
                        en -> en instanceof LivingEntity && !en.equals(p) && !(en instanceof ArmorStand));
                double dist = range;
                if (r != null) {
                    dist = eye.toVector().distance(r.getHitPosition());
                    if (r.getHitEntity() instanceof LivingEntity le) hurt(le, perPulse, p);
                }
                Color col = up ? Color.YELLOW : Color.ORANGE;
                for (double d = 1; d < dist; d += 0.5) {
                    Location pt = eye.clone().add(dir.clone().multiply(d)).subtract(0, 0.2, 0);
                    w.spawnParticle(Particle.DUST, pt, 1, 0, 0, 0, 0, new Particle.DustOptions(col, 1.5f));
                    if (up && ((int) (d * 2)) % 4 == 0) w.spawnParticle(Particle.END_ROD, pt, 1, 0, 0, 0, 0);
                }
                i++;
            }
        }.runTaskTimer(this, 0, 4);
    }

    /** Freezes the targeted player in place: 2s (Gold) / 4s + nearby players (Upgraded). */
    private boolean timeFreeze(Player p, boolean up) {
        World w = p.getWorld();
        Location eye = p.getEyeLocation();
        RayTraceResult r = w.rayTrace(eye, eye.getDirection(), up ? 50 : 30, FluidCollisionMode.NEVER, true,
                up ? 1.5 : 1.0,
                en -> en instanceof Player pl && !pl.equals(p) && pl.getGameMode() != GameMode.SPECTATOR);
        if (r == null || !(r.getHitEntity() instanceof Player target)) {
            p.sendMessage(txt("No player in sight.", ChatColor.RED));
            return false;
        }
        int ticks = up ? 80 : 40;
        freezePlayer(target, ticks);
        if (up) {
            for (Player o : target.getWorld().getPlayers()) {
                if (!o.equals(p) && !o.equals(target) && o.getLocation().distance(target.getLocation()) <= 6)
                    freezePlayer(o, ticks);
            }
        }
        return true;
    }

    private void freezePlayer(Player t, int ticks) {
        frozen.put(t.getUniqueId(), System.currentTimeMillis() + ticks * 50L);
        t.setFreezeTicks(t.getMaxFreezeTicks());
        t.getWorld().spawnParticle(Particle.SNOWFLAKE, t.getLocation().add(0, 1, 0), 60, 0.5, 1, 0.5, 0.02);
        t.getWorld().playSound(t.getLocation(), Sound.BLOCK_GLASS_BREAK, 1f, 0.6f);
        t.sendMessage(txt("You have been frozen!", ChatColor.AQUA));
    }

    /** Launch up, then slam down very fast with an impact hit. */
    private void slam(Player p, LivingEntity t, boolean up) {
        t.setVelocity(new Vector(0, up ? 1.9 : 1.5, 0));
        t.getWorld().playSound(t.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.5f);

        new BukkitRunnable() {
            int tick = 0;
            boolean down = false;
            int downTick = 0;
            double lastY = t.getLocation().getY();

            @Override
            public void run() {
                tick++;
                if (t.isDead() || !t.isValid()) { cancel(); return; }
                if (!down) {
                    double y = t.getLocation().getY();
                    if ((tick >= 3 && y <= lastY) || tick >= 30) {
                        down = true;
                        downTick = tick;
                        t.setVelocity(new Vector(0, -3.5, 0));
                    }
                    lastY = y;
                    return;
                }
                t.setFallDistance(0);
                t.setVelocity(new Vector(0, -3.5, 0));
                int since = tick - downTick;
                if ((since >= 2 && t.isOnGround()) || since >= 60) {
                    Location l = t.getLocation();
                    hurt(t, up ? 14 : 8, p);
                    World w = t.getWorld();
                    w.spawnParticle(Particle.EXPLOSION, l, up ? 6 : 2, 0.8, 0.2, 0.8, 0);
                    w.playSound(l, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.7f);
                    if (up) {
                        for (Entity en : t.getNearbyEntities(4, 3, 4)) {
                            if (en instanceof LivingEntity le && !en.equals(p) && !(en instanceof ArmorStand)) {
                                hurt(le, 6, p);
                                le.setVelocity(le.getLocation().toVector().subtract(l.toVector()).normalize()
                                        .multiply(1.2).setY(0.6));
                            }
                        }
                    }
                    cancel();
                }
            }
        }.runTaskTimer(this, 1, 1);
    }
}
