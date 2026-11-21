package com.ephemera.engine.client;

import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.resources.Identifier;

/** Replay just the text commands into this frame's live overlay collector. */
public final class DebugEntryText implements DebugScreenDisplayer {
    private final List<Consumer<DebugScreenDisplayer>> commands = new ArrayList<>();

    public void replay(DebugScreenDisplayer destination) {
        for (int i = 0; i < commands.size(); i++) commands.get(i).accept(destination);
    }

    @Override public void addPriorityLine(String text) {
        commands.add(out -> out.addPriorityLine(text));
    }

    @Override public void addLine(String text) {
        commands.add(out -> out.addLine(text));
    }

    @Override public void addToGroup(Identifier group, String text) {
        commands.add(out -> out.addToGroup(group, text));
    }

    @Override public void addToGroup(Identifier group, Collection<String> lines) {
        List<String> copy = new ArrayList<>(lines);
        commands.add(out -> out.addToGroup(group, copy));
    }
}
