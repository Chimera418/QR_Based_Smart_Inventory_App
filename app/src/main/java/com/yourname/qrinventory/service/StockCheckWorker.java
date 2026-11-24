package com.yourname.qrinventory.service;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.yourname.qrinventory.data.AppDatabase;
import com.yourname.qrinventory.model.Product;
import com.yourname.qrinventory.utils.AppSettings;
import com.yourname.qrinventory.utils.SessionManager;
import java.util.List;

public class StockCheckWorker extends Worker {
    public static final String KEY_MANUAL = "manual";

    public StockCheckWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    @NonNull @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        if (!SessionManager.isAdmin(ctx)) return Result.success();
        boolean manual = getInputData().getBoolean(KEY_MANUAL, false);
        // The scheduled run respects the Settings switch; the "check now" button always runs
        if (!manual && !AppSettings.alertsEnabled(ctx)) return Result.success();

        AppDatabase db = AppDatabase.get(ctx);
        NotificationHelper.createChannel(ctx);

        List<Product> low = db.productDao().lowStockNow();
        int days = AppSettings.expiryDays(ctx);
        long limit = System.currentTimeMillis() + days * 86400000L;
        List<Product> expiring = db.productDao().expiringBefore(limit);

        if (!low.isEmpty()) {
            NotificationHelper.show(ctx, 1001, "Low stock: " + low.size() + " items", names(low));
        }
        if (!expiring.isEmpty()) {
            NotificationHelper.show(ctx, 1002,
                    "Expiring within " + days + " days: " + expiring.size() + " items", names(expiring));
        }
        return Result.success();
    }

    private static String names(List<Product> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size() && i < 5; i++) {
            if (i > 0) sb.append(", ");
            sb.append(list.get(i).name);
        }
        if (list.size() > 5) sb.append(" and ").append(list.size() - 5).append(" more");
        return sb.toString();
    }
}