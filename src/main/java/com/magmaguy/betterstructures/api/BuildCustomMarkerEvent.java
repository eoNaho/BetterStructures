package com.magmaguy.betterstructures.api;

import com.magmaguy.betterstructures.buildingfitter.FitAnything;
import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Fired on the main thread for each [custom] sign after its structure's blocks have been pasted.
 * BetterStructures removes the sign first, so the listener can place anything at that location.
 * The text under [custom] is passed on unchanged for the listening plugin to interpret.
 */
public class BuildCustomMarkerEvent extends Event {
    private static final HandlerList handlers = new HandlerList();
    private final FitAnything fitAnything;
    private final Location location;
    private final List<String> lines;

    public BuildCustomMarkerEvent(FitAnything fitAnything, Location location, List<String> lines) {
        this.fitAnything = fitAnything;
        this.location = location.clone();
        this.lines = List.copyOf(lines);
    }

    /**
     * The structure being pasted, as in {@link BuildPlaceEvent}.
     *
     * @return The structure's fitting and schematic data.
     */
    public FitAnything getFitAnything() {
        return fitAnything;
    }

    /**
     * @return World location of the block the sign occupied. The block is air when this event fires.
     */
    public Location getLocation() {
        return location.clone();
    }

    /**
     * @return Lines two to four of the sign, trimmed. Blank lines are empty strings.
     */
    public List<String> getLines() {
        return lines;
    }

    /**
     * Lines two to four joined without separators, so a long ID can continue onto the next line.
     *
     * @return The marker ID, for example {@code Zombie_Plus}.
     */
    public String getId() {
        return String.join("", lines);
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}
