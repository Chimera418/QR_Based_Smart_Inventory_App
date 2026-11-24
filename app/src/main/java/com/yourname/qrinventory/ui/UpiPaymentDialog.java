package com.yourname.qrinventory.ui;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.yourname.qrinventory.R;
import com.yourname.qrinventory.utils.PaymentQrParser;

import java.util.Locale;

/**
 * Bottom sheet to enter a merchant/payee UPI ID and launch an installed UPI app for payment.
 */
public class UpiPaymentDialog {

    public interface OnSubmit {
        void onSubmit(String upiId, String payeeName);
    }

    private final Activity activity;
    private final double amount;
    private final OnSubmit onSubmit;

    public UpiPaymentDialog(Activity activity, double amount, OnSubmit onSubmit) {
        this.activity = activity;
        this.amount = amount;
        this.onSubmit = onSubmit;
    }

    public void show() {
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        View v = LayoutInflater.from(activity).inflate(R.layout.sheet_upi_payment, null);
        dialog.setContentView(v);

        TextView tvAmount = v.findViewById(R.id.tv_pay_amount);
        TextInputLayout tilUpi = v.findViewById(R.id.til_upi);
        TextInputEditText etUpi = v.findViewById(R.id.et_upi);
        TextInputEditText etPayee = v.findViewById(R.id.et_payee_name);
        Button btnPay = v.findViewById(R.id.btn_pay_now);

        tvAmount.setText(String.format(Locale.getDefault(), "₹%.2f", amount));
        btnPay.setText(String.format(Locale.getDefault(), "Open UPI App & Pay ₹%.2f", amount));

        btnPay.setOnClickListener(x -> {
            String upi = etUpi.getText() == null ? "" : etUpi.getText().toString().trim();
            if (!PaymentQrParser.isValidUpiId(upi)) {
                tilUpi.setError("Enter a valid UPI ID (e.g. store@okbank)");
                return;
            }
            tilUpi.setError(null);
            String payee = etPayee.getText() == null ? "" : etPayee.getText().toString().trim();
            if (payee.isEmpty()) payee = PaymentQrParser.STORE_NAME;
            dialog.dismiss();
            onSubmit.onSubmit(upi, payee);
        });

        dialog.show();
    }
}
