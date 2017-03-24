package org.gpsalarm;

import android.Manifest;
import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.media.MediaPlayer;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.places.Place;
import com.google.android.gms.location.places.ui.PlaceAutocompleteFragment;
import com.google.android.gms.location.places.ui.PlaceSelectionListener;
import com.google.android.gms.maps.CameraUpdate;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapFragment;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import static org.gpsalarm.MyStartActivity.FILENAME;
import static org.gpsalarm.MyStartActivity.markerDataList;
import static org.gpsalarm.MyStartActivity.saveMarkerDataList;

import java.io.IOException;
import java.util.ArrayList;

import static android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI;
import static org.gpsalarm.InternalStorage.readObject;

public class MapsActivity extends AppCompatActivity implements OnMapReadyCallback, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener, GoogleMap.OnInfoWindowClickListener, LocationListener {
    //Date date = new Date(2020, 12, 24);
    final static int MY_PERMISSION_FINE_LOCATIONS = 101;
    final static int NOTIFICATION_ID = 899068621;
    static String ringtonePath;
    float maximumSpeed; // FIXME set to nonstatic int
    static long interval = 0;                    // interval between tracking requests
    static private boolean userNotified = false; // is user notified about arrival
    static private boolean isTracking = true;    // is tracking going on
    static private boolean checkedWiFi = false;  // is WiFi connection suggested
    static Context context;
    static Marker marker;    // Marker of chosen location, should be static, otherwise can get different values when activity is called from different places
    GoogleMap googleMap;
    GoogleApiClient googleApiClient;
    Circle circle;
    float alarmRadius;    // FIXME set to nonstatic int Used by markers. Can now be set through preferences.
    MediaPlayer mediaPlayer;
    LocationRequest locationRequest;  // variable for requesting location
    TrackerAlarmReceiver alarm;
    Button closePopUp;
    WifiManager wifiManager;
    LatLng addressGeo;
    String addressName;
    private PopupWindow popupWindow;
    private LocationManager locationManager;

    enum Estimate { // List of travel distance estimation values
        FAR, NEAR, DISABLED;
    }

    private OnClickListener cancel_button_click_listener = new OnClickListener() {
        public void onClick(View v) {
            mediaPlayer.pause();
            clearMarker();
            userNotified = false;
            popupWindow.dismiss();
        }
    };

    static MapsActivity getMapsActivity() {
        return (MapsActivity) context;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d("onCreate", "");
        super.onCreate(savedInstanceState);
        context = this;

        // Reset marker
        // FIXME this makes addressName to null, should be deleted
        // geoLocate(null);

        // Manage wake up alerts
        alarm = new TrackerAlarmReceiver();

        checkGPS();
        checkAndConnect();

        if (googleServicesAvailable()) {
            setContentView(R.layout.activity_map);
            initMap();

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
            setAlarmRadius(Integer.parseInt(prefs.getString("alarmRadius", "500")));
            maximumSpeed = Integer.parseInt(prefs.getString("maximumSpeed", "100"));
            ringtonePath = prefs.getString("alarmRingtone", DEFAULT_ALARM_ALERT_URI.toString());
            initSound();

            SharedPreferences.OnSharedPreferenceChangeListener prefListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
                public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {

                    if (key.equals("mapType")) {
                        changeMapType(prefs.getString("2", "2"));
                    }
                    if (key.equals("alarmRadius")) {
                        setAlarmRadius(Integer.parseInt(prefs.getString(key, "500")));
                        clearMarker();
                    }
                    if (key.equals("alarmRingtone")) {
                        ringtonePath = prefs.getString("alarmRingtone", DEFAULT_ALARM_ALERT_URI.toString());
                        initSound();
                    }

                }
            };

            prefs.registerOnSharedPreferenceChangeListener(prefListener);

            try {
                markerDataList = (ArrayList<MarkerData>) InternalStorage.readObject(this, FILENAME); // Retrieve the list from internal storage
            } catch (IOException e) {
                Log.e("File Read error: ", e.getMessage());
            } catch (ClassNotFoundException e) {
                Toast.makeText(this, "Failed to retrieve list from file", Toast.LENGTH_SHORT).show();
                Log.e("File Read error: ", e.getMessage());
            } catch (Exception e) {
                Log.e("Exception: ", e.getMessage());
            }

        }

        // Google map searching by addres name
        // See https://g.co/AppIndexing/AndroidStudio for more information.
        PlaceAutocompleteFragment autocompleteFragment = (PlaceAutocompleteFragment)
                getFragmentManager().findFragmentById(R.id.place_autocomplete_fragment);
        autocompleteFragment.setOnPlaceSelectedListener(new PlaceSelectionListener() {
            @Override
            public void onPlaceSelected(Place place) {
                addressGeo = place.getLatLng();
                addressName = place.getName().toString();
                Log.i("V", "longitude: " + place.getLatLng().longitude);
            }

            @Override
            public void onError(Status status) {
            }
        });
    }

    private void initMap() {
        Log.d("initMap", "");
        MapFragment myMapFragment = (MapFragment) getFragmentManager().findFragmentById(R.id.fragment);
        myMapFragment.getMapAsync(this);            // Previously getMap
    }

    public boolean googleServicesAvailable() {
        GoogleApiAvailability api = GoogleApiAvailability.getInstance();
        int isAvailable = api.isGooglePlayServicesAvailable(this);      // Can return 3 different values

        if (isAvailable == ConnectionResult.SUCCESS) {
            return true;
        } else if (api.isUserResolvableError(isAvailable)) {
            Dialog dialog = api.getErrorDialog(this, isAvailable, 0);
            dialog.show();
        } else if (api.isUserResolvableError(isAvailable)) {
            Dialog dialog = api.getErrorDialog(this, isAvailable, 0);
            dialog.show();
        } else {
            Toast.makeText(this, "Can't connect to play services!", Toast.LENGTH_LONG).show();
        }

        return false;
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {

        this.googleMap = googleMap;
        googleMap.getUiSettings().setZoomControlsEnabled(true);

        if (this.googleMap != null) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
            changeMapType(prefs.getString("mapType", "2"));

            this.googleMap.setOnMarkerDragListener(new GoogleMap.OnMarkerDragListener() {
                @Override
                public void onMarkerDragStart(Marker marker) {
                    if (circle != null) {
                        circle.remove();
                    }
                }

                @Override
                public void onMarkerDrag(Marker marker) {
                }

                @Override
                public void onMarkerDragEnd(Marker marker) {
                    LatLng coordinates = marker.getPosition();
                    circle = drawCircle(coordinates);
                    double roundedLatitude = Math.round(coordinates.latitude * 100000.0) / 100000.0;
                    double roundedLongitude = Math.round(coordinates.longitude * 100000.0) / 100000.0;
                    setMarker(roundedLatitude, roundedLongitude);
                }
            });

            this.googleMap.setOnMapLongClickListener(new GoogleMap.OnMapLongClickListener() {
                @Override
                public void onMapLongClick(LatLng point) {
                    if (marker != null) {
                        MapsActivity.this.googleMap.clear();
                    }
                    double roundedLatitude = Math.round(point.latitude * 100000.0) / 100000.0;
                    double roundedLongitude = Math.round(point.longitude * 100000.0) / 100000.0;
                    setMarker(roundedLatitude, roundedLongitude);
                }
            });

            this.googleMap.setInfoWindowAdapter(new GoogleMap.InfoWindowAdapter() {

                @Override
                public View getInfoWindow(Marker marker) {
                    return null;
                }

                @Override
                public View getInfoContents(Marker marker) {
                    View v = getLayoutInflater().inflate(R.layout.info_window, null);
                    TextView tvLocality = (TextView) v.findViewById(R.id.tv_locality);
                    TextView tvLat = (TextView) v.findViewById(R.id.tv_lat);
                    TextView tvLng = (TextView) v.findViewById(R.id.tv_lng);
                    TextView tvSnippet = (TextView) v.findViewById(R.id.tv_snippet);
                    LatLng coordinates = marker.getPosition();
                    tvLocality.setText(marker.getTitle());
                    tvLat.setText("Latitude: " + coordinates.latitude);
                    tvLng.setText("Longitude: " + coordinates.longitude);
                    tvSnippet.setText(marker.getSnippet());
                    return v;
                }
            });

            this.googleMap.setOnInfoWindowClickListener(this);


        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            this.googleMap.setMyLocationEnabled(true);
            googleApiClient = new GoogleApiClient.Builder(this)       // This code is for updating current location
                    .addApi(LocationServices.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();
            googleApiClient.connect();
            zoom(15, 90, 40);
            if (MyStartActivity.selectedMarkerData != null && MyStartActivity.selectedMarkerData.isReal()) {
                setMarker(MyStartActivity.selectedMarkerData.getLatitude(), MyStartActivity.selectedMarkerData.getLongitude());
            }
            centerMap();
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, MY_PERMISSION_FINE_LOCATIONS);
            }
        }
    }

    private void zoom(float zoom, float bearing, float tilt) {
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        Criteria criteria = new Criteria();
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            Location location = locationManager.getLastKnownLocation(locationManager.getBestProvider(criteria, false));
            if (location != null) {
                googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(location.getLatitude(), location.getLongitude()), 13));
                CameraPosition cameraPosition = new CameraPosition.Builder()
                        .target(new LatLng(location.getLatitude(), location.getLongitude()))      // Sets the center of the map to location user
                        .zoom(zoom)                   // Sets the zoom
                        .bearing(bearing)                // Sets the orientation of the camera to east
                        .tilt(tilt)                   // Sets the tilt of the camera to 30 degrees
                        .build();                   // Creates a CameraPosition from the builder
                googleMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition));
            }
        }
    }

    private void centerMap() {
        Location location = googleMap.getMyLocation();
        if (marker != null) { // Go to marker location
            LatLng myLocation = new LatLng(marker.getPosition().latitude, marker.getPosition().longitude);
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(myLocation, 12));
        } else if (location != null) { // Go to current location
            LatLng myLocation = new LatLng(location.getLatitude(), location.getLongitude());
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(myLocation, 12));
        } else { // Go to Europe
            LatLng coordinates = new LatLng(56.54204, 13.36096);
            CameraUpdate camUpdate = CameraUpdateFactory.newLatLngZoom(coordinates, 3);
            googleMap.moveCamera(camUpdate);
        }
    }

    private void goToLocationZoom(double lat, double lng, float zoom) {
        LatLng coordinates = new LatLng(lat, lng);
        CameraUpdate camUpdate = CameraUpdateFactory.newLatLngZoom(coordinates, zoom);
        googleMap.moveCamera(camUpdate);
    }

    public void geoLocate(@SuppressWarnings("unused") View view) {
        Log.d("geoLocate", "addressName:" + addressName);
        checkGPS();
        if (addressName != null) {
            double lat = addressGeo.latitude;
            double lng = addressGeo.longitude;
            double roundedLat = Math.round(lat * 100000.0) / 100000.0;
            double roundedLng = Math.round(lng * 100000.0) / 100000.0;
            goToLocationZoom(lat, lng, 15);
            setMarker(roundedLat, roundedLng);
        } else {
            Toast.makeText(this, "No such location found. \nTry a different keyword.", Toast.LENGTH_LONG).show();
        }
    }

    void setMarker(double lat, double lng) {
        Log.d("setMarker", "");
        checkGPS();
        checkAndConnect();
        clearMarker();  // If marker has a reference, remove it.
        MarkerOptions options = new MarkerOptions()                 // This MarkerOptions object is needed to add a marker.
                .draggable(true)
                .icon(BitmapDescriptorFactory.fromResource(R.drawable.alarm_marker_40))      // Here it is possible to specify custom icon design.
                .position(new LatLng(lat, lng));
        marker = googleMap.addMarker(options);
        circle = drawCircle(new LatLng(lat, lng));
        startLocationRequest();
    }

    private Circle drawCircle(LatLng latLng) {
        CircleOptions circleOptions = new CircleOptions()
                .center(latLng)
                .radius(alarmRadius)
                .fillColor(0x33FF0000)              // 33 for alpha (transparency)
                .strokeColor(Color.BLUE)
                .strokeWidth(3);
        return googleMap.addCircle(circleOptions);
    }

    private void clearMarker() {
        if (marker != null)
            marker = null;
        if (circle != null)
            circle = null;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu, menu);
        return super.onCreateOptionsMenu(menu);         // More on this line: http://stackoverflow.com/questions/10303898/oncreateoptionsmenu-calling-super
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Intent intent;
        switch (item.getItemId()) {
            case R.id.menuItemSettings:
                intent = new Intent(this, MyPreferencesActivity.class);
                startActivity(intent);
                return true;
            case R.id.menuItemHelp:
                intent = new Intent(this, MyHelpActivity.class);
                startActivity(intent);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    public void onInfoWindowClick(Marker marker) {
        View myView = (LayoutInflater.from(this)).inflate(R.layout.input_name, null);
        final AlertDialog.Builder alertBuilder = new AlertDialog.Builder(this);
        alertBuilder.setView(myView);
        final EditText userInput = (EditText) myView.findViewById(R.id.etxtInputName);
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(userInput.getWindowToken(), 0);
        alertBuilder.setCancelable(true)
                .setTitle("Save Alarm")
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        String name = userInput.getText().toString();

                        if (TextUtils.isEmpty(name) || TextUtils.getTrimmedLength(name) < 1) {
                            Toast.makeText(MapsActivity.this, "Empty name not allowed. \nPlease try again.", Toast.LENGTH_LONG).show();
                            return;
                        }

                        for (MarkerData markerData : markerDataList) {
                            if (markerData.getName().equals(name)) {
                                Toast.makeText(MapsActivity.this, "Duplicate name not allowed. \nPlease try again with a unique name.", Toast.LENGTH_LONG).show();
                                return;
                            }
                        }
                        addMarkerDataToList(name, MapsActivity.marker);
                        MapsActivity.this.marker.hideInfoWindow();
                    }
                });
        Dialog myDialog = alertBuilder.create();
        myDialog.show();
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        Log.d("onConnected", "");
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            if (locationRequest != null)
                LocationServices.FusedLocationApi.requestLocationUpdates(googleApiClient, locationRequest, this);
        }
    }

    @Override
    public void onConnectionSuspended(int i) {
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
    }

    double haversine(double lat1, double lon1, double lat2, double lon2) {
        float[] results = new float[1];
        Location.distanceBetween(lat1, lon1, lat2, lon2, results);
        return results[0] / 1000;
    }

    private void setMarkerData() {
        if (marker != null) {
            MyStartActivity.selectedMarkerData.setLatitude(marker.getPosition().latitude);
            MyStartActivity.selectedMarkerData.setLongitude(marker.getPosition().longitude);
        }
    }

    private void showPopup() {
        Log.d("showPopup", "");
        addNotification("GPS alarm","You have arrived!");
        LayoutInflater inflater = (LayoutInflater) MapsActivity.this
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View layout = inflater.inflate(R.layout.screen_popup,
                (ViewGroup) findViewById(R.id.popup_element));
        popupWindow = new PopupWindow(layout, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popupWindow.showAtLocation(layout, Gravity.CENTER, 0, 0);

        popupWindow.setOutsideTouchable(false);                                         //Dobavlenij kod 16.02.2017
        popupWindow.setFocusable(false);                             // esli nado 4tob okno zakrivalosj pri kasanii vne ego, udalitj eti dve strochki

        popupWindow.setOnDismissListener(new PopupWindow.OnDismissListener() {       //Dobavlenij kod 16.02.2017
            @Override
            public void onDismiss() {
                mediaPlayer.pause();
                clearMarker();
                userNotified = false;
                popupWindow.dismiss();
            }
        });
        closePopUp = (Button) layout.findViewById(R.id.btn_close_popup);
        closePopUp.setOnClickListener(cancel_button_click_listener);
    }

    public void changeMapType(String type) {
        switch (type) {
            case "1":
                googleMap.setMapType(GoogleMap.MAP_TYPE_NONE);
                break;
            case "2":
                googleMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
                break;
            case "3":
                googleMap.setMapType(GoogleMap.MAP_TYPE_TERRAIN);
                break;
            case "4":
                googleMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE);
                break;
            case "5":
                googleMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                break;
        }
    }

    public void setAlarmRadius(int newRadius) {
        alarmRadius = newRadius;
    }

    public void initSound() {
        mediaPlayer = MediaPlayer.create(this, Uri.parse(ringtonePath));
    }

    public void addMarkerDataToList(String name, Marker marker) {
        MarkerData toBeAdded = new MarkerData();
        toBeAdded.setName(name);
        toBeAdded.setLatitude(marker.getPosition().latitude);
        toBeAdded.setLongitude(marker.getPosition().longitude);
        markerDataList.add(toBeAdded);
        saveMarkerDataList();
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case MY_PERMISSION_FINE_LOCATIONS:
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        googleMap.setMyLocationEnabled(true);
                        googleApiClient = new GoogleApiClient.Builder(this)       // This code is for updating current location
                                .addApi(LocationServices.API)
                                .addConnectionCallbacks(this)
                                .addOnConnectionFailedListener(this)
                                .build();
                        googleApiClient.connect();
                    }
                } else {
                    Toast.makeText(getApplicationContext(), "this app requires location permissions to be granted", Toast.LENGTH_LONG).show();
                    ActivityCompat.finishAffinity(MapsActivity.this);
                    System.exit(0);
                }
                break;
        }
    }

    private void buildAlertMessageNoGps() {
        Log.d("buildAlertMessageNoGps", "");
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage("Your GPS seems to be disabled, do you want to enable it?\n" + "\"If no, programm will be closed.\"")
                .setCancelable(false)
                .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                    public void onClick(@SuppressWarnings("unused") final DialogInterface dialog, @SuppressWarnings("unused") final int id) {
                        startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                    }
                })
                .setNegativeButton("No", new DialogInterface.OnClickListener() {
                    public void onClick(final DialogInterface dialog, @SuppressWarnings("unused") final int id) {
                        dialog.cancel();
                        ActivityCompat.finishAffinity(MapsActivity.this);
                        System.exit(0);
                    }
                });
        final AlertDialog alert = builder.create();
        alert.show();
    }

    private void buildAlertMessageNoWifi() {
        Log.d("buildAlertMessageNoWifi", "");
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage("Your Wi-Fi seems to be disabled, do you want to enable it?\n" + "\"If wi-fi not available, please connect via mobile data\"")
                .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                    public void onClick(@SuppressWarnings("unused") final DialogInterface dialog, @SuppressWarnings("unused") final int id) {
                        enableWiFi();
                    }
                })
                .setNegativeButton("No", new DialogInterface.OnClickListener() {
                    public void onClick(final DialogInterface dialog, @SuppressWarnings("unused") final int id) {
                        dialog.cancel();
                    }
                });
        final AlertDialog alert = builder.create();
        alert.show();
    }

    public void stopTrackingBut(@SuppressWarnings("unused") View view) {
        // Reinitialize alarm time
        alarm = new TrackerAlarmReceiver();
        if (isTracking) {
            // TODO should disable Pause/Start buttons when arrived
            stopLocationRequest();
            Toast.makeText(MapsActivity.this, "Tracking paused.", Toast.LENGTH_SHORT).show();
            Log.i("stopTrackingBut", "paused");
        } else {
            interval = 1000; // reset to smallest interval, to start with precise coordinate calculation
            startLocationRequest();
            Toast.makeText(MapsActivity.this, "Tracking restored.", Toast.LENGTH_SHORT).show();
            Log.i("stopTrackingBut", "started");
        }
    }

    void startLocationRequest() {
        isTracking = true;
        userNotified = false;
        checkGPS();
        renewLocationRequest();
        addNotification("GPS alarm","Tracking is started");
        Button button = (Button) findViewById(R.id.button4);
        button.setBackgroundColor(Color.RED);
        button.setText("Pause tracking");
        Log.d("startLocationRequest", "" +
                "\ninterval:" + String.valueOf(locationRequest.getInterval()) +
                "\nfastest interval:" + String.valueOf(locationRequest.getFastestInterval()));
    }

    void renewLocationRequest() {
        locationRequest = new LocationRequest();
        switch (getEstimate()) {
            case DISABLED:
                locationRequest.setPriority(LocationRequest.PRIORITY_NO_POWER);
                locationRequest.setNumUpdates(1); // Do just one update before going to sleep
                break;
            case FAR:
                locationRequest.setPriority(LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY);
                locationRequest.setNumUpdates(1);
                break;
            case NEAR:
                locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        }
        locationRequest.setInterval(interval);
        locationRequest.setFastestInterval(interval);
        Log.d("renewLocationRequest", "renewed");
        alarm.setAlarm(this);
    }

    void stopLocationRequest() {
        if (googleApiClient != null && googleApiClient.isConnected()) {
            LocationServices.FusedLocationApi.removeLocationUpdates(googleApiClient, this);
            googleApiClient.disconnect();
        }
        locationRequest = null;
        isTracking = false;
        alarm.cancelAlarm(this);
        alarm.releaseWakeLock();
        Button button = (Button) findViewById(R.id.button4);
        button.setBackgroundColor(Color.GREEN);
        button.setText("Start tracking");
        Log.i("stopLocationRequest", "stopped successfully");
    }

    public void enableWiFi() {
        Log.d("enableWiFi", "");
        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wifiManager.setWifiEnabled(true);
        startActivity(new Intent(WifiManager.ACTION_PICK_WIFI_NETWORK));
        Toast.makeText(getApplicationContext(), "Wi-fi connecting..", Toast.LENGTH_LONG).show();
    }

    private void checkGPS() {
        Log.d("checkGPS", "");
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
            buildAlertMessageNoGps();
    }

    public void checkAndConnect() {
        if (!checkedWiFi) {
            ConnectivityManager cm = (ConnectivityManager) this.getSystemService(Context.CONNECTIVITY_SERVICE);
            // test for connection
            if (cm != null) {
                if (!(cm.getActiveNetworkInfo() != null && cm.getActiveNetworkInfo().isAvailable() && cm.getActiveNetworkInfo().isConnected())) {
                    buildAlertMessageNoWifi();
                }
            }
            checkedWiFi = true;
            Log.d("checkAndConnect", "checkedWiFi");
        }
    }

    public void addNotification(String title, String text) {
        Notification.Builder mBuilder =
                new Notification.Builder(this)
                        .setSmallIcon(R.drawable.ic_launcher_web)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setAutoCancel(true)
                        .setPriority(Notification.PRIORITY_MIN);
        Intent resultIntent = new Intent(this,
                MapsActivity.class);
        resultIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                resultIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        mBuilder.setContentIntent(pendingIntent);
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID, mBuilder.build());

    }

    @Override
    // Invoked when location change event occurs
    public void onLocationChanged(Location location) {
        if (getEstimate() == Estimate.DISABLED) {
            Log.e("onLocationChanged", "Tracking disabled, but onLocationChanged() still called");
            return;
        }
        if (location == null) {
            Log.e("onLocationChanged", "Can't get current location");
            return;
        }
        if (marker == null) {
            Log.e("onLocationChanged", "Can't get location marker");
            return;
        }
        double distance = 0;
        double lat = location.getLatitude();
        double lon = location.getLongitude();
        // FIXME — this is just for testing purposes!!!
        maximumSpeed = maximumSpeed * 1.1f;
        alarmRadius = alarmRadius * 1.1f;
        //
        distance = haversine(lat, lon, marker.getPosition().latitude, marker.getPosition().longitude);
        // Check if reached destination
        if (distance <= alarmRadius / 1000) {
            if (!userNotified) {
                alarm.acquireLock();
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                v.vibrate(500);
                mediaPlayer.start();
                showPopup();
                userNotified = true;
                stopLocationRequest();
                NotificationManager notificationManager =
                        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                notificationManager.cancel(NOTIFICATION_ID);
                Log.d("user", "notified");
            }
            Log.i("onLocationChanged", "destination reached");
        } else {
            // Else set interval for location, depending on distance
            interval = (long) (3600_000 * distance / maximumSpeed);
            if (interval < 1000) interval = 1000; // preserve minimal interval to 1s
            if (getEstimate() == Estimate.FAR)
                alarm.releaseWakeLock();
        }
        Log.d("onLocationChanged", "" +
                "\nmaxSpeed: " + String.valueOf(maximumSpeed) + "km/h" +
                "\nalarmRad: " + alarmRadius + "m" +
                "\ninterval: " + interval + "s" +
                "\ndistance: " + String.format("%.2f", distance) + "km" +
                "\naccuracy: " + location.getAccuracy() + "m" +
                "\nestimate: " + getEstimate());
    }

    /**
     * Return enumerated value of estimated distance, based on status and expected traveling time
     */
    Estimate getEstimate() {
        if (!isTracking)
            return Estimate.DISABLED; // tracking disabled
        else if (interval > 120_000)  // more than 2 minutes for ongoing trackinig
            return Estimate.FAR;
        return Estimate.NEAR;         // less than 2 minutes for ongoing, or new request
    }
}
