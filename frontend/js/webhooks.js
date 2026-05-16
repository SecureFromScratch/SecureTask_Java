/**
 * webhooks.js — logic for the webhook management page.
 *
 * Security notes:
 * - All user-controlled strings (URLs, dates) are inserted with textContent, never innerHTML.
 * - CSRF token is sent automatically by apiRequest() via the X-XSRF-TOKEN header.
 */

async function loadWebhooksPage() {
    const meResult = await api.me();
    if (!meResult.ok) {
        window.location.href = "/login.html";
        return;
    }

    dom.setText("#current-username", meResult.data.username);
    document.getElementById("logout-btn").addEventListener("click", handleLogout);

    document.getElementById("add-btn").addEventListener("click", handleAddWebhook);
    document.getElementById("url-input").addEventListener("keydown", (e) => {
        if (e.key === "Enter") { handleAddWebhook(); }
    });

    await loadWebhooks();
}

async function loadWebhooks() {
    const result = await api.webhooks.list();
    if (!result.ok) {
        dom.showMessage("#message", "Failed to load webhooks.", "error");
        return;
    }
    renderTable(result.data);
}

function renderTable(webhooks) {
    const tbody = document.getElementById("webhooks-tbody");
    tbody.textContent = "";

    if (webhooks.length === 0) {
        const tr = document.createElement("tr");
        tr.className = "empty-row";
        const td = document.createElement("td");
        td.colSpan = 4;
        td.textContent = "No webhooks registered yet.";
        tr.appendChild(td);
        tbody.appendChild(tr);
        return;
    }

    for (const webhook of webhooks) {
        tbody.appendChild(buildRow(webhook));
    }
}

function buildRow(webhook) {
    const tr = document.createElement("tr");
    tr.dataset.id = webhook.id;

    // URL — monospace, truncated by CSS
    const urlTd = document.createElement("td");
    urlTd.className = "url-cell";
    urlTd.textContent = webhook.url;
    urlTd.title = webhook.url; // tooltip shows full URL on hover
    tr.appendChild(urlTd);

    // Added date
    const dateTd = document.createElement("td");
    dateTd.textContent = new Date(webhook.createdAt).toLocaleDateString();
    tr.appendChild(dateTd);

    // Last test status — updated in place when the test button is clicked
    const statusTd = document.createElement("td");
    statusTd.className = "status-cell";
    statusTd.textContent = "—";
    tr.appendChild(statusTd);

    // Actions: Test + Delete
    const actionTd = document.createElement("td");

    const testBtn = document.createElement("button");
    testBtn.textContent = "Test";
    testBtn.className = "action-btn test-btn";
    testBtn.addEventListener("click", async () => {
        testBtn.disabled = true;
        testBtn.textContent = "Testing…";
        statusTd.textContent = "";
        statusTd.className = "status-cell";
        dom.hideMessage("#message");

        const res = await api.webhooks.test(webhook.id);

        if (res.ok) {
            statusTd.className = "status-cell status-ok";
            statusTd.textContent = res.data.targetStatus + " — " + res.data.message;
        } else if (res.status === 422) {
            statusTd.className = "status-cell status-blocked";
            statusTd.textContent = "Blocked: " + ((res.data && res.data.error) || "URL not allowed");
        } else {
            statusTd.className = "status-cell status-err";
            statusTd.textContent = "Error " + res.status;
        }

        testBtn.disabled = false;
        testBtn.textContent = "Test";
    });

    const deleteBtn = document.createElement("button");
    deleteBtn.textContent = "Delete";
    deleteBtn.className = "action-btn delete-btn";
    deleteBtn.addEventListener("click", async () => {
        deleteBtn.disabled = true;
        testBtn.disabled = true;
        dom.hideMessage("#message");

        const res = await api.webhooks.delete(webhook.id);

        if (res.ok) {
            tr.remove();
            const tbody = document.getElementById("webhooks-tbody");
            if (tbody.childElementCount === 0) {
                renderTable([]);
            }
        } else {
            dom.showMessage("#message",
                (res.data && res.data.error) || "Failed to delete webhook.", "error");
            deleteBtn.disabled = false;
            testBtn.disabled = false;
        }
    });

    actionTd.appendChild(testBtn);
    actionTd.appendChild(deleteBtn);
    tr.appendChild(actionTd);

    return tr;
}

async function handleAddWebhook() {
    const input = document.getElementById("url-input");
    const url = input.value.trim();
    if (!url) { return; }

    const addBtn = document.getElementById("add-btn");
    addBtn.disabled = true;
    addBtn.textContent = "Adding…";
    dom.hideMessage("#message");

    const res = await api.webhooks.register(url);

    if (res.ok) {
        input.value = "";
        const tbody = document.getElementById("webhooks-tbody");
        // Remove the "no webhooks" placeholder row if present
        const emptyRow = tbody.querySelector(".empty-row");
        if (emptyRow) { emptyRow.remove(); }
        tbody.appendChild(buildRow(res.data));
    } else {
        const err = (res.data && res.data.error) || "Failed to register webhook.";
        dom.showMessage("#message", err, "error");
    }

    addBtn.disabled = false;
    addBtn.textContent = "Add webhook";
}

loadWebhooksPage();
