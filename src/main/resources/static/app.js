// STARA dashboard. Plain JS, no framework.
// Every value from the API is written with textContent, never as HTML, so a message cannot inject markup.

const KEY_STORAGE = "stara_api_key";
const $ = (id) => document.getElementById(id);

function getKey() {
    try { return sessionStorage.getItem(KEY_STORAGE); } catch { return null; }
}

function setKey(key) {
    try {
        if (key) sessionStorage.setItem(KEY_STORAGE, key);
        else sessionStorage.removeItem(KEY_STORAGE);
    } catch { /* storage blocked: the key just will not survive a reload */ }
}

let memoryKey = getKey();

function showLogin(message) {
    memoryKey = null;
    setKey(null);
    $("app").hidden = true;
    $("logout").hidden = true;
    $("login").hidden = false;
    $("login-error").textContent = message || "";
    $("api-key").focus();
}

function showApp() {
    $("login").hidden = true;
    $("app").hidden = false;
    $("logout").hidden = false;
    loadCustomers();
}

class Unauthorized extends Error {}

// Calls the API with the key. Throws Unauthorized on 401 and Error(message) on other failures.
async function api(path, options = {}) {
    const res = await fetch(path, {
        ...options,
        headers: { "Content-Type": "application/json", "X-API-Key": memoryKey, ...(options.headers || {}) },
    });
    if (res.status === 401) throw new Unauthorized();
    const body = res.status === 204 ? null : await res.json().catch(() => null);
    if (!res.ok) throw new Error((body && body.error) || `HTTP ${res.status}`);
    return body;
}

function handleError(err, target) {
    if (err instanceof Unauthorized) showLogin("API key salah atau kedaluwarsa.");
    else target.textContent = err.message;
}

function cell(text) {
    const td = document.createElement("td");
    td.textContent = text;
    return td;
}

async function loadCustomers() {
    try {
        const customers = await api("/customers");
        const rows = $("customer-rows");
        rows.replaceChildren(...customers.map((c) => {
            const tr = document.createElement("tr");
            tr.append(cell(c.name), cell(c.phone), cell(c.status));
            tr.tabIndex = 0;
            tr.addEventListener("click", () => openChat(c.id, tr));
            tr.addEventListener("keydown", (e) => { if (e.key === "Enter") openChat(c.id, tr); });
            return tr;
        }));
        $("list-empty").hidden = customers.length > 0;
    } catch (err) {
        handleError(err, $("add-error"));
    }
}

async function openChat(id, row) {
    document.querySelectorAll("#customer-rows tr").forEach((tr) => tr.classList.remove("selected"));
    row.classList.add("selected");
    try {
        const customer = await api(`/customers/${id}`);
        $("chat-title").textContent = `Percakapan dengan ${customer.name}`;
        $("chat-empty").hidden = customer.messages.length > 0;
        $("chat-empty").textContent = "Belum ada pesan.";
        $("chat").replaceChildren(...customer.messages.map((m) => {
            const li = document.createElement("li");
            li.className = m.direction;
            const text = document.createElement("p");
            text.textContent = m.content;
            const meta = document.createElement("small");
            const who = m.direction === "incoming" ? "Pelanggan" : "Balasan";
            meta.textContent = `${who} · ${new Date(m.created_at).toLocaleString("id-ID")}`;
            li.append(text, meta);
            return li;
        }));
    } catch (err) {
        handleError(err, $("chat-empty"));
    }
}

$("login-form").addEventListener("submit", (e) => {
    e.preventDefault();
    memoryKey = $("api-key").value.trim();
    setKey(memoryKey);
    $("api-key").value = "";
    showApp();
});

$("logout").addEventListener("click", () => showLogin());
$("reload").addEventListener("click", loadCustomers);

$("add-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    const form = e.target;
    $("add-error").textContent = "";
    try {
        await api("/customers", {
            method: "POST",
            body: JSON.stringify({ name: form.name.value, phone: form.phone.value, status: form.status.value }),
        });
        form.reset();
        loadCustomers();
    } catch (err) {
        handleError(err, $("add-error"));
    }
});

if (memoryKey) showApp(); else showLogin();
