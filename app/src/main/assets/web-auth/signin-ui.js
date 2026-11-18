(function () {
  "use strict";
  function label(node) {
    return ((node.textContent || "") + " " + (node.getAttribute("aria-label") || ""))
      .replace(/\s+/g, " ").trim();
  }
  function phoneSignIn(node) {
    var text = label(node);
    return /(?:sign[ -]?in|log[ -]?in)\s+(?:with|using)\s+(?:(?:an?|your)\s+)?(?:iPhone|passkey)/i.test(text) ||
      /(?:使用|透過|通过)\s*iPhone\s*(?:登入|登录)/i.test(text);
  }
  function hide(node) {
    if (!node) return;
    node.hidden = true;
    node.style.setProperty("display", "none", "important");
    node.setAttribute("aria-hidden", "true");
    node.setAttribute("data-amatv-phone-signin", "hidden");
  }
  function install(doc) {
    var host;
    try { host = doc.location.hostname.toLowerCase(); } catch (_) { return; }
    if (host !== "idmsa.apple.com" && host !== "idmsa.apple.com.cn") return;
    if (doc.__amatvPasswordSignInUi) return;
    doc.__amatvPasswordSignInUi = true;
    var queued = false;
    function update() {
      queued = false;
      var controls = doc.querySelectorAll('button, [role="button"], a');
      var password = null;
      var moveFocus = false;
      for (var i = 0; i < controls.length; i++) {
        var button = controls[i];
        if (!password && /password|密碼|密码/i.test(label(button)) && !button.disabled &&
            (button.tagName === "BUTTON" || button.getAttribute("role") === "button")) password = button;
        if (!phoneSignIn(button)) continue;
        if (button === doc.activeElement || button.contains(doc.activeElement)) moveFocus = true;
        hide(button);
        var descriptions = (button.getAttribute("aria-describedby") || "").split(/\s+/);
        for (var j = 0; j < descriptions.length; j++) {
          var description = descriptions[j] && doc.getElementById(descriptions[j]);
          if (description && /iPhone|iOS|passkey/i.test(description.textContent || "")) hide(description);
        }
        // Remove the iOS requirements caption with its option, never a form
        // wrapper containing the password button or account/verification fields.
        var parent = button.parentElement;
        if (parent && parent.querySelectorAll('button, [role="button"], a, input, textarea').length === 1 &&
            /\biOS\b/i.test(parent.textContent || "")) {
          hide(parent);
        } else {
          var caption = button.nextElementSibling;
          if (caption && /\biOS\b/i.test(caption.textContent || "") &&
              !caption.querySelector('button, [role="button"], a, input, textarea')) hide(caption);
        }
      }
      if (moveFocus && password) password.focus();
      // WebView evaluates the hook in the top document; inspect only accessible
      // frames.
      var frames = doc.querySelectorAll("iframe");
      for (var k = 0; k < frames.length; k++) {
        var frame = frames[k];
        try { if (frame.contentDocument) install(frame.contentDocument); } catch (_) { }
        if (!frame.__amatvPasswordSignInUi) {
          frame.__amatvPasswordSignInUi = true;
          frame.addEventListener("load", function () {
            try { if (this.contentDocument) install(this.contentDocument); } catch (_) { }
          });
        }
      }
    }
    function schedule() {
      if (queued) return;
      queued = true;
      doc.defaultView.requestAnimationFrame(function () {
        try { update(); } catch (_) { queued = false; }
      });
    }
    new doc.defaultView.MutationObserver(schedule).observe(doc, { childList: true, subtree: true, characterData: true });
    update();
  }
  try { install(document); } catch (_) { }
})();
