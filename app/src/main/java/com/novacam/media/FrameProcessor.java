package com.novacam.media;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.Image;
import android.media.ImageReader;
import android.media.ImageWriter;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.ParcelFileDescriptor;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;

public class FrameProcessor {

    public static Bitmap transformBitmap(Bitmap src, int targetW, int targetH,
                                         int rotationDegrees, boolean mirrorHorizontal,
                                         boolean centerCrop) {
        if (src == null || targetW <= 0 || targetH <= 0) return src;

        Bitmap oriented = src;
        if (rotationDegrees != 0 || mirrorHorizontal) {
            Matrix matrix = new Matrix();
            if (mirrorHorizontal) {
                matrix.postScale(-1f, 1f, src.getWidth() / 2f, src.getHeight() / 2f);
            }
            if (rotationDegrees != 0) {
                matrix.postRotate(rotationDegrees);
            }
            oriented = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), matrix, true);
        }

        Bitmap output = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.BLACK);

        float srcW = oriented.getWidth();
        float srcH = oriented.getHeight();
        float scale = centerCrop
                ? Math.max(targetW / srcW, targetH / srcH)
                : Math.min(targetW / srcW, targetH / srcH);

        float scaledW = srcW * scale;
        float scaledH = srcH * scale;
        float left = (targetW - scaledW) / 2f;
        float top = (targetH - scaledH) / 2f;

        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(oriented, null, new RectF(left, top, left + scaledW, top + scaledH), paint);

        if (oriented != src) {
            oriented.recycle();
        }
        return output;
    }

    public static void writeBitmapToYuvImage(Bitmap bitmap, Image image) {
        if (bitmap == null || image == null) return;

        int width = image.getWidth();
        int height = image.getHeight();
        Bitmap scaled = (bitmap.getWidth() == width && bitmap.getHeight() == height)
                ? bitmap
                : Bitmap.createScaledBitmap(bitmap, width, height, true);

        int[] argb = new int[width * height];
        scaled.getPixels(argb, 0, width, 0, 0, width, height);

        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuf = planes[0].getBuffer();
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();

        int yRowStride = planes[0].getRowStride();
        int yPixelStride = planes[0].getPixelStride();
        int uRowStride = planes[1].getRowStride();
        int uPixelStride = planes[1].getPixelStride();
        int vRowStride = planes[2].getRowStride();
        int vPixelStride = planes[2].getPixelStride();

        for (int y = 0; y < height; y++) {
            int yRowOffset = y * yRowStride;
            int uvRowOffset = (y >> 1) * uRowStride;
            int vRowOffset = (y >> 1) * vRowStride;

            for (int x = 0; x < width; x++) {
                int pixel = argb[y * width + x];
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;

                int yVal = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                if (yVal < 0) yVal = 0;
                else if (yVal > 255) yVal = 255;

                int yPos = yRowOffset + x * yPixelStride;
                if (yPos < yBuf.limit()) {
                    yBuf.put(yPos, (byte) yVal);
                }

                if ((y & 1) == 0 && (x & 1) == 0) {
                    int uVal = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int vVal = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    if (uVal < 0) uVal = 0;
                    else if (uVal > 255) uVal = 255;
                    if (vVal < 0) vVal = 0;
                    else if (vVal > 255) vVal = 255;

                    int uvCol = (x >> 1);
                    int uPos = uvRowOffset + uvCol * uPixelStride;
                    int vPos = vRowOffset + uvCol * vPixelStride;
                    if (uPos < uBuf.limit()) uBuf.put(uPos, (byte) uVal);
                    if (vPos < vBuf.limit()) vBuf.put(vPos, (byte) vVal);
                }
            }
        }

        if (scaled != bitmap) {
            scaled.recycle();
        }
    }

    public static byte[] bitmapToNv21(Bitmap bitmap, int width, int height) {
        Bitmap scaled = (bitmap.getWidth() == width && bitmap.getHeight() == height)
                ? bitmap
                : Bitmap.createScaledBitmap(bitmap, width, height, true);

        int[] argb = new int[width * height];
        scaled.getPixels(argb, 0, width, 0, 0, width, height);

        int frameSize = width * height;
        byte[] nv21 = new byte[frameSize + (frameSize / 2)];
        int yIndex = 0;
        int uvIndex = frameSize;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = argb[y * width + x];
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;

                int yVal = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                nv21[yIndex++] = (byte) Math.max(0, Math.min(255, yVal));

                if ((y & 1) == 0 && (x & 1) == 0 && uvIndex + 1 < nv21.length) {
                    int uVal = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int vVal = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    nv21[uvIndex++] = (byte) Math.max(0, Math.min(255, vVal));
                    nv21[uvIndex++] = (byte) Math.max(0, Math.min(255, uVal));
                }
            }
        }

        if (scaled != bitmap) {
            scaled.recycle();
        }
        return nv21;
    }

    public static Bitmap yuvImageToBitmap(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane[] planes = image.getPlanes();

        ByteBuffer yBuf = planes[0].getBuffer();
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();

        int yRowStride = planes[0].getRowStride();
        int yPixelStride = planes[0].getPixelStride();
        int uRowStride = planes[1].getRowStride();
        int uPixelStride = planes[1].getPixelStride();
        int vRowStride = planes[2].getRowStride();
        int vPixelStride = planes[2].getPixelStride();

        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            int yRowOffset = y * yRowStride;
            int uRowOffset = (y >> 1) * uRowStride;
            int vRowOffset = (y >> 1) * vRowStride;

            for (int x = 0; x < width; x++) {
                int yIdx = yRowOffset + x * yPixelStride;
                int uvCol = x >> 1;
                int uIdx = uRowOffset + uvCol * uPixelStride;
                int vIdx = vRowOffset + uvCol * vPixelStride;

                int Y = (yIdx < yBuf.limit()) ? (yBuf.get(yIdx) & 0xFF) : 16;
                int U = (uIdx < uBuf.limit()) ? (uBuf.get(uIdx) & 0xFF) - 128 : 0;
                int V = (vIdx < vBuf.limit()) ? (vBuf.get(vIdx) & 0xFF) - 128 : 0;

                int c = Math.max(0, Y - 16);
                int r = (298 * c + 409 * V + 128) >> 8;
                int g = (298 * c - 100 * U - 208 * V + 128) >> 8;
                int b = (298 * c + 516 * U + 128) >> 8;

                r = Math.max(0, Math.min(255, r));
                g = Math.max(0, Math.min(255, g));
                b = Math.max(0, Math.min(255, b));

                pixels[y * width + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }

        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bmp.setPixels(pixels, 0, width, 0, 0, width, height);
        return bmp;
    }

    public static void writeBitmapToJpegWriter(Bitmap bitmap, ImageWriter jpegWriter, int targetW, int targetH) {
        if (bitmap == null || jpegWriter == null) return;
        try {
            Bitmap scaled = (bitmap.getWidth() == targetW && bitmap.getHeight() == targetH)
                    ? bitmap
                    : Bitmap.createScaledBitmap(bitmap, targetW, targetH, true);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 92, baos);
            byte[] jpegBytes = baos.toByteArray();

            Image img = jpegWriter.dequeueInputImage();
            ByteBuffer buffer = img.getPlanes()[0].getBuffer();
            buffer.clear();
            int bytesToWrite = Math.min(jpegBytes.length, buffer.capacity());
            buffer.put(jpegBytes, 0, bytesToWrite);
            jpegWriter.queueInputImage(img);

            if (scaled != bitmap) {
                scaled.recycle();
            }
        } catch (Throwable ignored) {
        }
    }

    public static class VideoLoopDecoder {
        private volatile boolean running = false;
        private Thread decoderThread;
        private volatile Bitmap latestFrame;

        public Bitmap getLatestFrame() {
            return latestFrame;
        }

        public void start(final NovaCamConfig config,
                          final List<Surface> canvasPreviewSurfaces,
                          final List<ImageWriter> yuvWriters,
                          final int rotation,
                          final boolean mirror,
                          final boolean centerCrop) {
            stop();
            running = true;

            decoderThread = new Thread(() -> {
                while (running) {
                    ParcelFileDescriptor pfd = null;
                    MediaExtractor extractor = null;
                    MediaCodec codec = null;
                    ImageReader internalReader = null;

                    try {
                        pfd = config.openMediaDescriptor();
                        if (pfd == null) break;

                        extractor = new MediaExtractor();
                        extractor.setDataSource(pfd.getFileDescriptor());

                        int videoTrack = -1;
                        MediaFormat format = null;
                        for (int i = 0; i < extractor.getTrackCount(); i++) {
                            MediaFormat f = extractor.getTrackFormat(i);
                            String mime = f.getString(MediaFormat.KEY_MIME);
                            if (mime != null && mime.startsWith("video/")) {
                                videoTrack = i;
                                format = f;
                                break;
                            }
                        }

                        if (videoTrack < 0 || format == null) break;
                        extractor.selectTrack(videoTrack);

                        int width = format.getInteger(MediaFormat.KEY_WIDTH);
                        int height = format.getInteger(MediaFormat.KEY_HEIGHT);
                        String mime = format.getString(MediaFormat.KEY_MIME);

                        internalReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 3);
                        codec = MediaCodec.createDecoderByType(mime);
                        codec.configure(format, internalReader.getSurface(), null, 0);
                        codec.start();

                        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                        boolean inputDone = false;
                        long startMs = System.currentTimeMillis();

                        while (running) {
                            if (!inputDone) {
                                int inIdx = codec.dequeueInputBuffer(10000);
                                if (inIdx >= 0) {
                                    ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                                    int sampleSize = extractor.readSampleData(inBuf, 0);
                                    if (sampleSize < 0) {
                                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                        inputDone = true;
                                    } else {
                                        long ptsUs = extractor.getSampleTime();
                                        codec.queueInputBuffer(inIdx, 0, sampleSize, ptsUs, 0);
                                        extractor.advance();
                                    }
                                }
                            }

                            int outIdx = codec.dequeueOutputBuffer(info, 10000);
                            if (outIdx >= 0) {
                                boolean render = info.size > 0;
                                if (render) {
                                    long ptsMs = info.presentationTimeUs / 1000L;
                                    long elapsed = System.currentTimeMillis() - startMs;
                                    if (ptsMs > elapsed) {
                                        Thread.sleep(Math.min(ptsMs - elapsed, 50));
                                    }
                                }
                                codec.releaseOutputBuffer(outIdx, render);

                                if (render) {
                                    Image decodedImg = internalReader.acquireLatestImage();
                                    if (decodedImg != null) {
                                        try {
                                            Bitmap rawBmp = yuvImageToBitmap(decodedImg);
                                            latestFrame = rawBmp;

                                            for (ImageWriter writer : yuvWriters) {
                                                try {
                                                    Image outImg = writer.dequeueInputImage();
                                                    Bitmap fitted = transformBitmap(
                                                            rawBmp,
                                                            outImg.getWidth(),
                                                            outImg.getHeight(),
                                                            rotation,
                                                            mirror,
                                                            centerCrop
                                                    );
                                                    writeBitmapToYuvImage(fitted, outImg);
                                                    writer.queueInputImage(outImg);
                                                    fitted.recycle();
                                                } catch (Throwable ignored) {
                                                }
                                            }

                                            for (Surface preview : canvasPreviewSurfaces) {
                                                if (preview != null && preview.isValid()) {
                                                    try {
                                                        Canvas c = preview.lockCanvas(null);
                                                        if (c != null) {
                                                            Bitmap fitted = transformBitmap(
                                                                    rawBmp,
                                                                    c.getWidth(),
                                                                    c.getHeight(),
                                                                    rotation,
                                                                    mirror,
                                                                    centerCrop
                                                            );
                                                            c.drawBitmap(fitted, 0, 0, null);
                                                            preview.unlockCanvasAndPost(c);
                                                            fitted.recycle();
                                                        }
                                                    } catch (Throwable ignored) {
                                                    }
                                                }
                                            }
                                        } finally {
                                            decodedImg.close();
                                        }
                                    }
                                }

                                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                    break;
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                        break;
                    } finally {
                        try { if (codec != null) { codec.stop(); codec.release(); } } catch (Throwable ignored) {}
                        try { if (extractor != null) extractor.release(); } catch (Throwable ignored) {}
                        try { if (internalReader != null) internalReader.close(); } catch (Throwable ignored) {}
                        try { if (pfd != null) pfd.close(); } catch (Throwable ignored) {}
                    }
                }
            }, "NovaCam-VideoLoopDecoder");
            decoderThread.start();
        }

        public void stop() {
            running = false;
            if (decoderThread != null) {
                decoderThread.interrupt();
                decoderThread = null;
            }
        }
    }
}
