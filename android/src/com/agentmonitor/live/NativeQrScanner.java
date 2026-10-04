package com.agentmonitor.live;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Non-exported native camera screen. It only returns text; it never opens scanned URLs. */
public final class NativeQrScanner extends Activity implements TextureView.SurfaceTextureListener {
    public static final String EXTRA_TEXT = "com.agentmonitor.live.QR_TEXT";
    private static final int CAMERA_PERMISSION = 81, PICK_IMAGE = 82;
    private static final String DEFAULT_HINT = "将电脑上的配对二维码放入框内";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object imageLock = new Object();
    private TextureView preview;
    private TextView status;
    private Button permissionButton, torchButton, imageButton;
    private Typeface font;
    private HandlerThread analysisThread;
    private Handler analysis;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private CaptureRequest.Builder request;
    private ImageReader reader;
    private Surface previewSurface;
    private Size previewSize;
    private boolean active, opening, destroyed, delivered, askedPermission, permissionPending, galleryLoading, torch;
    private int cameraGeneration, sensorOrientation;
    private volatile int frameCropEdge;
    private Uri pendingImage;
    private String deferredText, deferredMessage;
    private String scanHint = DEFAULT_HINT;
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) { }
        @Override public void onDisplayRemoved(int id) { }
        @Override public void onDisplayChanged(int id) {
            if (preview != null && preview.getDisplay() != null && preview.getDisplay().getDisplayId() == id) configureTransform();
        }
    };
    private long lastFrame;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        setResult(RESULT_CANCELED);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.rgb(17, 23, 31));
        getWindow().setNavigationBarColor(Color.rgb(17, 23, 31));
        getWindow().getDecorView().setSystemUiVisibility(0);
        try { font = Typeface.createFromAsset(getAssets(), "fonts/MiSans-Regular.ttf"); }
        catch (RuntimeException ignored) { font = Typeface.DEFAULT; }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(17, 23, 31));
        root.setPadding(dp(24), dp(16), dp(24), dp(20));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(24) + insets.getSystemWindowInsetLeft(), dp(16) + insets.getSystemWindowInsetTop(),
                    dp(24) + insets.getSystemWindowInsetRight(), dp(20) + insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("返回");
        back.setContentDescription("返回添加电脑");
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(64), dp(48)));
        TextView title = text("扫一扫", 24, Color.WHITE);
        title.setPadding(dp(16), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(56), 1));
        root.addView(header);

        FrameLayout cameraFrame = new FrameLayout(this);
        GradientDrawable frameBackground = shape(Color.BLACK, 28);
        cameraFrame.setBackground(frameBackground);
        cameraFrame.setClipToOutline(true);
        LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(-1, 0, 1);
        frameParams.topMargin = dp(28); frameParams.bottomMargin = dp(20);
        root.addView(cameraFrame, frameParams);
        preview = new TextureView(this);
        preview.setContentDescription("二维码相机取景框");
        preview.setSurfaceTextureListener(this);
        cameraFrame.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        View guide = new View(this) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                float edge = Math.min(getWidth(), getHeight()) * .73f;
                float left = (getWidth() - edge) / 2f, top = (getHeight() - edge) / 2f;
                paint.setColor(0x77000000); paint.setStyle(Paint.Style.FILL);
                canvas.drawRect(0, 0, getWidth(), top, paint);
                canvas.drawRect(0, top + edge, getWidth(), getHeight(), paint);
                canvas.drawRect(0, top, left, top + edge, paint);
                canvas.drawRect(left + edge, top, getWidth(), top + edge, paint);
                paint.setColor(0xDDFFFFFF); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2));
                canvas.drawRoundRect(new RectF(left, top, left + edge, top + edge), dp(20), dp(20), paint);
            }
        };
        guide.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        cameraFrame.addView(guide, new FrameLayout.LayoutParams(-1, -1));

        status = text("将电脑上的配对二维码放入框内", 14, 0xFFCBD5E1);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(56)));
        permissionButton = button("开启相机");
        permissionButton.setVisibility(View.GONE);
        permissionButton.setOnClickListener(v -> {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                permissionButton.setVisibility(View.GONE); openCamera();
            } else if (askedPermission && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
                    && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            } else askCameraPermission();
        });
        root.addView(permissionButton, new LinearLayout.LayoutParams(-1, dp(48)));
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        imageButton = button("从相册选择");
        imageButton.setOnClickListener(v -> {
            try { startActivityForResult(NativeAvatar.pickerIntent(), PICK_IMAGE); }
            catch (RuntimeException failure) { status.setText("无法打开相册，请重试"); }
        });
        torchButton = button("打开闪光灯");
        torchButton.setVisibility(View.GONE);
        torchButton.setOnClickListener(v -> toggleTorch());
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(0, dp(52), 1);
        actionParams.setMargins(dp(4), dp(8), dp(4), 0);
        actions.addView(imageButton, actionParams);
        actions.addView(torchButton, new LinearLayout.LayoutParams(actionParams));
        root.addView(actions);
        setContentView(root);
        analysisThread = new HandlerThread("Monitor QR decode");
        analysisThread.start();
        analysis = new Handler(analysisThread.getLooper());
        if (saved != null) {
            askedPermission = saved.getBoolean("asked_camera");
            deferredText = saved.getString("decoded_image");
            deferredMessage = saved.getString("image_error");
            String pending = saved.getString("pending_image");
            if (pending != null && pending.length() <= 4096) decodeImage(Uri.parse(pending));
        }
    }

    @Override protected void onResume() {
        super.onResume(); active = true;
        ((DisplayManager) getSystemService(DISPLAY_SERVICE)).registerDisplayListener(displayListener, main);
        if (deferredText != null) { String text = deferredText; deferredText = null; deliver(text); return; }
        if (galleryLoading) return;
        if (deferredMessage != null) { scanHint = deferredMessage; status.setText(scanHint); deferredMessage = null; }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionButton.setText("开启相机");
            permissionButton.setVisibility(View.VISIBLE);
            status.setText(DEFAULT_HINT.equals(scanHint) ? "允许使用相机，或从相册选择二维码" : scanHint);
            if (!askedPermission && !permissionPending) askCameraPermission();
        } else { permissionButton.setVisibility(View.GONE); openCamera(); }
    }

    @Override protected void onPause() {
        active = false; closeCamera();
        ((DisplayManager) getSystemService(DISPLAY_SERVICE)).unregisterDisplayListener(displayListener);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("asked_camera", askedPermission);
        if (pendingImage != null) state.putString("pending_image", pendingImage.toString());
        if (deferredText != null) state.putString("decoded_image", deferredText);
        if (deferredMessage != null) state.putString("image_error", deferredMessage);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        destroyed = true; closeCamera();
        ((DisplayManager) getSystemService(DISPLAY_SERVICE)).unregisterDisplayListener(displayListener);
        if (analysisThread != null) analysisThread.quitSafely();
        super.onDestroy();
    }

    private void askCameraPermission() {
        askedPermission = true; permissionPending = true;
        requestPermissions(new String[] {Manifest.permission.CAMERA}, CAMERA_PERMISSION);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code != CAMERA_PERMISSION) return;
        permissionPending = false;
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            permissionButton.setVisibility(View.GONE); openCamera();
        } else {
            permissionButton.setVisibility(View.VISIBLE);
            status.setText("相机未授权，仍可从相册选择二维码");
        }
    }

    private void openCamera() {
        if (!active || destroyed || delivered || galleryLoading || opening || camera != null || !preview.isAvailable()
                || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        final int generation = ++cameraGeneration;
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            String chosen = null;
            CameraCharacteristics selected = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (chosen == null || (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK)) {
                    chosen = id; selected = characteristics;
                }
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) break;
            }
            if (chosen == null || selected == null) throw new IllegalStateException("No camera");
            Integer sensor = selected.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = sensor == null ? 0 : sensor;
            previewSize = chooseSize(selected.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP));
            if (previewSize == null) throw new IllegalStateException("No camera stream");
            final boolean flashSupported = Boolean.TRUE.equals(selected.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));
            opening = true;
            manager.openCamera(chosen, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    if (!active || destroyed || delivered || generation != cameraGeneration) { device.close(); return; }
                    opening = false; camera = device;
                    torchButton.setVisibility(flashSupported ? View.VISIBLE : View.GONE);
                    beginPreview(generation);
                }
                @Override public void onDisconnected(CameraDevice device) { device.close(); cameraFailure(generation); }
                @Override public void onError(CameraDevice device, int error) { device.close(); cameraFailure(generation); }
            }, main);
        } catch (Exception failure) { cameraFailure(generation); }
    }

    private static Size chooseSize(StreamConfigurationMap streams) {
        if (streams == null) return null;
        Size[] previewSizes = streams.getOutputSizes(SurfaceTexture.class);
        Size[] analysisSizes = streams.getOutputSizes(android.graphics.ImageFormat.YUV_420_888);
        if (previewSizes == null || analysisSizes == null) return null;
        Size best = null, smallest = null;
        for (Size size : previewSizes) {
            if (!Arrays.asList(analysisSizes).contains(size)) continue;
            long area = (long) size.getWidth() * size.getHeight();
            if (smallest == null || area < (long) smallest.getWidth() * smallest.getHeight()) smallest = size;
            if (size.getWidth() <= 1280 && size.getHeight() <= 720
                    && (best == null || area > (long) best.getWidth() * best.getHeight())) best = size;
        }
        return best != null ? best : smallest != null && smallest.getWidth() <= 2048 && smallest.getHeight() <= 2048 ? smallest : null;
    }

    private void beginPreview(final int generation) {
        try {
            SurfaceTexture texture = preview.getSurfaceTexture();
            if (texture == null || camera == null) return;
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            configureTransform();
            previewSurface = new Surface(texture);
            reader = ImageReader.newInstance(previewSize.getWidth(), previewSize.getHeight(), android.graphics.ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(source -> decodeFrame(source, generation), analysis);
            request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(previewSurface); request.addTarget(reader.getSurface());
            request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            camera.createCaptureSession(Arrays.asList(previewSurface, reader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession configured) {
                    if (!active || destroyed || generation != cameraGeneration || camera == null) { configured.close(); return; }
                    session = configured;
                    try {
                        session.setRepeatingRequest(request.build(), null, main);
                        status.setText(scanHint);
                    } catch (Exception failure) { cameraFailure(generation); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession failed) { failed.close(); cameraFailure(generation); }
            }, main);
        } catch (Exception failure) { cameraFailure(generation); }
    }

    private void decodeFrame(ImageReader source, int generation) {
        byte[] luminance;
        int width, height;
        synchronized (imageLock) {
            Image image = null;
            try {
                image = source.acquireLatestImage();
                if (image == null) return;
                long now = SystemClock.elapsedRealtime();
                if (now - lastFrame < 300) return;
                lastFrame = now;
                int frameWidth = image.getWidth(), frameHeight = image.getHeight();
                int edge = Math.min(frameCropEdge, Math.min(frameWidth, frameHeight));
                if (frameWidth > 2048 || frameHeight > 2048 || edge < 1) return;
                int left = (frameWidth - edge) / 2, top = (frameHeight - edge) / 2;
                width = edge; height = edge;
                Image.Plane plane = image.getPlanes()[0];
                ByteBuffer buffer = plane.getBuffer();
                int stride = plane.getRowStride(), step = plane.getPixelStride(), start = buffer.position();
                start += top * stride + left * step;
                if ((long) start + (height - 1L) * stride + (width - 1L) * step >= buffer.limit()) return;
                luminance = new byte[width * height];
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
                    luminance[y * width + x] = buffer.get(start + y * stride + x * step);
            } catch (RuntimeException ignored) { return;
            } finally { if (image != null) image.close(); }
        }
        String text = NativeQrDecoder.luminance(luminance, width, height);
        if (text != null) main.post(() -> { if (active && generation == cameraGeneration) deliver(text); });
    }

    private void configureTransform() {
        if (previewSize == null || preview.getWidth() == 0 || preview.getHeight() == 0) return;
        int rotationDegrees = getWindowManager().getDefaultDisplay().getRotation() * 90;
        Matrix matrix = new Matrix();
        float viewWidth = preview.getWidth(), viewHeight = preview.getHeight();
        float centerX = viewWidth / 2f, centerY = viewHeight / 2f;
        // TextureView already applies sensor orientation but stretches it to the view.
        // Undo that stretch, compensate display rotation, then apply one uniform cover scale.
        boolean sensorSwapped = sensorOrientation % 180 != 0;
        float naturalWidth = sensorSwapped ? previewSize.getHeight() : previewSize.getWidth();
        float naturalHeight = sensorSwapped ? previewSize.getWidth() : previewSize.getHeight();
        boolean displaySwapped = rotationDegrees % 180 != 0;
        float orientedWidth = displaySwapped ? naturalHeight : naturalWidth;
        float orientedHeight = displaySwapped ? naturalWidth : naturalHeight;
        float scale = Math.max(viewWidth / orientedWidth, viewHeight / orientedHeight);
        matrix.setScale(naturalWidth / viewWidth, naturalHeight / viewHeight, centerX, centerY);
        matrix.postRotate(-rotationDegrees, centerX, centerY);
        matrix.postScale(scale, scale, centerX, centerY);
        frameCropEdge = NativeQrDecoder.guideEdge(previewSize.getWidth(), previewSize.getHeight(),
                preview.getWidth(), preview.getHeight(), sensorSwapped != displaySwapped);
        preview.setTransform(matrix);
    }

    private void toggleTorch() {
        if (session == null || request == null) return;
        try {
            boolean next = !torch;
            request.set(CaptureRequest.FLASH_MODE, next ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
            session.setRepeatingRequest(request.build(), null, main);
            torch = next;
            torchButton.setText(torch ? "关闭闪光灯" : "打开闪光灯");
        } catch (Exception ignored) { status.setText("暂时无法使用闪光灯"); }
    }

    private void cameraFailure(int generation) {
        if (generation != cameraGeneration || destroyed) return;
        closeCamera();
        status.setText("相机暂时不可用，可从相册选择二维码");
        permissionButton.setText("重试相机"); permissionButton.setVisibility(View.VISIBLE);
    }

    private void closeCamera() {
        cameraGeneration++; opening = false; torch = false;
        if (session != null) { session.close(); session = null; }
        if (camera != null) { camera.close(); camera = null; }
        synchronized (imageLock) { if (reader != null) { reader.close(); reader = null; } }
        if (previewSurface != null) { previewSurface.release(); previewSurface = null; }
        request = null;
        if (torchButton != null) { torchButton.setText("打开闪光灯"); torchButton.setVisibility(View.GONE); }
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != PICK_IMAGE || result != RESULT_OK || data == null || data.getData() == null) return;
        decodeImage(data.getData());
    }

    private void decodeImage(final Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme())) { status.setText("请选择相册中的图片"); return; }
        pendingImage = uri;
        galleryLoading = true; closeCamera(); imageButton.setEnabled(false); status.setText("正在识别二维码…");
        analysis.post(() -> {
            String decoded = null, error = "图片中没有找到二维码";
            Bitmap bitmap = null;
            try {
                bitmap = NativeAvatar.decodeDocument(this, uri, 1600);
                int width = bitmap.getWidth(), height = bitmap.getHeight();
                int[] pixels = new int[width * height];
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
                decoded = NativeQrDecoder.rgb(pixels, width, height);
            } catch (OutOfMemoryError failure) { error = "图片尺寸过大，请换一张图片";
            } catch (Exception failure) { error = failure.getMessage() == null ? "无法读取这张图片" : failure.getMessage(); }
            finally { if (bitmap != null) bitmap.recycle(); }
            final String text = decoded, message = error;
            main.post(() -> {
                if (destroyed || isFinishing()) return;
                pendingImage = null; galleryLoading = false; imageButton.setEnabled(true);
                if (!active) { deferredText = text; deferredMessage = text == null ? message : null; return; }
                if (text != null) deliver(text);
                else { scanHint = message; status.setText(message); openCamera(); }
            });
        });
    }

    private void deliver(String text) {
        if (destroyed || delivered || isFinishing()) return;
        delivered = true; closeCamera();
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_TEXT, text));
        finish();
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) { openCamera(); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) { configureTransform(); }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) { closeCamera(); return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) { }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setTypeface(font); view.setGravity(Gravity.CENTER_VERTICAL); return view;
    }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable background = new GradientDrawable(); background.setColor(color); background.setCornerRadius(dp(radius)); return background;
    }
    private Button button(String value) {
        Button button = new Button(this); button.setText(value); button.setAllCaps(false); button.setTextSize(14); button.setTextColor(Color.WHITE); button.setTypeface(font);
        button.setPadding(dp(8), 0, dp(8), 0); button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x3375A7BC), shape(0xFF263240, 18), null));
        return button;
    }
}
