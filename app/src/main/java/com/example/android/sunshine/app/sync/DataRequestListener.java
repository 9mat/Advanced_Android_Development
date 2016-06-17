package com.example.android.sunshine.app.sync;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.util.Log;

import com.example.android.sunshine.app.R;
import com.example.android.sunshine.app.Utility;
import com.example.android.sunshine.app.data.WeatherContract;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.nio.ByteBuffer;

/**
 * Created by 9mat on 16/6/2016.
 * Listen to message from wearable and reply with data request from WeatherProvider
 */
public class DataRequestListener extends WearableListenerService {
    public static final String DATA_REQUEST_ACTION = "com.example.android.sunshine.weather.request";
    private static final String TAG = DataRequestListener.class.getSimpleName();

    public static final String HIGH_TEMP_KEY = "HIGH_TEMP";
    public static final String LOW_TEMP_KEY = "LOW_TEMP";
    public static final String WEATHER_ID_KEY = "WEATHER_ID";

    private GoogleApiClient mGoogleApiClient = null;

    @Override
    public void onCreate() {
        super.onCreate();
        mGoogleApiClient = Utility.getGoogleApiClient(getApplicationContext());
        mGoogleApiClient.connect();
    }

    @Override
    public void onDestroy() {
        mGoogleApiClient.disconnect();
        super.onDestroy();
    }

    private static final String[] FORECAST_COLUMNS = {
            WeatherContract.WeatherEntry.TABLE_NAME + "." + WeatherContract.WeatherEntry._ID,
            WeatherContract.WeatherEntry.COLUMN_DATE,
            WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
            WeatherContract.WeatherEntry.COLUMN_MIN_TEMP,
            WeatherContract.WeatherEntry.COLUMN_WEATHER_ID,
    };

    // These indices are tied to FORECAST_COLUMNS.  If FORECAST_COLUMNS changes, these
    // must change.
    static final int COL_WEATHER_MAX_TEMP = 2;
    static final int COL_WEATHER_MIN_TEMP = 3;
    static final int COL_WEATHER_CONDITION_ID = 4;


    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        String sortOrder = WeatherContract.WeatherEntry.COLUMN_DATE + " ASC";
        String locationSetting = Utility.getPreferredLocation(getApplicationContext());
        Uri weatherForLocationUri = WeatherContract.WeatherEntry
                .buildWeatherLocationWithStartDate(locationSetting, System.currentTimeMillis());

        long timeStamp = 0;
        if(messageEvent.getData() != null) {
            ByteBuffer buffer = ByteBuffer.wrap(messageEvent.getData());
            if(buffer.remaining() >= 8) {
                timeStamp = ByteBuffer.wrap(messageEvent.getData()).getLong();
            }
        }

        Cursor cursor = getContentResolver().query(weatherForLocationUri, FORECAST_COLUMNS, null, null, sortOrder);

        if(cursor != null && cursor.moveToFirst()) {
            int high = (int) cursor.getDouble(COL_WEATHER_MAX_TEMP);
            int low = (int) cursor.getDouble(COL_WEATHER_MIN_TEMP);
            int weatherId = cursor.getInt(COL_WEATHER_CONDITION_ID);

            Log.d(TAG, "Data exists. Start sending data to wearable.");
            sendDataToWearable(mGoogleApiClient, high, low, weatherId, timeStamp);
        } else {
            Log.d(TAG, "Data do not exist. Request sync from ContentResolver.");
            Bundle settingsBundle = new Bundle();
            settingsBundle.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
            settingsBundle.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);

            ContentResolver.requestSync(SunshineSyncAdapter.getSyncAccount(getApplicationContext()),
                    getString(R.string.content_authority), settingsBundle);
        }

        if(cursor != null) cursor.close();
    }

    public static void sendDataToWearable(GoogleApiClient client, int high, int low, int weatherId, long timeStamp) {
        PutDataMapRequest putDataMapRequest = PutDataMapRequest.create("/sunshine");
        putDataMapRequest.getDataMap().putInt(HIGH_TEMP_KEY, high);
        putDataMapRequest.getDataMap().putInt(LOW_TEMP_KEY, low);
        putDataMapRequest.getDataMap().putInt(WEATHER_ID_KEY, weatherId);
        putDataMapRequest.getDataMap().putLong("timestamp", timeStamp);

        PutDataRequest request = putDataMapRequest.asPutDataRequest();
        Wearable.DataApi.putDataItem(client, request).setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
            @Override
            public void onResult(@NonNull DataApi.DataItemResult dataItemResult) {
                if (dataItemResult.getStatus().isSuccess()){
                    Log.i(TAG, "Successfully sent to wearable");
                } else {
                    Log.i(TAG, "Couldn't  send to wearable");
                }
            }
        });

    }
}
