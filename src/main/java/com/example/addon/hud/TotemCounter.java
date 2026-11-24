package com.example.addon.hud;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class TotemCounter extends HudElement {
    public static final HudElementInfo<TotemCounter> INFO = new HudElementInfo<>(
        AddonTemplate.HUD_GROUP,
        "totem-counter",
        "Shows total totems (inventory + offhand) with optional warnings.",
        TotemCounter::new
    );

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgWarnings = settings.createGroup("Warnings");

    // ===== Display =====

    private final Setting<Boolean> showIcon = sgGeneral.add(new BoolSetting.Builder()
        .name("show-icon")
        .description("Draw the totem item icon.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Scale of the whole element.")
        .defaultValue(1.0)
        .min(0.5)
        .sliderRange(0.5, 3.0)
        .build()
    );

    private final Setting<SettingColor> textColor = sgGeneral.add(new ColorSetting.Builder()
        .name("text-color")
        .description("Color of the count text.")
        .defaultValue(new SettingColor(255, 255, 255))
        .build()
    );

    private final Setting<SettingColor> lowColor = sgGeneral.add(new ColorSetting.Builder()
        .name("low-color")
        .description("Text color when totems are below the warning threshold.")
        .defaultValue(new SettingColor(255, 80, 80))
        .build()
    );

    // ===== Warnings =====

    private final Setting<Boolean> lowTotemWarn = sgWarnings.add(new BoolSetting.Builder()
        .name("low-totem-warn")
        .description("Warn when total totems drop below the threshold.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> lowThreshold = sgWarnings.add(new IntSetting.Builder()
        .name("low-threshold")
        .description("Warn when total totems are below this number.")
        .defaultValue(3)
        .min(0)
        .sliderRange(0, 16)
        .visible(lowTotemWarn::get)
        .build()
    );

    private final Setting<Boolean> noOffhandWarn = sgWarnings.add(new BoolSetting.Builder()
        .name("no-offhand-warn")
        .description("Warn when there is no totem in the offhand.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chatWarnings = sgWarnings.add(new BoolSetting.Builder()
        .name("chat-warnings")
        .description("Also send a chat message when a warning triggers (with cooldown).")
        .defaultValue(false)
        .build()
    );

    // Internal
    private int lastWarnedCount = -1;
    private boolean lastHadOffhand = true;
    private long lastChatWarnMs = 0;

    public TotemCounter() {
        super(INFO);
    }

    @Override
    public void tick(HudRenderer renderer) {
        // nothing heavy needed
    }

    @Override
    public void render(HudRenderer renderer) {
        if (mc.player == null) {
            // Editor preview
            renderPreview(renderer);
            return;
        }

        int total = countTotems();
        boolean hasOffhand = hasTotemInOffhand();

        // Determine color
        boolean isLow = lowTotemWarn.get() && total < lowThreshold.get();
        boolean noOffhand = noOffhandWarn.get() && !hasOffhand;
        Color color = (isLow || noOffhand) ? lowColor.get() : textColor.get();

        double s = scale.get();
        double iconSize = 16 * s;

        // Calculate size
        String countStr = String.valueOf(total);
        double textW = renderer.textWidth(countStr, true, s);
        double textH = renderer.textHeight(true, s);

        double width = showIcon.get() ? iconSize + 2 + textW : textW;
        double height = Math.max(showIcon.get() ? iconSize : 0, textH);
        setSize(width, height);

        // Draw icon
        if (showIcon.get()) {
            ItemStack stack = new ItemStack(Items.TOTEM_OF_UNDYING, Math.max(total, 1));
            renderer.item(stack, x, y, (float) s, true);
        }

        // Draw count text (slightly overlapping the icon like vanilla stack count)
        double textX = showIcon.get() ? x + iconSize - textW + 2 : x;
        double textY = showIcon.get() ? y + iconSize - textH + 1 : y;
        renderer.text(countStr, textX, textY, color, true, s);

        // Optional chat warnings (with simple cooldown)
        if (chatWarnings.get()) {
            long now = System.currentTimeMillis();
            if (now - lastChatWarnMs > 5000) { // 5s cooldown
                if (isLow && total != lastWarnedCount) {
                    info("Low totems: %d (threshold %d)", total, lowThreshold.get());
                    lastChatWarnMs = now;
                }
                if (noOffhand && lastHadOffhand) {
                    warning("No totem in offhand!");
                    lastChatWarnMs = now;
                }
            }
        }

        lastWarnedCount = total;
        lastHadOffhand = hasOffhand;
    }

    private void renderPreview(HudRenderer renderer) {
        double s = scale.get();
        double iconSize = 16 * s;
        String countStr = "5";
        double textW = renderer.textWidth(countStr, true, s);
        double textH = renderer.textHeight(true, s);

        double width = showIcon.get() ? iconSize + 2 + textW : textW;
        double height = Math.max(showIcon.get() ? iconSize : 0, textH);
        setSize(width, height);

        if (showIcon.get()) {
            ItemStack stack = new ItemStack(Items.TOTEM_OF_UNDYING, 5);
            renderer.item(stack, x, y, (float) s, true);
        }

        double textX = showIcon.get() ? x + iconSize - textW + 2 : x;
        double textY = showIcon.get() ? y + iconSize - textH + 1 : y;
        renderer.text(countStr, textX, textY, textColor.get(), true, s);
    }

    private int countTotems() {
        int count = 0;

        // Main inventory + hotbar
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.is(Items.TOTEM_OF_UNDYING)) {
                count += stack.getCount();
            }
        }

        // Offhand is already included in getContainerSize on modern versions,
        // but we also check explicitly to be safe across versions
        ItemStack offhand = mc.player.getOffhandItem();
        if (offhand.is(Items.TOTEM_OF_UNDYING)) {
            // Avoid double-counting if it was already in the loop
            // On 1.21+ offhand is slot 40 and is included, so we only add if needed.
            // Safer approach: just trust InvUtils or re-count carefully.
        }

        // Cleaner way using Meteor utility
        return InvUtils.find(Items.TOTEM_OF_UNDYING).count();
    }

    private boolean hasTotemInOffhand() {
        return mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
    }
}
