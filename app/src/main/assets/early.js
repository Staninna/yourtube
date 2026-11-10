// YourTube early script. Runs at document start in every YouTube frame, before page scripts.
// It touches no DOM elements (there are none yet): the app CSS goes in as an adopted stylesheet, and
// the player-ads and sign-in layers wrap JSON.parse, fetch and XHR. Background playback needs nothing here: the
// WebView reports a visible window while it may play (TubeWebView). Picture-in-picture needs the
// window's outer size pinned and a stylesheet that fills the window with the video. Native replaces
// the config literal at the bottom with the current settings (see PageConfig.kt); running the script
// again swaps the config in place, and main.js reads it from here.
(function (config) {
  'use strict'
  var KEY = '__yourtubeEarly'
  if (window[KEY]) { window[KEY](config); return }

  var current = config
  var sheet = new CSSStyleSheet()
  // On only in picture-in-picture: native turns it on and off (WebViewHost.fillWithVideo).
  var fill = new CSSStyleSheet()
  var filling = false
  function apply(c) {
    current = c
    try {
      sheet.replaceSync(c.css || '')
      fill.replaceSync(filling ? c.fillCss || '' : '')
    } catch (e) {}
  }
  apply(config)
  document.adoptedStyleSheets = document.adoptedStyleSheets.concat([sheet, fill])
  Object.defineProperty(window, KEY, { value: apply })
  Object.defineProperty(window, '__yourtubeConfig', { get: function () { return current } })
  Object.defineProperty(window, '__yourtubeFill', {
    value: function (on) {
      filling = !!on
      apply(current)
    },
  })

  // --- the window's outer size never drops to picture-in-picture size ---
  // YouTube compares outerWidth * outerHeight with the largest area it has seen, takes a big drop for
  // picture-in-picture, and then pauses a video signed-out viewers may not play in the background
  // (stage 0). A window under half the largest area seen reports that largest size instead; a turned
  // screen, the keyboard or a split screen keep their real size.
  var outerWidth = Object.getOwnPropertyDescriptor(window, 'outerWidth')
  var outerHeight = Object.getOwnPropertyDescriptor(window, 'outerHeight')
  if (outerWidth && outerWidth.get && outerWidth.configurable && outerHeight && outerHeight.get && outerHeight.configurable) {
    var largest = { width: 0, height: 0 }
    var outer = function () {
      var width = outerWidth.get.call(window)
      var height = outerHeight.get.call(window)
      var area = width * height
      var most = largest.width * largest.height
      if (area >= most) largest = { width: width, height: height }
      return area < most / 2 ? largest : { width: width, height: height }
    }
    Object.defineProperty(window, 'outerWidth', { configurable: true, enumerable: true, get: function () { return outer().width } })
    Object.defineProperty(window, 'outerHeight', { configurable: true, enumerable: true, get: function () { return outer().height } })
  }

  // --- player ads: ad data leaves player responses before the player reads them ---
  // The rules (config.prune, null while the layer is off) are PlayerAdPrune in PageConfig.kt: keys to
  // delete at container paths, a flag that marks a Shorts entry as an ad, and the URLs worth parsing.

  // The value at a dotted path, or undefined unless every step of the path resolved.
  function at(value, path) {
    if (!path) return value
    var parts = path.split('.')
    for (var i = 0; i < parts.length; i++) {
      if (!value || typeof value !== 'object') return undefined
      value = value[parts[i]]
    }
    return value
  }

  // Prunes one object in place and says whether anything went.
  function pruneObject(value, rules) {
    var changed = false
    rules.containers.forEach(function (path) {
      var target = at(value, path)
      if (!target || typeof target !== 'object') return
      rules.keys.forEach(function (key) {
        if (Object.prototype.hasOwnProperty.call(target, key)) {
          delete target[key]
          changed = true
        }
      })
    })
    if (Array.isArray(value.entries)) {
      var kept = value.entries.filter(function (entry) { return at(entry, rules.reelAdFlag) !== true })
      if (kept.length !== value.entries.length) {
        value.entries = kept
        changed = true
      }
    }
    return changed
  }

  // Prunes a parsed answer in place: an object, or each element of an array (get_watch's shape).
  function prune(value) {
    var rules = current.prune
    if (!rules || !value || typeof value !== 'object') return false
    try {
      if (!Array.isArray(value)) return pruneObject(value, rules)
      var changed = false
      value.forEach(function (v) { if (v && typeof v === 'object' && pruneObject(v, rules)) changed = true })
      return changed
    } catch (e) { return false }
  }

  // --- sign-in-only actions: buttons that only ask a signed-out viewer to sign in leave page data ---
  // YouTube marks every such button in the data it renders pages from, whatever the layout or language:
  // tapping it leads to a signInEndpoint (straight away or through a "Sign in to like" dialog). An
  // element of the render tree goes when it leads to sign-in and to nothing else: no link, share, menu
  // or other action of its own. Actions that only work signed in (config.signIn.neutral: like,
  // subscribe and the like) and a sign-in dialog's own plumbing (Cancel, "Open app", logging) count as
  // nothing else. An action YouTube adds later counts as something else, so the button stays: unknown
  // means shown. Dialogs are prompts and are left whole; sheets are menus and are walked like the page.
  // The rules are SignInStrip in PageConfig.kt.

  var COMMAND = /(Endpoint|Command|Action)$/

  // Whether value's subtree leads to sign-in (state.signIn) and to anything else (state.other).
  function scanActions(value, state, neutral) {
    if (!value || typeof value !== 'object' || state.other) return state
    if (Array.isArray(value)) {
      for (var i = 0; i < value.length; i++) scanActions(value[i], state, neutral)
      return state
    }
    for (var key in value) {
      if (!Object.prototype.hasOwnProperty.call(value, key)) continue
      if (key === 'signInEndpoint') { state.signIn = true; continue }
      if (COMMAND.test(key)) {
        // A command that wraps others is judged by what it wraps; a leaf by its own name.
        var inner = scanActions(value[key], { signIn: false, other: false, command: false }, neutral)
        if (inner.signIn) state.signIn = true
        if (inner.other || (!inner.command && !inner.signIn && neutral.indexOf(key) < 0)) state.other = true
        state.command = true
        continue
      }
      scanActions(value[key], state, neutral)
    }
    return state
  }

  function signInOnly(value, neutral) {
    if (!value || typeof value !== 'object') return false
    var state = scanActions(value, { signIn: false, other: false, command: false }, neutral)
    return state.signIn && !state.other
  }

  // Removes sign-in-only elements from a render tree in place and says whether anything went.
  function stripTree(value, rules) {
    if (!value || typeof value !== 'object') return false
    var changed = false
    if (Array.isArray(value)) {
      for (var i = value.length - 1; i >= 0; i--) {
        if (signInOnly(value[i], rules.neutral)) { value.splice(i, 1); changed = true }
        else if (stripTree(value[i], rules)) changed = true
      }
      return changed
    }
    for (var key in value) {
      if (!Object.prototype.hasOwnProperty.call(value, key) || rules.prompts.indexOf(key) >= 0) continue
      var child = value[key]
      if (child && typeof child === 'object' && !Array.isArray(child) && !COMMAND.test(key) && signInOnly(child, rules.neutral)) {
        delete value[key]
        changed = true
      } else if (stripTree(child, rules)) changed = true
    }
    return changed
  }

  // Page data is an answer with responseContext. Player responses are left alone: their
  // playabilityStatus may hold a Sign in button, and the player and the app's bot-check banner
  // (main.js) read that object.
  function isPageData(value) {
    return !!value && typeof value === 'object' && !Array.isArray(value) && !!value.responseContext &&
      !value.playabilityStatus && !value.videoDetails
  }

  // Strips page data in place: an answer itself, or its `response` (a watch answer, alone or as an
  // array element: get_watch's shape, which also carries the playerResponse). Other JSON (settings,
  // player state, small strings) is left alone.
  function stripSignIn(value) {
    var rules = current.signIn
    if (!rules || !value || typeof value !== 'object') return false
    try {
      var answers = Array.isArray(value) ? value : [value]
      var changed = false
      answers.forEach(function (answer) {
        if (!answer || typeof answer !== 'object') return
        if (isPageData(answer) && stripTree(answer, rules)) changed = true
        if (isPageData(answer.response) && stripTree(answer.response, rules)) changed = true
      })
      return changed
    } catch (e) { return false }
  }

  // Both layers over one parsed answer; true when either changed it.
  function transform(value) {
    var pruned = prune(value)
    var stripped = stripSignIn(value)
    return pruned || stripped
  }

  var patterns = {}
  function matches(source, href) {
    if (!source) return false
    if (!patterns[source]) patterns[source] = new RegExp(source)
    return patterns[source].test(href)
  }
  function wanted(url) {
    var adRules = current.prune
    var signInRules = current.signIn
    if (!adRules && !signInRules) return false
    try {
      var href = new URL(String(url), location.href).href
      return matches(adRules && adRules.urls, href) || matches(signInRules && signInRules.urls, href)
    } catch (e) { return false }
  }

  var nativeParse = JSON.parse

  // The first player response comes as `var ytInitialPlayerResponse = {...}` in an inline script. With
  // an accessor already on window, that declaration calls the setter instead of replacing it.
  var initialPlayerResponse
  try {
    Object.defineProperty(window, 'ytInitialPlayerResponse', {
      configurable: true,
      enumerable: true,
      get: function () { return initialPlayerResponse },
      set: function (value) {
        transform(value)
        initialPlayerResponse = value
      },
    })
  } catch (e) {}

  // Everything YouTube parses itself, including answers read as text.
  JSON.parse = function () {
    var value = nativeParse.apply(this, arguments)
    transform(value)
    return value
  }

  // Fetched answers: response.json() parses natively, so the body is pruned before the page sees it.
  var nativeFetch = window.fetch
  if (nativeFetch) {
    window.fetch = function (input) {
      var pending = nativeFetch.apply(this, arguments)
      var url = input instanceof Request ? input.url : String(input)
      if (!wanted(url)) return pending
      return pending.then(function (response) {
        return response.clone().text().then(function (text) {
          var value = nativeParse(text)
          if (!transform(value)) return response
          var pruned = new Response(JSON.stringify(value), {
            status: response.status, statusText: response.statusText, headers: response.headers,
          })
          Object.defineProperties(pruned, {
            url: { value: response.url }, type: { value: response.type }, redirected: { value: response.redirected },
          })
          return pruned
        }).catch(function () { return response })
      })
    }
  }

  // XHR answers: response and responseText of a wanted request read pruned once it is done.
  var XHR = window.XMLHttpRequest
  if (XHR) {
    var proto = XHR.prototype
    var nativeOpen = proto.open
    var responseGetter = Object.getOwnPropertyDescriptor(proto, 'response').get
    var textGetter = Object.getOwnPropertyDescriptor(proto, 'responseText').get
    var watched = new WeakMap()

    proto.open = function (method, url) {
      if (wanted(url)) watched.set(this, { done: null })
      else watched.delete(this)
      return nativeOpen.apply(this, arguments)
    }

    function pruned(xhr, state) {
      if (state.done) return state.done
      var raw = responseGetter.call(xhr)
      var type = xhr.responseType
      state.done = { response: raw, text: type === '' || type === 'text' ? raw : null }
      try {
        if (type === 'json') transform(raw)
        else if (state.done.text) {
          var value = nativeParse(state.done.text)
          if (transform(value)) {
            var text = JSON.stringify(value)
            state.done = { response: text, text: text }
          }
        }
      } catch (e) {}
      return state.done
    }

    Object.defineProperty(proto, 'response', {
      configurable: true,
      enumerable: true,
      get: function () {
        var state = watched.get(this)
        return state && this.readyState === 4 ? pruned(this, state).response : responseGetter.call(this)
      },
    })
    Object.defineProperty(proto, 'responseText', {
      configurable: true,
      enumerable: true,
      get: function () {
        var state = watched.get(this)
        var done = state && this.readyState === 4 ? pruned(this, state) : null
        return done && done.text !== null ? done.text : textGetter.call(this)
      },
    })
  }
})(/*CONFIG*/{});
