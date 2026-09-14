/**
 * Remmi Smart Dark Mode Content Script
 * Applies smart CSS inversion to pages that lack native dark mode support,
 * respecting site bypass overrides and global settings.
 */
(function() {
  function applySmartDark() {
    var host = window.location.hostname;
    if (!host) return;

    if (typeof browser !== 'undefined' && browser.storage && browser.storage.local) {
      browser.storage.local.get(["forceDarkEnabled", "disabledSites"], function(res) {
        var enabled = res.forceDarkEnabled !== false;
        var disabled = res.disabledSites || [];
        var isBypassed = disabled.some(function(d) {
          return host === d || host.endsWith('.' + d);
        });

        if (enabled && !isBypassed) {
          try {
            var cs = window.getComputedStyle ? window.getComputedStyle(document.documentElement).colorScheme : '';
            if (cs === 'dark') {
              document.documentElement.classList.remove("remmi-smart-dark");
              return;
            }
          } catch(e) {}

          document.documentElement.classList.add("remmi-smart-dark");
        } else {
          document.documentElement.classList.remove("remmi-smart-dark");
        }
      });
    } else {
      document.documentElement.classList.add("remmi-smart-dark");
    }
  }

  if (document.documentElement) {
    applySmartDark();
  } else {
    document.addEventListener("DOMContentLoaded", applySmartDark, { once: true });
  }

  if (typeof browser !== 'undefined' && browser.runtime && browser.runtime.onMessage) {
    browser.runtime.onMessage.addListener(function(msg) {
      if (msg && msg.action === "toggle_smart_dark") {
        if (msg.enabled) {
          document.documentElement.classList.add("remmi-smart-dark");
        } else {
          document.documentElement.classList.remove("remmi-smart-dark");
        }
      }
    });
  }
})();
