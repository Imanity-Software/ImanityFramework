package org.imanity.framework.hologram;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.v1_8_R3.CraftWorld;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.imanity.framework.hologram.api.PlaceholderViewHandler;
import org.imanity.framework.hologram.api.TextViewHandler;
import org.imanity.framework.hologram.api.ViewHandler;
import org.imanity.framework.hologram.player.RenderedHolograms;
import org.imanity.framework.util.RV;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Getter
@Setter
public class Hologram {

    private static final float Y_PER_LINE = 0.25F;
    private static int NEW_ID = 0;

    private HologramHandler hologramHandler;

    private int id;
    private Location location;
    private boolean spawned;

    private Entity attachedTo;

    private List<HologramSingle> lines = new ArrayList<>();
    private List<Player> renderedPlayers = new ArrayList<>();

    public Hologram(Location location, HologramHandler hologramHandler) {
        this.id = NEW_ID++;
        this.location = location;
        this.hologramHandler = hologramHandler;
    }

    public double getX() {
        return this.location.getX();
    }

    public double getY() {
        return this.location.getY();
    }

    public double getZ() {
        return this.location.getZ();
    }

    public World getWorld() {
        return this.location.getWorld();
    }

    public void addText(String text) {
        this.addView(new TextViewHandler(text));
    }

    public void addView(ViewHandler viewHandler) {
        this.setView(this.lines.size(), viewHandler);
    }

    public void addText(String text, RV...replaceValues) {
        this.addView(new TextViewHandler(text), replaceValues);
    }

    public void addView(ViewHandler viewHandler, RV...replaceValues) {
        this.setView(this.lines.size(), new PlaceholderViewHandler(viewHandler, replaceValues));
    }

    public void setText(int index, String text) {
        this.setView(index, new TextViewHandler(text));
    }

    public void update() {
        this.lines.forEach(hologram -> {
            hologram.build(true);
            hologram.sendNamePackets(this.renderedPlayers);
        });
    }

    public void setView(int index, ViewHandler viewHandler) {

        if (index >= this.lines.size()) {
            HologramSingle single = new HologramSingle(this, viewHandler, -Y_PER_LINE * index, index);
            this.lines.add(index, single);

            if (this.isSpawned()) {
                single.build(false);
                single.setPacketsBuilt(true);

                single.send(this.location.getWorld().getPlayers());
            }
        } else {
            HologramSingle single = this.lines.get(index);
            if (single.getIndex() != index) {
                this.lines.add(index, single);
            }

            single.setViewHandler(viewHandler);
            single.build(true);
            single.sendNamePackets(this.renderedPlayers);
        }

    }

    public void removeView(int index) {
        if (lines.size() > index) {
            HologramSingle single = this.lines.get(index);
            single.sendRemove(this.renderedPlayers);
            this.lines.remove(index);
        }
    }

    public void setLocation(Location location) {
        this.move(location);
    }

    private void move(@NonNull Location location) {
        if (this.location.equals(location)) {
            return;
        }

        if (!this.location.getWorld().equals(location.getWorld())) {
            throw new IllegalArgumentException("cannot move to different world");
        }

        this.location = location;

        if (this.isSpawned()) {

            List<Player> players = this.location.getWorld().getPlayers();
            this.lines.forEach(hologram -> {
                hologram.build(true);
                hologram.sendTeleportPacket(players);
            });

        }
    }

    public boolean isAttached() {
        return attachedTo != null;
    }

    public void spawnPlayer(Player player) {
        this.lines.forEach(hologram -> {
            if (!hologram.isPacketsBuilt()) {
                hologram.build(false);
                hologram.setPacketsBuilt(true);
            }

            hologram.send(Collections.singleton(player));
        });
        this.renderedPlayers.add(player);
    }

    protected List<Player> getNearbyPlayers() {
        return ((CraftWorld) this.getWorld()).getHandle()
                .playerMap
                .getNearbyPlayersIgnoreHeight(this.getX(), 0, this.getZ(), HologramHandler.DISTANCE_TO_RENDER)
                .stream()
                .map(EntityPlayer::getBukkitEntity)
                .collect(Collectors.toList());
    }

    public void spawn() {
        this.validateDespawned();

        this.getNearbyPlayers()
                .forEach(this.hologramHandler::update);

        this.spawned = true;
    }

    public void removePlayer(Player player) {
        this.lines.forEach(hologram -> hologram.sendRemove(Collections.singleton(player)));
        this.renderedPlayers.remove(player);
    }

    public boolean remove() {
        this.validateSpawned();

        this.renderedPlayers
                .forEach(player -> {
                    RenderedHolograms holograms = this.hologramHandler.getRenderedHolograms(player);
                    holograms.removeHologram(player, this);
                });
        this.renderedPlayers.clear();

        this.spawned = false;
        return true;
    }

    public double distaneTo(Player player) {
        return Math.sqrt(Math.pow(this.getLocation().getX() - player.getLocation().getX(), 2)
                + Math.pow(this.getLocation().getZ() - player.getLocation().getZ(), 2));
    }

    private void validateSpawned() {
        if (!this.spawned)
            throw new IllegalStateException("Not spawned");
    }

    private void validateDespawned() {
        if (this.spawned)
            throw new IllegalStateException("Already spawned");
    }

}