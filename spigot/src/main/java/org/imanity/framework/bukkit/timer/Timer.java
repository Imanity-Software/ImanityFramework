package org.imanity.framework.bukkit.timer;

import org.bukkit.entity.Player;

import java.util.Collection;

public interface Timer {

    boolean isTimerElapsed();

    boolean isPaused();

    boolean finish();

    void pause();

    default void clear() {
        this.clear(true);
    }

    void clear(boolean removeFromHandler);

    default void start() {

    }

    default void tick() {

    }

    default Collection<? extends Player> getReceivers() {
        return null;
    }

    String getScoreboardText(Player player);

    long timeRemaining();

    int secondsRemaining();

    void extend(long millis);

    void duration(long duration);

}
