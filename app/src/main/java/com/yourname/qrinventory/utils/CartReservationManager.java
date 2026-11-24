package com.yourname.qrinventory.utils;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.yourname.qrinventory.service.CartExpiryWorker;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Tracks a 5-minute reservation window for items held in the shopping basket so one shopper
 * cannot hold inventory indefinitely without paying.
 */
public final class CartReservationManager {

    public static final long RESERVATION_DURATION_MS = 5 * 60 * 1000L;
    private static final String PREFS = "cart_reservation";
    private static final String KEY_EXPIRES_AT = "expires_at";
    private static final String WORK_NAME = "cart_reservation_expiry";

    private CartReservationManager() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized void startIfNeeded(Context ctx) {
        if (getExpiresAt(ctx) > 0L) return;
        long expiresAt = System.currentTimeMillis() + RESERVATION_DURATION_MS;
        prefs(ctx).edit().putLong(KEY_EXPIRES_AT, expiresAt).apply();

        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(CartExpiryWorker.class)
                .setInitialDelay(RESERVATION_DURATION_MS, TimeUnit.MILLISECONDS)
                .build();
        WorkManager.getInstance(ctx.getApplicationContext())
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, req);
    }

    public static synchronized void clear(Context ctx) {
        prefs(ctx).edit().putLong(KEY_EXPIRES_AT, 0L).apply();
        WorkManager.getInstance(ctx.getApplicationContext()).cancelUniqueWork(WORK_NAME);
    }

    public static long getExpiresAt(Context ctx) {
        return prefs(ctx).getLong(KEY_EXPIRES_AT, 0L);
    }

    public static long getRemainingMillis(Context ctx) {
        long exp = getExpiresAt(ctx);
        if (exp <= 0L) return 0L;
        return Math.max(0L, exp - System.currentTimeMillis());
    }

    public static boolean isExpired(Context ctx) {
        long exp = getExpiresAt(ctx);
        return exp > 0L && System.currentTimeMillis() >= exp;
    }

    public static String formatMmSs(long remainingMillis) {
        long totalSec = Math.max(0L, (remainingMillis + 999L) / 1000L);
        return String.format(Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60);
    }
}
