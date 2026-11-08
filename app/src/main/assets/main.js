// YourTube main script. Runs in the main frame once the DOM is ready. It reports raw facts to the
// app through window.TubeBridge and never decides what a page is; that happens in Kotlin.
// Messages out (JSON): page, playback, videoBox, resume, mute, segments, skipped, adLeak; consent.js
// sends consent. Messages in: resume, segments, undoSkip, and the media session's play, pause and
// seek. See BridgeMessage.kt.
// Settings come from early.js (window.__yourtubeConfig).
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
      else if (message.type === 'play' || message.type === 'pause' || message.type === 'seek') control(message)
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

    // Whether the player may play, so the app can tell a bot check. On the first load the player may not
    // be up yet, but the page's own player response is.
    function playability(reason) {
      var response = playerResponse() || (reason === 'load' ? window.ytInitialPlayerResponse : null)
      var p = response && response.playabilityStatus
      if (!p) return null
      var d = response.videoDetails
      return { status: p.status || null, reason: p.reason || null, videoId: (d && d.videoId) || null }
    }

    function report(reason) {
      var canonical = document.querySelector('link[rel="canonical"]')
      post({
        type: 'page', reason: reason, url: location.href,
        canonical: canonical ? canonical.href : null, title: document.title || '', player: playerFacts(),
        playability: playability(reason),
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
    // A seek or a speed change reports at once, outside timeupdate's one per second: the media session
    // sets its position and speed from them.
    var KINDS = {
      play: 'play', pause: 'pause', ended: 'ended', timeupdate: 'time', seeked: 'time', ratechange: 'time',
      loadedmetadata: 'metadata',
    }
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
      if (isAd()) {
        onAd(video)
        adPlayPause(e.type, video)
        return
      }
      afterAd(video)
      var id = currentVideoId()
      if (!id) return
      // YouTube sets the speed while a video loads: a report then would store position 0 before the
      // resume request goes out.
      if ((e.type === 'ratechange' || e.type === 'seeked') && video.readyState < HTMLMediaElement.HAVE_METADATA) return
      if (e.type === 'timeupdate') {
        applySegments(video, id)
        var now = Date.now()
        if (now - lastTimeReport < 1000) return
        lastTimeReport = now
      }
      if (e.type === 'loadedmetadata') {
        // Ask before reporting anything, so no progress event can overwrite the stored position first.
        // A video with t= keeps its start, but still takes the viewer's mute from the answer.
        resumeTarget = { videoId: id, video: video, keepStart: hasTimeParam() }
        post({ type: 'resume', videoId: id })
        askSegments(video, id)
        report('metadata')
      }
      main = { videoId: id, rate: video.playbackRate, position: finite(video.currentTime), duration: finite(video.duration) }
      post({
        type: 'playback', kind: KINDS[e.type], videoId: id, paused: video.paused, rate: main.rate,
        position: main.position, duration: main.duration,
      })
      reportBox(video)
    }

    // The video's picture size and on-screen box, for picture-in-picture's shape and enter animation.
    // Riding on the playback reports keeps it current while it plays, at most once a second.
    var lastBox = ''
    function reportBox(video) {
      var r = video.getBoundingClientRect()
      if (!video.videoWidth || !video.videoHeight || r.width <= 0 || r.height <= 0) return
      var box = {
        type: 'videoBox', videoWidth: video.videoWidth, videoHeight: video.videoHeight,
        left: r.left, top: r.top, right: r.right, bottom: r.bottom, viewportWidth: window.innerWidth,
      }
      var text = JSON.stringify(box)
      if (text === lastBox) return
      lastBox = text
      post(box)
    }
    Object.keys(KINDS).forEach(function (type) { document.addEventListener(type, onMedia, true) })

    // The main video's last report. A play or pause while an ad shows still goes to the app, or the media
    // session would take the pause back; it carries these numbers, so the store never records the ad's.
    // Before the main video's first report (a pre-roll) there is nothing safe to send.
    var main = null
    function adPlayPause(type, video) {
      if ((type !== 'play' && type !== 'pause') || !main || main.videoId !== currentVideoId()) return
      post({
        type: 'playback', kind: type, videoId: main.videoId, paused: video.paused, rate: main.rate,
        position: main.position, duration: main.duration,
      })
    }

    // The media session's commands (notification, lock screen, headset, the now-playing bar). They act on
    // whatever the player shows, an ad included, except a seek, which only means the video it names.
    function control(message) {
      var p = player()
      var video = p && p.querySelector('video')
      if (!video) return
      if (message.type === 'play') video.play().catch(function () {})
      else if (message.type === 'pause') video.pause()
      else if (message.videoId === currentVideoId() && !isAd()) video.currentTime = message.position
    }

    // The video element whose loadedmetadata asked for a resume position, and for which video.
    var resumeTarget = null
    var MIN_RESUME = 5
    function applyResume(message) {
      var target = resumeTarget
      if (!target || target.videoId !== message.videoId || currentVideoId() !== message.videoId) return
      resumeTarget = null
      // Only when the video is still near its start, so a late reply never yanks the viewer back.
      if (!target.keepStart && typeof message.position === 'number' && target.video.currentTime < MIN_RESUME) {
        target.video.currentTime = message.position
      }
      // A fresh identity lets YouTube start muted ("Tap to unmute"); only the viewer decides that here.
      setMuted(target.video, message.muted, true)
      // After a new identity the video plays on as it did, through the player so its controls agree.
      var carried = message.carried
      if (!carried) return
      try {
        if (carried.rate > 0 && carried.rate !== 1) player().setPlaybackRate(carried.rate)
      } catch (e) {}
      if (carried.paused) target.video.pause()
    }

    // --- the viewer's mute ---
    // A mute change the viewer made goes to the app, which applies it to every video after. The viewer's
    // is one that follows a touch on the player (where its mute controls are) or a key within a second.
    // A touch elsewhere does not count: YouTube unmutes by itself when a tapped link opens a video. The
    // changes this script makes are tagged.
    var lastInput = 0
    document.addEventListener('pointerdown', function (e) {
      // The player's controls sit beside #movie_player, inside the player's container.
      var area = document.getElementById('player-container-id') || player()
      if (area && area.contains(e.target)) lastInput = Date.now()
    }, true)
    document.addEventListener('keydown', function () { lastInput = Date.now() }, true)
    var ownMute = null // the muted state this script set, until its volumechange arrives
    var lastMuted = null
    // Through the player when its controls should agree (the "Tap to unmute" chip goes with it).
    function setMuted(video, muted, viaPlayer) {
      if (video.muted === muted) return
      ownMute = muted
      if (viaPlayer) {
        try {
          if (muted) player().mute()
          else player().unMute()
        } catch (e) {}
      }
      if (video.muted !== muted) video.muted = muted
    }
    document.addEventListener('volumechange', function (e) {
      var video = e.target
      // The main player only: a thumbnail's inline preview is a video of its own.
      var p = player()
      if (!(video instanceof HTMLVideoElement) || !p || !p.contains(video) || video.muted === lastMuted) return
      lastMuted = video.muted
      if (ownMute === video.muted) {
        ownMute = null
        return
      }
      if (isAd() || Date.now() - lastInput > 1000) return
      post({ type: 'mute', muted: video.muted })
    }, true)

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
      setMuted(video, true)
      if (video.playbackRate !== AD_RATE) video.playbackRate = AD_RATE
      var p = player()
      var skip = p && p.querySelector('.ytp-ad-skip-button-modern, .ytp-ad-skip-button, .ytp-skip-ad-button')
      if (skip) skip.click()
    }
    function afterAd(video) {
      if (!ad) return
      if (config().adSafetyNet) {
        setMuted(video, ad.wasMuted)
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
        setMuted(video, true)
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
      if (sponsor.muted && sponsor.video) setMuted(sponsor.video, sponsor.muted.wasMuted)
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
