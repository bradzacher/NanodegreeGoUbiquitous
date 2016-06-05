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

package com.example.android.sunshine.wear;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.support.wearable.view.BoxInsetLayout;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;


/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFaceService extends CanvasWatchFaceService implements DataApi.DataListener, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
    public final static String TEMPERATURE_HIGH = "TEMP_HIGH";
    public final static String TEMPERATURE_LOW = "TEMP_LOW";
    public final static String TEMPERATURE_ICON_ASSET = "TEMP_ICON_ASSET";

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    private GoogleApiClient mGoogleApiClient;

    @Override
    public Engine onCreateEngine() {
        mGoogleApiClient = new GoogleApiClient.Builder(getApplicationContext())
                .addApi(Wearable.API)
                .addOnConnectionFailedListener(this)
                .addConnectionCallbacks(this).build();
        mGoogleApiClient.connect();

        return new Engine();
    }

    @Override
    public void onConnected(Bundle bundle) {
        Wearable.DataApi.addListener(mGoogleApiClient, this);
    }

    @Override
    public void onConnectionSuspended(int i) {

    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

    }

    int    highTemp, lowTemp;
    Asset  temperatureIconAsset;
    Bitmap temperatureIcon;

    @Override
    public void onDataChanged(DataEventBuffer dataEventBuffer) {
        for (DataEvent event : dataEventBuffer) {
            if (event.getType() == DataEvent.TYPE_CHANGED) {
                DataItem dataItem = event.getDataItem();

                DataMap dataMap = DataMapItem.fromDataItem(dataItem).getDataMap();

                highTemp = dataMap.getInt(TEMPERATURE_HIGH);
                lowTemp = dataMap.getInt(TEMPERATURE_LOW);
                temperatureIconAsset = dataMap.getAsset(TEMPERATURE_ICON_ASSET);
                // get the bitmap
                Wearable.DataApi.getFdForAsset(mGoogleApiClient, temperatureIconAsset).setResultCallback(new ResultCallback<DataApi.GetFdForAssetResult>() {
                    @Override
                    public void onResult(@NonNull DataApi.GetFdForAssetResult getFdForAssetResult) {
                        InputStream inputStream = getFdForAssetResult.getInputStream();
                        temperatureIcon = BitmapFactory.decodeStream(inputStream);
                    }
                });
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        static final int MSG_UPDATE_TIME = 0;

        /**
         * Handler to update the time periodically in interactive mode.
         */
        @SuppressLint("HandlerLeak")
        final Handler mUpdateTimeHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_UPDATE_TIME:
                        invalidate();
                        if (shouldTimerBeRunning()) {
                            long timeMs = System.currentTimeMillis();
                            long delayMs = INTERACTIVE_UPDATE_RATE_MS
                                           - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                            mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
                        }
                        break;
                }
            }
        };

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Engine.this.updateTimeZone(intent.getStringExtra("time-zone"));
            }
        };
        public void updateTimeZone(String timeZoneId) {
            this.updateTimeZone(TimeZone.getTimeZone(timeZoneId));
        }
        public void updateTimeZone(TimeZone timeZone) {
            mTimeZone = timeZone;
        }

        boolean mRegisteredTimeZoneReceiver = false;

        boolean mAmbient;

        TimeZone mTimeZone = TimeZone.getDefault();
        @SuppressLint("SimpleDateFormat")
        SimpleDateFormat mHourFormat = new SimpleDateFormat("HH");
        @SuppressLint("SimpleDateFormat")
        SimpleDateFormat mMinuteFormat = new SimpleDateFormat("mm");
        @SuppressLint("SimpleDateFormat")
        SimpleDateFormat mDateFormat = new SimpleDateFormat("EEE, MMM, dd yyyy");

        float mXOffset = 0;
        float mYOffset = 0;

        private int specW, specH;
        private View myLayout;
        private TextView hour_text, minute_text, date_text, temperature_high_text, temperature_low_text;
        private BoxInsetLayout background_box;
        private LinearLayout weather_container;
        private ImageView temperature_icon;

        private final Point displaySize = new Point();

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @SuppressLint("InflateParams")
        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFaceService.this)
                                      .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                                      .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                                      .setShowSystemUiTime(false)
                                      .build());

            // Inflate the layout that we're using for the watch face
            LayoutInflater inflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            myLayout = inflater.inflate(R.layout.watch_face, null);

            // Load the display spec - we'll need this later for measuring myLayout
            Display display = ((WindowManager) getSystemService(Context.WINDOW_SERVICE))
                    .getDefaultDisplay();
            display.getSize(displaySize);

            // Find some views for later use
            hour_text = (TextView) myLayout.findViewById(R.id.hour_text);
            minute_text = (TextView) myLayout.findViewById(R.id.minute_text);
            date_text = (TextView) myLayout.findViewById(R.id.date_text);
            temperature_high_text = (TextView) myLayout.findViewById(R.id.temperature_high);
            temperature_low_text = (TextView) myLayout.findViewById(R.id.temperature_low);
            background_box = (BoxInsetLayout) myLayout.findViewById(R.id.box);
            temperature_icon = (ImageView) myLayout.findViewById(R.id.temperature_icon);
            weather_container = (LinearLayout) myLayout.findViewById(R.id.weather_container);
            weather_container.setVisibility(View.INVISIBLE);
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                updateTimeZone(TimeZone.getDefault());
            } else {
                unregisterReceiver();
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
            SunshineWatchFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            if (insets.isRound()) {
                // Shrink the face to fit on a round screen
                mYOffset = mXOffset = displaySize.x * 0.1f;
                displaySize.y -= 2 * mXOffset;
                displaySize.x -= 2 * mXOffset;
            } else {
                mXOffset = mYOffset = 0;
            }

            // Recompute the MeasureSpec fields - these determine the actual size of the layout
            specW = View.MeasureSpec.makeMeasureSpec(displaySize.x, View.MeasureSpec.EXACTLY);
            specH = View.MeasureSpec.makeMeasureSpec(displaySize.y, View.MeasureSpec.EXACTLY);
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

                // Show/hide the seconds fields
                if (inAmbientMode) {
                    background_box.setBackgroundColor(ContextCompat.getColor(SunshineWatchFaceService.this, R.color.background_ambient));
                } else {
                    background_box.setBackgroundColor(ContextCompat.getColor(SunshineWatchFaceService.this, R.color.background_active));
                }

                // Switch between bold & normal font
                Typeface font = Typeface.create("sans-serif-condensed",
                                                inAmbientMode ? Typeface.NORMAL : Typeface.BOLD);
                ViewGroup group = (ViewGroup) myLayout;
                for (int i = group.getChildCount() - 1; i >= 0; i--) {
                    // We only get away with this because every child is a TextView
                    ((TextView) group.getChildAt(i)).setTypeface(font);
                }

                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Get the current Time
            Date currentDate = Calendar.getInstance(mTimeZone).getTime();

            // Apply it to the date field
            date_text.setText(mDateFormat.format(currentDate));

            // Apply it to the time fields
            hour_text.setText(mHourFormat.format(currentDate));
            minute_text.setText(mMinuteFormat.format(currentDate));

            // Update the temps
            temperature_high_text.setText(getResources().getString(R.string.formatted_temperature, highTemp));
            temperature_low_text.setText(getResources().getString(R.string.formatted_temperature, lowTemp));
            temperature_icon.setImageBitmap(temperatureIcon);

            // Update the layout
            myLayout.measure(specW, specH);
            myLayout.layout(0, 0, myLayout.getMeasuredWidth(), myLayout.getMeasuredHeight());


            // Draw it to the Canvas
            canvas.drawColor(Color.BLACK);
            canvas.translate(mXOffset, mYOffset);
            myLayout.draw(canvas);
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
    }
}