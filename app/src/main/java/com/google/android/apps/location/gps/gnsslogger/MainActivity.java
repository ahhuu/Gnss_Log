/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.google.android.apps.location.gps.gnsslogger;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.viewpager.widget.ViewPager;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks;
import com.google.android.gms.common.api.GoogleApiClient.OnConnectionFailedListener;
import com.google.android.gms.location.ActivityRecognition;
import com.google.android.gms.location.ActivityRecognitionResult;
import com.google.android.gms.location.DetectedActivity;
import com.google.android.gms.location.LocationServices;
import com.google.android.material.tabs.TabLayout;
import java.util.Locale;

/** The activity for the application. */
public class MainActivity extends AppCompatActivity
        implements OnConnectionFailedListener, ConnectionCallbacks, GroundTruthModeSwitcher {
  private static final int LOCATION_REQUEST_ID = 1;
  private static final String[] REQUIRED_PERMISSIONS = {
          Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.WRITE_EXTERNAL_STORAGE
  };
  private static final int NUMBER_OF_FRAGMENTS = 6;
  private static final int FRAGMENT_INDEX_SETTING = 0;
  private static final int FRAGMENT_INDEX_LOGGER = 1;
  private static final int FRAGMENT_INDEX_RESULT = 2;
  private static final int FRAGMENT_INDEX_MAP = 3;
  private static final int FRAGMENT_INDEX_AGNSS = 4;
  private static final int FRAGMENT_INDEX_PLOT = 5;
  private static final String TAG = "MainActivity";

  private MeasurementProvider mMeasurementProvider;
  private UiLogger mUiLogger;
  private RealTimePositionVelocityCalculator mRealTimePositionVelocityCalculator;
  private FileLogger mFileLogger;
  private AgnssUiLogger mAgnssUiLogger;
  private Fragment[] mFragments;
  private GoogleApiClient mGoogleApiClient;
  private LoggerFragment mLoggerFragment;
  private SettingsFragment mSettingsFragment;

  private LoggerFragment.UIFragmentComponent mUIFragmentComponent;

  public LoggerFragment.UIFragmentComponent getUIFragmentComponent() {
    return mUIFragmentComponent;
  }

  private boolean mAutoSwitchGroundTruthMode;
  //广播接收器，用于接收活动检测结果的广播
  private final ActivityDetectionBroadcastReceiver mBroadcastReceiver =
          new ActivityDetectionBroadcastReceiver();
  //服务连接对象，用于绑定和解绑服务
  private ServiceConnection mConnection =
          new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName className, IBinder serviceBinder) {
              // Empty
            }

            @Override
            public void onServiceDisconnected(ComponentName className) {
              // Empty
            }
          };

  // 在活动启动时，绑定 TimerService 服务，确保应用运行时服务可用
  @Override
  protected void onStart() {
    super.onStart();
    bindService(new Intent(this, TimerService.class), mConnection, Context.BIND_AUTO_CREATE);
  }

  //在活动恢复时，注册广播接收器 mBroadcastReceiver，用于接收活动检测结果的广播
  @Override
  protected void onResume() {
    super.onResume();
    LocalBroadcastManager.getInstance(this)
            .registerReceiver(
                    mBroadcastReceiver,
                    new IntentFilter(DetectedActivitiesIntentReceiver.AR_RESULT_BROADCAST_ACTION));
  }

  //在活动暂停时，取消注册广播接收器 mBroadcastReceiver
  @Override
  protected void onPause() {
    LocalBroadcastManager.getInstance(this).unregisterReceiver(mBroadcastReceiver);
    super.onPause();
  }

  //在活动停止时，解绑之前绑定的服务
  @Override
  protected void onStop() {
    super.onStop();
    unbindService(mConnection);
  }

  @Override
  protected void onDestroy() {
    mMeasurementProvider.unregisterAll();
    super.onDestroy();
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
    SharedPreferences.Editor editor = sharedPreferences.edit();
    editor.putBoolean(SettingsFragment.PREFERENCE_KEY_AUTO_SCROLL, false);
    editor.commit();
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    buildGoogleApiClient();
    requestPermissionAndSetupFragments(this);
  }

  protected PendingIntent createActivityDetectionPendingIntent() {
    Intent intent = new Intent(this, DetectedActivitiesIntentReceiver.class);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      return PendingIntent.getBroadcast(this, 0, intent,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
    } else {
      return PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }
  }

  private synchronized void buildGoogleApiClient() {
    mGoogleApiClient =
            new GoogleApiClient.Builder(this)
                    .enableAutoManage(this, this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(ActivityRecognition.API)
                    .addApi(LocationServices.API)
                    .build();
  }

  @Override
  public void onConnectionFailed(@NonNull ConnectionResult result) {
    if (Log.isLoggable(TAG, Log.INFO)) {
      Log.i(TAG, "Connection failed: ErrorCode = " + result.getErrorCode());
    }
  }

  @Override
  public void onConnected(Bundle connectionHint) {
    if (Log.isLoggable(TAG, Log.INFO)) {
      Log.i(TAG, "Connected to GoogleApiClient");
    }
    try {
      ActivityRecognition.ActivityRecognitionApi.requestActivityUpdates(
              mGoogleApiClient, 0, createActivityDetectionPendingIntent());
    } catch (SecurityException e) {
      // TODO(adaext)
      //    ActivityCompat#requestPermissions
    }
  }

  @Override
  public void onConnectionSuspended(int cause) {
    if (Log.isLoggable(TAG, Log.INFO)) {
      Log.i(TAG, "Connection suspended");
    }
  }

  /**
   * A {@link FragmentStatePagerAdapter} that returns a fragment corresponding to one of the
   * sections/tabs/pages.
   */
  public class ViewPagerAdapter extends FragmentStatePagerAdapter {

    public ViewPagerAdapter(FragmentManager fm) {
      super(fm);
    }

    @Override
    public androidx.fragment.app.Fragment getItem(int position) {
      if (position < 0 || position >= mFragments.length) {
        Log.e(TAG, "Invalid section: " + position);
        throw new IllegalArgumentException("Invalid section: " + position);
      }
      Fragment fragment = mFragments[position];
      if (fragment == null) {
        Log.e(TAG, "Fragment at position " + position + " is null");
      }
      return fragment;
    }

    @Override
    public int getCount() {
      // Show total pages.
      return NUMBER_OF_FRAGMENTS;
    }

    @Override
    public CharSequence getPageTitle(int position) {
      Locale locale = Locale.getDefault();
      switch (position) {
        case FRAGMENT_INDEX_SETTING:
          return getString(R.string.title_settings).toUpperCase(locale);
        case FRAGMENT_INDEX_LOGGER:
          return getString(R.string.title_log).toUpperCase(locale);
        case FRAGMENT_INDEX_RESULT:
          return getString(R.string.title_offset).toUpperCase(locale);
        case FRAGMENT_INDEX_MAP:
          return getString(R.string.title_map).toUpperCase(locale);
        case FRAGMENT_INDEX_AGNSS:
          return getString(R.string.title_agnss).toUpperCase(locale);
        case FRAGMENT_INDEX_PLOT:
          return getString(R.string.title_plot).toLowerCase(locale);
        default:
          return super.getPageTitle(position);
      }
    }
  }

  @Override
  public void onRequestPermissionsResult(
          int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode == LOCATION_REQUEST_ID) {
      // If request is cancelled, the result arrays are empty.
      if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
        // 权限被授予，设置 Fragment
        setupFragments();
      } else {
        // 权限被拒绝，向用户解释为什么需要这些权限
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, REQUIRED_PERMISSIONS[0])) {
          Log.i(TAG, "Permissions are required to use the app properly.");
        } else {
          // 用户选择不再询问，引导用户到应用设置页面手动开启权限
          Log.i(TAG, "User denied permissions and selected 'Don't ask again'.");
        }
      }
    }
  }

  private void setupFragments() {
    mUiLogger = new UiLogger();
    mRealTimePositionVelocityCalculator = new RealTimePositionVelocityCalculator();
    mRealTimePositionVelocityCalculator.setMainActivity(this);
    mRealTimePositionVelocityCalculator.setResidualPlotMode(
            RealTimePositionVelocityCalculator.RESIDUAL_MODE_DISABLED, null /* fixedGroundTruth */);

    mFileLogger = new FileLogger(getApplicationContext());
    mAgnssUiLogger = new AgnssUiLogger();
    mMeasurementProvider =
            new MeasurementProvider(
                    getApplicationContext(),
                    mGoogleApiClient,
                    mUiLogger,
                    mFileLogger,
                    mRealTimePositionVelocityCalculator,
                    mAgnssUiLogger);
    mFragments = new Fragment[NUMBER_OF_FRAGMENTS];

    SettingsFragment settingsFragment = new SettingsFragment();
    settingsFragment.setGpsContainer(mMeasurementProvider);
    settingsFragment.setRealTimePositionVelocityCalculator(mRealTimePositionVelocityCalculator);
    settingsFragment.setAutoModeSwitcher(this);
    settingsFragment.setFileLogger(mFileLogger); // 设置 FileLogger 到 SettingsFragment
    mFragments[FRAGMENT_INDEX_SETTING] = settingsFragment;

    LoggerFragment loggerFragment = new LoggerFragment();
    loggerFragment.setUILogger(mUiLogger);
    loggerFragment.setFileLogger(mFileLogger);
    mUIFragmentComponent = loggerFragment.getUIFragmentComponent(); // 获取 UIFragmentComponent 实例
    mFragments[FRAGMENT_INDEX_LOGGER] = loggerFragment;

    ResultFragment resultFragment = new ResultFragment();
    resultFragment.setPositionVelocityCalculator(mRealTimePositionVelocityCalculator);
    mFragments[FRAGMENT_INDEX_RESULT] = resultFragment;

    MapFragment mapFragment = new MapFragment();
    mapFragment.setPositionVelocityCalculator(mRealTimePositionVelocityCalculator);
    mFragments[FRAGMENT_INDEX_MAP] = mapFragment;

    AgnssFragment agnssFragment = new AgnssFragment();
    agnssFragment.setMeasurementProvider(mMeasurementProvider);
    agnssFragment.setUILogger(mAgnssUiLogger);
    mFragments[FRAGMENT_INDEX_AGNSS] = agnssFragment;

    PlotFragment plotFragment = new PlotFragment();
    mFragments[FRAGMENT_INDEX_PLOT] = plotFragment;
    mRealTimePositionVelocityCalculator.setPlotFragment(plotFragment);

    // The viewpager that will host the section contents.
    ViewPager viewPager = findViewById(R.id.pager);
    viewPager.setOffscreenPageLimit(5);
    ViewPagerAdapter adapter = new ViewPagerAdapter(getSupportFragmentManager());
    viewPager.setAdapter(adapter);

    TabLayout tabLayout = findViewById(R.id.tab_layout);
    tabLayout.setTabsFromPagerAdapter(adapter);

    // Set a listener via setOnTabSelectedListener(OnTabSelectedListener) to be notified when any
    // tab's selection state has been changed.
    tabLayout.setOnTabSelectedListener(new TabLayout.ViewPagerOnTabSelectedListener(viewPager));

    // Use a TabLayout.TabLayoutOnPageChangeListener to forward the scroll and selection changes to
    // this layout
    viewPager.addOnPageChangeListener(new TabLayout.TabLayoutOnPageChangeListener(tabLayout));
  }

  // 检查是否拥有所有必需的权限
  private boolean hasPermissions(Activity activity) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      // Permissions granted at install time.
      return true;
    }
    for (String p : REQUIRED_PERMISSIONS) {
      if (ContextCompat.checkSelfPermission(activity, p) != PackageManager.PERMISSION_GRANTED) {
        return false;
      }
    }
    return true;
  }

  // 请求权限并设置 Fragment
  private void requestPermissionAndSetupFragments(final Activity activity) {
    if (hasPermissions(activity)) {
      setupFragments();
    } else {
      ActivityCompat.requestPermissions(activity, REQUIRED_PERMISSIONS, LOCATION_REQUEST_ID);
    }
  }

  /** Toggles the flag to allow Activity Recognition updates to change ground truth mode */
  public void setAutoSwitchGroundTruthModeEnabled(boolean enabled) {
    mAutoSwitchGroundTruthMode = enabled;
  }

  /**
   * A receiver for result of {@link
   * ActivityRecognition#ActivityRecognitionApi#requestActivityUpdates()} broadcast by {@link
   * DetectedActivitiesIntentReceiver}
   */
  public class ActivityDetectionBroadcastReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {

      // Modify the status of mRealTimePositionVelocityCalculator only if the status is set to auto
      // (indicated by mAutoSwitchGroundTruthMode).
      if (mAutoSwitchGroundTruthMode) {
        ActivityRecognitionResult result = ActivityRecognitionResult.extractResult(intent);
        setGroundTruthModeOnResult(result);
      }
    }
  }

  /**
   * Sets up the ground truth mode of {@link RealTimePositionVelocityCalculator} given an result
   * from Activity Recognition update. For activities other than {@link DetectedActivity#STILL} and
   * {@link DetectedActivity#TILTING}, we conservatively assume the user is moving and use the last
   * WLS position solution as ground truth for corrected residual computation.
   */
  private void setGroundTruthModeOnResult(ActivityRecognitionResult result) {
    if (result != null) {
      int detectedActivityType = result.getMostProbableActivity().getType();
      if (detectedActivityType == DetectedActivity.STILL
              || detectedActivityType == DetectedActivity.TILTING) {
        mRealTimePositionVelocityCalculator.setResidualPlotMode(
                RealTimePositionVelocityCalculator.RESIDUAL_MODE_STILL, null);
      } else {
        mRealTimePositionVelocityCalculator.setResidualPlotMode(
                RealTimePositionVelocityCalculator.RESIDUAL_MODE_MOVING, null);
      }
    }
  }
}