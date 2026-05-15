/**
 * api.js — thin wrapper around fetch() for same-origin JSON requests.
 *
 * All requests include credentials (session cookie) and the CSRF token.
 * The CSRF token is read from the XSRF-TOKEN cookie set by Spring Security.
 */

function getCsrfToken() {
    const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
    return match ? decodeURIComponent(match[1]) : null;
}

/**
 * Sends a JSON request and returns { ok, status, data }.
 * Never throws — callers handle errors via the returned object.
 */
async function apiRequest(method, path, body) {
    const headers = { "Content-Type": "application/json" };
    const token = getCsrfToken();
    if (token) {
        headers["X-XSRF-TOKEN"] = token;
    }

    const options = {
        method,
        headers,
        credentials: "same-origin",
    };

    if (body !== undefined) {
        options.body = JSON.stringify(body);
    }

    try {
        const response = await fetch(path, options);
        let data = null;
        const ct = response.headers.get("Content-Type") || "";
        if (ct.includes("application/json")) {
            data = await response.json();
        }
        return { ok: response.ok, status: response.status, data };
    } catch (err) {
        return { ok: false, status: 0, data: { error: "Network error" } };
    }
}

const api = {
    register: (username, email, password) =>
        apiRequest("POST", "/api/register", { username, email, password }),

    login: (username, password) => {
        // Spring Security's form login expects application/x-www-form-urlencoded.
        const token = getCsrfToken();
        const params = new URLSearchParams({ username, password });
        if (token) {
            params.append("_csrf", token);
        }
        return fetch("/login", {
            method: "POST",
            headers: { "Content-Type": "application/x-www-form-urlencoded" },
            credentials: "same-origin",
            body: params.toString(),
        }).then(async (r) => {
            let data = null;
            const ct = r.headers.get("Content-Type") || "";
            if (ct.includes("application/json")) {
                data = await r.json();
            }
            return { ok: r.ok, status: r.status, data };
        }).catch(() => ({ ok: false, status: 0, data: { error: "Network error" } }));
    },

    logout: () => apiRequest("POST", "/logout"),

    me: () => apiRequest("GET", "/api/me"),

    admin: {
        listUsers: () => apiRequest("GET", "/api/admin/users"),
        changeRole: (id, role) => apiRequest("PATCH", `/api/admin/users/${id}/role`, { role }),
    },

    webhooks: {
        list: () => apiRequest("GET", "/api/webhooks"),
        register: (url) => apiRequest("POST", "/api/webhooks", { url }),
        delete: (id) => apiRequest("DELETE", `/api/webhooks/${id}`),
        test: (id) => apiRequest("POST", `/api/webhooks/${id}/test`),
    },

    tasks: {
        list: () => apiRequest("GET", "/api/tasks"),
        create: (title, description) => apiRequest("POST", "/api/tasks", { title, description }),
        update: (id, title, description, status) =>
            apiRequest("PUT", `/api/tasks/${id}`, { title, description, status }),
        delete: (id) => apiRequest("DELETE", `/api/tasks/${id}`),
    },
};
