async function getCsrfToken() {
  const response = await fetch("/api/auth/csrf", { credentials: "same-origin" });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.error || "Could not start a secure session.");
  return data;
}

async function responseData(response) {
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(data.error || `Request failed (${response.status}).`);
  }
  return data;
}

function showLeakageMessage(text, success = false) {
  const message = document.getElementById("leakage-message");
  message.textContent = text;
  message.classList.toggle("success", success);
}

function percentage(value) {
  return value == null ? "No data" : `${Number(value).toFixed(2)}%`;
}

function renderPairs(pairs) {
  const table = document.getElementById("provider-pairs");
  table.replaceChildren();
  if (!pairs.length) {
    const row = table.insertRow();
    const cell = row.insertCell();
    cell.colSpan = 7;
    cell.textContent = "No provider comparison data available.";
    return;
  }
  for (const pair of pairs) {
    const row = table.insertRow();
    const values = [
      `${pair.providerA} vs ${pair.providerB}`,
      `${pair.distinctFingerprintCountA} / ${pair.distinctFingerprintCountB}`,
      pair.sharedFingerprintCount,
      pair.unionFingerprintCount,
      pair.jaccardSimilarity == null ? "No data" : Number(pair.jaccardSimilarity).toFixed(4),
      percentage(pair.sharedFingerprintPercentage),
      pair.dataAvailable ? (pair.highSimilarity ? "High" : "Below threshold") : "No data"
    ];
    for (const value of values) {
      row.insertCell().textContent = String(value);
    }
  }
}

function renderAnalysis(analysis, pairs = analysis.pairComparisons) {
  document.getElementById("overall-overlap").textContent = percentage(analysis.overallSimilarityPercent);
  document.getElementById("high-pairs").textContent =
    `${analysis.highSimilarityPairCount} / ${pairs?.length ?? analysis.analyzedPairCount}`;
  document.getElementById("analysis-threshold").textContent =
    `${(Number(analysis.highSimilarityThreshold) * 100).toFixed(1)}%`;
  document.getElementById("analysis-explanation").textContent = analysis.status === "NO_DATA"
    ? "No uploaded chunk fingerprints were available for a non-empty provider comparison."
    : `The overall indicator is the arithmetic mean of exact pairwise Jaccard values for pairs with non-empty unions. Run recorded at ${new Date(analysis.createdAt).toLocaleString()}.`;
  renderPairs(pairs || []);
}

async function loadPairs(analysisId) {
  const pairs = await responseData(await fetch(
    `/api/admin/leakage/analysis/${encodeURIComponent(analysisId)}/pairs`,
    { credentials: "same-origin" }));
  renderPairs(pairs);
  return pairs;
}

async function loadHistory() {
  const history = await responseData(await fetch(
    "/api/admin/leakage/analysis/history", { credentials: "same-origin" }));
  const table = document.getElementById("analysis-history");
  table.replaceChildren();
  if (!history.length) {
    const row = table.insertRow();
    const cell = row.insertCell();
    cell.colSpan = 6;
    cell.textContent = "No analysis history available.";
    return;
  }
  for (const item of history) {
    const row = table.insertRow();
    row.insertCell().textContent = new Date(item.createdAt).toLocaleString();
    row.insertCell().textContent = percentage(item.overallSimilarityPercent);
    row.insertCell().textContent = String(item.analyzedPairCount);
    row.insertCell().textContent = String(item.highSimilarityPairCount);
    row.insertCell().textContent = `${(Number(item.highSimilarityThreshold) * 100).toFixed(1)}%`;
    const action = document.createElement("button");
    action.type = "button";
    action.className = "button button-secondary";
    action.textContent = "View pairs";
    action.addEventListener("click", async () => {
      try {
        const pairs = await loadPairs(item.analysisId);
        renderAnalysis(item, pairs);
        showLeakageMessage(`Showing provider comparisons from ${new Date(item.createdAt).toLocaleString()}.`, true);
      } catch (error) {
        showLeakageMessage(error.message);
      }
    });
    row.insertCell().append(action);
  }
}

async function loadLatest() {
  const response = await fetch("/api/admin/leakage/analysis/latest", { credentials: "same-origin" });
  if (response.status === 404) {
    document.getElementById("overall-overlap").textContent = "—";
    document.getElementById("high-pairs").textContent = "—";
    document.getElementById("analysis-threshold").textContent = "—";
    document.getElementById("analysis-explanation").textContent = "No analysis has been run yet.";
    renderPairs([]);
    return;
  }
  const analysis = await responseData(response);
  renderAnalysis(analysis);
}

async function loadLeakageDashboard() {
  try {
    const profileResponse = await fetch("/api/auth/profile", { credentials: "same-origin" });
    if (profileResponse.status === 401) {
      window.location.replace("/login.html");
      return;
    }
    const profile = await responseData(profileResponse);
    if (profile.role !== "ADMIN") {
      showLeakageMessage("Administrator access is required.");
      document.getElementById("run-leakage-analysis").disabled = true;
      return;
    }
    document.getElementById("admin-account-label").textContent = profile.fullName;
    await Promise.all([loadLatest(), loadHistory()]);
  } catch (error) {
    showLeakageMessage(error.message);
  }
}

document.getElementById("run-leakage-analysis").addEventListener("click", async event => {
  const button = event.currentTarget;
  button.disabled = true;
  showLeakageMessage("Running provider analysis…");
  try {
    const csrf = await getCsrfToken();
    const analysis = await responseData(await fetch("/api/admin/leakage/analysis", {
      method: "POST",
      credentials: "same-origin",
      headers: { [csrf.headerName]: csrf.token }
    }));
    renderAnalysis(analysis);
    await loadHistory();
    showLeakageMessage("Analysis saved.", true);
  } catch (error) {
    showLeakageMessage(error.message);
  } finally {
    button.disabled = false;
  }
});

document.getElementById("admin-logout-button").addEventListener("click", async () => {
  try {
    const csrf = await getCsrfToken();
    await responseData(await fetch("/api/auth/logout", {
      method: "POST",
      credentials: "same-origin",
      headers: { [csrf.headerName]: csrf.token }
    }));
    window.location.replace("/login.html");
  } catch (error) {
    showLeakageMessage(error.message);
  }
});

loadLeakageDashboard();
