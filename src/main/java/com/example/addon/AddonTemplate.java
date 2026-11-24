package com.example.addon;

import com.example.addon.commands.CommandExample;
import com.example.addon.hud.HudExample;
import com.example.addon.hud.TotemCounter;
import com.example.addon.modules.ModuleExample;
import com.example.addon.modules.PlayerWarner;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class AddonTemplate extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("QoL");
    public static final HudGroup HUD_GROUP = new HudGroup("QoL");

    @Override
    public void onInitialize() {
        LOG.info("Initializing QoL Meteor Addon");

        // Modules
        Modules.get().add(new ModuleExample());
        Modules.get().add(new PlayerWarner());

        // Commands
        Commands.add(new CommandExample());

        // HUD
        Hud.get().register(HudExample.INFO);
        Hud.get().register(TotemCounter.INFO);
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.example.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("idkbro-jpg", "QolMeteorAddon");
    }
}
