// Tube content script, injected into m.youtube.com.
// Handles the in-page concerns: ad/shorts hiding, resume position, background-playback guard,
// and reporting page info + watch progress to the native app (window.TubeBridge).
(function () {
  'use strict'
  if (window.__tubeInjected) return
  window.__tubeInjected = true

  var REWIND = 5, MIN_RESUME = 5, END_MARGIN = 15
  var RESUME_KEY = 'tube:resume', STORE_LIMIT = 300
  var hideShorts = false

  function bridge(method, arg) {
    try { if (window.TubeBridge && window.TubeBridge[method]) window.TubeBridge[method](arg) } catch (e) {}
  }

  // --- settings from native ---
  window.__tubeApplySettings = function (s) {
    try { s = JSON.parse(s) } catch (e) { s = {} }
    hideShorts = !!s.hideShorts
    applyCss()
  }

  // --- ad / shorts hiding ---
  var styleEl
  function applyCss() {
    if (!styleEl) { styleEl = document.createElement('style'); (document.head || document.documentElement).appendChild(styleEl) }
    var rules = [
      'ytm-promoted-video-renderer,ytm-companion-ad-renderer,ytm-promoted-sparkles-web-renderer,',
      '.ad-container,ytm-player-ad,ytm-ad-slot-renderer,ad-slot-renderer,ytm-rich-grid-slot-renderer:has(ytm-ad-slot-renderer){display:none !important}',
      // The app provides its own bottom navigation, so hide YouTube's pivot bar.
      'ytm-pivot-bar-renderer{display:none !important}',
      'ytm-app,#app{padding-bottom:0 !important}',
    ]
    if (hideShorts) {
      rules.push('ytm-reel-shelf-renderer,ytm-rich-section-renderer:has([href*="/shorts"]),ytm-pivot-bar-item-renderer:has([aria-label="Shorts" i]),a[href^="/shorts"]{display:none !important}')
    }
    styleEl.textContent = rules.join('')
  }

  // --- resume ---
  function readStore() { try { return JSON.parse(localStorage.getItem(RESUME_KEY) || '{}') } catch (e) { return {} } }
  function writeStore(s) { try { localStorage.setItem(RESUME_KEY, JSON.stringify(s)) } catch (e) {} }
  function videoId() {
    try { var u = new URL(location.href); return u.searchParams.get('v') || (u.pathname.indexOf('/shorts/') === 0 ? u.pathname.split('/')[2] : '') || '' } catch (e) { return '' }
  }
  function hasTimeParam() { try { return new URL(location.href).searchParams.has('t') } catch (e) { return false } }
  function remember(id, t, d) {
    if (!id || !isFinite(t) || t < 0) return
    var s = readStore(); s[id] = { t: t, d: d || 0, at: Date.now() }
    var ids = Object.keys(s)
    if (ids.length > STORE_LIMIT) { ids.sort(function (a, b) { return (s[b].at || 0) - (s[a].at || 0) }); var p = {}; ids.slice(0, STORE_LIMIT).forEach(function (k) { p[k] = s[k] }); s = p }
    writeStore(s)
  }
  function resumeSeconds(id) {
    var e = readStore()[id]; if (!e) return undefined
    var t = Number(e.t) || 0, d = Number(e.d) || 0
    if (d <= 0 || t < MIN_RESUME || t > d - END_MARGIN) return undefined
    return Math.max(t - REWIND, 0)
  }

  var attempted = {}, boundVideo = null, lastWatchReport = 0

  function bind(video) {
    if (!video || video === boundVideo) return
    boundVideo = video
    video.addEventListener('timeupdate', function () {
      var id = videoId()
      if (id && video.currentTime > 0) {
        remember(id, video.currentTime, video.duration)
        var now = Date.now()
        if (now - lastWatchReport > 5000) { lastWatchReport = now; reportWatch(id, video) }
      }
    })
    video.addEventListener('play', function () { bridge('setPlaying', true); reportWatch(videoId(), video) })
    video.addEventListener('pause', function () { bridge('setPlaying', false) })
    video.addEventListener('ended', function () { bridge('setPlaying', false) })
    video.addEventListener('loadedmetadata', function () { maybeResume(video) })
    maybeResume(video)
  }
  function maybeResume(video) {
    var id = videoId(); if (!id || attempted[id] || hasTimeParam()) return
    if (video.currentTime > MIN_RESUME) { attempted[id] = true; return }
    var secs = resumeSeconds(id); attempted[id] = true
    if (secs !== undefined) { try { video.currentTime = secs } catch (e) {} }
  }

  // --- metadata helpers ---
  function playerDetails() {
    try { return (window.ytInitialPlayerResponse || {}).videoDetails || {} } catch (e) { return {} }
  }
  function cleanTitle() { return (document.title || '').replace(/\s*-\s*YouTube\s*$/, '').trim() }
  function channelIdFromPage() {
    var l = document.querySelector('link[rel="alternate"][type="application/rss+xml"]')
    if (l && l.href) { try { return new URL(l.href).searchParams.get('channel_id') || '' } catch (e) {} }
    return ''
  }

  function reportWatch(id, video) {
    if (!id) return
    var d = playerDetails()
    bridge('watch', JSON.stringify({
      videoId: id,
      title: (d.title || cleanTitle()),
      author: d.author || '',
      channelId: d.channelId || '',
      thumbnail: 'https://i.ytimg.com/vi/' + id + '/hqdefault.jpg',
      url: 'https://m.youtube.com/watch?v=' + id,
      position: video ? video.currentTime : 0,
      duration: video ? (video.duration || 0) : 0,
    }))
  }

  function reportPage() {
    var u
    try { u = new URL(location.href) } catch (e) { return }
    var path = u.pathname, info = { url: location.href, type: 'other', id: '', title: cleanTitle(), author: '', channelId: '' }
    var vid = videoId()
    if (vid) {
      var d = playerDetails()
      info.type = 'video'; info.id = vid
      info.title = d.title || cleanTitle()
      info.author = d.author || ''
      info.channelId = d.channelId || ''
      info.thumbnail = 'https://i.ytimg.com/vi/' + vid + '/hqdefault.jpg'
    } else if (path.indexOf('/channel/') === 0 || path.indexOf('/@') === 0 || path.indexOf('/c/') === 0 || path.indexOf('/user/') === 0) {
      info.type = 'channel'
      info.channelId = channelIdFromPage()
      info.id = info.channelId || path
      info.title = cleanTitle()
    } else if (path.indexOf('/playlist') === 0) {
      info.type = 'playlist'; info.id = u.searchParams.get('list') || ''
    }
    bridge('pageInfo', JSON.stringify(info))
  }

  // --- background guard ---
  function installBackgroundGuard() {
    try {
      Object.defineProperty(document, 'hidden', { get: function () { return false }, configurable: true })
      Object.defineProperty(document, 'visibilityState', { get: function () { return 'visible' }, configurable: true })
      Object.defineProperty(document, 'webkitHidden', { get: function () { return false }, configurable: true })
    } catch (e) {}
    var swallow = function (e) { e.stopImmediatePropagation() }
    document.addEventListener('visibilitychange', swallow, true)
    document.addEventListener('webkitvisibilitychange', swallow, true)
    window.addEventListener('blur', swallow, true)
  }

  function scan() { var v = document.querySelector('video'); if (v) bind(v) }

  var lastUrl = ''
  function onNav() {
    if (location.href === lastUrl) return
    lastUrl = location.href
    setTimeout(function () { reportPage(); scan() }, 400)
  }

  applyCss()
  installBackgroundGuard()
  scan()
  reportPage()
  lastUrl = location.href
  new MutationObserver(function () { scan() }).observe(document.documentElement, { childList: true, subtree: true })
  window.addEventListener('yt-navigate-finish', onNav, true)
  setInterval(onNav, 1000)
})();
