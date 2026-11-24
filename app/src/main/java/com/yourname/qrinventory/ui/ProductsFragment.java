package com.yourname.qrinventory.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.print.PrintHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.yourname.qrinventory.R;
import com.yourname.qrinventory.model.Product;
import com.yourname.qrinventory.utils.QrUtils;
import com.yourname.qrinventory.utils.SessionManager;
import com.yourname.qrinventory.viewmodel.BasketViewModel;
import com.yourname.qrinventory.viewmodel.ProductViewModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ProductsFragment extends Fragment {

    private ProductViewModel vm;
    private ProductAdapter adapter;
    private ChipGroup chipGroup;
    private TextView empty;
    private List<Product> latest = new ArrayList<>();
    private String selectedCategory = null;      // null = All
    private int sortMode = R.id.sort_name;

    public ProductsFragment() { super(R.layout.fragment_products); }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        final boolean admin = SessionManager.isAdmin(requireContext());

        vm = new ViewModelProvider(requireActivity()).get(ProductViewModel.class);
        chipGroup = view.findViewById(R.id.chip_group);
        empty = view.findViewById(R.id.tv_empty);

        RecyclerView rv = view.findViewById(R.id.rv_products);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        BasketViewModel basketVm = new ViewModelProvider(requireActivity()).get(BasketViewModel.class);
        adapter = new ProductAdapter(p -> {
            if (p.stock <= 0) {
                Toast.makeText(requireContext(), p.name + " is out of stock", Toast.LENGTH_SHORT).show();
                return;
            }
            basketVm.onScanned(p.qrCode);
            Toast.makeText(requireContext(), "Added " + p.name, Toast.LENGTH_SHORT).show();
        });
        rv.setAdapter(adapter);
        adapter.setMenuActions(new ProductAdapter.MenuActions() {
            @Override public void onEdit(Product p) {
                startActivity(new Intent(requireContext(), AddProductActivity.class)
                        .putExtra("product_id", (long) p.id));
            }
            @Override public void onShowQr(Product p) {
                startActivity(new Intent(requireContext(), QrCodeActivity.class)
                        .putExtra("qr", p.qrCode).putExtra("name", p.name));
            }
            @Override public void onDelete(Product p) {
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Delete " + p.name + "?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete", (d, w) -> vm.delete(p))
                        .show();
            }
        });
        view.findViewById(R.id.fab_add).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), AddProductActivity.class)));
        view.findViewById(R.id.fab_add).setVisibility(admin ? View.VISIBLE : View.GONE);
        if (!admin) adapter.setMenuActions(null);   // no edit/QR/delete menu for customers

        vm.products.observe(getViewLifecycleOwner(), list -> {
            latest = list;
            applyFilters();
        });
        vm.categories.observe(getViewLifecycleOwner(), this::buildChips);

        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
                inflater.inflate(R.menu.products_menu, menu);
                menu.findItem(R.id.action_print_qr).setVisible(admin);
                menu.findItem(R.id.sort_stock).setVisible(admin);
                menu.findItem(sortMode).setChecked(true);
                SearchView sv = (SearchView) menu.findItem(R.id.action_search).getActionView();
                searchItem = menu.findItem(R.id.action_search);
                searchView = sv;
                sv.setQueryHint("Search products");
                sv.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                    @Override public boolean onQueryTextSubmit(String q) { vm.setQuery(q); return true; }
                    @Override public boolean onQueryTextChange(String q) { vm.setQuery(q); return true; }
                });
            }
            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem item) {
                int id = item.getItemId();
                if (id == R.id.sort_name || id == R.id.sort_price_low
                        || id == R.id.sort_price_high || id == R.id.sort_stock) {
                    sortMode = id;
                    item.setChecked(true);
                    applyFilters();
                    return true;
                }
                if (id == R.id.action_print_qr) { printSheet(); return true; }
                if (id == R.id.action_settings) {
                    startActivity(new Intent(requireContext(), SettingsActivity.class));
                    return true;
                }
                if (id == R.id.action_voice) { startVoiceSearch(); return true; }
                return false;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
    }
    private MenuItem searchItem;
    private SearchView searchView;

    private final ActivityResultLauncher<Intent> voiceLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
                ArrayList<String> r = result.getData().getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (r == null || r.isEmpty()) return;
                String text = r.get(0);
                if (searchItem != null) searchItem.expandActionView();
                if (searchView != null) searchView.setQuery(text, true);
                else vm.setQuery(text);
            });
    private void printSheet() {
        if (latest == null || latest.isEmpty()) {
            Toast.makeText(requireContext(), "No products to print", Toast.LENGTH_SHORT).show();
            return;
        }
        PrintHelper helper = new PrintHelper(requireContext());
        helper.setScaleMode(PrintHelper.SCALE_MODE_FIT);
        helper.printBitmap("Product QR codes", QrUtils.sheet(latest));
    }

    private void buildChips(List<String> categories) {
        chipGroup.removeAllViews();
        addChip("All", null);
        if (categories != null) for (String c : categories) addChip(c, c);
        chipGroup.setOnCheckedStateChangeListener((group, ids) -> {
            if (ids.isEmpty()) return;
            Chip chip = group.findViewById(ids.get(0));
            selectedCategory = (String) chip.getTag();
            applyFilters();
        });
    }

    private void addChip(String label, String category) {
        Chip chip = (Chip) getLayoutInflater().inflate(R.layout.chip_category, chipGroup, false);
        chip.setId(View.generateViewId());
        chip.setText(label);
        chip.setTag(category);
        chipGroup.addView(chip);
        if (category == null ? selectedCategory == null : category.equals(selectedCategory)) {
            chip.setChecked(true);
        }
    }

    private void applyFilters() {
        if (latest == null) return;
        List<Product> out = new ArrayList<>();
        for (Product p : latest) {
            if (selectedCategory == null || selectedCategory.equalsIgnoreCase(p.category)) out.add(p);
        }
        if (sortMode == R.id.sort_price_low) {
            Collections.sort(out, (a, b) -> Double.compare(a.price, b.price));
        } else if (sortMode == R.id.sort_price_high) {
            Collections.sort(out, (a, b) -> Double.compare(b.price, a.price));
        } else if (sortMode == R.id.sort_stock) {
            Collections.sort(out, (a, b) -> Integer.compare(a.stock, b.stock));
        } else {
            Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        }
        adapter.submitList(out);
        empty.setVisibility(out.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void startVoiceSearch() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a product name");
        try {
            voiceLauncher.launch(i);   // implicit intent to the system speech recognizer
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), "Voice search is not available on this device", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        vm.setQuery("");
    }
}