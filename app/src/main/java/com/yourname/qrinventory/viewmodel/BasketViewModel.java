package com.yourname.qrinventory.viewmodel;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import com.yourname.qrinventory.data.InventoryRepository;
import com.yourname.qrinventory.model.BasketLine;
import com.yourname.qrinventory.model.Product;

import java.util.List;

public class BasketViewModel extends AndroidViewModel {
    private final InventoryRepository repo;
    public final LiveData<List<BasketLine>> lines;
    public final LiveData<Double> subtotal;
    public final MutableLiveData<String> message = new MutableLiveData<>();     // scan result text, for a Snackbar/Toast
    public final MutableLiveData<Long> orderResult = new MutableLiveData<>();   // order id after checkout, -1 if basket empty

    public BasketViewModel(@NonNull Application app) {
        super(app);
        repo = new InventoryRepository(app);
        lines = repo.getBasketLines();
        subtotal = Transformations.map(lines, list -> {
            double sum = 0;
            if (list != null) for (BasketLine l : list) sum += l.price * l.quantity;
            return sum;
        });
    }

    public final MutableLiveData<Product> scannedProduct = new MutableLiveData<>();

    // Step 1 of a scan: look the product up so the bottom sheet can show its details
    public void lookupScan(String qr) {
        repo.findProductByQr(qr, p -> {
            if (p == null) message.postValue("Unknown product: " + qr);
            else scannedProduct.postValue(p);
        });
    }

    // Step 2: the shopper confirmed a quantity
    public void addScanned(String qr, int qty) {
        repo.addQuantityToBasket(qr, qty, msg -> message.postValue(msg));
    }

    public void onScanned(String qr) { repo.addScannedToBasket(qr, msg -> message.postValue(msg)); }
    public void changeQuantity(BasketLine line, int newQty) { repo.changeQuantity(line, newQty); }
    public void remove(long basketId) { repo.removeFromBasket(basketId); }
    public void clear() { repo.clearBasket(); }
    public void checkout(double discountPercent) { repo.checkout(discountPercent, id -> orderResult.postValue(id)); }
    public void checkReservation(InventoryRepository.Callback<Boolean> onExpired) {
        repo.checkAndSyncReservation(onExpired);
    }
}
