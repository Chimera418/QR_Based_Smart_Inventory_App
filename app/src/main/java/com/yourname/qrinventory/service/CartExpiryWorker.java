package com.yourname.qrinventory.service;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.yourname.qrinventory.data.InventoryRepository;
import com.yourname.qrinventory.utils.CartReservationManager;

/**
 * Background worker that releases unpaid cart items back to inventory after the 5-minute window.
 */
public class CartExpiryWorker extends Worker {

    public CartExpiryWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context app = getApplicationContext();
        if (!CartReservationManager.isExpired(app)) return Result.success();
        InventoryRepository.expireCartSync(app);
        return Result.success();
    }
}
