package com.airmusic.player.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

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

    public void setTracks(List<Track> newTracks) {
        tracks.clear();
        tracks.addAll(newTracks);
        notifyDataSetChanged();
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
