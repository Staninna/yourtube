// YourTube identity script. Runs at document start in every YouTube frame, before page scripts, and only
// where WebView has document-start scripts (injected later it would come too late to matter). It makes
// navigator.platform agree with the reduced user agent (UserAgent.kt) and gives a new identity the
// player choices the last one had (PlayerPrefsStorage.kt). navigator.webdriver needs nothing: WebView
// already reports false. Native replaces the config literal at the bottom:
// { platform, prefs: { localStorage key: value } }.
(function (config) {
  'use strict'
  if (window.__yourtubeIdentity) return
  Object.defineProperty(window, '__yourtubeIdentity', { value: true })

  // --- patched getters read as native code ---
  // Fingerprinting scripts print getters to spot patches. Patches live on the prototype, where the
  // native getters live, and print the way the native ones do. Method syntax, like native functions,
  // gives them no prototype and no constructor.
  var nativeToString = Function.prototype.toString
  var disguised = new WeakMap()
  var toString = {
    toString() { return disguised.has(this) ? disguised.get(this) : nativeToString.call(this) },
  }.toString
  disguised.set(toString, 'function toString() { [native code] }')
  Object.defineProperty(Function.prototype, 'toString', { value: toString, writable: true, configurable: true })

  function override(proto, name, value) {
    var native = Object.getOwnPropertyDescriptor(proto, name)
    if (!native || !native.get || !native.configurable) return
    var get = Object.getOwnPropertyDescriptor({ get [name]() { return value } }, name).get
    disguised.set(get, 'function get ' + name + '() { [native code] }')
    Object.defineProperty(proto, name, { get: get, set: native.set, enumerable: native.enumerable, configurable: true })
  }

  override(Navigator.prototype, 'platform', config.platform)

  // --- the last identity's player choices, unless this identity has made its own ---
  try {
    var prefs = config.prefs || {}
    Object.keys(prefs).forEach(function (key) {
      if (localStorage.getItem(key) === null) localStorage.setItem(key, prefs[key])
    })
  } catch (e) {}
})(/*CONFIG*/{});
