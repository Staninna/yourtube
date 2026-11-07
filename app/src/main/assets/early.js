// YourTube early script. Runs at document start in every YouTube frame, before page scripts.
// It touches no DOM elements (there are none yet): the app CSS goes in as an adopted stylesheet and
// the background guard only redefines document properties. Native replaces the config literal at
// the bottom with the current settings; running the script again updates the CSS in place.
(function (config) {
  'use strict'
  var KEY = '__yourtubeEarly'
  if (window[KEY]) { window[KEY](config); return }

  var sheet = new CSSStyleSheet()
  function apply(c) { try { sheet.replaceSync(c.css || '') } catch (e) {} }
  apply(config)
  document.adoptedStyleSheets = document.adoptedStyleSheets.concat([sheet])
  Object.defineProperty(window, KEY, { value: apply })

  // Background guard: YouTube pauses or degrades playback when it thinks the page is hidden.
  try {
    Object.defineProperty(document, 'hidden', { get: function () { return false }, configurable: true })
    Object.defineProperty(document, 'visibilityState', { get: function () { return 'visible' }, configurable: true })
    Object.defineProperty(document, 'webkitHidden', { get: function () { return false }, configurable: true })
  } catch (e) {}
  var swallow = function (e) { e.stopImmediatePropagation() }
  document.addEventListener('visibilitychange', swallow, true)
  document.addEventListener('webkitvisibilitychange', swallow, true)
  window.addEventListener('blur', swallow, true)
})(/*CONFIG*/{});
