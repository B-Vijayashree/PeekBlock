package com.example.peekblock;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@androidx.annotation.OptIn(markerClass = androidx.camera.core.ExperimentalGetImage.class)
public class MainActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_CODE = 100;
    private static final int OVERLAY_PERMISSION_CODE = 101;
    
    private PreviewView previewView;
    private MaterialCardView cameraCard;
    private View blurOverlay;
    private View sideBlurLeft;
    private View sideBlurRight;
    private View warningText;
    private SwitchMaterial switchPrivacy;
    
    private SwitchMaterial switchBlur;
    private SwitchMaterial switchSideBlur;
    private SwitchMaterial switchToast;
    private SwitchMaterial switchVibrate;

    private TextView tvFraudPercentage;
    private TextView tvProximity;
    private TextView tvStatusLabel;
    private CircularProgressIndicator riskProgress;
    
    private ExecutorService cameraExecutor;
    private FaceDetector detector;
    private boolean isPrivacyModeEnabled = false;
    private Toast currentToast;
    private boolean isVibrating = false;

    private ProcessCameraProvider cameraProvider;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        cameraCard = findViewById(R.id.cameraCard);
        blurOverlay = findViewById(R.id.blurOverlay);
        sideBlurLeft = findViewById(R.id.sideBlurLeft);
        sideBlurRight = findViewById(R.id.sideBlurRight);
        warningText = findViewById(R.id.warningText);
        switchPrivacy = findViewById(R.id.switchPrivacy);
        
        switchBlur = findViewById(R.id.switchBlur);
        switchSideBlur = findViewById(R.id.switchSideBlur);
        switchToast = findViewById(R.id.switchToast);
        switchVibrate = findViewById(R.id.switchVibrate);

        tvFraudPercentage = findViewById(R.id.tvFraudPercentage);
        tvProximity = findViewById(R.id.tvProximity);
        tvStatusLabel = findViewById(R.id.tvStatusLabel);
        riskProgress = findViewById(R.id.riskProgress);

        // Sync UI with current service state
        if (PeekBlockService.isServiceRunning) {
            switchPrivacy.setChecked(true);
            isPrivacyModeEnabled = true;
            tvStatusLabel.setText("MONITORING");
            tvStatusLabel.setTextColor(Color.parseColor("#1A73E8"));
        }

        switchPrivacy.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isPrivacyModeEnabled = isChecked;
            if (isChecked) {
                tvStatusLabel.setText("MONITORING");
                tvStatusLabel.setTextColor(Color.parseColor("#1A73E8"));
                startServiceAction(PeekBlockService.ACTION_START_DETECTION);
                checkPermissions(); 
            } else {
                hideSecurityActions();
                resetDashboard();
                startServiceAction(PeekBlockService.ACTION_STOP_DETECTION);
                stopCamera(); 
            }
        });

        // Sync toggle switches with Service's static settings
        switchBlur.setChecked(PeekBlockService.useBlur);
        switchSideBlur.setChecked(PeekBlockService.useSideBlur);
        switchToast.setChecked(PeekBlockService.useToast);
        switchVibrate.setChecked(PeekBlockService.useVibrate);

        // Listen for strategy changes
        View.OnClickListener updateListener = v -> updateServiceSettings();
        switchBlur.setOnClickListener(updateListener);
        switchSideBlur.setOnClickListener(updateListener);
        switchToast.setOnClickListener(updateListener);
        switchVibrate.setOnClickListener(updateListener);

        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build();
        detector = FaceDetection.getClient(options);
        cameraExecutor = Executors.newSingleThreadExecutor();
        
        requestOverlayPermission();

        // Get the camera provider once
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                if (isPrivacyModeEnabled) {
                    checkPermissions();
                }
            } catch (ExecutionException | InterruptedException e) {
                Log.e("PeekBlock", "Camera provider error", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void updateServiceSettings() {
        PeekBlockService.useBlur = switchBlur.isChecked();
        PeekBlockService.useSideBlur = switchSideBlur.isChecked();
        PeekBlockService.useToast = switchToast.isChecked();
        PeekBlockService.useVibrate = switchVibrate.isChecked();
    }

    private void startServiceAction(String action) {
        Intent intent = new Intent(this, PeekBlockService.class);
        intent.setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void resetDashboard() {
        tvFraudPercentage.setText("0%");
        riskProgress.setProgress(0, true);
        riskProgress.setIndicatorColor(Color.parseColor("#388E3C"));
        tvStatusLabel.setText("INACTIVE");
        tvStatusLabel.setTextColor(Color.parseColor("#5F6368"));
        tvProximity.setText("N/A");
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
        } else {
            startCamera();
        }
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, OVERLAY_PERMISSION_CODE);
            }
        }
    }

    private void startCamera() {
        if (cameraProvider == null) return;
        
        runOnUiThread(() -> {
            cameraCard.setVisibility(View.VISIBLE);
            previewView.setVisibility(View.VISIBLE);
        });

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());
        
        CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;
        
        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        imageAnalysis.setAnalyzer(cameraExecutor, this::processImageProxy);

        try {
            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
            Log.d("PeekBlock", "Camera bound successfully.");
        } catch (Exception e) {
            Log.e("PeekBlock", "Binding failed", e);
        }
    }

    private void stopCamera() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        runOnUiThread(() -> {
            cameraCard.setVisibility(View.GONE);
            previewView.setVisibility(View.GONE);
        });
    }

    private void processImageProxy(ImageProxy imageProxy) {
        if (!isPrivacyModeEnabled) {
            imageProxy.close();
            return;
        }
        if (imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }
        InputImage image = InputImage.fromMediaImage(imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());
        detector.process(image)
                .addOnSuccessListener(faces -> {
                    int lookingCount = 0;
                    float maxSize = 0;
                    boolean intruderLeft = false, intruderRight = false;
                    Face mainUser = null; float maxArea = 0;
                    for (Face face : faces) {
                        float area = face.getBoundingBox().width() * face.getBoundingBox().height();
                        if (area > maxArea) { maxArea = area; mainUser = face; }
                    }
                    for (Face face : faces) {
                        float rotX = face.getHeadEulerAngleX(), rotY = face.getHeadEulerAngleY();
                        if (rotX > -20 && rotX < 20 && rotY > -20 && rotY < 20) {
                            lookingCount++;
                            Rect bounds = face.getBoundingBox();
                            if (bounds.width() * bounds.height() > maxSize) maxSize = bounds.width() * bounds.height();
                            if (face != mainUser && mainUser != null) {
                                if (face.getBoundingBox().centerX() < mainUser.getBoundingBox().centerX()) intruderRight = true;
                                else intruderLeft = true;
                            }
                        }
                    }
                    updateDashboard(faces.size(), lookingCount, maxSize, imageProxy.getWidth(), imageProxy.getHeight());
                    if (lookingCount > 1) showSecurityActions(intruderLeft, intruderRight);
                    else hideSecurityActions();
                })
                .addOnCompleteListener(task -> imageProxy.close());
    }

    private void updateDashboard(int total, int looking, float maxArea, int w, int h) {
        runOnUiThread(() -> {
            int risk = 0;
            if (looking > 1) risk = Math.min(100, (looking - 1) * 50);
            else if (total > 1) risk = 25;
            
            tvFraudPercentage.setText(String.format(Locale.getDefault(), "%d%%", risk));
            riskProgress.setProgress(risk, true);
            
            if (risk > 50) {
                riskProgress.setIndicatorColor(Color.RED);
                tvStatusLabel.setText("BREACH DETECTED");
                tvStatusLabel.setTextColor(Color.RED);
            } else if (risk > 0) {
                riskProgress.setIndicatorColor(Color.parseColor("#FBC02D"));
                tvStatusLabel.setText("CAUTION");
                tvStatusLabel.setTextColor(Color.parseColor("#FBC02D"));
            } else {
                riskProgress.setIndicatorColor(Color.parseColor("#388E3C"));
                tvStatusLabel.setText("SECURE");
                tvStatusLabel.setTextColor(Color.parseColor("#388E3C"));
            }

            if (looking > 0) {
                float ratio = maxArea / (w * h);
                tvProximity.setText(ratio > 0.15 ? "Very Close" : ratio > 0.05 ? "Optimal" : "Far");
            } else tvProximity.setText("None");
        });
    }

    private void showSecurityActions(boolean left, boolean right) {
        runOnUiThread(() -> {
            if (switchBlur.isChecked()) {
                blurOverlay.setVisibility(View.VISIBLE);
                warningText.setVisibility(View.VISIBLE);
            }
            if (switchSideBlur.isChecked()) {
                if (left) sideBlurLeft.setVisibility(View.VISIBLE);
                if (right) sideBlurRight.setVisibility(View.VISIBLE);
                warningText.setVisibility(View.VISIBLE);
            }
            if (switchToast.isChecked()) {
                if (currentToast != null) currentToast.cancel();
                currentToast = Toast.makeText(this, "PRIVACY BREACH!", Toast.LENGTH_SHORT);
                currentToast.show();
            }
            if (switchVibrate.isChecked()) vibratePhone();
        });
    }

    private void hideSecurityActions() {
        runOnUiThread(() -> {
            blurOverlay.setVisibility(View.GONE);
            sideBlurLeft.setVisibility(View.GONE);
            sideBlurRight.setVisibility(View.GONE);
            warningText.setVisibility(View.GONE);
            stopVibration();
        });
    }

    private void vibratePhone() {
        if (isVibrating) return;
        isVibrating = true;
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(new long[]{0, 500, 200}, 0));
            } else {
                v.vibrate(new long[]{0, 500, 200}, 0);
            }
        }
    }

    private void stopVibration() {
        if (!isVibrating) return;
        isVibrating = false;
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) v.cancel();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (isPrivacyModeEnabled) startCamera();
        }
    }

    @Override protected void onDestroy() { 
        super.onDestroy(); 
        cameraExecutor.shutdown(); 
        stopVibration(); 
    }
}