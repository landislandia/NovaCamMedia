package com.novacam.media;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileNotFoundException;

public class MediaBridgeProvider extends ContentProvider {

    public static final String AUTHORITY = "com.novacam.media.provider";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);
    public static final Uri MEDIA_URI = Uri.parse("content://" + AUTHORITY + "/active_media");

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        Context ctx = getContext();
        if (ctx == null) return null;

        if ("getConfig".equals(method)) {
            SharedPreferences prefs = ctx.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
            File vid = new File(ctx.getFilesDir(), MainActivity.INTERNAL_VIDEO_NAME);
            File img = new File(ctx.getFilesDir(), MainActivity.INTERNAL_IMAGE_NAME);

            String detectedType = "none";
            if (vid.exists() && vid.length() > 0) detectedType = "video";
            else if (img.exists() && img.length() > 0) detectedType = "image";

            Bundle result = new Bundle();
            result.putBoolean("enabled", prefs.getBoolean("enabled", true));
            result.putString("mediaType", detectedType);
            result.putInt("rotation", prefs.getInt("rotation", 0));
            result.putBoolean("mirrorHorizontal", prefs.getBoolean("mirrorHorizontal", false));
            result.putBoolean("autoMirrorFront", prefs.getBoolean("autoMirrorFront", true));
            result.putString("scaleMode", prefs.getString("scaleMode", "CENTER_CROP"));
            return result;
        }
        return super.call(method, arg, extras);
    }

    @Nullable
    @Override
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
        Context ctx = getContext();
        if (ctx == null) throw new FileNotFoundException("Contexto nulo");

        File vid = new File(ctx.getFilesDir(), MainActivity.INTERNAL_VIDEO_NAME);
        if (vid.exists() && vid.length() > 0) {
            return ParcelFileDescriptor.open(vid, ParcelFileDescriptor.MODE_READ_ONLY);
        }

        File img = new File(ctx.getFilesDir(), MainActivity.INTERNAL_IMAGE_NAME);
        if (img.exists() && img.length() > 0) {
            return ParcelFileDescriptor.open(img, ParcelFileDescriptor.MODE_READ_ONLY);
        }

        throw new FileNotFoundException("No hay archivo activo en NovaCam Media");
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] p, @Nullable String s, @Nullable String[] sa, @Nullable String so) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return "application/octet-stream";
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String s, @Nullable String[] sa) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String s, @Nullable String[] sa) {
        return 0;
    }
}
