package com.example.peekblock;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.RadioButton;
import android.widget.RadioGroup;
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
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@androidx.annotation.OptIn(markerClass = androidx.camera.core.ExperimentalGetImage.class)
public class MainActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_CODE = 100;
    private static final int OVERLAY_PERMISSION_CODE = 101;
    
    private PreviewView previewView;
    private View blurOverlay;
    private View warningText;
    private SwitchMaterial switchPrivacy;
    private RadioGroup radioGroupAlert;
    private ExecutorService cameraExecutor;
    private FaceDetector detector;
    private boolean isPrivacyModeEnabled = false;
    private Toast currentToast;
    private boolean isVibrating = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        blurOverlay = findViewById(R.id.blurOverlay);
        warningText = findViewById(R.id.warningText);
        switchPrivacy = findViewById(R.id.switchPrivacy);
        radioGroupAlert = findViewById(R.id.radioGroupAlert);

        switchPrivacy.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isPrivacyModeEnabled = isChecked;
            if (isChecked) {
                checkPermissions();
            } else {
                hideSecurityActions();
            }
        });

        // Step 3: Face detection with Classification and Euler angles
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .build();
        detector = FaceDetection.getClient(options);

        cameraExecutor = Executors.newSingleThreadExecutor();
        
        requestOverlayPermission();
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
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::processImageProxy);

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                Log.e("PeekBlock", "Camera initialization failed.", e);
            }
        }, ContextCompat.getMainExecutor(this));
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
                .addOnSuccessListener(new OnSuccessListener<List<Face>>() {
                    @Override
                    public void onSuccess(List<Face> faces) {
                        int lookingAtScreenCount = 0;

                        for (Face face : faces) {
                            float rotX = face.getHeadEulerAngleX(); // Up/Down
                            float rotY = face.getHeadEulerAngleY(); // Left/Right

                            // Improved Flow: Gaze detection check
                            // If angles are near 0 (+/- 15), person is facing the phone
                            if (rotX > -15 && rotX < 15 && rotY > -15 && rotY < 15) {
                                lookingAtScreenCount++;
                            }
                        }

                        // Security Action: Trigger if more than one person is looking
                        if (lookingAtScreenCount > 1) {
                            showSecurityActions();
                        } else {
                            hideSecurityActions();
                        }
                    }
                })
                .addOnCompleteListener(task -> imageProxy.close());
    }

    private void showSecurityActions() {
        runOnUiThread(() -> {
            int checkedId = radioGroupAlert.getCheckedRadioButtonId();
            
            if (checkedId == R.id.radioBlur) {
                blurOverlay.setVisibility(View.VISIBLE);
                warningText.setVisibility(View.VISIBLE);
            } else if (checkedId == R.id.radioToast) {
                if (currentToast == null) {
                    currentToast = Toast.makeText(MainActivity.this, "PRIVACY ALERT: PEERING DETECTED!", Toast.LENGTH_SHORT);
                }
                currentToast.show();
            } else if (checkedId == R.id.radioVibrate) {
                vibratePhone();
            }
        });
    }

    private void hideSecurityActions() {
        runOnUiThread(() -> {
            blurOverlay.setVisibility(View.GONE);
            warningText.setVisibility(View.GONE);
            stopVibration();
        });
    }

    private void vibratePhone() {
        if (isVibrating) return;
        isVibrating = true;
        
        Vibrator vibrator;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vibratorManager = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = vibratorManager.getDefaultVibrator();
        } else {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }

        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 500, 200}, 0));
            } else {
                vibrator.vibrate(new long[]{0, 500, 200}, 0);
            }
        }
    }

    private void stopVibration() {
        if (!isVibrating) return;
        isVibrating = false;
        
        Vibrator vibrator;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vibratorManager = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = vibratorManager.getDefaultVibrator();
        } else {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }
        
        if (vibrator != null) {
            vibrator.cancel();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show();
                switchPrivacy.setChecked(false);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        stopVibration();
    }
}