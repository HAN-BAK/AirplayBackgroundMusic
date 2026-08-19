package com.airmusic.player.ui;

import android.net.Uri;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.airmusic.player.R;
import com.airmusic.player.library.Track;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TrackAdapter extends RecyclerView.Adapter<TrackAdapter.Holder> {

    public interface OnTrackClick {
        void onTrackClick(Track track);
    }

    public interface OnTrackLongClick {
        void onTrackLongClick(Track track);
    }

    public interface OnSelectionChanged {
        void onSelectionChanged(int count);
    }

    private final List<Track> tracks = new ArrayList<>();
    private final Set<Integer> selected = new HashSet<>();
    private OnTrackClick listener;
    private OnTrackLongClick longListener;
    private OnSelectionChanged selectionListener;
    private Uri currentUri;
    private String currentTitle;
    private String currentArtist;
    private boolean selectionMode;

    public void setTracks(List<Track> newTracks) {
        tracks.clear();
        tracks.addAll(newTracks);
        selected.clear();
        notifyDataSetChanged();
    }

    /** Highlights the track with this URI (local playback). */
    public void setCurrentUri(Uri uri) {
        this.currentUri = uri;
        this.currentTitle = null;
        this.currentArtist = null;
        notifyDataSetChanged();
    }

    /** Highlights the track matching this title/artist (multi-room receiver). */
    public void setCurrentTitleArtist(String title, String artist) {
        this.currentUri = null;
        this.currentTitle = title;
        this.currentArtist = artist;
        notifyDataSetChanged();
    }

    private boolean isCurrent(Track track) {
        if (currentUri != null && track.uri != null && currentUri.equals(track.uri)) {
            return true;
        }
        return currentTitle != null && currentTitle.length() > 0
                && currentTitle.equals(track.displayTitle())
                && (currentArtist == null || currentArtist.length() == 0
                || currentArtist.equals(track.displayArtist()));
    }

    public void setOnTrackClick(OnTrackClick listener) {
        this.listener = listener;
    }

    public void setOnTrackLongClick(OnTrackLongClick listener) {
        this.longListener = listener;
    }

    public void setOnSelectionChanged(OnSelectionChanged listener) {
        this.selectionListener = listener;
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    /** Enters or leaves multi-select mode; clears the selection when leaving. */
    public void setSelectionMode(boolean mode) {
        if (selectionMode == mode) return;
        selectionMode = mode;
        if (!mode) {
            selected.clear();
        }
        notifyDataSetChanged();
        if (selectionListener != null) {
            selectionListener.onSelectionChanged(mode ? selected.size() : -1);
        }
    }

    /** Toggles a position's selection (selection mode only). */
    public void toggleSelection(int position) {
        if (!selectionMode || position < 0 || position >= tracks.size()) return;
        if (!selected.remove(position)) {
            selected.add(position);
        }
        notifyItemChanged(position);
        if (selectionListener != null) {
            selectionListener.onSelectionChanged(selected.size());
        }
    }

    public int getSelectedCount() {
        return selected.size();
    }

    /** Returns the selected tracks, preserving list order. */
    public List<Track> getSelectedTracks() {
        List<Track> out = new ArrayList<>();
        for (Integer pos : selected) {
            if (pos >= 0 && pos < tracks.size()) {
                out.add(tracks.get(pos));
            }
        }
        return out;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_track, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Track track = tracks.get(position);
        android.content.Context ctx = holder.itemView.getContext();
        String title = track.displayTitle();
        String artist = track.displayArtist();
        String album = track.displayAlbum();
        holder.title.setText(Track.UNKNOWN_TITLE.equals(title)
                ? ctx.getString(R.string.unknown_title) : title);
        holder.subtitle.setText(
                (Track.UNKNOWN_ARTIST.equals(artist)
                        ? ctx.getString(R.string.unknown_artist) : artist)
                        + " · "
                        + (Track.UNKNOWN_ALBUM.equals(album)
                        ? ctx.getString(R.string.unknown_album) : album));
        boolean isSelected = selected.contains(position);
        holder.title.setTextColor(ContextCompat.getColor(holder.itemView.getContext(),
                isCurrent(track) ? R.color.accent : R.color.text_primary));
        holder.check.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        holder.check.setChecked(isSelected);
        holder.itemView.setBackgroundColor(isSelected
                ? ContextCompat.getColor(holder.itemView.getContext(), R.color.surface_light)
                : Color.TRANSPARENT);
        holder.itemView.setOnLongClickListener(v -> {
            if (!selectionMode) {
                setSelectionMode(true);
                selected.add(position);
                notifyItemChanged(position);
                if (selectionListener != null) {
                    selectionListener.onSelectionChanged(selected.size());
                }
                if (longListener != null) longListener.onTrackLongClick(track);
                return true;
            }
            return false;
        });
        holder.itemView.setOnClickListener(v -> {
            if (selectionMode) {
                toggleSelection(position);
            } else if (listener != null) {
                listener.onTrackClick(track);
            }
        });
    }

    @Override
    public int getItemCount() {
        return tracks.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final CheckBox check;
        final TextView title;
        final TextView subtitle;

        Holder(@NonNull View itemView) {
            super(itemView);
            check = itemView.findViewById(R.id.track_check);
            title = itemView.findViewById(R.id.track_title);
            subtitle = itemView.findViewById(R.id.track_subtitle);
        }
    }
}
