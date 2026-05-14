/**
 * admin.js — logic for the admin user-management page.
 *
 * Security notes:
 * - All user-controlled strings are inserted with textContent, never innerHTML.
 * - The page redirects immediately if the current user is not ADMIN.
 *   This is a UX guard only — the real enforcement is @PreAuthorize on the server.
 */

const ROLES = ["ADMIN", "PROJECT_MANAGER", "DEVELOPER", "VIEWER"];

async function loadAdminPage() {
    // Verify authentication and role before rendering anything.
    const meResult = await api.me();
    if (!meResult.ok) {
        window.location.href = "/login.html";
        return;
    }

    const currentUser = meResult.data;

    // Client-side role gate — redirects non-admins to the dashboard.
    // The server enforces this independently; this only improves UX.
    if (currentUser.role !== "ADMIN") {
        window.location.href = "/dashboard.html";
        return;
    }

    dom.setText("#current-username", currentUser.username);
    document.getElementById("logout-btn").addEventListener("click", handleLogout);

    await refreshUserTable(currentUser.id);
}

async function refreshUserTable(currentUserId) {
    const result = await api.admin.listUsers();

    if (!result.ok) {
        dom.showMessage("#message", "Failed to load users.", "error");
        return;
    }

    const tbody = document.getElementById("users-tbody");
    tbody.textContent = "";

    for (const user of result.data) {
        tbody.appendChild(buildRow(user, currentUserId));
    }
}

function buildRow(user, currentUserId) {
    const tr = document.createElement("tr");

    // ID
    appendCell(tr, String(user.id));

    // Username
    appendCell(tr, user.username);

    // Email
    appendCell(tr, user.email);

    // Role badge
    const roleTd = document.createElement("td");
    const badge = document.createElement("span");
    badge.className = "badge";
    badge.textContent = user.role;
    roleTd.appendChild(badge);
    tr.appendChild(roleTd);

    // Actions column
    const actionTd = document.createElement("td");

    if (user.id === currentUserId) {
        // Own row — no role change allowed (self-escalation guard mirrors server rule).
        const note = document.createElement("span");
        note.className = "self-note";
        note.textContent = "you";
        actionTd.appendChild(note);
    } else {
        const select = document.createElement("select");
        select.className = "role-select";
        for (const role of ROLES) {
            const option = document.createElement("option");
            option.value = role;
            option.textContent = role;
            if (role === user.role) {
                option.selected = true;
            }
            select.appendChild(option);
        }

        const btn = document.createElement("button");
        btn.textContent = "Save";
        btn.className = "save-btn";
        btn.addEventListener("click", async () => {
            btn.disabled = true;
            btn.textContent = "Saving…";
            dom.hideMessage("#message");

            const newRole = select.value;
            const res = await api.admin.changeRole(user.id, newRole);

            if (res.ok) {
                // Update the badge in this row using textContent — safe.
                badge.textContent = res.data.role;
                select.value = res.data.role;
                dom.showMessage("#message", `Role updated for ${user.username}.`, "success");
            } else {
                const err = (res.data && res.data.error) ? res.data.error : "Update failed.";
                dom.showMessage("#message", err, "error");
            }

            btn.disabled = false;
            btn.textContent = "Save";
        });

        actionTd.appendChild(select);
        actionTd.appendChild(btn);
    }

    tr.appendChild(actionTd);
    return tr;
}

function appendCell(tr, text) {
    const td = document.createElement("td");
    td.textContent = text;
    tr.appendChild(td);
}

loadAdminPage();
