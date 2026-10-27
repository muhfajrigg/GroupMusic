package me.muhfajrigg.music.voice;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import me.muhfajrigg.music.music.MusicManager;

/** Simple Voice Chat plugin hook delivering VoicechatServerApi to MusicManager. */
public final class VoiceHook implements VoicechatPlugin {

    private final MusicManager music;

    public VoiceHook(MusicManager music) {
        this.music = music;
    }

    @Override
    public String getPluginId() {
        return "groupmusic";
    }

    @Override
    public void initialize(VoicechatApi api) {
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, e -> music.onVoiceReady(e.getVoicechat()));
        registration.registerEvent(VoicechatServerStoppedEvent.class, e -> music.onVoiceStopped());
    }
}
