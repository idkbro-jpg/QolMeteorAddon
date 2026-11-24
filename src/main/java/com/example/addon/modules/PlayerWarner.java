package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.SendMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.*;
import java.util.regex.Pattern;

public class PlayerWarner extends Module {
    private final SettingGroup sgCommands = settings.createGroup("Commands");
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
    private final Set<String> knownPlayers = new HashSet<>();
    private final Map<String, Integer> lastWarned = new HashMap<>();

    // Common rank prefixes to strip (case-insensitive)
    private static final Pattern RANK_PATTERN = Pattern.compile(
        "^(?i)(player|vip|mvp|mvp\\+|mvp\\+\\+|admin|mod|helper|owner|builder|yt|youtube|staff|dev|developer|media|partner|premium|donor|donator|elite|legend|hero|champion|master|grandmaster|immortal|rank|\\[.*?\\]|\\(.*?\\)|\\{.*?\\})\\s*"
    );

    public PlayerWarner() {
        super(AddonTemplate.CATEGORY, "player-warner", "Warns you about other players. Can be toggled by commands like /lobby (off) and /home (on).");
    }

    @Override
    public void onActivate() {
        knownPlayers.clear();
        lastWarned.clear();
        // Start inactive so the first enable-command is required
        warningActive = false;
        info("PlayerWarner ready. Currently inactive — type one of your enable-commands (e.g. /home) to start watching.");
    }

    @Override
    public void onDeactivate() {
        knownPlayers.clear();
        lastWarned.clear();
        warningActive = false;
    }

    // Detect when the player types a command
    @EventHandler
    private void onSendMessage(SendMessageEvent event) {
        String msg = event.message.trim();
        if (msg.isEmpty()) return;

        String cmd = msg.startsWith("/") ? msg.substring(1) : msg;
        // Only look at the first word (the actual command)
        String firstWord = cmd.split("\\s+")[0].toLowerCase(Locale.ROOT);

        for (String disable : disableCommands.get()) {
            String d = disable.startsWith("/") ? disable.substring(1) : disable;
            if (firstWord.equals(d.toLowerCase(Locale.ROOT))) {
                if (warningActive) {
                    warningActive = false;
                    knownPlayers.clear();
                    info("PlayerWarner disabled (matched disable-command: %s)", firstWord);
                }
                return;
            }
        }

        for (String enable : enableCommands.get()) {
            String e = enable.startsWith("/") ? enable.substring(1) : enable;
            if (firstWord.equals(e.toLowerCase(Locale.ROOT))) {
                if (!warningActive) {
                    warningActive = true;
                    knownPlayers.clear();
                    // Snapshot current players so we only warn about new ones
                    snapshotCurrentPlayers();
                    info("PlayerWarner enabled (matched enable-command: %s)", firstWord);
                }
                return;
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!warningActive || mc.player == null || mc.getConnection() == null) return;

        Collection<PlayerInfo> online = mc.getConnection().getOnlinePlayers();
        Set<String> current = new HashSet<>();

        for (PlayerInfo info : online) {
            if (info.getProfile() == null) continue;
            String rawName = info.getProfile().getName();
            if (rawName == null || rawName.isEmpty()) continue;

            // Skip self
            if (rawName.equalsIgnoreCase(mc.player.getGameProfile().getName())) continue;

            String name = stripRanks.get() ? stripRank(rawName) : rawName;
            current.add(name.toLowerCase(Locale.ROOT));

            // Already known → skip
            if (knownPlayers.contains(name.toLowerCase(Locale.ROOT))) continue;

            // Blacklisted?
            if (isBlacklisted(name)) {
                knownPlayers.add(name.toLowerCase(Locale.ROOT));
                continue;
            }

            // Friend check
            if (ignoreFriends.get() && meteordevelopment.meteorclient.systems.friends.Friends.get().get(rawName) != null) {
                knownPlayers.add(name.toLowerCase(Locale.ROOT));
                continue;
            }

            // Cooldown
            int tick = (int) mc.level.getGameTime();
            Integer last = lastWarned.get(name.toLowerCase(Locale.ROOT));
            if (last != null && tick - last < cooldown.get()) {
                knownPlayers.add(name.toLowerCase(Locale.ROOT));
                continue;
            }

            // === WARN ===
            warnPlayer(rawName, name);
            knownPlayers.add(name.toLowerCase(Locale.ROOT));
            lastWarned.put(name.toLowerCase(Locale.ROOT), tick);
        }

        // Clean up players who left
        knownPlayers.retainAll(current);
    }

    private void snapshotCurrentPlayers() {
        if (mc.getConnection() == null) return;
        for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            if (info.getProfile() == null) continue;
            String raw = info.getProfile().getName();
            if (raw == null) continue;
            if (raw.equalsIgnoreCase(mc.player.getGameProfile().getName())) continue;
            String name = stripRanks.get() ? stripRank(raw) : raw;
            knownPlayers.add(name.toLowerCase(Locale.ROOT));
        }
    }

    private boolean isBlacklisted(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String bl : blacklist.get()) {
            String cleaned = stripRanks.get() ? stripRank(bl) : bl;
            if (cleaned.toLowerCase(Locale.ROOT).equals(lower)) return true;
        }
        return false;
    }

    private String stripRank(String name) {
        // Repeatedly strip common prefixes
        String result = name.trim();
        for (int i = 0; i < 5; i++) {
            String cleaned = RANK_PATTERN.matcher(result).replaceFirst("").trim();
            if (cleaned.equals(result) || cleaned.isEmpty()) break;
            result = cleaned;
        }
        // Also remove leftover brackets/colors if any
        result = result.replaceAll("(?i)§[0-9a-fk-or]", ""); // color codes
        return result.isEmpty() ? name : result;
    }

    private void warnPlayer(String rawName, String cleanedName) {
        if (chatNotify.get()) {
            warning("Player detected: §c%s§r (cleaned: %s)", rawName, cleanedName);
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
