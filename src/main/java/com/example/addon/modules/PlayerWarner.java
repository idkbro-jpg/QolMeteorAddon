package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.events.game.SendMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;

import java.util.*;
import java.util.regex.Pattern;

public class PlayerWarner extends Module {
    private final SettingGroup sgCommands = settings.createGroup("Commands");
    private final SettingGroup sgDetection = settings.createGroup("Detection");
    private final SettingGroup sgFilter = settings.createGroup("Filter");
    private final SettingGroup sgNotify = settings.createGroup("Notifications");

    // ===== Commands =====

    private final Setting<List<String>> disableCommands = sgCommands.add(new StringListSetting.Builder()
        .name("disable-commands")
        .description("Commands that turn the player warner OFF (e.g. lobby). Leading / is optional.")
        .defaultValue(List.of("lobby", "spawn", "hub"))
        .build()
    );

    private final Setting<List<String>> enableCommands = sgCommands.add(new StringListSetting.Builder()
        .name("enable-commands")
        .description("Commands that turn the player warner ON (e.g. home). Leading / is optional.")
        .defaultValue(List.of("home", "base"))
        .build()
    );

    // ===== Detection =====

    private final Setting<Boolean> nearbyPlayers = sgDetection.add(new BoolSetting.Builder()
        .name("nearby-players")
        .description("Warn when a player appears near you (in the world / render distance). This is the main mode.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> maxDistance = sgDetection.add(new DoubleSetting.Builder()
        .name("max-distance")
        .description("Maximum distance to consider a player 'near' you. 0 = unlimited (any rendered player).")
        .defaultValue(64.0)
        .min(0)
        .sliderRange(0, 128)
        .visible(nearbyPlayers::get)
        .build()
    );

    private final Setting<Boolean> serverJoins = sgDetection.add(new BoolSetting.Builder()
        .name("server-joins")
        .description("Also warn when a new player simply joins the server (appears in the tab list). Off by default.")
        .defaultValue(false)
        .build()
    );

    // ===== Filter =====

    private final Setting<List<String>> blacklist = sgFilter.add(new StringListSetting.Builder()
        .name("blacklist")
        .description("Player names that should never trigger a warning.")
        .defaultValue(List.of())
        .build()
    );

    private final Setting<Boolean> stripRanks = sgFilter.add(new BoolSetting.Builder()
        .name("strip-ranks")
        .description("Remove common rank prefixes from names before comparing (e.g. Playerflowm30 → flowm30).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> ignoreFriends = sgFilter.add(new BoolSetting.Builder()
        .name("ignore-friends")
        .description("Ignore players that are in your Meteor friends list.")
        .defaultValue(true)
        .build()
    );

    // ===== Notifications =====

    private final Setting<Boolean> chatNotify = sgNotify.add(new BoolSetting.Builder()
        .name("chat")
        .description("Send a warning message in chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> soundNotify = sgNotify.add(new BoolSetting.Builder()
        .name("sound")
        .description("Play a sound when a player is detected.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> cooldown = sgNotify.add(new IntSetting.Builder()
        .name("cooldown")
        .description("Minimum ticks between warnings for the same player.")
        .defaultValue(100)
        .range(0, 600)
        .sliderRange(0, 200)
        .build()
    );

    // Internal state
    private boolean warningActive = false;

    // Players we have already warned about (or snapped) while the mode is active
    private final Set<String> knownNearby = new HashSet<>();
    private final Set<String> knownTab = new HashSet<>();
    private final Map<String, Integer> lastWarned = new HashMap<>();

    private static final Pattern RANK_PATTERN = Pattern.compile(
        "^(?i)(player|vip|mvp|mvp\\+|mvp\\+\\+|admin|mod|helper|owner|builder|yt|youtube|staff|dev|developer|media|partner|premium|donor|donator|elite|legend|hero|champion|master|grandmaster|immortal|rank|\\[.*?\\]|\\(.*?\\)|\\{.*?\\})\\s*"
    );

    public PlayerWarner() {
        super(AddonTemplate.CATEGORY, "player-warner",
            "Warns you about players near you. Can be toggled by commands like /lobby (off) and /home (on).");
    }

    @Override
    public void onActivate() {
        knownNearby.clear();
        knownTab.clear();
        lastWarned.clear();
        warningActive = false;
        info("PlayerWarner ready. Currently inactive — type one of your enable-commands (e.g. /home) to start watching.");
    }

    @Override
    public void onDeactivate() {
        knownNearby.clear();
        knownTab.clear();
        lastWarned.clear();
        warningActive = false;
    }

    // ===== Command detection =====

    @EventHandler
    private void onSendMessage(SendMessageEvent event) {
        String msg = event.message.trim();
        if (msg.isEmpty()) return;

        String cmd = msg.startsWith("/") ? msg.substring(1) : msg;
        String firstWord = cmd.split("\\s+")[0].toLowerCase(Locale.ROOT);

        for (String disable : disableCommands.get()) {
            String d = disable.startsWith("/") ? disable.substring(1) : disable;
            if (firstWord.equals(d.toLowerCase(Locale.ROOT))) {
                if (warningActive) {
                    warningActive = false;
                    knownNearby.clear();
                    knownTab.clear();
                    info("PlayerWarner disabled (matched: %s)", firstWord);
                }
                return;
            }
        }

        for (String enable : enableCommands.get()) {
            String e = enable.startsWith("/") ? enable.substring(1) : enable;
            if (firstWord.equals(e.toLowerCase(Locale.ROOT))) {
                if (!warningActive) {
                    warningActive = true;
                    knownNearby.clear();
                    knownTab.clear();
                    snapshotCurrent();
                    info("PlayerWarner enabled (matched: %s)", firstWord);
                }
                return;
            }
        }
    }

    // ===== Nearby players (main mode) =====

    @EventHandler
    private void onEntityAdded(EntityAddedEvent event) {
        if (!warningActive || !nearbyPlayers.get()) return;
        if (!(event.entity instanceof Player player)) return;
        if (player == mc.player) return;

        checkNearbyPlayer(player);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!warningActive || mc.player == null || mc.level == null) return;

        // Nearby players (continuous check in case EntityAdded was missed)
        if (nearbyPlayers.get()) {
            for (Player player : mc.level.players()) {
                if (player == mc.player) continue;
                checkNearbyPlayer(player);
            }
        }

        // Optional: server joins (tab list)
        if (serverJoins.get() && mc.getConnection() != null) {
            checkTabList();
        }
    }

    private void checkNearbyPlayer(Player player) {
        double dist = mc.player.distanceTo(player);
        if (maxDistance.get() > 0 && dist > maxDistance.get()) return;

        String rawName = player.getGameProfile().getName();
        if (rawName == null || rawName.isEmpty()) return;

        String key = normalize(rawName);

        if (knownNearby.contains(key)) return;
        if (shouldIgnore(rawName, key)) {
            knownNearby.add(key);
            return;
        }

        if (!canWarn(key)) {
            knownNearby.add(key);
            return;
        }

        warnPlayer(rawName, key, dist, "nearby");
        knownNearby.add(key);
        markWarned(key);
    }

    private void checkTabList() {
        Collection<PlayerInfo> online = mc.getConnection().getOnlinePlayers();
        Set<String> current = new HashSet<>();

        for (PlayerInfo info : online) {
            if (info.getProfile() == null) continue;
            String rawName = info.getProfile().getName();
            if (rawName == null || rawName.isEmpty()) continue;
            if (rawName.equalsIgnoreCase(mc.player.getGameProfile().getName())) continue;

            String key = normalize(rawName);
            current.add(key);

            if (knownTab.contains(key)) continue;
            if (shouldIgnore(rawName, key)) {
                knownTab.add(key);
                continue;
            }

            if (!canWarn(key)) {
                knownTab.add(key);
                continue;
            }

            warnPlayer(rawName, key, -1, "joined server");
            knownTab.add(key);
            markWarned(key);
        }

        // Players who left the server
        knownTab.retainAll(current);
    }

    // ===== Helpers =====

    private void snapshotCurrent() {
        // Snapshot nearby players so we don't instantly warn about people already around
        if (mc.level != null && mc.player != null) {
            for (Player player : mc.level.players()) {
                if (player == mc.player) continue;
                String key = normalize(player.getGameProfile().getName());
                knownNearby.add(key);
            }
        }

        // Snapshot tab list if that mode is enabled
        if (serverJoins.get() && mc.getConnection() != null) {
            for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
                if (info.getProfile() == null) continue;
                String raw = info.getProfile().getName();
                if (raw == null) continue;
                if (raw.equalsIgnoreCase(mc.player.getGameProfile().getName())) continue;
                knownTab.add(normalize(raw));
            }
        }
    }

    private boolean shouldIgnore(String rawName, String key) {
        if (isBlacklisted(key)) return true;
        if (ignoreFriends.get() && Friends.get().get(rawName) != null) return true;
        return false;
    }

    private boolean isBlacklisted(String key) {
        for (String bl : blacklist.get()) {
            if (normalize(bl).equals(key)) return true;
        }
        return false;
    }

    private String normalize(String name) {
        if (name == null) return "";
        String result = stripRanks.get() ? stripRank(name) : name;
        return result.toLowerCase(Locale.ROOT).trim();
    }

    private String stripRank(String name) {
        String result = name.trim();
        for (int i = 0; i < 5; i++) {
            String cleaned = RANK_PATTERN.matcher(result).replaceFirst("").trim();
            if (cleaned.equals(result) || cleaned.isEmpty()) break;
            result = cleaned;
        }
        result = result.replaceAll("(?i)§[0-9a-fk-or]", "");
        return result.isEmpty() ? name : result;
    }

    private boolean canWarn(String key) {
        if (mc.level == null) return false;
        int tick = (int) mc.level.getGameTime();
        Integer last = lastWarned.get(key);
        return last == null || tick - last >= cooldown.get();
    }

    private void markWarned(String key) {
        if (mc.level != null) {
            lastWarned.put(key, (int) mc.level.getGameTime());
        }
    }

    private void warnPlayer(String rawName, String cleaned, double distance, String reason) {
        if (chatNotify.get()) {
            if (distance >= 0) {
                warning("Player nearby: §c%s§r (%.0fm) [%s]", rawName, distance, reason);
            } else {
                warning("Player %s: §c%s§r", reason, rawName);
            }
        }

        if (soundNotify.get() && mc.player != null && mc.level != null) {
            mc.level.playSound(
                mc.player,
                mc.player.blockPosition(),
                SoundEvents.NOTE_BLOCK_PLING.value(),
                SoundSource.MASTER,
                1.0f,
                1.2f
            );
        }
    }

    @Override
    public String getInfoString() {
        return warningActive ? "ON" : "OFF";
    }
}
