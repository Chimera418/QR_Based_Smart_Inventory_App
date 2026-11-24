package com.yourname.qrinventory.data;

import android.content.Context;

import androidx.lifecycle.LiveData;

import com.yourname.qrinventory.model.BasketItem;
import com.yourname.qrinventory.model.BasketLine;
import com.yourname.qrinventory.model.CategoryStock;
import com.yourname.qrinventory.model.DailySales;
import com.yourname.qrinventory.model.Order;
import com.yourname.qrinventory.model.OrderItem;
import com.yourname.qrinventory.model.Product;
import com.yourname.qrinventory.model.TopSeller;
import com.yourname.qrinventory.utils.CartReservationManager;

import java.util.ArrayList;
import java.util.List;

public class InventoryRepository {
    private final AppDatabase db;
    private final Context appCtx;

    public InventoryRepository(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        this.db = AppDatabase.get(this.appCtx);
    }

    public interface Callback<T> { void onResult(T result); }

    // ---------- LiveData getters (for ViewModels / screens) ----------
    public LiveData<List<Product>> getProducts() { return db.productDao().getAll(); }
    public LiveData<List<Product>> searchProducts(String q) { return db.productDao().search(q); }
    public LiveData<List<Product>> productsByCategory(String c) { return db.productDao().byCategory(c); }
    public LiveData<List<String>> getCategories() { return db.productDao().categories(); }
    public LiveData<List<BasketLine>> getBasketLines() { return db.basketDao().getLines(); }
    public LiveData<List<Order>> getOrders() { return db.orderDao().getAll(); }
    public LiveData<List<CategoryStock>> getStockByCategory() { return db.productDao().stockByCategory(); }
    public LiveData<List<TopSeller>> getTopSellers() { return db.orderDao().topSellers(); }
    public LiveData<List<DailySales>> getDailySales() { return db.orderDao().dailySales(); }

    // ---------- Product CRUD ----------
    public void addProduct(Product p) { AppDatabase.IO.execute(() -> db.productDao().insert(p)); }
    public void updateProduct(Product p) { AppDatabase.IO.execute(() -> db.productDao().update(p)); }
    public void deleteProduct(Product p) { AppDatabase.IO.execute(() -> db.productDao().delete(p)); }

    // ---------- Basket (reserves inventory immediately on add, restores on cancel/remove/expiry) ----------
    // Called after tapping a product or quick scan. Callback runs on a background thread.
    public void addScannedToBasket(String qr, Callback<String> cb) {
        AppDatabase.IO.execute(() -> {
            String msg = db.runInTransaction(() -> {
                Product p = db.productDao().findByQr(qr);
                if (p == null) return "Unknown product";
                if (p.stock <= 0) return p.name + " is out of stock";

                p.stock -= 1;
                db.productDao().update(p);

                BasketItem existing = db.basketDao().findByProduct(p.id);
                if (existing == null) {
                    BasketItem b = new BasketItem();
                    b.productId = p.id;
                    b.quantity = 1;
                    db.basketDao().insert(b);
                } else {
                    existing.quantity += 1;
                    db.basketDao().update(existing);
                }
                CartReservationManager.startIfNeeded(appCtx);
                return "Added: " + p.name;
            });
            cb.onResult(msg);
        });
    }

    public void changeQuantity(BasketLine line, int newQty) {
        AppDatabase.IO.execute(() -> db.runInTransaction(() -> {
            BasketItem existing = db.basketDao().findById(line.basketId);
            if (existing == null) return;
            Product p = db.productDao().findById(existing.productId);

            if (newQty <= 0) {
                if (p != null) {
                    p.stock += existing.quantity;
                    db.productDao().update(p);
                }
                db.basketDao().remove(existing.id);
                if (db.basketDao().getAllNow().isEmpty()) {
                    CartReservationManager.clear(appCtx);
                }
                return;
            }

            int delta = newQty - existing.quantity;
            if (delta == 0) return;
            if (p != null) {
                if (delta > 0 && delta > p.stock) return;
                // Subtract when increasing cart qty; add back when decreasing cart qty
                p.stock -= delta;
                db.productDao().update(p);
            }
            existing.quantity = newQty;
            db.basketDao().update(existing);
        }));
    }

    public void removeFromBasket(long basketId) {
        AppDatabase.IO.execute(() -> db.runInTransaction(() -> {
            BasketItem existing = db.basketDao().findById(basketId);
            if (existing == null) return;
            Product p = db.productDao().findById(existing.productId);
            if (p != null) {
                p.stock += existing.quantity;
                db.productDao().update(p);
            }
            db.basketDao().remove(basketId);
            if (db.basketDao().getAllNow().isEmpty()) {
                CartReservationManager.clear(appCtx);
            }
        }));
    }

    public void clearBasket() {
        AppDatabase.IO.execute(() -> db.runInTransaction(() -> {
            List<BasketItem> items = db.basketDao().getAllNow();
            for (BasketItem b : items) {
                Product p = db.productDao().findById(b.productId);
                if (p != null) {
                    p.stock += b.quantity;
                    db.productDao().update(p);
                }
            }
            db.basketDao().clear();
            CartReservationManager.clear(appCtx);
        }));
    }

    /**
     * Synchronously expires the cart if the 5-minute reservation has elapsed, restoring stock.
     * @return true if at least one item was restored from an expired basket.
     */
    public static boolean expireCartSync(Context ctx) {
        Context app = ctx.getApplicationContext();
        AppDatabase db = AppDatabase.get(app);
        return db.runInTransaction(() -> {
            if (!CartReservationManager.isExpired(app)) return false;
            List<BasketItem> items = db.basketDao().getAllNow();
            for (BasketItem b : items) {
                Product p = db.productDao().findById(b.productId);
                if (p != null) {
                    p.stock += b.quantity;
                    db.productDao().update(p);
                }
            }
            db.basketDao().clear();
            CartReservationManager.clear(app);
            return !items.isEmpty();
        });
    }

    public void checkAndSyncReservation(Callback<Boolean> onExpired) {
        AppDatabase.IO.execute(() -> {
            if (CartReservationManager.isExpired(appCtx)) {
                boolean restored = expireCartSync(appCtx);
                onExpired.onResult(restored);
                return;
            }
            List<BasketItem> items = db.basketDao().getAllNow();
            if (items.isEmpty() && CartReservationManager.getExpiresAt(appCtx) > 0) {
                CartReservationManager.clear(appCtx);
            } else if (!items.isEmpty() && CartReservationManager.getExpiresAt(appCtx) <= 0) {
                CartReservationManager.startIfNeeded(appCtx);
            }
            onExpired.onResult(false);
        });
    }

    // ---------- Checkout ----------
    // Stock was already deducted when items were added to the basket, so checkout finalizes the order.
    public void checkout(double discountPercent, Callback<Long> cb) {   // returns order id, or -1 if empty
        AppDatabase.IO.execute(() -> {
            long orderId = db.runInTransaction(() -> {
                List<BasketItem> items = db.basketDao().getAllNow();
                if (items.isEmpty()) return -1L;
                double subtotal = 0;
                List<OrderItem> orderItems = new ArrayList<>();
                for (BasketItem b : items) {
                    Product p = db.productDao().findById(b.productId);
                    if (p == null) continue;
                    subtotal += p.price * b.quantity;
                    OrderItem oi = new OrderItem();
                    oi.productName = p.name;
                    oi.unitPrice = p.price;
                    oi.quantity = b.quantity;
                    orderItems.add(oi);
                }
                if (orderItems.isEmpty()) {
                    db.basketDao().clear();
                    CartReservationManager.clear(appCtx);
                    return -1L;
                }
                double discount = subtotal * discountPercent / 100.0;
                double tax = (subtotal - discount) * 0.05;       // 5% GST
                Order o = new Order();
                o.timestamp = System.currentTimeMillis();
                o.subtotal = subtotal;
                o.discount = discount;
                o.tax = tax;
                o.total = subtotal - discount + tax;
                long id = db.orderDao().insertOrder(o);
                for (OrderItem oi : orderItems) oi.orderId = id;
                db.orderDao().insertItems(orderItems);
                db.basketDao().clear();
                CartReservationManager.clear(appCtx);
                return id;
            });
            cb.onResult(orderId);   // now runs after the transaction has committed
        });
    }

    // ---------- Used by reports (PDF/CSV) ----------
    public List<OrderItem> getOrderItems(long orderId) { return db.orderDao().itemsOf(orderId); }
    public Order getOrder(long orderId) { return db.orderDao().findById(orderId); }

    // ---------- Scanned-item bottom sheet and re-order ----------
    public void findProductByQr(String qr, Callback<Product> cb) {
        AppDatabase.IO.execute(() -> cb.onResult(db.productDao().findByQr(qr)));
    }

    // Adds `qty` of a product to the basket and deducts `qty` from inventory immediately
    public void addQuantityToBasket(String qr, int qty, Callback<String> cb) {
        AppDatabase.IO.execute(() -> {
            String msg = db.runInTransaction(() -> {
                if (qty <= 0) return "Enter a valid quantity";
                Product p = db.productDao().findByQr(qr);
                if (p == null) return "Unknown product";
                if (qty > p.stock) return "Only " + p.stock + " left in stock";

                p.stock -= qty;
                db.productDao().update(p);

                BasketItem existing = db.basketDao().findByProduct(p.id);
                if (existing == null) {
                    BasketItem b = new BasketItem();
                    b.productId = p.id;
                    b.quantity = qty;
                    db.basketDao().insert(b);
                } else {
                    existing.quantity += qty;
                    db.basketDao().update(existing);
                }
                CartReservationManager.startIfNeeded(appCtx);
                return "Added " + qty + " x " + p.name;
            });
            cb.onResult(msg);
        });
    }

    // Puts the items of an old order back into the basket and reserves available stock
    public void reorder(long orderId, Callback<String> cb) {
        AppDatabase.IO.execute(() -> {
            String msg = db.runInTransaction(() -> {
                List<OrderItem> items = db.orderDao().itemsOf(orderId);
                int added = 0, skipped = 0;
                for (OrderItem oi : items) {
                    Product p = db.productDao().findByName(oi.productName);
                    if (p == null || p.stock <= 0) { skipped++; continue; }
                    int toAdd = Math.min(p.stock, oi.quantity);
                    if (toAdd <= 0) { skipped++; continue; }

                    p.stock -= toAdd;
                    db.productDao().update(p);

                    BasketItem existing = db.basketDao().findByProduct(p.id);
                    if (existing == null) {
                        BasketItem b = new BasketItem();
                        b.productId = p.id;
                        b.quantity = toAdd;
                        db.basketDao().insert(b);
                    } else {
                        existing.quantity += toAdd;
                        db.basketDao().update(existing);
                    }
                    added++;
                }
                if (added > 0) {
                    CartReservationManager.startIfNeeded(appCtx);
                }
                String result = added + " item(s) added to your basket";
                if (skipped > 0) result += ", " + skipped + " unavailable";
                return result;
            });
            cb.onResult(msg);
        });
    }
}
