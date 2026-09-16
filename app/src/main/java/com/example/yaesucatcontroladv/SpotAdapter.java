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

        // Copiem seturile de filtre DOAR dacă primim referințe diferite,
        // pentru a evita bug-ul de self-clearing (dacă bands == currentFilterBands).
        if (bands != null && bands != currentFilterBands) {
            currentFilterBands.clear();
            currentFilterBands.addAll(bands);
        }

        if (modes != null && modes != currentFilterModes) {
            currentFilterModes.clear();
            currentFilterModes.addAll(modes);
        }

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
        // Container vertical: rândul 1 = ora/frecvență/spot/spotter,
        // rândul 2 = comentariul, pe toată lățimea (nu se mai îngustează
        // când indicativul sau spotter-ul sunt lungi).
        LinearLayout root = new LinearLayout(parent.getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 15, 20, 15);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout rowSus = new LinearLayout(parent.getContext());
        rowSus.setOrientation(LinearLayout.HORIZONTAL);
        rowSus.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(rowSus);

        return new SpotViewHolder(root, rowSus);
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

        public SpotViewHolder(@NonNull LinearLayout root, @NonNull LinearLayout rowSus) {
            super(root);
            this.root = root;

            tvOra = createTv(12, Typeface.MONOSPACE, Color.WHITE);
            tvFreq = createTv(13, Typeface.DEFAULT_BOLD, Color.WHITE);
            tvDx = createTv(14, Typeface.DEFAULT_BOLD, Color.YELLOW);
            tvSpotter = createTv(10, Typeface.MONOSPACE, Color.parseColor("#CCCCCC"));

            // Rândul 1: ora, frecvența, spot (indicativ DX)
            rowSus.addView(tvOra);
            rowSus.addView(tvFreq);
            rowSus.addView(tvDx);

            // Spacer elastic - împinge callsign-ul spotter-ului spre dreapta
            View spacer = new View(itemView.getContext());
            LinearLayout.LayoutParams pSpacer = new LinearLayout.LayoutParams(0, 0, 1f);
            spacer.setLayoutParams(pSpacer);
            rowSus.addView(spacer);

            rowSus.addView(tvSpotter);

            // Rândul 2: comentariul, pe toată lățimea rândului
            tvComment = createTv(12, Typeface.DEFAULT, Color.WHITE);
            LinearLayout.LayoutParams pComment = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            pComment.topMargin = 6;
            pComment.leftMargin = 40;
            tvComment.setLayoutParams(pComment);
            root.addView(tvComment);
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