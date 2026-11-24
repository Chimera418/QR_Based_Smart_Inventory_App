package com.yourname.qrinventory.ui;

import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.yourname.qrinventory.R;
import com.yourname.qrinventory.model.BasketLine;
import com.yourname.qrinventory.utils.CartReservationManager;
import com.yourname.qrinventory.utils.ShakeDetector;
import com.yourname.qrinventory.viewmodel.BasketViewModel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class BasketFragment extends Fragment {

    private BasketViewModel vm;
    private BasketAdapter adapter;
    private TextView tvSubtotal, empty, tvCartTimer;
    private Button btnCheckout;
    private View checkoutBar;
    private List<BasketLine> latest = new ArrayList<>();
    // Swiped-away lines are hidden here until the Undo Snackbar finishes
    private final Set<Long> pending = new HashSet<>();

    private SensorManager sensorManager;
    private ShakeDetector shake;
    private AlertDialog clearDialog;
    private int shownCount = 0;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private boolean expiryRequested = false;
    private final Runnable timerRunnable = new Runnable() {
        @Override public void run() {
            if (!isAdded()) return;
            refreshTimerUi();
            if (shownCount > 0) {
                timerHandler.postDelayed(this, 1000L);
            }
        }
    };

    public BasketFragment() { super(R.layout.fragment_basket); }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        vm = new ViewModelProvider(requireActivity()).get(BasketViewModel.class);
        tvSubtotal = view.findViewById(R.id.tv_subtotal);
        empty = view.findViewById(R.id.tv_empty);
        tvCartTimer = view.findViewById(R.id.tv_cart_timer);
        btnCheckout = view.findViewById(R.id.btn_checkout);
        checkoutBar = view.findViewById(R.id.checkout_bar);

        RecyclerView rv = view.findViewById(R.id.rv_basket);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new BasketAdapter(new BasketAdapter.Actions() {
            @Override public void onQuantityChange(BasketLine line, int q) { changeQty(line, q); }
            @Override public void onEdit(BasketLine line) { showEditDialog(line); }
            @Override public void onRemove(BasketLine line) { removeWithUndo(line); }
        });
        rv.setAdapter(adapter);

        // Swipe left or right to remove (CO3: gestures)
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0,
                ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override public boolean onMove(@NonNull RecyclerView r,
                                            @NonNull RecyclerView.ViewHolder vh, @NonNull RecyclerView.ViewHolder t) { return false; }
            @Override public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) {
                int pos = vh.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) removeWithUndo(adapter.getCurrentList().get(pos));
            }
        }).attachToRecyclerView(rv);

        vm.lines.observe(getViewLifecycleOwner(), lines -> {
            latest = lines == null ? new ArrayList<>() : lines;
            render();
        });

        btnCheckout.setOnClickListener(v -> {
            commitPending();   // make sure swiped items are really gone before checkout
            startActivity(new Intent(requireContext(), CheckoutActivity.class)); // explicit intent
        });

        sensorManager = (SensorManager) requireContext().getSystemService(Context.SENSOR_SERVICE);
        shake = new ShakeDetector(this::confirmClear);

        requireActivity().addMenuProvider(new MenuProvider() {
            @Override public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
                inflater.inflate(R.menu.basket_menu, menu);
            }
            @Override public boolean onMenuItemSelected(@NonNull MenuItem item) {
                if (item.getItemId() == R.id.action_clear) { confirmClear(); return true; }
                return false;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
    }

    // Sensor listeners are registered only while this tab is visible (CO1: lifecycle)
    @Override
    public void onResume() {
        super.onResume();
        Sensor acc = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (acc != null) sensorManager.registerListener(shake, acc, SensorManager.SENSOR_DELAY_UI);
        expiryRequested = false;
        vm.checkReservation(expired -> {
            if (Boolean.TRUE.equals(expired) && isAdded()) {
                requireActivity().runOnUiThread(() ->
                        snack("Reservation expired (5 min) — items returned to stock"));
            }
        });
        timerHandler.removeCallbacks(timerRunnable);
        timerHandler.post(timerRunnable);
    }

    @Override
    public void onPause() {
        super.onPause();
        sensorManager.unregisterListener(shake);
        timerHandler.removeCallbacks(timerRunnable);
    }

    private void confirmClear() {
        if (shownCount == 0) { snack("Basket is already empty"); return; }
        if (clearDialog != null && clearDialog.isShowing()) return;   // ignore repeated shakes
        clearDialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Clear basket?")
                .setMessage("Remove all " + shownCount + " item(s) and return them to inventory?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (d, w) -> { vm.clear(); snack("Basket cleared — items returned to inventory"); })
                .show();
    }

    private void render() {
        Set<Long> ids = new HashSet<>();
        for (BasketLine l : latest) ids.add(l.basketId);
        pending.retainAll(ids);

        List<BasketLine> shown = new ArrayList<>();
        double subtotal = 0;
        for (BasketLine l : latest) {
            if (pending.contains(l.basketId)) continue;
            shown.add(l);
            subtotal += l.price * l.quantity;
        }
        shownCount = shown.size();
        adapter.submitList(shown);
        empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        tvSubtotal.setText(String.format(Locale.getDefault(), "Subtotal: ₹%.2f", subtotal));
        btnCheckout.setEnabled(!shown.isEmpty());
        refreshTimerUi();
        if (!shown.isEmpty() && isResumed()) {
            timerHandler.removeCallbacks(timerRunnable);
            timerHandler.postDelayed(timerRunnable, 1000L);
        }
    }

    private void refreshTimerUi() {
        if (tvCartTimer == null || !isAdded()) return;
        Context ctx = requireContext();
        if (shownCount == 0) {
            expiryRequested = false;
            tvCartTimer.setVisibility(View.GONE);
            return;
        }
        if (CartReservationManager.isExpired(ctx)) {
            tvCartTimer.setVisibility(View.GONE);
            if (!expiryRequested) {
                expiryRequested = true;
                vm.checkReservation(expired -> {
                    if (Boolean.TRUE.equals(expired) && isAdded()) {
                        requireActivity().runOnUiThread(() ->
                                snack("Reservation expired (5 min) — items returned to stock"));
                    }
                });
            }
            return;
        }
        if (CartReservationManager.getExpiresAt(ctx) <= 0L) {
            CartReservationManager.startIfNeeded(ctx);
        }
        long rem = CartReservationManager.getRemainingMillis(ctx);
        tvCartTimer.setText("Reserved for " + CartReservationManager.formatMmSs(rem)
                + " — pay before timer expires");
        tvCartTimer.setVisibility(View.VISIBLE);
    }

    private void changeQty(BasketLine line, int q) {
        int maxAvailable = line.quantity + line.stock;
        if (q <= 0) removeWithUndo(line);
        else if (q > maxAvailable) snack(line.stock <= 0 ? "No more stock available" : "Only " + line.stock + " more in stock");
        else vm.changeQuantity(line, q);
    }

    private void removeWithUndo(BasketLine line) {
        pending.add(line.basketId);
        render();
        Snackbar.make(requireView(), "Removed " + line.name, Snackbar.LENGTH_LONG)
                .setAnchorView(checkoutBar)
                .setAction("UNDO", v -> { pending.remove(line.basketId); render(); })
                .addCallback(new Snackbar.Callback() {
                    @Override public void onDismissed(Snackbar sb, int event) {
                        if (event != DISMISS_EVENT_ACTION) vm.remove(line.basketId);
                    }
                }).show();
    }

    private void commitPending() {
        for (Long id : new ArrayList<>(pending)) vm.remove(id);
    }

    private void showEditDialog(BasketLine line) {
        EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(line.quantity));
        input.setSelectAllOnFocus(true);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        FrameLayout box = new FrameLayout(requireContext());
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Edit quantity")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Update", (d, w) -> {
                    try { changeQty(line, Integer.parseInt(input.getText().toString().trim())); }
                    catch (NumberFormatException e) { snack("Enter a valid number"); }
                }).show();
    }

    private void snack(String msg) {
        Snackbar.make(requireView(), msg, Snackbar.LENGTH_SHORT).setAnchorView(checkoutBar).show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (clearDialog != null) clearDialog.dismiss();
        commitPending();
        pending.clear();
    }
}