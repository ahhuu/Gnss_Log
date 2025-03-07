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

import android.content.Context;
import android.content.Intent;
import android.location.GnssClock;
import android.location.GnssMeasurement;
import android.location.GnssMeasurementsEvent;
import android.location.GnssNavigationMessage;
import android.location.GnssStatus;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import com.google.android.apps.location.gps.gnsslogger.LoggerFragment.UIFragmentComponent;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileFilter;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** A GNSS logger to store information to a file. */
public class FileLogger implements MeasurementListener {

  private static final String TAG = "FileLogger";
  private static final String FILE_PREFIX = "gnss_log";
  private static final String ERROR_WRITING_FILE = "Problem writing to file.";
  private static final String COMMENT_START = "# ";
  private static final char RECORD_DELIMITER = ',';
  private static final String VERSION_TAG = "Version: ";

  private static final int MAX_FILES_STORED = 100;
  private static final int MINIMUM_USABLE_FILE_SIZE_BYTES = 1000;

  // 定义 RINEX 文件相关常量和变量
  private static final String RINEX_FILE_PREFIX = "RINEX";
  private BufferedWriter mRinexFileWriter;
  private File mRinexFile;

  private final Context mContext;

  private final Object mFileLock = new Object();
  private BufferedWriter mFileWriter;
  private File mFile;

  private UIFragmentComponent mUiComponent;

  public synchronized UIFragmentComponent getUiComponent() {
    return mUiComponent;
  }

  public synchronized void setUiComponent(UIFragmentComponent value) {
    mUiComponent = value;
  }

  public FileLogger(Context context) {
    this.mContext = context;
  }

  /** Start a new file logging process. */
  public void startNewLog() {
    synchronized (mFileLock) {
      // 创建原始 GNSS 数据文件
      File baseDirectory;
      String state = Environment.getExternalStorageState();
      if (Environment.MEDIA_MOUNTED.equals(state)) {
        // getExternalFilesDir 适配 Android 10 及以上版本
        baseDirectory = new File(mContext.getExternalFilesDir(null), FILE_PREFIX);
        if (!baseDirectory.exists() && !baseDirectory.mkdirs()) {
          logError("Failed to create directory: " + baseDirectory.getAbsolutePath());
          return;
        }
      } else if (Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
        logError("Cannot write to external storage.");
        return;
      } else {
        logError("Cannot read external storage.");
        return;
      }

      SimpleDateFormat formatter = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss");
      Date now = new Date();
      String fileName = String.format("%s_%s.txt", FILE_PREFIX, formatter.format(now));
      File currentFile = new File(baseDirectory, fileName);
      String currentFilePath = currentFile.getAbsolutePath();
      BufferedWriter currentFileWriter;
      try {
        currentFileWriter = new BufferedWriter(new FileWriter(currentFile));
      } catch (IOException e) {
        logException("Could not open file: " + currentFilePath, e);
        return;
      }

      // initialize the contents of the file
      try {
        currentFileWriter.write(COMMENT_START);
        currentFileWriter.newLine();
        currentFileWriter.write(COMMENT_START);
        currentFileWriter.write("Header Description:");
        currentFileWriter.newLine();
        currentFileWriter.write(COMMENT_START);
        currentFileWriter.newLine();
        currentFileWriter.write(COMMENT_START);
        currentFileWriter.write(VERSION_TAG);
        String manufacturer = Build.MANUFACTURER;
        String model = Build.MODEL;
        String fileVersion =
                mContext.getString(R.string.app_version)
                        + " Platform: "
                        + Build.VERSION.RELEASE
                        + " "
                        + "Manufacturer: "
                        + manufacturer
                        + " "
                        + "Model: "
                        + model;
        currentFileWriter.write(fileVersion);
        currentFileWriter.newLine();
        currentFileWriter.write(COMMENT_START);
        currentFileWriter.newLine();
        currentFileWriter.write(COMMENT_START);
        currentFileWriter.write(
                "Raw,ElapsedRealtimeMillis,TimeNanos,LeapSecond,TimeUncertaintyNanos,FullBiasNanos,"
                        + "BiasNanos,BiasUncertaintyNanos,DriftNanosPerSecond,DriftUncertaintyNanosPerSecond,"
                        + "HardwareClockDiscontinuityCount,Svid,TimeOffsetNanos,State,ReceivedSvTimeNanos,"
                        + "ReceivedSvTimeUncertaintyNanos,Cn0DbHz,PseudorangeRateMetersPerSecond,"
                        + "PseudorangeRateUncertaintyMetersPerSecond,"
                        + "AccumulatedDeltaRangeState,AccumulatedDeltaRangeMeters,"
                        + "AccumulatedDeltaRangeUncertaintyMeters,CarrierFrequencyHz,CarrierCycles,"
                        + "CarrierPhase,CarrierPhaseUncertainty,MultipathIndicator,SnrInDb,"
                        + "ConstellationType,AgcDb");
        currentFileWriter.newLine();
        currentFileWriter.write(COMMENT_START);
        currentFileWriter.newLine();
        currentFileWriter.write(COMMENT_START);
        currentFileWriter.write(
                "Fix,Provider,Latitude,Longitude,Altitude,Speed,Accuracy,(UTC)TimeInMs");
        currentFileWriter.newLine();
        currentFileWriter.write(COMMENT_START);
        currentFileWriter.newLine();
        currentFileWriter.write(COMMENT_START);
        currentFileWriter.write("Nav,Svid,Type,Status,MessageId,Sub-messageId,Data(Bytes)");
        currentFileWriter.newLine();
        currentFileWriter.write(COMMENT_START);
        currentFileWriter.newLine();
      } catch (IOException e) {
        logException("Count not initialize file: " + currentFilePath, e);
        return;
      }

      if (mFileWriter != null) {
        try {
          mFileWriter.close();
        } catch (IOException e) {
          logException("Unable to close all file streams.", e);
          return;
        }
      }

      mFile = currentFile;
      mFileWriter = currentFileWriter;
      Toast.makeText(mContext, "File opened: " + currentFilePath, Toast.LENGTH_SHORT).show();

      // 创建 RINEX 文件
      File rinexBaseDirectory;
      if (Environment.MEDIA_MOUNTED.equals(state)) {
        rinexBaseDirectory = new File(mContext.getExternalFilesDir(null), RINEX_FILE_PREFIX);
        if (!rinexBaseDirectory.exists() && !rinexBaseDirectory.mkdirs()) {
          logError("Failed to create RINEX directory: " + rinexBaseDirectory.getAbsolutePath());
          return;
        }
      } else if (Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
        logError("Cannot write to external storage for RINEX file.");
        return;
      } else {
        logError("Cannot read external storage for RINEX file.");
        return;
      }

      String rinexFileName = String.format("%s_%s.txt", RINEX_FILE_PREFIX, formatter.format(now));
      File currentRinexFile = new File(rinexBaseDirectory, rinexFileName);
      String currentRinexFilePath = currentRinexFile.getAbsolutePath();
      BufferedWriter currentRinexFileWriter;
      try {
        currentRinexFileWriter = new BufferedWriter(new FileWriter(currentRinexFile));
      } catch (IOException e) {
        logException("Could not open RINEX file: " + currentRinexFilePath, e);
        return;
      }

      if (mRinexFileWriter != null) {
        try {
          mRinexFileWriter.close();
        } catch (IOException e) {
          logException("Unable to close RINEX file stream.", e);
          return;
        }
      }

      mRinexFile = currentRinexFile;
      mRinexFileWriter = currentRinexFileWriter;
      Toast.makeText(mContext, "RINEX File opened: " + currentRinexFilePath, Toast.LENGTH_SHORT).show();

      // 写入 RINEX 头文件信息
      try {
        writeRinexHeader(currentRinexFileWriter);
      } catch (IOException e) {
        logException("Error writing RINEX header", e);
        return;
      }
    }
  }

  // 写入 RINEX 头文件信息
  private void writeRinexHeader(BufferedWriter writer) throws IOException {
    writer.write("     3.04           OBSERVATION DATA    M  RINEX VERSION / TYPE\n");
    writer.write("GNSS Logger                     PROGRAM\n");
    writer.write("Google                          AGENCY\n");
    writer.write("Sample Station Name             MARKER NAME\n");
    writer.write("Sample Marker Number            MARKER NUMBER\n");
    writer.write("NONE                            MARKER TYPE\n");
    writer.write("Sample Receiver Type            REC # / TYPE / VERS\n");
    writer.write("Sample Antenna Type             ANT # / TYPE\n");
    writer.write("    0.00000000       0.00000000       0.00000000                  APPROX POSITION XYZ\n");
    writer.write("    0.00000000       0.00000000       0.00000000                  ANTENNA: DELTA H/E/N\n");
    writer.write("                                                            WAVELENGTH FACT L1/2\n");
    writer.write("G    C1C L1C D1C S1C                                                         SYS / # / OBS TYPES\n");
    writer.write("                                                            TIME OF FIRST OBS\n");
    writer.write("                                                            TIME OF LAST OBS\n");
    writer.write("     0                            LEAP SECONDS\n");
    writer.write("                                                            END OF HEADER\n");
  }

  // 将 RINEX 数据写入 RINEX 文件
  private void writeRinexData(String data) {
    if (mRinexFileWriter != null) {
      try {
        mRinexFileWriter.write(data);
        mRinexFileWriter.newLine();
      } catch (IOException e) {
        logException("Error writing to RINEX file", e);
      }
    }
  }

  /**
   * Send the current log via email or other options selected from a pop menu shown to the user. A
   * new log is started when calling this function.
   */
  public void send() {
    if (mFile == null) {
      return;
    }
    Intent emailIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
    emailIntent.setType("*/*");
    emailIntent.putExtra(Intent.EXTRA_SUBJECT, "SensorLog");
    emailIntent.putExtra(Intent.EXTRA_TEXT, "");

    // 附加原始 GNSS 数据文件
    List<Uri> uris = new ArrayList<>();
    try {
      Uri fileURI = FileProvider.getUriForFile(mContext, BuildConfig.APPLICATION_ID + ".provider", mFile);
      uris.add(fileURI);
    } catch (IllegalArgumentException e) {
      logException("Error getting file URI for GNSS log", e);
      return;
    }

    // 附加 RINEX 数据文件
    if (mRinexFile != null) {
      try {
        Uri rinexFileURI = FileProvider.getUriForFile(mContext, BuildConfig.APPLICATION_ID + ".provider", mRinexFile);
        uris.add(rinexFileURI);
      } catch (IllegalArgumentException e) {
        logException("Error getting file URI for RINEX log", e);
        return;
      }
    }

    emailIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<>(uris));

    // 检查是否有能够处理这个 Intent 的应用
    if (emailIntent.resolveActivity(mContext.getPackageManager()) != null) {
      getUiComponent().startActivity(Intent.createChooser(emailIntent, "Send log.."));
    } else {
      logError("没有找到处理邮件的应用！请安装一个邮件应用。");
      Toast.makeText(mContext, "没有找到处理邮件的应用！", Toast.LENGTH_LONG).show();
    }

    if (mFileWriter != null) {
      try {
        mFileWriter.flush();
        mFileWriter.close();
        mFileWriter = null;
      } catch (IOException e) {
        logException("Unable to close all file streams.", e);
        return;
      }
    }

    if (mRinexFileWriter != null) {
      try {
        mRinexFileWriter.flush();
        mRinexFileWriter.close();
        mRinexFileWriter = null;
      } catch (IOException e) {
        logException("Unable to close RINEX file stream.", e);
        return;
      }
    }
  }

/*  控制 RINEX 记录的开启和关闭*/

  private boolean isRinexLogging = false;

  public void startRinexLogging() {
    if (!isRinexLogging) {
      try {
        startNewLog(); // 调用原有的启动日志方法
        isRinexLogging = true;
      } catch (Exception e) {
        logException("Error starting RINEX logging", e);
      }
    }
  }

  public void stopRinexLogging() {
    if (isRinexLogging) {
      try {
        if (mRinexFileWriter != null) {
          mRinexFileWriter.close();
          mRinexFileWriter = null;
        }
        isRinexLogging = false;
      } catch (IOException e) {
        logException("Error stopping RINEX logging", e);
      }
    }
  }

  @Override
  public void onProviderEnabled(String provider) {}

  @Override
  public void onProviderDisabled(String provider) {}

  private String convertToRinexFormat(GnssClock clock, GnssMeasurement measurement) {
    StringBuilder rinexData = new StringBuilder();
    // 假设时间格式为 YYYY MM DD HH MM SS.SSS
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy MM dd HH mm ss.SSS");
    Date date = new Date(clock.getTimeNanos() / 1000000);
    rinexData.append(String.format("%-4d %-2d %-2d %-2d %-2d %-11.3f",
            Integer.parseInt(sdf.format(date).substring(0, 4)),
            Integer.parseInt(sdf.format(date).substring(5, 7)),
            Integer.parseInt(sdf.format(date).substring(8, 10)),
            Integer.parseInt(sdf.format(date).substring(11, 13)),
            Integer.parseInt(sdf.format(date).substring(14, 16)),
            Double.parseDouble(sdf.format(date).substring(17))));
    rinexData.append("  0    "); // 时间标志和接收机钟差
    rinexData.append(getConstellationCode(measurement.getConstellationType())); // 星座类型
    rinexData.append(String.format("%02d", measurement.getSvid())); // 卫星编号
    rinexData.append("    ");
    rinexData.append(String.format("%14.3f", measurement.getCn0DbHz())); // C/N0
    rinexData.append("    ");
    rinexData.append(String.format("%14.9f", measurement.getReceivedSvTimeNanos() / 1e9)); // 接收时间
    return rinexData.toString();
  }

  private String convertLocationToRinexFormat(Location location) {
    StringBuilder rinexData = new StringBuilder();
    // 假设时间格式为 YYYY MM DD HH MM SS.SSS
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy MM dd HH mm ss.SSS");
    Date date = new Date(location.getTime());
    rinexData.append(String.format("%-4d %-2d %-2d %-2d %-2d %-11.3f",
            Integer.parseInt(sdf.format(date).substring(0, 4)),
            Integer.parseInt(sdf.format(date).substring(5, 7)),
            Integer.parseInt(sdf.format(date).substring(8, 10)),
            Integer.parseInt(sdf.format(date).substring(11, 13)),
            Integer.parseInt(sdf.format(date).substring(14, 16)),
            Double.parseDouble(sdf.format(date).substring(17))));
    rinexData.append("  0    "); // 时间标志和接收机钟差
    rinexData.append("G"); // 假设为 GPS 星座
    rinexData.append("00"); // 假设卫星编号为 00
    rinexData.append("    ");
    rinexData.append(String.format("%14.6f", location.getLatitude())); // 纬度
    rinexData.append("    ");
    rinexData.append(String.format("%14.6f", location.getLongitude())); // 经度
    rinexData.append("    ");
    rinexData.append(String.format("%14.3f", location.getAltitude())); // 海拔
    return rinexData.toString();
  }

  private String convertNavigationMessageToRinexFormat(GnssNavigationMessage navigationMessage) {
    StringBuilder rinexData = new StringBuilder();
    // 假设使用当前时间作为导航消息时间
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy MM dd HH mm ss.SSS");
    Date now = new Date();
    rinexData.append(String.format("%-4d %-2d %-2d %-2d %-2d %-11.3f",
            Integer.parseInt(sdf.format(now).substring(0, 4)),
            Integer.parseInt(sdf.format(now).substring(5, 7)),
            Integer.parseInt(sdf.format(now).substring(8, 10)),
            Integer.parseInt(sdf.format(now).substring(11, 13)),
            Integer.parseInt(sdf.format(now).substring(14, 16)),
            Double.parseDouble(sdf.format(now).substring(17))));
    rinexData.append("  0    "); // 时间标志和接收机钟差
    // 删除了记录星座信息的代码
    rinexData.append(String.format("%02d", navigationMessage.getSvid())); // 卫星编号
    rinexData.append("    ");
    rinexData.append(String.format("%d", navigationMessage.getType())); // 消息类型
    rinexData.append("    ");
    rinexData.append(String.format("%d", navigationMessage.getStatus())); // 消息状态
    rinexData.append("    ");
    rinexData.append(String.format("%d", navigationMessage.getMessageId())); // 消息 ID
    rinexData.append("    ");
    rinexData.append(String.format("%d", navigationMessage.getSubmessageId())); // 子消息 ID
    byte[] data = navigationMessage.getData();
    for (byte b : data) {
      rinexData.append(" ");
      rinexData.append(String.format("%02X", b));
    }
    return rinexData.toString();
  }

  private String getConstellationCode(int constellationType) {
    switch (constellationType) {
      case GnssStatus.CONSTELLATION_GPS:
        return "G";
      case GnssStatus.CONSTELLATION_GLONASS:
        return "R";
      case GnssStatus.CONSTELLATION_BEIDOU:
        return "C";
      case GnssStatus.CONSTELLATION_GALILEO:
        return "E";
      case GnssStatus.CONSTELLATION_QZSS:
        return "J";
      case GnssStatus.CONSTELLATION_SBAS:
        return "S";
      case GnssStatus.CONSTELLATION_IRNSS:
        return "I";
      default:
        return "?";
    }
  }

  @Override
  public void onLocationChanged(Location location) {
    synchronized (mFileLock) {
      if (mFileWriter == null) {
        return;
      }
      String locationStream =
              String.format(
                      Locale.US,
                      "Fix,%s,%f,%f,%f,%f,%f,%d",
                      location.getProvider(),
                      location.getLatitude(),
                      location.getLongitude(),
                      location.getAltitude(),
                      location.getSpeed(),
                      location.getAccuracy(),
                      location.getTime());
      try {
        mFileWriter.write(locationStream);
        mFileWriter.newLine();
      } catch (IOException e) {
        logException(ERROR_WRITING_FILE, e);
      }
      // 示例：将位置数据转换为 RINEX 格式并写入 RINEX 文件
      String rinexData = convertLocationToRinexFormat(location);
      writeRinexData(rinexData);
    }
  }

  @Override
  public void onLocationStatusChanged(String provider, int status, Bundle extras) {}

  @Override
  public void onGnssMeasurementsReceived(GnssMeasurementsEvent event) {
    synchronized (mFileLock) {
      if (mFileWriter == null) {
        return;
      }
      GnssClock gnssClock = event.getClock();
      for (GnssMeasurement measurement : event.getMeasurements()) {
        try {
          writeGnssMeasurementToFile(gnssClock, measurement);
        } catch (IOException e) {
          logException(ERROR_WRITING_FILE, e);
        }
        // 示例：将测量数据转换为 RINEX 格式并写入 RINEX 文件
        String rinexData = convertToRinexFormat(gnssClock, measurement);
        writeRinexData(rinexData);
      }
    }
  }

  @Override
  public void onGnssMeasurementsStatusChanged(int status) {}

  @Override
  public void onGnssNavigationMessageReceived(GnssNavigationMessage navigationMessage) {
    synchronized (mFileLock) {
      if (mFileWriter == null) {
        return;
      }
      StringBuilder builder = new StringBuilder("Nav");
      builder.append(RECORD_DELIMITER);
      builder.append(navigationMessage.getSvid());
      builder.append(RECORD_DELIMITER);
      builder.append(navigationMessage.getType());
      builder.append(RECORD_DELIMITER);

      int status = navigationMessage.getStatus();
      builder.append(status);
      builder.append(RECORD_DELIMITER);
      builder.append(navigationMessage.getMessageId());
      builder.append(RECORD_DELIMITER);
      builder.append(navigationMessage.getSubmessageId());
      byte[] data = navigationMessage.getData();
      for (byte word : data) {
        builder.append(RECORD_DELIMITER);
        builder.append(word);
      }
      try {
        mFileWriter.write(builder.toString());
        mFileWriter.newLine();
      } catch (IOException e) {
        logException(ERROR_WRITING_FILE, e);
      }

      // 将导航消息转换为 RINEX 格式并写入 RINEX 文件
      String rinexData = convertNavigationMessageToRinexFormat(navigationMessage);
      writeRinexData(rinexData);
    }
  }

  @Override
  public void onGnssNavigationMessageStatusChanged(int status) {}

  @Override
  public void onGnssStatusChanged(GnssStatus gnssStatus) {}

  @Override
  public void onNmeaReceived(long timestamp, String s) {
    synchronized (mFileLock) {
      if (mFileWriter == null) {
        return;
      }
      String nmeaStream = String.format(Locale.US, "NMEA,%s,%d", s.trim(), timestamp);
      try {
        mFileWriter.write(nmeaStream);
        mFileWriter.newLine();
      } catch (IOException e) {
        logException(ERROR_WRITING_FILE, e);
      }
    }
  }

  @Override
  public void onListenerRegistration(String listener, boolean result) {}

  @Override
  public void onTTFFReceived(long l) {}

  private void writeGnssMeasurementToFile(GnssClock clock, GnssMeasurement measurement)
          throws IOException {
    String clockStream =
            String.format(
                    "Raw,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s",
                    SystemClock.elapsedRealtime(),
                    clock.getTimeNanos(),
                    clock.hasLeapSecond() ? clock.getLeapSecond() : "",
                    clock.hasTimeUncertaintyNanos() ? clock.getTimeUncertaintyNanos() : "",
                    clock.getFullBiasNanos(),
                    clock.hasBiasNanos() ? clock.getBiasNanos() : "",
                    clock.hasBiasUncertaintyNanos() ? clock.getBiasUncertaintyNanos() : "",
                    clock.hasDriftNanosPerSecond() ? clock.getDriftNanosPerSecond() : "",
                    clock.hasDriftUncertaintyNanosPerSecond()
                            ? clock.getDriftUncertaintyNanosPerSecond()
                            : "",
                    clock.getHardwareClockDiscontinuityCount() + ",");
    mFileWriter.write(clockStream);

    String measurementStream =
            String.format(
                    "%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s",
                    measurement.getSvid(),
                    measurement.getTimeOffsetNanos(),
                    measurement.getState(),
                    measurement.getReceivedSvTimeNanos(),
                    measurement.getReceivedSvTimeUncertaintyNanos(),
                    measurement.getCn0DbHz(),
                    measurement.getPseudorangeRateMetersPerSecond(),
                    measurement.getPseudorangeRateUncertaintyMetersPerSecond(),
                    measurement.getAccumulatedDeltaRangeState(),
                    measurement.getAccumulatedDeltaRangeMeters(),
                    measurement.getAccumulatedDeltaRangeUncertaintyMeters(),
                    measurement.hasCarrierFrequencyHz() ? measurement.getCarrierFrequencyHz() : "",
                    measurement.hasCarrierCycles() ? measurement.getCarrierCycles() : "",
                    measurement.hasCarrierPhase() ? measurement.getCarrierPhase() : "",
                    measurement.hasCarrierPhaseUncertainty()
                            ? measurement.getCarrierPhaseUncertainty()
                            : "",
                    measurement.getMultipathIndicator(),
                    measurement.hasSnrInDb() ? measurement.getSnrInDb() : "",
                    measurement.getConstellationType(),
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            && measurement.hasAutomaticGainControlLevelDb()
                            ? measurement.getAutomaticGainControlLevelDb()
                            : "");
    mFileWriter.write(measurementStream);
    mFileWriter.newLine();
  }

  private void logException(String errorMessage, Exception e) {
    Log.e(MeasurementProvider.TAG + TAG, errorMessage, e);
    Toast.makeText(mContext, errorMessage, Toast.LENGTH_LONG).show();
  }

  private void logError(String errorMessage) {
    Log.e(MeasurementProvider.TAG + TAG, errorMessage);
    Toast.makeText(mContext, errorMessage, Toast.LENGTH_LONG).show();
  }

  /**
   * Implements a {@link FileFilter} to delete files that are not in the {@link
   * FileToDeleteFilter#mRetainedFiles}.
   */
  private static class FileToDeleteFilter implements FileFilter {
    private final List<File> mRetainedFiles;

    public FileToDeleteFilter(File... retainedFiles) {
      this.mRetainedFiles = Arrays.asList(retainedFiles);
    }

    /**
     * Returns {@code true} to delete the file, and {@code false} to keep the file.
     *
     * <p>Files are deleted if they are not in the {@link FileToDeleteFilter#mRetainedFiles} list.
     */
    @Override
    public boolean accept(File pathname) {
      if (pathname == null || !pathname.exists()) {
        return false;
      }
      if (mRetainedFiles.contains(pathname)) {
        return false;
      }
      return pathname.length() < MINIMUM_USABLE_FILE_SIZE_BYTES;
    }
  }
}