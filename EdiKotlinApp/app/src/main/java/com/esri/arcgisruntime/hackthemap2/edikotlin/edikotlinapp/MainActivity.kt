package com.esri.arcgisruntime.hackthemap2.edikotlin.edikotlinapp

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import android.os.Environment
import android.support.annotation.NonNull
import android.support.constraint.ConstraintLayout
import android.support.design.widget.NavigationView
import android.support.design.widget.Snackbar
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v4.view.GravityCompat
import android.support.v7.app.ActionBarDrawerToggle
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import com.esri.arcgisruntime.data.ShapefileFeatureTable
import com.esri.arcgisruntime.geometry.GeometryEngine
import com.esri.arcgisruntime.geometry.Point
import com.esri.arcgisruntime.geometry.SpatialReference
import com.esri.arcgisruntime.geometry.SpatialReferences
import com.esri.arcgisruntime.layers.FeatureLayer
import com.esri.arcgisruntime.layers.RasterLayer
import com.esri.arcgisruntime.layers.WmsLayer
import com.esri.arcgisruntime.mapping.ArcGISScene
import com.esri.arcgisruntime.mapping.ArcGISTiledElevationSource
import com.esri.arcgisruntime.mapping.Basemap
import com.esri.arcgisruntime.mapping.view.Camera
import com.esri.arcgisruntime.mapping.view.SceneView
import com.esri.arcgisruntime.raster.Raster
import com.esri.arcgisruntime.symbology.SimpleFillSymbol
import com.esri.arcgisruntime.symbology.SimpleLineSymbol
import com.esri.arcgisruntime.symbology.SimpleRenderer
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.app_bar_main.*

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener, SensorEventListener {
    companion object {
        private const val TAG = "ARock"
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1
        private const val REQUEST_CHECK_SETTINGS = 2
        private const val CAMERA_ID = 0
    }

    enum class Mode {
        TOUCH, VR, AR
    }
    private var mMode = Mode.TOUCH

    lateinit var mSceneView : SceneView
    lateinit var mSensorManager : SensorManager
    lateinit var mFusedLocationClient : FusedLocationProviderClient
    val mCragLoc = Point(327076.0, 672965.0, 200.0, SpatialReference.create(27700))
    //var mHillLoc = Point(327065.0, 672954.0, 150.0, SpatialReference.create(27700))
    var mHillLoc = mCragLoc
    var mViewpointLoc = mHillLoc

    private lateinit var mLocationCallback: LocationCallback
    private lateinit var mLocationRequest: LocationRequest
    private var mVrStartOffsetX = 0.0
    private var mVrStartOffsetY = 0.0
    private var mIsLocationUpdating = false
    private var mSetVrStartLocation = false

    lateinit var mBedrockRasterLayer : RasterLayer
    lateinit var mTrailsFeatureLayer: FeatureLayer
    lateinit var mWMSLayer: WmsLayer
    private var mCamera : android.hardware.Camera? = null
    lateinit var mCameraPreview : CameraPreview

//    class MyOnTouchListener : DefaultSceneViewOnTouchListener {
//        MyOnTouchListener(SceneView sceneView) {
//            super(sceneView)
//        }
//    }
//
//    private val mOnTouchListener = View.OnTouchListener()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)

        fab.setOnClickListener { view ->
            var msg = "started"
            mIsLocationUpdating = !mIsLocationUpdating
            if (mIsLocationUpdating) {
                startLocationUpdates()
            } else {
                stopLocationUpdates()
                msg = "stopped"
                mViewpointLoc = mCragLoc
            }
            Snackbar.make(view, "Location update " + msg, Snackbar.LENGTH_LONG)
                    .setAction("Action", null).show()
        }

        val toggle = ActionBarDrawerToggle(
                this, drawer_layout, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close)
        drawer_layout.addDrawerListener(toggle)
        toggle.syncState()

        nav_view.setNavigationItemSelectedListener(this)

        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        mLocationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                super.onLocationResult(locationResult)

                var location = locationResult.lastLocation
//                mViewpointLoc = Point(mVrStartLocation.longitude, mVrStartLocation.latitude, mVrStartLocation.altitude,
//                        SpatialReferences.getWgs84())
                var pt1 = Point(location.longitude, location.latitude, SpatialReferences.getWgs84())
                var pt2 = GeometryEngine.project(pt1, SpatialReference.create(27700)) as Point
                if (mSetVrStartLocation) {
                    mVrStartOffsetX = pt2.x - mHillLoc.x
                    mVrStartOffsetY = pt2.y - mHillLoc.y
                    mSetVrStartLocation = false
                }
                if (mMode == Mode.VR) {
                    mViewpointLoc = Point(pt2.x - mVrStartOffsetX, pt2.y - mVrStartOffsetY, 160.0, SpatialReference.create(27700))
                } else {
                    mViewpointLoc = Point(pt2.x, pt2.y, 150.0, SpatialReference.create(27700))
                }
            }
        }
        createLocationRequest(false)

        // create data sets
        createBedrockWMS();
        createBedrock();
        createTrails();
        requestCameraPermission()

        mSensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        mSceneView = findViewById<SceneView>(R.id.sceneView)
//        mSceneView.setOnTouchListener(mOnTouchListener)
        val scene = ArcGISScene()
        scene.setBasemap(Basemap.createImagery())
        mSceneView.scene = scene

        initTouchMode()

        val elevationSource = ArcGISTiledElevationSource(getResources().getString(R.string.elevation_image_service))
        scene.baseSurface.elevationSources.add(elevationSource)
    }

    override fun onPause() {
        super.onPause()
        mSceneView.pause()
        stopLocationUpdates()
    }

    override fun onResume() {
        super.onResume()
        mSceneView.resume()
        mSensorManager.registerListener(this, mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR),
                SensorManager.SENSOR_DELAY_FASTEST)
        if (mIsLocationUpdating) {
            startLocationUpdates()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CHECK_SETTINGS) {
            if (resultCode == Activity.RESULT_OK) {
                if (mIsLocationUpdating) {
                    startLocationUpdates()
                }
            }
        }
    }

    override fun onBackPressed() {
        if (drawer_layout.isDrawerOpen(GravityCompat.START)) {
            drawer_layout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.layers_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        when (item.itemId) {
            R.id.bedrockWMS -> {
                item.setChecked(!item.isChecked());
                if (item.isChecked()) {
                    mSceneView.getScene().getOperationalLayers().add(mWMSLayer);
                } else {
                    mSceneView.getScene().getOperationalLayers().remove(mWMSLayer);
                }
                return true
            }
            R.id.bedrocklocal -> {
                item.setChecked(!item.isChecked());
                if (item.isChecked()) {
                    mSceneView.getScene().getOperationalLayers().add(mBedrockRasterLayer);
                } else {
                    mSceneView.getScene().getOperationalLayers().remove(mBedrockRasterLayer);
                }
                return true
            }
            R.id.trails -> {
                item.setChecked(!item.isChecked());
                if (item.isChecked()) {
                    mSceneView.getScene().getOperationalLayers().add(mTrailsFeatureLayer);
                } else {
                    mSceneView.getScene().getOperationalLayers().remove(mTrailsFeatureLayer);
                }
                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        // Handle navigation view item clicks here.
        when (item.itemId) {
            R.id.touch_mode -> {
                if (mMode != Mode.TOUCH) {
                    stopLocationUpdates()
                    mIsLocationUpdating = false
                }
                initTouchMode()
            }
            R.id.vr_mode -> {
                if (mMode == Mode.TOUCH) {
                    mIsLocationUpdating = true
                    startLocationUpdates()
                }
                mMode = Mode.VR
                mViewpointLoc = mHillLoc
                mSetVrStartLocation = true;

            }
            R.id.ar_mode -> {
                if (mMode == Mode.TOUCH) {
                    mIsLocationUpdating = true
                    startLocationUpdates()
                }
                mMode = Mode.AR
            }
            R.id.nav_manage -> {

            }
            R.id.nav_share -> {

            }
            R.id.nav_send -> {

            }
        }

        drawer_layout.closeDrawer(GravityCompat.START)
        return true
    }

    private fun initTouchMode() {
        mMode = Mode.TOUCH
        val locationCamera = Camera(mHillLoc, 0.0, 90.0, 0.0)
        Log.d(TAG, "camera x: " + locationCamera.getLocation().getX() + ", y: " +
                locationCamera.getLocation().getY() + ", heading: " + locationCamera.getHeading())
        mSceneView.setViewpointCamera(locationCamera)
    }

    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (mMode != Mode.TOUCH && event is SensorEvent) {
            if (event.sensor.getType() === Sensor.TYPE_ROTATION_VECTOR) {
                // convert the rotation-vector to a 4x4 matrix.
                val mRotationMatrix = FloatArray(16)
                SensorManager.getRotationMatrixFromVector(mRotationMatrix, event.values)
                SensorManager.remapCoordinateSystem(mRotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, mRotationMatrix)
                val orientationVals = FloatArray(5)
                SensorManager.getOrientation(mRotationMatrix, orientationVals)

                // convert the result from radians to degrees
                val azimuth = Math.toDegrees(orientationVals[0].toDouble())
                val pitch = 90 - Math.toDegrees(orientationVals[1].toDouble())
//                val roll = - Math.toDegrees(orientationVals[2].toDouble())
                val roll = 270 - Math.toDegrees(orientationVals[2].toDouble())

                //Log.d(TAG, "Pitch: " + pitch + "Roll: " + roll + "Azimuth: " + azimuth);

                val locationCamera = Camera(mViewpointLoc, azimuth, pitch, roll)

//                Log.d(TAG, "camera x: " + locationCamera.getLocation().getX() + ", y: " +
//                        locationCamera.getLocation().getY() + ", heading: " + locationCamera.getHeading())

                mSceneView.setViewpointCamera(locationCamera)

                //mSceneView.setViewpointCamera(new Camera(mViewingPoint, 1000, 0, 135 ,0));
            }
        }
    }

//    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
//        when (requestCode) {
//            LOCATION_PERMISSION_REQUEST_CODE -> {
//                // If request is cancelled, the result arrays are empty.
//                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
//                    if (ActivityCompat.checkSelfPermission(this,
//                            android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
//                        mFusedLocationClient.lastLocation.addOnSuccessListener(this) { location ->
//                            // Got last known location. In some rare situations this can be null.
//                            if (location != null) {
//                                //lastLocation = location
//                                //val currentLatLng = LatLng(location.latitude, location.longitude)
//                                //map.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 12f))
//                                mViewpointLoc = Point(location.longitude, location.latitude, location.altitude, SpatialReferences.getWgs84())
//                            }
//                        }
//                    }
//                } else {
//
//                    // permission denied, boo! Disable the
//                    // functionality that depends on this permission.
//
//                }
//                return
//            }
//
//        // Add other 'when' lines to check for other
//        // permissions this app might request.
//
//            else -> {
//                // Ignore all other requests.
//            }
//        }
//    }

//    private fun initLocationSettings() {
//
////        if (ActivityCompat.checkSelfPermission(this,
////                android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
////            ActivityCompat.requestPermissions(this,
////                    arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_CHECK_SETTINGS)
////        }
////        mFusedLocationClient.lastLocation.addOnSuccessListener(this) { location ->
////            // Got last known location. In some rare situations this can be null.
////            if (location != null) {
////                //lastLocation = location
////                //val currentLatLng = LatLng(location.latitude, location.longitude)
////                //map.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 12f))
////                mViewpointLoc = Point(location.longitude, location.latitude, location.altitude, SpatialReferences.getWgs84())
////            }
////        }
//
//        mLocationRequest = LocationRequest()
//        mLocationRequest.setInterval(10000)
//        mLocationRequest.setFastestInterval(5000)
//        mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
//
//        val builder = LocationSettingsRequest.Builder().addLocationRequest(mLocationRequest);
//        val client = LocationServices.getSettingsClient(this);
//        val task = client.checkLocationSettings(builder.build());
//        task.addOnSuccessListener{
//            // All location settings are satisfied. The client can initialize
//            // location requests here.
//            if (ActivityCompat.checkSelfPermission(this,
//                    android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
//                // Get and use the last known location
//                mFusedLocationClient.lastLocation.addOnSuccessListener(this) { location ->
//                    // Got last known location. In some rare situations this can be null.
//                    if (location != null) {
//                        mViewpointLoc = Point(location.longitude, location.latitude, location.altitude, SpatialReferences.getWgs84())
//                    }
//                }
//            } else {
//                ActivityCompat.requestPermissions(this,
//                        arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_REQUEST_CODE)
//            }
//        }
//        task.addOnFailureListener { e ->
//            if (e is ResolvableApiException) {
//                // Location settings are not satisfied, but this can be fixed
//                // by showing the user a dialog.
//                try {
//                    // Show the dialog by calling startResolutionForResult(),
//                    // and check the result in onActivityResult().
//                    //e.startResolutionForResult(this@MainActivity, REQUEST_CHECK_SETTINGS);
//                } catch (sendEx: IntentSender.SendIntentException) {
//                    // Ignore the error.
//                }
//            }
//        }
//    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
                    LOCATION_PERMISSION_REQUEST_CODE)
            return
        }
        mFusedLocationClient.requestLocationUpdates(mLocationRequest, mLocationCallback, null /* Looper */)
    }

    private fun stopLocationUpdates() {
        mFusedLocationClient.removeLocationUpdates(mLocationCallback)
    }


    private fun createLocationRequest(turnOnLocation : Boolean) {
        mLocationRequest = LocationRequest()
        mLocationRequest.setInterval(10000)
        mLocationRequest.setFastestInterval(5000)
        mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)

        val builder = LocationSettingsRequest.Builder().addLocationRequest(mLocationRequest)
        val client = LocationServices.getSettingsClient(this)
        val task = client.checkLocationSettings(builder.build())

        // 5
        task.addOnSuccessListener {
            mIsLocationUpdating = turnOnLocation
            if (turnOnLocation) {
                startLocationUpdates()
            }
        }
        task.addOnFailureListener { e ->
            if (e is ResolvableApiException) {
                // Location settings are not satisfied, but this can be fixed
                // by showing the user a dialog.
                try {
                    // Show the dialog by calling startResolutionForResult(),
                    // and check the result in onActivityResult().
                    e.startResolutionForResult(this@MainActivity, REQUEST_CHECK_SETTINGS)
                } catch (sendEx: IntentSender.SendIntentException) {
                    // Ignore the error.
                }
            }
        }
    }

  /**
   * Creates a Raster layer from a local raster which shows bedrock.
   */
  private fun createBedrock() {
    val bedrock = Raster(Environment.getExternalStorageDirectory().toString() +
            getString(R.string.raster_folder) + getString(R.string.bedrock_file_name));
    bedrock.loadAsync();
    bedrock.addDoneLoadingListener{ ->
      Log.d("raster", bedrock.getLoadStatus().toString());
        if (bedrock.loadError != null) {
            bedrock.loadError.printStackTrace()
        }
        //Log.d("raster", "MESSGE=" + bedrock.loadError.message());
    };
    Log.d("raster", Environment.getExternalStorageDirectory().toString() +
            getString(R.string.raster_folder) + getString(R.string.bedrock_file_name));
    mBedrockRasterLayer = RasterLayer(bedrock);
  }

  /**
   * Creates a Feature layer from a local shapefiled which shows trails.
   */
  private fun createTrails() {
    val trailsFeatureTable = ShapefileFeatureTable(
        Environment.getExternalStorageDirectory().toString() +
                getString(R.string.shapefile_folder) + getString(R.string.trails_file_name));
    Log.d("path", Environment.getExternalStorageDirectory().toString() +
            getString(R.string.shapefile_folder) + getString(R.string.trails_file_name));
    trailsFeatureTable.loadAsync();
    trailsFeatureTable.addDoneLoadingListener{ ->
      Log.d(TAG, trailsFeatureTable.getLoadStatus().toString());
    };

    // use the shapefile feature table to create a feature layer
    mTrailsFeatureLayer = FeatureLayer(trailsFeatureTable);

    // create the Symbol
    val lineSymbol = SimpleLineSymbol(SimpleLineSymbol.Style.SOLID, Color.RED, 2.0f);
    val fillSymbol = SimpleFillSymbol(SimpleFillSymbol.Style.SOLID, Color.YELLOW, lineSymbol);

    // create the Renderer
    val renderer = SimpleRenderer(fillSymbol);

    // set the Renderer on the Layer
    mTrailsFeatureLayer.setRenderer(renderer);
  }

  /**
   * Create a WMS layer and add it to the scene.
   */
  private fun createBedrockWMS() {
      val names: MutableList<String> = ArrayList<String>();
    names.add("BGS.50k.Bedrock");

    mWMSLayer = WmsLayer(getString(R.string.wms_bgs_geology), names);
    mWMSLayer.loadAsync();

  }

  private fun startCamera() {
    // Open an instance of the first camera and retrieve its info.
    mCamera = getCameraInstance(CAMERA_ID);
    val cameraInfo = android.hardware.Camera.CameraInfo();
      android.hardware.Camera.getCameraInfo(CAMERA_ID, cameraInfo);

    if (mCamera == null || cameraInfo == null) {
      // Camera is not available, display error message
      Toast.makeText(this, "Camera is not available.", Toast.LENGTH_SHORT).show();
    } else {

      // Get the rotation of the screen to adjust the preview image accordingly.
      val displayRotation = getWindowManager().getDefaultDisplay().getRotation();

      // Create the Preview view and set it as the content of this Activity.
      mCameraPreview = CameraPreview(this, mCamera, cameraInfo, displayRotation);
      val preview : ConstraintLayout = findViewById(R.id.cameraView);
      preview.addView(mCameraPreview);
    }
  }

  /**
   * Request read permission on the device.
   */

  private fun requestCameraPermission() {
    // define permission to request
      val reqPermission = arrayOf(Manifest.permission.CAMERA, Manifest.permission.READ_EXTERNAL_STORAGE)
    val requestCode = 2;
    // For API level 23+ request permission at runtime
    if (ContextCompat.checkSelfPermission(this@MainActivity,
        reqPermission[0]) == PackageManager.PERMISSION_GRANTED) {
      startCamera();
    } else {
      // request permission
      ActivityCompat.requestPermissions(this@MainActivity, reqPermission, requestCode);
    }
  }

  /**
   * Handle the permissions request response.
   */
  public fun onRequestPermissionsResult(requestCode:Int, permissions : Array<String>, grantResults:Array<Int>) {
    if (grantResults.size > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
      releaseCamera();
    } else {
      // report to user that permission was denied
      Toast.makeText(this@MainActivity, getResources().getString(R.string.camera_permission_denied),
          Toast.LENGTH_SHORT).show();
    }
  }

  /**
   * A safe way to get an instance of the Camera object.
   */
  private fun getCameraInstance(cameraId : Int) : android.hardware.Camera {
    var c : android.hardware.Camera? = null
 //   try {
      c = android.hardware.Camera.open(cameraId); // attempt to get a Camera instance
//    } catch (Exception e) {
//      // Camera is not available (in use or does not exist)
//      Toast.makeText(this, "Camera " + cameraId + " is not available: " + e.getMessage(),
//          Toast.LENGTH_SHORT).show();
//    }
    return c; // returns null if camera is unavailable
  }

  private fun releaseCamera() {
    if (mCamera != null) {
      mCamera?.release()        // release the camera for other applications
      mCamera = null
    }
  }
}
