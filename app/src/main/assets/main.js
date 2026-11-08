// YourTube main script. Runs in the main frame once the DOM is ready. It reports raw facts to the
// app through window.TubeBridge and never decides what a page is; that happens in Kotlin.
// Messages out (JSON): page, playback, resume, segments, skipped, adLeak. Messages in: resume,
// segments, undoSkip. See BridgeMessage.kt. Settings come from early.js (window.__yourtubeConfig).
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
    function config() { return window.__yourtubeConfig || {} }

    // --- messages from the app: the reply proxy (message events) or evaluateJavascript (pushes) ---
    function receive(data) {
      var message
      try { message = JSON.parse(data) } catch (e) { return }
      if (!message) return
      if (message.type === 'resume') applyResume(message)
      else if (message.type === 'segments') setSegments(message)
      else if (message.type === 'undoSkip') undoSkip(message)
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

    function playerResponse() {
      try { return player().getPlayerResponse() } catch (e) { return null }
    }

    function playerFacts() {
      var response = playerResponse()
      var d = response && response.videoDetails
      if (!d) return null
      return { videoId: d.videoId || null, channelId: d.channelId || null, title: d.title || null, author: d.author || null }
    }

    function report(reason) {
      var canonical = document.querySelector('link[rel="canonical"]')
      post({
        type: 'page', reason: reason, url: location.href,
        canonical: canonical ? canonical.href : null, title: document.title || '', player: playerFacts(),
      })
    }

    // --- Shorts: with "Remove all Shorts" on, a Short opens as its normal watch page ---
    // The same rule as NavigationPolicy.shortAsWatch, for navigation that never leaves the page.
    var SHORT_PATH = /^\/shorts\/([A-Za-z0-9_-]{11})\/?$/
    function shortAsWatch(href) {
      try {
        var u = new URL(href, location.href)
        var m = /(^|\.)youtube\.com$/.test(u.hostname) && SHORT_PATH.exec(u.pathname)
        return m ? '/watch?v=' + m[1] : null
      } catch (e) { return null }
    }
    function leaveShort() {
      var watch = config().removeShorts && shortAsWatch(location.href)
      if (watch) location.replace(watch)
      return !!watch
    }
    document.addEventListener('click', function (e) {
      var link = config().removeShorts && e.target.closest && e.target.closest('a[href]')
      var watch = link && shortAsWatch(link.getAttribute('href'))
      if (!watch) return
      e.preventDefault()
      e.stopImmediatePropagation()
      location.assign(watch)
    }, true)

    // --- navigation ---
    function onNavigate() {
      if (!leaveShort()) report('navigate')
    }
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
    function isAd() {
      var p = player()
      return !!(p && (p.classList.contains('ad-showing') || p.classList.contains('ad-interrupting')))
    }

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
      if (!(video instanceof HTMLVideoElement)) return
      if (isAd()) { onAd(video); return }
      afterAd(video)
      var id = currentVideoId()
      if (!id) return
      if (e.type === 'timeupdate') {
        applySegments(video, id)
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
        askSegments(video, id)
        report('metadata')
      }
      post({
        type: 'playback', kind: KINDS[e.type], videoId: id, paused: video.paused,
        position: finite(video.currentTime), duration: finite(video.duration),
      })
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

    // --- ad safety net: an ad that got past the other layers ---
    // Every ad that shows is counted (adLeak). With the safety net on it is muted and played at 16x,
    // its skip button is pressed once it shows, and the viewer's mute state and speed come back after.
    // Not a seek to its end: the player then holds the ad on screen for its own clock (about 10 s per
    // ad on m.youtube.com, see docs/research/ad-blocking.md), while a fast ad ends as a normal one.
    var AD_RATE = 16 // Chromium's highest playback rate
    var ad = null // { wasMuted, wasRate } while an ad shows
    function onAd(video) {
      if (!ad) {
        ad = { wasMuted: video.muted, wasRate: video.playbackRate }
        post({ type: 'adLeak', url: location.href })
      }
      if (!config().adSafetyNet) return
      video.muted = true
      if (video.playbackRate !== AD_RATE) video.playbackRate = AD_RATE
      var p = player()
      var skip = p && p.querySelector('.ytp-ad-skip-button-modern, .ytp-ad-skip-button, .ytp-skip-ad-button')
      if (skip) skip.click()
    }
    function afterAd(video) {
      if (!ad) return
      if (config().adSafetyNet) {
        video.muted = ad.wasMuted
        video.playbackRate = ad.wasRate
      }
      ad = null
    }

    // --- SponsorBlock: segments come from the app per video, applied as playback moves ---
    // Skip happens only when playback runs into a segment; a seek that lands inside one is the
    // viewer's choice and is left alone. Undo (from the snackbar) seeks back and keeps that segment
    // from being skipped again in this playback. Mute holds only while inside a segment.
    var sponsor = { videoId: '', video: null, segments: [], lastTime: -1, muted: null, ownSeek: false }
    var marks = new CSSStyleSheet()
    document.adoptedStyleSheets = document.adoptedStyleSheets.concat([marks])

    // Every load is a new playback: nothing is "run into" yet and no Undo holds. Only a new video
    // asks the app again; coming back to the same one (A, a channel, A) keeps its segments.
    function askSegments(video, id) {
      sponsor.video = video
      leaveSegments()
      sponsor.lastTime = -1
      sponsor.segments.forEach(function (s) { s.undone = false })
      if (sponsor.videoId === id) return
      sponsor.videoId = id
      sponsor.segments = []
      drawMarks()
      post({ type: 'segments', videoId: id })
    }

    // Native answers a request, and pushes again when SponsorBlock settings change.
    function setSegments(message) {
      if (message.videoId !== sponsor.videoId) return
      var undone = {}
      sponsor.segments.forEach(function (s) { if (s.undone) undone[s.start] = true })
      leaveSegments()
      sponsor.segments = (message.segments || []).map(function (s) {
        return { start: s.start, end: s.end, category: s.category, action: s.action, undone: !!undone[s.start] }
      })
      drawMarks()
    }

    function applySegments(video, id) {
      if (sponsor.videoId !== id || !sponsor.segments.length) return
      var now = video.currentTime
      var last = sponsor.lastTime
      sponsor.lastTime = now
      var mute = find(function (s) { return s.action === 'mute' && now >= s.start && now < s.end })
      if (mute && !sponsor.muted) {
        sponsor.muted = { wasMuted: video.muted }
        video.muted = true
      } else if (!mute) leaveSegments()
      var skip = find(function (s) {
        return s.action === 'skip' && !s.undone && last <= s.start && now >= s.start && now < s.end
      })
      if (!skip) return
      seek(video, skip.end)
      post({ type: 'skipped', videoId: id, category: skip.category, start: skip.start, end: skip.end })
    }

    function undoSkip(message) {
      var video = sponsor.video
      if (message.videoId !== sponsor.videoId || !video) return
      var segment = find(function (s) { return s.start === message.start })
      if (!segment) return
      segment.undone = true
      seek(video, segment.start)
    }

    function seek(video, to) {
      sponsor.ownSeek = true
      sponsor.lastTime = to
      video.currentTime = to
    }
    document.addEventListener('seeking', function (e) {
      if (!(e.target instanceof HTMLVideoElement) || isAd()) return
      // The viewer's seek: nothing counts as "ran into" until playback moves on from where it landed.
      if (!sponsor.ownSeek) sponsor.lastTime = NaN
      sponsor.ownSeek = false
    }, true)

    function leaveSegments() {
      if (sponsor.muted && sponsor.video) sponsor.video.muted = sponsor.muted.wasMuted
      sponsor.muted = null
    }

    function find(test) {
      for (var i = 0; i < sponsor.segments.length; i++) if (test(sponsor.segments[i])) return sponsor.segments[i]
      return null
    }

    // Marks every segment on YouTube's progress line in SponsorBlock's colors, from a stylesheet of
    // our own: YouTube's elements stay untouched, and the marks survive the controls re-rendering.
    function drawMarks() {
      var duration = sponsor.video && sponsor.video.duration
      var colors = config().segmentColors || {}
      if (!sponsor.segments.length || !isFinite(duration) || duration <= 0) {
        marks.replaceSync('')
        return
      }
      function pct(t) { return (Math.min(Math.max(t / duration, 0), 1) * 100).toFixed(3) + '%' }
      var stops = sponsor.segments.map(function (s) {
        var color = colors[s.category] || '#ffffff'
        var a = pct(s.start)
        var b = pct(s.end)
        return 'transparent ' + a + ',' + color + ' ' + a + ',' + color + ' ' + b + ',transparent ' + b
      })
      marks.replaceSync(
        'yt-chaptered-progress-bar-line::after{content:"";position:absolute;inset:0;pointer-events:none;' +
        'background:linear-gradient(to right,' + stops.join(',') + ')}',
      )
    }

    if (!leaveShort()) report('load')
  }
})()
