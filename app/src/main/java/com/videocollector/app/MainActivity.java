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
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final int REQ_STORAGE = 7001;

    private LinearLayout root;
    private EditText input;
    private TextView status;
    private TextView result;
    private TextView logView;
    private WebView webView;
    private LinearLayout webControls;
    private Button downloadButton;
    private ProgressBar progressBar;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Set<String> seenCandidates = ConcurrentHashMap.newKeySet();

    private volatile boolean probeActive = false;
    private volatile boolean verified = false;
    private volatile String platform = "";
    private volatile String verifiedUrl = "";
    private volatile String verifiedReferer = "";
    private volatile String verifiedTitle = "";
    private volatile String currentPageUrl = "";

    private long activeDownloadId = -1L;
    private DownloadManager downloadManager;
    private String probeScript = "";

    private static final Pattern FIRST_HTTP =
        Pattern.compile("https?://[^\\s，。；;）),]+", Pattern.CASE_INSENSITIVE);

    private static final Pattern RAW_DIRECT =
        Pattern.compile(
            "(?i)[\"'](?:play_addr|playAddr|download_addr|downloadAddr|masterUrl|master_url|video_url|videoUrl|play_url|playUrl)[\"']\\s*:\\s*[\"']([^\"']+)[\"']"
        );

    private static final Pattern RAW_LIST =
        Pattern.compile(
            "(?i)[\"'](?:url_list|urlList|backupUrls|backup_urls)[\"']\\s*:\\s*\\[\\s*[\"']([^\"']+)[\"']"
        );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        downloadManager =
            (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);

        probeScript = readAsset("probe.js");

        buildUi();
        configureWebView();
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16, 19, 23));
        root.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView title = new TextView(this);
        title.setText("王的解析 · 本地会话诊断 V9.0.1");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24f);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        TextView explain = new TextView(this);
        explain.setText(
            "目标：不播放视频。先在本 APP 的真实平台网页中手动登录一次；之后加载分享链接时，从页面自己的 fetch/XHR/SSR 状态中寻找媒体，再由 APP 验证真实视频文件头。"
        );
        explain.setTextColor(Color.rgb(180, 190, 200));
        explain.setTextSize(14f);
        explain.setPadding(0, 0, 0, dp(10));
        root.addView(explain);

        input = new EditText(this);
        input.setHint("粘贴抖音/小红书分享链接或完整分享文案");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.rgb(120, 130, 140));
        input.setBackgroundColor(Color.rgb(24, 29, 35));
        input.setMinHeight(dp(95));
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(
            input,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(110)
            )
        );

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);

        Button paste = makeButton("粘贴");
        Button loginDy = makeButton("登录抖音会话");
        Button loginXhs = makeButton("登录小红书会话");

        row1.addView(paste, weight());
        row1.addView(loginDy, weight());
        row1.addView(loginXhs, weight());
        root.addView(row1);

        Button diagnose = makeButton("开始诊断（不播放）");
        diagnose.setTextSize(16f);
        root.addView(
            diagnose,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            )
        );

        status = new TextView(this);
        status.setText("状态：等待");
        status.setTextColor(Color.rgb(220, 225, 230));
        status.setTextSize(15f);
        status.setPadding(0, dp(8), 0, dp(6));
        root.addView(status);

        result = new TextView(this);
        result.setText("");
        result.setTextColor(Color.rgb(125, 225, 175));
        result.setTextSize(14f);
        result.setPadding(0, 0, 0, dp(6));
        root.addView(result);

        downloadButton = makeButton("下载验证通过的视频");
        downloadButton.setVisibility(View.GONE);
        root.addView(
            downloadButton,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(50)
            )
        );

        progressBar =
            new ProgressBar(
                this,
                null,
                android.R.attr.progressBarStyleHorizontal
            );
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);
        root.addView(
            progressBar,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(12)
            )
        );

        webControls = new LinearLayout(this);
        webControls.setOrientation(LinearLayout.HORIZONTAL);
        webControls.setVisibility(View.GONE);

        Button doneSession = makeButton("完成登录 / 返回");
        Button reload = makeButton("刷新网页");
        webControls.addView(doneSession, weight());
        webControls.addView(reload, weight());
        root.addView(webControls);

        webView = new WebView(this);
        webView.setVisibility(View.GONE);
        root.addView(
            webView,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        );

        TextView logTitle = new TextView(this);
        logTitle.setText("诊断日志（不会显示 Cookie/密码）");
        logTitle.setTextColor(Color.rgb(155, 165, 175));
        logTitle.setTextSize(12f);
        root.addView(logTitle);

        ScrollView logScroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextColor(Color.rgb(170, 180, 190));
        logView.setTextSize(11f);
        logView.setText("尚无日志");
        logScroll.addView(logView);

        root.addView(
            logScroll,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(155)
            )
        );

        setContentView(root);

        paste.setOnClickListener(v -> pasteClipboard());
        loginDy.setOnClickListener(v -> openLogin("douyin"));
        loginXhs.setOnClickListener(v -> openLogin("xhs"));
        diagnose.setOnClickListener(v -> startDiagnosis());
        doneSession.setOnClickListener(v -> finishSessionView());
        reload.setOnClickListener(v -> webView.reload());
        downloadButton.setOnClickListener(v -> downloadVerified());
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p =
            new LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            );
        p.setMargins(dp(2), dp(4), dp(2), dp(4));
        return p;
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        return b;
    }

    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setSupportMultipleWindows(false);
        s.setUserAgentString(
            s.getUserAgentString() + " WangParserSessionDiag/9.0.1"
        );

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) {
            cm.setAcceptThirdPartyCookies(webView, true);
        }

        webView.addJavascriptInterface(
            new ProbeBridge(),
            "AndroidProbe"
        );

        webView.setWebViewClient(
            new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(
                    WebView view,
                    WebResourceRequest request
                ) {
                    Uri uri = request.getUrl();
                    String scheme = uri.getScheme();

                    if (
                        "http".equalsIgnoreCase(scheme)
                        || "https".equalsIgnoreCase(scheme)
                    ) {
                        return false;
                    }

                    appendLog(
                        "阻止外部 scheme: "
                            + safeScheme(scheme)
                    );
                    return true;
                }

                @Override
                public void onPageStarted(
                    WebView view,
                    String url,
                    android.graphics.Bitmap favicon
                ) {
                    currentPageUrl = url == null ? "" : url;
                    appendLog(
                        "PAGE " + redactUrl(currentPageUrl)
                    );

                    if (probeActive && probeScript != null && !probeScript.isEmpty()) {
                        view.evaluateJavascript(probeScript, null);
                    }
                }

                @Override
                public void onPageFinished(
                    WebView view,
                    String url
                ) {
                    currentPageUrl = url == null ? "" : url;

                    if (probeActive && probeScript != null && !probeScript.isEmpty()) {
                        view.evaluateJavascript(probeScript, null);
                    }
                }

                @Override
                public void onPageCommitVisible(
                    WebView view,
                    String url
                ) {
                    currentPageUrl = url == null ? "" : url;

                    if (probeActive && probeScript != null && !probeScript.isEmpty()) {
                        view.evaluateJavascript(probeScript, null);
                    }
                }

                @Override
                public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view,
                    WebResourceRequest request
                ) {
                    if (probeActive && request != null && request.getUrl() != null) {
                        String u = request.getUrl().toString();
                        String low = u.toLowerCase(Locale.US);

                        if (
                            low.contains("douyinvod")
                            || low.contains("xhscdn")
                            || low.contains("sns-video")
                            || low.contains("/video/")
                            || low.contains("/play/")
                            || low.contains(".mp4")
                        ) {
                            String candidate = normalizeUrl(u);

                            if (
                                !candidate.isEmpty()
                                && seenCandidates.add(candidate)
                            ) {
                                appendLog(
                                    "NET "
                                        + safeHostPath(candidate)
                                );

                                executor.execute(
                                    () -> verifyCandidate(
                                        candidate,
                                        currentPageUrl
                                    )
                                );
                            }
                        }
                    }

                    return super.shouldInterceptRequest(
                        view,
                        request
                    );
                }
            }
        );

        appendLog("页面监听：已启用");
    }

    private void openLogin(String p) {
        probeActive = false;
        platform = p;
        verified = false;
        verifiedUrl = "";
        result.setText("");
        downloadButton.setVisibility(View.GONE);

        showWeb();

        String url =
            "douyin".equals(p)
                ? "https://www.douyin.com/"
                : "https://www.xiaohongshu.com/";

        status.setText(
            "状态：请直接在平台网页中手动登录。"
                + "本 APP 不读取账号、密码或验证码；登录完成后点“完成登录 / 返回”。"
        );

        webView.loadUrl(url);
    }

    private void finishSessionView() {
        CookieManager.getInstance().flush();
        probeActive = false;
        hideWeb();
        status.setText(
            "状态：会话已保留。现在粘贴分享链接，点“开始诊断（不播放）”。"
        );
    }

    private void startDiagnosis() {
        String raw = input.getText().toString().trim();
        String url = firstUrl(raw);

        if (url.isEmpty()) {
            Toast.makeText(
                this,
                "没有识别到链接。",
                Toast.LENGTH_LONG
            ).show();
            return;
        }

        String low = url.toLowerCase(Locale.US);

        if (
            low.contains("douyin.com")
            || low.contains("iesdouyin.com")
        ) {
            platform = "douyin";
        } else if (
            low.contains("xiaohongshu.com")
            || low.contains("xhslink.")
        ) {
            platform = "xhs";
        } else {
            Toast.makeText(
                this,
                "这个诊断版只测试抖音和小红书。",
                Toast.LENGTH_LONG
            ).show();
            return;
        }

        seenCandidates.clear();
        verified = false;
        verifiedUrl = "";
        verifiedReferer = "";
        verifiedTitle = "";
        result.setText("");
        downloadButton.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);
        logView.setText("");

        probeActive = true;

        status.setText(
            "状态：正在加载真实"
                + ("douyin".equals(platform) ? "抖音" : "小红书")
                + "网页并监听页面自己的数据请求；不会自动播放视频。"
        );

        appendLog(
            "START " + platform + " " + redactUrl(url)
        );

        showWeb();
        webView.loadUrl(url);

        main.postDelayed(
            () -> {
                if (
                    probeActive
                    && !verified
                ) {
                    status.setText(
                        "状态：20 秒内没有捕获到验证通过的视频。"
                        + "如果页面显示未登录，请先完成对应平台登录；"
                        + "如果已经登录，把日志和页面截图发回来。"
                    );
                    appendLog(
                        "TIMEOUT no verified video"
                    );
                }
            },
            20000
        );
    }

    private void showWeb() {
        webControls.setVisibility(View.VISIBLE);
        webView.setVisibility(View.VISIBLE);

        LinearLayout.LayoutParams p =
            (LinearLayout.LayoutParams)
                webView.getLayoutParams();

        p.height = 0;
        p.weight = 1f;
        webView.setLayoutParams(p);
    }

    private void hideWeb() {
        webControls.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
    }

    public class ProbeBridge {

        @JavascriptInterface
        public void onCandidate(
            String url,
            String path,
            String kind,
            String source
        ) {
            if (!probeActive || verified) {
                return;
            }

            if (
                url == null
                || !url.startsWith("http")
            ) {
                return;
            }

            if (
                "audio".equalsIgnoreCase(kind)
            ) {
                return;
            }

            String normalized =
                normalizeUrl(url);

            if (
                normalized.isEmpty()
                || !seenCandidates.add(normalized)
            ) {
                return;
            }

            appendLog(
                "CAND "
                    + safeHostPath(normalized)
                    + "  ← "
                    + shorten(path, 90)
            );

            executor.execute(
                () ->
                    verifyCandidate(
                        normalized,
                        source
                    )
            );
        }

        @JavascriptInterface
        public void onRaw(
            String source,
            String raw
        ) {
            if (
                !probeActive
                || verified
                || raw == null
                || raw.isEmpty()
            ) {
                return;
            }

            scanRaw(
                source,
                raw
            );
        }

        @JavascriptInterface
        public void onTitle(
            String title,
            String pageUrl
        ) {
            if (
                title != null
                && !title.trim().isEmpty()
            ) {
                verifiedTitle =
                    title.trim();
            }

            if (
                pageUrl != null
                && !pageUrl.isEmpty()
            ) {
                currentPageUrl = pageUrl;
            }
        }

        @JavascriptInterface
        public void onEvent(
            String type,
            String source,
            String detail
        ) {
            if (!probeActive) {
                return;
            }

            if (
                "probe".equals(type)
            ) {
                appendLog(
                    "PROBE document-start"
                );
            }
        }
    }

    private void scanRaw(
        String source,
        String raw
    ) {
        executor.execute(
            () -> {
                Matcher m1 =
                    RAW_DIRECT.matcher(raw);

                int count = 0;

                while (
                    m1.find()
                    && count++ < 40
                ) {
                    submitRawCandidate(
                        m1.group(1),
                        source,
                        "raw-direct"
                    );
                }

                Matcher m2 =
                    RAW_LIST.matcher(raw);

                count = 0;

                while (
                    m2.find()
                    && count++ < 40
                ) {
                    submitRawCandidate(
                        m2.group(1),
                        source,
                        "raw-list"
                    );
                }
            }
        );
    }

    private void submitRawCandidate(
        String raw,
        String source,
        String path
    ) {
        String u =
            normalizeUrl(raw);

        if (
            u.isEmpty()
            || !u.startsWith("http")
            || !seenCandidates.add(u)
        ) {
            return;
        }

        appendLog(
            "RAW "
                + safeHostPath(u)
        );

        verifyCandidate(
            u,
            source
        );
    }

    private void verifyCandidate(
        String candidate,
        String source
    ) {
        if (verified) {
            return;
        }

        HttpURLConnection c = null;

        try {
            c =
                (HttpURLConnection)
                    new URL(candidate)
                        .openConnection();

            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(7000);
            c.setReadTimeout(7000);

            c.setRequestProperty(
                "User-Agent",
                webView.getSettings()
                    .getUserAgentString()
            );

            c.setRequestProperty(
                "Accept",
                "*/*"
            );

            c.setRequestProperty(
                "Range",
                "bytes=0-8191"
            );

            String referer =
                firstNonEmpty(
                    source,
                    currentPageUrl
                );

            if (
                referer != null
                && referer.startsWith("http")
            ) {
                c.setRequestProperty(
                    "Referer",
                    referer
                );
            }

            String cookie =
                CookieManager.getInstance()
                    .getCookie(candidate);

            if (
                cookie != null
                && !cookie.isEmpty()
            ) {
                c.setRequestProperty(
                    "Cookie",
                    cookie
                );
            }

            int statusCode =
                c.getResponseCode();

            String type =
                c.getContentType();

            InputStream in =
                statusCode >= 200
                    && statusCode < 400
                    ? c.getInputStream()
                    : c.getErrorStream();

            byte[] head =
                readHead(in, 8192);

            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }

            String ascii =
                new String(
                    head,
                    StandardCharsets.ISO_8859_1
                );

            String lowType =
                type == null
                    ? ""
                    : type.toLowerCase(Locale.US);

            boolean html =
                lowType.contains("text/html")
                || ascii
                    .toLowerCase(Locale.US)
                    .contains("<html");

            boolean video =
                lowType.startsWith("video/")
                || ascii.contains("ftyp")
                || ascii
                    .toLowerCase(Locale.US)
                    .contains("webm")
                || ascii.contains("moov")
                || ascii.contains("mdat");

            appendLog(
                "VERIFY "
                    + statusCode
                    + " "
                    + safeType(type)
                    + " "
                    + safeHostPath(candidate)
                    + (video && !html ? " ✓" : " ✗")
            );

            if (
                !verified
                && statusCode >= 200
                && statusCode < 400
                && !html
                && video
            ) {
                verified = true;
                probeActive = false;
                verifiedUrl = candidate;
                verifiedReferer =
                    referer == null ? "" : referer;

                main.post(
                    () -> {
                        status.setText(
                            "状态：✅ 捕获并验证到真实视频。无需播放。"
                        );

                        result.setText(
                            "平台："
                                + platform
                                + "\n标题："
                                + (
                                    verifiedTitle == null
                                        || verifiedTitle.isEmpty()
                                        ? "未读取"
                                        : verifiedTitle
                                )
                                + "\n媒体："
                                + safeHostPath(
                                    verifiedUrl
                                )
                                + "\n校验：HTTP "
                                + statusCode
                                + " / "
                                + safeType(type)
                        );

                        downloadButton.setVisibility(
                            View.VISIBLE
                        );

                        main.postDelayed(
                            this::hideWeb,
                            700
                        );
                    }
                );
            }

        } catch (Exception e) {
            appendLog(
                "VERIFY ERR "
                    + safeHostPath(candidate)
                    + " "
                    + shorten(
                        e.getClass().getSimpleName(),
                        40
                    )
            );

        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private void downloadVerified() {
        if (
            verifiedUrl == null
            || verifiedUrl.isEmpty()
        ) {
            return;
        }

        if (
            Build.VERSION.SDK_INT <= 28
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
                "允许存储权限后再点一次下载。",
                Toast.LENGTH_LONG
            ).show();
            return;
        }

        try {
            String filename =
                "WangParser_Diag_"
                    + System.currentTimeMillis()
                    + ".mp4";

            DownloadManager.Request req =
                new DownloadManager.Request(
                    Uri.parse(verifiedUrl)
                );

            req.setTitle(filename);
            req.setMimeType("video/mp4");

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
                verifiedReferer != null
                && !verifiedReferer.isEmpty()
            ) {
                req.addRequestHeader(
                    "Referer",
                    verifiedReferer
                );
            }

            String cookie =
                CookieManager.getInstance()
                    .getCookie(verifiedUrl);

            if (
                cookie != null
                && !cookie.isEmpty()
            ) {
                req.addRequestHeader(
                    "Cookie",
                    cookie
                );
            }

            req.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_MOVIES,
                "WangParser/Diagnostics/"
                    + filename
            );

            activeDownloadId =
                downloadManager.enqueue(req);

            progressBar.setVisibility(
                View.VISIBLE
            );
            progressBar.setProgress(0);

            status.setText(
                "状态：正在下载验证视频…"
            );

            pollDownload(
                activeDownloadId
            );

        } catch (Exception e) {
            status.setText(
                "状态：下载启动失败："
                    + e.getMessage()
            );
        }
    }

    private void pollDownload(
        long id
    ) {
        main.postDelayed(
            () -> {
                if (id != activeDownloadId) {
                    return;
                }

                DownloadManager.Query q =
                    new DownloadManager.Query()
                        .setFilterById(id);

                Cursor c = null;

                try {
                    c =
                        downloadManager.query(q);

                    if (
                        c == null
                        || !c.moveToFirst()
                    ) {
                        pollDownload(id);
                        return;
                    }

                    int state =
                        c.getInt(
                            c.getColumnIndexOrThrow(
                                DownloadManager.COLUMN_STATUS
                            )
                        );

                    long done =
                        c.getLong(
                            c.getColumnIndexOrThrow(
                                DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR
                            )
                        );

                    long total =
                        c.getLong(
                            c.getColumnIndexOrThrow(
                                DownloadManager.COLUMN_TOTAL_SIZE_BYTES
                            )
                        );

                    if (total > 0) {
                        int pct =
                            (int) (
                                done * 100L / total
                            );
                        progressBar.setProgress(pct);

                        status.setText(
                            String.format(
                                Locale.US,
                                "状态：下载中 %d%%  %.1f / %.1f MB",
                                pct,
                                done / 1048576.0,
                                total / 1048576.0
                            )
                        );
                    }

                    if (
                        state
                            == DownloadManager.STATUS_SUCCESSFUL
                    ) {
                        activeDownloadId = -1L;
                        progressBar.setProgress(100);
                        verifyDownloadedFile(id);
                        return;
                    }

                    if (
                        state
                            == DownloadManager.STATUS_FAILED
                    ) {
                        activeDownloadId = -1L;
                        status.setText(
                            "状态：下载失败。"
                        );
                        return;
                    }

                    pollDownload(id);

                } catch (Exception e) {
                    pollDownload(id);

                } finally {
                    if (c != null) {
                        c.close();
                    }
                }
            },
            300
        );
    }

    private void verifyDownloadedFile(
        long id
    ) {
        executor.execute(
            () -> {
                long duration = 0L;
                Uri uri =
                    downloadManager
                        .getUriForDownloadedFile(id);

                try {
                    if (uri != null) {
                        MediaMetadataRetriever mmr =
                            new MediaMetadataRetriever();

                        mmr.setDataSource(
                            MainActivity.this,
                            uri
                        );

                        String d =
                            mmr.extractMetadata(
                                MediaMetadataRetriever
                                    .METADATA_KEY_DURATION
                            );

                        if (d != null) {
                            duration =
                                Long.parseLong(d);
                        }

                        mmr.release();
                    }
                } catch (Exception ignored) {
                }

                final long finalDuration =
                    duration;

                main.post(
                    () -> {
                        if (finalDuration > 0) {
                            status.setText(
                                "状态：✅ 下载完成并通过本地时长校验："
                                    + String.format(
                                        Locale.US,
                                        "%.1f 秒",
                                        finalDuration
                                            / 1000.0
                                    )
                                    + "。文件在 Movies/WangParser/Diagnostics。"
                            );
                        } else {
                            try {
                                downloadManager.remove(id);
                            } catch (Exception ignored) {
                            }

                            status.setText(
                                "状态：❌ 下载完成但时长校验失败，已删除测试文件。"
                            );
                        }
                    }
                );
            }
        );
    }

    private byte[] readHead(
        InputStream in,
        int max
    ) {
        if (in == null) {
            return new byte[0];
        }

        try {
            ByteArrayOutputStream out =
                new ByteArrayOutputStream();

            byte[] buf =
                new byte[2048];

            int remain = max;

            while (remain > 0) {
                int n =
                    in.read(
                        buf,
                        0,
                        Math.min(
                            buf.length,
                            remain
                        )
                    );

                if (n <= 0) {
                    break;
                }

                out.write(buf, 0, n);
                remain -= n;
            }

            return out.toByteArray();

        } catch (Exception e) {
            return new byte[0];
        }
    }

    private void pasteClipboard() {
        try {
            ClipboardManager cm =
                (ClipboardManager)
                    getSystemService(
                        Context.CLIPBOARD_SERVICE
                    );

            if (
                cm != null
                && cm.hasPrimaryClip()
            ) {
                ClipData d =
                    cm.getPrimaryClip();

                if (
                    d != null
                    && d.getItemCount() > 0
                ) {
                    CharSequence t =
                        d.getItemAt(0)
                            .coerceToText(this);

                    input.setText(
                        t == null
                            ? ""
                            : t.toString()
                    );
                }
            }
        } catch (Exception ignored) {
        }
    }

    private String firstUrl(String text) {
        if (text == null) {
            return "";
        }

        Matcher m =
            FIRST_HTTP.matcher(text);

        if (!m.find()) {
            return "";
        }

        return m.group()
            .replaceAll(
                "[，。,.；;）)]+$",
                ""
            );
    }

    private String normalizeUrl(
        String raw
    ) {
        if (raw == null) {
            return "";
        }

        String s =
            raw.trim()
                .replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("\\u002f", "/")
                .replace("\\u0026", "&")
                .replace("\\u003D", "=")
                .replace("\\u003d", "=")
                .replace("&amp;", "&");

        try {
            if (
                s.contains("%2F")
                || s.contains("%3A")
                || s.contains("%3F")
            ) {
                String decoded =
                    URLDecoder.decode(
                        s,
                        "UTF-8"
                    );

                if (
                    decoded.startsWith("http")
                ) {
                    s = decoded;
                }
            }
        } catch (Exception ignored) {
        }

        return s;
    }

    private String safeHostPath(String raw) {
        try {
            Uri u = Uri.parse(raw);
            String host =
                u.getHost() == null
                    ? ""
                    : u.getHost();

            String path =
                u.getPath() == null
                    ? ""
                    : u.getPath();

            return shorten(
                host + path,
                120
            );
        } catch (Exception e) {
            return shorten(raw, 120);
        }
    }

    private String redactUrl(String raw) {
        try {
            Uri u = Uri.parse(raw);
            return safeHostPath(raw);
        } catch (Exception e) {
            return shorten(raw, 100);
        }
    }

    private String safeScheme(String s) {
        return s == null ? "" : s;
    }

    private String safeType(String s) {
        return s == null || s.isEmpty()
            ? "(no content-type)"
            : s;
    }

    private String shorten(
        String s,
        int max
    ) {
        if (s == null) {
            return "";
        }

        return s.length() <= max
            ? s
            : s.substring(0, max) + "…";
    }

    private String firstNonEmpty(
        String a,
        String b
    ) {
        if (
            a != null
            && a.startsWith("http")
        ) {
            return a;
        }

        return b;
    }

    private String readAsset(
        String name
    ) {
        try {
            InputStream in =
                getAssets().open(name);

            ByteArrayOutputStream out =
                new ByteArrayOutputStream();

            byte[] buf =
                new byte[4096];

            int n;

            while (
                (n = in.read(buf)) > 0
            ) {
                out.write(buf, 0, n);
            }

            in.close();

            return out.toString(
                StandardCharsets.UTF_8.name()
            );

        } catch (Exception e) {
            return "";
        }
    }

    private void appendLog(
        String msg
    ) {
        main.post(
            () -> {
                String old =
                    logView.getText()
                        .toString();

                if (
                    old.equals("尚无日志")
                ) {
                    old = "";
                }

                String next =
                    old
                        + (
                            old.isEmpty()
                                ? ""
                                : "\n"
                        )
                        + msg;

                if (
                    next.length() > 12000
                ) {
                    next =
                        next.substring(
                            next.length()
                                - 12000
                        );
                }

                logView.setText(next);
            }
        );
    }

    private int dp(int value) {
        return (int) (
            value
                * getResources()
                    .getDisplayMetrics()
                    .density
                + 0.5f
        );
    }

    @Override
    protected void onDestroy() {
        probeActive = false;

        try {
            webView.destroy();
        } catch (Exception ignored) {
        }

        executor.shutdownNow();
        super.onDestroy();
    }
}
