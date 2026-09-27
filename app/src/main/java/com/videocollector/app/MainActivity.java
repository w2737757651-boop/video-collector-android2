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
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.Iterator;
import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import java.net.URL;
import java.net.HttpURLConnection;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.BufferedReader;
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
            s.getUserAgentString() + " VideoCollectorApp/5.3"
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
            ua.replace("; wv", "") + " VideoCollectorLocalParser/5.3"
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

                    String current =
                        url == null
                            ? ""
                            : url.toLowerCase(Locale.US);

                    boolean isXhs =
                        current.contains("xiaohongshu.com")
                        || current.contains("xhslink.");

                    if (isXhs) {
                        handler.postDelayed(
                            () -> scrapeXhsStructured(generation),
                            700
                        );

                        handler.postDelayed(
                            () -> scrapeXhsStructured(generation),
                            1800
                        );

                        handler.postDelayed(
                            () -> scrapeXhsStructured(generation),
                            3500
                        );
                    }

                    // Generic network/page scan remains fallback only.
                    handler.postDelayed(
                        () -> scrapeParserPage(generation, 1),
                        2200
                    );

                    handler.postDelayed(
                        () -> scrapeParserPage(generation, 2),
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



    private static final String DOUYIN_IPHONE_UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
        + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 "
        + "Mobile/15E148 Safari/604.1";


    private void parseDouyinHttpFirst(
        final String sourceUrl,
        final int generation
    ) {
        new Thread(
            () -> {
                try {
                    sendLocalStatus(
                        "抖音：正在手机本地解析分享链接…"
                    );

                    String finalUrl =
                        httpResolveFinalUrl(
                            sourceUrl,
                            DOUYIN_IPHONE_UA
                        );

                    String awemeId =
                        extractDouyinAwemeId(
                            finalUrl
                        );

                    if (awemeId == null) {
                        awemeId =
                            extractDouyinAwemeId(
                                sourceUrl
                            );
                    }

                    if (awemeId == null) {
                        throw new Exception(
                            "没有从抖音分享链接中识别到作品 ID"
                        );
                    }

                    sendLocalStatus(
                        "抖音：已识别作品，正在读取公开分享页数据…"
                    );

                    String shareUrl =
                        "https://www.iesdouyin.com/share/video/"
                        + awemeId
                        + "/";

                    String html =
                        httpGetText(
                            shareUrl,
                            DOUYIN_IPHONE_UA,
                            "https://www.douyin.com/"
                        );

                    String routerJson =
                        extractBalancedJsonAfter(
                            html,
                            "window._ROUTER_DATA"
                        );

                    if (routerJson == null) {
                        routerJson =
                            extractBalancedJsonAfter(
                                html,
                                "_ROUTER_DATA"
                            );
                    }

                    if (routerJson == null) {
                        throw new Exception(
                            "抖音公开分享页没有返回 _ROUTER_DATA"
                        );
                    }

                    JSONObject root =
                        new JSONObject(routerJson);

                    JSONObject item =
                        findDouyinItem(root);

                    if (item == null) {
                        throw new Exception(
                            "抖音页面数据里没有找到作品详情"
                        );
                    }

                    JSONObject result =
                        buildDouyinResult(
                            item,
                            sourceUrl,
                            shareUrl
                        );

                    if (
                        result.optJSONArray("videos") == null
                        || result.optJSONArray("videos").length() == 0
                    ) {
                        throw new Exception(
                            "该作品没有找到可直接访问的视频地址"
                        );
                    }

                    handler.post(
                        () -> {
                            if (
                                generation != parseGeneration
                                || localResultDelivered
                            ) {
                                return;
                            }

                            localResultDelivered = true;
                            deliverLocalResult(result);
                        }
                    );

                } catch (Exception primaryError) {

                    // WebView is fallback only.
                    handler.post(
                        () -> {
                            if (
                                generation != parseGeneration
                                || localResultDelivered
                            ) {
                                return;
                            }

                            sendLocalStatus(
                                "抖音直接解析未取得完整数据，"
                                + "正在使用手机网页兜底…"
                            );

                            parserWebView.stopLoading();
                            parserWebView.loadUrl(
                                sourceUrl
                            );
                        }
                    );
                }
            }
        ).start();
    }


    private String httpResolveFinalUrl(
        String sourceUrl,
        String userAgent
    ) throws Exception {
        String current = sourceUrl;

        for (int i = 0; i < 8; i++) {
            HttpURLConnection c =
                (HttpURLConnection)
                    new URL(current)
                        .openConnection();

            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(4500);
            c.setReadTimeout(4500);
            c.setRequestProperty(
                "User-Agent",
                userAgent
            );
            c.setRequestProperty(
                "Accept",
                "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8"
            );

            int status =
                c.getResponseCode();

            if (
                status >= 300
                && status < 400
            ) {
                String location =
                    c.getHeaderField(
                        "Location"
                    );

                c.disconnect();

                if (
                    location == null
                    || location.isEmpty()
                ) {
                    break;
                }

                current =
                    new URL(
                        new URL(current),
                        location
                    ).toString();

                continue;
            }

            String resolved =
                c.getURL().toString();

            c.disconnect();
            return resolved;
        }

        return current;
    }


    private String httpGetText(
        String url,
        String userAgent,
        String referer
    ) throws Exception {
        HttpURLConnection c =
            (HttpURLConnection)
                new URL(url)
                    .openConnection();

        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        c.setRequestProperty(
            "User-Agent",
            userAgent
        );
        c.setRequestProperty(
            "Accept",
            "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8"
        );
        c.setRequestProperty(
            "Accept-Language",
            "zh-CN,zh;q=0.9,en;q=0.8"
        );

        if (
            referer != null
            && !referer.isEmpty()
        ) {
            c.setRequestProperty(
                "Referer",
                referer
            );
        }

        int status =
            c.getResponseCode();

        InputStream stream =
            status >= 400
                ? c.getErrorStream()
                : c.getInputStream();

        if (stream == null) {
            c.disconnect();
            throw new Exception(
                "HTTP "
                + status
                + " 无响应内容"
            );
        }

        BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(
                    stream,
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
        c.disconnect();

        if (status >= 400) {
            throw new Exception(
                "HTTP " + status
            );
        }

        return sb.toString();
    }


    private String extractDouyinAwemeId(
        String url
    ) {
        if (url == null) {
            return null;
        }

        String[] patterns = new String[]{
            "/video/(\\d+)",
            "/share/video/(\\d+)",
            "[?&]modal_id=(\\d+)",
            "[?&]aweme_id=(\\d+)"
        };

        for (String p : patterns) {
            Matcher m =
                Pattern.compile(p)
                    .matcher(url);

            if (m.find()) {
                return m.group(1);
            }
        }

        return null;
    }


    private String extractBalancedJsonAfter(
        String text,
        String marker
    ) {
        if (
            text == null
            || marker == null
        ) {
            return null;
        }

        int markerIndex =
            text.indexOf(marker);

        if (markerIndex < 0) {
            return null;
        }

        int start =
            text.indexOf(
                '{',
                markerIndex
            );

        if (start < 0) {
            return null;
        }

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (
            int i = start;
            i < text.length();
            i++
        ) {
            char ch =
                text.charAt(i);

            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }

                continue;
            }

            if (ch == '"') {
                inString = true;
                continue;
            }

            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;

                if (depth == 0) {
                    return text.substring(
                        start,
                        i + 1
                    );
                }
            }
        }

        return null;
    }


    private JSONObject findDouyinItem(
        Object node
    ) {
        if (node == null) {
            return null;
        }

        if (node instanceof JSONObject) {
            JSONObject obj =
                (JSONObject) node;

            if (
                obj.has("video")
                && (
                    obj.has("aweme_id")
                    || obj.has("desc")
                    || obj.has("author")
                )
            ) {
                return obj;
            }

            Iterator<String> keys =
                obj.keys();

            while (keys.hasNext()) {
                String key =
                    keys.next();

                Object child =
                    obj.opt(key);

                JSONObject found =
                    findDouyinItem(child);

                if (found != null) {
                    return found;
                }
            }

        } else if (
            node instanceof JSONArray
        ) {
            JSONArray arr =
                (JSONArray) node;

            for (
                int i = 0;
                i < arr.length();
                i++
            ) {
                JSONObject found =
                    findDouyinItem(
                        arr.opt(i)
                    );

                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }


    private String firstUrl(
        Object value
    ) {
        if (value == null) {
            return null;
        }

        if (value instanceof String) {
            String s =
                (String) value;

            return s.startsWith("http")
                ? s
                : null;
        }

        if (value instanceof JSONArray) {
            JSONArray arr =
                (JSONArray) value;

            for (
                int i = 0;
                i < arr.length();
                i++
            ) {
                String s =
                    firstUrl(
                        arr.opt(i)
                    );

                if (s != null) {
                    return s;
                }
            }

            return null;
        }

        if (value instanceof JSONObject) {
            JSONObject obj =
                (JSONObject) value;

            JSONArray urls =
                obj.optJSONArray(
                    "url_list"
                );

            if (urls == null) {
                urls =
                    obj.optJSONArray(
                        "urlList"
                    );
            }

            if (urls != null) {
                return firstUrl(urls);
            }

            String[] candidateKeys =
                new String[]{
                    "url",
                    "src",
                    "play_addr",
                    "playAddr",
                    "download_addr"
                };

            for (String key : candidateKeys) {
                if (obj.has(key)) {
                    String s =
                        firstUrl(
                            obj.opt(key)
                        );

                    if (s != null) {
                        return s;
                    }
                }
            }
        }

        return null;
    }


    private void addUrlList(
        JSONArray out,
        Object source,
        String quality,
        String ext
    ) throws Exception {
        if (source == null) {
            return;
        }

        JSONArray urls = null;

        if (source instanceof JSONObject) {
            JSONObject obj =
                (JSONObject) source;

            urls =
                obj.optJSONArray(
                    "url_list"
                );

            if (urls == null) {
                urls =
                    obj.optJSONArray(
                        "urlList"
                    );
            }

        } else if (
            source instanceof JSONArray
        ) {
            urls =
                (JSONArray) source;
        }

        if (urls == null) {
            String single =
                firstUrl(source);

            if (single != null) {
                JSONObject item =
                    new JSONObject();

                item.put(
                    "quality",
                    quality
                );

                item.put(
                    "url",
                    single
                );

                item.put(
                    "ext",
                    ext
                );

                out.put(item);
            }

            return;
        }

        Set<String> seen =
            new CopyOnWriteArraySet<>();

        for (
            int i = 0;
            i < urls.length();
            i++
        ) {
            String u =
                urls.optString(
                    i,
                    ""
                );

            if (
                !u.startsWith("http")
                || seen.contains(u)
            ) {
                continue;
            }

            seen.add(u);

            JSONObject item =
                new JSONObject();

            item.put(
                "quality",
                quality
            );

            item.put(
                "url",
                u
            );

            item.put(
                "ext",
                ext
            );

            out.put(item);
        }
    }


    private JSONObject buildDouyinResult(
        JSONObject item,
        String sourceUrl,
        String resolvedUrl
    ) throws Exception {
        JSONObject video =
            item.optJSONObject(
                "video"
            );

        JSONObject author =
            item.optJSONObject(
                "author"
            );

        JSONObject music =
            item.optJSONObject(
                "music"
            );

        JSONArray videos =
            new JSONArray();

        if (video != null) {
            Object play =
                video.opt(
                    "play_addr"
                );

            if (play == null) {
                play =
                    video.opt(
                        "playAddr"
                    );
            }

            if (play == null) {
                play =
                    video.opt(
                        "download_addr"
                    );
            }

            addUrlList(
                videos,
                play,
                "原视频",
                "mp4"
            );
        }

        JSONArray audios =
            new JSONArray();

        if (music != null) {
            Object play =
                music.opt(
                    "play_url"
                );

            if (play == null) {
                play =
                    music.opt(
                        "playUrl"
                    );
            }

            addUrlList(
                audios,
                play,
                "原声音频",
                "mp3"
            );
        }

        JSONObject result =
            new JSONObject();

        result.put(
            "success",
            true
        );

        result.put(
            "platform",
            "Douyin / 本地直解析"
        );

        result.put(
            "title",
            item.optString(
                "desc",
                "抖音视频"
            )
        );

        result.put(
            "author",
            author != null
                ? author.optString(
                    "nickname",
                    ""
                )
                : ""
        );

        result.put(
            "source_url",
            sourceUrl
        );

        result.put(
            "resolved_url",
            resolvedUrl
        );

        result.put(
            "videos",
            videos
        );

        result.put(
            "audios",
            audios
        );

        return result;
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

        final int generation = parseGeneration;

        if (
            host.contains("douyin.com")
            || host.contains("iesdouyin.com")
        ) {
            parseDouyinHttpFirst(
                url,
                generation
            );
        } else {
            sendLocalStatus(
                "小红书：正在手机本地打开公开分享页…"
            );

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
        }

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
                                String h=getHost(currentParseUrl);
                                boolean isDouyin=
                                    h!=null
                                    && (
                                        h.contains("douyin.com")
                                        || h.contains("iesdouyin.com")
                                    );

                                deliverLocalError(
                                    isDouyin
                                        ? "抖音本地直解析和网页兜底都没有取得公开视频/音频地址。"
                                          + "请重新复制一条最新分享链接后再试。"
                                        : "小红书本地已打开公开分享页，但页面没有暴露可直接访问的视频/音频资源。"
                                          + "请确保使用小红书“分享→复制链接”得到的完整最新分享链接。"
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



    private void scrapeXhsStructured(
        final int generation
    ) {
        if (
            generation != parseGeneration
            || localResultDelivered
        ) {
            return;
        }

        String javascript =
            "(function(){"
            + "try{"
            + " const s=window.__INITIAL_STATE__;"
            + " if(!s)return JSON.stringify({ok:false,reason:'no_state'});"
            + " const m=s.note&&s.note.noteDetailMap;"
            + " if(!m)return JSON.stringify({ok:false,reason:'no_note_map'});"
            + " const keys=Object.keys(m);"
            + " if(!keys.length)return JSON.stringify({ok:false,reason:'empty_note_map'});"
            + " const wrap=m[keys[0]]||{};"
            + " const n=wrap.note||wrap;"
            + " if(!n)return JSON.stringify({ok:false,reason:'no_note'});"
            + " if(n.type&&n.type!=='video')return JSON.stringify({ok:false,reason:'not_video',type:n.type,title:n.title||n.desc||''});"
            + " const media=n.video&&n.video.media;"
            + " const stream=media&&media.stream;"
            + " const h264=stream&&stream.h264;"
            + " if(!Array.isArray(h264)||!h264.length)return JSON.stringify({ok:false,reason:'no_h264'});"
            + " let best=h264[0];"
            + " for(const x of h264){"
            + "   if(!x)continue;"
            + "   const a=Number(x.size||0),b=Number(best&&best.size||0);"
            + "   if(a>b)best=x;"
            + " }"
            + " const u=(best&&best.masterUrl)||'';"
            + " if(!/^https?:\\/\\//i.test(u))return JSON.stringify({ok:false,reason:'no_master_url'});"
            + " return JSON.stringify({"
            + "   ok:true,"
            + "   title:n.title||n.desc||'小红书视频',"
            + "   author:(n.user&&n.user.nickname)||'',"
            + "   url:u,"
            + "   width:Number(best.width||0),"
            + "   height:Number(best.height||0),"
            + "   size:Number(best.size||0),"
            + "   duration:Number(best.duration||0),"
            + "   page:location.href"
            + " });"
            + "}catch(e){return JSON.stringify({ok:false,reason:'exception',message:String(e)});}"
            + "})()";

        parserWebView.evaluateJavascript(
            javascript,
            encodedValue -> {
                if (
                    generation != parseGeneration
                    || localResultDelivered
                ) {
                    return;
                }

                try {
                    String jsonText =
                        decodeEvaluateJavascriptString(
                            encodedValue
                        );

                    if (
                        jsonText == null
                        || jsonText.isEmpty()
                    ) {
                        return;
                    }

                    JSONObject data =
                        new JSONObject(
                            jsonText
                        );

                    if (!data.optBoolean("ok")) {
                        String reason =
                            data.optString(
                                "reason",
                                ""
                            );

                        if (
                            "not_video".equals(reason)
                        ) {
                            deliverLocalError(
                                "这条小红书笔记不是视频作品，当前类型："
                                + data.optString(
                                    "type",
                                    "未知"
                                )
                            );

                            localResultDelivered = true;
                        }

                        return;
                    }

                    String mediaUrl =
                        data.optString(
                            "url",
                            ""
                        );

                    if (
                        mediaUrl.isEmpty()
                    ) {
                        return;
                    }

                    validateVideoUrlAsync(
                        mediaUrl,
                        data.optString(
                            "page",
                            currentParseUrl
                        ),
                        ok -> {
                            if (
                                generation
                                    != parseGeneration
                                || localResultDelivered
                            ) {
                                return;
                            }

                            if (!ok) {
                                sendLocalStatus(
                                    "已找到小红书 H.264 视频地址，"
                                    + "但媒体校验失败，正在继续检查页面…"
                                );

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
                                    "XiaoHongShu / 本地 H.264"
                                );

                                result.put(
                                    "title",
                                    data.optString(
                                        "title",
                                        "小红书视频"
                                    )
                                );

                                result.put(
                                    "author",
                                    data.optString(
                                        "author",
                                        ""
                                    )
                                );

                                result.put(
                                    "source_url",
                                    currentParseUrl
                                );

                                result.put(
                                    "resolved_url",
                                    data.optString(
                                        "page",
                                        currentParseUrl
                                    )
                                );

                                JSONArray videos =
                                    new JSONArray();

                                JSONObject item =
                                    new JSONObject();

                                item.put(
                                    "quality",
                                    (
                                        data.optInt(
                                            "height",
                                            0
                                        ) > 0
                                    )
                                        ? data.optInt(
                                            "height",
                                            0
                                        )
                                            + "p H.264"
                                        : "H.264"
                                );

                                item.put(
                                    "url",
                                    mediaUrl
                                );

                                item.put(
                                    "ext",
                                    "mp4"
                                );

                                item.put(
                                    "width",
                                    data.optInt(
                                        "width",
                                        0
                                    )
                                );

                                item.put(
                                    "height",
                                    data.optInt(
                                        "height",
                                        0
                                    )
                                );

                                videos.put(item);

                                result.put(
                                    "videos",
                                    videos
                                );

                                result.put(
                                    "audios",
                                    new JSONArray()
                                );

                                localResultDelivered = true;
                                deliverLocalResult(
                                    result
                                );

                            } catch (Exception e) {
                                deliverLocalError(
                                    "小红书视频结果构建失败："
                                    + e.getMessage()
                                );
                            }
                        }
                    );

                } catch (Exception ignored) {
                }
            }
        );
    }


    private interface BoolCallback {
        void onResult(boolean value);
    }


    private void validateVideoUrlAsync(
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
                        5000
                    );

                    c.setReadTimeout(
                        5000
                    );

                    c.setRequestProperty(
                        "User-Agent",
                        parserWebView
                            .getSettings()
                            .getUserAgentString()
                    );

                    if (
                        referer != null
                        && !referer.isEmpty()
                    ) {
                        c.setRequestProperty(
                            "Referer",
                            referer
                        );
                    }

                    c.setRequestProperty(
                        "Range",
                        "bytes=0-1023"
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
                        new byte[32];

                    int n =
                        in != null
                            ? in.read(head)
                            : -1;

                    if (in != null) {
                        in.close();
                    }

                    boolean typeLooksVideo =
                        type != null
                        && (
                            type.toLowerCase(
                                Locale.US
                            ).startsWith(
                                "video/"
                            )
                            || type.toLowerCase(
                                Locale.US
                            ).contains(
                                "octet-stream"
                            )
                        );

                    boolean hasFtyp = false;

                    if (n >= 8) {
                        String prefix =
                            new String(
                                head,
                                0,
                                Math.min(
                                    n,
                                    16
                                ),
                                StandardCharsets.ISO_8859_1
                            );

                        hasFtyp =
                            prefix.contains(
                                "ftyp"
                            );
                    }

                    valid =
                        (
                            status == 200
                            || status == 206
                        )
                        && (
                            typeLooksVideo
                            || hasFtyp
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
                        "mp4"
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
        String lower =
            filename == null
                ? ""
                : filename.toLowerCase(Locale.US);

        if (
            lower.endsWith(".mp4")
            || lower.endsWith(".webm")
            || lower.endsWith(".mov")
        ) {
            validateVideoUrlAsync(
                url,
                referer,
                ok -> {
                    if (!ok) {
                        Toast.makeText(
                            MainActivity.this,
                            "下载地址不是有效视频，已阻止保存，避免生成 0 秒白屏文件。",
                            Toast.LENGTH_LONG
                        ).show();

                        return;
                    }

                    enqueueDownload(
                        url,
                        filename,
                        referer
                    );
                }
            );

            return;
        }

        enqueueDownload(
            url,
            filename,
            referer
        );
    }


    private void enqueueDownload(
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
