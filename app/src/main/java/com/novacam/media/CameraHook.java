package com.novacam.media;

import android.app.AndroidAppHelper;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.Image;
import android.media.ImageReader;
import android.media.ImageWriter;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class CameraHook implements IXposedHookLoadPackage {

    private static final String TAG = "[NovaCamMedia] ";

    private static class ReaderMeta {
        final int width;
        final int height;
        final int format;

        ReaderMeta(int width, int height, int format) {
            this.width = width;
            this.height = height;
            this.format = format;
        }
    }

    private static final Map<Surface, ReaderMeta> TRACKED_READERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static volatile boolean isCurrentCameraFront = false;

    private static MediaPlayer activeMediaPlayer;
    private static ParcelFileDescriptor activeVideoPfd;
    private static FrameProcessor.VideoLoopDecoder activeVideoDecoder;
    private static ScheduledExecutorService imageLoopExecutor;

    private static final List<ImageWriter> activeYuvWriters = new CopyOnWriteArrayList<>();
    private static final List<ImageWriter> activeJpegWriters = new CopyOnWriteArrayList<>();
    private static final Map<ImageWriter, ReaderMeta> writerMetaMap =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static Surface camera1PreviewSurface;
    private static SurfaceTexture dummyCamera1Texture;
    private static SurfaceTexture dummyCamera2Texture;
    private static Camera.PreviewCallback camera1PreviewCallback;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if ("com.novacam.media".equals(lpparam.packageName)) {
            return;
        }

        hookImageReader();
        hookCameraManager();
        hookCamera1Api();
        hookCamera2Api(lpparam.classLoader);
    }

    private void hookImageReader() {
        try {
            XposedBridge.hookAllMethods(ImageReader.class, "newInstance", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    ImageReader reader = (ImageReader) param.getResult();
                    if (reader != null && reader.getSurface() != null) {
                        TRACKED_READERS.put(
                                reader.getSurface(),
                                new ReaderMeta(reader.getWidth(), reader.getHeight(), reader.getImageFormat())
                        );
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Error hook ImageReader: " + t.getMessage());
        }
    }

    private void hookCameraManager() {
        try {
            XposedBridge.hookAllMethods(CameraManager.class, "openCamera", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] instanceof String) {
                        String cameraId = (String) param.args[0];
                        try {
                            Context ctx = AndroidAppHelper.currentApplication();
                            if (ctx != null) {
                                CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
                                CameraCharacteristics chars = cm.getCameraCharacteristics(cameraId);
                                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                                isCurrentCameraFront = (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void hookCamera1Api() {
        try {
            XposedHelpers.findAndHookMethod(Camera.class, "open", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    int id = (int) param.args[0];
                    Camera.CameraInfo info = new Camera.CameraInfo();
                    Camera.getCameraInfo(id, info);
                    isCurrentCameraFront = (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT);
                }
            });

            XposedHelpers.findAndHookMethod(Camera.class, "setPreviewTexture", SurfaceTexture.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    NovaCamConfig cfg = NovaCamConfig.load();
                    if (!cfg.isActive()) return;

                    SurfaceTexture tex = (SurfaceTexture) param.args[0];
                    if (tex != null) {
                        if (camera1PreviewSurface != null) camera1PreviewSurface.release();
                        camera1PreviewSurface = new Surface(tex);
                        if (dummyCamera1Texture == null) dummyCamera1Texture = new SurfaceTexture(11);
                        param.args[0] = dummyCamera1Texture;
                    }
                }
            });

            XposedHelpers.findAndHookMethod(Camera.class, "setPreviewDisplay", SurfaceHolder.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    NovaCamConfig cfg = NovaCamConfig.load();
                    if (!cfg.isActive()) return;

                    SurfaceHolder holder = (SurfaceHolder) param.args[0];
                    if (holder != null && holder.getSurface() != null) {
                        camera1PreviewSurface = holder.getSurface();
                        param.args[0] = null;
                    }
                }
            });

            XC_MethodHook previewCbHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] instanceof Camera.PreviewCallback) {
                        camera1PreviewCallback = (Camera.PreviewCallback) param.args[0];
                    }
                }
            };
            XposedHelpers.findAndHookMethod(Camera.class, "setPreviewCallback", Camera.PreviewCallback.class, previewCbHook);
            XposedHelpers.findAndHookMethod(Camera.class, "setPreviewCallbackWithBuffer", Camera.PreviewCallback.class, previewCbHook);
            XposedHelpers.findAndHookMethod(Camera.class, "setOneShotPreviewCallback", Camera.PreviewCallback.class, previewCbHook);

            XposedHelpers.findAndHookMethod(Camera.class, "startPreview", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    NovaCamConfig cfg = NovaCamConfig.load();
                    if (!cfg.isActive()) return;

                    List<Surface> previews = new ArrayList<>();
                    if (camera1PreviewSurface != null) previews.add(camera1PreviewSurface);
                    startVirtualPipeline(cfg, previews, Collections.emptyList(), Collections.emptyList(), (Camera) param.thisObject);
                }
            });

            XposedHelpers.findAndHookMethod(Camera.class, "stopPreview", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    stopVirtualPipeline();
                }
            });

            XposedHelpers.findAndHookMethod(Camera.class, "release", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    stopVirtualPipeline();
                }
            });

            XposedHelpers.findAndHookMethod(
                    Camera.class,
                    "takePicture",
                    Camera.ShutterCallback.class,
                    Camera.PictureCallback.class,
                    Camera.PictureCallback.class,
                    Camera.PictureCallback.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            NovaCamConfig cfg = NovaCamConfig.load();
                            if (!cfg.isActive()) return;

                            Camera.ShutterCallback shutter = (Camera.ShutterCallback) param.args[0];
                            Camera.PictureCallback jpegCb = (Camera.PictureCallback) param.args[3];
                            if (jpegCb != null) {
                                Bitmap frame = captureCurrentFrameBitmap(cfg);
                                if (frame != null) {
                                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                    frame.compress(Bitmap.CompressFormat.JPEG, 92, baos);
                                    byte[] jpegBytes = baos.toByteArray();
                                    frame.recycle();

                                    if (shutter != null) shutter.onShutter();
                                    jpegCb.onPictureTaken(jpegBytes, (Camera) param.thisObject);
                                    param.setResult(null);
                                }
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Error hook Camera1: " + t.getMessage());
        }
    }

    private void hookCamera2Api(ClassLoader classLoader) {
        try {
            Class<?> cameraDeviceImplClass = XposedHelpers.findClass(
                    "android.hardware.camera2.impl.CameraDeviceImpl", classLoader
            );

            XC_MethodHook sessionHook = new XC_MethodHook() {
                @Override
                @SuppressWarnings("unchecked")
                protected void beforeHookedMethod(MethodHookParam param) {
                    NovaCamConfig cfg = NovaCamConfig.load();
                    if (!cfg.isActive() || param.args == null || param.args.length == 0) return;

                    List<Surface> extractedSurfaces = new ArrayList<>();
                    Object firstArg = param.args[0];

                    if (firstArg instanceof List) {
                        List<?> list = (List<?>) firstArg;
                        if (list.isEmpty()) return;

                        if (list.get(0) instanceof Surface) {
                            extractedSurfaces.addAll((List<Surface>) list);
                            param.args[0] = Collections.singletonList(createDummyCamera2Surface());
                        } else if (list.get(0) instanceof OutputConfiguration) {
                            for (OutputConfiguration oc : (List<OutputConfiguration>) list) {
                                if (oc.getSurface() != null) extractedSurfaces.add(oc.getSurface());
                            }
                            param.args[0] = Collections.singletonList(
                                    new OutputConfiguration(createDummyCamera2Surface())
                            );
                        }
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                            && firstArg instanceof SessionConfiguration) {
                        SessionConfiguration sc = (SessionConfiguration) firstArg;
                        for (OutputConfiguration oc : sc.getOutputConfigurations()) {
                            if (oc.getSurface() != null) extractedSurfaces.add(oc.getSurface());
                        }
                        SessionConfiguration replacement = new SessionConfiguration(
                                sc.getSessionType(),
                                Collections.singletonList(new OutputConfiguration(createDummyCamera2Surface())),
                                sc.getExecutor(),
                                sc.getStateCallback()
                        );
                        param.args[0] = replacement;
                    }

                    if (!extractedSurfaces.isEmpty()) {
                        dispatchCamera2Surfaces(cfg, extractedSurfaces);
                    }
                }
            };

            XposedBridge.hookAllMethods(cameraDeviceImplClass, "createCaptureSession", sessionHook);
            XposedBridge.hookAllMethods(cameraDeviceImplClass, "createCaptureSessionByOutputConfigurations", sessionHook);
            XposedBridge.hookAllMethods(cameraDeviceImplClass, "createReprocessableCaptureSession", sessionHook);
            XposedBridge.hookAllMethods(cameraDeviceImplClass, "createReprocessableCaptureSessionByConfigurations", sessionHook);
            XposedBridge.hookAllMethods(cameraDeviceImplClass, "createConstrainedHighSpeedCaptureSession", sessionHook);

            Class<?> sessionImplClass = XposedHelpers.findClass(
                    "android.hardware.camera2.impl.CameraCaptureSessionImpl", classLoader
            );

            XC_MethodHook captureHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (activeJpegWriters.isEmpty() || param.args == null || param.args.length == 0) return;

                    NovaCamConfig cfg = NovaCamConfig.load();
                    if (!cfg.isActive()) return;

                    List<CaptureRequest> requests = new ArrayList<>();
                    if (param.args[0] instanceof CaptureRequest) {
                        requests.add((CaptureRequest) param.args[0]);
                    } else if (param.args[0] instanceof List) {
                        for (Object item : (List<?>) param.args[0]) {
                            if (item instanceof CaptureRequest) requests.add((CaptureRequest) item);
                        }
                    }

                    for (CaptureRequest req : requests) {
                        if (isJpegCaptureRequest(req)) {
                            deliverJpegCapture(cfg);
                            break;
                        }
                    }
                }
            };

            XposedBridge.hookAllMethods(sessionImplClass, "capture", captureHook);
            XposedBridge.hookAllMethods(sessionImplClass, "captureBurst", captureHook);
            XposedBridge.hookAllMethods(sessionImplClass, "captureSingleRequest", captureHook);

        } catch (Throwable t) {
            XposedBridge.log(TAG + "Error hook Camera2: " + t.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean isJpegCaptureRequest(CaptureRequest req) {
        try {
            Object surfaceSet = XposedHelpers.getObjectField(req, "mSurfaceSet");
            if (surfaceSet instanceof Collection) {
                for (Surface s : (Collection<Surface>) surfaceSet) {
                    ReaderMeta meta = TRACKED_READERS.get(s);
                    if (meta != null && meta.format == ImageFormat.JPEG) {
                        return true;
                    }
                }
                return false;
            }
        } catch (Throwable ignored) {
        }
        return req.get(CaptureRequest.JPEG_QUALITY) != null
                || req.get(CaptureRequest.JPEG_ORIENTATION) != null;
    }

    private static void deliverJpegCapture(final NovaCamConfig cfg) {
        new Thread(() -> {
            Bitmap base = captureCurrentFrameBitmap(cfg);
            if (base == null) return;

            for (ImageWriter writer : activeJpegWriters) {
                ReaderMeta meta = writerMetaMap.get(writer);
                if (meta != null) {
                    Bitmap fitted = FrameProcessor.transformBitmap(
                            base,
                            meta.width,
                            meta.height,
                            0,
                            false,
                            cfg.centerCrop
                    );
                    FrameProcessor.writeBitmapToJpegWriter(fitted, writer, meta.width, meta.height);
                    if (fitted != base) fitted.recycle();
                }
            }
            base.recycle();
        }, "NovaCam-JpegCapture").start();
    }

    private static void dispatchCamera2Surfaces(NovaCamConfig cfg, List<Surface> surfaces) {
        List<Surface> previewSurfaces = new ArrayList<>();
        List<Surface> yuvSurfaces = new ArrayList<>();
        List<Surface> jpegSurfaces = new ArrayList<>();

        for (Surface s : surfaces) {
            if (s == null || !s.isValid()) continue;
            ReaderMeta meta = TRACKED_READERS.get(s);
            if (meta != null) {
                if (meta.format == ImageFormat.JPEG) {
                    jpegSurfaces.add(s);
                } else if (meta.format == ImageFormat.YUV_420_888
                        || meta.format == ImageFormat.NV21
                        || meta.format == ImageFormat.YV12) {
                    yuvSurfaces.add(s);
                } else {
                    previewSurfaces.add(s);
                }
            } else {
                previewSurfaces.add(s);
            }
        }

        startVirtualPipeline(cfg, previewSurfaces, yuvSurfaces, jpegSurfaces, null);
    }

    private static synchronized void startVirtualPipeline(
            final NovaCamConfig cfg,
            final List<Surface> previewSurfaces,
            final List<Surface> yuvSurfaces,
            final List<Surface> jpegSurfaces,
            final Camera camera1Instance) {

        stopVirtualPipeline();

        final boolean mirror = cfg.effectiveMirror(isCurrentCameraFront);
        final int rotation = cfg.rotation;
        final boolean centerCrop = cfg.centerCrop;

        for (Surface yuvSurface : yuvSurfaces) {
            try {
                ReaderMeta meta = TRACKED_READERS.get(yuvSurface);
                ImageWriter writer = ImageWriter.newInstance(yuvSurface, 3);
                activeYuvWriters.add(writer);
                if (meta != null) writerMetaMap.put(writer, meta);
            } catch (Throwable ignored) {
            }
        }

        for (Surface jpegSurface : jpegSurfaces) {
            try {
                ReaderMeta meta = TRACKED_READERS.get(jpegSurface);
                ImageWriter writer = ImageWriter.newInstance(jpegSurface, 2);
                activeJpegWriters.add(writer);
                if (meta != null) writerMetaMap.put(writer, meta);
            } catch (Throwable ignored) {
            }
        }

        if (cfg.isVideo()) {
            boolean needsTransform = (rotation != 0 || mirror);
            List<Surface> canvasPreviews = new ArrayList<>();

            if (!previewSurfaces.isEmpty() && !needsTransform) {
                Surface primaryPreview = previewSurfaces.get(0);
                try {
                    activeVideoPfd = cfg.openMediaDescriptor();
                    if (activeVideoPfd != null) {
                        activeMediaPlayer = new MediaPlayer();
                        activeMediaPlayer.setSurface(primaryPreview);
                        activeMediaPlayer.setDataSource(activeVideoPfd.getFileDescriptor());
                        activeMediaPlayer.setLooping(true);
                        activeMediaPlayer.setVolume(0f, 0f);
                        activeMediaPlayer.setOnPreparedListener(MediaPlayer::start);
                        activeMediaPlayer.prepareAsync();
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "Error MediaPlayer: " + t.getMessage());
                }
                if (previewSurfaces.size() > 1) {
                    canvasPreviews.addAll(previewSurfaces.subList(1, previewSurfaces.size()));
                }
            } else {
                canvasPreviews.addAll(previewSurfaces);
            }

            if (!activeYuvWriters.isEmpty() || !canvasPreviews.isEmpty()) {
                activeVideoDecoder = new FrameProcessor.VideoLoopDecoder();
                activeVideoDecoder.start(cfg, canvasPreviews, activeYuvWriters, rotation, mirror, centerCrop);
            }
        } else {
            final Bitmap rawBitmap = loadStaticBitmap(cfg);
            if (rawBitmap == null) return;

            imageLoopExecutor = Executors.newSingleThreadScheduledExecutor();
            imageLoopExecutor.scheduleAtFixedRate(() -> {
                for (Surface preview : previewSurfaces) {
                    if (preview != null && preview.isValid()) {
                        try {
                            Canvas c = preview.lockCanvas(null);
                            if (c != null) {
                                Bitmap fitted = FrameProcessor.transformBitmap(
                                        rawBitmap, c.getWidth(), c.getHeight(), rotation, mirror, centerCrop
                                );
                                c.drawBitmap(fitted, 0, 0, null);
                                preview.unlockCanvasAndPost(c);
                                fitted.recycle();
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }

                for (ImageWriter writer : activeYuvWriters) {
                    try {
                        Image img = writer.dequeueInputImage();
                        Bitmap fitted = FrameProcessor.transformBitmap(
                                rawBitmap, img.getWidth(), img.getHeight(), rotation, mirror, centerCrop
                        );
                        FrameProcessor.writeBitmapToYuvImage(fitted, img);
                        writer.queueInputImage(img);
                        fitted.recycle();
                    } catch (Throwable ignored) {
                    }
                }

                if (camera1Instance != null && camera1PreviewCallback != null) {
                    try {
                        Camera.Size size = camera1Instance.getParameters().getPreviewSize();
                        if (size != null) {
                            Bitmap fitted = FrameProcessor.transformBitmap(
                                    rawBitmap, size.width, size.height, rotation, mirror, centerCrop
                            );
                            byte[] nv21 = FrameProcessor.bitmapToNv21(fitted, size.width, size.height);
                            camera1PreviewCallback.onPreviewFrame(nv21, camera1Instance);
                            fitted.recycle();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }, 0, 33, TimeUnit.MILLISECONDS);
        }
    }

    private static Bitmap loadStaticBitmap(NovaCamConfig cfg) {
        try (ParcelFileDescriptor pfd = cfg.openMediaDescriptor()) {
            if (pfd != null) {
                return BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor());
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Bitmap captureCurrentFrameBitmap(NovaCamConfig cfg) {
        boolean mirror = cfg.effectiveMirror(isCurrentCameraFront);
        Bitmap raw = null;

        if (cfg.isVideo()) {
            if (activeVideoDecoder != null && activeVideoDecoder.getLatestFrame() != null) {
                Bitmap latest = activeVideoDecoder.getLatestFrame();
                raw = latest.copy(Bitmap.Config.ARGB_8888, false);
            } else {
                long posUs = 0;
                try {
                    if (activeMediaPlayer != null && activeMediaPlayer.isPlaying()) {
                        posUs = activeMediaPlayer.getCurrentPosition() * 1000L;
                    }
                } catch (Throwable ignored) {
                }

                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                try (ParcelFileDescriptor pfd = cfg.openMediaDescriptor()) {
                    if (pfd != null) {
                        retriever.setDataSource(pfd.getFileDescriptor());
                        raw = retriever.getFrameAtTime(posUs, MediaMetadataRetriever.OPTION_CLOSEST);
                    }
                } catch (Throwable ignored) {
                } finally {
                    try {
                        retriever.release();
                    } catch (Throwable ignored) {
                    }
                }
            }
        } else {
            raw = loadStaticBitmap(cfg);
        }

        if (raw == null) return null;
        int outW = (cfg.rotation == 90 || cfg.rotation == 270) ? raw.getHeight() : raw.getWidth();
        int outH = (cfg.rotation == 90 || cfg.rotation == 270) ? raw.getWidth() : raw.getHeight();
        Bitmap transformed = FrameProcessor.transformBitmap(raw, outW, outH, cfg.rotation, mirror, cfg.centerCrop);
        if (transformed != raw) raw.recycle();
        return transformed;
    }

    private static Surface createDummyCamera2Surface() {
        if (dummyCamera2Texture == null) {
            dummyCamera2Texture = new SurfaceTexture(12);
            dummyCamera2Texture.setDefaultBufferSize(1280, 720);
        }
        return new Surface(dummyCamera2Texture);
    }

    private static synchronized void stopVirtualPipeline() {
        if (activeMediaPlayer != null) {
            try {
                if (activeMediaPlayer.isPlaying()) activeMediaPlayer.stop();
                activeMediaPlayer.reset();
                activeMediaPlayer.release();
            } catch (Throwable ignored) {
            } finally {
                activeMediaPlayer = null;
            }
        }

        if (activeVideoPfd != null) {
            try {
                activeVideoPfd.close();
            } catch (Throwable ignored) {
            } finally {
                activeVideoPfd = null;
            }
        }

        if (activeVideoDecoder != null) {
            activeVideoDecoder.stop();
            activeVideoDecoder = null;
        }

        if (imageLoopExecutor != null) {
            imageLoopExecutor.shutdownNow();
            imageLoopExecutor = null;
        }

        for (ImageWriter w : activeYuvWriters) {
            try {
                w.close();
            } catch (Throwable ignored) {
            }
        }
        activeYuvWriters.clear();

        for (ImageWriter w : activeJpegWriters) {
            try {
                w.close();
            } catch (Throwable ignored) {
            }
        }
        activeJpegWriters.clear();
        writerMetaMap.clear();
    }
}
