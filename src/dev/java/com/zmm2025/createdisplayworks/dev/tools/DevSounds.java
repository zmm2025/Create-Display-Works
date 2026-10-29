package com.zmm2025.createdisplayworks.dev.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.openal.AL10;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Reports what the game is playing, so agents can check sounds without recording audio. Lists the sounds that hold a
 * channel right now, with the volume each reaches the listener at, and every sound started in a recent window.
 */
final class DevSounds {
    private DevSounds() {}

    private static final int HISTORY = 512;
    private static final Deque<Played> played = new ArrayDeque<>();
    private static SoundEventListener listener;

    private record Played(long nanos, String id, String source, float volume, float pitch, double x, double y, double z, float range, String instance) {}

    /** Starts recording sound starts. Runs on the render thread, which is where sounds start. */
    static void track() {
        if(listener != null) return;
        listener = (sound, events, range) -> {
            synchronized(played) {
                if(played.size() >= HISTORY) played.removeFirst();
                played.addLast(new Played(System.nanoTime(), sound.getLocation().toString(), sound.getSource().getName(), sound.getVolume(), sound.getPitch(),
                    sound.getX(), sound.getY(), sound.getZ(), range, sound.getClass().getSimpleName()));
            }
        };
        Minecraft.getInstance().getSoundManager().addListener(listener);
    }

    static JsonObject sounds(long sinceMs, String filter) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.submit(() -> {
            track();
            Vec3 ear = minecraft.getSoundManager().getListenerTransform().position();
            JsonObject response = new JsonObject();
            response.addProperty("ok", true);
            response.addProperty("listener", DevTools.round(ear.x) + " " + DevTools.round(ear.y) + " " + DevTools.round(ear.z));
            response.addProperty("masterVolume", minecraft.options.getSoundSourceVolume(SoundSource.MASTER));
            // What the sound engine actually plays at: 0 while the window is hidden, whatever the option says.
            response.addProperty("outputGain", DevTools.round(AL10.alGetListenerf(AL10.AL_GAIN)));

            JsonArray playing = new JsonArray();
            for(SoundInstance sound : channels(minecraft.getSoundManager()).keySet()) {
                if(!matches(sound.getLocation().toString(), filter)) continue;
                JsonObject json = describe(sound, ear, minecraft);
                if(sound instanceof TickableSoundInstance tickable) json.addProperty("stopped", tickable.isStopped());
                playing.add(json);
            }
            response.add("playing", playing);

            JsonArray started = new JsonArray();
            long now = System.nanoTime(), since = now - TimeUnit.MILLISECONDS.toNanos(sinceMs);
            synchronized(played) {
                for(Played sound : played) {
                    if(sound.nanos < since || !matches(sound.id, filter)) continue;
                    JsonObject json = new JsonObject();
                    json.addProperty("agoMs", TimeUnit.NANOSECONDS.toMillis(now - sound.nanos));
                    json.addProperty("id", sound.id);
                    json.addProperty("source", sound.source);
                    json.addProperty("volume", sound.volume);
                    json.addProperty("pitch", sound.pitch);
                    json.addProperty("pos", DevTools.round(sound.x) + " " + DevTools.round(sound.y) + " " + DevTools.round(sound.z));
                    json.addProperty("distance", DevTools.round(ear.distanceTo(new Vec3(sound.x, sound.y, sound.z))));
                    json.addProperty("range", Float.isInfinite(sound.range) ? -1 : sound.range);
                    json.addProperty("instance", sound.instance);
                    started.add(json);
                }
            }
            response.add("started", started);
            return response;
        }).get(1, TimeUnit.MINUTES);
    }

    /**
     * Estimates the gain the listener hears: the sound's volume times its category's slider, faded linearly to zero at
     * {@code max(volume, 1) * attenuation distance}, which is how the sound engine sets up OpenAL. Master volume is
     * reported separately.
     */
    private static JsonObject describe(SoundInstance sound, Vec3 ear, Minecraft minecraft) {
        JsonObject json = new JsonObject();
        json.addProperty("id", sound.getLocation().toString());
        json.addProperty("source", sound.getSource().getName());
        json.addProperty("instance", sound.getClass().getSimpleName());
        float volume = sound.getVolume();
        float category = sound.getSource() == SoundSource.MASTER ? 1 : minecraft.options.getSoundSourceVolume(sound.getSource());
        float gain = Math.clamp(volume * category, 0, 1);
        json.addProperty("volume", volume);
        json.addProperty("pitch", sound.getPitch());
        Vec3 pos = new Vec3(sound.getX(), sound.getY(), sound.getZ());
        json.addProperty("pos", DevTools.round(pos.x) + " " + DevTools.round(pos.y) + " " + DevTools.round(pos.z));
        double distance = sound.isRelative() ? pos.length() : ear.distanceTo(pos);
        json.addProperty("distance", DevTools.round(distance));
        Sound resolved = sound.getSound();
        if(sound.getAttenuation() == SoundInstance.Attenuation.LINEAR && resolved != null) {
            float range = Math.max(volume, 1) * resolved.getAttenuationDistance();
            json.addProperty("range", range);
            gain *= (float)Math.max(0, 1 - distance / range);
        } else json.addProperty("range", -1);
        json.addProperty("heardGain", Math.round(gain * 1000) / 1000.0);
        json.addProperty("looping", sound.isLooping());
        return json;
    }

    private static boolean matches(String id, String filter) {
        return filter == null || filter.isBlank() || id.contains(filter);
    }

    @SuppressWarnings("unchecked")
    private static Map<SoundInstance, ?> channels(SoundManager manager) {
        try {
            Field engineField = SoundManager.class.getDeclaredField("soundEngine");
            engineField.setAccessible(true);
            Field channelsField = SoundEngine.class.getDeclaredField("instanceToChannel");
            channelsField.setAccessible(true);
            return (Map<SoundInstance, ?>)channelsField.get(engineField.get(manager));
        } catch(ReflectiveOperationException e) {
            throw new IllegalStateException("Couldn't read the sound engine's channels", e);
        }
    }
}
