package com.yourname.qrinventory.ui;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.razorpay.Checkout;
import com.razorpay.PaymentResultListener;
import com.yourname.qrinventory.R;
import com.yourname.qrinventory.model.BasketLine;
import com.yourname.qrinventory.service.SystemEventReceiver;
import com.yourname.qrinventory.utils.CartReservationManager;
import com.yourname.qrinventory.utils.PaymentQrParser;
import com.yourname.qrinventory.utils.ReportExporter;
import com.yourname.qrinventory.utils.SessionManager;
import com.yourname.qrinventory.utils.ShareUtils;
import com.yourname.qrinventory.viewmodel.BasketViewModel;

import org.json.JSONObject;

import java.io.File;
import java.util.Locale;
import java.util.Random;

public class CheckoutActivity extends AppCompatActivity implements PaymentResultListener {

    private static final String TAG = "CheckoutActivity";
    private static final String RAZORPAY_KEY_ID = "rzp_test_THeRsyfbR2l4r2";
    private static final String STATE_AWAITING_UPI = "awaiting_upi";
    private static final String STATE_LEFT_FOR_UPI = "left_for_upi";
    private static final String STATE_PAYEE_LABEL = "payee_label";
    private static final String STATE_PAY_METHOD = "pay_method";
    private static final String STATE_ORDER_PLACED = "order_placed";

    private BasketViewModel vm;
    private double subtotal = 0;
    private int itemCount = 0;
    private String lastTxn;
    private String lastPayMethod = "UPI";
    private String pendingPayeeLabel = PaymentQrParser.STORE_NAME;
    private String pendingReturnedTxnId;
    private boolean awaitingUpiReturn = false;
    private boolean leftForUpiApp = false;
    private boolean upiResultHandled = false;
    private boolean orderPlaced = false;
    private AlertDialog confirmDialog;
    private File invoice;
    private TextView tvItems, tvSubtotal, tvDiscount, tvGst, tvTotal, tvSuccess, tvPaymentTimer;
    private CompoundButton cbDiscount;
    private Button btnRazorpay, btnScanPay, btnUpiPay, btnCancelOrder, btnShare, btnView;
    private View summaryGroup, successGroup, cardPaymentTimer;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private final Runnable timerRunnable = this::updatePaymentTimerTick;

    // 1) Camera scans a UPI QR -> immediately opens installed UPI app picker with exact bill amount
    private final ActivityResultLauncher<Intent> payScanLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                String code = result.getData().getStringExtra("code");
                if (code == null || code.trim().isEmpty()) return;

                PaymentQrParser.ParsedQr parsed = PaymentQrParser.parse(code);
                if (!parsed.isUpi) {
                    snack("Not a valid UPI QR code. Scan a UPI payment QR to pay.");
                    return;
                }
                String payUri = PaymentQrParser.buildPayUriFromScan(parsed, currentTotal(), "QR Mart Bill");
                launchUpiApp(payUri, parsed.displayLabel(), "UPI QR");
            });

    // 2) Direct ActivityResult from the selected UPI app (GPay / PhonePe / Paytm / Navi / BHIM)
    private final ActivityResultLauncher<Intent> upiAppLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                Intent data = result.getData();
                String response = extractUpiResponseString(data);
                String fallbackStatus = data != null
                        ? (data.getStringExtra("Status") != null ? data.getStringExtra("Status") : data.getStringExtra("status"))
                        : null;
                Log.d(TAG, "UPI resultCode=" + result.getResultCode()
                        + " response=" + response + " status=" + fallbackStatus
                        + " leftForUpiApp=" + leftForUpiApp);

                PaymentQrParser.UpiResponse parsed = PaymentQrParser.parseUpiResponse(response, fallbackStatus);

                if (parsed.status == PaymentQrParser.UpiStatus.SUCCESS
                        || (result.getResultCode() == RESULT_OK && parsed.status != PaymentQrParser.UpiStatus.FAILURE)) {
                    awaitingUpiReturn = false;
                    leftForUpiApp = false;
                    upiResultHandled = true;
                    String txn = parsed.txnId != null ? parsed.txnId : generateRef();
                    completeOrderAfterPayment(txn, lastPayMethod);
                } else if (parsed.status == PaymentQrParser.UpiStatus.FAILURE) {
                    awaitingUpiReturn = false;
                    leftForUpiApp = false;
                    upiResultHandled = true;
                    snack("UPI payment failed. Please try again.");
                } else {
                    pendingReturnedTxnId = parsed.txnId;
                    if (leftForUpiApp && hasWindowFocus()) {
                        awaitingUpiReturn = false;
                        leftForUpiApp = false;
                        confirmManualUpiOutcome(pendingReturnedTxnId);
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_checkout);

        Checkout.preload(getApplicationContext());

        if (savedInstanceState != null) {
            awaitingUpiReturn = savedInstanceState.getBoolean(STATE_AWAITING_UPI, false);
            leftForUpiApp = savedInstanceState.getBoolean(STATE_LEFT_FOR_UPI, false);
            pendingPayeeLabel = savedInstanceState.getString(STATE_PAYEE_LABEL, PaymentQrParser.STORE_NAME);
            lastPayMethod = savedInstanceState.getString(STATE_PAY_METHOD, "UPI");
            orderPlaced = savedInstanceState.getBoolean(STATE_ORDER_PLACED, false);
        }

        View root = findViewById(R.id.checkout_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        setSupportActionBar(findViewById(R.id.toolbar));
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        tvItems = findViewById(R.id.tv_items);
        tvSubtotal = findViewById(R.id.tv_subtotal);
        tvDiscount = findViewById(R.id.tv_discount);
        tvGst = findViewById(R.id.tv_gst);
        tvTotal = findViewById(R.id.tv_total);
        tvSuccess = findViewById(R.id.tv_success);
        cardPaymentTimer = findViewById(R.id.card_payment_timer);
        tvPaymentTimer = findViewById(R.id.tv_payment_timer);
        cbDiscount = findViewById(R.id.cb_discount);
        btnRazorpay = findViewById(R.id.btn_razorpay);
        btnScanPay = findViewById(R.id.btn_scan_pay);
        btnUpiPay = findViewById(R.id.btn_upi_pay);
        btnCancelOrder = findViewById(R.id.btn_cancel_order);
        btnShare = findViewById(R.id.btn_share);
        btnView = findViewById(R.id.btn_view);
        summaryGroup = findViewById(R.id.summary_group);
        successGroup = findViewById(R.id.success_group);

        vm = new ViewModelProvider(this).get(BasketViewModel.class);
        vm.lines.observe(this, lines -> {
            int n = 0;
            if (lines != null) for (BasketLine l : lines) n += l.quantity;
            itemCount = n;
            tvItems.setText(n + " item(s) in basket");
        });
        vm.subtotal.observe(this, s -> {
            subtotal = s == null ? 0 : s;
            updateTotals();
        });
        cbDiscount.setOnCheckedChangeListener((b, checked) -> updateTotals());

        btnRazorpay.setOnClickListener(v -> {
            if (!checkBasketNotEmpty()) return;
            startRazorpayPayment();
        });

        btnScanPay.setOnClickListener(v -> {
            if (!checkBasketNotEmpty()) return;
            Intent intent = new Intent(this, ScannerActivity.class)
                    .putExtra(ScannerActivity.EXTRA_PAY_SCAN_MODE, true);
            payScanLauncher.launch(intent);
        });

        btnUpiPay.setOnClickListener(v -> {
            if (!checkBasketNotEmpty()) return;
            new UpiPaymentDialog(this, currentTotal(), (upiId, payeeName) -> {
                String payUri = PaymentQrParser.buildUpiUri(upiId, payeeName, currentTotal(), "QR Mart Bill");
                String label = payeeName + " (" + upiId + ")";
                launchUpiApp(payUri, label, "UPI");
            }).show();
        });

        btnCancelOrder.setOnClickListener(v -> {
            if (!checkBasketNotEmpty()) return;
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Cancel order?")
                    .setMessage("All items in your basket will be removed and their quantities will be restored to inventory.")
                    .setNegativeButton("Keep items", null)
                    .setPositiveButton("Cancel order", (d, w) -> {
                        vm.clear();
                        Toast.makeText(this, "Order cancelled. Items returned to inventory.", Toast.LENGTH_SHORT).show();
                        finish();
                    })
                    .show();
        });

        vm.orderResult.observe(this, this::onOrder);

        btnShare.setOnClickListener(v ->
                ShareUtils.shareFile(this, invoice, "application/pdf", "Your bill"));
        btnView.setOnClickListener(v -> ShareUtils.viewFile(this, invoice, "application/pdf"));
        findViewById(R.id.btn_done).setOnClickListener(v -> finish());
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (awaitingUpiReturn) {
            leftForUpiApp = true;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (awaitingUpiReturn && leftForUpiApp && !upiResultHandled && !orderPlaced) {
            awaitingUpiReturn = false;
            leftForUpiApp = false;
            confirmManualUpiOutcome(pendingReturnedTxnId);
        }
        if (!orderPlaced) {
            timerHandler.removeCallbacks(timerRunnable);
            timerHandler.post(timerRunnable);
        }
    }

    @Override
    protected void onPause() {
        timerHandler.removeCallbacks(timerRunnable);
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_AWAITING_UPI, awaitingUpiReturn);
        outState.putBoolean(STATE_LEFT_FOR_UPI, leftForUpiApp);
        outState.putString(STATE_PAYEE_LABEL, pendingPayeeLabel);
        outState.putString(STATE_PAY_METHOD, lastPayMethod);
        outState.putBoolean(STATE_ORDER_PLACED, orderPlaced);
    }

    @Override
    protected void onDestroy() {
        timerHandler.removeCallbacks(timerRunnable);
        if (confirmDialog != null && confirmDialog.isShowing()) {
            confirmDialog.dismiss();
        }
        super.onDestroy();
    }

    private String extractUpiResponseString(Intent data) {
        if (data == null) return null;
        String resp = data.getStringExtra("response");
        if (resp != null && !resp.trim().isEmpty()) return resp;
        Bundle extras = data.getExtras();
        if (extras == null || extras.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (String key : extras.keySet()) {
            Object val = extras.get(key);
            if (val != null) {
                if (sb.length() > 0) sb.append('&');
                sb.append(key).append('=').append(val);
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private void launchUpiApp(String upiUri, String payeeLabel, String methodLabel) {
        if (upiUri == null || upiUri.isEmpty()) {
            snack("Invalid UPI payment link");
            return;
        }
        Log.d(TAG, "Launching UPI URI: " + upiUri);
        pendingPayeeLabel = payeeLabel;
        lastPayMethod = methodLabel;
        pendingReturnedTxnId = null;

        Intent upiIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(upiUri));
        if (getPackageManager().queryIntentActivities(upiIntent, PackageManager.MATCH_ALL).isEmpty()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("No UPI app installed")
                    .setMessage("No UPI app (Google Pay, PhonePe, Paytm, Navi, BHIM) was found on this device to pay "
                            + String.format(Locale.getDefault(), "₹%.2f", currentTotal())
                            + " to " + payeeLabel + ".")
                    .setNegativeButton("OK", null)
                    .show();
            return;
        }

        try {
            awaitingUpiReturn = true;
            leftForUpiApp = false;
            upiResultHandled = false;
            upiAppLauncher.launch(upiIntent);
        } catch (ActivityNotFoundException e) {
            awaitingUpiReturn = false;
            snack("Could not open a UPI app on this device");
        }
    }

    private void confirmManualUpiOutcome(String returnedTxnId) {
        if (isFinishing() || isDestroyed() || orderPlaced) return;
        if (confirmDialog != null && confirmDialog.isShowing()) return;
        String amountText = subtotal > 0
                ? String.format(Locale.getDefault(), "₹%.2f", currentTotal())
                : "your bill";
        confirmDialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Confirm UPI Payment")
                .setMessage("Amount: " + amountText + " to " + pendingPayeeLabel
                        + "\n\nPersonal UPI QRs don't send automatic confirmation back from UPI apps. Tap below if your payment succeeded to view your bill.")
                .setCancelable(false)
                .setNegativeButton("Not paid / Retry", (d, w) -> {
                    if (CartReservationManager.isExpired(this)) {
                        expireCheckoutCartNow();
                    }
                })
                .setPositiveButton("I've Paid — Generate Bill", (d, w) -> {
                    String txn = returnedTxnId != null ? returnedTxnId : generateRef();
                    completeOrderAfterPayment(txn, lastPayMethod);
                })
                .show();
    }

    private void updatePaymentTimerTick() {
        if (orderPlaced || isFinishing() || isDestroyed()) return;
        if (subtotal <= 0) {
            cardPaymentTimer.setVisibility(View.GONE);
            timerHandler.postDelayed(timerRunnable, 1000L);
            return;
        }
        cardPaymentTimer.setVisibility(View.VISIBLE);
        if (CartReservationManager.getExpiresAt(this) <= 0L) {
            CartReservationManager.startIfNeeded(this);
        }
        if (CartReservationManager.isExpired(this)) {
            if (awaitingUpiReturn || (confirmDialog != null && confirmDialog.isShowing())) {
                tvPaymentTimer.setText("Payment time expired — confirm if you already paid");
                timerHandler.postDelayed(timerRunnable, 1000L);
            } else {
                expireCheckoutCartNow();
            }
            return;
        }
        long rem = CartReservationManager.getRemainingMillis(this);
        tvPaymentTimer.setText("Payment window: " + CartReservationManager.formatMmSs(rem) + " remaining");
        timerHandler.postDelayed(timerRunnable, 1000L);
    }

    private void expireCheckoutCartNow() {
        if (orderPlaced) return;
        timerHandler.removeCallbacks(timerRunnable);
        setPaymentButtonsEnabled(false);
        vm.clear();
        Toast.makeText(this,
                "Payment time (5 min) expired. Cart cleared & items returned to stock.",
                Toast.LENGTH_LONG).show();
        finish();
    }

    private String generateRef() {
        return String.format(Locale.US, "UPI%09d", 100000000 + new Random().nextInt(900000000));
    }

    private boolean checkBasketNotEmpty() {
        if (subtotal <= 0) {
            snack("Basket is empty");
            return false;
        }
        return true;
    }

    private double currentTotal() {
        double discount = cbDiscount.isChecked() ? subtotal * 0.10 : 0;
        return (subtotal - discount) * 1.05;
    }

    private void startRazorpayPayment() {
        if (!SystemEventReceiver.isOnline(this)) {
            snack("You are offline. Connect to the internet to use Razorpay.");
            return;
        }
        awaitingUpiReturn = false;
        leftForUpiApp = false;
        long amountPaise = Math.max(100L, Math.round(currentTotal() * 100.0));
        setPaymentButtonsEnabled(false);

        Checkout checkout = new Checkout();
        checkout.setKeyID(RAZORPAY_KEY_ID);
        checkout.setImage(R.mipmap.ic_launcher);

        try {
            JSONObject options = new JSONObject();
            options.put("name", PaymentQrParser.STORE_NAME);
            options.put("description", "Order (" + itemCount + (itemCount == 1 ? " item)" : " items)"));
            options.put("currency", "INR");
            options.put("amount", amountPaise);

            JSONObject theme = new JSONObject();
            theme.put("color", "#2E7D32");
            options.put("theme", theme);

            JSONObject prefill = new JSONObject();
            String email = SessionManager.getEmail(this);
            prefill.put("email", (email != null && !email.isEmpty()) ? email : "shopper@qrmart.com");
            prefill.put("contact", "9999999999");
            options.put("prefill", prefill);

            checkout.open(this, options);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open Razorpay Checkout", e);
            setPaymentButtonsEnabled(true);
            snack("Could not start Razorpay: " + e.getMessage());
        }
    }

    @Override
    public void onPaymentSuccess(String razorpayPaymentId) {
        Log.d(TAG, "Razorpay payment success: " + razorpayPaymentId);
        completeOrderAfterPayment(razorpayPaymentId, "Razorpay");
    }

    @Override
    public void onPaymentError(int code, String response) {
        Log.w(TAG, "Razorpay payment error (" + code + "): " + response);
        setPaymentButtonsEnabled(true);
        if (code == Checkout.PAYMENT_CANCELED) {
            snack("Payment cancelled");
            return;
        }
        String msg = "Please try again";
        if (response != null && !response.trim().isEmpty()) {
            try {
                JSONObject obj = new JSONObject(response);
                JSONObject err = obj.optJSONObject("error");
                String desc = err != null ? err.optString("description", "") : "";
                msg = !desc.isEmpty() ? desc : response;
            } catch (Exception ignored) {
                msg = response;
            }
        }
        snack("Payment failed: " + msg);
    }

    private void completeOrderAfterPayment(String txnId, String methodLabel) {
        if (orderPlaced) return;
        orderPlaced = true;
        timerHandler.removeCallbacks(timerRunnable);
        cardPaymentTimer.setVisibility(View.GONE);
        lastTxn = txnId;
        lastPayMethod = methodLabel;
        setPaymentButtonsEnabled(false);
        vm.checkout(cbDiscount.isChecked() ? 10 : 0);
    }

    private void setPaymentButtonsEnabled(boolean enabled) {
        btnRazorpay.setEnabled(enabled);
        btnScanPay.setEnabled(enabled);
        btnUpiPay.setEnabled(enabled);
        btnCancelOrder.setEnabled(enabled);
    }

    private void updateTotals() {
        double disc = cbDiscount.isChecked() ? subtotal * 0.10 : 0;
        double gst = (subtotal - disc) * 0.05;
        tvSubtotal.setText(String.format(Locale.getDefault(), "Subtotal: ₹%.2f", subtotal));
        tvDiscount.setText(String.format(Locale.getDefault(), "Discount: -₹%.2f", disc));
        tvGst.setText(String.format(Locale.getDefault(), "GST (5%%): ₹%.2f", gst));
        tvTotal.setText(String.format(Locale.getDefault(), "Total: ₹%.2f", subtotal - disc + gst));
    }

    private void onOrder(Long id) {
        if (id == null) return;
        if (id < 0) {
            orderPlaced = false;
            setPaymentButtonsEnabled(true);
            snack("Basket is empty");
            return;
        }
        summaryGroup.setVisibility(View.GONE);
        successGroup.setVisibility(View.VISIBLE);
        tvSuccess.setText("Order #" + id + " placed. Preparing your bill…");

        ReportExporter.exportInvoice(this, id, (file, error) -> {
            if (error != null) {
                tvSuccess.setText("Order #" + id + " placed, but the bill failed: " + error);
                return;
            }
            invoice = file;
            tvSuccess.setText("Order #" + id + " placed"
                    + (lastTxn != null ? " (paid via " + lastPayMethod + ", " + lastTxn + ")" : "")
                    + ". Your bill is ready.");
            btnShare.setEnabled(true);
            btnView.setEnabled(true);
        });
    }

    private void snack(String msg) {
        Snackbar.make(findViewById(R.id.checkout_root), msg, Snackbar.LENGTH_LONG).show();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
