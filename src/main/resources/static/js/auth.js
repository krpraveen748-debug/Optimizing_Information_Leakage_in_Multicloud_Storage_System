async function getCsrfToken() {
  const response = await fetch("/api/auth/csrf", { credentials: "same-origin" });
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || "Could not start a secure session.");
  return data;
}

async function sendJson(path, method, data, csrf) {
  return fetch(path, {
    method,
    credentials: "same-origin",
    headers: { "Content-Type": "application/json", [csrf.headerName]: csrf.token },
    body: JSON.stringify(data)
  });
}

async function responseData(response) {
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    const fields = data.fields ? Object.values(data.fields).join(" ") : "";
    throw new Error([data.error, fields].filter(Boolean).join(" ") || `Request failed (${response.status}).`);
  }
  return data;
}

function showMessage(elementId, text, success = false) {
  const element = document.getElementById(elementId);
  if (!element) return;
  element.textContent = text;
  element.classList.toggle("success", success);
}

function formatBytes(bytes) {
  const value = Number(bytes);
  if (!Number.isFinite(value) || value < 0) return "Unavailable";
  if (value < 1024) return `${value.toLocaleString()} bytes`;
  const units = ["KiB", "MiB", "GiB", "TiB"];
  let size = value;
  let unitIndex = -1;
  do {
    size /= 1024;
    unitIndex += 1;
  } while (size >= 1024 && unitIndex < units.length - 1);
  return `${size.toFixed(size < 10 ? 2 : 1)} ${units[unitIndex]}`;
}

function setAccountLabel(profile) {
  const accountLabel = document.getElementById("account-label");
  if (accountLabel) accountLabel.textContent = profile.fullName;
}

function bindPasswordVisibility(inputId, toggleId) {
  const passwordInput = document.getElementById(inputId);
  const toggle = document.getElementById(toggleId);
  if (!passwordInput || !toggle) return;

  toggle.addEventListener("click", () => {
    const showPassword = passwordInput.type === "password";
    passwordInput.type = showPassword ? "text" : "password";
    toggle.textContent = showPassword ? "Hide" : "Show";
    toggle.setAttribute("aria-label", `${showPassword ? "Hide" : "Show"} password`);
    toggle.setAttribute("aria-pressed", String(showPassword));
  });
}

async function loadCurrentProfile() {
  await getCsrfToken();
  const response = await fetch("/api/auth/profile", { credentials: "same-origin" });
  if (response.status === 401) {
    window.location.replace("/login.html");
    return null;
  }
  return responseData(response);
}

const loginForm = document.getElementById("login-form");
bindPasswordVisibility("password", "password-visibility-toggle");
bindPasswordVisibility("password", "register-password-visibility-toggle");

if (loginForm) {
  loginForm.addEventListener("submit", async event => {
    event.preventDefault();
    showMessage("form-message", "Signing in…");
    try {
      const csrf = await getCsrfToken();
      const response = await fetch("/api/auth/login", {
        method: "POST",
        credentials: "same-origin",
        headers: {
          "Content-Type": "application/x-www-form-urlencoded",
          [csrf.headerName]: csrf.token
        },
        body: new URLSearchParams(new FormData(loginForm))
      });
      const profile = await responseData(response);
      window.location.assign(profile.role === "ADMIN" ? "/admin.html" : "/main.html");
    } catch (error) {
      showMessage("form-message", error.message);
    }
  });
}

const registerForm = document.getElementById("register-form");
if (registerForm) {
  registerForm.addEventListener("submit", async event => {
    event.preventDefault();
    showMessage("form-message", "Creating your account…");
    try {
      const csrf = await getCsrfToken();
      const data = Object.fromEntries(new FormData(registerForm));
      const profile = await responseData(await sendJson("/api/auth/register", "POST", data, csrf));
      registerForm.reset();
      showMessage(
        "form-message",
        `Account created. Your username is ${profile.username}. Save it for account recovery.`,
        true);
    } catch (error) {
      showMessage("form-message", error.message);
    }
  });
}

const activityRecords = [];
let activityPage = 0;
const activityPageSize = 15;

function renderActivityPage() {
  const body = document.getElementById("activity-body");
  if (!body) return;
  const query = document.getElementById("activity-search").value.trim().toLocaleLowerCase();
  const selectedType = document.getElementById("activity-filter").value;
  const filtered = activityRecords.filter(activity => {
    const searchable = [
      activity.id,
      activity.fileName,
      activity.activityType,
      activity.status,
      activity.technicalDetails || activity.description
    ].join(" ").toLocaleLowerCase();
    return (!selectedType || activity.activityType === selectedType)
      && (!query || searchable.includes(query));
  });
  const pageCount = Math.max(1, Math.ceil(filtered.length / activityPageSize));
  activityPage = Math.min(activityPage, pageCount - 1);
  const visible = filtered.slice(
    activityPage * activityPageSize,
    (activityPage + 1) * activityPageSize);
  body.replaceChildren();
  if (!visible.length) {
    const row = document.createElement("tr");
    const cell = document.createElement("td");
    cell.colSpan = 6;
    cell.className = "muted";
    cell.textContent = activityRecords.length
      ? "No recorded activities match these filters."
      : "No account activity has been recorded.";
    row.append(cell);
    body.append(row);
  }
  for (const activity of visible) {
    const row = document.createElement("tr");
    const values = [
      activity.id,
      activity.fileName || "—",
      activity.activityType,
      formatActivityTime(activity.activityTime),
      activity.status || "RECORDED",
      activity.technicalDetails || activity.description
    ];
    for (const value of values) {
      const cell = document.createElement("td");
      cell.textContent = String(value ?? "—");
      row.append(cell);
    }
    body.append(row);
  }
  document.getElementById("activity-page-label").textContent =
    `Page ${activityPage + 1} of ${pageCount} · ${filtered.length} matching records`;
  document.getElementById("activity-previous").disabled = activityPage === 0;
  document.getElementById("activity-next").disabled = activityPage >= pageCount - 1;
}

function formatActivityTime(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? String(value ?? "—") : date.toLocaleString();
}

function populateActivityTypes() {
  const filter = document.getElementById("activity-filter");
  if (!filter) return;
  const selected = filter.value;
  filter.replaceChildren(new Option("All activity types", ""));
  for (const type of [...new Set(activityRecords.map(item => item.activityType))].sort()) {
    filter.append(new Option(type, type));
  }
  filter.value = selected;
}

async function loadActivityPage() {
  try {
    const profile = await loadCurrentProfile();
    if (!profile) return;
    setAccountLabel(profile);
    const profileName = document.getElementById("profile-name");
    const profileEmail = document.getElementById("profile-email");
    const profileUsername = document.getElementById("profile-username");
    if (profileUsername) profileUsername.value = profile.username;
    if (profileName) profileName.value = profile.fullName;
    if (profileEmail) profileEmail.value = profile.email;
    const response = await fetch("/api/dashboard/activity?limit=100", {
      credentials: "same-origin"
    });
    const records = await responseData(response);
    activityRecords.splice(0, activityRecords.length, ...records);
    populateActivityTypes();
    renderActivityPage();
  } catch (error) {
    showMessage("page-message", error.message);
    const body = document.getElementById("activity-body");
    if (body) {
      body.replaceChildren();
      const row = document.createElement("tr");
      const cell = document.createElement("td");
      cell.colSpan = 6;
      cell.textContent = error.message;
      row.append(cell);
      body.append(row);
    }
  }
}

for (const id of ["activity-search", "activity-filter"]) {
  const control = document.getElementById(id);
  if (control) {
    control.addEventListener("input", () => {
      activityPage = 0;
      renderActivityPage();
    });
    control.addEventListener("change", () => {
      activityPage = 0;
      renderActivityPage();
    });
  }
}

document.getElementById("activity-previous")?.addEventListener("click", () => {
  activityPage = Math.max(0, activityPage - 1);
  renderActivityPage();
});
document.getElementById("activity-next")?.addEventListener("click", () => {
  activityPage += 1;
  renderActivityPage();
});

const profileForm = document.getElementById("profile-form");
if (profileForm) {
  profileForm.addEventListener("submit", async event => {
    event.preventDefault();
    try {
      const csrf = await getCsrfToken();
      const data = Object.fromEntries(new FormData(profileForm));
      const profile = await responseData(await sendJson("/api/auth/profile", "PUT", data, csrf));
      setAccountLabel(profile);
      showMessage("profile-message", "Profile updated.", true);
    } catch (error) {
      showMessage("profile-message", error.message);
    }
  });
}

let mainFiles = [];
let completedSimilarity = null;

function mainFileById(id) {
  return mainFiles.find(file => String(file.id) === String(id));
}

function clearWorkflowResults() {
  completedSimilarity = null;
  document.getElementById("similarity-results").replaceChildren();
  document.getElementById("placement-section").hidden = true;
  document.getElementById("final-results-section").hidden = true;
  document.getElementById("placement-results").replaceChildren();
  document.getElementById("chunk-allocation").replaceChildren();
  document.getElementById("final-results").replaceChildren();
}

function setSelectPlaceholder(select, text) {
  select.replaceChildren(new Option(text, ""));
  select.disabled = true;
}

function populateSimilaritySelectors(files, preferredSourceId) {
  const source = document.getElementById("similarity-file");
  const candidate = document.getElementById("similarity-candidate");
  if (!source || !candidate) return;
  const compareButton = document.querySelector("#similarity-form button[type='submit']");
  const previousSource = preferredSourceId ?? source.value;
  const previousCandidate = candidate.value;
  mainFiles = files;
  if (!files.length) {
    setSelectPlaceholder(source, "No uploaded files");
    setSelectPlaceholder(candidate, "No uploaded files");
    compareButton.disabled = true;
    updateSelectedFileActions();
    showMessage("similarity-message", "Upload at least two files to run a comparison.");
    return;
  }
  for (const select of [source, candidate]) {
    select.disabled = false;
    select.replaceChildren();
    for (const file of files) {
      select.append(new Option(file.original_name, String(file.id)));
    }
  }
  candidate.disabled = files.length < 2;
  source.value = files.some(file => String(file.id) === String(previousSource))
    ? String(previousSource)
    : String(files[0].id);
  const anotherFile = files.find(file => String(file.id) !== source.value);
  candidate.value = files.some(file =>
    String(file.id) === String(previousCandidate) && String(file.id) !== source.value)
    ? String(previousCandidate)
    : String(anotherFile?.id ?? files[0].id);
  updateSimilarityControls();
  if (files.length < 2) {
    showMessage("similarity-message", "Upload a second different file to enable analysis.");
  } else {
    showMessage("similarity-message", "Choose two different uploaded files, then run the analysis.");
  }
}

function updateSimilarityControls() {
  const source = document.getElementById("similarity-file");
  const candidate = document.getElementById("similarity-candidate");
  if (!source || !candidate) return;
  if (mainFiles.length > 1 && source.value === candidate.value) {
    const replacement = mainFiles.find(file => String(file.id) !== source.value);
    if (replacement) candidate.value = String(replacement.id);
  }
  for (const option of source.options) option.disabled = option.value === candidate.value;
  for (const option of candidate.options) option.disabled = option.value === source.value;
  document.querySelector("#similarity-form button[type='submit']").disabled =
    mainFiles.length < 2 || source.value === candidate.value;
  updateSelectedFileActions();
}

function updateSelectedFileActions() {
  const source = document.getElementById("similarity-file");
  const actions = document.getElementById("selected-file-actions");
  const file = source && mainFileById(source.value);
  if (!actions) return;
  actions.hidden = !file;
  if (!file) return;
  document.getElementById("download-selected-file").href =
    `/api/files/${encodeURIComponent(file.id)}/download`;
  const deleteButton = document.getElementById("delete-selected-file");
  deleteButton.dataset.fileId = file.id;
  deleteButton.dataset.fileName = file.original_name;
}

for (const id of ["similarity-file", "similarity-candidate"]) {
  const select = document.getElementById(id);
  if (select) {
    select.addEventListener("change", () => {
      clearWorkflowResults();
      updateSimilarityControls();
      if (mainFiles.length > 1) {
        showMessage("similarity-message", "Run the analysis to display a new result.");
      }
    });
  }
}

async function refreshMainFiles(preferredSourceId) {
  const response = await fetch("/api/files", { credentials: "same-origin" });
  const files = await responseData(response);
  populateSimilaritySelectors(files, preferredSourceId);
}

function setProcessingStage(name, status, text) {
  const item = document.querySelector(`[data-stage="${name}"]`);
  if (!item) return;
  item.dataset.status = status;
  item.querySelector("strong").textContent = text;
}

function renderProcessingResult(result) {
  setProcessingStage("validation", "complete",
    result.validationStatus === "PASSED" ? "Passed server validation" : "Status unavailable");
  setProcessingStage("chunking", "complete",
    `${Number(result.chunkCount).toLocaleString()} chunks generated by the server`);
  setProcessingStage("hashing", "complete",
    `${result.hashAlgorithm || "SHA-256"} checksum: ${result.sha256 || "Unavailable"}`);
  const encryption = result.encryptionStatus || "UNKNOWN";
  const algorithm = result.encryptionAlgorithm || "Algorithm unavailable";
  setProcessingStage("encryption", encryption === "ENCRYPTED" || encryption === "NOT_APPLICABLE"
    ? "complete" : "error", `${encryption} · ${algorithm}`);
  setProcessingStage("deduplication", "complete",
    `${Number(result.deduplicatedChunks || 0).toLocaleString()} repeated chunks reused`);
}

function clearProcessingStages() {
  for (const stage of ["validation", "chunking", "hashing", "encryption", "deduplication"]) {
    setProcessingStage(stage, "", "Waiting for upload");
  }
}

function uploadWithProgress(file, strategy, csrf, onProgress) {
  return new Promise((resolve, reject) => {
    const request = new XMLHttpRequest();
    const formData = new FormData();
    formData.append("file", file);
    request.open("POST", `/api/files/upload?strategy=${encodeURIComponent(strategy)}`);
    request.withCredentials = true;
    request.setRequestHeader(csrf.headerName, csrf.token);
    request.upload.addEventListener("progress", event => {
      if (event.lengthComputable) onProgress(event.loaded, event.total);
    });
    request.addEventListener("load", () => {
      let data;
      try {
        data = JSON.parse(request.responseText);
      } catch {
        reject(new Error(request.status >= 200 && request.status < 300
          ? "The server returned an invalid upload response."
          : `Upload failed (${request.status}).`));
        return;
      }
      if (request.status < 200 || request.status >= 300) {
        reject(new Error(data.error || `Upload failed (${request.status}).`));
        return;
      }
      resolve(data);
    });
    request.addEventListener("error", () => reject(new Error("Network error while uploading the file.")));
    request.addEventListener("abort", () => reject(new Error("File upload was cancelled.")));
    request.send(formData);
  });
}

function displaySelectedFile(file) {
  const details = document.getElementById("selected-file-details");
  if (!file) {
    details.hidden = true;
    return;
  }
  details.hidden = false;
  document.getElementById("selected-file-name").textContent = file.name;
  document.getElementById("selected-file-type").textContent =
    file.type || "Not supplied by the browser";
  document.getElementById("selected-file-size").textContent = formatBytes(file.size);
  showMessage("upload-message", "");
}

const fileInput = document.getElementById("file");
const fileDropzone = document.getElementById("file-dropzone");
if (fileInput) {
  fileInput.addEventListener("change", () => displaySelectedFile(fileInput.files[0]));
}
if (fileDropzone && fileInput) {
  for (const eventName of ["dragenter", "dragover"]) {
    fileDropzone.addEventListener(eventName, event => {
      event.preventDefault();
      fileDropzone.classList.add("drag-over");
    });
  }
  for (const eventName of ["dragleave", "drop"]) {
    fileDropzone.addEventListener(eventName, event => {
      event.preventDefault();
      fileDropzone.classList.remove("drag-over");
    });
  }
  fileDropzone.addEventListener("drop", event => {
    const files = [...(event.dataTransfer?.files || [])];
    if (files.length !== 1) {
      showMessage("upload-message", "Choose one file per upload; the server currently accepts one file at a time.");
      return;
    }
    const transfer = new DataTransfer();
    transfer.items.add(files[0]);
    fileInput.files = transfer.files;
    displaySelectedFile(files[0]);
  });
}

const uploadForm = document.getElementById("upload-form");
if (uploadForm) {
  uploadForm.addEventListener("submit", async event => {
    event.preventDefault();
    const file = fileInput.files[0];
    if (!file) return;
    clearWorkflowResults();
    clearProcessingStages();
    const uploadButton = document.getElementById("upload-button");
    const progressWrap = document.getElementById("upload-progress-wrap");
    const progress = document.getElementById("upload-progress");
    const progressLabel = document.getElementById("upload-progress-label");
    uploadButton.disabled = true;
    progressWrap.hidden = false;
    progress.value = 0;
    progressLabel.textContent = "Starting transfer…";
    showMessage("upload-message", "Uploading file; server processing will be confirmed after completion.");
    try {
      const csrf = await getCsrfToken();
      const strategy = document.getElementById("upload-placement-strategy").value;
      const result = await uploadWithProgress(file, strategy, csrf, (loaded, total) => {
        const percentage = total ? Math.round((loaded / total) * 100) : 0;
        progress.value = percentage;
        progressLabel.textContent = `${percentage}% transferred (${formatBytes(loaded)} of ${formatBytes(total)})`;
      });
      renderProcessingResult(result);
      progress.value = 100;
      progressLabel.textContent = "Transfer complete; server processing confirmed.";
      showMessage("upload-message", `Processed ${result.fileName} successfully.`, true);
      fileInput.value = "";
      let refreshed = false;
      try {
        await refreshMainFiles(result.fileId);
        refreshed = true;
      } catch (error) {
        showMessage("main-message", `Upload succeeded, but the file selector could not be refreshed: ${error.message}`);
      }
      if (refreshed) {
        showMessage("similarity-message",
          mainFiles.length < 2
            ? "Upload a second different file to run similarity analysis."
            : "Choose two different uploaded files, then run the analysis.");
      }
    } catch (error) {
      showMessage("upload-message", error.message);
      for (const stage of ["validation", "chunking", "hashing", "encryption", "deduplication"]) {
        setProcessingStage(stage, "", "Not confirmed; upload did not complete successfully");
      }
    } finally {
      uploadButton.disabled = false;
    }
  });
}

function appendResultCard(container, label, value, detail) {
  const card = document.createElement("article");
  card.className = "result-card";
  const heading = document.createElement("strong");
  heading.textContent = label;
  const text = document.createElement("span");
  text.textContent = value;
  card.append(heading, text);
  if (detail) {
    const description = document.createElement("p");
    description.className = "muted";
    description.textContent = detail;
    card.append(description);
  }
  container.append(card);
}

function renderSimilarityResult(result) {
  const container = document.getElementById("similarity-results");
  container.replaceChildren();
  const source = mainFileById(result.fileId);
  const candidate = mainFileById(result.candidateFileId);
  appendResultCard(container, "Files compared",
    `${source?.original_name || `File ${result.fileId}`} ↔ ${candidate?.original_name || `File ${result.candidateFileId}`}`,
    result.status);
  if (result.estimatedJaccardSimilarity === null) {
    appendResultCard(container, "MinHash similarity", "Unavailable", result.message);
  } else {
    appendResultCard(container, "Estimated similarity",
      `${Number(result.similarityPercent).toFixed(2)}%`,
      `MinHash estimate using ${result.minHashPermutationCount} permutations; this is not exact Jaccard.`);
  }
  appendResultCard(container, "Exact matching chunk fingerprints",
    Number(result.exactMatchingChunkCount).toLocaleString(),
    `${result.sourceChunkCount} source chunks · ${result.candidateChunkCount} comparison chunks`);
  appendResultCard(container, "Bloom Filter positives",
    Number(result.bloomPositiveCount).toLocaleString(),
    `Estimated false-positive probability: ${(Number(result.bloomExpectedFalsePositiveRate) * 100).toFixed(2)}%.`);
  showMessage("similarity-message", result.message, result.status === "AVAILABLE");
}

function renderPlacement(details) {
  const providerCounts = new Map([
    ["cloud1", { chunks: 0, bytes: 0 }],
    ["cloud2", { chunks: 0, bytes: 0 }],
    ["cloud3", { chunks: 0, bytes: 0 }]
  ]);
  for (const chunk of details.chunks || []) {
    const provider = String(chunk.provider);
    const measurement = providerCounts.get(provider) || { chunks: 0, bytes: 0 };
    measurement.chunks += 1;
    measurement.bytes += Number(chunk.chunk_size || 0);
    providerCounts.set(provider, measurement);
  }
  const distribution = document.getElementById("placement-results");
  distribution.replaceChildren();
  for (const [provider, measurement] of providerCounts) {
    appendResultCard(distribution, provider,
      `${measurement.chunks.toLocaleString()} chunks`,
      `${formatBytes(measurement.bytes)} of logical chunk data · simulated provider`);
  }

  const allocation = document.getElementById("chunk-allocation");
  allocation.replaceChildren();
  const chunks = details.chunks || [];
  if (!chunks.length) {
    const empty = document.createElement("p");
    empty.className = "muted";
    empty.textContent = "This file has no chunks to allocate.";
    allocation.append(empty);
    return [...providerCounts.entries()]
      .filter(([, measurement]) => measurement.chunks > 0)
      .map(([provider]) => provider);
  }
  const table = document.createElement("table");
  table.className = "data-table";
  const head = document.createElement("thead");
  const headerRow = document.createElement("tr");
  for (const title of ["Chunk", "Provider", "Size", "Encryption", "Deduplicated"]) {
    const cell = document.createElement("th");
    cell.scope = "col";
    cell.textContent = title;
    headerRow.append(cell);
  }
  head.append(headerRow);
  const body = document.createElement("tbody");
  for (const chunk of chunks) {
    const row = document.createElement("tr");
    const values = [
      Number(chunk.chunk_index) + 1,
      chunk.provider,
      formatBytes(chunk.chunk_size),
      chunk.encryption_status,
      chunk.deduplicated ? "Yes" : "No"
    ];
    for (const value of values) {
      const cell = document.createElement("td");
      cell.textContent = String(value ?? "—");
      row.append(cell);
    }
    body.append(row);
  }
  table.append(head, body);
  allocation.append(table);
  return [...providerCounts.keys()];
}

function appendDefinition(container, label, value) {
  const term = document.createElement("dt");
  term.textContent = label;
  const description = document.createElement("dd");
  description.textContent = String(value ?? "Unavailable");
  container.append(term, description);
}

function renderFinalResults(details, providerNames) {
  const results = document.getElementById("final-results");
  results.replaceChildren();
  const similarity = completedSimilarity;
  const similarityText = !similarity
    ? "Not analyzed"
    : similarity.estimatedJaccardSimilarity === null
      ? `${similarity.status}: ${similarity.message}`
      : `${Number(similarity.similarityPercent).toFixed(2)}% estimated · `
        + `${similarity.exactMatchingChunkCount} exact matching chunk fingerprints`;
  appendDefinition(results, "Original file name", details.original_name);
  appendDefinition(results, "Original file size", formatBytes(details.size_bytes));
  appendDefinition(results, "Number of chunks", Number(details.total_chunks).toLocaleString());
  appendDefinition(results, "Encryption status", details.encryptionStatus);
  appendDefinition(results, "Deduplication status",
    `${Number(details.deduplicatedChunks || 0).toLocaleString()} shared/reused chunks`);
  appendDefinition(results, "Similarity analysis result", similarityText);
  appendDefinition(results, "Storage providers",
    providerNames.length ? providerNames.join(", ") : "No chunk providers");
  appendDefinition(results, "Placement strategy",
    details.placement_strategy || "Not recorded for this previously stored file");
  appendDefinition(results, "Storage placement status",
    details.upload_status === "UPLOADED" ? "Completed; persisted chunk assignments verified" : details.upload_status);
}

async function loadPlacementAndFinalResults(fileId) {
  document.getElementById("placement-section").hidden = false;
  document.getElementById("final-results-section").hidden = true;
  document.getElementById("placement-status").textContent = "Loading persisted chunk assignments…";
  try {
    const response = await fetch(`/api/files/${encodeURIComponent(fileId)}`, {
      credentials: "same-origin"
    });
    const details = await responseData(response);
    renderProcessingResult({
      validationStatus: details.upload_status === "UPLOADED" ? "PASSED" : details.upload_status,
      chunkCount: details.total_chunks,
      hashAlgorithm: "SHA-256",
      sha256: details.file_sha256,
      encryptionStatus: details.encryptionStatus,
      encryptionAlgorithm: details.encryptionStatus === "ENCRYPTED" ? "AES-256-GCM" : details.encryptionStatus,
      deduplicatedChunks: details.deduplicatedChunks
    });
    const providerNames = renderPlacement(details);
    document.getElementById("placement-status").textContent =
      `Placement confirmed for ${details.original_name} · strategy: `
      + `${details.placement_strategy || "not recorded"} · ${details.upload_status}.`;
    renderFinalResults(details, providerNames);
    document.getElementById("final-results-section").hidden = false;
  } catch (error) {
    showMessage("placement-status", `Could not load actual placement details: ${error.message}`);
    document.getElementById("placement-status").classList.add("message");
  }
}

const similarityForm = document.getElementById("similarity-form");
if (similarityForm) {
  similarityForm.addEventListener("submit", async event => {
    event.preventDefault();
    clearWorkflowResults();
    showMessage("similarity-message", "Running similarity analysis…");
    const button = similarityForm.querySelector("button[type='submit']");
    button.disabled = true;
    try {
      const csrf = await getCsrfToken();
      const fileId = Number(document.getElementById("similarity-file").value);
      const candidateFileId = Number(document.getElementById("similarity-candidate").value);
      const response = await fetch("/api/similarity/compare", {
        method: "POST",
        credentials: "same-origin",
        headers: { "Content-Type": "application/json", [csrf.headerName]: csrf.token },
        body: JSON.stringify({ fileId, candidateFileId })
      });
      completedSimilarity = await responseData(response);
      renderSimilarityResult(completedSimilarity);
      await loadPlacementAndFinalResults(fileId);
    } catch (error) {
      showMessage("similarity-message", error.message);
    } finally {
      updateSimilarityControls();
    }
  });
}

document.getElementById("delete-selected-file")?.addEventListener("click", async event => {
  const button = event.currentTarget;
  const fileId = button.dataset.fileId;
  const filename = button.dataset.fileName;
  if (!window.confirm(`Delete "${filename}"?`)) return;
  button.disabled = true;
  try {
    const csrf = await getCsrfToken();
    const response = await fetch(`/api/files/${encodeURIComponent(fileId)}`, {
      method: "DELETE",
      credentials: "same-origin",
      headers: { [csrf.headerName]: csrf.token }
    });
    if (!response.ok) await responseData(response);
    clearWorkflowResults();
    clearProcessingStages();
    showMessage("selected-file-message", `Deleted ${filename}.`, true);
    await refreshMainFiles();
  } catch (error) {
    showMessage("selected-file-message", error.message);
  } finally {
    button.disabled = false;
  }
});

const logoutButton = document.getElementById("logout-button");
if (logoutButton) {
  logoutButton.addEventListener("click", async () => {
    try {
      const csrf = await getCsrfToken();
      const response = await fetch("/api/auth/logout", {
        method: "POST",
        credentials: "same-origin",
        headers: { [csrf.headerName]: csrf.token }
      });
      await responseData(response);
      window.location.replace("/login.html");
    } catch (error) {
      const messageId = document.getElementById("main-message") ? "main-message" : "page-message";
      showMessage(messageId, error.message);
    }
  });
}

async function loadMainPage() {
  try {
    const profile = await loadCurrentProfile();
    if (!profile) return;
    setAccountLabel(profile);
    await refreshMainFiles();
  } catch (error) {
    showMessage("main-message", error.message);
  }
}

if (document.getElementById("upload-form")) loadMainPage();
if (document.getElementById("activity-body")) loadActivityPage();
