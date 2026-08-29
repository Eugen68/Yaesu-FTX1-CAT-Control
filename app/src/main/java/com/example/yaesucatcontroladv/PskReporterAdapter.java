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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Adapter RecyclerView pentru rapoartele de recepție PSKReporter (cine ne-a auzit).
 */
public class PskReporterAdapter extends RecyclerView.Adapter<PskReporterAdapter.PskViewHolder> {

    public interface OnReportClickListener {
        void onReportClick(PskSpot spot);
    }

    private final List<PskSpot> fullList = new ArrayList<>();
    private final List<PskSpot> filteredList = new ArrayList<>();
    private final Set<String> currentFilterBands = new HashSet<>();
    private final Set<String> currentFilterModes = new HashSet<>();
    private final OnReportClickListener clickListener;

    public PskReporterAdapter(OnReportClickListener listener) {
        this.clickListener = listener;
    }

    public void updateList(List<PskSpot> newSpots) {
        fullList.clear();
        fullList.addAll(newSpots);
        applyFilter(currentFilterBands, currentFilterModes);
    }

    /**
     * Filtrare pe bandă/mod, identică ca abordare cu SpotAdapter de la DX Cluster.
     */
    public void applyFilter(Set<String> bands, Set<String> modes) {
        currentFilterBands.clear();
        if (bands != null) currentFilterBands.addAll(bands);
        currentFilterModes.clear();
        if (modes != null) currentFilterModes.addAll(modes);

        filteredList.clear();
        for (PskSpot s : fullList) {
            if (respectsFilters(s)) filteredList.add(s);
        }
        notifyDataSetChanged();
    }

    private boolean respectsFilters(PskSpot s) {
        if (!currentFilterBands.isEmpty() && !currentFilterBands.contains(s.band)) return false;
        if (!currentFilterModes.isEmpty()) {
            String mode = s.mode != null ? s.mode.toUpperCase(Locale.US) : "";
            if (!currentFilterModes.contains(mode)) return false;
        }
        return true;
    }

    @NonNull
    @Override
    public PskViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
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

        return new PskViewHolder(root, rowSus);
    }

    @Override
    public void onBindViewHolder(@NonNull PskViewHolder holder, int position) {
        holder.bind(filteredList.get(position), clickListener);
    }

    @Override
    public int getItemCount() {
        return filteredList.size();
    }

    static class PskViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvOra, tvFreq, tvReceiver, tvSnr;
        private final TextView tvDetalii;
        private final LinearLayout root;

        public PskViewHolder(@NonNull LinearLayout root, @NonNull LinearLayout rowSus) {
            super(root);
            this.root = root;

            tvOra = createTv(12, Typeface.MONOSPACE, Color.WHITE);
            tvFreq = createTv(13, Typeface.DEFAULT_BOLD, Color.WHITE);
            tvReceiver = createTv(14, Typeface.DEFAULT_BOLD, Color.YELLOW);

            rowSus.addView(tvOra);
            rowSus.addView(tvFreq);
            rowSus.addView(tvReceiver);

            View spacer = new View(itemView.getContext());
            LinearLayout.LayoutParams pSpacer = new LinearLayout.LayoutParams(0, 0, 1f);
            spacer.setLayoutParams(pSpacer);
            rowSus.addView(spacer);

            tvSnr = createTv(12, Typeface.MONOSPACE, Color.parseColor("#CCCCCC"));
            tvSnr.setGravity(Gravity.END);
            rowSus.addView(tvSnr);

            tvDetalii = createTv(12, Typeface.DEFAULT, Color.parseColor("#DDDDDD"));
            LinearLayout.LayoutParams pDetalii = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            pDetalii.topMargin = 6;
            pDetalii.leftMargin = 40;
            tvDetalii.setLayoutParams(pDetalii);
            root.addView(tvDetalii);
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

        public void bind(PskSpot spot, OnReportClickListener listener) {
            tvOra.setText(spot.getOraUTC());
            tvFreq.setText(String.format(Locale.US, "%.5f", spot.frequencyMhz));
            tvReceiver.setText(spot.receiverCallsign);
            tvSnr.setText(spot.snrDb != null ? String.format(Locale.US, "%+d dB", spot.snrDb) : "");

            StringBuilder detalii = new StringBuilder();
            detalii.append(spot.mode != null ? spot.mode : "??");
            if (spot.receiverLocator != null) detalii.append(" · ").append(spot.receiverLocator);
            if (spot.distanceKm != null) detalii.append(" · ").append(String.format(Locale.US, "%.0f km", spot.distanceKm));
            tvDetalii.setText(detalii.toString());

            root.setBackgroundColor(getCuloareBanda(spot.band));
            root.setOnClickListener(v -> listener.onReportClick(spot));
        }

        private int getCuloareBanda(String banda) {
            if (banda == null) return Color.parseColor("#424242");
            switch (banda) {
                case "160m": return Color.parseColor("#4A148C");
                case "80m": return Color.parseColor("#1A237E");
                case "60m": return Color.parseColor("#3E2723");
                case "40m": return Color.parseColor("#004D40");
                case "30m": return Color.parseColor("#006064");
                case "20m": return Color.parseColor("#E65100");
                case "17m": return Color.parseColor("#33691E");
                case "15m": return Color.parseColor("#BF360C");
                case "12m": return Color.parseColor("#F57F17");
                case "10m": return Color.parseColor("#B71C1C");
                case "6m": return Color.parseColor("#880E4F");
                case "2m": return Color.parseColor("#311B92");
                default: return Color.parseColor("#424242");
            }
        }
    }
}
