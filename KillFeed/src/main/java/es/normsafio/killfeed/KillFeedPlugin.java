package es.normsafio.killfeed;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/**
 * KillFeed v2: tarjetas de muerte animadas pegadas al borde derecho.
 * Boss bar sin dibujo (sprites transparentes del pack v14) + fuentes normsafio:kf0..kf4
 * (cada una con una altura distinta) con texto, iconos, panel, brillo, chispas y barra de tiempo.
 */
public final class KillFeedPlugin extends JavaPlugin implements Listener, TabExecutor {

    // ---- maquetacion (px de GUI)
    static final int ACCENT_W = 3, ICON_X = 5, ICON_BLOCK = 20, TEXT_X = 30, RPAD = 8;
    static final int[] ICON_H = {16, 22, 28};
    static final char PANEL8 = '\uE180', PANEL1 = '\uE181', BAR8 = '\uE182', BAR1 = '\uE183', SHINE = '\uE185';
    static final char BACK = '\uE300'; // -1 px
    static final char[] SPARK = {'\uE190', '\uE191', '\uE192', '\uE193'};
    static final int[] SPARK_ADV = {9, 13, 9, 13};

    // ---- colores
    static final int GOLD = 0xFFD23F, RED = 0xFF6B6B, WHITE = 0xFFFFFF;
    static final int TINT_NORMAL = 0x303040, TINT_YOU = 0x2E8F4E, TINT_ME = 0x9A2A2A;
    static final int ACC_NORMAL = 0xFFB52E, ACC_YOU = 0x3DFF7A, ACC_ME = 0xFF3D3D;

    enum Icon { PLUMA, ESPADA, HACHA, ARCO, TNT, RADIACION, CALAVERA, FUEGO, LAVA, AGUA, VACIO, TRIDENTE;
        char glyph(int frame) { return (char) (0xE100 + ordinal() * 3 + frame); }
    }
    enum Role { TEXT, ATTACKER, VICTIM }
    enum Kind { NORMAL, YOU, ME }
    record Part(Component base, int width, Role role) {}
    record Msg(List<Part> parts, Icon icon, UUID killer, UUID victim, int streak) {}
    static final class Line {
        final BossBar bar; final Msg msg; final Kind kind; int age = 0;
        Line(BossBar bar, Msg msg, Kind kind) { this.bar = bar; this.msg = msg; this.kind = kind; }
    }

    private final Map<UUID, List<Line>> feeds = new HashMap<>();
    private final Map<UUID, long[]> streaks = new HashMap<>();
    private final Set<UUID> packOk = new HashSet<>();
    private NamespacedKey keyRadius, keyOff, keyForce, keyPos;
    private int life, maxLines, defaultRadius, defaultPos;
    private long streakMs;
    private boolean onlyWithPack, hideChat, sounds;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        keyRadius = new NamespacedKey(this, "radio");
        keyOff = new NamespacedKey(this, "off");
        keyForce = new NamespacedKey(this, "forzar");
        keyPos = new NamespacedKey(this, "pos");
        loadSettings();
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("killfeed")).setExecutor(this);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
    }

    @Override
    public void onDisable() {
        for (Map.Entry<UUID, List<Line>> en : feeds.entrySet()) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p != null) for (Line l : en.getValue()) p.hideBossBar(l.bar);
        }
        feeds.clear();
    }

    private void loadSettings() {
        reloadConfig();
        life = Math.max(40, getConfig().getInt("duracion-ticks", 100));
        maxLines = Math.max(1, Math.min(5, getConfig().getInt("max-lineas", 4)));
        defaultRadius = getConfig().getInt("mitad-ancho-gui", 240);
        defaultPos = Math.max(0, Math.min(4, getConfig().getInt("posicion-vertical", 2)));
        streakMs = getConfig().getInt("racha-segundos", 12) * 1000L;
        onlyWithPack = getConfig().getBoolean("solo-con-resourcepack", true);
        hideChat = getConfig().getBoolean("ocultar-mensaje-chat", false);
        sounds = getConfig().getBoolean("sonidos", true);
    }

    // ------------------------------------------------------------------ eventos

    @EventHandler
    public void onPack(PlayerResourcePackStatusEvent e) {
        if (e.getStatus() == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) packOk.add(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        packOk.remove(id);
        feeds.remove(id);
        streaks.remove(id);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) {
        Player v = e.getPlayer();
        EntityDamageEvent last = v.getLastDamageCause();
        EntityDamageByEntityEvent byEntity = last instanceof EntityDamageByEntityEvent b ? b : null;
        String plain = e.deathMessage() == null ? "" : PlainTextComponentSerializer.plainText().serialize(e.deathMessage()).toLowerCase();

        Player killer = v.getKiller();
        if (killer == null && byEntity != null && byEntity.getDamager() instanceof TNTPrimed t && t.getSource() instanceof Player s) killer = s;
        if (killer != null && killer.equals(v)) killer = null;
        streaks.remove(v.getUniqueId());

        Msg msg;
        if (plain.contains("radia")) {
            msg = death(v.getName(), "ha muerto por radiación", Icon.RADIACION, v.getUniqueId());
        } else if (killer != null) {
            msg = killMsg(killer, v, byEntity);
        } else if (byEntity != null) {
            msg = mobMsg(v, byEntity);
        } else {
            msg = causeMsg(v, last == null ? null : last.getCause());
        }
        show(msg);
        if (hideChat) e.deathMessage(null);
    }

    // ------------------------------------------------------------------ construccion de mensajes

    private Part txt(String s) {
        return new Part(Component.text(s, TextColor.color(WHITE)), Metrics.width(s), Role.TEXT);
    }

    private Part name(String s, int color, Role role) {
        return new Part(Component.text(s, TextColor.color(color)), Metrics.width(s), role);
    }

    private Msg death(String victim, String tail, Icon icon, UUID victimId) {
        List<Part> parts = new ArrayList<>();
        parts.add(name(victim, RED, Role.VICTIM));
        parts.add(txt(" " + tail));
        return new Msg(parts, icon, null, victimId, 0);
    }

    private Msg kill(String killer, String victim, String tail, Icon icon, UUID killerId, UUID victimId, int streak) {
        List<Part> parts = new ArrayList<>();
        parts.add(name(killer, GOLD, Role.ATTACKER));
        parts.add(txt(" ha matado a "));
        parts.add(name(victim, RED, Role.VICTIM));
        parts.add(txt(" " + tail));
        if (streak >= 2) parts.add(name(" x" + streak, GOLD, Role.TEXT));
        return new Msg(parts, icon, killerId, victimId, streak);
    }

    private int streak(UUID id) {
        long now = System.currentTimeMillis();
        long[] s = streaks.computeIfAbsent(id, k -> new long[]{0, 0});
        if (now - s[1] > streakMs) s[0] = 0;
        s[0]++;
        s[1] = now;
        return (int) s[0];
    }

    private Msg killMsg(Player killer, Player v, EntityDamageByEntityEvent byEntity) {
        String tail;
        Icon icon;
        Entity damager = byEntity == null ? null : byEntity.getDamager();
        if (damager instanceof Trident) {
            icon = Icon.TRIDENTE; tail = "con tridente" + dist(killer, v);
        } else if (damager instanceof AbstractArrow) {
            Material hand = killer.getInventory().getItemInMainHand().getType();
            icon = Icon.ARCO; tail = (hand == Material.CROSSBOW ? "con ballesta" : "con arco") + dist(killer, v);
        } else if (damager instanceof Projectile) {
            icon = Icon.CALAVERA; tail = "con un proyectil" + dist(killer, v);
        } else if (damager instanceof TNTPrimed) {
            icon = Icon.TNT; tail = "con TNT";
        } else {
            Material hand = killer.getInventory().getItemInMainHand().getType();
            String n = hand.name();
            if (n.endsWith("_SWORD")) { icon = Icon.ESPADA; tail = "con espada"; }
            else if (n.endsWith("_AXE")) { icon = Icon.HACHA; tail = "con hacha"; }
            else if (hand == Material.MACE) { icon = Icon.HACHA; tail = "con maza"; }
            else if (hand == Material.TRIDENT) { icon = Icon.TRIDENTE; tail = "con tridente"; }
            else if (hand == Material.AIR) { icon = Icon.CALAVERA; tail = "con los puños"; }
            else { icon = Icon.CALAVERA; tail = "con un objeto"; }
        }
        int st = streak(killer.getUniqueId());
        return kill(killer.getName(), v.getName(), tail, icon, killer.getUniqueId(), v.getUniqueId(), st);
    }

    private String dist(Player a, Player b) {
        if (!a.getWorld().equals(b.getWorld())) return "";
        int d = (int) Math.round(a.getLocation().distance(b.getLocation()));
        return d >= 6 ? " (" + d + " m)" : "";
    }

    private Msg mobMsg(Player v, EntityDamageByEntityEvent byEntity) {
        Entity d = byEntity.getDamager();
        if (d instanceof Projectile p && p.getShooter() instanceof Entity shooter) d = shooter;
        if (d instanceof Creeper || d instanceof TNTPrimed)
            return death(v.getName(), "ha volado por los aires", Icon.TNT, v.getUniqueId());
        List<Part> parts = new ArrayList<>();
        parts.add(name(v.getName(), RED, Role.VICTIM));
        parts.add(txt(" murió a manos de "));
        parts.add(name(mobName(d.getType()), GOLD, Role.ATTACKER));
        return new Msg(parts, Icon.CALAVERA, null, v.getUniqueId(), 0);
    }

    static String mobName(EntityType t) {
        switch (t.name()) {
            case "ZOMBIE": return "Zombi";
            case "SKELETON": return "Esqueleto";
            case "WITHER_SKELETON": return "Esqueleto wither";
            case "CREEPER": return "Creeper";
            case "SPIDER": return "Araña";
            case "CAVE_SPIDER": return "Araña de cueva";
            case "ENDERMAN": return "Enderman";
            case "ENDER_DRAGON": return "Dragón";
            case "ZOMBIFIED_PIGLIN": return "Piglin zombi";
            case "DROWNED": return "Ahogado";
            case "WITCH": return "Bruja";
            case "PILLAGER": return "Saqueador";
            case "VINDICATOR": return "Vindicador";
            case "RAVAGER": return "Devastador";
            case "EVOKER": return "Evocador";
            case "GUARDIAN": return "Guardián";
            case "IRON_GOLEM": return "Gólem de hierro";
            case "WOLF": return "Lobo";
            case "BEE": return "Abeja";
            case "MAGMA_CUBE": return "Cubo de magma";
            default:
                String s = t.name().toLowerCase().replace('_', ' ');
                return Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
    }

    private Msg causeMsg(Player v, EntityDamageEvent.DamageCause c) {
        String tail = "ha muerto";
        Icon icon = Icon.CALAVERA;
        if (c != null) switch (c) {
            case FALL -> { tail = "ha muerto de caída"; icon = Icon.PLUMA; }
            case FIRE, FIRE_TICK, HOT_FLOOR -> { tail = "ha muerto quemado"; icon = Icon.FUEGO; }
            case LAVA -> { tail = "se ha bañado en lava"; icon = Icon.LAVA; }
            case DROWNING -> { tail = "se ha ahogado"; icon = Icon.AGUA; }
            case VOID -> { tail = "ha caído al vacío"; icon = Icon.VACIO; }
            case BLOCK_EXPLOSION, ENTITY_EXPLOSION -> { tail = "ha volado por los aires"; icon = Icon.TNT; }
            case STARVATION -> tail = "ha muerto de hambre";
            case CONTACT -> tail = "ha muerto pinchado";
            case LIGHTNING -> tail = "ha sido alcanzado por un rayo";
            case SUFFOCATION -> tail = "se ha asfixiado";
            case FLY_INTO_WALL -> tail = "se ha estampado";
            case POISON -> tail = "ha muerto envenenado";
            case MAGIC, WITHER -> tail = "ha muerto por magia";
            case FREEZE -> tail = "se ha congelado";
            case SUICIDE, KILL -> tail = "se ha suicidado";
            default -> { }
        }
        return death(v.getName(), tail, icon, v.getUniqueId());
    }

    // ------------------------------------------------------------------ mostrar y animar

    private boolean eligible(Player p) {
        if (p.getPersistentDataContainer().has(keyOff, PersistentDataType.BYTE)) return false;
        if (!onlyWithPack) return true;
        return packOk.contains(p.getUniqueId()) || p.getPersistentDataContainer().has(keyForce, PersistentDataType.BYTE);
    }

    private void show(Msg msg) {
        for (Player p : Bukkit.getOnlinePlayers()) if (eligible(p)) showTo(p, msg);
    }

    private void showTo(Player p, Msg msg) {
        Kind kind = p.getUniqueId().equals(msg.killer()) ? Kind.YOU : p.getUniqueId().equals(msg.victim()) ? Kind.ME : Kind.NORMAL;
        List<Line> list = feeds.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>());
        while (list.size() >= maxLines) p.hideBossBar(list.remove(0).bar);
        BossBar bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
        Line line = new Line(bar, msg, kind);
        bar.name(render(p, line));
        p.showBossBar(bar);
        list.add(line);
        if (sounds) {
            if (kind == Kind.YOU) {
                float pitch = Math.min(2f, 1.2f + 0.12f * msg.streak());
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.55f, pitch);
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, Math.min(2f, 1.4f + 0.1f * msg.streak()));
            } else if (kind == Kind.ME) {
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.6f);
            }
        }
    }

    private void tick() {
        Iterator<Map.Entry<UUID, List<Line>>> it = feeds.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, List<Line>> en = it.next();
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null) { it.remove(); continue; }
            List<Line> list = en.getValue();
            for (Iterator<Line> li = list.iterator(); li.hasNext(); ) {
                Line l = li.next();
                l.age++;
                if (l.age >= life) { p.hideBossBar(l.bar); li.remove(); continue; }
                if (l.age <= 30 || l.age >= life - 9 || l.age % 2 == 0) l.bar.name(render(p, l));
            }
            if (list.isEmpty()) it.remove();
        }
    }

    private int radius(Player p) {
        Integer r = p.getPersistentDataContainer().get(keyRadius, PersistentDataType.INTEGER);
        return r != null ? r : defaultRadius;
    }

    private int pos(Player p) {
        Integer r = p.getPersistentDataContainer().get(keyPos, PersistentDataType.INTEGER);
        return r != null ? Math.max(0, Math.min(4, r)) : defaultPos;
    }

    private static double easeOutBack(double x) {
        double c1 = 1.70158, c3 = c1 + 1;
        return 1 + c3 * Math.pow(x - 1, 3) + c1 * Math.pow(x - 1, 2);
    }

    /** Desplazamiento a la derecha (px): entra con rebote y sale acelerando. */
    private int slide(int age) {
        final int in = 10, out = 8;
        if (age < in) return (int) Math.round(240 * (1 - easeOutBack(age / (double) in)));
        if (age >= life - out) { double x = (age - (life - out)) / (double) out; return (int) Math.round(280 * x * x * x); }
        return 0;
    }

    private static int lerp(int a, int b, double t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int) Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }

    /** Pincel que lleva la cuenta de la posicion horizontal para poder retroceder y dibujar capas. */
    private static final class Canvas {
        final TextComponent.Builder b;
        int x = 0;
        Canvas(Key font) { b = Component.text().font(font); }
        void move(int to) {
            if (to != x) { b.append(Component.text(spaces(to - x))); x = to; }
        }
        void put(String s, int advance, int rgb) {
            b.append(Component.text(s, TextColor.color(rgb)));
            x += advance;
        }
        void comp(Component c, int advance) {
            b.append(c);
            x += advance;
        }
    }

    private static String tiles(char big, char small, int w) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < w / 8; i++) sb.append(big).append(BACK);
        for (int i = 0; i < w % 8; i++) sb.append(small).append(BACK);
        return sb.toString();
    }

    private void spark(Canvas c, int idx, int x, int rgb) {
        c.move(x);
        c.put(String.valueOf(SPARK[idx]), SPARK_ADV[idx], rgb);
    }

    private Component render(Player p, Line l) {
        Msg m = l.msg;
        int age = l.age;
        int textW = 0;
        for (Part part : m.parts()) textW += part.width();
        int panelW = TEXT_X + textW + RPAD;

        // movimiento: entrada con rebote, salida acelerada y temblor si te matan
        int dx = slide(age);
        if (l.kind == Kind.ME && age >= 10 && age < 26) dx += ((age % 2 == 0) ? 1 : -1) * (int) Math.round(4 * (26 - age) / 16.0);
        int s = 2 * radius(p) - panelW + 2 * dx;
        if (((s + panelW) & 1) != 0) s++;

        // colores
        int tint = l.kind == Kind.YOU ? TINT_YOU : l.kind == Kind.ME ? TINT_ME : TINT_NORMAL;
        int accent = l.kind == Kind.YOU ? ACC_YOU : l.kind == Kind.ME ? ACC_ME : ACC_NORMAL;
        if (age >= 8 && age < 14) tint = lerp(tint, WHITE, (14 - age) / 6.0 * 0.55);      // destello al llegar
        boolean flash = l.kind != Kind.NORMAL && age >= 8 && age < 32 && (age / 2) % 2 == 0;
        int frame = (age >= 8 && age < 11) ? 2 : (age >= 11 && age < 14) ? 1 : 0;           // "pop" del icono

        Canvas c = new Canvas(Key.key("normsafio", "kf" + pos(p)));
        c.b.append(Component.text(spaces(s)));

        // capa 1: panel, franja de color y barra de tiempo
        c.put(tiles(PANEL8, PANEL1, panelW), panelW, tint);
        c.move(0);
        c.put(tiles(PANEL8, PANEL1, ACCENT_W), ACCENT_W, accent);
        int barW = (int) ((panelW - ACCENT_W) * (1.0 - age / (double) life));
        if (age >= 10 && barW > 0) {
            c.move(ACCENT_W);
            c.put(tiles(BAR8, BAR1, barW), barW, accent);
        }

        // capa 2: brillo diagonal que barre la tarjeta
        if (age >= 6 && age <= 22) {
            int sx = ACCENT_W + (int) ((age - 6) / 16.0 * (panelW - ACCENT_W - 8));
            c.move(sx);
            c.put(String.valueOf(SHINE) + BACK, 8, WHITE);
        }

        // capa 3: chispas alrededor del icono
        if (age >= 9 && age < 21) {
            int cx = ICON_X + ICON_BLOCK / 2;
            if (age % 2 == 0) spark(c, 1, cx - 16, GOLD); else spark(c, 3, cx + 8, WHITE);
            if (age % 3 == 0) spark(c, 0, panelW - 16, GOLD);
            if (age % 3 == 1) spark(c, 2, TEXT_X - 6, WHITE);
        }

        // capa 4: icono con pop
        int h = ICON_H[frame];
        c.move(ICON_X + (ICON_BLOCK - h) / 2);
        c.put(String.valueOf(m.icon().glyph(frame)), h + 1, WHITE);

        // capa 5: texto
        c.move(TEXT_X);
        for (Part part : m.parts()) {
            Component comp = part.base();
            if (flash && part.role() != Role.TEXT) comp = comp.color(TextColor.color(WHITE));
            c.comp(comp, part.width());
        }
        c.move(panelW);
        return c.b.build();
    }

    /** Espacio horizontal exacto en pixeles con la fuente (positivo o negativo). */
    static String spaces(int px) {
        int base = px >= 0 ? 0xE200 : 0xE300;
        int n = Math.min(4095, Math.abs(px));
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < 12; k++) if ((n & (1 << k)) != 0) sb.append((char) (base + k));
        return sb.toString();
    }

    // ------------------------------------------------------------------ comandos

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (a.length == 0) {
            sender.sendMessage("§6/kf on|off §7· activar/desactivar para ti");
            sender.sendMessage("§6/kf ajuste <n|+n|-n> §7· distancia al borde derecho (defecto " + defaultRadius + ")");
            sender.sendMessage("§6/kf pos <0-4> §7· altura: 0 arriba ... 4 abajo (defecto " + defaultPos + ")");
            if (sender.hasPermission("killfeed.admin"))
                sender.sendMessage("§6/kf test <caida|espada|arco|tnt|radiacion|fuego|lava|agua|vacio|matas|tematan|racha> §7· /kf push <icono> <texto> §7· /kf reload");
            return true;
        }
        String sub = a[0].toLowerCase();
        if (sub.equals("reload") && sender.hasPermission("killfeed.admin")) { loadSettings(); sender.sendMessage("§aRecargado."); return true; }

        if (sub.equals("push") && sender.hasPermission("killfeed.admin")) {
            if (a.length < 3) { sender.sendMessage("§cUso: /kf push <icono> <texto con &colores>"); return true; }
            Icon icon;
            try { icon = Icon.valueOf(a[1].toUpperCase()); }
            catch (IllegalArgumentException ex) { sender.sendMessage("§cIconos: " + Arrays.toString(Icon.values()).toLowerCase()); return true; }
            String text = String.join(" ", Arrays.copyOfRange(a, 2, a.length));
            Component c = LegacyComponentSerializer.legacyAmpersand().deserialize(text);
            int w = Metrics.width(PlainTextComponentSerializer.plainText().serialize(c));
            show(new Msg(List.of(new Part(c, w, Role.TEXT)), icon, null, null, 0));
            return true;
        }

        if (!(sender instanceof Player p)) { sender.sendMessage("Solo jugadores."); return true; }
        switch (sub) {
            case "on" -> {
                p.getPersistentDataContainer().remove(keyOff);
                p.getPersistentDataContainer().set(keyForce, PersistentDataType.BYTE, (byte) 1);
                p.sendMessage("§aKill feed activado.");
            }
            case "off" -> {
                p.getPersistentDataContainer().set(keyOff, PersistentDataType.BYTE, (byte) 1);
                p.sendMessage("§cKill feed desactivado.");
            }
            case "ajuste" -> {
                if (a.length < 2) { p.sendMessage("§cUso: /kf ajuste <número> o +5 / -5 (más grande = más pegado al borde)"); return true; }
                try {
                    int r = a[1].startsWith("+") || a[1].startsWith("-") ? radius(p) + Integer.parseInt(a[1].replace("+", "")) : Integer.parseInt(a[1]);
                    r = Math.max(60, Math.min(1000, r));
                    p.getPersistentDataContainer().set(keyRadius, PersistentDataType.INTEGER, r);
                    p.sendMessage("§aAjuste guardado: " + r + ". Prueba con /kf test caida");
                } catch (NumberFormatException ex) { p.sendMessage("§cPon un número."); }
            }
            case "pos" -> {
                if (a.length < 2) { p.sendMessage("§cUso: /kf pos <0-4>"); return true; }
                try {
                    int v = Math.max(0, Math.min(4, Integer.parseInt(a[1])));
                    p.getPersistentDataContainer().set(keyPos, PersistentDataType.INTEGER, v);
                    p.sendMessage("§aAltura " + v + " guardada.");
                } catch (NumberFormatException ex) { p.sendMessage("§cPon un número del 0 al 4."); }
            }
            case "test" -> {
                if (!p.hasPermission("killfeed.admin")) return true;
                String t = a.length > 1 ? a[1].toLowerCase() : "caida";
                UUID me = p.getUniqueId();
                Msg m = switch (t) {
                    case "espada" -> kill("Ana", "Pepe", "con espada", Icon.ESPADA, null, null, 0);
                    case "arco" -> kill("Ana", "Pepe", "con arco (32 m)", Icon.ARCO, null, null, 0);
                    case "tnt" -> death("Pepe", "ha volado por los aires", Icon.TNT, null);
                    case "radiacion" -> death("Pepe", "ha muerto por radiación", Icon.RADIACION, null);
                    case "fuego" -> death("Pepe", "ha muerto quemado", Icon.FUEGO, null);
                    case "lava" -> death("Pepe", "se ha bañado en lava", Icon.LAVA, null);
                    case "agua" -> death("Pepe", "se ha ahogado", Icon.AGUA, null);
                    case "vacio" -> death("Pepe", "ha caído al vacío", Icon.VACIO, null);
                    case "matas" -> kill(p.getName(), "Pepe", "con espada", Icon.ESPADA, me, null, 0);
                    case "racha" -> kill(p.getName(), "Pepe", "con espada", Icon.ESPADA, me, null, 3);
                    case "tematan" -> kill("Ana", p.getName(), "con arco (20 m)", Icon.ARCO, null, me, 0);
                    default -> death("Pepe", "ha muerto de caída", Icon.PLUMA, null);
                };
                showTo(p, m);
            }
            default -> p.sendMessage("§cSubcomando desconocido. Usa /kf");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 1) return filter(List.of("on", "off", "ajuste", "pos", "test", "push", "reload"), a[0]);
        if (a.length == 2 && a[0].equalsIgnoreCase("test"))
            return filter(List.of("caida", "espada", "arco", "tnt", "radiacion", "fuego", "lava", "agua", "vacio", "matas", "tematan", "racha"), a[1]);
        if (a.length == 2 && a[0].equalsIgnoreCase("pos")) return filter(List.of("0", "1", "2", "3", "4"), a[1]);
        if (a.length == 2 && a[0].equalsIgnoreCase("push"))
            return filter(Arrays.stream(Icon.values()).map(i -> i.name().toLowerCase()).toList(), a[1]);
        return List.of();
    }

    private static List<String> filter(List<String> all, String start) {
        String s = start.toLowerCase();
        return all.stream().filter(x -> x.startsWith(s)).toList();
    }
}
