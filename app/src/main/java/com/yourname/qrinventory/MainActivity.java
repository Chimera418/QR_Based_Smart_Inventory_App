package com.yourname.qrinventory;

import android.content.Intent;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.yourname.qrinventory.data.AppDatabase;
import com.yourname.qrinventory.data.InventoryRepository;
import com.yourname.qrinventory.ui.LoginActivity;
import com.yourname.qrinventory.utils.SessionManager;

import android.widget.Toast;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!SessionManager.isLoggedIn(this)) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        AppDatabase.IO.execute(() -> AppDatabase.get(getApplicationContext()).productDao().count());

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        NavHostFragment host = (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.nav_host);
        NavController nav = host.getNavController();

        AppBarConfiguration cfg = new AppBarConfiguration.Builder(
                R.id.productsFragment, R.id.basketFragment, R.id.scanFragment,
                R.id.historyFragment, R.id.profileFragment).build();
        NavigationUI.setupActionBarWithNavController(this, nav, cfg);

        BottomNavigationView bottom = findViewById(R.id.bottom_nav);
        NavigationUI.setupWithNavController(bottom, nav);
    }

    @Override
    protected void onResume() {
        super.onResume();
        new InventoryRepository(this).checkAndSyncReservation(expired -> {
            if (Boolean.TRUE.equals(expired)) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Cart reservation (5 min) expired — items returned to stock",
                        Toast.LENGTH_LONG).show());
            }
        });
    }
}