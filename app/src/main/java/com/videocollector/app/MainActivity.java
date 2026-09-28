package com.videocollector.app;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_STORAGE = 100;
    private static final int REQ_SCREEN_CAPTURE = 2001;
    private static final int REQ_AUDIO = 2002;

    private LinearLayout root;
    private WebView webView;

    private LinearLayout downloadPanel;
    private TextView downloadText;
    private ProgressBar downloadProgress;

    private DownloadManager downloadManager;
    private Handler handler;

    private long currentDownloadId = -1L;
    private long lastBytes = 0L;
    private long lastSampleTime = 0L;

    private String pendingRecordUrl = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        downloadManager =
            (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);

        handler = new Handler(Looper.getMainLooper());

        buildLayout();
        configureWebView();

        webView.loadUrl("file:///android_asset/index.html");
    }

    private void buildLayout() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101317);

        webView = new WebView(this);

        root.addView(
            webView,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        );

        downloadPanel = new LinearLayout(this);
        downloadPanel.setOrientation(LinearLayout.VERTICAL);
        downloadPanel.setPadding(22, 12, 22, 16);
        downloadPanel.setBackgroundColor(0xFF181D23);
        downloadPanel.setVisibility(android.view.View.GONE);

        downloadText = new TextView(this);
        downloadText.setTextColor(0xFFF4F7FA);
        downloadText.setTextSize(14f);

        downloadProgress = new ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        );
        downloadProgress.setMax(100);

        downloadPanel.addView(downloadText);

        LinearLayout.LayoutParams p =
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                22
            );
        p.topMargin = 8;
        downloadPanel.addView(downloadProgress, p);

        root.addView(downloadPanel);

        setContentView(root);
    }

    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setUserAgentString(
            s.getUserAgentString() + " WangParser/8.0.0"
        );

        webView.addJavascriptInterface(
            new AndroidClipboard(),
            "AndroidClipboard"
        );

        webView.addJavascriptInterface(
            new AndroidDownloader(),
            "AndroidDownloader"
        );

        webView.addJavascriptInterface(
            new AndroidExternal(),
            "AndroidExternal"
        );

        webView.addJavascriptInterface(
            new AndroidScreenRecorder(),
            "AndroidScreenRecorder"
        );

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
    }

    public class AndroidClipboard {
        @JavascriptInterface
        public String getText() {
            try {
                ClipboardManager cm =
                    (ClipboardManager)
                        getSystemService(Context.CLIPBOARD_SERVICE);

                if (cm == null || !cm.hasPrimaryClip()) {
                    return "";
                }

                ClipData clip = cm.getPrimaryClip();

                if (clip == null || clip.getItemCount() == 0) {
                    return "";
                }

                CharSequence value =
                    clip.getItemAt(0)
                        .coerceToText(MainActivity.this);

                return value == null ? "" : value.toString();

            } catch (Exception e) {
                return "";
            }
        }
    }

    public class AndroidExternal {
        @JavascriptInterface
        public void open(final String raw) {
            runOnUiThread(() -> openExternal(raw));
        }
    }

    public class AndroidScreenRecorder {

        @JavascriptInterface
        public void start(final String rawUrl) {
            runOnUiThread(() -> {
                pendingRecordUrl =
                    extractFirstHttpUrl(rawUrl);

                if (pendingRecordUrl == null) {
                    pendingRecordUrl = "";
                }

                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(
                        new String[]{Manifest.permission.RECORD_AUDIO},
                        REQ_AUDIO
                    );
                    return;
                }

                requestScreenCapture();
            });
        }

        @JavascriptInterface
        public void stop() {
            runOnUiThread(() -> {
                if (!ScreenRecordService.isRunning()) {
                    Toast.makeText(
                        MainActivity.this,
                        "当前没有正在进行的录屏。",
                        Toast.LENGTH_SHORT
                    ).show();
                    return;
                }

                Intent i =
                    new Intent(
                        MainActivity.this,
                        ScreenRecordService.class
                    );
                i.setAction(ScreenRecordService.ACTION_STOP);
                startService(i);
            });
        }

        @JavascriptInterface
        public boolean isRecording() {
            return ScreenRecordService.isRunning();
        }
    }

    private void requestScreenCapture() {
        MediaProjectionManager mpm =
            (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        if (mpm == null) {
            Toast.makeText(
                this,
                "当前设备不支持系统录屏授权。",
                Toast.LENGTH_LONG
            ).show();
            return;
        }

        try {
            startActivityForResult(
                mpm.createScreenCaptureIntent(),
                REQ_SCREEN_CAPTURE
            );
        } catch (Exception e) {
            Toast.makeText(
                this,
                "无法启动系统录屏授权：" + e.getMessage(),
                Toast.LENGTH_LONG
            ).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(
        int requestCode,
        String[] permissions,
        int[] grantResults
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        );

        if (requestCode == REQ_AUDIO) {
            // Even if microphone permission is denied, screen video can still be recorded.
            requestScreenCapture();
        }
    }

    @Override
    protected void onActivityResult(
        int requestCode,
        int resultCode,
        Intent data
    ) {
        super.onActivityResult(
            requestCode,
            resultCode,
            data
        );

        if (requestCode != REQ_SCREEN_CAPTURE) {
            return;
        }

        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(
                this,
                "你取消了系统录屏授权。",
                Toast.LENGTH_SHORT
            ).show();
            return;
        }

        boolean useMic =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M
            || checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;

        Intent service =
            new Intent(this, ScreenRecordService.class);

        service.setAction(ScreenRecordService.ACTION_START);
        service.putExtra(
            ScreenRecordService.EXTRA_RESULT_CODE,
            resultCode
        );
        service.putExtra(
            ScreenRecordService.EXTRA_RESULT_DATA,
            data
        );
        service.putExtra(
            ScreenRecordService.EXTRA_USE_MIC,
            useMic
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service);
        } else {
            startService(service);
        }

        Toast.makeText(
            this,
            useMic
                ? "录屏已开始。麦克风已启用；它录的是环境/扬声器声音，不代表平台内部音频。"
                : "录屏已开始。当前只录画面。",
            Toast.LENGTH_LONG
        ).show();

        handler.postDelayed(
            () -> {
                if (
                    pendingRecordUrl != null
                    && !pendingRecordUrl.isEmpty()
                ) {
                    openExternal(pendingRecordUrl);
                }
            },
            900
        );
    }

    private void openExternal(String raw) {
        String url = extractFirstHttpUrl(raw);

        if (url == null) {
            Toast.makeText(
                this,
                "没有识别到可打开的链接。",
                Toast.LENGTH_LONG
            ).show();
            return;
        }

        try {
            Intent i =
                new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(url)
                );
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);

        } catch (Exception e) {
            Toast.makeText(
                this,
                "无法打开链接：" + e.getMessage(),
                Toast.LENGTH_LONG
            ).show();
        }
    }

    public class AndroidDownloader {

        @JavascriptInterface
        public void download(
            final String url,
            final String filename,
            final String referer
        ) {
            runOnUiThread(
                () ->
                    validateBeforeDownload(
                        url,
                        filename,
                        referer
                    )
            );
        }
    }

    private interface BoolCallback {
        void onResult(boolean value);
    }

    private void validateBeforeDownload(
        String url,
        String filename,
        String referer
    ) {
        String lower =
            filename == null
                ? ""
                : filename.toLowerCase(Locale.US);

        boolean isVideo =
            lower.endsWith(".mp4")
            || lower.endsWith(".webm")
            || lower.endsWith(".mov")
            || lower.endsWith(".mkv");

        if (!isVideo) {
            startDownload(url, filename, referer);
            return;
        }

        validateVideoAsync(
            url,
            referer,
            ok -> {
                if (!ok) {
                    Toast.makeText(
                        MainActivity.this,
                        "视频地址真实性校验失败，已阻止保存。不会生成 0 秒白屏文件。",
                        Toast.LENGTH_LONG
                    ).show();
                    return;
                }

                startDownload(
                    url,
                    filename,
                    referer
                );
            }
        );
    }

    private void validateVideoAsync(
        final String url,
        final String referer,
        final BoolCallback callback
    ) {
        new Thread(
            () -> {
                boolean valid = false;
                HttpURLConnection c = null;

                try {
                    c =
                        (HttpURLConnection)
                            new URL(url)
                                .openConnection();

                    c.setInstanceFollowRedirects(true);
                    c.setConnectTimeout(7000);
                    c.setReadTimeout(7000);

                    c.setRequestProperty(
                        "User-Agent",
                        webView.getSettings()
                            .getUserAgentString()
                    );

                    if (
                        referer != null
                        && !referer.trim().isEmpty()
                    ) {
                        c.setRequestProperty(
                            "Referer",
                            referer
                        );
                    }

                    c.setRequestProperty(
                        "Range",
                        "bytes=0-4095"
                    );

                    int status =
                        c.getResponseCode();

                    String type =
                        c.getContentType();

                    InputStream in =
                        status >= 200 && status < 400
                            ? c.getInputStream()
                            : null;

                    byte[] head = new byte[256];

                    int n =
                        in != null
                            ? in.read(head)
                            : -1;

                    if (in != null) {
                        in.close();
                    }

                    String lowType =
                        type == null
                            ? ""
                            : type.toLowerCase(Locale.US);

                    String prefix =
                        n > 0
                            ? new String(
                                head,
                                0,
                                n,
                                StandardCharsets.ISO_8859_1
                            )
                            : "";

                    boolean html =
                        lowType.contains("text/html")
                        || prefix
                            .toLowerCase(Locale.US)
                            .contains("<html");

                    boolean video =
                        lowType.startsWith("video/")
                        || prefix.contains("ftyp")
                        || prefix.contains("webm");

                    valid =
                        (
                            status == 200
                            || status == 206
                        )
                        && !html
                        && video;

                } catch (Exception ignored) {
                    valid = false;

                } finally {
                    if (c != null) {
                        c.disconnect();
                    }
                }

                final boolean result = valid;

                handler.post(
                    () -> callback.onResult(result)
                );
            }
        ).start();
    }

    private void startDownload(
        String url,
        String filename,
        String referer
    ) {
        if (
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
            && checkSelfPermission(
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
                != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                new String[]{
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                },
                REQ_STORAGE
            );

            Toast.makeText(
                this,
                "请允许存储权限后重新点击下载。",
                Toast.LENGTH_LONG
            ).show();
            return;
        }

        try {
            filename = sanitize(filename);

            DownloadManager.Request req =
                new DownloadManager.Request(
                    Uri.parse(url)
                );

            req.setNotificationVisibility(
                DownloadManager.Request
                    .VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            );

            req.addRequestHeader(
                "User-Agent",
                webView.getSettings()
                    .getUserAgentString()
            );

            if (
                referer != null
                && !referer.trim().isEmpty()
            ) {
                req.addRequestHeader(
                    "Referer",
                    referer
                );
            }

            req.setMimeType(
                guessMimeType(filename)
            );

            req.setTitle(filename);

            req.setDestinationInExternalPublicDir(
                getTargetDirectory(filename),
                "WangParser/" + filename
            );

            currentDownloadId =
                downloadManager.enqueue(req);

            lastBytes = 0L;
            lastSampleTime =
                System.currentTimeMillis();

            downloadPanel.setVisibility(
                android.view.View.VISIBLE
            );

            downloadProgress.setIndeterminate(false);
            downloadProgress.setProgress(0);

            downloadText.setText(
                "正在下载：" + filename + "  0%"
            );

            poll(
                currentDownloadId,
                filename
            );

        } catch (Exception e) {
            Toast.makeText(
                this,
                "下载启动失败：" + e.getMessage(),
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void poll(
        final long id,
        final String filename
    ) {
        handler.postDelayed(
            () -> {
                if (id != currentDownloadId) {
                    return;
                }

                DownloadManager.Query q =
                    new DownloadManager.Query()
                        .setFilterById(id);

                Cursor c = null;

                try {
                    c = downloadManager.query(q);

                    if (
                        c == null
                        || !c.moveToFirst()
                    ) {
                        poll(id, filename);
                        return;
                    }

                    int status =
                        c.getInt(
                            c.getColumnIndexOrThrow(
                                DownloadManager.COLUMN_STATUS
                            )
                        );

                    long done =
                        c.getLong(
                            c.getColumnIndexOrThrow(
                                DownloadManager
                                    .COLUMN_BYTES_DOWNLOADED_SO_FAR
                            )
                        );

                    long total =
                        c.getLong(
                            c.getColumnIndexOrThrow(
                                DownloadManager
                                    .COLUMN_TOTAL_SIZE_BYTES
                            )
                        );

                    if (
                        status
                            == DownloadManager.STATUS_SUCCESSFUL
                    ) {
                        downloadProgress.setIndeterminate(false);
                        downloadProgress.setProgress(100);

                        downloadText.setText(
                            "下载完成：" + filename + "  100%"
                        );

                        Toast.makeText(
                            this,
                            "下载完成。",
                            Toast.LENGTH_LONG
                        ).show();

                        currentDownloadId = -1L;
                        return;
                    }

                    if (
                        status
                            == DownloadManager.STATUS_FAILED
                    ) {
                        downloadText.setText(
                            "下载失败：" + filename
                        );
                        currentDownloadId = -1L;
                        return;
                    }

                    long now =
                        System.currentTimeMillis();

                    long deltaMs =
                        Math.max(
                            1L,
                            now - lastSampleTime
                        );

                    long deltaBytes =
                        Math.max(
                            0L,
                            done - lastBytes
                        );

                    double speedMB =
                        deltaBytes
                            / 1024.0
                            / 1024.0
                            / (deltaMs / 1000.0);

                    lastBytes = done;
                    lastSampleTime = now;

                    double doneMB =
                        done / 1024.0 / 1024.0;

                    if (total > 0) {
                        int percent =
                            (int) (
                                done * 100L / total
                            );

                        double totalMB =
                            total / 1024.0 / 1024.0;

                        downloadProgress
                            .setIndeterminate(false);
                        downloadProgress
                            .setProgress(percent);

                        downloadText.setText(
                            String.format(
                                Locale.US,
                                "正在下载：%s  %d%%  %.1f / %.1f MB  %.2f MB/s",
                                filename,
                                percent,
                                doneMB,
                                totalMB,
                                speedMB
                            )
                        );

                    } else {
                        downloadProgress.setIndeterminate(true);

                        downloadText.setText(
                            String.format(
                                Locale.US,
                                "正在下载：%s  %.1f MB  %.2f MB/s",
                                filename,
                                doneMB,
                                speedMB
                            )
                        );
                    }

                    poll(id, filename);

                } catch (Exception e) {
                    poll(id, filename);

                } finally {
                    if (c != null) {
                        c.close();
                    }
                }
            },
            250
        );
    }

    private String sanitize(String s) {
        if (s == null || s.trim().isEmpty()) {
            return "download";
        }

        return s.replaceAll(
            "[\\\\/:*?\"<>|]",
            "_"
        );
    }

    private String guessMimeType(
        String filename
    ) {
        String lower =
            filename == null
                ? ""
                : filename.toLowerCase(Locale.US);

        if (lower.endsWith(".mp4"))
            return "video/mp4";
        if (lower.endsWith(".webm"))
            return "video/webm";
        if (lower.endsWith(".mkv"))
            return "video/x-matroska";
        if (lower.endsWith(".mov"))
            return "video/quicktime";
        if (lower.endsWith(".mp3"))
            return "audio/mpeg";
        if (lower.endsWith(".m4a"))
            return "audio/mp4";
        if (lower.endsWith(".aac"))
            return "audio/aac";
        if (lower.endsWith(".wav"))
            return "audio/wav";
        if (lower.endsWith(".ogg"))
            return "audio/ogg";

        return "application/octet-stream";
    }

    private String getTargetDirectory(
        String filename
    ) {
        String lower =
            filename == null
                ? ""
                : filename.toLowerCase(Locale.US);

        if (
            lower.endsWith(".mp4")
            || lower.endsWith(".webm")
            || lower.endsWith(".mkv")
            || lower.endsWith(".mov")
        ) {
            return Environment.DIRECTORY_MOVIES;
        }

        if (
            lower.endsWith(".mp3")
            || lower.endsWith(".m4a")
            || lower.endsWith(".aac")
            || lower.endsWith(".wav")
            || lower.endsWith(".ogg")
        ) {
            return Environment.DIRECTORY_MUSIC;
        }

        return Environment.DIRECTORY_DOWNLOADS;
    }

    private String extractFirstHttpUrl(
        String text
    ) {
        if (text == null) {
            return null;
        }

        int h1 = text.indexOf("http://");
        int h2 = text.indexOf("https://");

        int start;

        if (h1 < 0) {
            start = h2;
        } else if (h2 < 0) {
            start = h1;
        } else {
            start = Math.min(h1, h2);
        }

        if (start < 0) {
            return null;
        }

        int end = start;

        while (end < text.length()) {
            char c = text.charAt(end);

            if (
                Character.isWhitespace(c)
                || c == '，'
                || c == '。'
                || c == ','
                || c == ';'
                || c == '；'
                || c == ')'
                || c == '）'
            ) {
                break;
            }

            end++;
        }

        String u =
            text.substring(start, end);

        return u.replaceAll(
            "[，。,.；;）)]+$",
            ""
        );
    }
}
