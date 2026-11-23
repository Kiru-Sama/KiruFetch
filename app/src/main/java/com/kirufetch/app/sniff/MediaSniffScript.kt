package com.kirufetch.app.sniff

object MediaSniffScript {
    const val SCRIPT = """
(function(){
  if (window.__kirufetchSniffInstalled) return;
  window.__kirufetchSniffInstalled = true;

  function send(urls){
    if (!urls || !urls.length) return;
    var bridge = window.KiruFetchBridge;
    if (!bridge || typeof bridge.onVideoUrls !== 'function') return;
    bridge.onVideoUrls(JSON.stringify(Array.from(new Set(urls))));
  }

  function collect(){
    var out = [];
    document.querySelectorAll('video, source').forEach(function(el){
      ['src','data-src','data-url','srcset'].forEach(function(attr){
        var v = el.getAttribute && el.getAttribute(attr);
        if (v) out.push(v);
      });
      if (el.currentSrc) out.push(el.currentSrc);
    });
    try {
      performance.getEntriesByType('resource').forEach(function(e){
        var u = e.name || '';
        if (/\.m3u8(\?|$)/i.test(u) || /\.mp4(\?|$)/i.test(u) || /\.webm(\?|$)/i.test(u)) out.push(u);
      });
    } catch(e) {}
    for (var k in window) {
      try {
        var v = window[k];
        if (v && typeof v === 'object' && v.mediaDefinitions) {
          var md = v.mediaDefinitions;
          if (typeof md === 'string') { try { md = JSON.parse(md); } catch(e) { md = []; } }
          if (Array.isArray(md)) md.forEach(function(it){
            if (it && typeof it.videoUrl === 'string') out.push(it.videoUrl);
          });
        }
      } catch(e) {}
    }
    return out.filter(function(u){ return /^https?:/i.test(u); });
  }

  function abs(u){
    try { return new URL(u, location.href).href; } catch(e) { return ''; }
  }

  function sendPoster(){
    var bridge = window.KiruFetchBridge;
    if (!bridge || typeof bridge.onPagePoster !== 'function') return;
    var url = '';
    try {
      var v = document.querySelector('video[poster]');
      if (v && v.poster) url = v.poster;
      if (!url) {
        var m = document.querySelector('meta[property="og:image"],meta[name="twitter:image"],link[rel="image_src"]');
        if (m) url = m.content || m.href || '';
      }
    } catch(e) {}
    if (url) bridge.onPagePoster(abs(url));
  }

  setInterval(function(){ sendPoster(); send(collect()); }, 1500);
  document.addEventListener('DOMContentLoaded', function(){ sendPoster(); send(collect()); });
})();
"""
}
