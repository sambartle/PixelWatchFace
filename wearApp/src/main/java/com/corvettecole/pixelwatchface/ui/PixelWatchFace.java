package com.corvettecole.pixelwatchface.ui;

import static com.corvettecole.pixelwatchface.util.Constants.INFO_BAR_Y_SPACING_RATIO;
import static com.corvettecole.pixelwatchface.util.Constants.KEY_WEATHER_JSON;
import static com.corvettecole.pixelwatchface.util.Constants.NO_TEMPERATURE;
import static com.corvettecole.pixelwatchface.util.Constants.WEATHER_BACKOFF_DELAY_ONETIME;
import static com.corvettecole.pixelwatchface.util.Constants.WEATHER_ICON_MARGIN_RATIO;
import static com.corvettecole.pixelwatchface.util.Constants.WEATHER_ICON_Y_OFFSET_RATIO;
import static com.corvettecole.pixelwatchface.util.Constants.WEATHER_UPDATE_INTERVAL;
import static com.corvettecole.pixelwatchface.util.Constants.WEATHER_UPDATE_WORKER;
import static com.corvettecole.pixelwatchface.util.WatchFaceUtil.drawableToBitmap;

import android.Manifest;
import android.Manifest.permission;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.complications.ComplicationData;
import android.support.wearable.complications.SystemProviders;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.lifecycle.Observer;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import com.corvettecole.pixelwatchface.R;
import com.corvettecole.pixelwatchface.models.Weather;
import com.corvettecole.pixelwatchface.util.Constants.UpdatesRequired;
import com.corvettecole.pixelwatchface.util.Settings;
import com.corvettecole.pixelwatchface.util.UnitLocale;
import com.corvettecole.pixelwatchface.util.WatchFaceUtil;
import com.corvettecole.pixelwatchface.workers.LocationUpdateWorker;
import com.corvettecole.pixelwatchface.workers.WeatherUpdateWorker;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.gson.Gson;
import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.concurrent.TimeUnit;


public class PixelWatchFace extends CanvasWatchFaceService {

  /**
   * Update rate in milliseconds for interactive mode. Defaults to one minute because the watch face
   * needs to update minutes in interactive mode.
   */
  private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.MINUTES.toMillis(1);

  /**
   * Handler message id for updating the time periodically in interactive mode.
   */
  private static final int MSG_UPDATE_TIME = 0;

  @Override
  public Engine onCreateEngine() {
    return new Engine();
  }

  private static class EngineHandler extends Handler {

    private final WeakReference<PixelWatchFace.Engine> mWeakReference;

    public EngineHandler(PixelWatchFace.Engine reference) {
      mWeakReference = new WeakReference<>(reference);
    }

    @Override
    public void handleMessage(Message msg) {
      PixelWatchFace.Engine engine = mWeakReference.get();
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
      DataClient.OnDataChangedListener {

    private final Bitmap mWearOSBitmap = drawableToBitmap(getDrawable(R.drawable.ic_wear_os_logo));

    private final Handler mUpdateTimeHandler = new EngineHandler(this);
    private final Bitmap mWearOSBitmapAmbient = drawableToBitmap(
        getDrawable(R.drawable.ic_wear_os_logo_ambient));
    private final int BATTERY_COMPLICATION_ID = 20;
    private final int STEP_COUNT_COMPLICATION_ID = 21;
    private final int WEATHER_COMPLICATION_ID = 22;
    private final String WEATHER_PROVIDER_SERVICE = "com.google.android.clockwork.home.weather.WeatherProviderService";

    Engine() {
      super(true); // hardware acceleration
    }

    private FusedLocationProviderClient mFusedLocationClient;
    private boolean mRegisteredTimeZoneReceiver = false;
    private Paint mBackgroundPaint;
    private Paint mTimePaint;
    private Paint mInfoPaint;
    private int mBatteryLevel;
    /**
     * Whether the display supports fewer bits for each color in ambient mode. When true, we disable
     * anti-aliasing in ambient mode.
     */
    private boolean mLowBitAmbient;
    private boolean mBurnInProtection;
    private boolean mAmbient;
    private boolean mIsRound;
    private int mChinSize;
    private long mPermissionRequestedTime = 0;
    private long mWeatherRequestedTime = 0;
    private Typeface mProductSans;
    private Typeface mProductSansThin;
    // TODO get rid of this singleton
    private Settings mSettings = Settings.getInstance(getApplicationContext());
    private Weather mCurrentWeather = new Weather();

    @Override
    public void onCreate(SurfaceHolder holder) {
      super.onCreate(holder);

      setDefaultSystemComplicationProvider(BATTERY_COMPLICATION_ID, SystemProviders.WATCH_BATTERY,
          ComplicationData.TYPE_RANGED_VALUE);
//      setDefaultComplicationProvider(WEATHER_COMPLICATION_ID,
//          new ComponentName("com.google.android.wearable.app", WEATHER_PROVIDER_SERVICE), ComplicationData.TYPE_ICON);
      //setDefaultSystemComplicationProvider(STEP_COUNT_COMPLICATION_ID, SystemProviders.STEP_COUNT, ComplicationData.TYPE_SHORT_TEXT);


      setWatchFaceStyle(new WatchFaceStyle.Builder(PixelWatchFace.this)
          .setHideNotificationIndicator(true)
          .setShowUnreadCountIndicator(true)
          .setStatusBarGravity(Gravity.CENTER_HORIZONTAL)
          .setStatusBarGravity(Gravity.TOP)
          .build());

      // Initializes syncing with companion app
      Wearable.getDataClient(getApplicationContext()).addListener(this);

      // Initializes background.
      mBackgroundPaint = new Paint();
      mBackgroundPaint.setColor(
          ContextCompat.getColor(getApplicationContext(), R.color.background));
      mProductSans = ResourcesCompat.getFont(getApplicationContext(), R.font.product_sans_regular);
      mProductSansThin = ResourcesCompat.getFont(getApplicationContext(), R.font.product_sans_thin);

      // Initializes Watch Face.
      mTimePaint = new Paint();
      mTimePaint.setTypeface(mProductSans);
      mTimePaint.setAntiAlias(true);
      mTimePaint.setColor(
          ContextCompat.getColor(getApplicationContext(), R.color.digital_text));
      mTimePaint.setStrokeWidth(3f);

      mInfoPaint = new Paint();
      mInfoPaint.setTypeface(mProductSans);
      mInfoPaint.setAntiAlias(true);
      mInfoPaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.digital_text));
      mInfoPaint.setStrokeWidth(2f);

      if (shouldSuggestSettings()) {
        setSuggestedSettings();
      }
    }

    @Override
    public void onDestroy() {
      mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
      Wearable.getDataClient(getApplicationContext()).removeListener(this);
      WorkManager.getInstance(getApplicationContext()).cancelAllWorkByTag(WEATHER_UPDATE_WORKER);
      super.onDestroy();
    }

    @Override
    public void onComplicationDataUpdate(int watchFaceComplicationId, ComplicationData data) {
      String TAG = "onComplicationDataUpdate";
      //Log.d(TAG, watchFaceComplicationId + ", type: " + data.getType());
      if (watchFaceComplicationId == BATTERY_COMPLICATION_ID) {
        mBatteryLevel = (int) data.getValue();
        invalidate();
      }

//      if (watchFaceComplicationId == WEATHER_COMPLICATION_ID) {
//
//        mCurrentWeather.setIconBitmap(Bitmap
//              .createScaledBitmap(drawableToBitmap(data.getIcon().loadDrawable(getApplicationContext())), 34, 34, false));
//          invalidate();
//      }

//      switch (data.getType()){
//        case ComplicationData.TYPE_RANGED_VALUE:
//          Log.d(TAG, "max " + data.getMaxValue() + " current " + data.getValue());
//          break;
//        case ComplicationData.TYPE_SHORT_TEXT:
//          Log.d(TAG, "data: " + data.getShortText().getText(getApplicationContext(), System.currentTimeMillis()).toString());
//          mCurrentWeather.setIconBitmap(Bitmap
//              .createScaledBitmap(drawableToBitmap(data.getIcon().loadDrawable(getApplicationContext())), 34, 34, false));
//          invalidate();
//          break;
//        case ComplicationData.TYPE_LONG_TEXT:
//          Log.d(TAG, data.getLongText().getText(getApplicationContext(), System.currentTimeMillis()).toString());
//          break;
//          case ComplicationData.TYPE_ICON:
//            Log.d(TAG, "icon time yeet");
//            mCurrentWeather.setIconBitmap(Bitmap
//                .createScaledBitmap(drawableToBitmap(data.getIcon().loadDrawable(getApplicationContext())), 34, 34, false));
//            invalidate();
//            break;
//      }

    }

    @Override
    public void onVisibilityChanged(boolean visible) {
      super.onVisibilityChanged(visible);

      if (visible) {
        registerReceivers();
        invalidate();
      } else {
        unregisterReceivers();
      }

      // Whether the timer should be running depends on whether we're visible (as well as
      // whether we're in ambient mode), so we may need to start or stop the timer.
      updateTimer();
    }

    private void registerReceivers() {
      if (mRegisteredTimeZoneReceiver) {
        return;
      }
      setActiveComplications(BATTERY_COMPLICATION_ID);
      mRegisteredTimeZoneReceiver = true;
      IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
    }

    private void unregisterReceivers() {
      if (!mRegisteredTimeZoneReceiver) {
        return;
      }
      mRegisteredTimeZoneReceiver = false;
    }

    @Override
    public void onApplyWindowInsets(WindowInsets insets) {
      super.onApplyWindowInsets(insets);
      Log.d("onApplyWindowInsets",
          "onApplyWindowInsets: " + (insets.isRound() ? "round" : "square"));

      // Load resources that have alternate values for round watches.
      Resources resources = PixelWatchFace.this.getResources();
      mIsRound = insets.isRound();
      mChinSize = insets.getSystemWindowInsetBottom();

      float timeTextSize = resources.getDimension(mIsRound
          ? R.dimen.digital_time_text_size_round : R.dimen.digital_time_text_size);
      float dateTextSize = resources.getDimension(mIsRound
          ? R.dimen.digital_date_text_size_round : R.dimen.digital_date_text_size);

      mTimePaint.setTextSize(timeTextSize);
      mInfoPaint.setTextSize(dateTextSize);


    }

    @Override
    public void onPropertiesChanged(Bundle properties) {
      super.onPropertiesChanged(properties);
      mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
      mBurnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
    }

    @Override
    public void onTimeTick() {
      super.onTimeTick();
      invalidate(); // forces redraw (calls onDraw)
      String TAG = "onTimeTick";
      Log.d(TAG, "onTimeTick called");

      if (mWeatherRequestedTime == 0
          || System.currentTimeMillis() - mWeatherRequestedTime >= TimeUnit.MINUTES
          .toMillis(WEATHER_UPDATE_INTERVAL)) {
        initWeatherUpdater(false);
      }
    }


    @Override
    public void onAmbientModeChanged(boolean inAmbientMode) {
      super.onAmbientModeChanged(inAmbientMode);

      mAmbient = inAmbientMode;
      if (mLowBitAmbient) {
        mTimePaint.setAntiAlias(!inAmbientMode);
        mInfoPaint.setAntiAlias(!inAmbientMode);
      }

      updateFontConfig();

      // Whether the timer should be running depends on whether we're visible (as well as
      // whether we're in ambient mode), so we may need to start or stop the timer.
      updateTimer();
    }

    // TODO use this to calculate y offsets outside of onDraw
    @Override
    public void onSurfaceChanged(
        SurfaceHolder holder, int format, int width, int height) {

      super.onSurfaceChanged(holder, format, width, height);
    }


    // TODO massively optimize this so that calculations and object allocation etc etc are not
    // performed here (this needs to run as fast as possible)
    // https://developer.android.com/training/wearables/watch-faces/performance
    @SuppressLint("DefaultLocale")
    @Override
    public void onDraw(Canvas canvas, Rect bounds) {
      final String TAG = "onDraw";

      // Draw the background.
      //canvas.drawColor(Color.BLACK);  // test not drawing background every render pass
      canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);

      long now = System.currentTimeMillis();

      // Create locale specific time string.
      java.text.DateFormat timeFormat = DateFormat.getTimeFormat(getApplicationContext());
      String timeText = timeFormat.format(now).replaceAll("[A-Z|\\s]", ""); // this also removes AM/PM if present

      float mTimeXOffset = computeXOffset(timeText, mTimePaint, bounds);

      Rect timeTextBounds = new Rect();
      mTimePaint.getTextBounds(timeText, 0, timeText.length(), timeTextBounds);
      float timeYOffset = computeTimeYOffset(timeTextBounds, bounds);

      canvas.drawText(timeText, mTimeXOffset, timeYOffset, mTimePaint);

      // Create locale specific date string.
      String dateFormat = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMd");
      String dateText = DateFormat.format(dateFormat, now).toString();

      String temperatureText = "";

      float centerX = bounds.exactCenterX();
      float dateTextLength = mInfoPaint.measureText(dateText);
      float totalLength = dateTextLength;

      float bitmapTotalMargin = mCurrentWeather.getIconBitmap(getApplicationContext()).getWidth()
          / WEATHER_ICON_MARGIN_RATIO;

      if (!mSettings.isWeatherDisabled()) {
        if (mSettings.isShowTemperature()) {
          temperatureText = mCurrentWeather.getFormattedTemperature(mSettings.isUseCelsius(),
              mSettings.isShowTemperatureFractional());
          if (mSettings.isShowWeatherIcon()) {
            totalLength =
                dateTextLength + bitmapTotalMargin + mCurrentWeather
                    .getIconBitmap(getApplicationContext())
                    .getWidth() + mInfoPaint.measureText(temperatureText);
          } else {
            totalLength =
                dateTextLength + bitmapTotalMargin + mInfoPaint.measureText(temperatureText);
          }
        } else if (mSettings.isShowWeatherIcon()) {
          totalLength = dateTextLength + bitmapTotalMargin / 2.0f + mCurrentWeather
              .getIconBitmap(getApplicationContext()).getWidth();
        }
      }

      float infoBarXOffset = centerX - (totalLength / 2.0f);
      float infoBarYOffset = computeInfoBarYOffset(dateText, mInfoPaint, timeTextBounds,
          timeYOffset);

      // draw infobar
      if (mSettings.isShowInfoBarAmbient() || !mAmbient) {
        canvas.drawText(dateText, infoBarXOffset, infoBarYOffset, mInfoPaint);
        if (!mSettings.isWeatherDisabled()) {
          if (mSettings.isShowWeatherIcon() && mCurrentWeather != null) {
            canvas.drawBitmap(mCurrentWeather.getIconBitmap(getApplicationContext()),
                infoBarXOffset + (dateTextLength + bitmapTotalMargin / 2.0f),
                infoBarYOffset
                    - mCurrentWeather.getIconBitmap(getApplicationContext()).getHeight()
                    / WEATHER_ICON_Y_OFFSET_RATIO,
                null);
            canvas.drawText(temperatureText,
                infoBarXOffset + (dateTextLength + bitmapTotalMargin + mCurrentWeather
                    .getIconBitmap(getApplicationContext()).getWidth()), infoBarYOffset,
                mInfoPaint);
          } else if (!mSettings.isShowWeatherIcon() && mSettings.isShowTemperature()
              && mCurrentWeather != null) {
            canvas.drawText(temperatureText, infoBarXOffset + (dateTextLength + bitmapTotalMargin),
                infoBarYOffset, mInfoPaint);
          }
        }
      }

      // draw battery percentage
      if (mSettings.isShowBattery()) {
        String battery = String.format("%d%%", mBatteryLevel);
        float batteryXOffset = computeXOffset(battery, mInfoPaint, bounds);
        float batteryYOffset = computeBatteryYOffset(battery, mInfoPaint, bounds);

        canvas.drawText(battery, batteryXOffset, batteryYOffset, mInfoPaint);
      }

      // draw wearOS icon
      if (mSettings.isShowWearIcon()) {
        if (mAmbient) {
          float mIconXOffset = bounds.exactCenterX() - (mWearOSBitmapAmbient.getWidth() / 2.0f);
          float mIconYOffset = timeYOffset / 4.0f;
          canvas.drawBitmap(mWearOSBitmapAmbient, mIconXOffset, mIconYOffset, null);
        } else {
          float mIconXOffset = bounds.exactCenterX() - (mWearOSBitmap.getWidth() / 2.0f);
          float mIconYOffset = timeYOffset / 4.0f;
          canvas.drawBitmap(mWearOSBitmap, mIconXOffset, mIconYOffset, null);
        }
      }
      checkAndLaunchDialogs();
    }

    private boolean shouldSuggestSettings() {
      // return true if the settings are their initial default values, and none of the onboarding dialogs have been shown
      return mSettings.isShowBattery() && !mSettings
          .isShowTemperature() && mSettings.isUseCelsius() && !mSettings.isShowWeatherIcon() &&
          !mSettings.isShowTemperatureFractional()
          && !mSettings.isUseThin() && !mSettings.isUseThinAmbient() && mSettings.isUseGrayInfoAmbient() &&

          mSettings.isShowInfoBarAmbient() && !mSettings.isShowWearIcon() && !mSettings.isAdvanced()
          && (!mSettings.isCompanionAppNotified() && !mSettings.isWeatherChangeNotified());
    }

    private void setSuggestedSettings() {
      boolean metric = UnitLocale.getDefault() == UnitLocale.Metric;
      mSettings.setUseCelsius(metric);
    }

    private void checkAndLaunchDialogs() {
      //Log.d("checkAndLaunchDialogs", "checking what dialogs should be launched...");
      if (!mSettings.isWeatherChangeNotified()) {
        Intent weatherChangeIntent = new Intent(getBaseContext(),
            WeatherUpdateActivity.class);
        weatherChangeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(weatherChangeIntent);
      } else if (!mSettings.isCompanionAppNotified()) {
        Intent companionNotifyIntent = new Intent(getBaseContext(),
            CompanionNotifyActivity.class);
        companionNotifyIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(companionNotifyIntent);
      }
    }

    private void updateFontConfig() {
      if (mAmbient) {
        if (mSettings.isUseThinAmbient()) {
          mTimePaint.setStyle(Paint.Style.FILL);
          mTimePaint.setTypeface(mProductSansThin);
        } else {
          mTimePaint.setTypeface(mProductSans);
          mTimePaint.setStyle(Paint.Style.STROKE);
        }
        if (mSettings.isUseGrayInfoAmbient()) {
          mInfoPaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.digital_text_ambient));
        } else {
          mInfoPaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.digital_text));
        }
      } else {
        if (mSettings.isUseThin()) {
          mTimePaint.setStyle(Paint.Style.FILL);
          mTimePaint.setTypeface(mProductSansThin);
        } else {
          mTimePaint.setTypeface(mProductSans);
          mTimePaint.setStyle(Paint.Style.FILL);
        }
        mInfoPaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.digital_text));
      }
    }

    private void initWeatherUpdater(boolean forceUpdate) {
      String TAG = "initWeatherUpdater";
      if (!mSettings.isWeatherDisabled()) {
        if (ActivityCompat
            .checkSelfPermission(getApplicationContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
          Log.d(TAG, "requesting permission");
          requestPermissions();
        } else {
          Log.d(TAG, "last weather retrieved: " + mCurrentWeather.getTime());
          Log.d(TAG, "current time: " + System.currentTimeMillis());

          Constraints constraints = new Constraints.Builder()
              .setRequiredNetworkType(NetworkType.CONNECTED)
              .build();

          if (forceUpdate || mWeatherRequestedTime == 0
              || System.currentTimeMillis() - mWeatherRequestedTime >= TimeUnit.MINUTES
              .toMillis(WEATHER_UPDATE_INTERVAL)) {

            OneTimeWorkRequest locationUpdate =
                new OneTimeWorkRequest.Builder(LocationUpdateWorker.class)
                    .setConstraints(constraints)
                    // forced weather update is expected to happen sooner, so
                    // try again in (30 seconds * attempt count). After 3 failed
                    // attempts, it would wait 1.5 minutes before retrying again
                    .setBackoffCriteria(BackoffPolicy.LINEAR, WEATHER_BACKOFF_DELAY_ONETIME,
                        TimeUnit.SECONDS)
                    .build();

            OneTimeWorkRequest weatherUpdate =
                new OneTimeWorkRequest.Builder(WeatherUpdateWorker.class)
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.LINEAR, WEATHER_BACKOFF_DELAY_ONETIME,
                        TimeUnit.SECONDS)
                    .build();

            WorkManager.getInstance(getApplicationContext())
                .beginUniqueWork(WEATHER_UPDATE_WORKER, ExistingWorkPolicy.REPLACE, locationUpdate)
                .then(weatherUpdate)
                .enqueue();
            mWeatherRequestedTime = System.currentTimeMillis();

            Observer<WorkInfo> weatherObserver = new Observer<WorkInfo>() {
              @Override
              public void onChanged(WorkInfo workInfo) {
                if (workInfo != null) {
                  Log.d(TAG, "weatherObserver onChanged : " + workInfo.toString());
                  if (workInfo.getState().isFinished()) {
                    String currentWeatherJSON = workInfo.getOutputData()
                        .getString(KEY_WEATHER_JSON);
                    Log.d(TAG, "outputWeather: " + currentWeatherJSON);
                    Weather newCurrentWeather = new Gson()
                        .fromJson(currentWeatherJSON, Weather.class);

                    // check if newCurrentWeather is actually valid
                    if (newCurrentWeather != null
                        && newCurrentWeather.getTemperature() != NO_TEMPERATURE) {
                      // copy bitmap over so we don't have to regenerate it
                      if (newCurrentWeather.getIconID() == mCurrentWeather.getIconID()) {
                        newCurrentWeather
                            .setIconBitmap(mCurrentWeather.getIconBitmap(getApplicationContext()));
                      }
                      mCurrentWeather = newCurrentWeather;
                      invalidate(); // redraw to update the displayed weather
                    }
                  }
                }
              }
            };

            WatchFaceUtil.observeUntilFinished(WorkManager.getInstance(getApplicationContext())
                .getWorkInfoByIdLiveData(weatherUpdate.getId()), weatherObserver);
          }
        }
      }
    }


    private float computeXOffset(String text, Paint paint, Rect watchBounds) {
      float centerX = watchBounds.exactCenterX();
      float textLength = paint.measureText(text);
      return centerX - (textLength / 2.0f);
    }


    private float computeTimeYOffset(Rect textBounds, Rect watchBounds) {
      if (mSettings.isShowWearIcon() || (!mSettings.isShowInfoBarAmbient() && mAmbient)) {
        return watchBounds.exactCenterY() + (textBounds.height()
            / 4.0f);
      }
      // this positions the time in the exact center but generally looks... off. So we will position
      // it slightly up to look more right
//      else if (!mSettings.isShowInfoBarAmbient() && mAmbient) {
//        return watchBounds.exactCenterY() + (textBounds.height() / 2.0f);
//      }
      else {
        return watchBounds.exactCenterY();
      }
    }

    private float computeInfoBarYOffset(String dateText, Paint datePaint, Rect timeTextBounds,
        float timeTextYOffset) {
      Rect textBounds = new Rect();
      datePaint.getTextBounds(dateText, 0, dateText.length(), textBounds);
      return textBounds.height() * INFO_BAR_Y_SPACING_RATIO + timeTextYOffset;
    }

    private float computeBatteryYOffset(String batteryText, Paint batteryPaint, Rect watchBounds) {
      Rect textBounds = new Rect();
      batteryPaint.getTextBounds(batteryText, 0, batteryText.length(), textBounds);
      return (watchBounds.bottom - mChinSize) - textBounds.height();
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
      String TAG = "onDataChanged";
      Log.d(TAG, "Data changed");
      DataMap dataMap = new DataMap();
      for (DataEvent event : dataEvents) {
        if (event.getType() == DataEvent.TYPE_CHANGED) {
          // DataItem changed
          DataItem item = event.getDataItem();
          Log.d(TAG, "DataItem uri: " + item.getUri());

          if (item.getUri().getPath().compareTo("/settings") == 0) {
            Log.d(TAG, "Companion app changed a setting!");
            dataMap = DataMapItem.fromDataItem(item).getDataMap();
            Log.d(TAG, dataMap.toString());

            // handle legacy nested data map
            if (dataMap.containsKey("com.corvettecole.pixelwatchface")) {
              dataMap = dataMap.getDataMap("com.corvettecole.pixelwatchface");
            }

            Log.d(TAG, dataMap.toString());

            for (UpdatesRequired updateRequired : mSettings.updateSettings(dataMap)) {
              switch (updateRequired) {
                case WEATHER:
                  initWeatherUpdater(true);
                  break;
                case FONT:
                  updateFontConfig();
                  break;
              }
            }

            invalidate();// forces redraw
            //syncToPhone();
          } else if (item.getUri().getPath().compareTo("/requests") == 0) {
            if (dataMap.containsKey("settings-update")) {

            }
          }


        } else if (event.getType() == DataEvent.TYPE_DELETED) {
          // DataItem deleted
        }
      }
    }

    // sync current settings to phone upon request
    private void syncToPhone() {
      String TAG = "syncToPhone";
      DataClient mDataClient = Wearable.getDataClient(getApplicationContext());
      PutDataMapRequest putDataMapReq = PutDataMapRequest.create("/watch-settings");

      putDataMapReq.setUrgent();
      Task<DataItem> putDataTask = mDataClient.putDataItem(putDataMapReq.asPutDataRequest());
      if (putDataTask.isSuccessful()) {
        Log.d(TAG, "Current stats synced to phone");
      }
    }

    private void requestPermissions() {
      Log.d("requestPermission", "mPermissionRequestTime: " + mPermissionRequestedTime);
      Log.d("requestPermission",
          "System.currentTimeMillis() - mPermissionRequestedTime: " + (System.currentTimeMillis()
              - mPermissionRequestedTime));
//        if (mPermissionRequestedTime == 0
//            || System.currentTimeMillis() - mPermissionRequestedTime > TimeUnit.MINUTES
//            .toMillis(1)) {
      Log.d("requestPermission",
          "Actually requesting permission, more than one minute has passed");
      mPermissionRequestedTime = System.currentTimeMillis();
      if (ContextCompat
          .checkSelfPermission(getApplication(), permission.ACCESS_FINE_LOCATION)
          != PackageManager.PERMISSION_GRANTED) {
        Intent mPermissionRequestIntent = new Intent(getBaseContext(),
            WatchPermissionRequestActivity.class);
        mPermissionRequestIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (VERSION.SDK_INT >= VERSION_CODES.Q) {
          mPermissionRequestIntent.putExtra("KEY_PERMISSIONS",
              new String[]{permission.ACCESS_COARSE_LOCATION,
                  permission.ACCESS_FINE_LOCATION,
                  permission.ACCESS_BACKGROUND_LOCATION});
        } else {
          mPermissionRequestIntent.putExtra("KEY_PERMISSIONS",
              new String[]{permission.ACCESS_COARSE_LOCATION,
                  permission.ACCESS_FINE_LOCATION});
        }
        startActivity(mPermissionRequestIntent);
        //}
      }
    }

    /**
     * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently or
     * stops it if it shouldn't be running but currently is.
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
