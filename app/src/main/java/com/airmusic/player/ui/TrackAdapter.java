package com.airmusic.player.ui;

import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.airmusic.player.R;
import com.airmusic.player.library.Track;

import java.util.ArrayList;
import java.util.List;

public class TrackAdapter extends RecyclerView.Adapter<TrackAdapter.Holder> {

    public interface OnTrackClick {
        void onTrackClick(Track track);
    }

    private final List<Track> tracks = new ArrayList<>();
    private OnTrackClick listener;
    private Uri currentUri;
    private String currentTitle;
    private String currentArtist;

    public void setTracks(List<Track> newTracks) {
        tracks.clear();
        tracks.addAll(newTracks);
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
        holder.title.setText(track.displayTitle());
        holder.subtitle.setText(track.displayArtist() + " · " + track.displayAlbum());
        holder.title.setTextColor(ContextCompat.getColor(holder.itemView.getContext(),
                isCurrent(track) ? R.color.accent : R.color.text_primary));
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onTrackClick(track);
        });
    }

    @Override
    public int getItemCount() {
        return tracks.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView subtitle;

        Holder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.track_title);
            subtitle = itemView.findViewById(R.id.track_subtitle);
        }
    }
}
