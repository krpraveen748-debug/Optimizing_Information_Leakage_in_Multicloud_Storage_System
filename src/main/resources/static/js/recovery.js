let emailRecoveryChallenge = null;
let passwordResetToken = null;

const emailUsernameForm = document.getElementById("email-username-form");
if (emailUsernameForm) {
  emailUsernameForm.addEventListener("submit", async event => {
    event.preventDefault();
    const button = emailUsernameForm.querySelector("button[type='submit']");
    button.disabled = true;
    showMessage("email-recovery-message", "Verifying username…");
    try {
      const csrf = await getCsrfToken();
      const request = Object.fromEntries(new FormData(emailUsernameForm));
      const result = await responseData(await sendJson(
        "/api/recovery/email/username", "POST", request, csrf));
      emailRecoveryChallenge = result.challengeToken;
      document.getElementById("email-username-step").hidden = true;
      document.getElementById("email-verification-step").hidden = false;
      showMessage("email-recovery-message", "Username verified. Confirm the registered email and password.");
    } catch (error) {
      showMessage("email-recovery-message", error.message);
    } finally {
      button.disabled = false;
    }
  });
}

const emailVerificationForm = document.getElementById("email-verification-form");
if (emailVerificationForm) {
  emailVerificationForm.addEventListener("submit", async event => {
    event.preventDefault();
    if (!emailRecoveryChallenge) {
      showMessage("email-recovery-message", "Recovery step expired. Start again.");
      return;
    }
    const button = emailVerificationForm.querySelector("button[type='submit']");
    button.disabled = true;
    showMessage("email-recovery-message", "Checking identity…");
    try {
      const csrf = await getCsrfToken();
      const request = Object.fromEntries(new FormData(emailVerificationForm));
      request.challengeToken = emailRecoveryChallenge;
      const result = await responseData(await sendJson(
        "/api/recovery/email/verify", "POST", request, csrf));
      emailRecoveryChallenge = null;
      document.getElementById("email-verification-step").hidden = true;
      const resultElement = document.getElementById("recovered-email");
      resultElement.textContent = `Verified registered email: ${result.email}`;
      resultElement.hidden = false;
      showMessage("email-recovery-message", "Identity verified successfully.", true);
      emailVerificationForm.reset();
    } catch (error) {
      emailRecoveryChallenge = null;
      emailVerificationForm.reset();
      document.getElementById("email-verification-step").hidden = true;
      document.getElementById("email-username-step").hidden = false;
      showMessage("email-recovery-message", `${error.message} Enter your username to start again.`);
    } finally {
      button.disabled = false;
    }
  });
}

const passwordRecoveryForm = document.getElementById("password-recovery-form");
if (passwordRecoveryForm) {
  passwordRecoveryForm.addEventListener("submit", async event => {
    event.preventDefault();
    const button = document.getElementById("password-recovery-submit");
    button.disabled = true;
    showMessage("password-recovery-message", "Requesting a secure reset link…");
    try {
      const csrf = await getCsrfToken();
      const request = Object.fromEntries(new FormData(passwordRecoveryForm));
      const result = await responseData(await sendJson(
        "/api/recovery/password/request", "POST", request, csrf));
      showMessage("password-recovery-message", result.message, true);
      passwordRecoveryForm.reset();
    } catch (error) {
      showMessage("password-recovery-message", error.message);
    } finally {
      button.disabled = false;
    }
  });
}

const resetPasswordForm = document.getElementById("reset-password-form");
if (resetPasswordForm) {
  passwordResetToken = new URLSearchParams(window.location.search).get("token");
  if (window.location.search) {
    window.history.replaceState(null, "", window.location.pathname);
  }
  (async () => {
    if (!passwordResetToken) {
      showMessage("reset-password-message", "Reset link is missing. Request a new link.");
      return;
    }
    try {
      const csrf = await getCsrfToken();
      const result = await responseData(await sendJson(
        "/api/recovery/password/validate",
        "POST",
        { token: passwordResetToken },
        csrf));
      if (!result.valid) {
        passwordResetToken = null;
        throw new Error("Reset link is invalid, expired, or already used. Request a new link.");
      }
      resetPasswordForm.hidden = false;
      showMessage("reset-password-message", "Reset link verified. Choose a new password.", true);
    } catch (error) {
      passwordResetToken = null;
      showMessage("reset-password-message", error.message);
    }
  })();

  resetPasswordForm.addEventListener("submit", async event => {
    event.preventDefault();
    if (!passwordResetToken) {
      showMessage("reset-password-message", "Reset link is invalid, expired, or already used.");
      return;
    }
    const formData = new FormData(resetPasswordForm);
    const newPassword = String(formData.get("newPassword") || "");
    const confirmPassword = String(formData.get("confirmPassword") || "");
    if (newPassword !== confirmPassword) {
      showMessage("reset-password-message", "New password and confirmation do not match.");
      return;
    }
    const button = document.getElementById("reset-password-submit");
    button.disabled = true;
    showMessage("reset-password-message", "Updating password…");
    try {
      const csrf = await getCsrfToken();
      const result = await responseData(await sendJson(
        "/api/recovery/password/reset",
        "POST",
        { token: passwordResetToken, newPassword, confirmPassword },
        csrf));
      passwordResetToken = null;
      resetPasswordForm.reset();
      resetPasswordForm.hidden = true;
      showMessage("reset-password-message", result.message, true);
    } catch (error) {
      showMessage("reset-password-message", error.message);
    } finally {
      button.disabled = false;
    }
  });
}
