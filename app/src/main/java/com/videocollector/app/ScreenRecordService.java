package com.videocollector.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ScreenRecordService extends Service {

    public static final String ACTION_START =
        "com.videocollector.app.action.START_RECORD";
    public static final String ACTION_STOP =
        "com.videocollector.app.action.STOP_RECORD";

    public static final String EXTRA_RESULT_CODE =
        "result_code";
    public static final String EXTRA_RESULT_DATA =
        "result_data";
    public static final String EXTRA_USE_MIC =
        "use_mic";

    private static final String CHANNEL_ID =
        "wangparser_screen_record";
    private static final int NOTIFICATION_ID = 8801;

    private static volatile boolean running = false;

    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay virtualDisplay;
    private MediaRecorder recorder;

    private Uri outputUri;
    private ParcelFileDescriptor outputFd;
    private File legacyFile;

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(
        Intent intent,
        int flags,
        int startId
    ) {
        if (intent == null) {
            return START_NOT_STICKY;
        }

        String action = intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopRecording(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!ACTION_START.equals(action)) {
            return START_NOT_STICKY;
        }

        if (running) {
            return START_NOT_STICKY;
        }

        int resultCode =
            intent.getIntExtra(
                EXTRA_RESULT_CODE,
                0
            );

        Intent resultData;

        if (Build.VERSION.SDK_INT >= 33) {
            resultData =
                intent.getParcelableExtra(
                    EXTRA_RESULT_DATA,
                    Intent.class
                );
        } else {
            resultData =
                intent.getParcelableExtra(
                    EXTRA_RESULT_DATA
                );
        }

        boolean useMic =
            intent.getBooleanExtra(
                EXTRA_USE_MIC,
                false
            );

        if (resultData == null || resultCode == 0) {
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification notification =
            buildNotification();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int types =
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;

            if (useMic) {
                types |=
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }

            startForeground(
                NOTIFICATION_ID,
                notification,
                types
            );
        } else {
            startForeground(
                NOTIFICATION_ID,
                notification
            );
        }

        try {
            startRecording(
                resultCode,
                resultData,
                useMic
            );

            running = true;

            Toast.makeText(
                this,
                "录屏已开始。完成后返回“王的解析”点停止，或使用通知里的停止按钮。",
                Toast.LENGTH_LONG
            ).show();

        } catch (Exception e) {
            cleanupOutput(false);

            Toast.makeText(
                this,
                "录屏启动失败：" + e.getMessage(),
                Toast.LENGTH_LONG
            ).show();

            stopForeground(true);
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void startRecording(
        int resultCode,
        Intent resultData,
        boolean useMic
    ) throws Exception {
        DisplayMetrics dm =
            new DisplayMetrics();

        WindowManager wm =
            (WindowManager)
                getSystemService(WINDOW_SERVICE);

        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Rect bounds =
                wm.getCurrentWindowMetrics()
                    .getBounds();

            dm.widthPixels = bounds.width();
            dm.heightPixels = bounds.height();
            dm.densityDpi =
                getResources()
                    .getDisplayMetrics()
                    .densityDpi;
        } else {
            wm.getDefaultDisplay()
                .getRealMetrics(dm);
        }

        int width =
            makeEven(dm.widthPixels);

        int height =
            makeEven(dm.heightPixels);

        int density =
            dm.densityDpi;

        recorder = new MediaRecorder();

        if (useMic) {
            recorder.setAudioSource(
                MediaRecorder.AudioSource.MIC
            );
        }

        recorder.setVideoSource(
            MediaRecorder.VideoSource.SURFACE
        );

        recorder.setOutputFormat(
            MediaRecorder.OutputFormat.MPEG_4
        );

        prepareOutputFile();
        setRecorderOutput();

        recorder.setVideoEncoder(
            MediaRecorder.VideoEncoder.H264
        );

        recorder.setVideoEncodingBitRate(
            Math.max(
                4_000_000,
                Math.min(
                    12_000_000,
                    width * height * 6
                )
            )
        );

        recorder.setVideoFrameRate(30);
        recorder.setVideoSize(width, height);

        if (useMic) {
            recorder.setAudioEncoder(
                MediaRecorder.AudioEncoder.AAC
            );
            recorder.setAudioEncodingBitRate(
                128_000
            );
            recorder.setAudioSamplingRate(
                44_100
            );
        }

        recorder.prepare();

        MediaProjectionManager mpm =
            (MediaProjectionManager)
                getSystemService(
                    MEDIA_PROJECTION_SERVICE
                );

        projection =
            mpm.getMediaProjection(
                resultCode,
                resultData
            );

        if (projection == null) {
            throw new IllegalStateException(
                "系统未返回 MediaProjection"
            );
        }

        projectionCallback =
            new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    if (running) {
                        stopRecording(true);
                        stopSelf();
                    }
                }
            };

        projection.registerCallback(
            projectionCallback,
            null
        );

        virtualDisplay =
            projection.createVirtualDisplay(
                "WangParserScreenRecord",
                width,
                height,
                density,
                DisplayManager
                    .VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.getSurface(),
                null,
                null
            );

        recorder.start();
    }

    private int makeEven(int value) {
        return value % 2 == 0
            ? value
            : value - 1;
    }

    private void prepareOutputFile()
        throws Exception {
        String stamp =
            new SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                Locale.US
            ).format(new Date());

        String fileName =
            "王的解析_录屏_"
                + stamp
                + ".mp4";

        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values =
                new ContentValues();

            values.put(
                MediaStore.Video.Media.DISPLAY_NAME,
                fileName
            );

            values.put(
                MediaStore.Video.Media.MIME_TYPE,
                "video/mp4"
            );

            values.put(
                MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES
                    + "/WangParser/ScreenRecords"
            );

            values.put(
                MediaStore.Video.Media.IS_PENDING,
                1
            );

            ContentResolver resolver =
                getContentResolver();

            outputUri =
                resolver.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    values
                );

            if (outputUri == null) {
                throw new IllegalStateException(
                    "无法创建录屏文件"
                );
            }

            outputFd =
                resolver.openFileDescriptor(
                    outputUri,
                    "w"
                );

            if (outputFd == null) {
                throw new IllegalStateException(
                    "无法打开录屏文件"
                );
            }

        } else {
            File dir =
                new File(
                    Environment
                        .getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_MOVIES
                        ),
                    "WangParser/ScreenRecords"
                );

            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException(
                    "无法创建录屏目录"
                );
            }

            legacyFile =
                new File(
                    dir,
                    fileName
                );
        }
    }

    private void setRecorderOutput()
        throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            recorder.setOutputFile(
                outputFd.getFileDescriptor()
            );
        } else {
            recorder.setOutputFile(
                legacyFile.getAbsolutePath()
            );
        }
    }

    private void stopRecording(
        boolean keepFile
    ) {
        if (!running && recorder == null) {
            return;
        }

        boolean good = keepFile;

        try {
            if (recorder != null) {
                recorder.stop();
            }
        } catch (Exception e) {
            good = false;
        }

        try {
            if (virtualDisplay != null) {
                virtualDisplay.release();
            }
        } catch (Exception ignored) {
        }

        try {
            if (projection != null) {
                if (projectionCallback != null) {
                    projection.unregisterCallback(
                        projectionCallback
                    );
                }
                projection.stop();
            }
        } catch (Exception ignored) {
        }

        try {
            if (recorder != null) {
                recorder.reset();
                recorder.release();
            }
        } catch (Exception ignored) {
        }

        recorder = null;
        virtualDisplay = null;
        projection = null;
        projectionCallback = null;
        running = false;

        cleanupOutput(good);

        stopForeground(true);

        Toast.makeText(
            this,
            good
                ? "录屏已停止并保存到相册：Movies/WangParser/ScreenRecords"
                : "录屏没有形成有效视频，已删除。",
            Toast.LENGTH_LONG
        ).show();
    }

    private void cleanupOutput(
        boolean keep
    ) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                if (outputFd != null) {
                    outputFd.close();
                }
            } catch (Exception ignored) {
            }

            if (outputUri != null) {
                if (keep) {
                    ContentValues values =
                        new ContentValues();

                    values.put(
                        MediaStore.Video.Media.IS_PENDING,
                        0
                    );

                    try {
                        getContentResolver()
                            .update(
                                outputUri,
                                values,
                                null,
                                null
                            );
                    } catch (Exception ignored) {
                    }
                } else {
                    try {
                        getContentResolver()
                            .delete(
                                outputUri,
                                null,
                                null
                            );
                    } catch (Exception ignored) {
                    }
                }
            }

        } else if (!keep && legacyFile != null) {
            try {
                legacyFile.delete();
            } catch (Exception ignored) {
            }
        }

        outputFd = null;
        outputUri = null;
        legacyFile = null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }

        NotificationChannel channel =
            new NotificationChannel(
                CHANNEL_ID,
                "王的解析录屏",
                NotificationManager.IMPORTANCE_LOW
            );

        channel.setDescription(
            "系统录屏保存状态"
        );

        NotificationManager nm =
            (NotificationManager)
                getSystemService(
                    NOTIFICATION_SERVICE
                );

        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent stop =
            new Intent(
                this,
                ScreenRecordService.class
            );
        stop.setAction(ACTION_STOP);

        PendingIntent pendingStop =
            PendingIntent.getService(
                this,
                1,
                stop,
                PendingIntent.FLAG_UPDATE_CURRENT
                    | PendingIntent.FLAG_IMMUTABLE
            );

        Notification.Builder builder;

        if (Build.VERSION.SDK_INT >= 26) {
            builder =
                new Notification.Builder(
                    this,
                    CHANNEL_ID
                );
        } else {
            builder =
                new Notification.Builder(this);
        }

        return builder
            .setContentTitle("王的解析正在录屏")
            .setContentText("点击“停止并保存”结束录屏")
            .setSmallIcon(
                android.R.drawable.presence_video_online
            )
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "停止并保存",
                pendingStop
            )
            .build();
    }

    @Override
    public void onDestroy() {
        if (running) {
            stopRecording(true);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
