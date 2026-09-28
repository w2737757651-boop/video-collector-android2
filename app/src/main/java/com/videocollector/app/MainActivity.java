package com.videocollector.app;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_STORAGE = 100;

    private WebView webView;
    private LinearLayout downloadPanel;
    private TextView downloadText;
    private ProgressBar downloadProgress;

    private DownloadManager downloadManager;
    private Handler handler;

    private long currentDownloadId = -1L;
    private long lastBytes = 0L;
    private long lastSampleTime = 0L;

    private Class<?> nativeCoreClass;
    private String nativeCoreClassName = "";


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        downloadManager =
            (DownloadManager)
                getSystemService(
                    Context.DOWNLOAD_SERVICE
                );

        handler =
            new Handler(
                Looper.getMainLooper()
            );

        LinearLayout root =
            new LinearLayout(this);

        root.setOrientation(
            LinearLayout.VERTICAL
        );

        root.setBackgroundColor(
            0xFF101317
        );

        webView =
            new WebView(this);

        root.addView(
            webView,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        );

        downloadPanel =
            new LinearLayout(this);

        downloadPanel.setOrientation(
            LinearLayout.VERTICAL
        );

        downloadPanel.setPadding(
            22,
            12,
            22,
            16
        );

        downloadPanel.setBackgroundColor(
            0xFF181D23
        );

        downloadPanel.setVisibility(
            android.view.View.GONE
        );

        downloadText =
            new TextView(this);

        downloadText.setTextColor(
            0xFFF4F7FA
        );

        downloadText.setTextSize(
            14f
        );

        downloadProgress =
            new ProgressBar(
                this,
                null,
                android.R.attr.progressBarStyleHorizontal
            );

        downloadProgress.setMax(100);

        downloadPanel.addView(
            downloadText
        );

        LinearLayout.LayoutParams progressParams =
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                22
            );

        progressParams.topMargin = 8;

        downloadPanel.addView(
            downloadProgress,
            progressParams
        );

        root.addView(
            downloadPanel
        );

        setContentView(root);

        configureWebView();
        initNativeCore();

        webView.loadUrl(
            "file:///android_asset/index.html"
        );
    }


    private void configureWebView() {
        WebSettings s =
            webView.getSettings();

        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);

        s.setUserAgentString(
            s.getUserAgentString()
                + " VideoCollectorApp/7.0"
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
            new AndroidNativeParser(),
            "AndroidNativeParser"
        );

        webView.setWebChromeClient(
            new WebChromeClient()
        );

        webView.setWebViewClient(
            new WebViewClient()
        );
    }


    private void initNativeCore() {
        try {
            nativeCoreClassName =
                readAssetText(
                    "native_class.txt"
                ).trim();

            if (
                nativeCoreClassName.isEmpty()
            ) {
                throw new Exception(
                    "native_class.txt 为空"
                );
            }

            nativeCoreClass =
                Class.forName(
                    nativeCoreClassName
                );

            Method init =
                findNativeMethod(
                    "init",
                    1
                );

            if (init != null) {
                init.invoke(
                    null,
                    getFilesDir()
                        .getAbsolutePath()
                );
            }

        } catch (Exception e) {
            nativeCoreClass = null;

            Toast.makeText(
                this,
                "本地 Go 解析引擎加载失败："
                    + e.getMessage(),
                Toast.LENGTH_LONG
            ).show();
        }
    }


    private String readAssetText(
        String name
    ) throws Exception {
        InputStream in =
            getAssets().open(
                name
            );

        BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(
                    in,
                    StandardCharsets.UTF_8
                )
            );

        StringBuilder sb =
            new StringBuilder();

        String line;

        while (
            (line = reader.readLine())
                != null
        ) {
            sb.append(line);
        }

        reader.close();
        in.close();

        return sb.toString();
    }


    private Method findNativeMethod(
        String name,
        int parameterCount
    ) {
        if (
            nativeCoreClass == null
        ) {
            return null;
        }

        for (
            Method method :
                nativeCoreClass.getMethods()
        ) {
            if (
                method.getName()
                    .equalsIgnoreCase(name)
                && method
                    .getParameterTypes()
                    .length
                    == parameterCount
                && Modifier.isStatic(
                    method.getModifiers()
                )
            ) {
                return method;
            }
        }

        return null;
    }


    public class AndroidNativeParser {

        @JavascriptInterface
        public void parse(
            final String text
        ) {
            new Thread(
                () -> {
                    String output;

                    try {
                        if (
                            nativeCoreClass
                                == null
                        ) {
                            throw new Exception(
                                "本地 Go 引擎未加载"
                            );
                        }

                        Method parse =
                            findNativeMethod(
                                "parse",
                                1
                            );

                        if (parse == null) {
                            throw new Exception(
                                "没有找到本地 Parse 方法"
                            );
                        }

                        Object value =
                            parse.invoke(
                                null,
                                text
                            );

                        output =
                            value == null
                                ? ""
                                : String.valueOf(
                                    value
                                );

                        if (
                            output.trim()
                                .isEmpty()
                        ) {
                            throw new Exception(
                                "本地解析器返回空结果"
                            );
                        }

                    } catch (
                        Exception e
                    ) {
                        try {
                            JSONObject err =
                                new JSONObject();

                            err.put(
                                "success",
                                false
                            );

                            err.put(
                                "error",
                                "本地 Go 解析失败："
                                    + e.getMessage()
                            );

                            err.put(
                                "code",
                                "NATIVE_BRIDGE"
                            );

                            output =
                                err.toString();

                        } catch (
                            Exception ignored
                        ) {
                            output =
                                "{\"success\":false,\"error\":\"本地解析桥接失败\"}";
                        }
                    }

                    final String result =
                        output;

                    handler.post(
                        () -> {
                            String quoted =
                                JSONObject.quote(
                                    result
                                );

                            webView
                                .evaluateJavascript(
                                    "window.onNativeParseResult && "
                                        + "window.onNativeParseResult(JSON.parse("
                                        + quoted
                                        + "));",
                                    null
                                );
                        }
                    );
                }
            ).start();
        }
    }


    public class AndroidClipboard {

        @JavascriptInterface
        public String getText() {
            try {
                ClipboardManager cm =
                    (ClipboardManager)
                        getSystemService(
                            Context.CLIPBOARD_SERVICE
                        );

                if (
                    cm == null
                    || !cm.hasPrimaryClip()
                ) {
                    return "";
                }

                ClipData clip =
                    cm.getPrimaryClip();

                if (
                    clip == null
                    || clip.getItemCount()
                        == 0
                ) {
                    return "";
                }

                CharSequence value =
                    clip.getItemAt(0)
                        .coerceToText(
                            MainActivity.this
                        );

                return value == null
                    ? ""
                    : value.toString();

            } catch (Exception e) {
                return "";
            }
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
        void onResult(
            boolean value
        );
    }


    private void validateBeforeDownload(
        String url,
        String filename,
        String referer
    ) {
        String lower =
            filename == null
                ? ""
                : filename.toLowerCase(
                    Locale.US
                );

        boolean isVideo =
            lower.endsWith(".mp4")
            || lower.endsWith(".webm")
            || lower.endsWith(".mov");

        if (!isVideo) {
            startDownload(
                url,
                filename,
                referer
            );

            return;
        }

        validateVideoAsync(
            url,
            referer,
            ok -> {
                if (!ok) {
                    Toast.makeText(
                        MainActivity.this,
                        "视频地址校验失败，已阻止保存，避免生成 0 秒白屏文件。",
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

                    c.setInstanceFollowRedirects(
                        true
                    );

                    c.setConnectTimeout(
                        6000
                    );

                    c.setReadTimeout(
                        6000
                    );

                    c.setRequestProperty(
                        "User-Agent",
                        webView
                            .getSettings()
                            .getUserAgentString()
                    );

                    if (
                        referer != null
                        && !referer
                            .trim()
                            .isEmpty()
                    ) {
                        c.setRequestProperty(
                            "Referer",
                            referer
                        );
                    }

                    c.setRequestProperty(
                        "Range",
                        "bytes=0-2047"
                    );

                    int status =
                        c.getResponseCode();

                    String type =
                        c.getContentType();

                    InputStream in =
                        (
                            status >= 200
                            && status < 400
                        )
                            ? c.getInputStream()
                            : null;

                    byte[] head =
                        new byte[96];

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
                            : type.toLowerCase(
                                Locale.US
                            );

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
                        lowType.contains(
                            "text/html"
                        )
                        || prefix
                            .toLowerCase(
                                Locale.US
                            )
                            .contains(
                                "<html"
                            );

                    boolean video =
                        lowType.startsWith(
                            "video/"
                        )
                        || prefix.contains(
                            "ftyp"
                        );

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

                final boolean result =
                    valid;

                handler.post(
                    () ->
                        callback.onResult(
                            result
                        )
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
            Build.VERSION.SDK_INT
                <= Build.VERSION_CODES.P
            && checkSelfPermission(
                Manifest.permission
                    .WRITE_EXTERNAL_STORAGE
            )
                != PackageManager
                    .PERMISSION_GRANTED
        ) {
            requestPermissions(
                new String[]{
                    Manifest.permission
                        .WRITE_EXTERNAL_STORAGE
                },
                REQ_STORAGE
            );

            Toast.makeText(
                this,
                "请允许存储权限后重新点击下载",
                Toast.LENGTH_LONG
            ).show();

            return;
        }

        try {
            filename =
                sanitize(
                    filename
                );

            DownloadManager.Request req =
                new DownloadManager.Request(
                    Uri.parse(
                        url
                    )
                );

            req.setNotificationVisibility(
                DownloadManager.Request
                    .VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            );

            req.addRequestHeader(
                "User-Agent",
                webView
                    .getSettings()
                    .getUserAgentString()
            );

            if (
                referer != null
                && !referer
                    .trim()
                    .isEmpty()
            ) {
                req.addRequestHeader(
                    "Referer",
                    referer
                );
            }

            req.setMimeType(
                guessMimeType(
                    filename
                )
            );

            req.setTitle(
                filename
            );

            req.setDestinationInExternalPublicDir(
                getTargetDirectory(
                    filename
                ),
                "VideoCollector/"
                    + filename
            );

            currentDownloadId =
                downloadManager.enqueue(
                    req
                );

            lastBytes = 0L;
            lastSampleTime =
                System.currentTimeMillis();

            downloadPanel.setVisibility(
                android.view.View.VISIBLE
            );

            downloadProgress.setIndeterminate(
                false
            );

            downloadProgress.setProgress(
                0
            );

            downloadText.setText(
                "正在下载："
                    + filename
                    + "  0%"
            );

            poll(
                currentDownloadId,
                filename
            );

        } catch (Exception e) {
            Toast.makeText(
                this,
                "下载启动失败："
                    + e.getMessage(),
                Toast.LENGTH_LONG
            ).show();
        }
    }


    private String sanitize(
        String s
    ) {
        if (
            s == null
            || s.trim()
                .isEmpty()
        ) {
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
                : filename.toLowerCase(
                    Locale.US
                );

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
                : filename.toLowerCase(
                    Locale.US
                );

        if (
            lower.endsWith(".mp4")
            || lower.endsWith(".webm")
            || lower.endsWith(".mkv")
            || lower.endsWith(".mov")
        ) {
            return Environment
                .DIRECTORY_MOVIES;
        }

        if (
            lower.endsWith(".mp3")
            || lower.endsWith(".m4a")
            || lower.endsWith(".aac")
            || lower.endsWith(".wav")
            || lower.endsWith(".ogg")
        ) {
            return Environment
                .DIRECTORY_MUSIC;
        }

        return Environment
            .DIRECTORY_DOWNLOADS;
    }


    private void poll(
        final long id,
        final String filename
    ) {
        handler.postDelayed(
            () -> {
                if (
                    id
                        != currentDownloadId
                ) {
                    return;
                }

                DownloadManager.Query q =
                    new DownloadManager.Query()
                        .setFilterById(
                            id
                        );

                Cursor c = null;

                try {
                    c =
                        downloadManager.query(
                            q
                        );

                    if (
                        c == null
                        || !c.moveToFirst()
                    ) {
                        poll(
                            id,
                            filename
                        );

                        return;
                    }

                    int status =
                        c.getInt(
                            c.getColumnIndexOrThrow(
                                DownloadManager
                                    .COLUMN_STATUS
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
                            == DownloadManager
                                .STATUS_SUCCESSFUL
                    ) {
                        downloadProgress
                            .setIndeterminate(
                                false
                            );

                        downloadProgress
                            .setProgress(
                                100
                            );

                        downloadText.setText(
                            "下载完成："
                                + filename
                                + "  100%"
                        );

                        try {
                            int uriIndex =
                                c.getColumnIndex(
                                    DownloadManager
                                        .COLUMN_LOCAL_URI
                                );

                            if (
                                uriIndex
                                    >= 0
                            ) {
                                String localUri =
                                    c.getString(
                                        uriIndex
                                    );

                                if (
                                    localUri != null
                                    && localUri
                                        .startsWith(
                                            "file://"
                                        )
                                ) {
                                    String localPath =
                                        Uri.parse(
                                            localUri
                                        )
                                            .getPath();

                                    if (
                                        localPath != null
                                    ) {
                                        MediaScannerConnection
                                            .scanFile(
                                                MainActivity.this,
                                                new String[]{
                                                    localPath
                                                },
                                                new String[]{
                                                    guessMimeType(
                                                        filename
                                                    )
                                                },
                                                null
                                            );
                                    }
                                }
                            }

                        } catch (
                            Exception ignored
                        ) {
                        }

                        String lower =
                            filename.toLowerCase(
                                Locale.US
                            );

                        boolean isVideo =
                            lower.endsWith(
                                ".mp4"
                            )
                            || lower.endsWith(
                                ".webm"
                            )
                            || lower.endsWith(
                                ".mkv"
                            )
                            || lower.endsWith(
                                ".mov"
                            );

                        Toast.makeText(
                            this,
                            isVideo
                                ? "下载完成，已保存到 Movies/VideoCollector，并已通知系统相册"
                                : "下载完成，已保存到 Music/VideoCollector",
                            Toast.LENGTH_LONG
                        ).show();

                        currentDownloadId =
                            -1L;

                        return;
                    }

                    if (
                        status
                            == DownloadManager
                                .STATUS_FAILED
                    ) {
                        downloadText.setText(
                            "下载失败："
                                + filename
                        );

                        currentDownloadId =
                            -1L;

                        return;
                    }

                    long now =
                        System.currentTimeMillis();

                    long deltaMs =
                        Math.max(
                            1L,
                            now
                                - lastSampleTime
                        );

                    long deltaBytes =
                        Math.max(
                            0L,
                            done
                                - lastBytes
                        );

                    double speedMB =
                        deltaBytes
                            / 1024.0
                            / 1024.0
                            / (
                                deltaMs
                                    / 1000.0
                            );

                    lastBytes =
                        done;

                    lastSampleTime =
                        now;

                    double doneMB =
                        done
                            / 1024.0
                            / 1024.0;

                    if (
                        total > 0
                    ) {
                        int percent =
                            (int)
                                (
                                    done
                                        * 100L
                                        / total
                                );

                        double totalMB =
                            total
                                / 1024.0
                                / 1024.0;

                        downloadProgress
                            .setIndeterminate(
                                false
                            );

                        downloadProgress
                            .setProgress(
                                percent
                            );

                        downloadText
                            .setText(
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
                        downloadProgress
                            .setIndeterminate(
                                true
                            );

                        downloadText
                            .setText(
                                String.format(
                                    Locale.US,
                                    "正在下载：%s  %.1f MB  %.2f MB/s",
                                    filename,
                                    doneMB,
                                    speedMB
                                )
                            );
                    }

                    poll(
                        id,
                        filename
                    );

                } catch (
                    Exception e
                ) {
                    poll(
                        id,
                        filename
                    );

                } finally {
                    if (
                        c != null
                    ) {
                        c.close();
                    }
                }
            },
            250
        );
    }
}
