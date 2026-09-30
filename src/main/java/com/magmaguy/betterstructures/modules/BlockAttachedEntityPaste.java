package com.magmaguy.betterstructures.modules;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.Vector3;
import com.sk89q.worldedit.math.transform.AffineTransform;
import com.sk89q.worldedit.util.Direction;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.util.concurrency.LazyReference;
import org.enginehub.linbus.tree.LinCompoundTag;
import org.enginehub.linbus.tree.LinDoubleTag;
import org.enginehub.linbus.tree.LinListTag;
import org.enginehub.linbus.tree.LinNumberTag;
import org.enginehub.linbus.tree.LinTagType;

import java.util.List;

/**
 * Lets WorldEdit's entity copy paste block-attached entities such as paintings and item frames.
 * Minecraft checks their block_pos against the position stored in their NBT while loading them, before
 * WorldEdit moves them, and that stored position still points at the world the module was built in.
 * WorldEdit also moves their block to the rotated spot without turning the direction they face.
 * Either way the server discards them with "Block-attached entity at invalid position".
 */
final class BlockAttachedEntityPaste {
    /** Painting "facing" holds Minecraft's horizontal direction ids; item frame "Facing" the full ids. */
    private static final List<Direction> HORIZONTAL_IDS = List.of(Direction.SOUTH, Direction.WEST, Direction.NORTH, Direction.EAST);
    private static final List<Direction> DIRECTION_IDS = List.of(Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH,
            Direction.WEST, Direction.EAST);

    private BlockAttachedEntityPaste() {
    }

    /** The world, with each created entity's stored Pos set to the position WorldEdit creates it at. */
    static Extent destination(org.bukkit.World world) {
        return new AbstractDelegateExtent(BukkitAdapter.adapt(world)) {
            @Override
            public Entity createEntity(Location location, BaseEntity entity) {
                return super.createEntity(location, withPosition(entity, location));
            }
        };
    }

    static BaseEntity withPosition(BaseEntity entity, Location location) {
        LinCompoundTag nbt = entity == null ? null : entity.getNbt();
        if (nbt == null || !nbt.value().containsKey("Pos")) return entity;
        LinCompoundTag positioned = nbt.toBuilder().put("Pos", LinListTag.builder(LinTagType.doubleTag())
                .add(LinDoubleTag.of(location.getX()))
                .add(LinDoubleTag.of(location.getY()))
                .add(LinDoubleTag.of(location.getZ()))
                .build()).build();
        return new BaseEntity(entity.getType(), LazyReference.computed(positioned));
    }

    /** The entity with its facing turned by the module's rotation, or the entity itself when nothing turns. */
    static Entity rotated(Entity entity, AffineTransform transform) {
        if (transform.isIdentity()) return entity;
        BaseEntity state = entity.getState();
        LinCompoundTag nbt = state == null ? null : state.getNbt();
        if (nbt == null) return entity;
        LinCompoundTag rotated = rotate(rotate(nbt, "facing", HORIZONTAL_IDS, transform), "Facing", DIRECTION_IDS, transform);
        if (rotated == nbt) return entity;
        BaseEntity rotatedState = new BaseEntity(state.getType(), LazyReference.computed(rotated));
        return new Entity() {
            @Override public BaseEntity getState() { return rotatedState; }
            @Override public boolean remove() { return entity.remove(); }
            @Override public <T> T getFacet(Class<? extends T> cls) { return entity.getFacet(cls); }
            @Override public Location getLocation() { return entity.getLocation(); }
            @Override public boolean setLocation(Location location) { return entity.setLocation(location); }
            @Override public Extent getExtent() { return entity.getExtent(); }
        };
    }

    static LinCompoundTag rotate(LinCompoundTag nbt, String key, List<Direction> ids, AffineTransform transform) {
        if (!(nbt.value().get(key) instanceof LinNumberTag<?> tag)) return nbt;
        int id = tag.value().intValue();
        if (id < 0 || id >= ids.size()) return nbt;
        // Only the rotation applies to a direction, not the transform's translation.
        Vector3 turned = transform.apply(ids.get(id).toVector()).subtract(transform.apply(Vector3.ZERO));
        int rotatedId = ids.indexOf(Direction.findClosest(turned, Direction.Flag.CARDINAL | Direction.Flag.UPRIGHT));
        if (rotatedId < 0 || rotatedId == id) return nbt;
        return nbt.toBuilder().putByte(key, (byte) rotatedId).build();
    }
}
