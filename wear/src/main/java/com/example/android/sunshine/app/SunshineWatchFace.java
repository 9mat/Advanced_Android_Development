/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {

    private static final String TAG = SunshineWatchFace.class.getSimpleName();
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    private static final Typeface LIGHT_TYPEFACE =
            Typeface.create("sans-serif-light", Typeface.NORMAL);

    private static final String SUNSHINE_PATH = "/sunshine";
    private static final String HIGH_TEMP_KEY = "HIGH_TEMP";
    private static final String LOW_TEMP_KEY = "LOW_TEMP";
    private static final String WEATHER_ID_KEY = "WEATHER_ID";

    private int mHighTemp = 41;
    private int mLowTemp = 37;
    private int mWeatherId = 0;
    private Bitmap mBitmap;


    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler  {

        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements
            DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener {

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        boolean mAmbient;

        private Paint mBackgroundPaint;
        private Paint mHourPaint, mMinutePaint, mColonPaint, mDateTextPaint, mHighTempPaint, mLowTempPaint, mLinePaint, mIconPaint;

        // Y is offset from the top
        private float mYDateOffset, mYTempOffset, mYLineOffset, mYIconOffset;

        // note: X is offset from the center (bounds.centerX())
        private float mXHourOffset, mXColonOffset, mXMinuteOffset, mXIconOffset, mXHighTempOffset, mXLowTempOffset;

        private float mColonWidth, mHourWidth, mHighTempWidth, mTempPadding;
        private float mDateHeight, mTempHeight;

        private static final String SAMPLE_HOUR = "00";
        private static final String SAMPLE_TEMP = "88" + (char) 0x00B0;
        private static final String SAMPLE_DATE = "Thu, Jun 22 2016";

        private static final float PADDING_DATE_SCALE = 2f;
        private static final float PADDING_TEMP_SCALE = 3f;
        private static final float ICON_SCALING_FACTOR = 2f;

        Calendar mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime = new GregorianCalendar(TimeZone.getTimeZone(intent.getStringExtra("time-zone")));
            }
        };

        private static final String DATA_REQUEST_PATH = "/weather-request";
        private String remoteNodeId;

        int mTapCount;

        float mXOffset;
        float mYTimeOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        private GoogleApiClient googleApiClient;

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d(TAG, "onDataChanged " + dataEventBuffer);
            for(DataEvent event: dataEventBuffer) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    processDataItem(event.getDataItem());
                }
            }

            dataEventBuffer.release();
        }

        private void processDataItem(DataItem item) {
            if (SUNSHINE_PATH.equals(item.getUri().getPath())) {
                DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                if (dataMap.containsKey(HIGH_TEMP_KEY)) mHighTemp = dataMap.getInt(HIGH_TEMP_KEY);
                if (dataMap.containsKey(LOW_TEMP_KEY)) mLowTemp = dataMap.getInt(LOW_TEMP_KEY);
                if (dataMap.containsKey(WEATHER_ID_KEY)) { // && mWeatherId != dataMap.getInt(WEATHER_ID_KEY)) {
                    mWeatherId = dataMap.getInt(WEATHER_ID_KEY);
                    Log.d(TAG, "Retrieve Bitmap " + mWeatherId);
                    mBitmap = ((BitmapDrawable) getResources().getDrawable(getIconResourceForWeatherCondition(mWeatherId))).getBitmap();
                }
                if (dataMap.containsKey("timestamp")) dataMap.getLong("timestamp");
            }
        }

        private void sendDataRequestMessage() {
            Wearable.MessageApi.sendMessage(googleApiClient, remoteNodeId, DATA_REQUEST_PATH, null);
        }

        public int getIconResourceForWeatherCondition(int weatherId) {
            // Based on weather code data found at:
            // http://bugs.openweathermap.org/projects/api/wiki/Weather_Condition_Codes
            if (weatherId >= 200 && weatherId <= 232) return R.drawable.ic_storm;
            if (weatherId >= 300 && weatherId <= 321) return R.drawable.ic_light_rain;
            if (weatherId >= 500 && weatherId <= 504) return R.drawable.ic_rain;
            if (weatherId == 511) return R.drawable.ic_snow;
            if (weatherId >= 520 && weatherId <= 531) return R.drawable.ic_rain;
            if (weatherId >= 600 && weatherId <= 622) return R.drawable.ic_snow;
            if (weatherId >= 701 && weatherId <= 761) return R.drawable.ic_fog;
            if (weatherId == 761 || weatherId == 781) return R.drawable.ic_storm;
            if (weatherId == 800) return R.drawable.ic_clear;
            if (weatherId == 801) return R.drawable.ic_light_clouds;
            if (weatherId >= 802 && weatherId <= 804) return R.drawable.ic_cloudy;
            return -1;
        }


        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.d(TAG, "connected GoogleAPI");
            Wearable.DataApi.addListener(googleApiClient, this);
            Wearable.NodeApi.getConnectedNodes(googleApiClient).setResultCallback(new ResultCallback<NodeApi.GetConnectedNodesResult>() {
                @Override
                public void onResult(@NonNull NodeApi.GetConnectedNodesResult getConnectedNodesResult) {
                    if(getConnectedNodesResult.getStatus().isSuccess() && getConnectedNodesResult.getNodes().size() > 0) {
                        remoteNodeId = getConnectedNodesResult.getNodes().get(0).getId();
                    }
                }
            });
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.e(TAG, "suspended GoogleAPI");
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.e(TAG, "connectionFailed GoogleAPI" + connectionResult);
        }

        private void releaseGoogleApiClient() {
            if (googleApiClient != null && googleApiClient.isConnected()) {
                Wearable.DataApi.removeListener(googleApiClient, this);
                googleApiClient.disconnect();
            }
        }

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());

            googleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();

            Resources resources = SunshineWatchFace.this.getResources();
            mYTimeOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mHourPaint = createTextPaint(resources.getColor(R.color.digital_text), NORMAL_TYPEFACE);
            mMinutePaint = createTextPaint(resources.getColor(R.color.digital_text), LIGHT_TYPEFACE);
            mColonPaint = createTextPaint(resources.getColor(R.color.disabled_text_light), NORMAL_TYPEFACE);
            mDateTextPaint = createTextPaint(resources.getColor(R.color.disabled_text_light), LIGHT_TYPEFACE);
            mHighTempPaint = createTextPaint(resources.getColor(R.color.digital_text), NORMAL_TYPEFACE);
            mLowTempPaint = createTextPaint(resources.getColor(R.color.disabled_text_light), LIGHT_TYPEFACE);

            mLinePaint = new Paint();
            mLinePaint.setColor(resources.getColor(R.color.disabled_text_light));
            mLowTempPaint.setAntiAlias(true);

            mIconPaint = new Paint();
            mIconPaint.setAntiAlias(true);

            mTime = new GregorianCalendar(TimeZone.getDefault());
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            releaseGoogleApiClient();
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                // Update time zone in case it changed while we weren't visible.
                mTime = new GregorianCalendar(TimeZone.getDefault());
                googleApiClient.connect();
            } else {
                unregisterReceiver();
                releaseGoogleApiClient();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float timeTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_time_text_size_round : R.dimen.digital_time_text_size);
            float dateTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_date_text_size_round : R.dimen.digital_date_text_size);
            float tempTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_temp_text_size_round : R.dimen.digital_temp_text_size);

            mHourPaint.setTextSize(timeTextSize);
            mMinutePaint.setTextSize(timeTextSize);
            mColonPaint.setTextSize(timeTextSize);

            mDateTextPaint.setTextSize(dateTextSize);

            mHighTempPaint.setTextSize(tempTextSize);
            mLowTempPaint.setTextSize(tempTextSize);

            calculateBounds();
        }

        private void calculateBounds(){
            mColonWidth = mColonPaint.measureText(":");
            mHighTempWidth = mHighTempPaint.measureText(SAMPLE_TEMP);
            mHourWidth = mHourPaint.measureText(SAMPLE_HOUR);
            mTempPadding = mHighTempWidth/3;

            Rect textBounds = new Rect();

            mHourPaint.getTextBounds(SAMPLE_HOUR,0, 1, textBounds);

            mHighTempPaint.getTextBounds(SAMPLE_TEMP, 0, 1, textBounds);
            mTempHeight = textBounds.height();

            mDateTextPaint.getTextBounds(SAMPLE_DATE, 0, SAMPLE_DATE.length()-1, textBounds);
            mDateHeight = textBounds.height();

            mYDateOffset = mYTimeOffset + mDateHeight* PADDING_DATE_SCALE;
            mYLineOffset = mYDateOffset + mTempHeight* (PADDING_TEMP_SCALE-1)/2;
            mYTempOffset = mYDateOffset + mTempHeight* PADDING_TEMP_SCALE;
            mYIconOffset = mYTempOffset - mTempHeight*(1+ICON_SCALING_FACTOR)/2;

            mXHourOffset = - mColonWidth/2 - mHourWidth;
            mXColonOffset = - mColonWidth/2;
            mXMinuteOffset = mColonWidth/2;

            mXIconOffset = - mHighTempWidth/2 - mTempPadding - mTempHeight*ICON_SCALING_FACTOR;
            mXHighTempOffset = - mHighTempWidth/2;
            mXLowTempOffset = mHighTempWidth/2 + mTempPadding;
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    boolean antiAlias = !inAmbientMode;
                    mHourPaint.setAntiAlias(antiAlias);
                    mColonPaint.setAntiAlias(antiAlias);
                    mMinutePaint.setAntiAlias(antiAlias);
                    mDateTextPaint.setAntiAlias(antiAlias);
                    mHighTempPaint.setAntiAlias(antiAlias);
                    mLowTempPaint.setAntiAlias(antiAlias);
                    mIconPaint.setAntiAlias(antiAlias);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = SunshineWatchFace.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    mTapCount++;
                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
                            R.color.background : R.color.background2));
                    sendDataRequestMessage();
                    break;
            }
            invalidate();
        }

        private void drawCenteredText(Canvas canvas, Rect bounds, Paint paint, float yOffset, String text) {
            canvas.drawText(text, bounds.centerX() - paint.measureText(text)/2, yOffset, paint);
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            mTime = new GregorianCalendar(mTime.getTimeZone());
            String timeText = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(mTime.getTime());
            String dateText = new SimpleDateFormat("EEE, MMM dd yyyy", Locale.getDefault()).format(mTime.getTime()).toUpperCase();

            String highTempText = String.valueOf(mHighTemp) + (char) 0x00B0;
            String lowTempText = String.valueOf(mLowTemp) + (char) 0x00B0;

            float centerX = bounds.centerX();

            canvas.drawText(timeText.substring(0,2), centerX + mXHourOffset, mYTimeOffset, mHourPaint);
            canvas.drawText(":", centerX + mXColonOffset, mYTimeOffset, mColonPaint);
            canvas.drawText(timeText.substring(3,5), centerX + mXMinuteOffset, mYTimeOffset, mMinutePaint);

            canvas.drawText(dateText, centerX - mDateTextPaint.measureText(dateText)/2, mYDateOffset, mDateTextPaint);

            canvas.drawLine(centerX - bounds.width()/10, mYLineOffset, centerX + bounds.width()/10,  mYLineOffset, mLinePaint);

            canvas.drawText(highTempText, centerX + mXHighTempOffset, mYTempOffset, mHighTempPaint);
            canvas.drawText(lowTempText, centerX + mXLowTempOffset, mYTempOffset, mLowTempPaint);

            if(mBitmap != null) {
                Log.d(TAG, "draw Bitmap");
                float intrinsicSize = 1f * mBitmap.getScaledHeight(canvas);
                float scale = mTempHeight*ICON_SCALING_FACTOR / intrinsicSize;
                Matrix matrix = new Matrix();
                matrix.setScale(scale, scale);
                matrix.postTranslate(centerX + mXIconOffset, mYIconOffset);
                canvas.drawBitmap(mBitmap, matrix, new Paint());
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
    }
}
