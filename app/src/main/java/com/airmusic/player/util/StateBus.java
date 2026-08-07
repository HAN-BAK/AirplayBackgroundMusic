package com.airmusic.player.util;

import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Simple observable holder for the current player UI state.
 */
public final class StateBus {

    public interface Listener {
        void onStateChanged(PlayerUiState state);
    }

    private static final StateBus INSTANCE = new StateBus();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile PlayerUiState state = new PlayerUiState();

    private StateBus() {
    }

    public static StateBus get() {
        return INSTANCE;
    }

    public PlayerUiState getState() {
        return state;
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
            main.post(() -> listener.onStateChanged(state));
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void postState(PlayerUiState newState) {
        state = newState;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            notifyListeners();
        } else {
            main.post(this::notifyListeners);
        }
    }

    private void notifyListeners() {
        for (Listener l : listeners) {
            try {
                l.onStateChanged(state);
            } catch (Throwable ignored) {
            }
        }
    }
}
