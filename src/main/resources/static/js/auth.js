/**
 * auth.js — page-level logic for login, registration, and session checks.
 */

async function handleRegister(event) {
    event.preventDefault();
    dom.hideMessage("#message");

    const username = document.querySelector("#username").value.trim();
    const email = document.querySelector("#email").value.trim();
    const password = document.querySelector("#password").value;

    const result = await api.register(username, email, password);

    if (result.ok) {
        dom.showMessage("#message", "Registration successful! Redirecting to login…", "success");
        setTimeout(() => { window.location.href = "/login.html"; }, 1500);
    } else {
        const msg = (result.data && result.data.error) ? result.data.error : "Registration failed";
        dom.showMessage("#message", msg, "error");
    }
}

async function handleLogin(event) {
    event.preventDefault();
    dom.hideMessage("#message");

    const username = document.querySelector("#username").value.trim();
    const password = document.querySelector("#password").value;

    const result = await api.login(username, password);

    if (result.ok) {
        window.location.href = "/dashboard.html";
    } else {
        dom.showMessage("#message", "Invalid username or password", "error");
    }
}

async function handleLogout() {
    await api.logout();
    window.location.href = "/login.html";
}

/**
 * Redirects to login if the user is not authenticated.
 * Called on pages that require a session.
 */
async function requireAuth() {
    const result = await api.me();
    if (!result.ok) {
        window.location.href = "/login.html";
        return null;
    }
    return result.data;
}
