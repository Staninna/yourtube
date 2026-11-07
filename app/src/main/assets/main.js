// YourTube main script. Runs in the main frame once the DOM is ready. It reports raw facts to the
// app through window.TubeBridge and never decides what a page is; that happens in Kotlin.
// Messages (JSON): {type:'page'}, {type:'playback'}, {type:'resume'}; see BridgeMessage.kt.
// No timers: navigation comes from the history hook, popstate and state-navigateend, playback from
// media events captured on the document.
(function () {
  'use strict'
  if (window !== window.top || window.__yourtubeMain) return
  Object.defineProperty(window, '__yourtubeMain', { value: true })
  var bridge = window.TubeBridge
  if (!bridge) return
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start, { once: true })
  else start()

  function start() {
    function post(message) { try { bridge.postMessage(JSON.stringify(message)) } catch (e) {} }

    // --- replies from the app: the reply proxy (message events) or evaluateJavascript (fallback) ---
    function receive(data) {
      var message
      try { message = JSON.parse(data) } catch (e) { return }
      if (message && message.type === 'resume') applyResume(message)
    }
    if (bridge.addEventListener) bridge.addEventListener('message', function (e) { receive(e.data) })
    Object.defineProperty(window, '__yourtubeReceive', { value: receive })

    // --- facts ---
    function player() { return document.getElementById('movie_player') }

    function urlVideoId() {
      try {
        var u = new URL(location.href)
        var parts = u.pathname.split('/')
        if (parts[1] === 'watch') return u.searchParams.get('v') || ''
        if (parts[1] === 'shorts' || parts[1] === 'live') return parts[2] || ''
      } catch (e) {}
      return ''
    }

    function hasTimeParam() {
      try { return new URL(location.href).searchParams.has('t') } catch (e) { return false }
    }

    function playerFacts() {
      try {
        var response = player().getPlayerResponse()
        var d = response && response.videoDetails
        if (!d) return null
        return { videoId: d.videoId || null, channelId: d.channelId || null, title: d.title || null, author: d.author || null }
      } catch (e) { return null }
    }

    function report(reason) {
      var canonical = document.querySelector('link[rel="canonical"]')
      post({
        type: 'page', reason: reason, url: location.href,
        canonical: canonical ? canonical.href : null, title: document.title || '', player: playerFacts(),
      })
    }

    // --- navigation ---
    function onNavigate() { report('navigate') }
    ;['pushState', 'replaceState'].forEach(function (name) {
      var original = history[name]
      history[name] = function () {
        var result = original.apply(this, arguments)
        onNavigate()
        return result
      }
    })
    window.addEventListener('popstate', onNavigate)
    window.addEventListener('state-navigateend', onNavigate)

    // --- playback ---
    var KINDS = { play: 'play', pause: 'pause', ended: 'ended', timeupdate: 'time', loadedmetadata: 'metadata' }
    var lastTimeReport = 0

    function finite(x) { return isFinite(x) && x > 0 ? x : 0 }
    function isAd() { var p = player(); return !!(p && p.classList.contains('ad-showing')) }

    // The URL's video, but only once the player shows it too, so events from the previous video
    // during an SPA transition are not attributed to the new one.
    function currentVideoId() {
      var id = urlVideoId()
      if (!id) return ''
      try {
        var data = player().getVideoData()
        if (data && data.video_id && data.video_id !== id) return ''
      } catch (e) {}
      return id
    }

    function onMedia(e) {
      var video = e.target
      if (!(video instanceof HTMLVideoElement) || isAd()) return
      var id = currentVideoId()
      if (!id) return
      if (e.type === 'timeupdate') {
        var now = Date.now()
        if (now - lastTimeReport < 1000) return
        lastTimeReport = now
      }
      if (e.type === 'loadedmetadata') {
        // Ask before reporting anything, so no progress event can overwrite the stored position first.
        if (!hasTimeParam()) {
          resumeTarget = { videoId: id, video: video }
          post({ type: 'resume', videoId: id })
        }
        report('metadata')
      }
      post({ type: 'playback', kind: KINDS[e.type], videoId: id, position: finite(video.currentTime), duration: finite(video.duration) })
    }
    Object.keys(KINDS).forEach(function (type) { document.addEventListener(type, onMedia, true) })

    // The video element whose loadedmetadata asked for a resume position, and for which video.
    var resumeTarget = null
    var MIN_RESUME = 5
    function applyResume(message) {
      var target = resumeTarget
      if (!target || target.videoId !== message.videoId || currentVideoId() !== message.videoId) return
      resumeTarget = null
      // Only when the video is still near its start, so a late reply never yanks the viewer back.
      if (typeof message.position === 'number' && target.video.currentTime < MIN_RESUME) {
        target.video.currentTime = message.position
      }
    }

    report('load')
  }
})()
