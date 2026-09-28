package com.videocollector.app;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public class MainActivity extends Activity {

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
    private boolean currentDownloadPostVerify = false;

    private String currentPageUrl = "";
    private String sessionPlatform = "Browser";

    private final Set<String> networkVideoCandidates =
        new CopyOnWriteArraySet<>();

    private final Set<String> networkAudioCandidates =
        new CopyOnWriteArraySet<>();

    private Class<?> nativeCoreClass;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        downloadManager =
            (DownloadManager)
                getSystemService(Context.DOWNLOAD_SERVICE);

        handler =
            new Handler(Looper.getMainLooper());

        buildLayout();
        configureHomeWebView();
        configureSessionWebView();
        initNativeCore();

        homeWebView.loadUrl(
            "file:///android_asset/index.html"
        );
    }


    private void buildLayout() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101317);

        browserToolbar = new LinearLayout(this);
        browserToolbar.setOrientation(LinearLayout.VERTICAL);
        browserToolbar.setPadding(14, 10, 14, 10);
        browserToolbar.setBackgroundColor(0xFF181D23);
        browserToolbar.setVisibility(View.GONE);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        Button back = new Button(this);
        back.setText("返回");

        Button reload = new Button(this);
        reload.setText("刷新");

        Button extract = new Button(this);
        extract.setText("提取当前媒体");

        LinearLayout.LayoutParams one =
            new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            );

        row.addView(back, one);
        row.addView(reload, one);
        row.addView(extract, one);

        browserStatus = new TextView(this);
        browserStatus.setTextColor(0xFFD5DDE5);
        browserStatus.setTextSize(13f);
        browserStatus.setPadding(8, 8, 8, 0);
        browserStatus.setText(
            "先让页面里的视频真正开始播放，再点“提取当前媒体”。如果网页要求登录，本工具不会绕过登录。"
        );

        browserToolbar.addView(row);
        browserToolbar.addView(browserStatus);

        back.setOnClickListener(v -> showHome());
        reload.setOnClickListener(v -> sessionWebView.reload());
        extract.setOnClickListener(v -> extractCurrentMedia());

        root.addView(
            browserToolbar,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        );

        homeWebView = new WebView(this);
        root.addView(
            homeWebView,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        );

        sessionWebView = new WebView(this);
        sessionWebView.setVisibility(View.GONE);
        root.addView(
            sessionWebView,
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
        progressParams.topMargin = 8;

        downloadPanel.addView(
            downloadProgress,
            progressParams
        );

        root.addView(downloadPanel);
        setContentView(root);
    }


    private void configureHomeWebView() {
        WebSettings s = homeWebView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setUserAgentString(
            s.getUserAgentString() + " WangParser/7.2.0"
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
            new AndroidNativeParser(),
            "AndroidNativeParser"
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
        WebSettings s = sessionWebView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setLoadsImagesAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(true);

        String ua = s.getUserAgentString();
        s.setUserAgentString(
            ua.replace("; wv", "")
                + " WangParserSession/7.2.0"
        );

        CookieManager cm = CookieManager.getInstance();
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
                        request.getUrl().toString();

                    return !(
                        u.startsWith("http://")
                        || u.startsWith("https://")
                    );
                }


                @Override
                public WebResourceResponse shouldInterceptRequest(
                    WebView view,
                    WebResourceRequest request
                ) {
                    inspectNetworkUrl(
                        request.getUrl().toString()
                    );

                    return super.shouldInterceptRequest(
                        view,
                        request
                    );
                }


                @Override
                public void onPageFinished(
                    WebView view,
                    String url
                ) {
                    currentPageUrl =
                        url == null ? "" : url;

                    browserStatus.setText(
                        "页面已打开。请先让视频真正开始播放，再点“提取当前媒体”。如果页面跳到登录页，本工具不会绕过登录限制。"
                    );
                }
            }
        );
    }


    private void initNativeCore() {
        try {
            String className =
                readAssetText(
                    "native_class.txt"
                ).trim();

            nativeCoreClass =
                Class.forName(className);

            Method init =
                findNativeMethod(
                    "init",
                    1
                );

            if (init != null) {
                init.invoke(
                    null,
                    getFilesDir().getAbsolutePath()
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
            getAssets().open(name);

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
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }

        reader.close();
        in.close();
        return sb.toString();
    }


    private Method findNativeMethod(
        String name,
        int count
    ) {
        if (nativeCoreClass == null) {
            return null;
        }

        for (Method method : nativeCoreClass.getMethods()) {
            if (
                method.getName().equalsIgnoreCase(name)
                && method.getParameterTypes().length == count
                && Modifier.isStatic(method.getModifiers())
            ) {
                return method;
            }
        }

        return null;
    }


    public class AndroidNativeParser {

        @JavascriptInterface
        public void parse(final String text) {
            new Thread(
                () -> {
                    String output;

                    try {
                        if (nativeCoreClass == null) {
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
                                : String.valueOf(value);

                        if (output.trim().isEmpty()) {
                            throw new Exception(
                                "本地解析器返回空结果"
                            );
                        }

                    } catch (Exception e) {
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

                            output = err.toString();

                        } catch (Exception ignored) {
                            output =
                                "{\"success\":false,\"error\":\"本地解析桥接失败\"}";
                        }
                    }

                    final String result = output;

                    handler.post(
                        () -> {
                            String quoted =
                                JSONObject.quote(result);

                            homeWebView.evaluateJavascript(
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


    public class AndroidBrowserSession {

        @JavascriptInterface
        public void open(final String text) {
            runOnUiThread(
                () -> {
                    String url =
                        extractFirstHttpUrl(text);

                    if (url == null) {
                        sendHomeError(
                            "没有识别到可打开的抖音链接。"
                        );
                        return;
                    }

                    networkVideoCandidates.clear();
                    networkAudioCandidates.clear();
                    currentPageUrl = url;

                    String low = url.toLowerCase(Locale.US);
                    if (low.contains("xiaohongshu") || low.contains("xhslink")) {
                        sessionPlatform = "XiaoHongShu";
                    } else if (low.contains("douyin") || low.contains("iesdouyin")) {
                        sessionPlatform = "Douyin";
                    } else {
                        sessionPlatform = "Browser";
                    }

                    homeWebView.setVisibility(View.GONE);
                    browserToolbar.setVisibility(View.VISIBLE);
                    sessionWebView.setVisibility(View.VISIBLE);

                    browserStatus.setText(
                        "正在打开" + sessionPlatform + "真实网页…"
                    );

                    sessionWebView.loadUrl(url);
                }
            );
        }
    }


    private void inspectNetworkUrl(String u) {
        if (u == null || u.isEmpty()) {
            return;
        }

        String l = u.toLowerCase(Locale.US);

        if (
            l.startsWith("blob:")
            || l.startsWith("data:")
            || l.contains(".jpg")
            || l.contains(".jpeg")
            || l.contains(".png")
            || l.contains(".webp")
            || l.contains(".gif")
        ) {
            return;
        }

        if (
            l.contains("douyinvod.com")
            || l.contains("/video/tos/")
            || l.contains("/aweme/v1/play/")
            || l.contains("xhscdn.com")
            || l.contains("xhscdn.net")
            || l.contains("sns-video")
            || l.contains("/stream/")
            || l.contains(".mp4")
        ) {
            if (
                !l.contains(".m3u8")
                && !l.contains(".m4s")
            ) {
                networkVideoCandidates.add(u);
            }
        }

        if (
            l.contains(".m4a")
            || l.contains(".mp3")
            || l.contains(".aac")
            || (
                l.contains("music")
                && l.contains("play")
            )
        ) {
            networkAudioCandidates.add(u);
        }
    }


    private void extractCurrentMedia() {
        browserStatus.setText(
            "正在读取当前播放器与网络请求…"
        );

        String js =
            "(function(){"
            + "const o={title:document.title||'',page:location.href,videos:[],audios:[]};"
            + "const v=new Set(),a=new Set();"
            + "function norm(u){if(typeof u!=='string')return'';return u.replace(/\\\\u002F/gi,'/').replace(/\\\\\\//g,'/');}"
            + "function addV(u){u=norm(u);if(!/^https?:/i.test(u))return;let l=u.toLowerCase();"
            + " if(/\\.(jpg|jpeg|png|webp|gif)(\\?|$)/i.test(l)||l.includes('imageview')||l.includes('imagemogr'))return;"
            + " if(l.includes('douyinvod')||l.includes('/video/tos/')||l.includes('/aweme/v1/play/')||l.includes('xhscdn')||l.includes('sns-video')||l.includes('/stream/')||l.includes('.mp4'))v.add(u);}"
            + "function addA(u){u=norm(u);if(/^https?:/i.test(u)&&/\\.(m4a|mp3|aac)(\\?|$)/i.test(u))a.add(u);}"
            + "document.querySelectorAll('video').forEach(x=>{addV(x.currentSrc);addV(x.src);try{x.querySelectorAll('source').forEach(s=>addV(s.src));}catch(e){}});"
            + "document.querySelectorAll('audio').forEach(x=>{addA(x.currentSrc);addA(x.src);try{x.querySelectorAll('source').forEach(s=>addA(s.src));}catch(e){}});"
            + "try{performance.getEntriesByType('resource').forEach(e=>{addV(e.name||'');addA(e.name||'');});}catch(e){}"
            + "try{let seen=0;function walk(x,d){if(!x||d>12||seen++>12000)return;"
            + " if(typeof x==='string'){addV(x);addA(x);return;}"
            + " if(Array.isArray(x)){for(let i=0;i<x.length&&i<300;i++)walk(x[i],d+1);return;}"
            + " if(typeof x==='object'){for(const k in x){if(seen>12000)break;try{walk(x[k],d+1);}catch(e){}}}}"
            + " walk(window.__INITIAL_STATE__,0);}catch(e){}"
            + "o.videos=[...v];o.audios=[...a];return JSON.stringify(o);"
            + "})()";

        sessionWebView.evaluateJavascript(
            js,
            this::handleBrowserMediaJson
        );
    }


    private void handleBrowserMediaJson(
        String encoded
    ) {
        try {
            String json =
                decodeEvaluateJavascriptString(
                    encoded
                );

            JSONObject data =
                new JSONObject(json);

            currentPageUrl =
                data.optString(
                    "page",
                    currentPageUrl
                );

            Set<String> videos =
                new CopyOnWriteArraySet<>();

            Set<String> audios =
                new CopyOnWriteArraySet<>();

            videos.addAll(
                networkVideoCandidates
            );

            audios.addAll(
                networkAudioCandidates
            );

            JSONArray pageVideos =
                data.optJSONArray("videos");

            if (pageVideos != null) {
                for (
                    int i = 0;
                    i < pageVideos.length();
                    i++
                ) {
                    String u =
                        pageVideos.optString(i, "");
                    if (
                        u.startsWith("http")
                        && !u.contains(".m3u8")
                        && !u.contains(".m4s")
                    ) {
                        videos.add(u);
                    }
                }
            }

            JSONArray pageAudios =
                data.optJSONArray("audios");

            if (pageAudios != null) {
                for (
                    int i = 0;
                    i < pageAudios.length();
                    i++
                ) {
                    String u =
                        pageAudios.optString(i, "");
                    if (u.startsWith("http")) {
                        audios.add(u);
                    }
                }
            }

            if (
                videos.isEmpty()
                && audios.isEmpty()
            ) {
                browserStatus.setText(
                    "没有捕获到可直接下载的 HTTP 媒体。请确认视频已经真正开始播放；如果页面要求登录、验证码或只使用 blob/MSE 分段播放，免费本地模式不会绕过限制，也不会生成假文件。"
                );
                return;
            }

            JSONObject result =
                new JSONObject();

            result.put("success", true);
            result.put(
                "platform",
                sessionPlatform + " / 浏览器会话"
            );
            result.put(
                "title",
                data.optString(
                    "title",
                    "抖音当前视频"
                )
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
                "referer",
                currentPageUrl
            );
            result.put(
                "session_download",
                true
            );

            JSONArray vv = new JSONArray();
            int count = 0;
            for (String u : videos) {
                if (count++ >= 8) {
                    break;
                }

                JSONObject item =
                    new JSONObject();

                item.put(
                    "quality",
                    "网页会话捕获视频"
                );
                item.put("url", u);
                item.put("ext", "mp4");
                vv.put(item);
            }

            JSONArray aa = new JSONArray();
            count = 0;
            for (String u : audios) {
                if (count++ >= 6) {
                    break;
                }

                JSONObject item =
                    new JSONObject();

                item.put(
                    "quality",
                    "网页会话捕获音频"
                );
                item.put("url", u);
                item.put(
                    "ext",
                    guessAudioExt(u)
                );
                aa.put(item);
            }

            result.put("videos", vv);
            result.put("audios", aa);

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
                "读取浏览器媒体失败："
                    + e.getMessage()
            );
        }
    }


    private void showHome() {
        sessionWebView.setVisibility(View.GONE);
        browserToolbar.setVisibility(View.GONE);
        homeWebView.setVisibility(View.VISIBLE);
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
                new JSONTokener(value)
                    .nextValue();

            return decoded instanceof String
                ? (String) decoded
                : String.valueOf(decoded);

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

        int h1 =
            text.indexOf("http://");
        int h2 =
            text.indexOf("https://");

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


    private void sendHomeError(
        String message
    ) {
        String q =
            JSONObject.quote(message);

        homeWebView.evaluateJavascript(
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
                    || clip.getItemCount() == 0
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


        @JavascriptInterface
        public void downloadSession(
            final String url,
            final String filename,
            final String referer
        ) {
            runOnUiThread(
                () ->
                    startDownload(
                        url,
                        filename,
                        referer,
                        true
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
            || lower.endsWith(".mov");

        if (!isVideo) {
            startDownload(
                url,
                filename,
                referer,
                false
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
                        "视频地址校验失败，已阻止保存，避免生成 0 秒文件。",
                        Toast.LENGTH_LONG
                    ).show();
                    return;
                }

                startDownload(
                    url,
                    filename,
                    referer,
                    false
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
                    c.setConnectTimeout(6000);
                    c.setReadTimeout(6000);

                    c.setRequestProperty(
                        "User-Agent",
                        homeWebView
                            .getSettings()
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
                        "bytes=0-2047"
                    );

                    int status =
                        c.getResponseCode();

                    String type =
                        c.getContentType();

                    InputStream in =
                        status >= 200
                        && status < 400
                            ? c.getInputStream()
                            : null;

                    byte[] head = new byte[96];

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
                        || prefix.contains("ftyp");

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
                    () ->
                        callback.onResult(result)
                );
            }
        ).start();
    }


    private void startDownload(
        String url,
        String filename,
        String referer,
        boolean postVerify
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
                CookieManager
                    .getInstance()
                    .getCookie(url);

            if (
                cookie != null
                && !cookie.isEmpty()
            ) {
                req.addRequestHeader(
                    "Cookie",
                    cookie
                );
            }

            String ua =
                postVerify
                    ? sessionWebView
                        .getSettings()
                        .getUserAgentString()
                    : homeWebView
                        .getSettings()
                        .getUserAgentString();

            req.addRequestHeader(
                "User-Agent",
                ua
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

            currentDownloadPostVerify =
                postVerify;

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


    private boolean verifyDownloadedVideo(
        long id
    ) {
        MediaMetadataRetriever mmr =
            new MediaMetadataRetriever();

        try {
            Uri uri =
                downloadManager
                    .getUriForDownloadedFile(id);

            if (uri == null) {
                return false;
            }

            mmr.setDataSource(
                this,
                uri
            );

            String duration =
                mmr.extractMetadata(
                    MediaMetadataRetriever
                        .METADATA_KEY_DURATION
                );

            long d =
                duration == null
                    ? 0
                    : Long.parseLong(duration);

            return d > 0;

        } catch (Exception e) {
            return false;

        } finally {
            try {
                mmr.release();
            } catch (Exception ignored) {
            }
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


    private String guessAudioExt(
        String url
    ) {
        String l =
            url.toLowerCase(Locale.US);

        if (l.contains(".m4a")) {
            return "m4a";
        }

        if (l.contains(".aac")) {
            return "aac";
        }

        return "mp3";
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
                            == DownloadManager
                                .STATUS_SUCCESSFUL
                    ) {
                        String lower =
                            filename.toLowerCase(Locale.US);

                        boolean isVideo =
                            lower.endsWith(".mp4")
                            || lower.endsWith(".webm")
                            || lower.endsWith(".mkv")
                            || lower.endsWith(".mov");

                        if (
                            currentDownloadPostVerify
                            && isVideo
                            && !verifyDownloadedVideo(id)
                        ) {
                            downloadManager.remove(id);

                            downloadProgress.setProgress(0);

                            downloadText.setText(
                                "下载内容不是完整视频，已自动删除。"
                            );

                            Toast.makeText(
                                this,
                                "该网页会话地址不是完整视频文件，已删除，避免生成 0 秒白屏文件。",
                                Toast.LENGTH_LONG
                            ).show();

                            currentDownloadId = -1L;
                            currentDownloadPostVerify = false;
                            return;
                        }

                        downloadProgress
                            .setIndeterminate(false);
                        downloadProgress.setProgress(100);

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

                            if (uriIndex >= 0) {
                                String localUri =
                                    c.getString(uriIndex);

                                if (
                                    localUri != null
                                    && localUri
                                        .startsWith("file://")
                                ) {
                                    String localPath =
                                        Uri.parse(localUri)
                                            .getPath();

                                    if (localPath != null) {
                                        MediaScannerConnection
                                            .scanFile(
                                                MainActivity.this,
                                                new String[]{localPath},
                                                new String[]{
                                                    guessMimeType(filename)
                                                },
                                                null
                                            );
                                    }
                                }
                            }

                        } catch (Exception ignored) {
                        }

                        Toast.makeText(
                            this,
                            isVideo
                                ? "下载完成，已保存到 Movies/WangParser"
                                : "下载完成，已保存到 Music/WangParser",
                            Toast.LENGTH_LONG
                        ).show();

                        currentDownloadId = -1L;
                        currentDownloadPostVerify = false;
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

                        currentDownloadId = -1L;
                        currentDownloadPostVerify = false;
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
            sessionWebView != null
            && sessionWebView.getVisibility()
                == View.VISIBLE
        ) {
            if (sessionWebView.canGoBack()) {
                sessionWebView.goBack();
            } else {
                showHome();
            }
            return;
        }

        if (
            homeWebView != null
            && homeWebView.canGoBack()
        ) {
            homeWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
