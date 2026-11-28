"use strict";

// Tally flags admin screen (ADR-055). Plain browser JavaScript, no libraries.
// Everything this page shows comes from the admin API; the token lives only in
// the #token input and is read at each call. The page never persists it.

const $ = (id) => document.getElementById(id);

function el(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined && text !== null) node.textContent = text;
  if (className) node.className = className;
  return node;
}

function token() {
  return $("token").value.trim();
}

// One helper for every call: sets the bearer header, a fresh idempotency key on
// every change, and a JSON body when there is one. Non-2xx responses throw.
async function api(method, path, body) {
  const bearer = token();
  if (!bearer) throw new Error("Enter a staff token first.");
  const headers = { Authorization: "Bearer " + bearer };
  if (method !== "GET") {
    headers["Idempotency-Key"] = crypto.randomUUID();
  }
  const hasBody = body !== undefined;
  const init = { method, headers };
  if (hasBody) {
    headers["Content-Type"] = "application/json";
    init.body = JSON.stringify(body);
  }
  const response = await fetch(path, init);
  const text = await response.text();
  if (!response.ok) {
    throw new Error(response.status + " " + response.statusText + ": " + text);
  }
  if (text) return JSON.parse(text);
  return null;
}

function showStatus(text, kind) {
  const node = $("status");
  node.className = kind || "";
  node.textContent = text;
}

// ---- report ---------------------------------------------------------------

function summaryCounts(entries) {
  const on = entries.filter(([, value]) => value).length;
  return entries.length + " assigned (" + on + " on)";
}

function rowForFlag(flag) {
  const row = document.createElement("tr");
  if (flag.overdue) row.classList.add("overdue");

  row.appendChild(cell(flag.name));
  row.appendChild(cell(flag.defaultOn ? "On" : "Off"));
  row.appendChild(cell(summaryCounts(Object.entries(flag.accounts ?? {}))));
  row.appendChild(cell(summaryCounts(Object.entries(flag.countries ?? {}))));
  row.appendChild(cell(flag.releasedAt ?? "—"));
  row.appendChild(cell(flag.removeBy ?? "—"));
  row.appendChild(cell(flag.overdue ? "yes" : "no"));
  row.appendChild(cell(String(flag.changeCount ?? 0)));
  row.appendChild(cell(flag.lastChangeAt ?? "—"));

  const actions = document.createElement("td");
  const button = el("button", "Details", "details");
  button.type = "button";
  button.addEventListener("click", () => showDetails(flag.name));
  actions.appendChild(button);
  row.appendChild(actions);
  return row;
}

function cell(text) {
  const td = document.createElement("td");
  td.textContent = text;
  return td;
}

function renderOverdue(names) {
  const node = $("overdue");
  node.replaceChildren();
  if (!names.length) {
    node.className = "empty";
    node.textContent = "No overdue flags.";
    return;
  }
  node.className = "has";
  node.appendChild(el("strong", "Overdue flags:"));
  const list = document.createElement("ul");
  for (const name of names) list.appendChild(el("li", name));
  node.appendChild(list);
}

let currentDetailName = null;

function renderReport(report) {
  $("asOf").textContent = report.asOf ?? "—";
  renderOverdue(report.overdue ?? []);
  const body = $("report");
  const flags = report.flags ?? [];
  body.replaceChildren(...flags.map(rowForFlag));
  if (!flags.length) {
    const row = document.createElement("tr");
    const cellNode = document.createElement("td");
    cellNode.colSpan = 10;
    cellNode.textContent = "No flags defined.";
    row.appendChild(cellNode);
    body.appendChild(row);
  }
}

async function fetchReport() {
  const report = await api("GET", "/v1/admin/flags");
  renderReport(report);
  return report;
}

async function loadReport() {
  try {
    await fetchReport();
    showStatus("Report loaded.", "ok");
  } catch (error) {
    showStatus("Error: " + error.message, "error");
  }
}

// ---- detail ----------------------------------------------------------------

function addField(definitionList, label, value) {
  definitionList.appendChild(el("dt", label));
  definitionList.appendChild(el("dd", value));
}

function listOrEmpty(items, emptyText) {
  if (!items.length) return el("p", emptyText);
  const list = document.createElement("ul");
  list.className = "plain";
  for (const item of items) list.appendChild(el("li", item));
  return list;
}

function renderDetail(flag) {
  const section = $("detail");
  section.replaceChildren();
  currentDetailName = flag.name;

  section.appendChild(el("h2", flag.name));

  const fields = document.createElement("dl");
  fields.className = "fields";
  addField(fields, "Default", flag.defaultOn ? "On" : "Off");
  addField(fields, "Released at", flag.releasedAt ?? "—");
  addField(fields, "Remove by", flag.removeBy ?? "—");
  addField(fields, "Overdue", flag.overdue ? "yes" : "no");
  addField(fields, "Changes", String(flag.changeCount ?? 0));
  addField(fields, "Last change", flag.lastChangeAt ?? "—");
  section.appendChild(fields);

  section.appendChild(el("h3", "Accounts"));
  section.appendChild(listOrEmpty(
    Object.entries(flag.accounts ?? {}).map(([id, on]) => id + ": " + (on ? "on" : "off")),
    "No account assignments."));

  section.appendChild(el("h3", "Countries"));
  section.appendChild(listOrEmpty(
    Object.entries(flag.countries ?? {}).map(([code, on]) => code + ": " + (on ? "on" : "off")),
    "No country assignments."));

  section.appendChild(el("h3", "Change log"));
  const changes = flag.changes ?? [];
  if (!changes.length) {
    section.appendChild(el("p", "No changes recorded."));
  } else {
    const list = document.createElement("ol");
    list.className = "plain";
    for (const change of changes) list.appendChild(el("li", change.at + " — " + change.description));
    section.appendChild(list);
  }
}

async function showDetails(name) {
  try {
    const flag = await api("GET", "/v1/admin/flags/" + encodeURIComponent(name));
    renderDetail(flag);
  } catch (error) {
    showStatus("Error: " + error.message, "error");
  }
}

// ---- changes ----------------------------------------------------------------

function flagInputName() {
  return $("flagName").value.trim();
}

function requireFlagName() {
  const name = flagInputName();
  if (!name) {
    showStatus("Enter a flag name first.", "error");
    return null;
  }
  return name;
}

async function afterChange() {
  // Reload the report, then refresh the detail view if it still shows a live flag.
  const detailName = currentDetailName;
  try {
    const report = await fetchReport();
    const names = new Set((report.flags ?? []).map((flag) => flag.name));
    if (detailName) {
      if (names.has(detailName)) {
        await showDetails(detailName);
      } else {
        $("detail").replaceChildren();
        currentDetailName = null;
      }
    }
  } catch (error) {
    showStatus("Error: " + error.message, "error");
  }
}

async function defineFlag() {
  const name = $("newName").value.trim();
  if (!name) {
    showStatus("Enter a new flag name.", "error");
    return;
  }
  try {
    await api("POST", "/v1/admin/flags", { name });
    $("newName").value = "";
    showStatus('Flag "' + name + '" defined.', "ok");
    await afterChange();
  } catch (error) {
    showStatus("Error: " + error.message, "error");
  }
}

async function setDefault() {
  const name = requireFlagName();
  if (!name) return;
  const on = $("defaultOn").checked;
  try {
    await api("PUT", "/v1/admin/flags/" + encodeURIComponent(name) + "/default", { on });
    showStatus('Default for "' + name + '" set to ' + (on ? "on" : "off") + ".", "ok");
    await afterChange();
  } catch (error) {
    showStatus("Error: " + error.message, "error");
  }
}

async function assignAccount() {
  const name = requireFlagName();
  if (!name) return;
  const accountId = $("accountId").value.trim();
  if (!accountId) {
    showStatus("Enter an account ID first.", "error");
    return;
  }
  const on = $("accountOn").checked;
  try {
    await api("PUT", "/v1/admin/flags/" + encodeURIComponent(name) + "/accounts/" + encodeURIComponent(accountId), { on });
    showStatus('Assigned "' + name + '" for account ' + accountId + " (" + (on ? "on" : "off") + ").", "ok");
    await afterChange();
  } catch (error) {
    showStatus("Error: " + error.message, "error");
  }
}

async function clearAccount() {
  const name = requireFlagName();
  if (!name) return;
  const accountId = $("accountId").value.trim();
  if (!accountId) {
    showStatus("Enter an account ID first.", "error");
    return;
  }
  try {
    await api("DELETE", "/v1/admin/flags/" + encodeURIComponent(name) + "/accounts/" + encodeURIComponent(accountId));
    showStatus('Cleared account ' + accountId + ' for "' + name + '".', "ok");
    await afterChange();
  } catch (error) {
    showStatus("Error: " + error.message, "error");
  }
}

async function assignCountry() {
  const name = requireFlagName();
  if (!name) return;
  const country = $("country").value.trim();
  if (!country) {
    showStatus("Enter a country code first.", "error");
    return;
  }
  const on = $("countryOn").checked;
  try {
    await api("PUT", "/v1/admin/flags/" + encodeURIComponent(name) + "/countries/" + encodeURIComponent(country), { on });
    showStatus('Assigned "' + name + '" for country ' + country + " (" + (on ? "on" : "off") + ").", "ok");
    await afterChange();
  } catch (error) {
    showStatus("Error: " + error.message, "error");
  }
}

async function clearCountry() {
  const name = requireFlagName();
  if (!name) return;
  const country = $("country").value.trim();
  if (!country) {
    showStatus("Enter a country code first.", "error");
    return;
  }
  try {
    await api("DELETE", "/v1/admin/flags/" + encodeURIComponent(name) + "/countries/" + encodeURIComponent(country));
    showStatus('Cleared country ' + country + ' for "' + name + '".', "ok");
    await afterChange();
  } catch (error) {
    showStatus("Error: " + error.message, "error");
  }
}

async function releaseFlag() {
  const name = requireFlagName();
  if (!name) return;
  try {
    await api("POST", "/v1/admin/flags/" + encodeURIComponent(name) + "/release");
    showStatus('Flag "' + name + '" released.', "ok");
    await afterChange();
  } catch (error) {
    showStatus("Error: " + error.message, "error");
  }
}

async function removeFlag() {
  const name = requireFlagName();
  if (!name) return;
  if (!window.confirm('Remove flag "' + name + '"? This cannot be undone.')) return;
  try {
    await api("DELETE", "/v1/admin/flags/" + encodeURIComponent(name));
    showStatus('Flag "' + name + '" removed.', "ok");
    await afterChange();
  } catch (error) {
    showStatus("Error: " + error.message, "error");
  }
}

// ---- wiring -----------------------------------------------------------------

$("load").addEventListener("click", loadReport);

$("flag-form").addEventListener("submit", (event) => {
  event.preventDefault();
  defineFlag();
});

$("setDefault").addEventListener("click", setDefault);
$("assignAccount").addEventListener("click", assignAccount);
$("clearAccount").addEventListener("click", clearAccount);
$("assignCountry").addEventListener("click", assignCountry);
$("clearCountry").addEventListener("click", clearCountry);
$("release").addEventListener("click", releaseFlag);
$("remove").addEventListener("click", removeFlag);
