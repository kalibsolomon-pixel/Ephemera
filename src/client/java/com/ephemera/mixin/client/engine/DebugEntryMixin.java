package com.ephemera.mixin.client.engine;

import com.ephemera.EphemeraCommon;
import com.ephemera.engine.client.DebugEntryText;
import java.util.IdentityHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.minecraft.client.gui.components.debug.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DebugScreenOverlay.class)
abstract class DebugEntryMixin {
    @Shadow @Final private Minecraft minecraft;
    @Unique private final IdentityHashMap<DebugScreenEntry, DebugEntryText> ephemera$text = new IdentityHashMap<>();
    @Unique private long ephemera$refreshedAt;
    @Unique private long ephemera$version;
    @Unique private Level ephemera$level;
    @Unique private LevelChunk ephemera$clientChunk;
    @Unique private LevelChunk ephemera$serverChunk;

    @Inject(method = {"reset", "clearChunkCache"}, at = @At("HEAD"))
    private void ephemera$clear(CallbackInfo ci) {
        ephemera$text.clear();
        ephemera$level = null;
        ephemera$clientChunk = null;
        ephemera$serverChunk = null;
    }

    @Redirect(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/debug/DebugScreenEntry;display(Lnet/minecraft/client/gui/components/debug/DebugScreenDisplayer;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/chunk/LevelChunk;)V"))
    private void ephemera$entry(DebugScreenEntry entry, DebugScreenDisplayer output, Level level, LevelChunk client, LevelChunk server) {
        if (!EphemeraCommon.config().engineDebugHudCache) {
            entry.display(output, level, client, server);
            return;
        }
        long now = System.nanoTime();
        long version = minecraft.debugEntries.getCurrentlyEnabledVersion();
        if (now - ephemera$refreshedAt >= 50_000_000L || ephemera$version != version
                || level != ephemera$level || client != ephemera$clientChunk || server != ephemera$serverChunk) {
            ephemera$text.clear();
            ephemera$refreshedAt = now;
            ephemera$version = version;
            ephemera$level = level;
            ephemera$clientChunk = client;
            ephemera$serverChunk = server;
        }
        DebugEntryText text = ephemera$text.get(entry);
        if (text == null) {
            text = new DebugEntryText();
            entry.display(text, level, client, server);
            ephemera$text.put(entry, text);
        }
        text.replay(output);
    }
}
