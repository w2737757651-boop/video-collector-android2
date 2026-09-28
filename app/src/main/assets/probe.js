(function () {
  if (window.__WANG_PARSER_PROBE_V9__) return;
  window.__WANG_PARSER_PROBE_V9__ = true;

  const DIRECT_VIDEO_KEYS = new Set([
    "play_addr","playaddr","download_addr","downloadaddr",
    "masterurl","master_url","videourl","video_url",
    "playurl","play_url","url_default","urldefault"
  ]);

  const DIRECT_AUDIO_KEYS = new Set([
    "play_url","playurl","audio_url","audiourl",
    "music_url","musicurl"
  ]);

  const LIST_KEYS = new Set([
    "url_list","urllist","backupurls","backup_urls","urls"
  ]);

  function safeString(v) {
    try { return String(v == null ? "" : v); } catch (_) { return ""; }
  }

  function normalize(u) {
    return safeString(u)
      .replace(/\\u002F/gi, "/")
      .replace(/\\u0026/gi, "&")
      .replace(/\\u003D/gi, "=")
      .replace(/\\\//g, "/")
      .replace(/&amp;/g, "&")
      .trim();
  }

  function emit(url, path, kind, source) {
    url = normalize(url);
    if (!/^https?:\/\//i.test(url)) return;
    try {
      AndroidProbe.onCandidate(
        url,
        safeString(path).slice(0, 600),
        safeString(kind).slice(0, 32),
        safeString(source || location.href).slice(0, 2000)
      );
    } catch (_) {}
  }

  function emitValue(v, path, kind, source, depth) {
    if (depth > 5 || v == null) return;

    if (typeof v === "string") {
      emit(v, path, kind, source);
      return;
    }

    if (Array.isArray(v)) {
      for (let i = 0; i < Math.min(v.length, 80); i++) {
        emitValue(v[i], path + "[" + i + "]", kind, source, depth + 1);
      }
      return;
    }

    if (typeof v === "object") {
      for (const k of ["url","main_url","mainUrl","src","uri"]) {
        if (typeof v[k] === "string") emit(v[k], path + "." + k, kind, source);
      }

      for (const k of ["url_list","urlList","backupUrls","backup_urls","urls"]) {
        if (v[k] != null) emitValue(v[k], path + "." + k, kind, source, depth + 1);
      }
    }
  }

  function scan(root, rootPath, source) {
    const seen = new WeakSet();
    let count = 0;

    function rec(v, path, depth) {
      if (v == null || depth > 14 || count++ > 20000) return;

      if (typeof v === "string") {
        const lowPath = path.toLowerCase();
        if (
          /^https?:\/\//i.test(v) &&
          (
            lowPath.includes("video") ||
            lowPath.includes("play") ||
            lowPath.includes("stream") ||
            lowPath.includes("download") ||
            lowPath.includes("h264") ||
            lowPath.includes("h265") ||
            lowPath.includes("music") ||
            lowPath.includes("audio")
          )
        ) {
          emit(
            v,
            path,
            (lowPath.includes("music") || lowPath.includes("audio")) ? "audio" : "video",
            source
          );
        }
        return;
      }

      if (typeof v !== "object") return;
      if (seen.has(v)) return;
      seen.add(v);

      if (Array.isArray(v)) {
        for (let i = 0; i < Math.min(v.length, 300); i++) {
          rec(v[i], path + "[" + i + "]", depth + 1);
        }
        return;
      }

      for (const [key, val] of Object.entries(v)) {
        const lower = key.toLowerCase();
        const next = path ? path + "." + key : key;
        const ctx = next.toLowerCase();

        if (DIRECT_VIDEO_KEYS.has(lower)) {
          emitValue(val, next, "video", source, 0);
        } else if (DIRECT_AUDIO_KEYS.has(lower) && (ctx.includes("music") || ctx.includes("audio"))) {
          emitValue(val, next, "audio", source, 0);
        } else if (
          LIST_KEYS.has(lower) &&
          (
            ctx.includes("video") ||
            ctx.includes("play") ||
            ctx.includes("stream") ||
            ctx.includes("download") ||
            ctx.includes("h264") ||
            ctx.includes("h265")
          )
        ) {
          emitValue(val, next, "video", source, 0);
        }

        rec(val, next, depth + 1);
      }
    }

    try { rec(root, rootPath || "root", 0); } catch (_) {}
  }

  function parseAndScan(text, source) {
    if (!text || typeof text !== "string") return;
    if (text.length > 3000000) return;

    try {
      const obj = JSON.parse(text);
      scan(obj, "network_json", source);
      try { AndroidProbe.onEvent("json", source, "parsed"); } catch (_) {}
      return;
    } catch (_) {}

    if (
      text.includes("play_addr") ||
      text.includes("playAddr") ||
      text.includes("download_addr") ||
      text.includes("masterUrl") ||
      text.includes("backupUrls") ||
      text.includes("url_list")
    ) {
      try {
        AndroidProbe.onRaw(
          source || location.href,
          text.slice(0, 1800000)
        );
      } catch (_) {}
    }
  }

  // Hook fetch before site scripts execute.
  try {
    const originalFetch = window.fetch;
    if (originalFetch) {
      window.fetch = async function (...args) {
        const response = await originalFetch.apply(this, args);
        try {
          const clone = response.clone();
          const source = response.url || safeString(args[0]);
          clone.text().then(t => parseAndScan(t, source)).catch(() => {});
        } catch (_) {}
        return response;
      };
    }
  } catch (_) {}

  // Hook XHR before site scripts execute.
  try {
    const oldOpen = XMLHttpRequest.prototype.open;
    const oldSend = XMLHttpRequest.prototype.send;

    XMLHttpRequest.prototype.open = function (method, url) {
      this.__wang_url = safeString(url);
      return oldOpen.apply(this, arguments);
    };

    XMLHttpRequest.prototype.send = function () {
      try {
        this.addEventListener("loadend", () => {
          try {
            let text = "";
            if (!this.responseType || this.responseType === "text") {
              text = safeString(this.responseText);
            } else if (this.responseType === "json") {
              text = JSON.stringify(this.response);
            }
            parseAndScan(text, this.responseURL || this.__wang_url || "");
          } catch (_) {}
        });
      } catch (_) {}
      return oldSend.apply(this, arguments);
    };
  } catch (_) {}

  function sweep() {
    try { scan(window.__INITIAL_STATE__, "__INITIAL_STATE__", location.href); } catch (_) {}
    try { scan(window._ROUTER_DATA, "_ROUTER_DATA", location.href); } catch (_) {}
    try { scan(window.__UNIVERSAL_DATA_FOR_REHYDRATION__, "__UNIVERSAL_DATA_FOR_REHYDRATION__", location.href); } catch (_) {}
    try { scan(window.__INIT_PROPS__, "__INIT_PROPS__", location.href); } catch (_) {}

    // Scan interesting inline scripts only, not the entire DOM.
    try {
      document.querySelectorAll("script").forEach(s => {
        const t = s.textContent || "";
        if (
          t.length > 0 &&
          t.length < 1800000 &&
          (
            t.includes("play_addr") ||
            t.includes("playAddr") ||
            t.includes("download_addr") ||
            t.includes("masterUrl") ||
            t.includes("backupUrls") ||
            t.includes("url_list")
          )
        ) {
          AndroidProbe.onRaw(location.href, t);
        }
      });
    } catch (_) {}

    // Resource URLs are only candidates. Android still validates actual video bytes.
    try {
      performance.getEntriesByType("resource").forEach(e => {
        const u = safeString(e.name);
        if (
          /douyinvod|xhscdn|sns-video|\/video\/|\/play\/|\.mp4(?:\?|$)/i.test(u)
        ) {
          emit(u, "performance.resource", "video", location.href);
        }
      });
    } catch (_) {}

    // Explicitly do not rely on playback.
    try {
      document.querySelectorAll("video,audio").forEach(m => {
        try {
          m.autoplay = false;
          if (!m.paused) m.pause();
        } catch (_) {}
      });
    } catch (_) {}

    try {
      AndroidProbe.onTitle(document.title || "", location.href);
    } catch (_) {}
  }

  try {
    document.addEventListener("DOMContentLoaded", sweep, { once: false });
    window.addEventListener("load", sweep, { once: false });
  } catch (_) {}

  setTimeout(sweep, 0);
  setInterval(sweep, 1000);

  try {
    AndroidProbe.onEvent("probe", location.href, "document-start-installed");
  } catch (_) {}
})();