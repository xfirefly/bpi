package com.example.bpi;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
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
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SparseArray;
import android.view.Display;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class CameraUtil {

    Context context;

    AutoFitTextureView previewView = null;
    Size previewSize;
    int WIDTH = 1920;
    int HEIGHT = 1080;
    Surface surfaceBuffer = null;

    String cameraId;
    CameraDevice mCamera;
    CameraCaptureSession mSession;
    CaptureRequest.Builder mPreviewRequestBuilder;
    private CaptureRequest previewRequest;
    private final Semaphore cameraOpenCloseLock = new Semaphore(1);

    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;

    private ImageReader previewReader;

    private Range<Integer>[] fpsRanges;

    public CameraUtil(Context context, @Nullable Surface surfaceBuffer, @Nullable AutoFitTextureView preview){
        this.previewView = preview;
        this.surfaceBuffer = surfaceBuffer;
        this.context = context;

        startBackgroundThread();

        if(previewView == null)
            openCamera(1920, 1080);

        if (previewView.isAvailable()) {
            openCamera(previewView.getWidth(), previewView.getHeight());
        } else {
            previewView.setSurfaceTextureListener(surfaceTextureListener);
        }
    }

    //监听TextView的状态。准备好之后openCamera
    private final TextureView.SurfaceTextureListener surfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(
                        final SurfaceTexture texture, final int width, final int height) {
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
            Log.d("camera2","Cannot configure camera device");
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
                    //Log.d("camera2", "Capture number" +  result.getFrameNumber());
                }
            };

    private void setUpCameraOutputs(final int width, final int height) {
        final CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
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

                //Size yuv[] = map.getOutputSizes(ImageFormat.YUV_420_888);

                fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                Log.d("camera2", "camera's FPS " + fpsRanges[0]);

                if (map == null) {
                    continue;
                }

                // For still image captures, we use the largest available size.
/*                final Size largest =
                        Collections.max(
                                Arrays.asList(map.getOutputSizes(ImageFormat.YUV_420_888)),
                                new CompareSizesByArea());*/

                // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
                // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
                // garbage capture data.
//                previewSize =
//                        chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), width, height, largest);

                previewSize = new Size(WIDTH, HEIGHT);

                // We fit the aspect ratio of TextureView to the size of preview we picked.
                final int orientation = context.getResources().getConfiguration().orientation;
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
    private void openCamera(final int width, final int height) {

        if (ContextCompat.checkSelfPermission(this.context, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        setUpCameraOutputs(width, height);
        configureTransform(width, height);
        final CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            if (ActivityCompat.checkSelfPermission(this.context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
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


    private void configureTransform(final int viewWidth, final int viewHeight) {
        if (null == previewView || null == previewSize) {
            return;
        }
        WindowManager window = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
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
    private void createCameraPreviewSession() {
        try {
            // We set up a CaptureRequest.Builder with the output Surface.
            mPreviewRequestBuilder = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

            List<Surface> output = new ArrayList<>(previewView == null ? 1 : 2);

            if(surfaceBuffer != null)
            {
                output.add(surfaceBuffer);
                mPreviewRequestBuilder.addTarget(surfaceBuffer);
            }

            final SurfaceTexture texture = previewView.getSurfaceTexture();
            assert texture != null;

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());

            // This is the output Surface we need to start preview.
            final Surface surface = new Surface(texture);


            if (surface != null && surface.isValid()) {
                output.add(surface);
                mPreviewRequestBuilder.addTarget(surface);
            }

            //喂给encode
            //mPreviewRequestBuilder.addTarget(surfaceBuffer);

            //Timber.tag(TAG).i("Opening camera preview: " + previewSize.getWidth() + "x" + previewSize.getHeight());
            Log.d("camera2", "Opening camera preview: " + previewSize.getWidth() + "x" + previewSize.getHeight());

            //需要对每一帧做处理的话
/*            previewReader = ImageReader.newInstance(
                    1920, 1080, ImageFormat.YUV_420_888, 2
            );
            previewReader.setOnImageAvailableListener(mOnGetPreviewListener, mBackgroundHandler);
            mPreviewRequestBuilder.addTarget(previewReader.getSurface());*/

            // Here, we create a CameraCaptureSession for camera preview.
            mCamera.createCaptureSession(
                    output,
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
                                Log.d("camera2", "Exception!" + e.getMessage());
                            }
                        }

                        @Override
                        public void onConfigureFailed(final CameraCaptureSession cameraCaptureSession) {
                            Log.d("camera2", "failed");
                        }
                    },
                    null);
        } catch (final CameraAccessException e) {
            //Timber.tag(TAG).e("Exception!", e);
            Log.d("camera2", "Exception! " + e.getMessage());
        }
    }

    //相机线程
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

    public void release(){
        if (mSession != null) {
            mSession.close();
            stopBackgroundThread();
        }
    }

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
}
