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
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public class MainActivity extends Activity {

    private static final String SERVER = "https://video-collector-0d1n.onrender.com";
    private static final int REQ_STORAGE = 100;

    private WebView webView;
    private WebView parserWebView;

    private LinearLayout downloadPanel;
    private TextView downloadText;
    private ProgressBar downloadProgress;

    private DownloadManager downloadManager;
    private Handler handler;

    private long currentDownloadId = -1L;
    private long lastBytes = 0L;
    private long lastSampleTime = 0L;

    private final Set<String> interceptedVideos = new CopyOnWriteArraySet<>();
    private final Set<String> interceptedAudios = new CopyOnWriteArraySet<>();

    private int parseGeneration = 0;
    private String currentParseUrl = "";
    private boolean localResultDelivered = false;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        downloadManager =
            (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);

        handler = new Handler(Looper.getMainLooper());

        LinearLayout root = new LinearLayout(this);
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

        // A real local Android WebView does the Douyin/Xiaohongshu parsing.
        // It stays effectively invisible, but remains attached so JS/hydration runs normally.
        parserWebView = new WebView(this);
        parserWebView.setAlpha(0.01f);

        LinearLayout.LayoutParams parserParams =
            new LinearLayout.LayoutParams(1, 1);

        root.addView(parserWebView, parserParams);

        downloadPanel = new LinearLayout(this);
        downloadPanel.setOrientation(LinearLayout.VERTICAL);
        downloadPanel.setPadding(24, 14, 24, 18);
        downloadPanel.setBackgroundColor(0xFF181D23);
        downloadPanel.setVisibility(View.GONE);

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

        LinearLayout.LayoutParams progressParams =
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                22
            );

        progressParams.topMargin = 10;

        downloadPanel.addView(downloadProgress, progressParams);
        root.addView(downloadPanel);

        setContentView(root);

        configureMainWebView();
        configureParserWebView();

        webView.loadUrl("file:///android_asset/index.html");
    }


    private void configureMainWebView() {
        WebSettings s = webView.getSettings();

        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUserAgentString(
            s.getUserAgentString() + " VideoCollectorApp/5.0"
        );

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(
            new AndroidDownloader(),
            "AndroidDownloader"
        );

        webView.addJavascriptInterface(
            new AndroidClipboard(),
            "AndroidClipboard"
        );

        webView.addJavascriptInterface(
            new AndroidLocalParser(),
            "AndroidLocalParser"
        );

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
    }


    private void configureParserWebView() {
        WebSettings s = parserWebView.getSettings();

        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setLoadsImagesAutomatically(false);
        s.setBlockNetworkImage(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        // Keep a normal Android Chrome UA. Do not impersonate a logged-in app.
        String ua = s.getUserAgentString();
        s.setUserAgentString(
            ua.replace("; wv", "") + " VideoCollectorLocalParser/5.0"
        );

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(
            parserWebView,
            true
        );

        parserWebView.setWebChromeClient(new WebChromeClient());

        parserWebView.setWebViewClient(
            new WebViewClient() {

                @Override
                public WebResourceResponse shouldInterceptRequest(
                    WebView view,
                    WebResourceRequest request
                ) {
                    inspectRequestUrl(request.getUrl().toString());
                    return super.shouldInterceptRequest(view, request);
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);

                    final int generation = parseGeneration;

                    sendLocalStatus(
                        "本地页面已打开，正在读取公开媒体资源…"
                    );

                    handler.postDelayed(
                        () -> scrapeParserPage(generation, 1),
                        900
                    );

                    handler.postDelayed(
                        () -> scrapeParserPage(generation, 2),
                        2600
                    );

                    handler.postDelayed(
                        () -> scrapeParserPage(generation, 3),
                        5200
                    );
                }
            }
        );
    }


    private void inspectRequestUrl(String url) {
        if (url == null || url.isEmpty()) return;

        String low = url.toLowerCase(Locale.US);

        if (
            low.startsWith("blob:")
            || low.startsWith("data:")
        ) {
            return;
        }

        if (looksLikeImage(low)) {
            return;
        }

        // Strong video signals from public web playback.
        if (
            low.contains(".mp4")
            || low.contains(".m3u8")
            || low.contains("sns-video")
            || low.contains("xhscdn.com")
                && (
                    low.contains("video")
                    || low.contains("stream")
                )
            || low.contains("douyinvod.com")
            || low.contains("/aweme/v1/play/")
            || low.contains("/video/tos/")
        ) {
            interceptedVideos.add(url);
            return;
        }

        // Audio/music signals.
        if (
            low.contains(".mp3")
            || low.contains(".m4a")
            || low.contains(".aac")
            || low.contains("music")
                && (
                    low.contains("play")
                    || low.contains("audio")
                )
        ) {
            interceptedAudios.add(url);
        }
    }


    private boolean looksLikeImage(String low) {
        return
            low.contains(".jpg")
            || low.contains(".jpeg")
            || low.contains(".png")
            || low.contains(".webp")
            || low.contains(".gif")
            || low.contains(".avif")
            || low.contains(".svg");
    }


    public class AndroidLocalParser {

        @JavascriptInterface
        public void parse(final String text) {
            runOnUiThread(
                () -> startLocalParse(text)
            );
        }
    }


    private void startLocalParse(String text) {
        String url = extractFirstHttpUrl(text);

        if (url == null) {
            deliverLocalError("没有识别到有效 http/https 链接。");
            return;
        }

        String host = getHost(url);

        if (
            host == null
            || !(
                host.contains("douyin.com")
                || host.contains("iesdouyin.com")
                || host.contains("xiaohongshu.com")
                || host.contains("xhslink.com")
                || host.contains("xhslink.cn")
            )
        ) {
            deliverLocalError(
                "local_unsupported"
            );
            return;
        }

        parseGeneration++;
        localResultDelivered = false;
        currentParseUrl = url;

        interceptedVideos.clear();
        interceptedAudios.clear();

        parserWebView.stopLoading();
        parserWebView.loadUrl("about:blank");

        sendLocalStatus(
            host.contains("douyin")
                ? "抖音：正在手机本地打开公开分享页…"
                : "小红书：正在手机本地打开公开分享页…"
        );

        final int generation = parseGeneration;

        handler.postDelayed(
            () -> {
                if (
                    generation == parseGeneration
                    && !localResultDelivered
                ) {
                    parserWebView.loadUrl(url);
                }
            },
            120
        );

        // Hard local timeout. This does not loop forever.
        handler.postDelayed(
            () -> {
                if (
                    generation == parseGeneration
                    && !localResultDelivered
                ) {
                    scrapeParserPage(generation, 99);

                    handler.postDelayed(
                        () -> {
                            if (
                                generation == parseGeneration
                                && !localResultDelivered
                            ) {
                                deliverLocalError(
                                    "手机本地已打开该公开分享页，"
                                    + "但没有捕获到可直接访问的视频/音频资源。"
                                    + "如果页面要求登录、验证码或平台限制网页播放，"
                                    + "本工具不会绕过。"
                                );
                            }
                        },
                        900
                    );
                }
            },
            11000
        );
    }


    private void scrapeParserPage(
        final int generation,
        final int attempt
    ) {
        if (
            generation != parseGeneration
            || localResultDelivered
        ) {
            return;
        }

        String javascript =
            "(function(){"
            + "const out={title:document.title||'',videos:[],audios:[],page:location.href};"
            + "const seenV=new Set(),seenA=new Set();"
            + "function clean(u){"
            + " if(!u||typeof u!=='string')return '';"
            + " if(u.startsWith('//'))u=location.protocol+u;"
            + " return u;"
            + "}"
            + "function addV(u){u=clean(u);if(/^https?:/i.test(u)&&!seenV.has(u)){seenV.add(u);out.videos.push(u);}}"
            + "function addA(u){u=clean(u);if(/^https?:/i.test(u)&&!seenA.has(u)){seenA.add(u);out.audios.push(u);}}"
            + "document.querySelectorAll('video').forEach(v=>{addV(v.currentSrc);addV(v.src);});"
            + "document.querySelectorAll('video source').forEach(s=>addV(s.src));"
            + "document.querySelectorAll('audio').forEach(a=>{addA(a.currentSrc);addA(a.src);});"
            + "document.querySelectorAll('audio source').forEach(s=>addA(s.src));"
            + "function walk(o,p,d){"
            + " if(d>11||o==null)return;"
            + " if(typeof o==='string'){"
            + "   if(!/^https?:\\/\\//i.test(o))return;"
            + "   const lp=(p||'').toLowerCase(),lu=o.toLowerCase();"
            + "   const img=/\\.(jpg|jpeg|png|webp|gif|avif)(\\?|$)/i.test(lu);"
            + "   if(img)return;"
            + "   if(lp.includes('music')||lp.includes('audio')||lp.includes('play_url'))addA(o);"
            + "   if(lp.includes('video')||lp.includes('stream')||lp.includes('masterurl')||lp.includes('playaddr')||lp.includes('play_addr'))addV(o);"
            + "   return;"
            + " }"
            + " if(Array.isArray(o)){for(let i=0;i<Math.min(o.length,100);i++)walk(o[i],p+'['+i+']',d+1);return;}"
            + " if(typeof o==='object'){"
            + "   let n=0;"
            + "   for(const k in o){if(++n>700)break;try{walk(o[k],p+'.'+k,d+1);}catch(e){}}"
            + " }"
            + "}"
            + "try{walk(window.__INITIAL_STATE__,'INITIAL_STATE',0);}catch(e){}"
            + "try{walk(window._ROUTER_DATA,'ROUTER_DATA',0);}catch(e){}"
            + "try{performance.getEntriesByType('resource').forEach(e=>{"
            + " const u=e.name||'',l=u.toLowerCase();"
            + " if(/\\.(mp4|m3u8)(\\?|$)/i.test(l)||l.includes('douyinvod.com')||l.includes('sns-video'))addV(u);"
            + " if(/\\.(mp3|m4a|aac)(\\?|$)/i.test(l))addA(u);"
            + "});}catch(e){}"
            + "return JSON.stringify(out);"
            + "})()";

        parserWebView.evaluateJavascript(
            javascript,
            value -> handleScrapeResult(
                generation,
                attempt,
                value
            )
        );
    }


    private void handleScrapeResult(
        int generation,
        int attempt,
        String encodedValue
    ) {
        if (
            generation != parseGeneration
            || localResultDelivered
        ) {
            return;
        }

        try {
            String jsonText = decodeEvaluateJavascriptString(encodedValue);

            if (jsonText == null || jsonText.isEmpty()) {
                return;
            }

            JSONObject pageData = new JSONObject(jsonText);

            JSONArray pageVideos =
                pageData.optJSONArray("videos");

            JSONArray pageAudios =
                pageData.optJSONArray("audios");

            if (pageVideos != null) {
                for (int i = 0; i < pageVideos.length(); i++) {
                    String u = pageVideos.optString(i, "");
                    if (!u.isEmpty()) {
                        inspectRequestUrl(u);
                        if (!looksLikeImage(u.toLowerCase(Locale.US))) {
                            interceptedVideos.add(u);
                        }
                    }
                }
            }

            if (pageAudios != null) {
                for (int i = 0; i < pageAudios.length(); i++) {
                    String u = pageAudios.optString(i, "");
                    if (!u.isEmpty()) {
                        interceptedAudios.add(u);
                    }
                }
            }

            if (!interceptedVideos.isEmpty()) {
                JSONObject result = new JSONObject();

                result.put("success", true);
                result.put("platform", platformName(currentParseUrl));
                result.put(
                    "title",
                    pageData.optString("title", "本地解析视频")
                );
                result.put(
                    "resolved_url",
                    pageData.optString(
                        "page",
                        parserWebView.getUrl()
                    )
                );

                JSONArray videos = new JSONArray();

                int count = 0;
                for (String u : interceptedVideos) {
                    if (count++ >= 12) break;

                    JSONObject item = new JSONObject();
                    item.put("quality", "本地捕获视频源");
                    item.put("url", u);
                    item.put(
                        "ext",
                        u.toLowerCase(Locale.US).contains(".m3u8")
                            ? "m3u8"
                            : "mp4"
                    );
                    videos.put(item);
                }

                JSONArray audios = new JSONArray();

                count = 0;
                for (String u : interceptedAudios) {
                    if (count++ >= 8) break;

                    JSONObject item = new JSONObject();
                    item.put("quality", "本地捕获音频");
                    item.put("url", u);
                    item.put(
                        "ext",
                        guessAudioExt(u)
                    );
                    audios.put(item);
                }

                result.put("videos", videos);
                result.put("audios", audios);

                localResultDelivered = true;
                deliverLocalResult(result);
                return;
            }

            if (attempt == 1) {
                sendLocalStatus("页面已加载，正在等待播放器暴露媒体地址…");
            } else if (attempt == 2) {
                sendLocalStatus("正在读取页面状态和实际网络媒体请求…");
            }

        } catch (Exception ignored) {
        }
    }


    private String decodeEvaluateJavascriptString(String value) {
        try {
            if (value == null || "null".equals(value)) {
                return null;
            }

            Object decoded = new JSONTokener(value).nextValue();

            if (decoded instanceof String) {
                return (String) decoded;
            }

            return String.valueOf(decoded);

        } catch (Exception e) {
            return null;
        }
    }


    private String extractFirstHttpUrl(String text) {
        if (text == null) return null;

        int http = text.indexOf("http://");
        int https = text.indexOf("https://");

        int start;

        if (http < 0) {
            start = https;
        } else if (https < 0) {
            start = http;
        } else {
            start = Math.min(http, https);
        }

        if (start < 0) return null;

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

        String url = text.substring(start, end);

        while (
            url.endsWith(".")
            || url.endsWith(",")
            || url.endsWith("。")
            || url.endsWith("，")
        ) {
            url = url.substring(0, url.length() - 1);
        }

        return url;
    }


    private String getHost(String url) {
        try {
            return new URI(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }


    private String platformName(String url) {
        String low = url == null
            ? ""
            : url.toLowerCase(Locale.US);

        if (low.contains("douyin")) {
            return "Douyin / 本地";
        }

        if (
            low.contains("xiaohongshu")
            || low.contains("xhslink")
        ) {
            return "XiaoHongShu / 本地";
        }

        return "Local";
    }


    private String guessAudioExt(String url) {
        String low = url.toLowerCase(Locale.US);

        if (low.contains(".m4a")) return "m4a";
        if (low.contains(".aac")) return "aac";
        return "mp3";
    }


    private void sendLocalStatus(String message) {
        final String q = JSONObject.quote(message);

        webView.evaluateJavascript(
            "window.onLocalParseStatus && window.onLocalParseStatus("
                + q
                + ");",
            null
        );
    }


    private void deliverLocalResult(JSONObject result) {
        final String q =
            JSONObject.quote(result.toString());

        webView.evaluateJavascript(
            "window.onLocalParseResult && window.onLocalParseResult(JSON.parse("
                + q
                + "));",
            null
        );
    }


    private void deliverLocalError(String message) {
        final String q = JSONObject.quote(message);

        webView.evaluateJavascript(
            "window.onLocalParseError && window.onLocalParseError("
                + q
                + ");",
            null
        );
    }


    public class AndroidDownloader {

        @JavascriptInterface
        public void download(
            final String url,
            final String filename,
            final String referer
        ) {
            runOnUiThread(
                () -> startDownload(
                    url,
                    filename,
                    referer
                )
            );
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
                    || clip.getItemCount() == 0
                ) {
                    return "";
                }

                CharSequence text =
                    clip.getItemAt(0)
                        .coerceToText(
                            MainActivity.this
                        );

                return text == null
                    ? ""
                    : text.toString();

            } catch (Exception e) {
                return "";
            }
        }
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
                "请允许存储权限后重新点击下载",
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

            String cookie =
                CookieManager.getInstance()
                    .getCookie(url);

            if (cookie != null) {
                req.addRequestHeader(
                    "Cookie",
                    cookie
                );
            }

            req.addRequestHeader(
                "User-Agent",
                parserWebView
                    .getSettings()
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

            String mimeType =
                guessMimeType(filename);

            req.setMimeType(mimeType);
            req.setTitle(filename);

            String targetDir =
                getTargetDirectory(filename);

            req.setDestinationInExternalPublicDir(
                targetDir,
                "VideoCollector/" + filename
            );

            currentDownloadId =
                downloadManager.enqueue(req);

            lastBytes = 0L;
            lastSampleTime =
                System.currentTimeMillis();

            downloadPanel.setVisibility(
                View.VISIBLE
            );

            downloadProgress.setIndeterminate(
                false
            );

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
                "下载启动失败："
                    + e.getMessage(),
                Toast.LENGTH_LONG
            ).show();
        }
    }


    private String sanitize(String s) {
        if (
            s == null
            || s.trim().isEmpty()
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
                : filename.toLowerCase();

        if (lower.endsWith(".mp4"))
            return "video/mp4";

        if (lower.endsWith(".webm"))
            return "video/webm";

        if (lower.endsWith(".mkv"))
            return "video/x-matroska";

        if (lower.endsWith(".mov"))
            return "video/quicktime";

        if (lower.endsWith(".m3u8"))
            return "application/vnd.apple.mpegurl";

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
                : filename.toLowerCase();

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


    private void poll(
        final long id,
        final String filename
    ) {
        handler.postDelayed(
            () -> {
                if (
                    id != currentDownloadId
                ) {
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
                                DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR
                            )
                        );

                    long total =
                        c.getLong(
                            c.getColumnIndexOrThrow(
                                DownloadManager.COLUMN_TOTAL_SIZE_BYTES
                            )
                        );

                    if (
                        status
                            == DownloadManager.STATUS_SUCCESSFUL
                    ) {
                        downloadProgress.setIndeterminate(
                            false
                        );

                        downloadProgress.setProgress(
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
                                    DownloadManager.COLUMN_LOCAL_URI
                                );

                            if (uriIndex >= 0) {
                                String localUri =
                                    c.getString(uriIndex);

                                if (
                                    localUri != null
                                    && localUri.startsWith(
                                        "file://"
                                    )
                                ) {
                                    String localPath =
                                        Uri.parse(localUri)
                                            .getPath();

                                    if (localPath != null) {
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
                        } catch (Exception ignored) {
                        }

                        String lower =
                            filename.toLowerCase();

                        boolean isVideo =
                            lower.endsWith(".mp4")
                                || lower.endsWith(".webm")
                                || lower.endsWith(".mkv")
                                || lower.endsWith(".mov");

                        Toast.makeText(
                            this,
                            isVideo
                                ? "下载完成，已保存到 Movies/VideoCollector，并已通知系统相册"
                                : "下载完成，已保存到 Music/VideoCollector",
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
                        done
                            / 1024.0
                            / 1024.0;

                    if (total > 0) {
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
                        downloadProgress
                            .setIndeterminate(true);

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


    @Override
    public void onBackPressed() {
        if (
            webView != null
            && webView.canGoBack()
        ) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
