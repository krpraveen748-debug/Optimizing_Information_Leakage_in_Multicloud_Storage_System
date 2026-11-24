const charts = {};

async function jsonFetch(path, options = {}) {
  const response = await fetch(path, { credentials:"same-origin", ...options });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.error || `Request failed (${response.status})`);
  return data;
}

async function csrf() {
  return jsonFetch("/api/auth/csrf");
}

function chartCanvas(id) {
  if (!window.Chart) return null;
  if (charts[id]) charts[id].destroy();
  return document.getElementById(id);
}

function emptyChart(canvasId, message) {
  const canvas = chartCanvas(canvasId);
  if (!canvas) return;
  const context = canvas.getContext("2d");
  context.clearRect(0, 0, canvas.width, canvas.height);
  context.font = "14px system-ui";
  context.fillStyle = "#69788d";
  context.textAlign = "center";
  context.fillText(message, canvas.width / 2, canvas.height / 2);
}

function renderChart(id, type, labels, values, options = {}) {
  if (!window.Chart) return;
  const canvas = chartCanvas(id);
  if (!labels.length) {
    emptyChart(id, "No data available");
    return;
  }
  charts[id] = new Chart(canvas, {
    type,
    data: {
      labels,
      datasets: [{
        label: options.label || "Value",
        data: values,
        backgroundColor: options.colors || ["#2867c7","#4ea1d3","#78c091","#f0b44d","#8e70c9"],
        borderColor: "#2867c7",
        borderWidth: 1,
        tension: .25
      }]
    },
    options: {
      responsive:true,
      maintainAspectRatio:false,
      scales: type === "line" || type === "bar" ? { y: { beginAtZero:true } } : undefined,
      plugins: { legend: { display:type === "doughnut" } }
    }
  });
}

async function loadDashboard() {
  try {
    const profile = await jsonFetch("/api/auth/profile");
    if (profile.role !== "ADMIN") {
      window.location.replace("/main.html");
      return;
    }
    document.getElementById("admin-name").textContent = profile.fullName;
    const dashboard = await jsonFetch("/api/admin/dashboard");
    renderSummary(dashboard);
    renderCharts(dashboard);
    await loadUsers();
    await loadActivity();
  } catch (error) {
    document.getElementById("admin-message").textContent = error.message;
  }
}

function renderSummary(data) {
  const cards = [
    ["Registered users", data.totalUsers],
    ["Uploaded files", data.totalFiles],
    ["Unique chunks", data.totalUniqueChunks],
    ["Deduplicated chunks", data.deduplicatedChunks],
    ["Original file bytes", Number(data.totalOriginalBytes).toLocaleString()],
    ["Unique chunk storage", Number(data.totalUniqueChunkStorageBytes).toLocaleString()]
  ];
  const container = document.getElementById("summary-cards");
  container.replaceChildren();
  for (const [label, value] of cards) {
    const column = document.createElement("div");
    column.className = "col-sm-6 col-xl-2";
    column.innerHTML = `<article class="card summary-card"><div class="card-body"><span>${label}</span><strong>${value}</strong></div></article>`;
    container.append(column);
  }
}

async function renderCharts(dashboard) {
  const providers = dashboard.providerDistribution || [];
  renderChart("provider-chart", "doughnut",
    providers.map(item => item.provider),
    providers.map(item => Number(item.storageBytes)));

  const timeline = dashboard.uploadTimeline || [];
  renderChart("upload-chart", "line",
    timeline.map(item => item.date),
    timeline.map(item => Number(item.fileCount)),
    { label:"Uploaded files" });

  renderChart("dedup-chart", "bar",
    ["Unique storage", "Deduplicated references"],
    [Number(dashboard.totalUniqueChunkStorageBytes),
     Number(dashboard.deduplicatedChunks)],
    { label:"Deduplication metrics", colors:["#2867c7","#78c091"] });

  const leakage = await jsonFetch("/api/admin/analytics/leakage");
  const latest = leakage.latest || {};
  const pairs = latest.pairComparisons || [];
  renderChart("jaccard-chart", "bar",
    pairs.map(pair => `${pair.providerAName || pair.providerA}/${pair.providerBName || pair.providerB}`),
    pairs.map(pair => pair.jaccardSimilarity == null ? null : Number(pair.jaccardSimilarity)),
    { label:"Jaccard similarity", colors:["#4ea1d3"] });

  const placement = dashboard.latestPlacementExperiment || {};
  const strategies = placement.comparison ? Object.entries(placement.comparison) : [];
  renderChart("placement-chart", "bar",
    strategies.map(([name]) => name),
    strategies.map(([, result]) => Object.values(result.providers || {}).reduce(
      (sum, provider) => sum + Number(provider.chunkCount || 0), 0)),
    { label:"Placed chunks", colors:["#2867c7","#78c091","#f0b44d"] });

  renderProviderLoad(dashboard);
}

function renderProviderLoad(dashboard) {
  const providers = dashboard.providerDistribution || [];
  const existing = document.getElementById("provider-load-table");
  if (existing) existing.remove();
  const panel = document.querySelector("#overview");
  if (!panel) return;
  const table = document.createElement("div");
  table.id = "provider-load-table";
  table.className = "admin-panel mt-3";
  table.innerHTML = `<h2>Provider Load Balance</h2><div class="table-responsive"><table class="table"><thead><tr><th>Provider</th><th>Chunks</th><th>Storage bytes</th></tr></thead><tbody></tbody></table></div>`;
  panel.append(table);
  const tbody = table.querySelector("tbody");
  for (const provider of providers) {
    const row = document.createElement("tr");
    for (const value of [
      provider.provider,
      provider.chunkCount,
      Number(provider.storageBytes).toLocaleString()
    ]) {
      const cell = document.createElement("td");
      cell.textContent = String(value ?? "");
      row.append(cell);
    }
    tbody.append(row);
  }
  if (!providers.length) {
    tbody.innerHTML = '<tr><td colspan="3" class="text-muted">No provider data available.</td></tr>';
  }
}

async function loadUsers(query = "") {
  const users = await jsonFetch(`/api/admin/users/search?query=${encodeURIComponent(query)}`);
  const body = document.getElementById("users-body");
  body.replaceChildren();
  for (const user of users) {
    const row = document.createElement("tr");
    row.innerHTML = `<td>${escapeHtml(user.fullName)}</td><td>${escapeHtml(user.email)}</td><td>${user.role}</td><td>${user.status}</td><td>${new Date(user.createdAt).toLocaleString()}</td><td></td>`;
    if (user.role !== "ADMIN") {
      const button = document.createElement("button");
      button.className = "btn btn-sm " + (user.status === "ACTIVE" ? "btn-outline-danger" : "btn-outline-success");
      button.textContent = user.status === "ACTIVE" ? "Deactivate" : "Activate";
      button.onclick = () => changeUserStatus(user.id, user.status === "ACTIVE" ? "DISABLED" : "ACTIVE");
      row.lastElementChild.append(button);
    }
    body.append(row);
  }
  if (!users.length) body.innerHTML = '<tr><td colspan="6" class="text-muted">No users match this search.</td></tr>';
}

async function changeUserStatus(id, status) {
  try {
    const token = await csrf();
    await jsonFetch(`/api/admin/users/${id}/status?value=${encodeURIComponent(status)}`, {
      method:"PATCH",
      headers:{ [token.headerName]:token.token }
    });
    await loadUsers(document.getElementById("user-search").value);
  } catch (error) {
    document.getElementById("admin-message").textContent = error.message;
  }
}

async function loadActivity() {
  const activity = await jsonFetch("/api/admin/analytics/activity?limit=50");
  const body = document.getElementById("activity-body");
  body.replaceChildren();
  for (const item of activity) {
    const row = document.createElement("tr");
    row.innerHTML = `<td>${new Date(item.activityTime).toLocaleString()}</td><td>${escapeHtml(item.email)}</td><td>${escapeHtml(item.activityType)}</td><td>${escapeHtml(item.description)}</td>`;
    body.append(row);
  }
  if (!activity.length) body.innerHTML = '<tr><td colspan="4" class="text-muted">No activity has been recorded.</td></tr>';
}

function escapeHtml(value) {
  const element = document.createElement("span");
  element.textContent = String(value ?? "");
  return element.innerHTML;
}

document.getElementById("user-search").addEventListener("input",
  event => loadUsers(event.target.value).catch(error => {
    document.getElementById("admin-message").textContent = error.message;
  }));

document.getElementById("print-report").addEventListener("click", () => window.print());
document.getElementById("admin-logout").addEventListener("click", async () => {
  try {
    const token = await csrf();
    await jsonFetch("/api/auth/logout", {
      method:"POST",
      headers:{ [token.headerName]:token.token }
    });
    window.location.replace("/login.html");
  } catch (error) {
    document.getElementById("admin-message").textContent = error.message;
  }
});

loadDashboard();
