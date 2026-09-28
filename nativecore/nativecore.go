package nativecore

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	dy "github.com/tamnd/douyin-cli/douyin"
	xhs "github.com/tamnd/xiaohongshu-cli/xiaohongshu"
)

var dataDir string

var httpURLRE = regexp.MustCompile(`https?://[^\s，。；;）),]+`)
var xhsIDRE = regexp.MustCompile(`/(?:explore|discovery/item)/([0-9a-fA-F]+)`)

type mediaItem struct {
	Quality string `json:"quality"`
	URL     string `json:"url"`
	Ext     string `json:"ext"`
	Width   int64  `json:"width,omitempty"`
	Height  int64  `json:"height,omitempty"`
}

type result struct {
	Success     bool        `json:"success"`
	Platform    string      `json:"platform,omitempty"`
	Title       string      `json:"title,omitempty"`
	Author      string      `json:"author,omitempty"`
	Cover       string      `json:"cover,omitempty"`
	Duration    int64       `json:"duration,omitempty"`
	SourceURL   string      `json:"source_url,omitempty"`
	ResolvedURL string      `json:"resolved_url,omitempty"`
	Referer     string      `json:"referer,omitempty"`
	Videos      []mediaItem `json:"videos,omitempty"`
	Audios      []mediaItem `json:"audios,omitempty"`
	Error       string      `json:"error,omitempty"`
	Code        string      `json:"code,omitempty"`
}

func Init(dir string) {
	dataDir = strings.TrimSpace(dir)
	if dataDir == "" {
		return
	}
	_ = os.MkdirAll(dataDir, 0o755)
	_ = os.Setenv("HOME", dataDir)
	_ = os.Setenv("XDG_CONFIG_HOME", filepath.Join(dataDir, "config"))
	_ = os.Setenv("XDG_CACHE_HOME", filepath.Join(dataDir, "cache"))
}

func Version() string {
	return "7.0"
}

func Parse(text string) string {
	u, err := firstURL(text)
	if err != nil {
		return encode(result{Success: false, Error: err.Error(), Code: "NO_URL"})
	}

	host := strings.ToLower(hostname(u))
	switch {
	case strings.Contains(host, "douyin.com") || strings.Contains(host, "iesdouyin.com"):
		return encode(parseDouyin(u))
	case strings.Contains(host, "xiaohongshu.com") || strings.Contains(host, "xhslink."):
		return encode(parseXHS(u))
	default:
		return encode(result{
			Success: false,
			Error:   "该平台不使用本地 Go 引擎。",
			Code:    "UNSUPPORTED_LOCAL",
		})
	}
}

func firstURL(text string) (string, error) {
	m := httpURLRE.FindString(strings.TrimSpace(text))
	if m == "" {
		return "", errors.New("没有识别到有效 http/https 链接")
	}
	return strings.TrimRight(m, "，。,.；;）)"), nil
}

func hostname(raw string) string {
	u, err := url.Parse(raw)
	if err != nil {
		return ""
	}
	return u.Hostname()
}

func encode(v result) string {
	b, err := json.Marshal(v)
	if err != nil {
		return `{"success":false,"error":"JSON 编码失败","code":"JSON_ERROR"}`
	}
	return string(b)
}

func resolveFinalURL(rawURL, userAgent string) (string, error) {
	client := &http.Client{
		Timeout: 12 * time.Second,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= 8 {
				return errors.New("重定向次数过多")
			}
			req.Header.Set("User-Agent", userAgent)
			req.Header.Set("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
			return nil
		},
	}

	req, err := http.NewRequest(http.MethodGet, rawURL, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("User-Agent", userAgent)
	req.Header.Set("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
	req.Header.Set("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")

	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()

	if resp.Request == nil || resp.Request.URL == nil {
		return rawURL, nil
	}
	return resp.Request.URL.String(), nil
}

func douyinCommonParams() url.Values {
	v := url.Values{}
	v.Set("device_platform", "webapp")
	v.Set("aid", "6383")
	v.Set("channel", "channel_pc_web")
	v.Set("pc_client_type", "1")
	v.Set("version_code", "290100")
	v.Set("version_name", "29.1.0")
	v.Set("cookie_enabled", "true")
	v.Set("screen_width", "1920")
	v.Set("screen_height", "1080")
	v.Set("browser_language", "zh-CN")
	v.Set("browser_platform", "MacIntel")
	v.Set("browser_name", "Chrome")
	v.Set("browser_version", "124.0.0.0")
	v.Set("browser_online", "true")
	v.Set("engine_name", "Blink")
	v.Set("engine_version", "124.0.0.0")
	v.Set("os_name", "Mac OS")
	v.Set("os_version", "10.15.7")
	v.Set("platform", "PC")
	v.Set("downlink", "10")
	v.Set("effective_type", "4g")
	v.Set("round_trip_time", "50")
	return v
}

func parseDouyin(sourceURL string) result {
	finalURL, err := resolveFinalURL(sourceURL, dy.DefaultUserAgent)
	if err != nil {
		return result{Success: false, Platform: "Douyin", SourceURL: sourceURL, Error: "抖音短链展开失败：" + err.Error(), Code: "DOUYIN_RESOLVE"}
	}

	id, err := dy.ParseVideoID(finalURL)
	if err != nil {
		return result{Success: false, Platform: "Douyin", SourceURL: sourceURL, ResolvedURL: finalURL, Error: "没有识别到抖音作品 ID：" + err.Error(), Code: "DOUYIN_ID"}
	}

	cfg := dy.DefaultConfig()
	cfg.Timeout = 12 * time.Second
	cfg.Retries = 1
	cfg.Rate = 250 * time.Millisecond
	client := dy.NewClient(cfg)

	ctx, cancel := context.WithTimeout(context.Background(), 18*time.Second)
	defer cancel()

	q := douyinCommonParams()
	q.Set("aweme_id", id)

	body, err := client.GetAPI(ctx, "/aweme/v1/web/aweme/detail/", q.Encode())
	if err != nil {
		code := "DOUYIN_API"
		msg := err.Error()
		if errors.Is(err, dy.ErrWalled) || strings.Contains(strings.ToLower(msg), "anti-bot") || strings.Contains(strings.ToLower(msg), "residential") {
			code = "DOUYIN_WALLED"
			msg = "抖音当前网络触发了平台风控。该开源引擎需要正常住宅网络/可用会话；这不是付费限制。"
		}
		return result{
			Success:     false,
			Platform:    "Douyin / Go Local",
			SourceURL:   sourceURL,
			ResolvedURL: finalURL,
			Error:       msg,
			Code:        code,
		}
	}

	var root map[string]any
	if err := json.Unmarshal(body, &root); err != nil {
		return result{Success: false, Platform: "Douyin / Go Local", SourceURL: sourceURL, ResolvedURL: finalURL, Error: "抖音数据解码失败：" + err.Error(), Code: "DOUYIN_JSON"}
	}

	detail := obj(root["aweme_detail"])
	if detail == nil {
		detail = findMapWithKey(root, "aweme_id")
	}
	if detail == nil {
		return result{Success: false, Platform: "Douyin / Go Local", SourceURL: sourceURL, ResolvedURL: finalURL, Error: "抖音返回数据里没有作品详情。", Code: "DOUYIN_EMPTY"}
	}

	videoObj := obj(detail["video"])
	musicObj := obj(detail["music"])
	authorObj := obj(detail["author"])

	play := firstNestedURL(videoObj, "play_addr", "url_list")
	if play == "" {
		play = firstNestedURL(videoObj, "playAddr", "urlList")
	}
	if play == "" {
		play = firstNestedURL(videoObj, "download_addr", "url_list")
	}

	audio := firstNestedURL(musicObj, "play_url", "url_list")
	if audio == "" {
		audio = firstNestedURL(musicObj, "playUrl", "urlList")
	}

	cover := firstNestedURL(videoObj, "cover", "url_list")
	if cover == "" {
		cover = firstNestedURL(videoObj, "origin_cover", "url_list")
	}

	width := int64(number(videoObj["width"]))
	height := int64(number(videoObj["height"]))
	duration := int64(number(videoObj["duration"]))
	if duration > 1000 {
		duration = duration / 1000
	}

	videos := []mediaItem{}
	if play != "" {
		videos = append(videos, mediaItem{
			Quality: quality(height),
			URL:     play,
			Ext:     "mp4",
			Width:   width,
			Height:  height,
		})
	}

	audios := []mediaItem{}
	if audio != "" {
		audios = append(audios, mediaItem{
			Quality: "原声音频",
			URL:     audio,
			Ext:     audioExt(audio),
		})
	}

	if len(videos) == 0 && len(audios) == 0 {
		return result{Success: false, Platform: "Douyin / Go Local", SourceURL: sourceURL, ResolvedURL: finalURL, Error: "作品详情已取得，但没有返回可下载的视频/音频地址。", Code: "DOUYIN_NO_MEDIA"}
	}

	return result{
		Success:     true,
		Platform:    "Douyin / Go Local",
		Title:       str(detail["desc"]),
		Author:      str(authorObj["nickname"]),
		Cover:       cover,
		Duration:    duration,
		SourceURL:   sourceURL,
		ResolvedURL: finalURL,
		Referer:     finalURL,
		Videos:      videos,
		Audios:      audios,
	}
}

func parseXHS(sourceURL string) result {
	finalURL, err := resolveFinalURL(sourceURL, xhs.DefaultUserAgent)
	if err != nil {
		return result{Success: false, Platform: "XiaoHongShu", SourceURL: sourceURL, Error: "小红书短链展开失败：" + err.Error(), Code: "XHS_RESOLVE"}
	}

	u, err := url.Parse(finalURL)
	if err != nil {
		return result{Success: false, Platform: "XiaoHongShu", SourceURL: sourceURL, Error: "小红书链接格式错误：" + err.Error(), Code: "XHS_URL"}
	}

	m := xhsIDRE.FindStringSubmatch(u.Path)
	if len(m) < 2 {
		return result{Success: false, Platform: "XiaoHongShu", SourceURL: sourceURL, ResolvedURL: finalURL, Error: "没有从分享链接中识别到小红书 Note ID。", Code: "XHS_ID"}
	}
	noteID := m[1]
	token := u.Query().Get("xsec_token")
	if token == "" {
		token = u.Query().Get("xsecToken")
	}
	if token == "" {
		return result{Success: false, Platform: "XiaoHongShu", SourceURL: sourceURL, ResolvedURL: finalURL, Error: "分享链接没有携带可用的 xsec_token。请在小红书里重新点“分享→复制链接”。", Code: "XHS_TOKEN"}
	}

	cfg := xhs.DefaultConfig()
	cfg.Timeout = 15 * time.Second
	cfg.Retries = 1
	cfg.Rate = 500 * time.Millisecond
	if dataDir != "" {
		cfg.CacheDir = filepath.Join(dataDir, "xhs-cache")
		_ = os.MkdirAll(cfg.CacheDir, 0o755)
	}

	client := xhs.NewClient(cfg)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	note, err := client.Note(ctx, noteID, token)
	if err != nil {
		kind := xhs.Kind(err)
		code := "XHS_API"
		msg := err.Error()
		switch kind {
		case xhs.ErrAntibot:
			code = "XHS_ANTIBOT"
			msg = "小红书当前网络触发了平台风控，请稍后再试或切换手机网络/Wi‑Fi。"
		case xhs.ErrRate:
			code = "XHS_RATE"
			msg = "小红书当前网络请求过于频繁，请等待一会儿再试。"
		case xhs.ErrAccess:
			code = "XHS_ACCESS"
		case xhs.ErrNetwork:
			code = "XHS_NETWORK"
		}
		return result{
			Success:     false,
			Platform:    "XiaoHongShu / Go Local",
			SourceURL:   sourceURL,
			ResolvedURL: finalURL,
			Error:       msg,
			Code:        code,
		}
	}

	if note.Type != "video" || note.Video == nil {
		return result{
			Success:     false,
			Platform:    "XiaoHongShu / Go Local",
			SourceURL:   sourceURL,
			ResolvedURL: finalURL,
			Title:       note.Title,
			Author:      note.Nickname,
			Error:       "这条小红书笔记不是视频作品。",
			Code:        "XHS_NOT_VIDEO",
		}
	}

	videos := make([]mediaItem, 0, len(note.Video.Masters))
	seen := map[string]bool{}
	for _, master := range note.Video.Masters {
		master = strings.TrimSpace(master)
		if master == "" || seen[master] {
			continue
		}
		seen[master] = true
		videos = append(videos, mediaItem{
			Quality: quality(int64(note.Video.Height)),
			URL:     master,
			Ext:     "mp4",
			Width:   int64(note.Video.Width),
			Height:  int64(note.Video.Height),
		})
	}

	if len(videos) == 0 {
		return result{Success: false, Platform: "XiaoHongShu / Go Local", SourceURL: sourceURL, ResolvedURL: finalURL, Error: "小红书作品详情已取得，但没有返回可播放视频流。", Code: "XHS_NO_MEDIA"}
	}

	return result{
		Success:     true,
		Platform:    "XiaoHongShu / Go Local",
		Title:       firstNonEmpty(note.Title, note.Desc, "小红书视频"),
		Author:      note.Nickname,
		Cover:       note.Video.Cover,
		Duration:    int64(note.Video.Duration),
		SourceURL:   sourceURL,
		ResolvedURL: finalURL,
		Referer:     finalURL,
		Videos:      videos,
		Audios:      []mediaItem{},
	}
}

func obj(v any) map[string]any {
	if m, ok := v.(map[string]any); ok {
		return m
	}
	return map[string]any{}
}

func arr(v any) []any {
	if a, ok := v.([]any); ok {
		return a
	}
	return nil
}

func str(v any) string {
	if s, ok := v.(string); ok {
		return s
	}
	return ""
}

func number(v any) float64 {
	switch n := v.(type) {
	case float64:
		return n
	case int:
		return float64(n)
	case int64:
		return float64(n)
	case json.Number:
		f, _ := n.Float64()
		return f
	default:
		return 0
	}
}

func firstNestedURL(root map[string]any, keys ...string) string {
	var cur any = root
	for _, key := range keys {
		m := obj(cur)
		cur = m[key]
	}
	switch v := cur.(type) {
	case string:
		if strings.HasPrefix(v, "http") {
			return v
		}
	case []any:
		for _, x := range v {
			if s := str(x); strings.HasPrefix(s, "http") {
				return s
			}
		}
	}
	return ""
}

func findMapWithKey(v any, key string) map[string]any {
	switch x := v.(type) {
	case map[string]any:
		if _, ok := x[key]; ok {
			return x
		}
		for _, child := range x {
			if m := findMapWithKey(child, key); m != nil {
				return m
			}
		}
	case []any:
		for _, child := range x {
			if m := findMapWithKey(child, key); m != nil {
				return m
			}
		}
	}
	return nil
}

func quality(height int64) string {
	if height > 0 {
		return fmt.Sprintf("%dp", height)
	}
	return "视频"
}

func audioExt(raw string) string {
	l := strings.ToLower(raw)
	switch {
	case strings.Contains(l, ".m4a"):
		return "m4a"
	case strings.Contains(l, ".aac"):
		return "aac"
	default:
		return "mp3"
	}
}

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if strings.TrimSpace(v) != "" {
			return v
		}
	}
	return ""
}
