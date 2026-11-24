package com.yourname.qrinventory.ui;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.charts.PieChart;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.yourname.qrinventory.R;
import com.yourname.qrinventory.data.InventoryRepository;
import com.yourname.qrinventory.model.Order;
import com.yourname.qrinventory.service.WorkScheduler;
import com.yourname.qrinventory.utils.ChartHelper;
import com.yourname.qrinventory.utils.ReportExporter;
import com.yourname.qrinventory.utils.SessionManager;
import com.yourname.qrinventory.utils.ShareUtils;
import com.yourname.qrinventory.viewmodel.ReportViewModel;

import java.io.File;

public class HistoryFragment extends Fragment {

    private final ActivityResultLauncher<String> importPicker =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) importCsv(uri);
            });

    private final ActivityResultLauncher<String> notificationPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!isAdded()) return;
                if (granted) runAlertCheck();
                else Toast.makeText(requireContext(),
                        "Notifications are off for this app. Enable them in system settings to see alerts.",
                        Toast.LENGTH_LONG).show();
            });

    private void runAlertCheck() {
        WorkScheduler.runNow(requireContext());
        Toast.makeText(requireContext(), "Checking stock and expiry...", Toast.LENGTH_SHORT).show();
    }
    public HistoryFragment() { super(R.layout.fragment_history); }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Context ctx = requireContext();
        boolean admin = SessionManager.isAdmin(ctx);
        ReportViewModel vm = new ViewModelProvider(this).get(ReportViewModel.class);

        View adminReportsGroup = view.findViewById(R.id.admin_reports_group);
        adminReportsGroup.setVisibility(admin ? View.VISIBLE : View.GONE);

        if (admin) {
            // Charts (admin-only analytics)
            PieChart pie = view.findViewById(R.id.pie_chart);
            BarChart bar = view.findViewById(R.id.bar_chart);
            LineChart line = view.findViewById(R.id.line_chart);
            pie.setTouchEnabled(false);    // so the page scrolls smoothly over the charts
            bar.setTouchEnabled(false);
            line.setTouchEnabled(false);
            vm.stockByCategory.observe(getViewLifecycleOwner(), d -> ChartHelper.showStockPie(pie, d));
            vm.topSellers.observe(getViewLifecycleOwner(), d -> ChartHelper.showTopSellersBar(bar, d));
            vm.dailySales.observe(getViewLifecycleOwner(), d -> ChartHelper.showDailySalesLine(line, d));

            // Reports, CSV import/export, and background stock/expiry alert checks (admin-only)
            view.findViewById(R.id.btn_export_inventory).setOnClickListener(v ->
                    ReportExporter.exportInventoryCsv(ctx, (file, err) ->
                            deliver(file, err, "text/csv", "Inventory export")));
            view.findViewById(R.id.btn_export_low).setOnClickListener(v ->
                    ReportExporter.exportLowStockCsv(ctx, (file, err) ->
                            deliver(file, err, "text/csv", "Low stock report")));
            view.findViewById(R.id.btn_import).setOnClickListener(v -> importPicker.launch("*/*"));
            view.findViewById(R.id.btn_refresh_alerts).setOnClickListener(v -> {
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx,
                        Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
                } else {
                    runAlertCheck();
                }
            });
        }

        // Order history (visible to both customers and admin)
        TextView none = view.findViewById(R.id.tv_no_orders);
        RecyclerView rv = view.findViewById(R.id.rv_orders);
        rv.setLayoutManager(new LinearLayoutManager(ctx));
        rv.setNestedScrollingEnabled(false);
        OrderAdapter adapter = new OrderAdapter(this::onOrderClicked);
        rv.setAdapter(adapter);
        vm.orders.observe(getViewLifecycleOwner(), list -> {
            adapter.setItems(list);
            none.setVisibility(list == null || list.isEmpty() ? View.VISIBLE : View.GONE);
        });
    }

    private void deliver(File file, String err, String mime, String subject) {
        if (!isAdded()) return;
        if (err != null) {
            Toast.makeText(requireContext(), err, Toast.LENGTH_LONG).show();
            return;
        }
        ShareUtils.shareFile(requireContext(), file, mime, subject);
    }

    private void onOrderClicked(Order o) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Order #" + o.id)
                .setItems(new String[]{"View bill (PDF)", "Share bill", "Re-order these items"}, (d, which) -> {
                    if (which == 2) { reorder(o); return; }
                    ReportExporter.exportInvoice(requireContext(), o.id, (file, err) -> {
                        if (!isAdded()) return;
                        if (err != null) {
                            Toast.makeText(requireContext(), err, Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (which == 0) ShareUtils.viewFile(requireContext(), file, "application/pdf");
                        else ShareUtils.shareFile(requireContext(), file, "application/pdf", "Bill #" + o.id);
                    });
                })
                .show();
    }

    private void reorder(Order o) {
        View root = requireView();
        new InventoryRepository(requireContext()).reorder(o.id, msg -> root.post(() ->
                Snackbar.make(root, msg, Snackbar.LENGTH_LONG)
                        .setAction("View basket", v ->
                                Navigation.findNavController(root).navigate(R.id.basketFragment))
                        .show()));
    }

    private void importCsv(Uri uri) {
        ReportExporter.importInventoryCsv(requireContext(), uri, (count, err) -> {
            if (!isAdded()) return;
            String msg = err != null ? err : count + " products imported";
            Snackbar.make(requireView(), msg, Snackbar.LENGTH_LONG).show();
        });
    }
}