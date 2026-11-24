package com.yourname.qrinventory.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.yourname.qrinventory.R;
import com.yourname.qrinventory.service.BackupService;
import com.yourname.qrinventory.utils.AppSettings;
import com.yourname.qrinventory.utils.SessionManager;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_settings);

        View root = findViewById(R.id.settings_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        setSupportActionBar(findViewById(R.id.toolbar));
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        boolean admin = SessionManager.isAdmin(this);
        TextView account = findViewById(R.id.tv_account);
        account.setText(SessionManager.getName(this) + "\n" + SessionManager.getEmail(this) + "\n"
                + (admin ? "Store manager (Admin)" : "Customer"));

        View adminGroup = findViewById(R.id.admin_settings_group);
        adminGroup.setVisibility(admin ? View.VISIBLE : View.GONE);
        if (!admin) return;

        MaterialSwitch alerts = findViewById(R.id.switch_alerts);
        alerts.setChecked(AppSettings.alertsEnabled(this));
        alerts.setOnCheckedChangeListener((b, on) -> AppSettings.setAlertsEnabled(this, on));

        TextView tvDays = findViewById(R.id.tv_days);
        Slider slider = findViewById(R.id.slider_days);
        int days = AppSettings.expiryDays(this);
        slider.setValue(days);
        tvDays.setText(daysText(days));
        slider.addOnChangeListener((sl, value, fromUser) -> {
            int d = (int) value;
            tvDays.setText(daysText(d));
            if (fromUser) AppSettings.setExpiryDays(this, d);
        });

        // Backup: starts the foreground service; its result comes back through LiveData
        View btnBackup = findViewById(R.id.btn_backup);
        TextView tvBackup = findViewById(R.id.tv_backup_status);
        btnBackup.setOnClickListener(v ->
                ContextCompat.startForegroundService(this, new Intent(this, BackupService.class)));
        BackupService.running.observe(this, r -> btnBackup.setEnabled(!Boolean.TRUE.equals(r)));
        BackupService.status.observe(this, s -> {
            if (s != null) tvBackup.setText(s);
        });
    }

    private static String daysText(int d) {
        return "Warn when a product expires within " + d + (d == 1 ? " day" : " days");
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}