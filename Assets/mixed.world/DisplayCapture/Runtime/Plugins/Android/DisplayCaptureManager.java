package com.trev3d.DisplayCapture;

import static android.content.ContentValues.TAG;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.view.Surface;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.unity3d.player.UnityPlayer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.io.File;

public class DisplayCaptureManager implements ImageReader.OnImageAvailableListener {

    public static DisplayCaptureManager instance = null;
    private static Intent staticMPIntentData;
    private static int staticMPResultCode;
    public ArrayList<IDisplayCaptureReceiver> receivers;

    private ImageReader reader;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplayForUnity;
    private VirtualDisplay virtualDisplayForEncoder;
    private Intent notifServiceIntent;

    private ByteBuffer byteBuffer;

    private int width;
    private int height;

    private UnityInterface unityInterface;
    private MediaCodec mediaCodec;
    private MediaMuxer mediaMuxer;
    private int trackIndex;
    private boolean muxerStarted = false;
    private boolean isEncoding = false;
    private boolean requestEncoding = false;
    private String outputPath;
    private static final int TIMEOUT_USEC = 10000;

    private static class UnityInterface {
        private final String gameObjectName;

        private UnityInterface(String gameObjectName) {
            this.gameObjectName = gameObjectName;
        }

        private void Call(String functionName) {
            UnityPlayer.UnitySendMessage(gameObjectName, functionName, "");
        }

        public void OnCaptureStarted() {
            Call("OnCaptureStarted");
        }

        public void OnPermissionDenied() {
            Call("OnPermissionDenied");
        }

        public void OnCaptureStopped() {
            Call("OnCaptureStopped");
        }

        public void OnNewFrameAvailable() {
            Call("OnNewFrameAvailable");
        }

        public void OnEncodingComplete() {
            Call("OnEncodingComplete");
        }

        public void OnEncodingError() {
            Call("OnEncodingError");
        }
    }

    public DisplayCaptureManager() {
        receivers = new ArrayList<IDisplayCaptureReceiver>();
    }

    public static synchronized DisplayCaptureManager getInstance() {
        if (instance == null)
            instance = new DisplayCaptureManager();
        return instance;
    }

    public void onPermissionResponse(int resultCode, Intent intent) {
        if (resultCode != Activity.RESULT_OK) {
            unityInterface.OnPermissionDenied();
            Log.i(TAG, "Screen capture permission denied!");
            return;
        }
        if (staticMPIntentData == null && staticMPResultCode == 0) {
            staticMPIntentData = intent;
            staticMPResultCode = resultCode;
            startCapture(resultCode, intent);
        }
        else {
            startCapture(staticMPResultCode, staticMPIntentData);
        }
    }

    private void startCapture(int resultCode, Intent intent) {
        notifServiceIntent = new Intent(
            UnityPlayer.currentContext,
            DisplayCaptureNotificationService.class);
        UnityPlayer.currentContext.startService(notifServiceIntent);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Log.i(TAG, "Starting screen capture...");

            MediaProjectionManager projectionManager = (MediaProjectionManager)
                    UnityPlayer.currentContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = projectionManager.getMediaProjection(resultCode, intent);

            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.i(TAG, "Screen capture ended!");
                    handleScreenCaptureEnd();
                }
            }, new Handler(Looper.getMainLooper()));
            if (requestEncoding) {
                startEncoding();
            }
            else {
                // Create virtual display for Unity feedback
                virtualDisplayForUnity = projection.createVirtualDisplay("ScreenCaptureUnity",
                    width, height, 300,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, null);
                
                unityInterface.OnCaptureStarted();
            }

        }, 100);
    }

    @Override
    public void onImageAvailable(ImageReader imageReader) {
        Image image = imageReader.acquireLatestImage();
        if (image == null) return;

        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        buffer.rewind();

        byteBuffer.clear();
        byteBuffer.put(buffer);

        long timestamp = image.getTimestamp();
        image.close();

        for(int i = 0; i < receivers.size(); i++) {
            buffer.rewind();
            receivers.get(i).onNewImage(byteBuffer, width, height, timestamp);
        }

        unityInterface.OnNewFrameAvailable();
    }

    private void handleScreenCaptureEnd() {
        if (virtualDisplayForUnity != null) {
            virtualDisplayForUnity.release();
        }
        if (virtualDisplayForEncoder != null) {
            stopEncoding();
            //virtualDisplayForEncoder.release();
        }
        UnityPlayer.currentContext.stopService(notifServiceIntent);
        unityInterface.OnCaptureStopped();
    }

    // Called by Unity
    public void setup(String gameObjectName, int width, int height) {
        unityInterface = new UnityInterface(gameObjectName);
        this.width = width;
        this.height = height;

        // Calculate the exact buffer size required (4 bytes per pixel for RGBA_8888)
        int bufferSize = width * height * 4;

        // Allocate a direct ByteBuffer for better performance
        byteBuffer = ByteBuffer.allocateDirect(bufferSize);

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(this, new Handler(Looper.getMainLooper()));
    }
    public void setupVideoOutput(String fileName) {
        File outputDir = UnityPlayer.currentActivity.getExternalFilesDir(null);
        outputPath = new File(outputDir, fileName).getAbsolutePath();
        Log.i(TAG, "Video output path set to: " + outputPath);
    }

    public void requestEncoding() {
        if (isEncoding) {
            Log.i(TAG, "Already encoding!");
            return;
        }
        setupVideoOutput("mixedworld_reallightcapture_" + new java.text.SimpleDateFormat("yyyy-MM-dd-HH-mm-ss").format(new java.util.Date()) + ".mp4");
        requestEncoding = true;
        requestCapture();
    }

    public void startEncoding() {
        requestEncoding = false;
        if (isEncoding) {
            Log.i(TAG, "Already encoding!");
            return;
        }
        try {
            prepareMediaCodec(width, height);
            isEncoding = true;
            Log.i(TAG, "Started encoding");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start encoding: " + e.getMessage());
            unityInterface.Call("OnEncodingError");
        }
    }

    private void prepareMediaCodec(int screenWidth, int screenHeight) {
        try {
            mediaMuxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            trackIndex = -1;
            muxerStarted = false;

            MediaFormat mediaFormat = MediaFormat.createVideoFormat("video/avc", screenWidth, screenHeight);
            mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, 6000000);
            mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, 
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);

            mediaCodec = MediaCodec.createEncoderByType("video/avc");
            mediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            
            // Create input surface for encoder
            Surface encoderSurface = mediaCodec.createInputSurface();
            
            // Create second virtual display for encoding
            virtualDisplayForEncoder = projection.createVirtualDisplay(
                "ScreenCaptureEncoder",
                width, height, 
                300,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                encoderSurface, 
                null, 
                null);

            mediaCodec.setCallback(new MediaCodec.Callback() {
                @Override
                public void onInputBufferAvailable(MediaCodec codec, int index) {
                    // Not used with Surface input
                }

                @Override
                public void onOutputBufferAvailable(MediaCodec codec, int outputBufferId, MediaCodec.BufferInfo info) {
                    try {
                        ByteBuffer encodedData = codec.getOutputBuffer(outputBufferId);
                        if (encodedData == null) {
                            return;
                        }

                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            if (!muxerStarted) {
                                MediaFormat format = codec.getOutputFormat();
                                trackIndex = mediaMuxer.addTrack(format);
                                mediaMuxer.start();
                                muxerStarted = true;
                                Log.i(TAG, "MediaMuxer started");
                            }
                            info.size = 0;
                        }

                        if (info.size != 0 && muxerStarted) {
                            encodedData.position(info.offset);
                            encodedData.limit(info.offset + info.size);
                            mediaMuxer.writeSampleData(trackIndex, encodedData, info);
                            Log.d(TAG, "Writing sample data to muxer");
                        }

                        codec.releaseOutputBuffer(outputBufferId, false);

                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            Log.i(TAG, "Received EOS");
                            stopEncoding();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Exception in encoder output callback", e);
                        unityInterface.Call("OnEncodingError");
                    }
                }

                @Override
                public void onError(MediaCodec codec, MediaCodec.CodecException e) {
                    Log.e(TAG, "MediaCodec error: " + e.getMessage());
                    unityInterface.Call("OnEncodingError");
                }

                @Override
                public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
                    Log.i(TAG, "Encoder output format changed: " + format);
                }
            });

            mediaCodec.start();

        } catch (Exception e) {
            Log.e(TAG, "Error preparing MediaCodec: " + e.getMessage());
            unityInterface.Call("OnEncodingError");
        }
    }

    public void stopEncoding() {
        requestEncoding = false;
        if (!isEncoding) {
            Log.i(TAG, "Encoder already stopped");
            return;
        }

        try {
            Log.i(TAG, "Beginning encoder shutdown sequence");
            isEncoding = false;
            
            if (virtualDisplayForEncoder != null) {
                virtualDisplayForEncoder.release();
                virtualDisplayForEncoder = null;
            }

            if (mediaCodec != null) {
                try {
                    mediaCodec.signalEndOfInputStream();
                    mediaCodec.stop();
                    mediaCodec.release();
                    mediaCodec = null;
                } catch (Exception e) {
                    Log.e(TAG, "Error during mediaCodec shutdown: " + e.getMessage(), e);
                }
            }

            if (mediaMuxer != null) {
                if (muxerStarted) {
                    try {
                        mediaMuxer.stop();
                    } catch (Exception e) {
                        Log.e(TAG, "Error stopping mediaMuxer: " + e.getMessage(), e);
                    }
                }
                try {
                    mediaMuxer.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing mediaMuxer: " + e.getMessage(), e);
                }
                mediaMuxer = null;
                muxerStarted = false;
            }

            Log.i(TAG, "Encoder shutdown complete");
            unityInterface.Call("OnEncodingComplete");
        } catch (Exception e) {
            Log.e(TAG, "Error during encoder shutdown: ", e);
            unityInterface.Call("OnEncodingError");
        }
    }

    public void requestCapture() {
        Log.i(TAG, "Asking for screen capture permission...");
        if (staticMPIntentData == null) {
        Intent intent = new Intent(
                UnityPlayer.currentActivity,
                DisplayCaptureRequestActivity.class);
        UnityPlayer.currentActivity.startActivity(intent);
        }
        else {
            startCapture(staticMPResultCode, staticMPIntentData);
        }
    }

    public void stopCapture() {

        Log.i(TAG, "Stopping screen capture...");
        if(projection == null) return;
        projection.stop();
    }

    public ByteBuffer getByteBuffer() {
        return byteBuffer;
    }
}