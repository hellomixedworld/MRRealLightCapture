package com.trev3d.DisplayCapture;

import static android.content.ContentValues.TAG;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.ContentValues;
import android.content.ContentResolver;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
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
import java.util.ArrayList;

import com.unity3d.player.UnityPlayer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.io.File;
import java.io.FileDescriptor;

import android.provider.MediaStore;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLES11Ext;  // For GL_TEXTURE_EXTERNAL_OES

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

    private SurfaceTexture inputSurfaceTexture;
    private Surface inputSurface;
    private Surface encoderSurface;
    private EGLDisplay eglDisplay;
    private EGLContext eglContext;
    private EGLSurface eglSurface;
    private int textureId;

    private UnityInterface unityInterface;
    private MediaCodec mediaCodec;
    private MediaMuxer mediaMuxer;
    private int trackIndex;
    private boolean muxerStarted = false;
    private boolean isEncoding = false;
    private boolean isCapturing = false;
    private boolean requestEncoding = false;
    private String outputPath;
    private String oculusVideoPath = "/storage/emulated/0/Movies/";
    private static final int TIMEOUT_USEC = 10000;

    private static class UnityInterface {
        private final String gameObjectName;

        private UnityInterface(String gameObjectName) {
            this.gameObjectName = gameObjectName;
        }

        private void Call(String functionName) {
            UnityPlayer.UnitySendMessage(gameObjectName, functionName, "");
        }

        private void Call(String functionName, String message) {
            UnityPlayer.UnitySendMessage(gameObjectName, functionName, message);
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

        public void OnLogText(String message) {
            Call("OnLogText", message);
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
                isCapturing = true;
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
        init();
    }

    public void setupVideoOutput(String fileName) {
        // try {
        //     ContentValues values = new ContentValues();
        //     values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
        //     values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        //     //values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies");

        //     ContentResolver resolver = UnityPlayer.currentActivity.getContentResolver();
        //     Uri uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            
        //     if (uri != null) {
        //         outputPath = getPathFromUri(UnityPlayer.currentActivity, uri);
        //         Log.i(TAG, "Video output path set to: " + outputPath);
        //     } else {
        //         // Fallback to app-specific directory
        //         File outputDir = UnityPlayer.currentActivity.getExternalFilesDir(null);
        //         outputPath = new File(outputDir, fileName).getAbsolutePath();
        //         Log.w(TAG, "Falling back to app directory: " + outputPath);
        //     }
        // } catch (Exception e) {
        //     Log.e(TAG, "Error setting up video output, falling back to default." + e);
        // }
        // Check if we have write permission for external storage
        if (UnityPlayer.currentActivity.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) 
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            outputPath = oculusVideoPath + fileName;
        }
        else
        {
            // Fallback to app-specific directory
            File outputDir = UnityPlayer.currentActivity.getExternalFilesDir(null);
            outputPath = new File(outputDir, fileName).getAbsolutePath();
        }

    }

    public String getOutputPath() {
        return outputPath;
    }
    private String getPathFromUri(Context context, Uri uri) {
        try {
            ParcelFileDescriptor parcelFileDescriptor = context.getContentResolver().openFileDescriptor(uri, "w");
            if (parcelFileDescriptor != null) {
                FileDescriptor fileDescriptor = parcelFileDescriptor.getFileDescriptor();
                String path = "/proc/self/fd/" + fileDescriptor.toString();
                parcelFileDescriptor.close();
                return path;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting path from URI: " + e.getMessage());
        }
        return null;
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
            unityInterface.OnCaptureStarted();
            unityInterface.OnLogText("Started encoding");
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
            encoderSurface = mediaCodec.createInputSurface();
            
            // Initialize EGL
            initializeEGL();
            
            // Create input surface texture using the texture ID created in initializeEGL
            inputSurfaceTexture = new SurfaceTexture(textureId);
            inputSurfaceTexture.setDefaultBufferSize(width, height);
            inputSurface = new Surface(inputSurfaceTexture);
            
            // Reinitialize ImageReader for Unity feedback
            if (reader != null) {
                reader.close();
            }
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(this, new Handler(Looper.getMainLooper()));
            
            // Set up frame listener
            inputSurfaceTexture.setOnFrameAvailableListener(texture -> {
                try {
                    copyFrame();
                } catch (Exception e) {
                    Log.e(TAG, "Error processing frame", e);
                }
            });

            // Create virtual display that writes to input surface
            virtualDisplayForEncoder = projection.createVirtualDisplay(
                "ScreenCaptureEncoder",
                width, height, 
                300,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,  // Use input surface instead of encoder surface
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
            cleanupEGL();
            cleanup();
            Log.i(TAG, "Encoder shutdown complete");
            unityInterface.Call("OnEncodingComplete");
        } catch (Exception e) {
            Log.e(TAG, "Error during encoder shutdown: ", e);
            unityInterface.Call("OnEncodingError");
        }
    }

    public void requestCapture() {
        if (isEncoding || isCapturing) {
            Log.i(TAG, "Already capturing...");
            return;
        }
        if (reader == null || byteBuffer == null){
            init();
        }
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
        if (isEncoding){
            stopEncoding();
            return;
        }
        if (isCapturing){
            isCapturing = false;
            try {
                Log.i(TAG, "Stopping screen capture...");
                cleanup();
                if(projection == null) return;
                projection.stop();
            } catch (Exception e) {
                Log.e(TAG, "Error during capture shutdown: ", e);
            }
        }
    }

    public ByteBuffer getByteBuffer() {
        return byteBuffer;
    }

    private void initializeEGL() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1);
        
        int[] attribList = {
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        };
        
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0);
        
        int[] contextAttribs = {
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        };
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
        
        // Create surface for encoder
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encoderSurface, new int[]{EGL14.EGL_NONE}, 0);
        
        // Make current
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw new RuntimeException("eglMakeCurrent failed");
        }
        
        // Create and setup texture for external OES
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        textureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        
        // Initialize shaders and buffers
        initializeShaders();
    }

    private void copyFrame() {
        // Make sure we're using our EGL context
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
        
        // Update texture with new frame
        inputSurfaceTexture.updateTexImage();
        
        // Clear the surface
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        
        // Draw the texture to the encoder surface
        drawTexture();
        byteBuffer.clear();
        // Read pixels into the ImageReader buffer
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, byteBuffer);

        long timestamp = System.nanoTime();

        for(int i = 0; i < receivers.size(); i++) {
            byteBuffer.rewind();
            receivers.get(i).onNewImage(byteBuffer, width, height, timestamp);
        }

        unityInterface.OnNewFrameAvailable();

        // Swap buffers
        EGL14.eglSwapBuffers(eglDisplay, eglSurface);
    }

    private String vertexShader;
    private String fragmentShader;
    private int shaderProgram;
    private FloatBuffer fullQuadCoordsBuffer;
    private FloatBuffer fullQuadTexCoordsBuffer;

    private void initializeShaders() {
        vertexShader =
            "attribute vec4 position;\n" +
            "attribute vec2 texcoord;\n" +
            "varying vec2 v_texcoord;\n" +
            "void main() {\n" +
            "    gl_Position = position;\n" +
            "    // Flip vertically by inverting y-coordinates\n" +
            "    v_texcoord = vec2(texcoord.x, 1.0 - texcoord.y);\n" +
            "}\n";

        fragmentShader =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES texture;\n" +
            "varying vec2 v_texcoord;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(texture, v_texcoord);\n" +
            "}\n";

        shaderProgram = createProgram(vertexShader, fragmentShader);

        // Full screen quad coordinates
        float[] FULL_QUAD_COORDS = {
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f,
        };

        float[] FULL_QUAD_TEXCOORDS = {
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f,
        };

        fullQuadCoordsBuffer = ByteBuffer.allocateDirect(FULL_QUAD_COORDS.length * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(FULL_QUAD_COORDS);
        fullQuadCoordsBuffer.position(0);

        fullQuadTexCoordsBuffer = ByteBuffer.allocateDirect(FULL_QUAD_TEXCOORDS.length * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(FULL_QUAD_TEXCOORDS);
        fullQuadTexCoordsBuffer.position(0);
    }

    private void drawTexture() {
        // Use the shader program
        GLES20.glUseProgram(shaderProgram);

        int posLocation = GLES20.glGetAttribLocation(shaderProgram, "position");
        int texLocation = GLES20.glGetAttribLocation(shaderProgram, "texcoord");
        
        GLES20.glVertexAttribPointer(posLocation, 2, GLES20.GL_FLOAT, false, 0, fullQuadCoordsBuffer);
        GLES20.glVertexAttribPointer(texLocation, 2, GLES20.GL_FLOAT, false, 0, fullQuadTexCoordsBuffer);

        GLES20.glEnableVertexAttribArray(posLocation);
        GLES20.glEnableVertexAttribArray(texLocation);
        
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    // Helper method to create shader program
    private int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);
        return program;
    }

    private int loadShader(int type, String shaderCode) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);
        return shader;
    }
    private void cleanupEGL() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
            }
            EGL14.eglTerminate(eglDisplay);
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglSurface = EGL14.EGL_NO_SURFACE;
    }

    private void init() {
        
        // Calculate the exact buffer size required (4 bytes per pixel for RGBA_8888)
        int bufferSize = width * height * 4;
        if (byteBuffer == null) {
            // Allocate a direct ByteBuffer for better performance
            byteBuffer = ByteBuffer.allocateDirect(bufferSize);
        }
        if (reader == null) {
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(this, new Handler(Looper.getMainLooper()));
        }
    }

    private void cleanup() {
        if (byteBuffer != null) {
            byteBuffer.clear();
            byteBuffer = null;
        }
        if (reader != null) {
            reader.close();  // Close the ImageReader
            reader = null;
        }
    }
}