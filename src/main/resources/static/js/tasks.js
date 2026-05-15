/**
 * tasks.js — logic for the task management page.
 *
 * Security notes:
 * - All user-controlled strings (title, description) are inserted with textContent, never innerHTML.
 *   Setting innerHTML with untrusted input is a stored XSS vulnerability.
 * - CSRF token is sent automatically by apiRequest() via the X-XSRF-TOKEN header.
 */

async function loadTasksPage() {
    const meResult = await api.me();
    if (!meResult.ok) {
        window.location.href = "/login.html";
        return;
    }

    dom.setText("#current-username", meResult.data.username);
    document.getElementById("logout-btn").addEventListener("click", handleLogout);
    document.getElementById("add-btn").addEventListener("click", handleAddTask);
    document.getElementById("title-input").addEventListener("keydown", (e) => {
        if (e.key === "Enter") { handleAddTask(); }
    });

    await loadTasks();
}

async function loadTasks() {
    const result = await api.tasks.list();
    if (!result.ok) {
        dom.showMessage("#message", "Failed to load tasks.", "error");
        return;
    }
    renderTable(result.data);
}

function renderTable(tasks) {
    const tbody = document.getElementById("tasks-tbody");
    tbody.textContent = "";

    if (tasks.length === 0) {
        const tr = document.createElement("tr");
        tr.className = "empty-row";
        const td = document.createElement("td");
        td.colSpan = 5;
        td.textContent = "No tasks yet.";
        tr.appendChild(td);
        tbody.appendChild(tr);
        return;
    }

    for (const task of tasks) {
        tbody.appendChild(buildRow(task));
    }
}

function buildRow(task) {
    const tr = document.createElement("tr");
    tr.dataset.id = task.id;

    // Title — user-controlled; textContent prevents XSS.
    // VULNERABLE pattern (never use): titleTd.innerHTML = task.title
    const titleTd = document.createElement("td");
    titleTd.className = "title-cell";
    titleTd.textContent = task.title;
    titleTd.title = task.title;
    tr.appendChild(titleTd);

    // Description — user-controlled; textContent prevents XSS.
    const descTd = document.createElement("td");
    descTd.className = "desc-cell";
    descTd.textContent = task.description || "";
    descTd.title = task.description || "";
    tr.appendChild(descTd);

    // Status — dropdown for inline update
    const statusTd = document.createElement("td");
    const select = document.createElement("select");
    select.className = "status-select";
    for (const s of ["OPEN", "IN_PROGRESS", "DONE"]) {
        const opt = document.createElement("option");
        opt.value = s;
        opt.textContent = s.replace("_", " ");
        if (s === task.status) { opt.selected = true; }
        select.appendChild(opt);
    }
    select.addEventListener("change", async () => {
        select.disabled = true;
        const res = await api.tasks.update(task.id, task.title, task.description, select.value);
        if (res.ok) {
            task.status = res.data.status;
        } else {
            // Revert on failure
            select.value = task.status;
            dom.showMessage("#message",
                (res.data && res.data.errors && res.data.errors[0]) || "Failed to update task.", "error");
        }
        select.disabled = false;
    });
    statusTd.appendChild(select);
    tr.appendChild(statusTd);

    // Created date
    const dateTd = document.createElement("td");
    dateTd.textContent = new Date(task.createdAt).toLocaleDateString();
    tr.appendChild(dateTd);

    // Actions
    const actionTd = document.createElement("td");
    const deleteBtn = document.createElement("button");
    deleteBtn.textContent = "Delete";
    deleteBtn.className = "action-btn delete-btn";
    deleteBtn.addEventListener("click", async () => {
        deleteBtn.disabled = true;
        select.disabled = true;
        dom.hideMessage("#message");

        const res = await api.tasks.delete(task.id);
        if (res.ok) {
            tr.remove();
            const tbody = document.getElementById("tasks-tbody");
            if (tbody.childElementCount === 0) { renderTable([]); }
        } else {
            dom.showMessage("#message",
                (res.data && res.data.error) || "Failed to delete task.", "error");
            deleteBtn.disabled = false;
            select.disabled = false;
        }
    });

    actionTd.appendChild(deleteBtn);
    tr.appendChild(actionTd);

    return tr;
}

async function handleAddTask() {
    const titleInput = document.getElementById("title-input");
    const descInput  = document.getElementById("desc-input");
    const title = titleInput.value.trim();
    const description = descInput.value.trim() || null;

    if (!title) {
        dom.showMessage("#message", "Title is required.", "error");
        return;
    }

    const addBtn = document.getElementById("add-btn");
    addBtn.disabled = true;
    addBtn.textContent = "Adding…";
    dom.hideMessage("#message");

    const res = await api.tasks.create(title, description);

    if (res.ok) {
        titleInput.value = "";
        descInput.value = "";
        const tbody = document.getElementById("tasks-tbody");
        const emptyRow = tbody.querySelector(".empty-row");
        if (emptyRow) { emptyRow.remove(); }
        tbody.appendChild(buildRow(res.data));
    } else {
        const errors = res.data && res.data.errors;
        const msg = errors ? errors.join("; ") : ((res.data && res.data.error) || "Failed to create task.");
        dom.showMessage("#message", msg, "error");
    }

    addBtn.disabled = false;
    addBtn.textContent = "Add task";
}

loadTasksPage();
