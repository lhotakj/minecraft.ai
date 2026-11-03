package com.example.aibuild;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.function.Consumer;

/**
 * Places (or restores) blocks a few thousand per tick so a big build never stalls the server.
 *
 * <p>For a build, pass a {@code previousOut} array: each block that actually changes has its old
 * state stored there, which is what {@code /ai undo} later restores. For an undo, pass
 * {@code null}; entries of {@code target} that are null are skipped.
 */
final class BlockWorker extends BukkitRunnable {

    private final World world;
    private final int[] xs;
    private final int[] ys;
    private final int[] zs;
    private final BlockData[] target;
    private final List<BuildPlanner.ItemInfo>[] items;
    private final BlockData[] previousOut;
    private final int perTick;
    private final Runnable onDone;
    private final Consumer<RuntimeException> onError;
    private int cursor;

    BlockWorker(World world, int[] xs, int[] ys, int[] zs, BlockData[] target,
                List<BuildPlanner.ItemInfo>[] items, BlockData[] previousOut,
                int perTick, Runnable onDone, Consumer<RuntimeException> onError) {
        this.world = world;
        this.xs = xs;
        this.ys = ys;
        this.zs = zs;
        this.target = target;
        this.items = items;
        this.previousOut = previousOut;
        this.perTick = Math.max(1, perTick);
        this.onDone = onDone;
        this.onError = onError;
    }

    BlockWorker(World world, int[] xs, int[] ys, int[] zs, BlockData[] target, BlockData[] previousOut,
                int perTick, Runnable onDone, Consumer<RuntimeException> onError) {
        this(world, xs, ys, zs, target, null, previousOut, perTick, onDone, onError);
    }

    @Override
    public void run() {
        try {
            int end = Math.min(cursor + perTick, xs.length);
            for (; cursor < end; cursor++) {
                BlockData wanted = target[cursor];
                if (wanted == null) {
                    continue;
                }
                Block block = world.getBlockAt(xs[cursor], ys[cursor], zs[cursor]);
                if (previousOut != null) {
                    BlockData current = block.getBlockData();
                    if (current.equals(wanted)) {
                        continue; // nothing to change, nothing to undo
                    }
                    previousOut[cursor] = current;
                }
                block.setBlockData(wanted, false); // false = skip physics updates
                if (items != null && items[cursor] != null && !items[cursor].isEmpty()) {
                    if (block.getState() instanceof Container container) {
                        for (BuildPlanner.ItemInfo item : items[cursor]) {
                            Material mat = Material.matchMaterial(item.materialName());
                            if (mat != null && mat.isItem()) {
                                container.getInventory().addItem(new ItemStack(mat, item.count()));
                            }
                        }
                        container.update(true, false);
                    }
                }
            }
            if (cursor >= xs.length) {
                cancel();
                onDone.run();
            }
        } catch (RuntimeException e) {
            cancel();
            onError.accept(e);
        }
    }
}
