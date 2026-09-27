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
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity {

    private static final String SERVER =
        "https://video-collector-0d1n.onrender.com";

    private static final int REQ_STORAGE = 100;

    private LinearLayout root;
    private LinearLayout browserToolbar;
    private LinearLayout downloadPanel;

    private WebView homeWebView;
    private WebView sessionWebView;

    private TextView browserStatus;
    private TextView downloadText;
    private ProgressBar downloadProgress;

    private DownloadManager downloadManager;
    private Handler handler;

    private long currentDownloadId = -1L;
    private long lastBytes = 0L;
    private long lastSampleTime = 0L;

    private String currentPageUrl = "";

    private final Set<String> networkVideoCandidates =
        new CopyOnWriteArraySet<>();

    private final Set<String> networkAudioCandidates =
        new CopyOnWriteArraySet<>();


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

        buildLayout();
        configureHomeWebView();
        configureSessionWebView();

        homeWebView.loadUrl(
            "file:///android_asset/index.html"
        );
    }


    private void buildLayout() {
        root =
            new LinearLayout(this);

        root.setOrientation(
            LinearLayout.VERTICAL
        );

        root.setBackgroundColor(
            0xFF101317
        );

        // Browser toolbar
        browserToolbar =
            new LinearLayout(this);

        browserToolbar.setOrientation(
            LinearLayout.VERTICAL
        );

        browserToolbar.setPadding(
            14,
            12,
            14,
            10
        );

        browserToolbar.setBackgroundColor(
            0xFF181D23
        );

        browserToolbar.setVisibility(
            View.GONE
        );

        LinearLayout buttonRow =
            new LinearLayout(this);

        buttonRow.setOrientation(
            LinearLayout.HORIZONTAL
        );

        Button backButton =
            new Button(this);

        backButton.setText("返回");

        Button reloadButton =
            new Button(this);

        reloadButton.setText("刷新");

        Button extractButton =
            new Button(this);

        extractButton.setText(
            "提取当前媒体"
        );

        LinearLayout.LayoutParams one =
            new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            );

        one.setMargins(
            4,
            0,
            4,
            0
        );

        buttonRow.addView(
            backButton,
            one
        );

        buttonRow.addView(
            reloadButton,
            one
        );

        buttonRow.addView(
            extractButton,
            one
        );

        browserStatus =
            new TextView(this);

        browserStatus.setTextColor(
            0xFFD5DDE5
        );

        browserStatus.setTextSize(
            13f
        );

        browserStatus.setPadding(
            8,
            8,
            8,
            0
        );

        browserStatus.setText(
            "打开页面后，先播放视频，再点“提取当前媒体”"
        );

        browserToolbar.addView(
            buttonRow
        );

        browserToolbar.addView(
            browserStatus
        );

        backButton.setOnClickListener(
            v -> showHome()
        );

        reloadButton.setOnClickListener(
            v -> {
                if (sessionWebView != null) {
                    sessionWebView.reload();
                }
            }
        );

        extractButton.setOnClickListener(
            v -> extractCurrentMedia()
        );


        // Main home WebView
        homeWebView =
            new WebView(this);

        root.addView(
            homeWebView,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        );


        // Visible real browser WebView
        sessionWebView =
            new WebView(this);

        sessionWebView.setVisibility(
            View.GONE
        );

        root.addView(
            browserToolbar,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        );

        root.addView(
            sessionWebView,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        );


        // Download panel
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
            View.GONE
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
                android.R.attr
                    .progressBarStyleHorizontal
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
    }


    private void configureHomeWebView() {
        WebSettings s =
            homeWebView.getSettings();

        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);

        s.setMixedContentMode(
            WebSettings
                .MIXED_CONTENT_NEVER_ALLOW
        );

        s.setUserAgentString(
            s.getUserAgentString()
                + " VideoCollectorApp/6.0"
        );

        homeWebView.addJavascriptInterface(
            new AndroidClipboard(),
            "AndroidClipboard"
        );

        homeWebView.addJavascriptInterface(
            new AndroidDownloader(),
            "AndroidDownloader"
        );

        homeWebView.addJavascriptInterface(
            new AndroidBrowserSession(),
            "AndroidBrowserSession"
        );

        homeWebView.setWebChromeClient(
            new WebChromeClient()
        );

        homeWebView.setWebViewClient(
            new WebViewClient()
        );
    }


    private void configureSessionWebView() {
        WebSettings s =
            sessionWebView.getSettings();

        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setLoadsImagesAutomatically(true);
        s.setBlockNetworkImage(false);
        s.setMediaPlaybackRequiresUserGesture(true);

        String ua =
            s.getUserAgentString();

        s.setUserAgentString(
            ua.replace("; wv", "")
                + " VideoCollectorSession/6.0"
        );

        CookieManager cm =
            CookieManager.getInstance();

        cm.setAcceptCookie(true);

        cm.setAcceptThirdPartyCookies(
            sessionWebView,
            true
        );

        sessionWebView.setWebChromeClient(
            new WebChromeClient()
        );

        sessionWebView.setWebViewClient(
            new WebViewClient() {

                @Override
                public boolean shouldOverrideUrlLoading(
                    WebView view,
                    WebResourceRequest request
                ) {
                    String u =
                        request.getUrl()
                            .toString();

                    if (
                        u.startsWith("http://")
                        || u.startsWith("https://")
                    ) {
                        return false;
                    }

                    // Do not jump out to platform app/deep-link.
                    return true;
                }


                @Override
                public WebResourceResponse
                    shouldInterceptRequest(
                        WebView view,
                        WebResourceRequest request
                    ) {
                    inspectNetworkUrl(
                        request.getUrl()
                            .toString()
                    );

                    return super
                        .shouldInterceptRequest(
                            view,
                            request
                        );
                }


                @Override
                public void onPageFinished(
                    WebView view,
                    String url
                ) {
                    super.onPageFinished(
                        view,
                        url
                    );

                    currentPageUrl =
                        url == null
                            ? ""
                            : url;

                    browserStatus.setText(
                        "页面已打开。若页面有视频，请先点播放，再点“提取当前媒体”。"
                    );

                    // Try one passive extraction first.
                    handler.postDelayed(
                        () ->
                            extractCurrentMediaInternal(
                                true
                            ),
                        1800
                    );
                }
            }
        );
    }


    public class AndroidBrowserSession {

        @JavascriptInterface
        public void open(String text) {
            runOnUiThread(
                () -> {
                    String url =
                        extractFirstHttpUrl(
                            text
                        );

                    if (url == null) {
                        sendHomeError(
                            "没有识别到有效 http/https 链接。"
                        );

                        return;
                    }

                    openBrowser(
                        url
                    );
                }
            );
        }
    }


    private void openBrowser(
        String url
    ) {
        networkVideoCandidates.clear();
        networkAudioCandidates.clear();

        currentPageUrl = url;

        homeWebView.setVisibility(
            View.GONE
        );

        browserToolbar.setVisibility(
            View.VISIBLE
        );

        sessionWebView.setVisibility(
            View.VISIBLE
        );

        browserStatus.setText(
            "正在打开真实平台页面…"
        );

        sessionWebView.loadUrl(
            url
        );
    }


    private void showHome() {
        sessionWebView.setVisibility(
            View.GONE
        );

        browserToolbar.setVisibility(
            View.GONE
        );

        homeWebView.setVisibility(
            View.VISIBLE
        );
    }


    private void inspectNetworkUrl(
        String url
    ) {
        if (
            url == null
            || url.isEmpty()
        ) {
            return;
        }

        String low =
            url.toLowerCase(
                Locale.US
            );

        if (
            low.startsWith("blob:")
            || low.startsWith("data:")
            || looksLikeImage(low)
        ) {
            return;
        }

        if (
            low.contains(".mp4")
            || low.contains("douyinvod.com")
            || low.contains("sns-video")
            || low.contains("/aweme/v1/play/")
            || low.contains("/video/tos/")
            || (
                low.contains("video")
                && (
                    low.contains("play")
                    || low.contains("stream")
                )
            )
        ) {
            networkVideoCandidates.add(
                url
            );
        }

        if (
            low.contains(".m4a")
            || low.contains(".mp3")
            || low.contains(".aac")
            || (
                low.contains("audio")
                && low.contains("play")
            )
            || (
                low.contains("music")
                && low.contains("play")
            )
        ) {
            networkAudioCandidates.add(
                url
            );
        }
    }


    private boolean looksLikeImage(
        String low
    ) {
        return
            low.contains(".jpg")
            || low.contains(".jpeg")
            || low.contains(".png")
            || low.contains(".webp")
            || low.contains(".gif")
            || low.contains(".avif")
            || low.contains(".svg");
    }


    private void extractCurrentMedia() {
        extractCurrentMediaInternal(
            false
        );
    }


    private void extractCurrentMediaInternal(
        boolean passive
    ) {
        if (
            sessionWebView.getVisibility()
                != View.VISIBLE
        ) {
            return;
        }

        if (!passive) {
            browserStatus.setText(
                "正在从当前页面和播放器读取真实媒体地址…"
            );
        }

        String javascript =
            "(function(){"
            + "const out={title:document.title||'',page:location.href,videos:[],audios:[]};"
            + "const sv=new Set(),sa=new Set();"
            + "function norm(u){"
            + " if(!u||typeof u!=='string')return '';"
            + " if(u.startsWith('//'))u=location.protocol+u;"
            + " return u;"
            + "}"
            + "function addV(u){u=norm(u);if(/^https?:/i.test(u)&&!sv.has(u)){sv.add(u);out.videos.push(u);}}"
            + "function addA(u){u=norm(u);if(/^https?:/i.test(u)&&!sa.has(u)){sa.add(u);out.audios.push(u);}}"

            // Real DOM player sources
            + "document.querySelectorAll('video').forEach(v=>{"
            + " addV(v.currentSrc);addV(v.src);"
            + " try{v.querySelectorAll('source').forEach(s=>addV(s.src));}catch(e){}"
            + "});"
            + "document.querySelectorAll('audio').forEach(a=>{"
            + " addA(a.currentSrc);addA(a.src);"
            + " try{a.querySelectorAll('source').forEach(s=>addA(s.src));}catch(e){}"
            + "});"

            // Performance requests generated by the actual player
            + "try{performance.getEntriesByType('resource').forEach(e=>{"
            + " const u=e.name||'',l=u.toLowerCase();"
            + " if(l.includes('.mp4')||l.includes('douyinvod.com')||l.includes('sns-video')||l.includes('/video/tos/'))addV(u);"
            + " if(l.includes('.m4a')||l.includes('.mp3')||l.includes('.aac'))addA(u);"
            + "});}catch(e){}"

            // Structured state: only URLs stored under media-like keys
            + "function walk(o,k,d){"
            + " if(d>12||o==null)return;"
            + " if(typeof o==='string'){"
            + "   if(!/^https?:\\/\\//i.test(o))return;"
            + "   const lk=(k||'').toLowerCase(),lu=o.toLowerCase();"
            + "   if(lk.includes('masterurl')||lk.includes('play_addr')||lk.includes('playaddr')||lk.includes('download_addr'))addV(o);"
            + "   if((lk.includes('music')||lk.includes('audio'))&&(lk.includes('url')||lk.includes('play')))addA(o);"
            + "   return;"
            + " }"
            + " if(Array.isArray(o)){for(let i=0;i<Math.min(o.length,120);i++)walk(o[i],k+'['+i+']',d+1);return;}"
            + " if(typeof o==='object'){let n=0;for(const x in o){if(++n>900)break;try{walk(o[x],k+'.'+x,d+1);}catch(e){}}}"
            + "}"
            + "try{walk(window.__INITIAL_STATE__,'INITIAL_STATE',0);}catch(e){}"
            + "try{walk(window._ROUTER_DATA,'ROUTER_DATA',0);}catch(e){}"
            + "return JSON.stringify(out);"
            + "})()";

        sessionWebView.evaluateJavascript(
            javascript,
            value ->
                handlePageMediaJson(
                    value,
                    passive
                )
        );
    }


    private void handlePageMediaJson(
        String encoded,
        boolean passive
    ) {
        try {
            String json =
                decodeEvaluateJavascriptString(
                    encoded
                );

            if (
                json == null
                || json.isEmpty()
            ) {
                if (!passive) {
                    browserStatus.setText(
                        "页面没有返回可分析的媒体信息。"
                    );
                }

                return;
            }

            JSONObject obj =
                new JSONObject(
                    json
                );

            currentPageUrl =
                obj.optString(
                    "page",
                    currentPageUrl
                );

            Set<String> videoCandidates =
                new CopyOnWriteArraySet<>();

            Set<String> audioCandidates =
                new CopyOnWriteArraySet<>();

            videoCandidates.addAll(
                networkVideoCandidates
            );

            audioCandidates.addAll(
                networkAudioCandidates
            );

            JSONArray videos =
                obj.optJSONArray(
                    "videos"
                );

            if (videos != null) {
                for (
                    int i = 0;
                    i < videos.length();
                    i++
                ) {
                    String u =
                        videos.optString(
                            i,
                            ""
                        );

                    if (
                        u.startsWith("http")
                        && !u.toLowerCase(
                            Locale.US
                        ).contains(".m3u8")
                    ) {
                        videoCandidates.add(
                            u
                        );
                    }
                }
            }

            JSONArray audios =
                obj.optJSONArray(
                    "audios"
                );

            if (audios != null) {
                for (
                    int i = 0;
                    i < audios.length();
                    i++
                ) {
                    String u =
                        audios.optString(
                            i,
                            ""
                        );

                    if (
                        u.startsWith("http")
                    ) {
                        audioCandidates.add(
                            u
                        );
                    }
                }
            }

            if (
                videoCandidates.isEmpty()
                && audioCandidates.isEmpty()
            ) {
                if (!passive) {
                    browserStatus.setText(
                        "暂未发现真实媒体。请先在页面里播放视频，再点“提取当前媒体”。"
                    );
                }

                return;
            }

            validateCandidates(
                obj.optString(
                    "title",
                    "当前页面媒体"
                ),
                videoCandidates,
                audioCandidates,
                passive
            );

        } catch (Exception e) {
            if (!passive) {
                browserStatus.setText(
                    "读取页面媒体失败："
                    + e.getMessage()
                );
            }
        }
    }


    private void validateCandidates(
        final String title,
        final Set<String> videoCandidates,
        final Set<String> audioCandidates,
        final boolean passive
    ) {
        final JSONArray validVideos =
            new JSONArray();

        final JSONArray validAudios =
            new JSONArray();

        int total =
            Math.min(
                16,
                videoCandidates.size()
            )
            + Math.min(
                10,
                audioCandidates.size()
            );

        if (total == 0) {
            return;
        }

        AtomicInteger remaining =
            new AtomicInteger(
                total
            );

        AtomicInteger videoIndex =
            new AtomicInteger(0);

        AtomicInteger audioIndex =
            new AtomicInteger(0);

        int used = 0;

        for (String u : videoCandidates) {
            if (used++ >= 16) {
                break;
            }

            validateMediaAsync(
                u,
                true,
                ok -> {
                    if (ok) {
                        synchronized (
                            validVideos
                        ) {
                            try {
                                JSONObject item =
                                    new JSONObject();

                                item.put(
                                    "quality",
                                    "当前页面真实视频"
                                );

                                item.put(
                                    "url",
                                    u
                                );

                                item.put(
                                    "ext",
                                    "mp4"
                                );

                                validVideos.put(
                                    item
                                );

                            } catch (
                                Exception ignored
                            ) {
                            }
                        }
                    }

                    if (
                        remaining
                            .decrementAndGet()
                            == 0
                    ) {
                        finishMediaValidation(
                            title,
                            validVideos,
                            validAudios,
                            passive
                        );
                    }
                }
            );
        }

        used = 0;

        for (String u : audioCandidates) {
            if (used++ >= 10) {
                break;
            }

            validateMediaAsync(
                u,
                false,
                ok -> {
                    if (ok) {
                        synchronized (
                            validAudios
                        ) {
                            try {
                                JSONObject item =
                                    new JSONObject();

                                item.put(
                                    "quality",
                                    "当前页面真实音频"
                                );

                                item.put(
                                    "url",
                                    u
                                );

                                item.put(
                                    "ext",
                                    guessAudioExt(
                                        u
                                    )
                                );

                                validAudios.put(
                                    item
                                );

                            } catch (
                                Exception ignored
                            ) {
                            }
                        }
                    }

                    if (
                        remaining
                            .decrementAndGet()
                            == 0
                    ) {
                        finishMediaValidation(
                            title,
                            validVideos,
                            validAudios,
                            passive
                        );
                    }
                }
            );
        }
    }


    private interface BoolCallback {
        void onResult(
            boolean value
        );
    }


    private void validateMediaAsync(
        final String url,
        final boolean wantVideo,
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
                        4500
                    );

                    c.setReadTimeout(
                        4500
                    );

                    c.setRequestProperty(
                        "User-Agent",
                        sessionWebView
                            .getSettings()
                            .getUserAgentString()
                    );

                    if (
                        currentPageUrl != null
                        && !currentPageUrl.isEmpty()
                    ) {
                        c.setRequestProperty(
                            "Referer",
                            currentPageUrl
                        );
                    }

                    String mediaCookie =
                        CookieManager
                            .getInstance()
                            .getCookie(url);

                    if (
                        mediaCookie != null
                        && !mediaCookie.isEmpty()
                    ) {
                        c.setRequestProperty(
                            "Cookie",
                            mediaCookie
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

                    boolean audio =
                        lowType.startsWith(
                            "audio/"
                        )
                        || prefix.startsWith(
                            "ID3"
                        )
                        || (
                            prefix.contains(
                                "ftyp"
                            )
                            && !wantVideo
                        );

                    valid =
                        (
                            status == 200
                            || status == 206
                        )
                        && !html
                        && (
                            wantVideo
                                ? video
                                : audio
                        );

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


    private void finishMediaValidation(
        String title,
        JSONArray videos,
        JSONArray audios,
        boolean passive
    ) {
        if (
            videos.length() == 0
            && audios.length() == 0
        ) {
            if (!passive) {
                browserStatus.setText(
                    "找到了一些候选地址，但全部校验失败。请确认页面里的视频已经真正开始播放，再重新提取。"
                );
            }

            return;
        }

        try {
            JSONObject result =
                new JSONObject();

            result.put(
                "success",
                true
            );

            result.put(
                "platform",
                platformName(
                    currentPageUrl
                )
                    + " / 当前浏览器会话"
            );

            result.put(
                "title",
                title == null
                    || title.trim().isEmpty()
                        ? "当前页面媒体"
                        : title
            );

            result.put(
                "source_url",
                currentPageUrl
            );

            result.put(
                "resolved_url",
                currentPageUrl
            );

            result.put(
                "videos",
                videos
            );

            result.put(
                "audios",
                audios
            );

            showHome();

            String quoted =
                JSONObject.quote(
                    result.toString()
                );

            homeWebView.evaluateJavascript(
                "window.onBrowserMediaResult && "
                    + "window.onBrowserMediaResult(JSON.parse("
                    + quoted
                    + "));",
                null
            );

        } catch (Exception e) {
            browserStatus.setText(
                "构建媒体结果失败："
                    + e.getMessage()
            );
        }
    }


    private String decodeEvaluateJavascriptString(
        String value
    ) {
        try {
            if (
                value == null
                || "null".equals(value)
            ) {
                return null;
            }

            Object decoded =
                new JSONTokener(
                    value
                ).nextValue();

            if (
                decoded
                    instanceof String
            ) {
                return (String)
                    decoded;
            }

            return String.valueOf(
                decoded
            );

        } catch (Exception e) {
            return null;
        }
    }


    private String extractFirstHttpUrl(
        String text
    ) {
        if (text == null) {
            return null;
        }

        int http =
            text.indexOf(
                "http://"
            );

        int https =
            text.indexOf(
                "https://"
            );

        int start;

        if (http < 0) {
            start = https;

        } else if (https < 0) {
            start = http;

        } else {
            start =
                Math.min(
                    http,
                    https
                );
        }

        if (start < 0) {
            return null;
        }

        int end = start;

        while (
            end < text.length()
        ) {
            char c =
                text.charAt(
                    end
                );

            if (
                Character
                    .isWhitespace(c)
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

        String url =
            text.substring(
                start,
                end
            );

        while (
            url.endsWith(".")
            || url.endsWith(",")
            || url.endsWith("。")
            || url.endsWith("，")
        ) {
            url =
                url.substring(
                    0,
                    url.length() - 1
                );
        }

        return url;
    }


    private String platformName(
        String url
    ) {
        String low =
            url == null
                ? ""
                : url.toLowerCase(
                    Locale.US
                );

        if (
            low.contains(
                "douyin"
            )
        ) {
            return "Douyin";
        }

        if (
            low.contains(
                "xiaohongshu"
            )
            || low.contains(
                "xhslink"
            )
        ) {
            return "XiaoHongShu";
        }

        return "Browser";
    }


    private String guessAudioExt(
        String url
    ) {
        String low =
            url.toLowerCase(
                Locale.US
            );

        if (
            low.contains(".m4a")
        ) {
            return "m4a";
        }

        if (
            low.contains(".aac")
        ) {
            return "aac";
        }

        return "mp3";
    }


    private void sendHomeError(
        String message
    ) {
        String q =
            JSONObject.quote(
                message
            );

        homeWebView
            .evaluateJavascript(
                "window.onBrowserMediaError && "
                    + "window.onBrowserMediaError("
                    + q
                    + ");",
                null
            );
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


    public class AndroidDownloader {

        @JavascriptInterface
        public void download(
            final String url,
            final String filename,
            final String referer
        ) {
            runOnUiThread(
                () ->
                    validateAndStartDownload(
                        url,
                        filename,
                        referer
                    )
            );
        }
    }


    private void validateAndStartDownload(
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

        boolean video =
            lower.endsWith(".mp4")
            || lower.endsWith(".webm")
            || lower.endsWith(".mov");

        boolean audio =
            lower.endsWith(".mp3")
            || lower.endsWith(".m4a")
            || lower.endsWith(".aac")
            || lower.endsWith(".wav")
            || lower.endsWith(".ogg");

        if (
            video
            || audio
        ) {
            validateMediaAsync(
                url,
                video,
                ok -> {
                    if (!ok) {
                        Toast.makeText(
                            MainActivity.this,
                            "媒体地址已失效或不是有效文件。请返回平台页面重新播放后再提取。",
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

            return;
        }

        startDownload(
            url,
            filename,
            referer
        );
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

            String mediaCookie =
                CookieManager
                    .getInstance()
                    .getCookie(
                        url
                    );

            if (
                mediaCookie != null
                && !mediaCookie.isEmpty()
            ) {
                req.addRequestHeader(
                    "Cookie",
                    mediaCookie
                );
            }

            req.addRequestHeader(
                "User-Agent",
                sessionWebView
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

            String mimeType =
                guessMimeType(
                    filename
                );

            req.setMimeType(
                mimeType
            );

            req.setTitle(
                filename
            );

            String targetDir =
                getTargetDirectory(
                    filename
                );

            req.setDestinationInExternalPublicDir(
                targetDir,
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
                View.VISIBLE
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

        if (
            lower.endsWith(".mp4")
        ) {
            return "video/mp4";
        }

        if (
            lower.endsWith(".webm")
        ) {
            return "video/webm";
        }

        if (
            lower.endsWith(".mkv")
        ) {
            return "video/x-matroska";
        }

        if (
            lower.endsWith(".mov")
        ) {
            return "video/quicktime";
        }

        if (
            lower.endsWith(".mp3")
        ) {
            return "audio/mpeg";
        }

        if (
            lower.endsWith(".m4a")
        ) {
            return "audio/mp4";
        }

        if (
            lower.endsWith(".aac")
        ) {
            return "audio/aac";
        }

        if (
            lower.endsWith(".wav")
        ) {
            return "audio/wav";
        }

        if (
            lower.endsWith(".ogg")
        ) {
            return "audio/ogg";
        }

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


    @Override
    public void onBackPressed() {
        if (
            sessionWebView != null
            && sessionWebView
                .getVisibility()
                == View.VISIBLE
        ) {
            if (
                sessionWebView
                    .canGoBack()
            ) {
                sessionWebView.goBack();
            } else {
                showHome();
            }

            return;
        }

        if (
            homeWebView != null
            && homeWebView
                .canGoBack()
        ) {
            homeWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
