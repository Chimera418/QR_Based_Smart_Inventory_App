package com.yourname.qrinventory.data;
import com.yourname.qrinventory.model.*;
import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;
import java.util.List;

@Dao
public interface BasketDao {
    @Query("SELECT b.id AS basketId, p.id AS productId, p.name, p.price, b.quantity, p.stock " +
            "FROM basket_items b JOIN products p ON p.id = b.productId")
    LiveData<List<BasketLine>> getLines();

    @Query("SELECT * FROM basket_items WHERE productId = :pid LIMIT 1") BasketItem findByProduct(long pid);
    @Query("SELECT * FROM basket_items WHERE id = :id LIMIT 1") BasketItem findById(long id);
    @Insert long insert(BasketItem i);
    @Update void update(BasketItem i);
    @Query("DELETE FROM basket_items WHERE id = :id") void remove(long id);
    @Query("DELETE FROM basket_items") void clear();
    @Query("SELECT * FROM basket_items") List<BasketItem> getAllNow();
}
