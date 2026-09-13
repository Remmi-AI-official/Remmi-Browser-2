// Remmi Engine Extension - Intelligent Form & Password Autofill Bridge
(function () {
  if (window.__remmi_autofill_bridge_installed) return;
  window.__remmi_autofill_bridge_installed = true;

  let lastFocusTime = 0;
  let lastCapturedPass = "";
  let lastCapturedUser = "";

  function isRelevantInput(el) {
    if (!el || el.tagName !== "INPUT") return false;
    const type = (el.type || "").toLowerCase();
    if (type === "password") return true;
    if (type === "email" || type === "text") {
      const name = (el.name || "").toLowerCase();
      const id = (el.id || "").toLowerCase();
      const ac = (el.getAttribute("autocomplete") || "").toLowerCase();
      if (ac.includes("username") || ac.includes("email")) return true;
      if (name.includes("user") || name.includes("login") || name.includes("email") || name.includes("account")) return true;
      if (id.includes("user") || id.includes("login") || id.includes("email") || id.includes("account")) return true;
    }
    return false;
  }

  function findCredentialsInContainer(container) {
    const root = container || document;
    const passInputs = Array.from(root.querySelectorAll('input[type="password"]'));
    if (passInputs.length === 0) return null;

    let password = "";
    for (const p of passInputs) {
      if (p.value && p.value.length > 0) {
        password = p.value;
        break;
      }
    }
    if (!password) return null;

    // Search for username/email input preceding or near the password field
    let username = "";
    const allInputs = Array.from(root.querySelectorAll('input:not([type="password"]):not([type="hidden"]):not([type="submit"]):not([type="button"]):not([type="checkbox"]):not([type="radio"])'));
    
    // Check for explicit username/email fields
    for (const input of allInputs) {
      const type = (input.type || "").toLowerCase();
      const ac = (input.getAttribute("autocomplete") || "").toLowerCase();
      const name = (input.name || "").toLowerCase();
      const id = (input.id || "").toLowerCase();
      if (type === "email" || ac.includes("username") || ac.includes("email") || name.includes("user") || name.includes("login") || name.includes("email") || id.includes("user") || id.includes("email")) {
        if (input.value && input.value.trim().length > 0) {
          username = input.value.trim();
          break;
        }
      }
    }

    // Fallback to first non-empty text input if not found
    if (!username) {
      for (const input of allInputs) {
        if (input.value && input.value.trim().length > 0) {
          username = input.value.trim();
          break;
        }
      }
    }

    return { username, password };
  }

    function isSignUpContext(el) {
    if (!el) return false;
    try {
      const ac = (el.getAttribute("autocomplete") || "").toLowerCase();
      if (ac === "new-password" || ac.includes("new-password") || ac === "one-time-code") return true;

      const name = (el.name || "").toLowerCase();
      const id = (el.id || "").toLowerCase();
      const signupPatterns = [
        "signup", "sign-up", "register", "registration", "newpass", "new-pass",
        "new_pass", "confirmpass", "confirm-pass", "confirm_pass", "passwd2",
        "pass2", "repeatpass", "repeat-pass", "join", "create_account"
      ];
      for (let i = 0; i < signupPatterns.length; i++) {
        const pat = signupPatterns[i];
        if (name.includes(pat) || id.includes(pat)) return true;
      }

      const form = el.closest("form") || (el.parentElement ? el.parentElement.closest("div, form, [role='form']") : null);
      if (form) {
        const passInputs = form.querySelectorAll('input[type="password"]');
        if (passInputs.length >= 2) return true;

        const formId = (form.id || "").toLowerCase();
        const formClass = (form.className || "").toString().toLowerCase();
        const formAction = (form.action || "").toLowerCase();
        const formPatterns = ["signup", "sign-up", "register", "registration", "create-account", "create_account", "join"];
        for (let j = 0; j < formPatterns.length; j++) {
          const pat = formPatterns[j];
          if (formId.includes(pat) || formClass.includes(pat) || formAction.includes(pat)) return true;
        }

        const buttons = form.querySelectorAll('button, input[type="submit"]');
        for (let k = 0; k < buttons.length; k++) {
          const btn = buttons[k];
          const btnText = (btn.innerText || btn.value || "").toLowerCase();
          if (btnText.includes("sign up") || btnText.includes("signup") || btnText.includes("register") || btnText.includes("create account") || btnText.includes("get started")) {
            return true;
          }
        }
      }

      const path = (window.location.pathname || "").toLowerCase();
      if (path.includes("/signup") || path.includes("/sign-up") || path.includes("/register") || path.includes("/registration") || path.includes("/join") || path.includes("/create-account")) {
        return true;
      }
    } catch (_e) {}
    return false;
  }

  function notifyFocus(isPassword) {
    const now = Date.now();
    if (now - lastFocusTime < 800) return;
    lastFocusTime = now;

    try {
      if (typeof browser !== "undefined" && browser.runtime && browser.runtime.sendMessage) {
        browser.runtime.sendMessage({
          type: "AUTH_FIELD_FOCUSED",
          origin: window.location.origin,
          url: window.location.href,
          isPassword: !!isPassword
        }).catch(function () {});
      }
    } catch (_e) {}
  }

  function notifySubmission(username, password) {
    if (!password || password.length === 0) return;
    if (password === lastCapturedPass && username === lastCapturedUser) return;
    lastCapturedPass = password;
    lastCapturedUser = username;

    try {
      if (typeof browser !== "undefined" && browser.runtime && browser.runtime.sendMessage) {
        browser.runtime.sendMessage({
          type: "AUTH_FORM_SUBMITTED",
          origin: window.location.origin,
          url: window.location.href,
          username: username || "",
          password: password
        }).catch(function () {});
      }
    } catch (_e) {}
  }

  // 1. Detect focus on input fields (Only Sign-In, NEVER on Sign-Up)
  document.addEventListener("focusin", function (e) {
    const target = e.target;
    if (isRelevantInput(target)) {
      if (isSignUpContext(target)) {
        return; // Suppress autofill prompt on sign-up / registration forms
      }
      notifyFocus((target.type || "").toLowerCase() === "password");
    }
  }, true);

  // 2. Detect form submit event
  document.addEventListener("submit", function (e) {
    const form = e.target;
    const creds = findCredentialsInContainer(form);
    if (creds && creds.password) {
      notifySubmission(creds.username, creds.password);
    }
  }, true);

  // 3. Detect click on submit/login/signup buttons
  document.addEventListener("click", function (e) {
    const target = e.target;
    if (!target) return;
    const button = target.closest('button, input[type="submit"], input[type="button"], [role="button"]');
    if (!button) return;

    // Small delay to allow value updates in reactive form frameworks
    setTimeout(function () {
      const form = button.closest("form");
      const creds = findCredentialsInContainer(form || document);
      if (creds && creds.password) {
        notifySubmission(creds.username, creds.password);
      }
    }, 50);
  }, true);

  // 4. Detect Enter key press in password fields
  document.addEventListener("keydown", function (e) {
    if (e.key === "Enter") {
      const target = e.target;
      if (target && target.tagName === "INPUT" && (target.type || "").toLowerCase() === "password") {
        setTimeout(function () {
          const form = target.closest("form");
          const creds = findCredentialsInContainer(form || document);
          if (creds && creds.password) {
            notifySubmission(creds.username, creds.password);
          }
        }, 50);
      }
    }
  }, true);

  // 5. Listen for autofill command from native layer
  if (typeof browser !== "undefined" && browser.runtime && browser.runtime.onMessage) {
    browser.runtime.onMessage.addListener(function (message, sender, sendResponse) {
      if (!message) return;
      if (message.type === "AUTOFILL_FILL") {
        const username = message.username || "";
        const password = message.password || "";

        const passInputs = Array.from(document.querySelectorAll('input[type="password"]'));
        if (passInputs.length > 0) {
          const passEl = passInputs[0];
          passEl.value = password;
          passEl.dispatchEvent(new Event("input", { bubbles: true }));
          passEl.dispatchEvent(new Event("change", { bubbles: true }));

          const form = passEl.closest("form") || document;
          const userInputs = Array.from(form.querySelectorAll('input:not([type="password"]):not([type="hidden"]):not([type="submit"]):not([type="button"])'));
          if (userInputs.length > 0 && username) {
            const userEl = userInputs[0];
            userEl.value = username;
            userEl.dispatchEvent(new Event("input", { bubbles: true }));
            userEl.dispatchEvent(new Event("change", { bubbles: true }));
          }
        }
        if (sendResponse) sendResponse({ filled: true });
        return true;
      }
    });
  }
})();
