package org.tensorflow.demo.arcore;

import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;

import com.google.ar.sceneform.ux.ArFragment;

import org.tensorflow.demo.R;

public class MrDemoDetectorWithARCoreActivity extends AppCompatActivity {

    private ArFragment fragment;

    //private PointerDrawable pointer = new PointerDrawable();
    private boolean isTracking;
    private boolean isHitting;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_arcore_container);

        fragment = (ArFragment)
                getSupportFragmentManager().findFragmentById(R.id.sceneform_fragment);

        fragment.getArSceneView().getScene().setOnUpdateListener(frameTime -> {
            fragment.onUpdate(frameTime);
            //onUpdate();
        });

        //initializeGallery();

    }


}
