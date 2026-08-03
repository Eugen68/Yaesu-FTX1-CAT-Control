package com.example.yaesucatcontroladv;

import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Adapter RecyclerView pentru spoturi DX.
 * Optimizează performanța listei prin reciclarea elementelor vizuale.
 */
public class SpotAdapter extends RecyclerView.Adapter<SpotAdapter.SpotViewHolder> {

    public interface OnSpotClickListener {
        void onSpotClick(Spot spot);
    }

    private final List<Spot> fullList = new ArrayList<>();
    private final List<Spot> filteredList = new ArrayList<>();
    private final OnSpotClickListener clickListener;

    public SpotAdapter(OnSpotClickListener listener) {
        this.clickListener = listener;
    }

    public void updateList(List<Spot> newSpots) {
        this.fullList.clear();
        this.fullList.addAll(newSpots);
        applyFilter(currentFilterSearch, currentFilterBands, currentFilterModes);
    }

    private String currentFilterSearch = "";
    private final java.util.Set<String> currentFilterBands = new java.util.HashSet<>();
    private final java.util.Set<String> currentFilterModes = new java.util.HashSet<>();

    public void applyFilter(String search, java.util.Set<String> bands, java.util.Set<String> modes) {
        this.currentFilterSearch = (search != null) ? search.toUpperCase() : "";
        
        // Copiem seturile de filtre pentru a evita bug-urile de referință (self-clearing)
        currentFilterBands.clear();
        if (bands != null) currentFilterBands.addAll(bands);
        
        currentFilterModes.clear();
        if (modes != null) currentFilterModes.addAll(modes);

        filteredList.clear();
        for (Spot s : fullList) {
            if (respectsFilters(s)) {
                filteredList.add(s);
            }
        }
        notifyDataSetChanged();
    }

    private boolean respectsFilters(Spot s) {
        if (!currentFilterBands.isEmpty() && !currentFilterBands.contains(s.band)) return false;
        if (!currentFilterModes.isEmpty() && !currentFilterModes.contains(s.mode)) return false;
        if (!currentFilterSearch.isEmpty() && !s.dxCall.toUpperCase().contains(currentFilterSearch)) return false;
        return true;
    }

    @NonNull
    @Override
    public SpotViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LinearLayout layout = new LinearLayout(parent.getContext());
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setPadding(20, 15, 20, 15);
        layout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        
        // Cream ViewHolder-ul cu elementele necesare (Time, Freq, Call, Comment)
        return new SpotViewHolder(layout);
    }

    @Override
    public void onBindViewHolder(@NonNull SpotViewHolder holder, int position) {
        Spot spot = filteredList.get(position);
        holder.bind(spot, clickListener);
    }

    @Override
    public int getItemCount() {
        return filteredList.size();
    }

    static class SpotViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvOra, tvFreq, tvDx, tvComment, tvSpotter;
        private final LinearLayout root;

        public SpotViewHolder(@NonNull View itemView) {
            super(itemView);
            root = (LinearLayout) itemView;
            
            tvOra = createTv(12, Typeface.MONOSPACE, Color.WHITE);
            tvFreq = createTv(13, Typeface.DEFAULT_BOLD, Color.WHITE);
            tvDx = createTv(14, Typeface.DEFAULT_BOLD, Color.YELLOW);
            tvComment = createTv(12, Typeface.DEFAULT, Color.WHITE);
            tvSpotter = createTv(10, Typeface.MONOSPACE, Color.parseColor("#CCCCCC"));

            // Adaugam layout-ul (fara view-uri spacer goale)
            root.addView(tvOra);
            root.addView(tvFreq);
            root.addView(tvDx);
            root.addView(tvComment);
            root.addView(tvSpotter);
        }

        private TextView createTv(int size, Typeface tf, int color) {
            TextView tv = new TextView(itemView.getContext());
            tv.setTextSize(size);
            tv.setTypeface(tf);
            tv.setTextColor(color);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            p.setMarginEnd(15);
            tv.setLayoutParams(p);
            return tv;
        }

        public void bind(Spot spot, OnSpotClickListener listener) {
            tvOra.setText(spot.getOraUTC());
            tvFreq.setText(String.format(Locale.US, "%.5f", spot.frequency));
            tvDx.setText(spot.dxCall);
            tvComment.setText(spot.comment);
            tvSpotter.setText(spot.spotter != null ? "de " + spot.spotter : "");
            
            // Expandam comentariul pentru a umple spatiul
            LinearLayout.LayoutParams pC = (LinearLayout.LayoutParams) tvComment.getLayoutParams();
            pC.width = 0;
            pC.weight = 1f;
            tvComment.setLayoutParams(pC);

            root.setBackgroundColor(getCuloareBanda(spot.band));
            root.setOnClickListener(v -> listener.onSpotClick(spot));
        }

        private int getCuloareBanda(String banda) {
            switch (banda) {
                case "160m": return Color.parseColor("#4A148C");
                case "80m": return Color.parseColor("#1A237E");
                case "60m": return Color.parseColor("#3E2723"); // Maro inchis
                case "40m": return Color.parseColor("#004D40");
                case "30m": return Color.parseColor("#006064"); // Cyan inchis
                case "20m": return Color.parseColor("#E65100");
                case "17m": return Color.parseColor("#33691E"); // Verde inchis
                case "15m": return Color.parseColor("#BF360C");
                case "12m": return Color.parseColor("#F57F17"); // Galben inchis
                case "10m": return Color.parseColor("#B71C1C");
                case "6m": return Color.parseColor("#880E4F");
                case "2m": return Color.parseColor("#311B92");
                default: return Color.parseColor("#424242");
            }
        }
    }
}
