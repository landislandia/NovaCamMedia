package com.novacam.media;

import android.app.AndroidAppHelper;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class NovaCamConfig {

    public final boolean enabled;
    public final String mediaType;
    public final int rotation;
    public final boolean mirrorHorizontal;
    public final boolean autoMirrorFront;
    public final boolean centerCrop;
    public final boolean fromProvider;
    public final File fallbackFile;
    public final Uri mediaStoreUri;

    private NovaCamConfig(boolean enabled, String mediaType, int rotation,
                          boolean mirrorHorizontal, boolean autoMirrorFront,
                          boolean centerCrop, boolean fromProvider,
                          File fallbackFile, Uri mediaStoreUri) {
        this.enabled = enabled;
        this.mediaType = mediaType;
        this.rotation = rotation;
        this.mirrorHorizontal = mirrorHorizontal;
        this.autoMirrorFront = autoMirrorFront;
        this.centerCrop = centerCrop;
        this.fromProvider = fromProvider;
        this.fallbackFile = fallbackFile;
        this.mediaStoreUri = mediaStoreUri;
    }

    public boolean isActive() {
        return enabled && ("video".equals(mediaType) || "image".equals(mediaType));
    }

    public boolean isVideo() {
        return "video".equals(mediaType);
    }

    public boolean effectiveMirror(boolean isFrontCamera) {
        boolean mirror = mirrorHorizontal;
        if (autoMirrorFront && isFrontCamera) {
            mirror = !mirror;
        }
        return mirror;
    }

    public ParcelFileDescriptor openMediaDescriptor() {
        try {
            Context appCtx = AndroidAppHelper.currentApplication();
            if (fromProvider && appCtx != null) {
                return appCtx.getContentResolver()
                        .openFileDescriptor(MediaBridgeProvider.MEDIA_URI, "r");
            }
            if (mediaStoreUri != null && appCtx != null) {
                return appCtx.getContentResolver()
                        .openFileDescriptor(mediaStoreUri, "r");
            }
            if (fallbackFile != null && fallbackFile.exists() && fallbackFile.canRead()) {
                return ParcelFileDescriptor.open(fallbackFile, ParcelFileDescriptor.MODE_READ_ONLY);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static NovaCamConfig load() {
        Context appCtx = AndroidAppHelper.currentApplication();

        try {
            if (appCtx != null) {
                Bundle b = appCtx.getContentResolver().call(
                        MediaBridgeProvider.CONTENT_URI, "getConfig", null, null
                );
                if (b != null) {
                    boolean enabled = b.getBoolean("enabled", true);
                    String mediaType = b.getString("mediaType", "none");
                    int rotation = b.getInt("rotation", 0);
                    boolean mirror = b.getBoolean("mirrorHorizontal", false);
                    boolean autoFront = b.getBoolean("autoMirrorFront", true);
                    boolean crop = !"FIT_CENTER".equals(b.getString("scaleMode", "CENTER_CROP"));
                    return new NovaCamConfig(enabled, mediaType, rotation, mirror, autoFront, crop, true, null, null);
                }
            }
        } catch (Throwable ignored) {
        }

        File[] candidateDirs = new File[]{
                new File("/sdcard/Download/NovaCam"),
                new File("/sdcard/DCIM/NovaCam"),
                new File("/sdcard/novacam")
        };

        boolean enabled = true;
        int rotation = 0;
        boolean mirror = false;
        boolean autoFront = true;
        boolean crop = true;

        for (File dir : candidateDirs) {
            File cfg = new File(dir, "config.json");
            if (cfg.exists() && cfg.canRead()) {
                try (FileInputStream fis = new FileInputStream(cfg)) {
                    byte[] data = new byte[(int) cfg.length()];
                    int read = fis.read(data);
                    if (read > 0) {
                        JSONObject json = new JSONObject(new String(data, 0, read, StandardCharsets.UTF_8));
                        enabled = json.optBoolean("enabled", true);
                        rotation = json.optInt("rotation", 0);
                        mirror = json.optBoolean("mirrorHorizontal", false);
                        autoFront = json.optBoolean("autoMirrorFront", true);
                        crop = !"FIT_CENTER".equals(json.optString("scaleMode", "CENTER_CROP"));
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        for (File dir : candidateDirs) {
            if (!dir.exists() || !dir.isDirectory()) continue;
            File vid = new File(dir, "virtual_source.mp4");
            if (vid.exists() && vid.canRead() && vid.length() > 0) {
                return new NovaCamConfig(enabled, "video", rotation, mirror, autoFront, crop, false, vid, null);
            }
            File img = new File(dir, "virtual_source.jpg");
            if (img.exists() && img.canRead() && img.length() > 0) {
                return new NovaCamConfig(enabled, "image", rotation, mirror, autoFront, crop, false, img, null);
            }
        }

        if (appCtx != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri cfgUri = queryMediaStoreByName(appCtx, MediaStore.Downloads.EXTERNAL_CONTENT_URI, "novacam_config.json");
            if (cfgUri != null) {
                try (InputStream is = appCtx.getContentResolver().openInputStream(cfgUri);
                     ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    if (is != null) {
                        byte[] chunk = new byte[4096];
                        int n;
                        while ((n = is.read(chunk)) > 0) baos.write(chunk, 0, n);
                        JSONObject json = new JSONObject(baos.toString("UTF-8"));
                        enabled = json.optBoolean("enabled", true);
                        rotation = json.optInt("rotation", 0);
                        mirror = json.optBoolean("mirrorHorizontal", false);
                        autoFront = json.optBoolean("autoMirrorFront", true);
                        crop = !"FIT_CENTER".equals(json.optString("scaleMode", "CENTER_CROP"));
                    }
                } catch (Throwable ignored) {
                }
            }

            Uri vidUri = queryMediaStoreByName(appCtx, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "novacam_virtual_source.mp4");
            if (vidUri != null) {
                return new NovaCamConfig(enabled, "video", rotation, mirror, autoFront, crop, false, null, vidUri);
            }

            Uri imgUri = queryMediaStoreByName(appCtx, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "novacam_virtual_source.jpg");
            if (imgUri != null) {
                return new NovaCamConfig(enabled, "image", rotation, mirror, autoFront, crop, false, null, imgUri);
            }
        }

        return new NovaCamConfig(false, "none", 0, false, true, true, false, null, null);
    }

    private static Uri queryMediaStoreByName(Context ctx, Uri collection, String displayName) {
        String[] projection = new String[]{MediaStore.MediaColumns._ID};
        String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=?";
        try (Cursor cursor = ctx.getContentResolver().query(
                collection, projection, selection, new String[]{displayName},
                MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
            if (cursor != null && cursor.moveToFirst()) {
                long id = cursor.getLong(0);
                return ContentUris.withAppendedId(collection, id);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
