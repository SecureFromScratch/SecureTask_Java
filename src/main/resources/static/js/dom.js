/**
 * dom.js — safe DOM helpers.
 *
 * Security rule: never use innerHTML with user-controlled strings.
 * Always use textContent or these helpers to avoid XSS.
 */

const dom = {
    /**
     * Sets element text safely using textContent.
     * Never call element.innerHTML = userContent.
     */
    setText(selector, text) {
        const el = document.querySelector(selector);
        if (el) {
            el.textContent = text;
        }
    },

    showMessage(selector, text, type) {
        const el = document.querySelector(selector);
        if (!el) { return; }
        el.textContent = text;
        el.className = "message " + (type || "");
        el.style.display = "block";
    },

    hideMessage(selector) {
        const el = document.querySelector(selector);
        if (el) {
            el.style.display = "none";
        }
    },

    /**
     * Creates a text node — safe alternative to innerHTML for dynamic content.
     */
    createTextNode(text) {
        return document.createTextNode(String(text));
    },
};
