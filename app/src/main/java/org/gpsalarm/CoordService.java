package org.gpsalarm;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Vibrator;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;

/*
* This is service class which acquires GPS coordinates when started. It can work both in foreground
* and in background.
* */

public class CoordService extends Service implements
        GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener,
        LocationListener {

    private static final String TAG = "CoordService";

    private boolean currentlyProcessingLocation = false;
    private LocationRequest locationRequest;
    private GoogleApiClient googleApiClient;

    private double destinationLat;
    private double destinationLng;
    private double currentLat;
    private double currentLng;

    protected boolean isDestAvail = false;

    public CoordService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // if we are currently trying to get a location and the alarm manager has called this again,
        // no need to start processing a new location.
        if (!currentlyProcessingLocation) {
            currentlyProcessingLocation = true;
            startTracking();
            double tempLat = intent.getExtras().getDouble("DestLat");
            double tempLng = intent.getExtras().getDouble("DestLng");
            if (tempLat != 0 && tempLng != 0) {
                setDestinationLat(intent.getExtras().getDouble("DestLat"));
                setDestinationLng(intent.getExtras().getDouble("DestLng"));
                if (getDestinationLat() != 0 && getDestinationLng() != 0) {
                    isDestAvail = true;
                } else isDestAvail = false;
            }
        }

        return START_NOT_STICKY;
    }

    private void startTracking() {
        Log.d(TAG, "startTracking");

        if (GooglePlayServicesUtil.isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS) {

            googleApiClient = new GoogleApiClient.Builder(this)
                    .addApi(LocationServices.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();

            if (!googleApiClient.isConnected() || !googleApiClient.isConnecting()) {
                googleApiClient.connect();
            }
        } else {
            Log.e(TAG, "unable to connect to google play services.");
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location != null) {
            Log.e(TAG, "position: " + location.getLatitude() + ", " + location.getLongitude() + " accuracy: " + location.getAccuracy());
            Vibrator vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            vib.vibrate(new long[]{2000}, -1);

            setCurrentLat(location.getLatitude());
            setCurrentLng(location.getLongitude());

            if(isDestAvail){
                Log.d(TAG, "Calculating distance");
                double destination = haversine(getCurrentLat(), getCurrentLng(), getDestinationLat(), getDestinationLng());
                Log.d(TAG, "Destination: " + destination);
                if(destination>0.0d && destination<=500.0d){
                    //sound alarm
                    Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                    v.vibrate(new long[]{0, 500, 500, 500}, -1);
                }
            }


            Intent i = new Intent("LOCATION_UPDATE");
            i.putExtra("latitude",location.getLatitude());
            i.putExtra("longitude",location.getLongitude());
            sendBroadcast(i);

            if (location.getAccuracy() < 500.0f) {
            }
        }
    }

    private void stopLocationUpdates() {
        if (googleApiClient != null && googleApiClient.isConnected()) {
            googleApiClient.disconnect();
        }
    }

    /**
     * Called by Location Services when the request to connect the
     * client finishes successfully. At this point, you can
     * request the current location or start periodic updates
     */
    @Override
    public void onConnected(Bundle bundle) {
        Log.d(TAG, "onConnected");

        locationRequest = LocationRequest.create();
        locationRequest.setInterval(1*60*1000); // milliseconds
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

        try{
            LocationServices.FusedLocationApi.requestLocationUpdates(
                    googleApiClient, locationRequest, this);
        } catch (SecurityException e){
            e.printStackTrace();
        }
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        Log.e(TAG, "onConnectionFailed");

        stopLocationUpdates();
        stopSelf();
    }

    @Override
    public void onConnectionSuspended(int i) {
        Log.e(TAG, "GoogleApiClient connection has been suspended");
    }

    double haversine(double lat1, double lon1, double lat2, double lon2) {
        float[] results = new float[1];
        Location.distanceBetween(lat1, lon1, lat2, lon2, results);
        return results[0];
    }

    public double getDestinationLat() {
        return destinationLat;
    }

    public void setDestinationLat(double destinationLat) {
        this.destinationLat = destinationLat;
    }

    public double getDestinationLng() {
        return destinationLng;
    }

    public void setDestinationLng(double destinationLng) {
        this.destinationLng = destinationLng;
    }

    public void setCurrentLat(double currentLat){
        this.currentLat = currentLat;
    }

    public double getCurrentLat(){
        return currentLat;
    }

    public void setCurrentLng(double currentLng){
        this.currentLng = currentLng;
    }

    public double getCurrentLng(){
        return currentLng;
    }
}
