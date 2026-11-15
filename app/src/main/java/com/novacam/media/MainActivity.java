package com.novacam.media;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity {

    public static final String PREFS_NAME = "novacam_prefs";
    public static final String INTERNAL_VIDEO_NAME = "active_video.mp4";
    public static final String INTERNAL_IMAGE_NAME = "active_image.jpg";

    private SwitchMaterial switchEnabled;
    private SwitchMaterial switchMirror;
    private SwitchMaterial switchAutoMirrorFront;
    private RadioGroup rgRotation;
    private RadioGroup rgScaleMode;
    private TextView tvStatus;
    private ImageView ivPreview;

    private final ActivityResultLauncher<String[]> pickVideoLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) importMediaUri(uri, true);
            });

    private final ActivityResultLauncher<String[]> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) importMediaUri(uri, false);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        switchEnabled = findViewById(R.id.switchEnabled);
        switchMirror = findViewById(R.id.switchMirror);
        switchAutoMirrorFront = findViewById(R.id.switchAutoMirrorFront);
        rgRotation = findViewById(R.id.rgRotation);
        rgScaleMode = findViewById(R.id.rgScaleMode);
        tvStatus = findViewById(R.id.tvStatus);
        ivPreview = findViewById(R.id.ivPreview);

        Button btnSelectVideo = findViewById(R.id.btnSelectVideo);
        Button btnSelectImage = findViewById(R.id.btnSelectImage);
        Button btnClearMedia = findViewById(R.id.btnClearMedia);

        requestStoragePermissionsIfNeeded();
        loadUiFromPrefs();

        btnSelectVideo.setOnClickListener(v -> pickVideoLauncher.launch(new String[]{"video/*"}));
        btnSelectImage.setOnClickListener(v -> pickImageLauncher.launch(new String[]{"image/*"}));
        btnClearMedia.setOnClickListener(v -> clearActiveMedia());

        switchEnabled.setOnCheckedChangeListener((b, checked) -> saveSettings());
        switchMirror.setOnCheckedChangeListener((b, checked) -> {
            saveSettings();
            refreshPreview();
        });
        switchAutoMirrorFront.setOnCheckedChangeListener((b, checked) -> saveSettings());
        rgRotation.setOnCheckedChangeListener((g, id) -> {
            saveSettings();
            refreshPreview();
        });
        rgScaleMode.setOnCheckedChangeListener((g, id) -> saveSettings());

        refreshPreview();
    }

    private void requestStoragePermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.READ_MEDIA_VIDEO,
                        Manifest.permission.READ_MEDIA_IMAGES
                }, 101);
            }
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, 101);
            }
        }
    }

    private void importMediaUri(Uri uri, boolean isVideo) {
        try {
            File internalVideo = new File(getFilesDir(), INTERNAL_VIDEO_NAME);
            File internalImage = new File(getFilesDir(), INTERNAL_IMAGE_NAME);
            if (internalVideo.exists()) internalVideo.delete();
            if (internalImage.exists()) internalImage.delete();

            File targetInternal = isVideo ? internalVideo : internalImage;
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(targetInternal)) {
                if (in == null) {
                    Toast.makeText(this, "No se pudo abrir el archivo", Toast.LENGTH_SHORT).show();
                    return;
                }
                byte[] buf = new byte[16384];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            }

            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            prefs.edit()
                    .putString("mediaType", isVideo ? "video" : "image")
                    .putBoolean("enabled", true)
                    .apply();

            switchEnabled.setChecked(true);
            syncFallbackPublicDirectory(targetInternal, isVideo);
            saveSettings();
            refreshPreview();
            Toast.makeText(this, "Medio configurado correctamente", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Error al importar: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void clearActiveMedia() {
        File internalVideo = new File(getFilesDir(), INTERNAL_VIDEO_NAME);
        File internalImage = new File(getFilesDir(), INTERNAL_IMAGE_NAME);
        if (internalVideo.exists()) internalVideo.delete();
        if (internalImage.exists()) internalImage.delete();

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString("mediaType", "none")
                .apply();

        try {
            File fallbackDir = getFallbackDir();
            File fbVid = new File(fallbackDir, "virtual_source.mp4");
            File fbImg = new File(fallbackDir, "virtual_source.jpg");
            if (fbVid.exists()) fbVid.delete();
            if (fbImg.exists()) fbImg.delete();
        } catch (Throwable ignored) {
        }

        saveSettings();
        refreshPreview();
    }

    private void loadUiFromPrefs() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        switchEnabled.setChecked(prefs.getBoolean("enabled", true));
        switchMirror.setChecked(prefs.getBoolean("mirrorHorizontal", false));
        switchAutoMirrorFront.setChecked(prefs.getBoolean("autoMirrorFront", true));

        int rotation = prefs.getInt("rotation", 0);
        if (rotation == 90) rgRotation.check(R.id.rbRot90);
        else if (rotation == 180) rgRotation.check(R.id.rbRot180);
        else if (rotation == 270) rgRotation.check(R.id.rbRot270);
        else rgRotation.check(R.id.rbRot0);

        String scaleMode = prefs.getString("scaleMode", "CENTER_CROP");
        if ("FIT_CENTER".equals(scaleMode)) rgScaleMode.check(R.id.rbFitCenter);
        else rgScaleMode.check(R.id.rbCenterCrop);
    }

    private void saveSettings() {
        int rotation = 0;
        int rotId = rgRotation.getCheckedRadioButtonId();
        if (rotId == R.id.rbRot90) rotation = 90;
        else if (rotId == R.id.rbRot180) rotation = 180;
        else if (rotId == R.id.rbRot270) rotation = 270;

        String scaleMode = (rgScaleMode.getCheckedRadioButtonId() == R.id.rbFitCenter)
                ? "FIT_CENTER" : "CENTER_CROP";

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
                .putBoolean("enabled", switchEnabled.isChecked())
                .putBoolean("mirrorHorizontal", switchMirror.isChecked())
                .putBoolean("autoMirrorFront", switchAutoMirrorFront.isChecked())
                .putInt("rotation", rotation)
                .putString("scaleMode", scaleMode)
                .apply();

        writeFallbackConfigJson();
    }

    private File getFallbackDir() {
        File dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        File dir = new File(dcim, "NovaCam");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private void syncFallbackPublicDirectory(File sourceInternal, boolean isVideo) {
        try {
            File dir = getFallbackDir();
            File oldVid = new File(dir, "virtual_source.mp4");
            File oldImg = new File(dir, "virtual_source.jpg");
            if (oldVid.exists()) oldVid.delete();
            if (oldImg.exists()) oldImg.delete();

            File dest = new File(dir, isVideo ? "virtual_source.mp4" : "virtual_source.jpg");
            try (InputStream in = new FileInputStream(sourceInternal);
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[16384];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void writeFallbackConfigJson() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            JSONObject json = new JSONObject();
            json.put("enabled", prefs.getBoolean("enabled", true));
            json.put("mediaType", prefs.getString("mediaType", "none"));
            json.put("rotation", prefs.getInt("rotation", 0));
            json.put("mirrorHorizontal", prefs.getBoolean("mirrorHorizontal", false));
            json.put("autoMirrorFront", prefs.getBoolean("autoMirrorFront", true));
            json.put("scaleMode", prefs.getString("scaleMode", "CENTER_CROP"));

            File cfgFile = new File(getFallbackDir(), "config.json");
            try (FileOutputStream fos = new FileOutputStream(cfgFile)) {
                fos.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    private void refreshPreview() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        File internalVideo = new File(getFilesDir(), INTERNAL_VIDEO_NAME);
        File internalImage = new File(getFilesDir(), INTERNAL_IMAGE_NAME);
        int rotation = prefs.getInt("rotation", 0);
        boolean mirror = prefs.getBoolean("mirrorHorizontal", false);

        if (internalVideo.exists() && internalVideo.length() > 0) {
            tvStatus.setText("Activo: VIDEO (" + (internalVideo.length() / 1024) + " KB) | Rotación: " + rotation + "°");
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(internalVideo.getAbsolutePath());
                Bitmap frame = retriever.getFrameAtTime(0);
                if (frame != null) {
                    Bitmap transformed = FrameProcessor.transformBitmap(
                            frame, frame.getWidth(), frame.getHeight(), rotation, mirror, false
                    );
                    ivPreview.setImageBitmap(transformed);
                }
            } catch (Throwable ignored) {
            } finally {
                try {
                    retriever.release();
                } catch (Throwable ignored) {
                }
            }
        } else if (internalImage.exists() && internalImage.length() > 0) {
            tvStatus.setText("Activo: IMAGEN (" + (internalImage.length() / 1024) + " KB) | Rotación: " + rotation + "°");
            Bitmap raw = BitmapFactory.decodeFile(internalImage.getAbsolutePath());
            if (raw != null) {
                Bitmap transformed = FrameProcessor.transformBitmap(
                        raw, raw.getWidth(), raw.getHeight(), rotation, mirror, false
                );
                ivPreview.setImageBitmap(transformed);
            }
        } else {
            tvStatus.setText("Sin medio cargado (la app usará la cámara real)");
            ivPreview.setImageDrawable(null);
        }
    }
}
