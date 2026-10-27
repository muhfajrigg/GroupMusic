package me.muhfajrigg.music;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import me.muhfajrigg.music.command.LaguCommand;
import me.muhfajrigg.music.config.ConfigManager;
import me.muhfajrigg.music.config.Messages;
import me.muhfajrigg.music.music.MusicManager;
import me.muhfajrigg.music.voice.VoiceHook;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class GroupMusic extends JavaPlugin {

    private ConfigManager config;
    private Messages messages;
    private MusicManager music;

    /** Configuration manager holding all YAML config files. */
    public ConfigManager cfg() {
        return config;
    }

    public Messages messages() {
        return messages;
    }

    @Override
    public void onEnable() {
        config = new ConfigManager(this);
        config.load();
        messages = new Messages(config);

        BukkitVoicechatService service = getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (service == null) {
            getLogger().severe("Simple Voice Chat service not found. GroupMusic disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        music = new MusicManager(this);
        service.registerPlugin(new VoiceHook(music));

        LaguCommand command = new LaguCommand(this, music);
        for (String name : LaguCommand.NAMES) {
            PluginCommand pc = getCommand(name);
            if (pc != null) {
                pc.setExecutor(command);
                pc.setTabCompleter(command);
            }
        }

        music.start();
        getLogger().info("GroupMusic enabled successfully.");
    }

    @Override
    public void onDisable() {
        if (music != null) {
            music.shutdown();
        }
    }

    /** Reload all configuration and message files. */
    public void reloadAll() {
        config.load();
    }
}
