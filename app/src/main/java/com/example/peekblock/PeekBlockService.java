package com.example.peekblock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@androidx.annotation.OptIn(markerClass = androidx.camera.core.ExperimentalGetImage.class)
public class PeekBlockService extends Service implements LifecycleOwner {

    public static final String ACTION_START_DETECTION = "ACTION_START_DETECTION";
    public static final String ACTION_STOP_DETECTION = "ACTION_STOP_DETECTION";
    private static final String CHANNEL_ID = "PeekBlockChannel";
    private static final int NOTIFICATION_ID = 1;

    private LifecycleRegistry lifecycleRegistry;
    private ExecutorService cameraExecutor;
    private FaceDetector detector;
    private WindowManager windowManager;
    
    private View fullOverlay;
    private View sideBlurLeft;
    private View sideBlurRight;
    private View warningText;
    
    private boolean isFullOverlayVisible = false;
    private boolean isSideLeftVisible = false;
    private boolean isSideRightVisible = false;
    private boolean isWarningVisible = false;
    
    private boolean isVibrating = false;
    private Toast currentToast;
    
    // Static settings to be toggled from MainActivity
    public static boolean useBlur = true;
    public static boolean useSideBlur = false;
    public static boolean useToast = false;
    public static boolean useVibrate = false;
    
    public static boolean isServiceRunning = false;

    @Override
    public void onCreate() {
        super.onCreate();
        lifecycleRegistry = new LifecycleRegistry(this);
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);

        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build();
        detector = FaceDetection.getClient(options);
        cameraExecutor = Executors.newSingleThreadExecutor();

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        
        LayoutInflater inflater = LayoutInflater.from(this);
        fullOverlay = inflater.inflate(R.layout.overlay_blur, null);
        sideBlurLeft = inflater.inflate(R.layout.overlay_side_left, null);
        sideBlurRight = inflater.inflate(R.layout.overlay_side_right, null);
        warningText = inflater.inflate(R.layout.overlay_warning, null);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (ACTION_START_DETECTION.equals(intent.getAction())) {
                isServiceRunning = true;
                createNotificationChannel();
                Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentTitle("PeekBlock Shield Active")
                        .setContentText("Privacy protection is running in background")
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .setOngoing(true)
                        .build();

                startForeground(NOTIFICATION_ID, notification);
                lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
                startCamera();
            } else if (ACTION_STOP_DETECTION.equals(intent.getAction())) {
                stopSelf();
            }
        }
        return START_STICKY;
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::processImageProxy);

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                Log.e("PeekBlockService", "Camera init failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void processImageProxy(ImageProxy imageProxy) {
        if (imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }

        InputImage image = InputImage.fromMediaImage(imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());

        detector.process(image)
                .addOnSuccessListener(faces -> {
                    int lookingCount = 0;
                    boolean intruderLeft = false;
                    boolean intruderRight = false;

                    Face mainUser = null;
                    float maxArea = 0;
                    for (Face face : faces) {
                        float area = face.getBoundingBox().width() * face.getBoundingBox().height();
                        if (area > maxArea) {
                            maxArea = area;
                            mainUser = face;
                        }
                    }

                    for (Face face : faces) {
                        float rotX = face.getHeadEulerAngleX();
                        float rotY = face.getHeadEulerAngleY();

                        if (rotX > -20 && rotX < 20 && rotY > -20 && rotY < 20) {
                            lookingCount++;
                            if (face != mainUser && mainUser != null) {
                                if (face.getBoundingBox().centerX() < mainUser.getBoundingBox().centerX()) {
                                    intruderRight = true;
                                } else {
                                    intruderLeft = true;
                                }
                            }
                        }
                    }

                    if (lookingCount > 1) {
                        showSecurityActions(intruderLeft, intruderRight);
                    } else {
                        hideSecurityActions();
                    }
                })
                .addOnCompleteListener(task -> imageProxy.close());
    }

    private void showSecurityActions(boolean left, boolean right) {
        ContextCompat.getMainExecutor(this).execute(() -> {
            boolean anyOverlayShown = false;

            if (useBlur) {
                addOverlay(fullOverlay, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, Gravity.CENTER);
                isFullOverlayVisible = true;
                anyOverlayShown = true;
            }
            if (useSideBlur) {
                if (left) {
                    addOverlay(sideBlurLeft, 350, WindowManager.LayoutParams.MATCH_PARENT, Gravity.START);
                    isSideLeftVisible = true;
                    anyOverlayShown = true;
                }
                if (right) {
                    addOverlay(sideBlurRight, 350, WindowManager.LayoutParams.MATCH_PARENT, Gravity.END);
                    isSideRightVisible = true;
                    anyOverlayShown = true;
                }
            }
            
            if (anyOverlayShown) {
                addWarning();
            }

            if (useToast) {
                if (currentToast != null) currentToast.cancel();
                currentToast = Toast.makeText(this, "PRIVACY BREACH DETECTED!", Toast.LENGTH_SHORT);
                currentToast.show();
            }
            
            if (useVibrate) {
                vibratePhone();
            }
        });
    }

    private void addOverlay(View view, int width, int height, int gravity) {
        if (view.getParent() != null) return;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width, height,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = gravity;
        windowManager.addView(view, params);
    }

    private void addWarning() {
        if (warningText.getParent() != null) return;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.CENTER;
        windowManager.addView(warningText, params);
        isWarningVisible = true;
    }

    private void hideSecurityActions() {
        ContextCompat.getMainExecutor(this).execute(() -> {
            if (isFullOverlayVisible) { windowManager.removeView(fullOverlay); isFullOverlayVisible = false; }
            if (isSideLeftVisible) { windowManager.removeView(sideBlurLeft); isSideLeftVisible = false; }
            if (isSideRightVisible) { windowManager.removeView(sideBlurRight); isSideRightVisible = false; }
            if (isWarningVisible) { windowManager.removeView(warningText); isWarningVisible = false; }
            stopVibration();
        });
    }

    private void vibratePhone() {
        if (isVibrating) return;
        isVibrating = true;
        Vibrator v = getVibrator();
        if (v != null && v.hasVibrator()) {
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
        Vibrator v = getVibrator();
        if (v != null) v.cancel();
    }

    private Vibrator getVibrator() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            return vm.getDefaultVibrator();
        }
        return (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "PeekBlock Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isServiceRunning = false;
        lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
        cameraExecutor.shutdown();
        hideSecurityActions();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
    @androidx.annotation.NonNull @Override public Lifecycle getLifecycle() { return lifecycleRegistry; }
}