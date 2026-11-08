// YourTube consent script. Answers YouTube's cookie choice with "Reject all": the consent.youtube.com
// page, and the consent dialog m.youtube.com shows on a page (both seen live with a Chrome user agent and
// no SOCS cookie). Buttons are found by form fields and class names, never by their words, so any page
// language works. On YouTube pages it tells the app (ConsentRejected); the app counts consent pages by
// their address.
(function () {
  'use strict'
  if (window !== window.top || window.__yourtubeConsent) return
  Object.defineProperty(window, '__yourtubeConsent', { value: true })

  if (location.hostname === 'consent.youtube.com') {
    // Twice at most per tab, so a page that keeps coming back stays on screen for the viewer.
    var KEY = 'yourtube-consent-tries'
    var tries = 0
    try { tries = +sessionStorage.getItem(KEY) || 0 } catch (e) {}
    if (tries >= 2) return
    var reject = function () {
      // "Reject all" posts set_eom=true; the "Accept all" forms carry set_eom=false and set_ytc.
      var forms = document.querySelectorAll('form[action$="/save"]')
      for (var i = 0; i < forms.length; i++) {
        var form = forms[i]
        var button = form.querySelector('button')
        if (!button || !form.querySelector('input[name="set_eom"][value="true"]') || form.querySelector('input[name="set_ytc"]')) continue
        try { sessionStorage.setItem(KEY, String(tries + 1)) } catch (e) {}
        button.click()
        return
      }
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', reject, { once: true })
    else reject()
    return
  }

  // The dialog can render any time after load; a live collection makes each check one length read.
  var dialogs = document.getElementsByTagName('ytm-consent-bump-v2-renderer')
  var answered = new WeakSet()
  function check() {
    if (!dialogs.length) return
    var button = dialogs[0].querySelector('.eom-reject button')
    if (!button || answered.has(button)) return
    answered.add(button)
    button.click()
    try { window.TubeBridge.postMessage(JSON.stringify({ type: 'consent' })) } catch (e) {}
  }
  new MutationObserver(check).observe(document, { childList: true, subtree: true })
  check()
})();
