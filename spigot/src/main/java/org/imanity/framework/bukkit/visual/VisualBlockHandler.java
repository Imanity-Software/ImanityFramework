package org.imanity.framework.bukkit.visual;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.base.Predicate;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import net.minecraft.server.v1_8_R3.BlockPosition;
import net.minecraft.server.v1_8_R3.PacketPlayInFlying;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.material.MaterialData;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;
import org.imanity.framework.bukkit.Imanity;
import org.imanity.framework.bukkit.util.CoordXZ;
import org.imanity.framework.bukkit.util.CoordinatePair;
import org.imanity.framework.bukkit.util.TaskUtil;
import org.imanity.framework.bukkit.visual.event.PreHandleVisualClaimEvent;
import org.imanity.framework.bukkit.visual.event.PreHandleVisualEvent;
import org.imanity.framework.bukkit.visual.type.VisualType;
import org.imanity.framework.data.PlayerData;
import spg.lgdev.handler.MovementHandler;
import spg.lgdev.iSpigot;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class VisualBlockHandler implements Runnable {

    private final Table<UUID, VisualPosition, VisualBlock> table = HashBasedTable.create();
    private final LoadingCache<CoordinatePair, Optional<VisualBlockClaim>> claimCache;
    private final Table<CoordinatePair, CoordXZ, VisualBlockClaim> claimPositionTable;
    private final Queue<VisualTask> visualTasks = new ConcurrentLinkedQueue<>();

    public VisualBlockHandler() {
        this.claimPositionTable = HashBasedTable.create();
        this.claimCache = Caffeine.newBuilder()
                .maximumSize(8000)
                .build(coordinatePair -> {
                    final int chunkX = coordinatePair.getX() >> 4;
                    final int chunkZ = coordinatePair.getZ() >> 4;
                    final int posX = coordinatePair.getX() % 16;
                    final int posZ = coordinatePair.getZ() % 16;
                    synchronized (claimPositionTable) {
                        return Optional.ofNullable(claimPositionTable.get(new CoordinatePair(coordinatePair.getWorldName(), chunkX, chunkZ), new CoordXZ((byte) posX, (byte) posZ)));
                    }
                });
        TaskUtil.runAsyncRepeated(this, 1L);
        iSpigot.INSTANCE.addMovementHandler(new MovementHandler() {
            @Override
            public void handleUpdateLocation(Player player, Location to, Location from, PacketPlayInFlying packetPlayInFlying) {
                if (to.getBlockX() == from.getBlockX()
                    && to.getBlockY() == from.getBlockY()
                    && to.getBlockZ() == from.getBlockZ()) {
                    return;
                }

                handlePositionChanged(player, to);
            }

            @Override
            public void handleUpdateRotation(Player player, Location location, Location location1, PacketPlayInFlying packetPlayInFlying) {

            }
        });
    }

    public void cacheClaim(VisualBlockClaim claim) {
        final World world = claim.getWorld();
        final int minX = Math.min(claim.getMaxX(), claim.getMinX());
        final int maxX = Math.max(claim.getMaxX(), claim.getMinX());
        final int minZ = Math.min(claim.getMaxZ(), claim.getMinZ());
        final int maxZ = Math.max(claim.getMaxZ(), claim.getMinZ());
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                final CoordinatePair worldPosition = new CoordinatePair(world, x, z);
                final CoordinatePair chunkPair = new CoordinatePair(world, x >> 4, z >> 4);
                final CoordXZ chunkPosition = new CoordXZ((byte) (x % 16), (byte) (z % 16));
                synchronized (claimPositionTable) {
                    claimPositionTable.put(chunkPair, chunkPosition, claim);
                }
                claimCache.invalidate(worldPosition);
            }
        }
    }

    public void clearAll(final Player player, final boolean send) {
        table.rowMap().remove(player.getUniqueId());
        ((CraftPlayer) player).getHandle().clearFakeBlocks(send);
    }

    public void clearVisualType(final Player player, final VisualType visualType, final boolean send) {
        clearVisualType(player, visualType, null, send);
    }

    public void clearVisualType(final Player player, final VisualType visualType, final Predicate<VisualBlock> predicate, final boolean send) {
        final List<BlockPosition> removeFromClient = new ArrayList<>();
        synchronized (table) {
            final Map<VisualPosition, VisualBlock> currentBlocks = table.row(player.getUniqueId());
            for (final Map.Entry<VisualPosition, VisualBlock> entry : new ArrayList<>(currentBlocks.entrySet())) {
                final VisualPosition blockPosition = entry.getKey();
                final VisualBlock visualBlock = entry.getValue();
                final VisualType blockVisualType = visualBlock.getVisualType();
                if (blockVisualType.equals(visualType) && (predicate == null || predicate.apply(visualBlock))) {
                    removeFromClient.add(blockPosition);
                    currentBlocks.remove(blockPosition);
                }
            }
        }
        ((CraftPlayer) player).getHandle().setFakeBlocks(Collections.emptyMap(), removeFromClient, send);
    }

    public Map<BlockPosition, MaterialData> addVisualType(final Player player, final Collection<VisualPosition> locations, final boolean send) {
        final Map<BlockPosition, MaterialData> sendToClient = new HashMap<>();
        locations.removeIf(blockPosition -> {
            final World world = player.getWorld();
            final Block block = world.getBlockAt(blockPosition.getX(), blockPosition.getY(), blockPosition.getZ());
            final Material material = block.getType();
            return material.isSolid();
        });
        synchronized (table) {
            final Iterator<VisualPosition> iterator = locations.iterator();
            for (VisualPosition blockPosition : locations) {
                VisualType visualType = blockPosition.getType();
                VisualBlockData visualBlockData = visualType.generate(player, blockPosition);
                sendToClient.put(blockPosition, visualBlockData);
                table.put(player.getUniqueId(), blockPosition, new VisualBlock(visualType, visualBlockData, blockPosition));
            }
        }
        ((CraftPlayer) player).getHandle().setFakeBlocks(sendToClient, Collections.emptyList(), send);
        return sendToClient;
    }

    public Map<BlockPosition, MaterialData> setVisualType(final Player player, final Collection<VisualPosition> locations, final boolean send) {
        final Map<BlockPosition, MaterialData> sendToClient = new HashMap<>();
        final List<BlockPosition> removeFromClient = new ArrayList<>();
        locations.removeIf(blockPosition -> {
            final World world = player.getWorld();
            final Block block = world.getBlockAt(blockPosition.getX(), blockPosition.getY(), blockPosition.getZ());
            final Material material = block.getType();
            return material.isSolid();
        });
        synchronized (table) {
            final Map<VisualPosition, VisualBlock> currentBlocks = table.row(player.getUniqueId());
            for (final Map.Entry<VisualPosition, VisualBlock> entry : new ArrayList<>(currentBlocks.entrySet())) {
                final VisualPosition blockPosition = entry.getKey();
                final VisualBlock visualBlock = entry.getValue();
                final VisualType blockVisualType = visualBlock.getVisualType();
                if (blockVisualType.equals(blockPosition.getType())) {
                    if (!locations.remove(blockPosition)) {
                        removeFromClient.add(blockPosition);
                        currentBlocks.remove(blockPosition);
                    }
                }
            }
            for (VisualPosition blockPosition : locations) {
                VisualType visualType = blockPosition.getType();
                VisualBlockData visualBlockData = visualType.generate(player, blockPosition);
                sendToClient.put(blockPosition, visualBlockData);
                table.put(player.getUniqueId(), blockPosition, new VisualBlock(visualType, visualBlockData, blockPosition));
            }
        }
        ((CraftPlayer) player).getHandle().setFakeBlocks(sendToClient, removeFromClient, send);
        return sendToClient;
    }

    public VisualBlockClaim getClaimAt(final Location location) {
        return getTeamAt(location.getWorld(), location.getBlockX(), location.getBlockZ());
    }

    public VisualBlockClaim getTeamAt(final World world, final int x, final int z) {
        try {
            return claimCache.get(new CoordinatePair(world, x, z)).orElse(null);
        } catch (final Exception exception) {
            exception.printStackTrace();
            final int chunkX = x >> 4;
            final int chunkZ = z >> 4;
            final byte posX = (byte) (x % 16);
            final byte posZ = (byte) (z % 16);
            synchronized (claimPositionTable) {
                return claimPositionTable.get(new CoordinatePair(world, chunkX, chunkZ), new CoordXZ(posX, posZ));
            }
        }
    }

    public void handlePositionChanged(final Player player, final Location location) {
        if (this.claimPositionTable.isEmpty()) {
            return;
        }

        PreHandleVisualEvent event = new PreHandleVisualEvent(player);
        Imanity.callEvent(event);

        if (event.isCancelled()) {
            return;
        }

        final int minHeight = location.getBlockY() - 5;
        final int maxHeight = location.getBlockY() + 4;

        final int toX = location.getBlockX();
        final int toZ = location.getBlockZ();

        final Collection<VisualBlockClaim> claimCache = new HashSet<>();

        for (int x = toX - 7; x < toX + 7; x++) {
            for (int z = toZ - 7; z < toZ + 7; z++) {
                final VisualBlockClaim color = this.getTeamAt(location.getWorld(), x, z);
                PreHandleVisualClaimEvent claimEvent = new PreHandleVisualClaimEvent(player, color);

                Imanity.callEvent(claimEvent);

                if (color != null && !claimEvent.isCancelled()) {
                    claimCache.add(color);
                }
            }
        }

        final List<VisualPosition> blockPositions = new ArrayList<>();

        if (!claimCache.isEmpty()) {
            final Iterator<VisualBlockClaim> claims = claimCache.iterator();
            while (claims.hasNext()) {

                VisualBlockClaim claim = claims.next();
                VisualType type = claim.getType();

                for (final Vector edge : this.getEdges(claim)) {
                    if (Math.abs(edge.getBlockX() - toX) > 7) {
                        continue;
                    }
                    if (Math.abs(edge.getBlockZ() - toZ) > 7) {
                        continue;
                    }
                    final Location location2 = edge.toLocation(location.getWorld());
                    if (location2 == null) {
                        continue;
                    }
                    for (int y = minHeight; y <= maxHeight; y++) {
                        blockPositions.add(new VisualPosition(location2.getBlockX(), y, location2.getBlockZ(), type));
                    }
                }

                claims.remove();
            }
        }

        if (player.isOnline()) {
            visualTasks.removeIf(visualTask -> visualTask.getPlayer() == player);
            visualTasks.add(new VisualTask(player, blockPositions));
        }
    }

    public List<Vector> getEdges(VisualBlockClaim claim) {

        final int minX = Math.min(claim.getMinX(), claim.getMaxX());
        final int maxX = Math.max(claim.getMinX(), claim.getMaxX());
        final int minZ = Math.min(claim.getMinZ(), claim.getMaxZ());
        final int startX = minZ + 1;
        final int maxZ = Math.max(claim.getMinZ(), claim.getMaxZ());
        int capacity = (maxX - minX) * 4 + (maxZ - minZ) * 4;
        capacity += 4;
        if (capacity <= 0) {
            return new ArrayList<>();
        }
        final List<Vector> result = new ArrayList<>(capacity);
        final int minY = Math.min(claim.getMinY(), claim.getMaxY());
        final int maxY = Math.max(claim.getMinY(), claim.getMaxY());
        for (int z = minX; z <= maxX; ++z) {
            result.add(new Vector(z, minY, minZ));
            result.add(new Vector(z, minY, maxZ));
            result.add(new Vector(z, maxY, minZ));
            result.add(new Vector(z, maxY, maxZ));
        }
        for (int z = startX; z < maxZ; ++z) {
            result.add(new Vector(minX, minY, z));
            result.add(new Vector(minX, maxY, z));
            result.add(new Vector(maxX, minY, z));
            result.add(new Vector(maxX, maxY, z));
        }
        return result;

    }

    public void addVisualTask(Player player, VisualTask task) {
        this.visualTasks.removeIf(otherTask -> otherTask.getPlayer() == player);
        this.visualTasks.add(task);
    }

    public void clear() {
        Bukkit.getOnlinePlayers().forEach(player -> this.clearAll(player, true));
    }

    @Override
    public void run() {
        VisualTask visualTask;
        while ((visualTask = visualTasks.poll()) != null) {
            this.setVisualType(visualTask.getPlayer(), visualTask.getBlockPositions(), true);
        }
    }
}