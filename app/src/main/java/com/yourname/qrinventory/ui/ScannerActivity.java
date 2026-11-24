package com.yourname.qrinventory.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.Image;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.yourname.qrinventory.R;
import com.yourname.qrinventory.model.Product;
import com.yourname.qrinventory.service.SystemEventReceiver;
import com.yourname.qrinventory.viewmodel.BasketViewModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ScannerActivity extends AppCompatActivity implements SensorEventListener {

    private static final String TAG = "ScannerActivity";
    public static final String EXTRA_PICK_MODE = "pick_mode";
    public static final String EXTRA_PAY_SCAN_MODE = "pay_scan_mode";

    private BasketViewModel vm;
    private PreviewView previewView;
    private TextView tvResult, tvOffline, tvBattery, tvLight;
    private Button btnTorch;
    private View permGroup;
    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private BarcodeScanner scanner;
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private boolean torchOn = false;
    private boolean pickMode;
    private boolean payScanMode;
    private String lastCode = "";
    private boolean sheetOpen = false;
    private long lastTime = 0;

    private SensorManager sensorManager;
    private Sensor lightSensor;
    private boolean manualOverride = false;   // user tapped the torch button, so stop auto control
    private boolean autoOn = false;           // true only if the sensor turned the torch on

    private final ActivityResultLauncher<String[]> permLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> updateCameraState());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");
        setContentView(R.layout.activity_scanner);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.overlay), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        previewView = findViewById(R.id.preview);
        tvResult = findViewById(R.id.tv_result);
        tvOffline = findViewById(R.id.tv_offline);
        tvBattery = findViewById(R.id.tv_battery);
        btnTorch = findViewById(R.id.btn_torch);
        permGroup = findViewById(R.id.perm_group);

        tvLight = findViewById(R.id.tv_light);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        if (lightSensor == null) tvLight.setText("No light sensor on this device");

        pickMode = getIntent().getBooleanExtra(EXTRA_PICK_MODE, false);
        payScanMode = getIntent().getBooleanExtra(EXTRA_PAY_SCAN_MODE, false);
        BarcodeScannerOptions.Builder ob = new BarcodeScannerOptions.Builder();
        if (payScanMode) {
            ob.setBarcodeFormats(Barcode.FORMAT_QR_CODE);
            tvResult.setText("Point the camera at a payment / UPI QR code");
        } else if (pickMode) {
            // real packets use 1D barcodes (EAN/UPC), not QR
            ob.setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E);
            tvResult.setText("Scan the product's barcode");
        } else {
            ob.setBarcodeFormats(Barcode.FORMAT_QR_CODE);
        }
        scanner = BarcodeScanning.getClient(ob.build());

        vm = new ViewModelProvider(this).get(BasketViewModel.class);

        vm.message.observe(this, msg -> { if (msg != null) tvResult.setText(msg); });
        vm.scannedProduct.observe(this, this::showItemSheet);
        tvOffline.setVisibility(SystemEventReceiver.isOnline(this) ? View.GONE : View.VISIBLE);
        SystemEventReceiver.online.observe(this, on ->
                tvOffline.setVisibility(Boolean.FALSE.equals(on) ? View.VISIBLE : View.GONE));
        SystemEventReceiver.lowBattery.observe(this, low ->
                tvBattery.setVisibility(Boolean.TRUE.equals(low) ? View.VISIBLE : View.GONE));

        btnTorch.setOnClickListener(v -> {
            manualOverride = true;
            if (camera == null || !camera.getCameraInfo().hasFlashUnit()) {
                Toast.makeText(this, "No flash on this device", Toast.LENGTH_SHORT).show();
            } else {
                setTorch(!torchOn);
            }
        });
        findViewById(R.id.btn_done).setOnClickListener(v -> finish());
        findViewById(R.id.btn_grant).setOnClickListener(v -> onGrantClicked());

        if (savedInstanceState == null) askPermissions();
    }

    @Override protected void onStart() { super.onStart(); Log.d(TAG, "onStart"); }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume: starting camera");
        updateCameraState();
        if (lightSensor != null) sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "onPause: pausing camera");
        if (cameraProvider != null) cameraProvider.unbindAll();
        torchOn = false;
        sensorManager.unregisterListener(this);
        autoOn = false;
        super.onPause();
    }

    @Override protected void onStop() { Log.d(TAG, "onStop"); super.onStop(); }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy: releasing scanner and executor");
        scanner.close();
        cameraExecutor.shutdown();
        camera = null;
        super.onDestroy();
    }

    private boolean hasCamera() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        if (!hasCamera()) need.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) permLauncher.launch(need.toArray(new String[0]));
    }

    private void onGrantClicked() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
            permLauncher.launch(new String[]{Manifest.permission.CAMERA});
        } else {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)));
        }
    }

    private void updateCameraState() {
        if (hasCamera()) {
            permGroup.setVisibility(View.GONE);
            if (cameraProvider == null) startCamera(); else bindCamera();
        } else {
            permGroup.setVisibility(View.VISIBLE);
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCamera();
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera provider failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCamera() {
        if (cameraProvider == null) return;
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
        analysis.setAnalyzer(cameraExecutor, this::analyze);

        try {
            cameraProvider.unbindAll();
            camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
            torchOn = false;
            btnTorch.setText("Torch: OFF");
        } catch (Exception e) {
            Log.e(TAG, "Camera bind failed", e);
            Toast.makeText(this, "Could not start the camera", Toast.LENGTH_LONG).show();
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void analyze(ImageProxy proxy) {
        Image media = proxy.getImage();
        if (media == null) { proxy.close(); return; }
        InputImage image = InputImage.fromMediaImage(media, proxy.getImageInfo().getRotationDegrees());
        scanner.process(image)
                .addOnSuccessListener(codes -> {
                    for (Barcode b : codes) {
                        String raw = b.getRawValue();
                        if (raw != null) { handleCode(raw.trim()); break; }
                    }
                })
                .addOnCompleteListener(t -> proxy.close());
    }

    private void handleCode(String code) {
        if (sheetOpen) return;
        long now = SystemClock.elapsedRealtime();
        if (code.equals(lastCode) && now - lastTime < 3000) return;
        if (now - lastTime < 1000) return;
        lastCode = code;
        lastTime = now;
        Log.d(TAG, "Scanned: " + code);
        if (pickMode || payScanMode) {
            previewView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            setResult(RESULT_OK, new Intent().putExtra("code", code));
            finish();
            return;
        }
        previewView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        tvResult.setText("Scanned: " + code);
        vm.lookupScan(code);
    }

    // Bottom sheet that shows the scanned product and lets the shopper pick a quantity
    private void showItemSheet(Product p) {
        if (p == null || sheetOpen) return;
        vm.scannedProduct.setValue(null);   // so it is not shown again after a rotation
        sheetOpen = true;

        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View v = getLayoutInflater().inflate(R.layout.sheet_scanned_item, null);
        dialog.setContentView(v);

        TextView tvName = v.findViewById(R.id.sheet_name);
        TextView tvMeta = v.findViewById(R.id.sheet_meta);
        TextView tvPrice = v.findViewById(R.id.sheet_price);
        TextView tvQty = v.findViewById(R.id.sheet_qty);
        TextView tvTotal = v.findViewById(R.id.sheet_total);
        View btnMinus = v.findViewById(R.id.sheet_minus);
        View btnPlus = v.findViewById(R.id.sheet_plus);
        Button btnAdd = v.findViewById(R.id.sheet_add);

        tvName.setText(p.name);
        tvMeta.setText(p.category + "  |  " + p.stock + " in stock");
        tvPrice.setText(String.format(Locale.getDefault(), "₹%.2f each", p.price));

        int[] qty = {1};
        Runnable refresh = () -> {
            tvQty.setText(String.valueOf(qty[0]));
            tvTotal.setText(String.format(Locale.getDefault(), "₹%.2f", p.price * qty[0]));
            btnMinus.setEnabled(qty[0] > 1);
            btnPlus.setEnabled(qty[0] < p.stock);
        };
        btnMinus.setOnClickListener(x -> { qty[0]--; refresh.run(); });
        btnPlus.setOnClickListener(x -> { qty[0]++; refresh.run(); });
        refresh.run();

        if (p.stock <= 0) {
            btnAdd.setEnabled(false);
            btnAdd.setText("Out of stock");
        }
        btnAdd.setOnClickListener(x -> {
            vm.addScanned(p.qrCode, qty[0]);
            dialog.dismiss();
        });
        v.findViewById(R.id.sheet_cancel).setOnClickListener(x -> dialog.dismiss());
        dialog.setOnDismissListener(d -> sheetOpen = false);
        dialog.show();
    }
    void setTorch(boolean on) {
        if (camera == null || !camera.getCameraInfo().hasFlashUnit()) return;
        torchOn = on;
        camera.getCameraControl().enableTorch(on);
        btnTorch.setText(on ? "Torch: ON" : "Torch: OFF");
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float lux = event.values[0];
        tvLight.setText(String.format(Locale.getDefault(),
                "Light: %.0f lx%s", lux, manualOverride ? " (manual torch)" : " (auto torch)"));
        if (manualOverride || camera == null) return;
        if (lux < 10f && !torchOn) {            // dark: switch the torch on
            setTorch(true);
            autoOn = true;
        } else if (lux > 50f && torchOn && autoOn) {   // bright again: switch it off
            setTorch(false);
            autoOn = false;
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
}