/**
 * attachments.js — UI logic for per-task file attachments.
 *
 * Security notes:
 * - Filenames from the server are inserted with textContent, never innerHTML.
 * - Download links use api.attachments.download() which returns a path routed through
 *   the authenticated controller endpoint — files are never served directly from the
 *   webroot and always carry Content-Disposition: attachment.
 */

function buildAttachmentDetailRow(taskId) {
    const tr = document.createElement("tr");
    tr.className = "attachment-row";
    tr.style.display = "none";
    tr.dataset.taskId = String(taskId);

    const td = document.createElement("td");
    td.colSpan = 5;

    const panel = document.createElement("div");
    panel.className = "attachment-panel";

    // Upload area
    const uploadDiv = document.createElement("div");
    uploadDiv.className = "attachment-upload";

    const fileInput = document.createElement("input");
    fileInput.type = "file";
    fileInput.className = "file-input";
    fileInput.accept = ".png,.jpg,.jpeg,.gif,.webp,.pdf";

    const uploadBtn = document.createElement("button");
    uploadBtn.textContent = "Upload";
    uploadBtn.className = "action-btn upload-btn";

    const uploadMsg = document.createElement("span");
    uploadMsg.className = "upload-msg";

    uploadDiv.appendChild(fileInput);
    uploadDiv.appendChild(uploadBtn);
    uploadDiv.appendChild(uploadMsg);

    // Attachment list
    const listDiv = document.createElement("div");
    listDiv.className = "attachment-list";

    panel.appendChild(uploadDiv);
    panel.appendChild(listDiv);
    td.appendChild(panel);
    tr.appendChild(td);

    uploadBtn.addEventListener("click", () =>
        handleAttachmentUpload(taskId, fileInput, listDiv, uploadMsg, uploadBtn));

    return tr;
}

async function loadAttachments(taskId, listDiv) {
    listDiv.textContent = "Loading…";
    const result = await api.attachments.list(taskId);
    if (!result.ok) {
        listDiv.textContent = "Failed to load attachments.";
        return;
    }
    renderAttachmentList(taskId, result.data, listDiv);
}

function renderAttachmentList(taskId, attachments, listDiv) {
    listDiv.textContent = "";

    if (attachments.length === 0) {
        const p = document.createElement("p");
        p.className = "no-attachments";
        p.textContent = "No attachments yet.";
        listDiv.appendChild(p);
        return;
    }

    const ul = document.createElement("ul");
    ul.className = "attachment-items";

    for (const att of attachments) {
        const li = document.createElement("li");
        li.className = "attachment-item";

        // Download link — textContent prevents XSS on the displayed filename.
        const link = document.createElement("a");
        link.href = api.attachments.download(taskId, att.id);
        link.textContent = att.originalFilename;
        link.download = att.originalFilename;
        link.className = "attachment-link";

        const size = document.createElement("span");
        size.className = "attachment-size";
        size.textContent = " (" + formatFileSize(att.fileSize) + ")";

        const removeBtn = document.createElement("button");
        removeBtn.textContent = "Remove";
        removeBtn.className = "action-btn delete-btn attachment-remove-btn";
        removeBtn.addEventListener("click", () =>
            handleAttachmentDelete(taskId, att.id, li, ul, listDiv));

        li.appendChild(link);
        li.appendChild(size);
        li.appendChild(removeBtn);
        ul.appendChild(li);
    }

    listDiv.appendChild(ul);
}

async function handleAttachmentUpload(taskId, fileInput, listDiv, msgEl, uploadBtn) {
    const file = fileInput.files[0];
    if (!file) {
        msgEl.textContent = "Select a file first.";
        msgEl.className = "upload-msg error";
        return;
    }

    uploadBtn.disabled = true;
    msgEl.textContent = "";
    msgEl.className = "upload-msg";

    const res = await api.attachments.upload(taskId, file);

    if (res.ok) {
        fileInput.value = "";
        msgEl.textContent = "Uploaded.";
        msgEl.className = "upload-msg success";
        await loadAttachments(taskId, listDiv);
    } else {
        const errMsg = (res.data && (res.data.error || (res.data.errors && res.data.errors[0])))
            || "Upload failed.";
        msgEl.textContent = errMsg;
        msgEl.className = "upload-msg error";
    }

    uploadBtn.disabled = false;
}

async function handleAttachmentDelete(taskId, attachmentId, itemEl, ulEl, listDiv) {
    const res = await api.attachments.delete(taskId, attachmentId);
    if (res.ok) {
        itemEl.remove();
        if (ulEl.childElementCount === 0) {
            renderAttachmentList(taskId, [], listDiv);
        }
    }
}

function formatFileSize(bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return Math.round(bytes / 1024) + " KB";
    return (bytes / (1024 * 1024)).toFixed(1) + " MB";
}
