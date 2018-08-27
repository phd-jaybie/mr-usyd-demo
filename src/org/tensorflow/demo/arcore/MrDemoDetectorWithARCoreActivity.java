package org.tensorflow.demo.arcore;

import android.Manifest;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.os.Trace;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.widget.SeekBar;
import android.widget.Toast;

import com.google.ar.core.Anchor;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.exceptions.NotYetAvailableException;
import com.google.ar.sceneform.AnchorNode;
import com.google.ar.sceneform.Camera;
import com.google.ar.sceneform.rendering.ModelRenderable;
import com.google.ar.sceneform.ux.ArFragment;
import com.google.ar.sceneform.ux.TransformableNode;

import org.opencv.android.OpenCVLoader;
import org.tensorflow.demo.Classifier;
import org.tensorflow.demo.OverlayView;
import org.tensorflow.demo.R;
import org.tensorflow.demo.TensorFlowMultiBoxDetector;
import org.tensorflow.demo.TensorFlowObjectDetectionAPIModel;
import org.tensorflow.demo.TensorFlowYoloDetector;
import org.tensorflow.demo.env.BorderedText;
import org.tensorflow.demo.env.ImageUtils;
import org.tensorflow.demo.initializer.ObjectReferenceList;
import org.tensorflow.demo.phd.MrDemoDetectorActivity;
import org.tensorflow.demo.simulator.App;
import org.tensorflow.demo.simulator.AppRandomizer;
import org.tensorflow.demo.simulator.Randomizer;
import org.tensorflow.demo.simulator.SingletonAppList;
import org.tensorflow.demo.tracking.DemoMultiBoxTracker;
import org.tensorflow.demo.tracking.DemoMultiBoxTrackerWithARCore;
import org.tensorflow.demo.tracking.MultiBoxTracker;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;
import java.util.Vector;

public class MrDemoDetectorWithARCoreActivity extends AppCompatActivity {

    private static final String TAG = MrDemoDetectorWithARCoreActivity.class.getName();

    private ArFragment fragment;
    private ModelRenderable andyRenderable;

    //private PointerDrawable pointer = new PointerDrawable();
    private boolean isTracking;
    private boolean isHitting;

    private static final int PERMISSIONS_REQUEST = 1;

    private static final String PERMISSION_CAMERA = Manifest.permission.CAMERA;
    private static final String PERMISSION_STORAGE = Manifest.permission.WRITE_EXTERNAL_STORAGE;

    // Configuration values for the prepackaged multibox model.
    private static final int MB_INPUT_SIZE = 224;
    private static final int MB_IMAGE_MEAN = 128;
    private static final float MB_IMAGE_STD = 128;
    private static final String MB_INPUT_NAME = "ResizeBilinear";
    private static final String MB_OUTPUT_LOCATIONS_NAME = "output_locations/Reshape";
    private static final String MB_OUTPUT_SCORES_NAME = "output_scores/Reshape";
    private static final String MB_MODEL_FILE = "file:///android_asset/multibox_model.pb";
    private static final String MB_LOCATION_FILE =
            "file:///android_asset/multibox_location_priors.txt";

    private static final int TF_OD_API_INPUT_SIZE = 300;
    private static final String TF_OD_API_MODEL_FILE =
            "file:///android_asset/ssd_mobilenet_v1_android_export.pb";
    private static final String TF_OD_API_LABELS_FILE = "file:///android_asset/coco_labels_list.txt";

    // Configuration values for tiny-yolo-voc. Note that the graph is not included with TensorFlow and
    // must be manually placed in the assets/ directory by the user.
    // Graphs and models downloaded from http://pjreddie.com/darknet/yolo/ may be converted e.g. via
    // DarkFlow (https://github.com/thtrieu/darkflow). Sample command:
    // ./flow --model cfg/tiny-yolo-voc.cfg --load bin/tiny-yolo-voc.weights --savepb --verbalise
    private static final String YOLO_MODEL_FILE = "file:///android_asset/graph-tiny-yolo-voc.pb";
    private static final int YOLO_INPUT_SIZE = 416;
    private static final String YOLO_INPUT_NAME = "input";
    private static final String YOLO_OUTPUT_NAMES = "output";
    private static final int YOLO_BLOCK_SIZE = 32;

    // Which detection model to use: by default uses Tensorflow Object Detection API frozen
    // checkpoints.  Optionally use legacy Multibox (trained using an older version of the API)
    // or YOLO.
    private enum DetectorMode {
        TF_OD_API, MULTIBOX, YOLO;
    }
    private static final DetectorMode MODE = DetectorMode.TF_OD_API;

    // Configuration values for the regular non-boxing classifier.
    private static final int INPUT_SIZE = 224;
    private static final int IMAGE_MEAN = 117;
    private static final float IMAGE_STD = 1;
    private static final String INPUT_NAME = "input";
    private static final String OUTPUT_NAME = "output";

    private static final String MODEL_FILE = "file:///android_asset/tensorflow_inception_graph.pb";
    private static final String LABEL_FILE =
            "file:///android_asset/imagenet_comp_graph_label_strings.txt";


    // Minimum detection confidence to track a detection.
    private static final float MINIMUM_CONFIDENCE_TF_OD_API = 0.6f;
    private static final float MINIMUM_CONFIDENCE_MULTIBOX = 0.1f;
    private static final float MINIMUM_CONFIDENCE_YOLO = 0.25f;

    private static final boolean MAINTAIN_ASPECT = MODE == DetectorMode.YOLO;

    private Classifier detector; //for TF detection

    private boolean debug = false;

    private Handler handler;
    private HandlerThread handlerThread;

    protected static final int CAPTURE_TIMEOUT = 19;
    protected static final int INSTANCE_TIMEOUT = 10;
    protected static long[] overallTimes;
    protected static long[] detectionTimes;

    private boolean useCamera2API;
    private boolean initialized = false;
    private boolean isProcessingFrame = false;
    private byte[][] yuvBytes = new byte[3][];
    private int[] rgbBytes = null;
    private int yRowStride;
    private byte[] luminanceCopy;

    protected int previewWidth = 0;
    protected int previewHeight = 0;
    protected static int inputSize = 300; // default to default crop size

    private long lastProcessingTimeMs;
    private long markerDetectionTime;
    private Bitmap rgbFrameBitmap = null;
    private Bitmap croppedBitmap = null;
    private Bitmap cropCopyBitmap = null;
    private Bitmap inputBitmap = null;

    private boolean computingDetection = false;

    private long timestamp = 0;

    private Runnable postInferenceCallback;
    private Runnable imageConverter;

    // For a multi-app setup
    private Randomizer randomizer;
    protected static SingletonAppList singletonAppList = SingletonAppList.getInstance();
    protected static List<App> appList;
    protected static String appListText;

    // List of reference objects form the initialization stage.
    protected static ObjectReferenceList objectReferenceList = ObjectReferenceList.getInstance();

    private BorderedText borderedText;

    private static final Size DESIRED_PREVIEW_SIZE = new Size(640, 480);

    private static final boolean SAVE_PREVIEW_BITMAP = false;
    private static final float TEXT_SIZE_DIP = 10;

    private Integer sensorOrientation;

    private Matrix frameToCropTransform;
    private Matrix cropToFrameTransform;

    private Matrix frameToInputTransform;
    private Matrix inputToFrameTransform;

    private Matrix inputToCropTransform;
    private Matrix cropToInputTransform;

    private OverlayView trackingOverlay;
    private DemoMultiBoxTrackerWithARCore tracker;

    static {
        if(!OpenCVLoader.initDebug()){
            Log.d(TAG,"OpenCV not loaded");
        } else {
            Log.d(TAG,"OpenCV loaded");
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG,"onCreate " + this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_arcore_container);

        fragment = (ArFragment)
                getSupportFragmentManager().findFragmentById(R.id.sceneform_fragment);

        SeekBar seekBar = (SeekBar) findViewById(R.id.seekBar);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser){
                Toast toast = Toast.makeText(MrDemoDetectorWithARCoreActivity.this,
                        "Privilege level set to " + progress, Toast.LENGTH_LONG);
                toast.setGravity(Gravity.CENTER, 0, 0);
                toast.show();
                tracker.setPrivilege(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar){

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }

        });

        fragment.getArSceneView().getScene().setOnUpdateListener(frameTime -> {
            fragment.onUpdate(frameTime);

            try {
                Image image = fragment.getArSceneView().getArFrame().acquireCameraImage();

                if (!initialized) initialize(image);
                //onUpdate();
                if (initialized) recognizeImage(image);
            } catch (NotYetAvailableException e1) {
                e1.printStackTrace();
            }

        });

        // When you build a Renderable, Sceneform loads its resources in the background while returning
        // a CompletableFuture. Call thenAccept(), handle(), or check isDone() before calling get().
        ModelRenderable.builder()
                .setSource(this, R.raw.andy)
                .build()
                .thenAccept(renderable -> andyRenderable = renderable)
                .exceptionally(
                        throwable -> {
                            Toast toast =
                                    Toast.makeText(this, "Unable to load andy renderable", Toast.LENGTH_LONG);
                            toast.setGravity(Gravity.CENTER, 0, 0);
                            toast.show();
                            Log.e(TAG,"Unable to load andy renderable");
                            return null;
                        });

        fragment.setOnTapArPlaneListener(
                (HitResult hitResult, Plane plane, MotionEvent motionEvent) -> {
                    if (andyRenderable == null) {
                        return;
                    }

                    // Create the Anchor.
                    Anchor anchor = hitResult.createAnchor();
                    AnchorNode anchorNode = new AnchorNode(anchor);
                    anchorNode.setParent(fragment.getArSceneView().getScene());

                    // Create the transformable andy and add it to the anchor.
                    TransformableNode andy = new TransformableNode(fragment.getTransformationSystem());
                    andy.setParent(anchorNode);
                    andy.setRenderable(andyRenderable);
                    andy.select();
                });

        //initializeGallery();
        if (appList == null) {
            singletonAppList = SingletonAppList.getInstance();
            getAppList();
        }

    }

    private void initialize(Image image){

        previewHeight = image.getHeight();
        previewWidth = image.getWidth();

        if ((previewWidth == 0 && previewHeight ==0)||(initialized)) return;

        final float textSizePx =
                TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, getResources().getDisplayMetrics());
        borderedText = new BorderedText(textSizePx);
        borderedText.setTypeface(Typeface.MONOSPACE);

        tracker = new DemoMultiBoxTrackerWithARCore(this);

        // setting up a TF detector (a TF OD type)
        int cropSize = TF_OD_API_INPUT_SIZE;
        if (MODE == DetectorMode.YOLO) {
            detector =
                    TensorFlowYoloDetector.create(
                            getAssets(),
                            YOLO_MODEL_FILE,
                            inputSize, //YOLO_INPUT_SIZE,
                            YOLO_INPUT_NAME,
                            YOLO_OUTPUT_NAMES,
                            YOLO_BLOCK_SIZE);
            cropSize = YOLO_INPUT_SIZE;
        } else if (MODE == DetectorMode.MULTIBOX) {
            detector =
                    TensorFlowMultiBoxDetector.create(
                            getAssets(),
                            MB_MODEL_FILE,
                            MB_LOCATION_FILE,
                            MB_IMAGE_MEAN,
                            MB_IMAGE_STD,
                            MB_INPUT_NAME,
                            MB_OUTPUT_LOCATIONS_NAME,
                            MB_OUTPUT_SCORES_NAME);
            cropSize = MB_INPUT_SIZE;
            Log.i(TAG,"Created detector using Multi-box Detector");
        } else {
            try {
                detector = TensorFlowObjectDetectionAPIModel.create(
                        getAssets(),
                        TF_OD_API_MODEL_FILE,
                        TF_OD_API_LABELS_FILE,
                        inputSize//TF_OD_API_INPUT_SIZE
                );
                cropSize = TF_OD_API_INPUT_SIZE;
                Log.i(TAG,"Created detector using Object Detector");
            } catch (final IOException e) {
                Log.e(TAG,"Exception initializing classifier!", e);
                Toast toast =
                        Toast.makeText(
                                getApplicationContext(), "Classifier could not be initialized", Toast.LENGTH_SHORT);
                toast.show();
                finish();
            }
        }

        sensorOrientation = 90; //getScreenOrientation(); // rotation - getScreenOrientation();
        Log.i(TAG,String.format("Camera orientation relative to screen canvas: %d", sensorOrientation));

        Log.d(TAG,String.format("Initialized with preview size (WxH) %dx%d", previewWidth,previewHeight));

        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888);
        croppedBitmap = Bitmap.createBitmap(cropSize, cropSize, Bitmap.Config.ARGB_8888);
        //inputBitmap = Bitmap.createBitmap(inputSize, inputSize, Config.ARGB_8888);

        frameToCropTransform =
                ImageUtils.getTransformationMatrix(
                        previewWidth, previewHeight,
                        cropSize, cropSize,
                        sensorOrientation, MAINTAIN_ASPECT);

        cropToFrameTransform = new Matrix();
        frameToCropTransform.invert(cropToFrameTransform);

        inputToCropTransform =
                ImageUtils.getTransformationMatrix(
                        inputSize, inputSize,
                        cropSize, cropSize,
                        sensorOrientation, MAINTAIN_ASPECT);

        cropToInputTransform = new Matrix();
        inputToCropTransform.invert(cropToInputTransform);

        frameToInputTransform =
                ImageUtils.getTransformationMatrix(
                        previewWidth, previewHeight,
                        inputSize, inputSize,
                        sensorOrientation, MAINTAIN_ASPECT);

        inputToFrameTransform = new Matrix();
        frameToInputTransform.invert(inputToFrameTransform);

        trackingOverlay = (OverlayView) findViewById(R.id.tracking_overlay);
        trackingOverlay.addCallback(
                new OverlayView.DrawCallback() {
                    @Override
                    public void drawCallback(final Canvas canvas) {
                        Log.i(TAG,String.format("(WxH) Preview dims: %dx%d; Canvas dims: %dx%d",
                                previewWidth,previewHeight,
                                canvas.getWidth(), canvas.getHeight()));
                        //tracker.draw(canvas);
                        tracker.drawSanitized(canvas);
/*                        if (isDebug()) {
                            tracker.drawDebug(canvas);
                        }*/
                    }
                });

        /**
         * This following addCallback is declared in the MrThreadedCameraActivity class and is another method
         * implementation of the addCallback from the OverlayView. Also, this is the one that
         * draws on the debug_overlay, whereas the previous one is for the tracking_overlay.
         */
        addCallback(
                new OverlayView.DrawCallback() {
                    @Override
                    public void drawCallback(final Canvas canvas) {

                        if (!isDebug()) {
                            return;
                        }

                        final Bitmap copy = cropCopyBitmap;
                        if (copy == null) {
                            return;
                        }

                        final int backgroundColor = Color.argb(100, 0, 0, 0);
                        canvas.drawColor(backgroundColor);

                        final Matrix matrix = new Matrix();
                        final float scaleFactor = 2;
                        matrix.postScale(scaleFactor, scaleFactor);
                        matrix.postTranslate(
                                canvas.getWidth() - copy.getWidth() * scaleFactor,
                                canvas.getHeight() - copy.getHeight() * scaleFactor);
                        canvas.drawBitmap(copy, matrix, new Paint());

                        final Vector<String> lines = new Vector<String>();
                        /**
                         * The for-loop below is responsible for the debug information that appears
                         * which looks like an output from the "drawDebug". The statString returns
                         * non-null if onSetDebug, i.e. stat logging, is enabled.
                         */
                        if (detector != null) {
                            Log.i(TAG,"StatStrings are printed.");
                            final String statString = detector.getStatString();
                            final String[] statLines = statString.split("\n");
                            for (final String line : statLines) {
                                lines.add(line);
                            }
                        }
                        lines.add("");

                        //if (markerDetected) lines.add("2D Hamming Marker detected.");
                        lines.add("Running " + singletonAppList.getList().size() + " apps");
                        lines.add("Frame: " + previewWidth + "x" + previewHeight);
                        //lines.add("Processed Frame: " + inputBitmap.getWidth() + "x" + inputBitmap.getWidth());
                        lines.add("Crop: " + copy.getWidth() + "x" + copy.getHeight());
                        lines.add("View: " + canvas.getWidth() + "x" + canvas.getHeight());
                        //lines.add("Rotation: " + sensorOrientation);
                        lines.add("Frame processing time: " + lastProcessingTimeMs + "ms (Marker detection time: " + markerDetectionTime + ")");

                        borderedText.drawLines(canvas, 10, canvas.getHeight() - 10, lines);
                    }
                });

        initialized = true;
    }

    protected void getAppList(){

        Log.d(TAG, "Creating a new list from SingletonAppList");
        randomizer = AppRandomizer.create();

        appList = randomizer.fixedAppGenerator(getApplicationContext(), 1);

        String appLogMessage = "App list:\n";
        for (App app : appList) {
            appLogMessage = appLogMessage + app.getName() + "\n";
        }
        Log.i(TAG,appLogMessage);
        appListText = appLogMessage;

        singletonAppList.setList(appList);
        singletonAppList.setListText(appListText);

        //appList = singletonAppList.getList();
        //appListText = singletonAppList.getListText();
    }

    private void startBackgroundThread() {
        handlerThread = new HandlerThread("inference");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
    }

    private void stopBackgroundThread() {
        handlerThread.quitSafely();
        try {
            handlerThread.join();
            handlerThread = null;
            handler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public synchronized void onStart() {
        Log.d(TAG,"onStart " + this);
        super.onStart();

        startBackgroundThread();
        //manager.generateList();
        //utilityHit = 0;
    }

    @Override
    public synchronized void onResume() {
        Log.d(TAG,"onResume " + this);
        super.onResume();

        startBackgroundThread();
        //manager.generateList();
    }

    @Override
    public synchronized void onPause() {
        Log.d(TAG,"onPause " + this);

        if (!isFinishing()) {
            Log.d(TAG,"Requesting finish");
            finish();
        }

        stopBackgroundThread();

        super.onPause();
        appList = null;
        //manager.storeList();
    }

    @Override
    public synchronized void onStop() {
        Log.d(TAG,"onStop " + this);
        super.onStop();
    }

    @Override
    public synchronized void onDestroy() {
        Log.d(TAG,"onDestroy " + this);
        super.onDestroy();
    }

    protected synchronized void runInBackground(final Runnable r) {
        if (handler != null) {
            Log.i(TAG,"Posting a runnable to the background thread.");
            handler.post(r);
        }
    }

    public void recognizeImage(Image image) {

        Log.d(TAG,"Entered recognizeImage.");

        //We need wait until we have some size from onPreviewSizeChosen
        if (previewWidth == 0 || previewHeight == 0) {
            return;
        }
        if (rgbBytes == null) {
            rgbBytes = new int[previewWidth * previewHeight];
        }
        try {
            //final Image image = fragment.getArSceneView().getArFrame().acquireCameraImage();
            Log.d(TAG,String.format("Acquired image with size %dx%d", image.getWidth(), image.getHeight()));

            if (image == null) {
                return;
            }

            if (isProcessingFrame) {
                image.close();
                Log.i(TAG,"Frame is processing.");
                return;
            }
            isProcessingFrame = true;
            Trace.beginSection("imageAvailable");
            final Image.Plane[] planes = image.getPlanes();
            fillBytes(planes, yuvBytes);
            yRowStride = planes[0].getRowStride();
            final int uvRowStride = planes[1].getRowStride();
            final int uvPixelStride = planes[1].getPixelStride();

            imageConverter =
                    new Runnable() {
                        @Override
                        public void run() {
                            ImageUtils.convertYUV420ToARGB8888(
                                    yuvBytes[0],
                                    yuvBytes[1],
                                    yuvBytes[2],
                                    previewWidth,
                                    previewHeight,
                                    yRowStride,
                                    uvRowStride,
                                    uvPixelStride,
                                    rgbBytes);
                        }
                    };

            postInferenceCallback =
                    new Runnable() {
                        @Override
                        public void run() {
                            image.close();
                            isProcessingFrame = false;
                        }
                    };

            //if (appList != null)
            if (detector!=null) processImage();
            else readyForNextImage();

        } catch (final Exception e) {
            Log.e(TAG,"Exception!");
            e.printStackTrace();
            Trace.endSection();
            return;
        }
        Trace.endSection();
    }

    protected void fillBytes(final Image.Plane[] planes, final byte[][] yuvBytes) {
        // Because of the variable row stride it's not possible to know in
        // advance the actual necessary dimensions of the yuv planes.
        for (int i = 0; i < planes.length; ++i) {
            final ByteBuffer buffer = planes[i].getBuffer();
            if (yuvBytes[i] == null) {
                Log.d(TAG,String.format("Initializing buffer %d at size %d", i, buffer.capacity()));
                yuvBytes[i] = new byte[buffer.capacity()];
            }
            buffer.get(yuvBytes[i]);
        }
    }

    protected void readyForNextImage() {
        if (postInferenceCallback != null) {
            postInferenceCallback.run();
        }
    }

    protected int getScreenOrientation() {
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_270:
                return 270;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_90:
                return 90;
            default:
                return 0;
        }
    }

    public void requestRender() {
        final OverlayView overlay = (OverlayView) findViewById(R.id.debug_overlay);
        if (overlay != null) {
            overlay.postInvalidate();
        }
    }

    public void addCallback(final OverlayView.DrawCallback callback) {
        final OverlayView overlay = (OverlayView) findViewById(R.id.debug_overlay);
        if (overlay != null) {
            overlay.addCallback(callback);
        }
    }

    private boolean isDebug() {
        return debug;
    }

    private void onSetDebug(final boolean debug) {}

    @Override
    public boolean onKeyDown(final int keyCode, final KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            debug = !debug;
            requestRender();
            //onSetDebug(debug);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    protected int[] getRgbBytes() {
        imageConverter.run();
        return rgbBytes;
    }

    protected int getLuminanceStride() {
        return yRowStride;
    }

    protected byte[] getLuminance() {
        return yuvBytes[0];
    }

    protected void processImage() {

        Log.d(TAG,"Entered processImage.");

        ++timestamp;
        final long currTimestamp = timestamp;
        byte[] originalLuminance = getLuminance();

        rgbFrameBitmap.setPixels(getRgbBytes(), 0, previewWidth, 0, 0, previewWidth, previewHeight);

        tracker.HomogFrameTracker(
                previewWidth,
                previewHeight,
                sensorOrientation,
                rgbFrameBitmap,
                timestamp);

        trackingOverlay.postInvalidate();

        // No mutex needed as this method is not reentrant.
        if (computingDetection) {
            readyForNextImage();
            return;
        }

        computingDetection = true;
        //tracker.setInitialize(false); // Signaling that a new initialization has to be done at the tracker.

        Log.i(TAG,"Preparing image " + currTimestamp + " for detection in bg thread.");

        if (luminanceCopy == null) {
            luminanceCopy = new byte[originalLuminance.length];
        }
        System.arraycopy(originalLuminance, 0, luminanceCopy, 0, originalLuminance.length);
        readyForNextImage();

        final Canvas canvas = new Canvas(croppedBitmap);
        canvas.drawBitmap(rgbFrameBitmap, frameToCropTransform, null);
        // For examining the actual TF input.
/*        if (SAVE_PREVIEW_BITMAP) {
            ImageUtils.saveBitmap(croppedBitmap);
        }*/

        runInBackground(
                new Runnable() {
                    @Override
                    public void run() {
                        // Send to tracker the reference frame for tracking.
                        tracker.setFrame(rgbFrameBitmap);

                        Log.i(TAG,"Running detection on image " + currTimestamp);
                        final long startTime = SystemClock.uptimeMillis();
                        final List<Classifier.Recognition> results = detector.recognizeImage(croppedBitmap);
                        lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime;

                        cropCopyBitmap = Bitmap.createBitmap(croppedBitmap);
                        final Canvas canvas = new Canvas(cropCopyBitmap);
                        final Paint paint = new Paint();
                        paint.setColor(Color.RED);
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setStrokeWidth(2.0f);

                        float minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
                        switch (MODE) {
                            case TF_OD_API:
                                minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
                                break;
                            case MULTIBOX:
                                minimumConfidence = MINIMUM_CONFIDENCE_MULTIBOX;
                                break;
                            case YOLO:
                                minimumConfidence = MINIMUM_CONFIDENCE_YOLO;
                                break;
                        }

                        final List<Classifier.Recognition> mappedRecognitions =
                                new LinkedList<Classifier.Recognition>();

                        for (final Classifier.Recognition result : results) {
                            final RectF location = result.getLocation();
                            if (location != null && result.getConfidence() >= minimumConfidence) {
                                canvas.drawRect(location, paint);

                                // Just checking the values of the RectF.
                                Log.i(TAG,
                                        result.getTitle()
                                                + "Bounding box dimensions are left: "
                                                + String.valueOf(location.left)
                                                + " and bottom: "
                                                + String.valueOf(location.bottom)
                                );

                                cropToFrameTransform.mapRect(location);
                                result.setLocation(location);
                                mappedRecognitions.add(result);
                            }
                        }

                        tracker.trackResults(mappedRecognitions, luminanceCopy, currTimestamp);
                        trackingOverlay.postInvalidate();

                        requestRender();
                        computingDetection = false;

                        Log.i(TAG,String.format("Object detection time %d ms and overall frame processing %d ms.", lastProcessingTimeMs, SystemClock.uptimeMillis() - startTime));

                    }
                });

    }

}
