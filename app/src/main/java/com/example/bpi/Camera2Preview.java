package com.example.bpi;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SparseArray;
import android.view.Display;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class Camera2Preview extends AppCompatActivity {

    static{
        System.loadLibrary("native-yuv-to-buffer-lib");
    }

    //编码参数定义
    private static final String MIME_TYPE = "video/avc";    // H.264 Advanced Video Coding
    private static final int BIT_RATE = 2500000;            // 2Mbps
    private static final int FRAME_RATE = 60;               // 15fps
    private static final int IFRAME_INTERVAL = 10;          // 10 seconds between I-frames
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    Queue<FrameData> mQueue = new LinkedList<FrameData>();

    MediaCodec mEncoder;
    MediaFormat mEncoderOutputVideoFormat;
    MediaMuxer mMuxer;
    private boolean mMuxing;
    private int mOutputVideoTrack;
    private LinkedList<Integer> mPendingVideoEncoderOutputBufferIndices;
    private LinkedList<MediaCodec.BufferInfo> mPendingVideoEncoderOutputBufferInfos;

    String mOutputFile;
    String mOutPutPicture;

    //相机变量定义
    AutoFitTextureView previewView;
    Size previewSize;

    String cameraId;
    CameraDevice mCamera;
    CameraCaptureSession mSession;
    CaptureRequest.Builder mPreviewRequestBuilder;
    private CaptureRequest previewRequest;

    private final Semaphore cameraOpenCloseLock = new Semaphore(1);
    private static final int MINIMUM_PREVIEW_SIZE = 320;

    private Range<Integer>[] fpsRanges;
    //监听摄像头状态
    CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            cameraOpenCloseLock.release();
            mCamera = camera;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            showToast("Cannot configure camera device");
            return;
        }
    };

    //监听capture状态
    private final CameraCaptureSession.CaptureCallback captureCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override

                public void onCaptureProgressed(
                        final CameraCaptureSession session,
                        final CaptureRequest request,
                        final CaptureResult partialResult) {
                }

                @Override
                public void onCaptureCompleted(
                        final CameraCaptureSession session,
                        final CaptureRequest request,
                        final TotalCaptureResult result) {
                    Log.d("camera2", "Capture number" +  result.getFrameNumber());
                }
            };

    //用来得到每一帧的对象
    private ImageReader previewReader;
    private int mVideoEncodedFrameCount;
    private boolean mVideoEncoderDone;

    //线程1用来数据的抓取和处理
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;

    //线程2用来编码
    private HandlerThread mBackgroundThread1;
    private Handler mBackgroundHandler1;

    Surface inputSurface;

    private void startBackgroundThread1() {
        mBackgroundThread1 = new HandlerThread("EncoderThread");
        mBackgroundThread1.start();
        mBackgroundHandler1 = new Handler(mBackgroundThread1.getLooper());
    }

    private void stopBackgroundThread1() {
        mBackgroundThread1.quitSafely();
        try {
            mBackgroundThread1.join();
            mBackgroundThread1 = null;
            mBackgroundHandler1 = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.preview);   //加载活动对应的XML文件名

        findViewById(R.id.stop_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mEncoder != null) {
                    mEncoder.stop();
                    mEncoder.release();
                    stopBackgroundThread1();
                }

                if (mMuxing && mMuxer != null) {
                    mMuxer.stop();
                    mMuxer.release();
                }

                if (mSession != null) {
                    mSession.close();
                    stopBackgroundThread();
                }
            }
        });



        permission();

        previewView = findViewById(R.id.preview_view);
        startBackgroundThread();

        if (previewView.isAvailable()) {
            openCamera(previewView.getWidth(), previewView.getHeight());
        } else {
            previewView.setSurfaceTextureListener(surfaceTextureListener);
        }
        //获得支持的编码格式
        HashMap<String, MediaCodecInfo.CodecCapabilities> mEncoderInfos = new HashMap<>();
        for(int i = MediaCodecList.getCodecCount() - 1; i >= 0; i--){
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
            if(codecInfo.isEncoder()){
                for(String t : codecInfo.getSupportedTypes()){
                    try{
                        mEncoderInfos.put(t, codecInfo.getCapabilitiesForType(t));
                        //从每个格式的编码器中的实例得到
                        //MediaCodecInfo.VideoCapabilities video = codecInfo.getCapabilitiesForType(t).getVideoCapabilities();
                    } catch(IllegalArgumentException e){
                        e.printStackTrace();
                    }
                }
            }
        }

        //编码操作
        startBackgroundThread1();       //创建线程
        MediaFormat format = createMediaFormat();
        MediaCodecInfo info = selectCodec(MIME_TYPE);

        mOutputFile = new File(Environment.getExternalStorageDirectory()
                + File.separator + "1280*720-rate.mp4").getAbsolutePath();

        mPendingVideoEncoderOutputBufferIndices = new LinkedList<>();
        mPendingVideoEncoderOutputBufferInfos = new LinkedList<>();

        try {
            mMuxer = new MediaMuxer(mOutputFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            setupMuxer();

        } catch (IOException e) {
            e.printStackTrace();
            finish();
        }

        try {
            mEncoder = MediaCodec.createByCodecName(info.getName());
        } catch (IOException e) {
            e.printStackTrace();
            finish();
        }

        mEncoder.setCallback(new MediaCodec.Callback() {   //将回调函放在线程1执行
            //计算帧率
            long timeDiff = 0;
            long startTime = 0;
            int framesRecevied = 0;
            @Override
            public void onInputBufferAvailable(MediaCodec codec, int index) {
                //"Empty" input buffers becomes available here
                //User should fill them with desired media data.
                ByteBuffer inputBuffer = codec.getInputBuffer(index);

                FrameData data = mQueue.poll();

                try {
                    if (data != null) {
                        if (inputBuffer != null) {
                            inputBuffer.clear();
                            inputBuffer.put(data.getBuffer());

                            codec.queueInputBuffer(index,
                                    0,
                                    data.getBuffer().length,
                                    data.getPresentationTimeUs(),
                                    0);
                        }
                    } else {
                        //EOS
                        codec.queueInputBuffer(index,
                                0,0, 0, 0);
                    }
                } catch (BufferOverflowException e) {
                    e.printStackTrace();
                    inputBuffer.clear();
                }
            }

            @Override
            public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
                //Encoded data are placed in output buffers
                //User will consume these buffers here.
                timeDiff = System.currentTimeMillis() - startTime;
                if(timeDiff < 1000){
                    framesRecevied++;
                }else{
                    timeDiff = 0;
                    startTime = System.currentTimeMillis();
                    Log.d("encode","FPS " + framesRecevied);
                    framesRecevied = 0;

                    MediaFormat newFormat = codec.getOutputFormat();
                    int videoWidth = newFormat.getInteger("width");
                    int videoHeight = newFormat.getInteger("height");
/*                    int maximput = newFormat.getInteger("max-input-size");
                    int maxheight = newFormat.getInteger("max-height");*/
                    Log.d("encode","width " + videoWidth);
                    Log.d("encode", "height " + videoHeight);
                }
                //mEncoder.releaseOutputBuffer(index, false);

                muxVideo(index, info);

            }

            @Override
            public void onError(MediaCodec codec, MediaCodec.CodecException e) {

            }

            @Override
            public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
                mEncoderOutputVideoFormat = mEncoder.getOutputFormat();
                setupMuxer();
            }
        });

        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        //得到video/avc编码器的能力
        MediaCodecInfo.VideoCapabilities video = info.getCapabilitiesForType(MIME_TYPE).getVideoCapabilities();
        boolean ret = video.isSizeSupported(3840,2160);
        Range<Integer> widths = video.getSupportedHeights();
        Range<Integer> heights = video.getSupportedWidths();
        Range<Integer> bitrate = video.getBitrateRange();
        //Range<Integer> widthSupport = video.getSupportedHeightsFor(3840);
        //Range<Integer> heightSupport = video.getSupportedWidthsFor(2160);
        Range<Double> fps = video.getSupportedFrameRatesFor(1920,1080);
        Range<Double> fps1088 = video.getSupportedFrameRatesFor(1920, 1088);
        Range<Double> fps1440 = video.getSupportedFrameRatesFor(1440, 900);

        //返回支持的性能点
        //List<MediaCodecInfo.VideoCapabilities.PerformancePoint> per =
        ret = video.isSizeSupported(720,450);
        Range<Double> fps720 = video.getSupportedFrameRatesFor(720, 450);

        ret = video.isSizeSupported(1280, 720);
        Range<Double> fps1280 = video.getSupportedFrameRatesFor(1280, 720);

/*        MediaCodecInfo.EncoderCapabilities encoder = info.getCapabilitiesForType(MIME_TYPE).getEncoderCapabilities();
        Range<Integer> quality = encoder.getQualityRange();
        Range<Integer> complexity = encoder.getComplexityRange();*/
        //创建surface输入
        inputSurface = mEncoder.createPersistentInputSurface();
        mEncoder.setInputSurface(inputSurface);
        mEncoder.start();

    }


    /**
     * Creates a MediaFormat with the basic set of values.
     */
    private static MediaFormat createMediaFormat() {
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, 1280, 720);  //VIDEO_AVC

        int colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface;
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);

        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR );
        int bittate = 62500000/2;
        format.setInteger(MediaFormat.KEY_BIT_RATE, bittate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        return format;
    }
    /**
     * Returns the first codec capable of encoding the specified MIME type, or null if no
     * match was found.
     */
    private static MediaCodecInfo selectCodec(String mimeType) {
        int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
            if (!codecInfo.isEncoder()) {
                continue;
            }
            String[] types = codecInfo.getSupportedTypes();
            for (int j = 0; j < types.length; j++) {
                if (types[j].equalsIgnoreCase(mimeType)) {
                    return codecInfo;
                }
            }
        }
        return null;
    }

    private void setupMuxer() {
        if (!mMuxing && mEncoderOutputVideoFormat != null) {

            mOutputVideoTrack = mMuxer.addTrack(mEncoderOutputVideoFormat);

            mMuxer.start();
            mMuxing = true;

            MediaCodec.BufferInfo info;

            while ((info = mPendingVideoEncoderOutputBufferInfos.poll()) != null) {
                int index = mPendingVideoEncoderOutputBufferIndices.poll().intValue();
                muxVideo(index, info);
            }
        }
    }
    private void muxVideo(int index, MediaCodec.BufferInfo info) {
        if (!mMuxing) {
            mPendingVideoEncoderOutputBufferIndices.add(new Integer(index));
            mPendingVideoEncoderOutputBufferInfos.add(info);
            return;
        }

        ByteBuffer encoderOutputBuffer = mEncoder.getOutputBuffer(index);
        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            // Simply ignore codec config buffers.
            mEncoder.releaseOutputBuffer(index, false);
            return;
        }

        if (info.size != 0) {
            mMuxer.writeSampleData(
                    mOutputVideoTrack, encoderOutputBuffer, info);
        }
        mEncoder.releaseOutputBuffer(index, false);
        mVideoEncodedFrameCount++;
        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            synchronized (this) {
                mVideoEncoderDone = true;
                notifyAll();
            }
        }
    }

    //相机操作
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraThread");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void openCamera(final int width, final int height) {

        if (ContextCompat.checkSelfPermission(Camera2Preview.this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        setUpCameraOutputs(width, height);
        configureTransform(width, height);
        final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            if (ActivityCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                //Timber.tag(TAG).w("checkSelfPermission CAMERA");
                Log.d("hehe", "checkSelfPermission CAMERA");
            }
            manager.openCamera(cameraId, stateCallback, mBackgroundHandler);
            //Timber.tag(TAG).d("open Camera");
            Log.d("hehe", "open Camera");
        } catch (final CameraAccessException e) {
            //Timber.tag(TAG).e("Exception!", e);
            Log.d("hehe", "Exception!" + e.getMessage());
        } catch (final InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }

    private void setUpCameraOutputs(final int width, final int height) {
        final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            SparseArray<Integer> cameraFaceTypeMap = new SparseArray<>();
            // Check the facing types of camera devices
            for (final String cameraId : manager.getCameraIdList()) {
                final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    if (cameraFaceTypeMap.get(CameraCharacteristics.LENS_FACING_FRONT) != null) {
                        cameraFaceTypeMap.append(CameraCharacteristics.LENS_FACING_FRONT, cameraFaceTypeMap.get(CameraCharacteristics.LENS_FACING_FRONT) + 1);
                    } else {
                        cameraFaceTypeMap.append(CameraCharacteristics.LENS_FACING_FRONT, 1);
                    }
                }

                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    if (cameraFaceTypeMap.get(CameraCharacteristics.LENS_FACING_FRONT) != null) {
                        cameraFaceTypeMap.append(CameraCharacteristics.LENS_FACING_BACK, cameraFaceTypeMap.get(CameraCharacteristics.LENS_FACING_BACK) + 1);
                    } else {
                        cameraFaceTypeMap.append(CameraCharacteristics.LENS_FACING_BACK, 1);
                    }
                }
            }

            Integer num_facing_back_camera = cameraFaceTypeMap.get(CameraCharacteristics.LENS_FACING_BACK);
            for (final String cameraId : manager.getCameraIdList()) {
                final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                // If facing back camera or facing external camera exist, we won't use facing front camera
                if (num_facing_back_camera != null && num_facing_back_camera > 0) {
                    // We don't use a front facing camera in this sample if there are other camera device facing types
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        continue;
                    }
                }

                final StreamConfigurationMap map =
                        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                Size yuv[] = map.getOutputSizes(ImageFormat.YUV_420_888);

                fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                Log.d("camera2", "camera's FPS " + fpsRanges[0]);

                if (map == null) {
                    continue;
                }

                // For still image captures, we use the largest available size.
                final Size largest =
                        Collections.max(
                                Arrays.asList(map.getOutputSizes(ImageFormat.YUV_420_888)),
                                new CompareSizesByArea());

                // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
                // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
                // garbage capture data.
//                previewSize =
//                        chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), width, height, largest);

                previewSize = new Size(WIDTH, HEIGHT);

                // We fit the aspect ratio of TextureView to the size of preview we picked.
                // We fit the aspect ratio of TextureView to the size of preview we picked.
                final int orientation = getResources().getConfiguration().orientation;
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    previewView.setAspectRatio(previewSize.getWidth(), previewSize.getHeight());
                } else {
                    previewView.setAspectRatio(previewSize.getHeight(), previewSize.getWidth());
                }

                this.cameraId = cameraId;
                return;
            }
        } catch (final CameraAccessException e) {
            //Timber.tag(TAG).e("Exception!", e);
            Log.d("hehe", "CameraAccessException");
        } catch (final NullPointerException e) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            //ErrorDialog.newInstance(getString(R.string.camera_error)).show(getChildFragmentManager(), FRAGMENT_DIALOG);
            Log.d("hehe", "NullPointerException");
        }
    }

    /**
     * Configures the necessary {@link Matrix} transformation to `mTextureView`.
     * This method should be called after the camera preview size is determined in
     * setUpCameraOutputs and also the size of `mTextureView` is fixed.
     *
     * @param viewWidth  The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private void configureTransform(final int viewWidth, final int viewHeight) {
        if (null == previewView || null == previewSize) {
            return;
        }

        WindowManager window = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Display display = window.getDefaultDisplay();
        final int rotation = display.getRotation();
        final Matrix matrix = new Matrix();
        final RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        final RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        final float centerX = viewRect.centerX();
        final float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            final float scale =
                    Math.max(
                            (float) viewHeight / previewSize.getHeight(),
                            (float) viewWidth / previewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        previewView.setTransform(matrix);
    }

    /**
     * Creates a new {@link CameraCaptureSession} for camera preview.
     */
    @SuppressLint("LongLogTag")
    private void createCameraPreviewSession() {
        try {
            final SurfaceTexture texture = previewView.getSurfaceTexture();
            assert texture != null;

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());

            // This is the output Surface we need to start preview.
            final Surface surface = new Surface(texture);

            // We set up a CaptureRequest.Builder with the output Surface.
            mPreviewRequestBuilder = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(surface);

            //喂给encode
            mPreviewRequestBuilder.addTarget(inputSurface);

            //Timber.tag(TAG).i("Opening camera preview: " + previewSize.getWidth() + "x" + previewSize.getHeight());
            Log.d("hehe", "Opening camera preview: " + previewSize.getWidth() + "x" + previewSize.getHeight());

            previewReader = ImageReader.newInstance(
                    1600, 1200, ImageFormat.YUV_420_888, 2
            );
            int sizeRange = previewReader.getWidth();
            //Size yuv[] = map.getOutputSizes(ImageFormat.YUV_420_888);
            previewReader.setOnImageAvailableListener(mOnGetPreviewListener, mBackgroundHandler);
            //mPreviewRequestBuilder.addTarget(previewReader.getSurface());

            // Here, we create a CameraCaptureSession for camera preview.
            mCamera.createCaptureSession(
                    Arrays.asList(surface,
                            inputSurface),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(final CameraCaptureSession cameraCaptureSession) {
                            // The camera is already closed
                            if (null == mCamera) {
                                return;
                            }

                            // When the session is ready, we start displaying the preview.
                            mSession = cameraCaptureSession;
                            try {
                                // Auto focus should be continuous for camera preview.
                                mPreviewRequestBuilder.set(
                                        CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                // Flash is automatically enabled when necessary.
                                mPreviewRequestBuilder.set(
                                        CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);

                                // Finally, we start displaying the camera preview.
                                previewRequest = mPreviewRequestBuilder.build();
                                mSession.setRepeatingRequest(
                                        previewRequest, captureCallback, mBackgroundHandler);
                            } catch (final CameraAccessException e) {
                                //Timber.tag(TAG).e("Exception!", e);
                                Log.d("hehe", "Exception!" + e.getMessage());
                            }
                        }

                        @Override
                        public void onConfigureFailed(final CameraCaptureSession cameraCaptureSession) {
                            showToast("Failed");
                        }
                    },
                    null);
        } catch (final CameraAccessException e) {
            //Timber.tag(TAG).e("Exception!", e);
            Log.d("hehe", "Exception! " + e.getMessage());
        }

        //mOnGetPreviewListener.initialize(getApplicationContext(), getAssets(), mScoreView, inferenceHandler);
    }

    //计算帧率
    long PtimeDiff = 0;
    long PstartTime = 0;
    int PframesRecevied = 0;

    private final ImageReader.OnImageAvailableListener mOnGetPreviewListener
            = new ImageReader.OnImageAvailableListener() {

        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = reader.acquireLatestImage();
            if (image == null)
                return;
            int width = image.getWidth();
            int height = image.getHeight();
            //Log.d("produce:", "Frame");
            //image.setCropRect(new Rect(0, 0, 1920, 1080));

/*            final Image.Plane[] planes = image.getPlanes();
            Image.Plane yPlane = planes[0];
            Image.Plane uPlane = planes[1];
            Image.Plane vPlane = planes[2];*/

            //byte[] mBuffer =getDataFromImage(image, COLOR_FormatI420);
/*            byte[] mBuffer = toNV12(yPlane.getBuffer(),
                    uPlane.getBuffer(),
                    vPlane.getBuffer(),
                    1920,
                    1088);*/

/*            mOutPutPicture = new File(Environment.getExternalStorageDirectory()
                    + File.separator + "save").getAbsolutePath();
            dumpFile(mOutPutPicture, mBuffer);*/
/*            byte[] mBuffer = yuvToBuffer(yPlane.getBuffer(),
                    uPlane.getBuffer(),
                    vPlane.getBuffer(),
                    yPlane.getPixelStride(),
                    yPlane.getRowStride(),
                    uPlane.getPixelStride(),
                    uPlane.getRowStride(),
                    vPlane.getPixelStride(),
                    vPlane.getRowStride(),
                    image.getWidth(),
                    image.getHeight());*/
            //mediacodec want microsecond, image.gettimestamp give nanosecond
            //mQueue.add(new FrameData(mBuffer, image.getTimestamp() / 1000, false));
            image.close();
            PtimeDiff = System.currentTimeMillis() - PstartTime;
            if(PtimeDiff < 1000){
                PframesRecevied++;
            }else{
                PtimeDiff = 0;
                PstartTime = System.currentTimeMillis();
                Log.d("camera2","FPS " + PframesRecevied);
                PframesRecevied = 0;
            }
        }
    };

/*    public void decodeToBitMap(byte[] data) {

        try {
            YuvImage image = new YuvImage(data, ImageFormat.NV21, 1920,
                    1080, null);
            if (image != null) {
                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                image.compressToJpeg(new Rect(0, 0, size.width, size.height),
                        80, stream);
                Bitmap bmp = BitmapFactory.decodeByteArray(
                        stream.toByteArray(), 0, stream.size());
                stream.close();
            }
        } catch (Exception ex) {
            Log.e("Sys", "Error:" + ex.getMessage());
        }
    }*/


    private static final int COLOR_FormatI420 = 1;
    private static final int COLOR_FormatNV21 = 2;
    private static boolean isImageFormatSupported(Image image) {
        int format = image.getFormat();
        switch (format) {
            case ImageFormat.YUV_420_888:
            case ImageFormat.NV21:
            case ImageFormat.YV12:
                return true;
        }
        return false;
    }
    private static byte[] getDataFromImage(Image image, int colorFormat) {
        if (colorFormat != COLOR_FormatI420 && colorFormat != COLOR_FormatNV21) {
            throw new IllegalArgumentException("only support COLOR_FormatI420 " + "and COLOR_FormatNV21");
        }
        if (!isImageFormatSupported(image)) {
            throw new RuntimeException("can't convert Image to byte array, format " + image.getFormat());
        }
        Rect crop = image.getCropRect();
        int format = image.getFormat();
        int width = crop.width();
        int height = crop.height();
        Image.Plane[] planes = image.getPlanes();
        byte[] data = new byte[width * height * ImageFormat.getBitsPerPixel(format) / 8];
        byte[] rowData = new byte[planes[0].getRowStride()];

        int channelOffset = 0;   //写入byte[]中的初始偏移量
        int outputStride = 1;    //写入数据的间隔，步长
        for (int i = 0; i < planes.length; i++) {
            switch (i) {
                case 0:
                    channelOffset = 0;
                    outputStride = 1;
                    break;
                case 1:
                    if (colorFormat == COLOR_FormatI420) {
                        channelOffset = width * height;
                        outputStride = 1;
                    } else if (colorFormat == COLOR_FormatNV21) {
                        channelOffset = width * height + 1;
                        outputStride = 2;
                    }
                    break;
                case 2:
                    if (colorFormat == COLOR_FormatI420) {
                        channelOffset = (int) (width * height * 1.25);
                        outputStride = 1;
                    } else if (colorFormat == COLOR_FormatNV21) {
                        channelOffset = width * height;
                        outputStride = 2;
                    }
                    break;
            }
            ByteBuffer buffer = planes[i].getBuffer();
            int rowStride = planes[i].getRowStride();
            int pixelStride = planes[i].getPixelStride();

            Log.v("image", "pixelStride " + pixelStride);
            Log.v("image", "rowStride " + rowStride);
            Log.v("image", "width " + width);
            Log.v("image", "height " + height);
            Log.v("image", "buffer size " + buffer.remaining());

            int shift = (i == 0) ? 0 : 1;
            int w = width >> shift;
            int h = height >> shift;
            buffer.position(rowStride * (crop.top >> shift) + pixelStride * (crop.left >> shift));
            for (int row = 0; row < h; row++) {
                int length;
                if (pixelStride == 1 && outputStride == 1) {
                    length = w;
                    buffer.get(data, channelOffset, length);
                    channelOffset += length;
                } else {
                    length = (w - 1) * pixelStride + 1;
                    buffer.get(rowData, 0, length);
                    for (int col = 0; col < w; col++) {
                        data[channelOffset] = rowData[col * pixelStride];
                        channelOffset += outputStride;
                    }
                }
/*                if (row < h - 1) {
                    buffer.position(buffer.position() + rowStride - length);
                }*/
            }
             Log.v("image", "Finished reading data from plane " + i);
        }
        return data;
    }

    private static void dumpFile(String fileName, byte[] data) {
        FileOutputStream outStream;
        try {
            outStream = new FileOutputStream(fileName);
        } catch (IOException ioe) {
            throw new RuntimeException("Unable to create output file " + fileName, ioe);
        }
        try {
            outStream.write(data);
            outStream.close();
        } catch (IOException ioe) {
            throw new RuntimeException("failed writing data to file " + fileName, ioe);
        }
    }
    /**
     * {@link android.view.TextureView.SurfaceTextureListener} handles several lifecycle events on a
     * {@link TextureView}.
     */
    private Surface mEncodeSurface;
    private final TextureView.SurfaceTextureListener surfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(
                        final SurfaceTexture texture, final int width, final int height) {
                    mEncodeSurface = new Surface(texture);
                    openCamera(width, height);
                }

                @Override
                public void onSurfaceTextureSizeChanged(
                        final SurfaceTexture texture, final int width, final int height) {
                    configureTransform(width, height);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(final SurfaceTexture texture) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(final SurfaceTexture texture) {
                }
            };

    private static Size chooseOptimalSize(
            final Size[] choices, final int width, final int height, final Size aspectRatio) {
        // Collect the supported resolutions that are at least as big as the preview Surface
        final List<Size> bigEnough = new ArrayList<Size>();
        for (final Size option : choices) {
            if (option.getHeight() >= MINIMUM_PREVIEW_SIZE && option.getWidth() >= MINIMUM_PREVIEW_SIZE) {
                //Timber.tag(TAG).i("Adding size: " + option.getWidth() + "x" + option.getHeight());
                Log.d("hehe", "Adding size: " + option.getWidth() + "x" + option.getHeight());
                bigEnough.add(option);
            } else {
                //Timber.tag(TAG).i("Not adding size: " + option.getWidth() + "x" + option.getHeight());
                Log.d("hehe", "Not adding size: " + option.getWidth() + "x" + option.getHeight());
            }
        }

        // Pick the smallest of those, assuming we found any
        if (bigEnough.size() > 0) {
            final Size chosenSize = Collections.min(bigEnough, new CompareSizesByArea());
            //Timber.tag(TAG).i("Chosen size: " + chosenSize.getWidth() + "x" + chosenSize.getHeight());
            Log.d("hehe", "Chosen size: " + chosenSize.getWidth() + "x" + chosenSize.getHeight());
            return chosenSize;
        } else {
            //Timber.tag(TAG).e("Couldn't find any suitable preview size");
            Log.d("hehe", "Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    /**
     * Compares two {@code Size}s based on their areas.
     */
    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(final Size lhs, final Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum(
                    (long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    private void showToast(final String text) {
        new Handler(getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), text, Toast.LENGTH_SHORT).show();
            }
        });
    }

    public native byte[] yuvToBuffer(ByteBuffer y, ByteBuffer u, ByteBuffer v, int yPixelStride, int yRowStride,
                                     int uPixelStride, int uRowStride, int vPixelStride, int vRowStride, int imgWidth, int imgHeight);

    public native byte[] toNV12(ByteBuffer y, ByteBuffer u, ByteBuffer v,  int imgWidth, int imgHeight);
    //获取权限
    private void permission() {

        if (Build.VERSION.SDK_INT >= 23) {
            String[] mPermissionList = new String[]{
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.READ_LOGS,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.SET_DEBUG_APP,
                    Manifest.permission.SYSTEM_ALERT_WINDOW,
                    Manifest.permission.GET_ACCOUNTS,
                    Manifest.permission.WRITE_APN_SETTINGS,
                    Manifest.permission.CAMERA};
            ActivityCompat.requestPermissions(Camera2Preview.this, mPermissionList, 123);
           // ActivityCompat.requestPermissions(Camera2Preview.this, mPermissionList,  android.permission.WRITE_EXTERNAL_STORAGE);
        }
    }
}