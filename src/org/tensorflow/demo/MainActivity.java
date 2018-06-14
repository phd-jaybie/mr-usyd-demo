package org.tensorflow.demo;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.View;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

import org.opencv.android.OpenCVLoader;
import org.tensorflow.demo.env.Logger;
import org.tensorflow.demo.phd.MrDetectorActivity;
import org.tensorflow.demo.phd.MrNullActivity;
import org.tensorflow.demo.phd.MrThreadedDetectorActivity;
import org.tensorflow.demo.phd.ProtectedMrDetectorActivity;
import org.tensorflow.demo.phd.ProtectedMrDetectorActivityWithNetwork;
import org.tensorflow.demo.simulator.App;
import org.tensorflow.demo.simulator.AppRandomizer;
import org.tensorflow.demo.simulator.Randomizer;
import org.tensorflow.demo.simulator.SingletonAppList;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by deg032 on 9/2/18.
 */

public class MainActivity extends Activity {
    private static final Logger LOGGER = new Logger();
    private static String logFile;
    private static PrintWriter writer;

    private Handler handler;
    private HandlerThread handlerThread;

    private Randomizer randomizer;

    private int numberOfApps;
    public List<App> appList = new ArrayList<>();
    public String appListText;

    private SingletonAppList singletonAppList;

    private TextView textView;
    private EditText numberText;
    private EditText urlStringView;
    private EditText captureSizeView;
    private Switch debugSwitch; // This switch just tells the processing activities if captures are limited or not.
    private Switch threadSwitch; // This switch just tells the processing activities if threaded or not.
    private Switch fixedAppsSwitch; // This switch just tells the app randomizer to create a fixed set of apps.
    private Switch networkSwitch; // This switch just tells whether the detection is local or remote.

    private String NetworkMode = "LOCAL";
    private boolean FastDebug = false;
    private boolean Threading = false;
    private boolean FixedApps = false;
    private String remoteUrl = null;
    private int inputSize = 300;

    public final String firstMessage = "Generate App list first";

    static {
        LOGGER.i("DataGatheringAverage, Image, Number of Apps, Frame Size, " +
                "Overall Frame Processing (ms), Detection Time (ms), " +
                "Number of hits, Secret hits (, Latent Privacy Hit) ");

        if(!OpenCVLoader.initDebug()){
            LOGGER.d("OpenCV not loaded");
        } else {
            LOGGER.d("OpenCV loaded");
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState){
        LOGGER.d("onCreate " + this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initialize(); //Initializes the views

/*        if (isNetworkConnected()) {
            ProgressDialog mProgressDialog = new ProgressDialog(this);
            mProgressDialog.setMessage("Please wait...");
            mProgressDialog.setCancelable(true);
            mProgressDialog.show();
        } else {
            noConnection();
        }*/

    }

    private boolean isNetworkConnected() {
        ConnectivityManager connMgr = (ConnectivityManager)
                getSystemService(Context.CONNECTIVITY_SERVICE); // 1
        NetworkInfo networkInfo = connMgr.getActiveNetworkInfo(); // 2
        return networkInfo != null && networkInfo.isConnected(); // 3
    }

    private void noConnection(){
        new AlertDialog.Builder(this)
                .setTitle("No Internet Connection")
                .setMessage("It looks like your internet connection is off. Please turn it " +
                        "on and try again")
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                    }
                });//.setIcon(android.R.drawable.ic_dialog_alert).show();
    }

    private void initialize() {
        Timestamp timestamp = new Timestamp(System.currentTimeMillis());
        logFile = "log-" + timestamp.toString() + ".txt";

        try {
            Context context = this.getApplicationContext();
            File file = new File(context.getFilesDir(), logFile);
            FileWriter writer = new FileWriter(file);
            writer.write("DataGathering, Image, Number of Apps, Frame Size," +
                    "Overall Frame Processing (ms), Detection Time (ms)");
            singletonAppList.setWriter(writer);
        } catch (Exception e) {
            e.printStackTrace();
        }

        textView = (TextView) findViewById(R.id.generate_textView);
        numberText = (EditText) findViewById(R.id.number_of_apps);
        captureSizeView = (EditText) findViewById(R.id.capture_size);
        urlStringView = (EditText) findViewById(R.id.remote_url);
        debugSwitch = (Switch) findViewById(R.id.debug_toggle);
        threadSwitch = (Switch) findViewById(R.id.thread_toggle);
        fixedAppsSwitch = (Switch) findViewById(R.id.fixed_apps_toggle);
        networkSwitch = (Switch) findViewById(R.id.network_toggle);
        singletonAppList = SingletonAppList.getInstance();
    }

    public void generateAppList(View view){

        String sNumberOfApps = numberText.getText().toString();

        if (sNumberOfApps.isEmpty()) return;

        numberOfApps = Integer.valueOf(sNumberOfApps);

        String message;

        if (debugSwitch.isChecked()) message = "Will only take a limited number captures.\n";
        else message = "Will capture continuously.\n";

        message = message + "Creating a " + numberOfApps + "-app list.\n";
        LOGGER.i(message);

        writeToTextView(message);

        randomizer = AppRandomizer.create();

        if (FixedApps) {

            runInBackground(new Runnable() {
                @Override
                public void run() {
                    appList = randomizer.fixedAppGenerator(getApplicationContext(), numberOfApps);

                    String appLogMessage = "App list:\n";
                    for (App app : appList) {
                        appLogMessage = appLogMessage + app.getName() + "\n";
                    }
                    LOGGER.i(appLogMessage);
                    appListText = appLogMessage;

                    writeToTextView(appLogMessage);
                    singletonAppList.setList(appList);
                    singletonAppList.setListText(appListText);
                }
            });

        } else {
            runInBackground(new Runnable() {
                @Override
                public void run() {
                    appList = randomizer.appGenerator(getApplicationContext(), numberOfApps);

                    String appLogMessage = "App list:\n";
                    for (App app : appList) {
                        appLogMessage = appLogMessage + app.getName() + "\n";
                    }
                    LOGGER.i(appLogMessage);
                    appListText = appLogMessage;

                    writeToTextView(appLogMessage);
                    singletonAppList.setList(appList);
                    singletonAppList.setListText(appListText);
                }
            });
        }

    }

    private boolean checkList(){

        String captureSizeViewText = captureSizeView.getText().toString();
        if (captureSizeViewText.isEmpty()) inputSize = 300;
        else inputSize = Integer.valueOf(captureSizeViewText);

        if (singletonAppList.getList().isEmpty()) {
            writeToTextView(firstMessage);
            return false;
        } else if (networkSwitch.isChecked() && (remoteUrl == null)) {// || !URLUtil.isValidUrl(remoteUrl)) ) {
            writeToTextView("No or Invalid URL for remote.");
            return false;
        } else {
            writeToTextView(singletonAppList.getListText());
            return true;
        }

    }

    private void writeToTextView(final String message){

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (textView == null) textView = (TextView) findViewById(R.id.generate_textView);
                textView.setText(message);
            }
        });
    }

    public void onFastDebug(View view){
        if (debugSwitch.isChecked()) {
            debugSwitch.setTextColor(Color.BLACK);
            FastDebug = true;
        } else {
            debugSwitch.setTextColor(Color.LTGRAY);
            FastDebug = false;
        }

    }

    public void onThread(View view){
        if (threadSwitch.isChecked()) {
            threadSwitch.setTextColor(Color.BLACK);
            Threading = true;
        } else {
            threadSwitch.setTextColor(Color.LTGRAY);
            Threading = false;
        }

    }

    public void onFixedApps(View view){
        if (fixedAppsSwitch.isChecked()) {
            fixedAppsSwitch.setTextColor(Color.BLACK);
            FixedApps = true;
        } else {
            fixedAppsSwitch.setTextColor(Color.LTGRAY);
            FixedApps = false;
        }

    }

    public void onNetworkProcess(View view){

        remoteUrl = urlStringView.getText().toString();

        if (networkSwitch.isChecked()) {
            LOGGER.i("Remote image processing.");
            networkSwitch.setTextColor(Color.BLACK);
            NetworkMode = "REMOTE_PROCESS";
        } else {
            LOGGER.i("Local image processing.");
            NetworkMode = "LOCAL";
            networkSwitch.setTextColor(Color.LTGRAY);
        }

    }


    public void mrNullIntent(View view){

        //if (!checkList()) return;

        Intent detectorIntent = new Intent(this, MrNullActivity.class);
        detectorIntent.putExtra("InputSize", inputSize);
        detectorIntent.putExtra("FastDebug", FastDebug);
        startActivity(detectorIntent);

    }

    public void mrDetectionIntent(View view){

        if (!checkList()) return;

        if (Threading) {
            Intent detectorIntent = new Intent(this, MrThreadedDetectorActivity.class);
            detectorIntent.putExtra("InputSize", inputSize);
            detectorIntent.putExtra("FastDebug", FastDebug);
            startActivity(detectorIntent);
        } else {
            Intent detectorIntent = new Intent(this, MrDetectorActivity.class);
            detectorIntent.putExtra("InputSize", inputSize);
            detectorIntent.putExtra("FastDebug", FastDebug);
            startActivity(detectorIntent);
        }

    }

    public void mrDetectionIntentProtected(View view){

        if (!checkList()) return;

        Intent detectorIntent = new Intent(this, ProtectedMrDetectorActivity.class);
        detectorIntent.putExtra("InputSize", inputSize);
        detectorIntent.putExtra("FastDebug", FastDebug);
        startActivity(detectorIntent);

    }

    public void mrDetectionIntentWithSharing(View view){

        if (!checkList()) return;

        Intent detectorIntent = new Intent(this, ProtectedMrDetectorActivityWithNetwork.class);
        detectorIntent.putExtra("NetworkMode",NetworkMode);
        detectorIntent.putExtra("RemoteURL",remoteUrl);
        detectorIntent.putExtra("InputSize", inputSize);
        detectorIntent.putExtra("FastDebug", FastDebug);
        startActivity(detectorIntent);

    }

    private void startBackgroundThread() {
        handlerThread = new HandlerThread("main activity");
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
        LOGGER.d("onStart " + this);
        super.onStart();

        initialize();
        startBackgroundThread();
    }

    @Override
    public synchronized void onResume() {
        LOGGER.d("onResume " + this);
        super.onResume();

        initialize();
        startBackgroundThread();
    }

    @Override
    public synchronized void onPause() {
        LOGGER.d("onPause " + this);

        if (!isFinishing()) {
            LOGGER.d("Requesting finish");
            finish();
        }

        stopBackgroundThread();

        super.onPause();
        if (writer!=null) writer.close();
    }

    protected synchronized void runInBackground(final Runnable r) {
        if (handler != null) {
            handler.post(r);
        }
    }

}
