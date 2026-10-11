// ============================================
//  ANA UYGULAMA MANTIĞI
// ============================================

let uploadedFiles   = [];
let analysisResults = [];
let preparedCache   = new Map(); // dosya index -> hazırlanmış görüntü

// ──────── BAŞLANGIÇ ────────
document.addEventListener('DOMContentLoaded', () => {
  setupDropZone();
  restoreSettings();
  refreshLogView();
  Logger.info('system', 'Uygulama başlatıldı');
});

// ──────── AYARLAR (API KEY, MODEL, GITHUB) ────────
function restoreSettings() {
  const key = getApiKey();
  if (key) {
    document.getElementById('apiKey').value = key;
    showStatus('apiStatus', '✅ Kayıtlı API key bulundu', 'success');
  }
  document.getElementById('modelName').value = getModel();
  document.getElementById('ghRepo').value    = localStorage.getItem(STORAGE_KEYS.ghRepo) || '';
  document.getElementById('ghToken').value   = localStorage.getItem(STORAGE_KEYS.ghToken) || '';
}

function saveApiKey() {
  const key = document.getElementById('apiKey').value.trim();
  if (!key.startsWith('sk-ant-')) {
    showStatus('apiStatus', '❌ Geçersiz format! sk-ant- ile başlamalı', 'error');
    Logger.warn('system', 'Geçersiz API key formatı girildi');
    return;
  }
  localStorage.setItem(STORAGE_KEYS.apiKey, key);
  showStatus('apiStatus', '✅ Kaydedildi!', 'success');
  Logger.info('system', 'API key kaydedildi (değer günlüğe yazılmaz)');
}

function saveSettings() {
  const model = document.getElementById('modelName').value.trim();
  const repo  = document.getElementById('ghRepo').value.trim();
  const token = document.getElementById('ghToken').value.trim();
  if (model) localStorage.setItem(STORAGE_KEYS.model, model);
  if (repo)  localStorage.setItem(STORAGE_KEYS.ghRepo, repo);
  if (token) localStorage.setItem(STORAGE_KEYS.ghToken, token);
  showStatus('settingsStatus', '✅ Ayarlar kaydedildi', 'success');
  Logger.info('system', 'Ayarlar kaydedildi', { model: model || '(varsayılan)', ghRepo: repo || '(yok)' });
}

// ──────── DROP ZONE ────────
function setupDropZone() {
  const zone  = document.getElementById('dropZone');
  const input = document.getElementById('fileInput');

  zone.addEventListener('click', () => input.click());
  input.addEventListener('change', e => addFiles(e.target.files));

  zone.addEventListener('dragover', e => {
    e.preventDefault();
    zone.classList.add('dragover');
  });
  zone.addEventListener('dragleave', () => zone.classList.remove('dragover'));
  zone.addEventListener('drop', e => {
    e.preventDefault();
    zone.classList.remove('dragover');
    addFiles(e.dataTransfer.files);
  });
}

function addFiles(fileList) {
  const files = Array.from(fileList);
  let accepted = 0;
  files.forEach(file => {
    if (!file.type.startsWith('image/')) {
      Logger.warn('upload', `Görüntü olmayan dosya atlandı: ${file.name}`, { type: file.type });
      return;
    }
    uploadedFiles.push(file);
    renderPreview(file, uploadedFiles.length - 1);
    accepted++;
  });
  Logger.info('upload', `${accepted}/${files.length} dosya eklendi`, { total: uploadedFiles.length });
}

function renderPreview(file, index) {
  const grid = document.getElementById('previewGrid');
  const item = document.createElement('div');
  item.className = 'preview-item';
  item.id = `preview-${index}`;

  const reader = new FileReader();
  reader.onload = e => {
    item.innerHTML = `
      <img src="${e.target.result}" alt="Görüntü ${index + 1}">
      <div class="label">📸 ${index + 1}</div>
    `;
  };
  reader.onerror = () => Logger.error('upload', `Önizleme okunamadı: ${file.name}`);
  reader.readAsDataURL(file);
  grid.appendChild(item);
}

// ──────── ANALİZ ────────
async function analyzeAll() {
  if (uploadedFiles.length === 0) {
    alert('Lütfen önce bir görüntü yükleyin!');
    return;
  }
  if (!getApiKey()) {
    alert('Lütfen önce API Key girin!');
    Logger.warn('system', 'Analiz başlatılamadı: API key yok');
    return;
  }

  const mode      = document.getElementById('analysisMode').value;
  const isCompare = mode === 'compare';

  analysisResults = [];
  preparedCache   = new Map();
  document.getElementById('progressSection').style.display = 'block';
  document.getElementById('resultsSection').style.display  = 'none';
  document.getElementById('notesContainer').innerHTML      = '';
  Logger.info('system', 'Analiz başladı', { mode, images: uploadedFiles.length, model: getModel() });

  let okCount = 0, failCount = 0, unreadable = 0;

  for (let i = 0; i < uploadedFiles.length; i++) {
    const file = uploadedFiles[i];
    updateProgress(i, uploadedFiles.length, `Analiz ediliyor: ${i + 1}/${uploadedFiles.length} — ${file.name}`);

    try {
      const images = [await getPrepared(i)];
      // Karşılaştırma modu: önceki görüntü de gönderilir
      if (isCompare && i > 0) images.unshift(await getPrepared(i - 1));

      const result = await analyzeImages(images, mode);
      result._imageUrl  = URL.createObjectURL(file);
      result._filename  = file.name;
      result._index     = i;
      result._timestamp = new Date().toISOString();
      analysisResults.push(result);

      if (result.parse_error || result.readability?.readable === false) {
        unreadable++;
        document.getElementById(`preview-${i}`)?.classList.add('warn');
        Logger.warn('screen', `Ekran tam okunamadı: ${file.name}`, {
          reason: result.readability?.reason || 'JSON ayrıştırılamadı',
          unclear_regions: result.readability?.unclear_regions || [],
          blocking_elements: result.readability?.blocking_elements || []
        });
      } else {
        Logger.info('screen', `Ekran okundu: ${file.name}`, {
          scene_type: result.scene_type,
          game_phase: result.coc_analysis?.game_phase,
          confidence: result.readability?.confidence,
          durationMs: result._meta?.durationMs
        });
      }

      document.getElementById(`preview-${i}`)?.classList.add('done');
      renderNote(result, document.getElementById('notesContainer'));
      okCount++;
    } catch (err) {
      failCount++;
      Logger.error('system', `Görüntü ${i + 1} analiz edilemedi: ${file.name}`, err);
      document.getElementById(`preview-${i}`)?.classList.add('error');
      const failed = { _error: err.message, _index: i, _filename: file.name };
      analysisResults.push(failed);
      renderNote(failed, document.getElementById('notesContainer'));
    }
  }

  updateProgress(uploadedFiles.length, uploadedFiles.length, '✅ Tüm analizler tamamlandı!');
  document.getElementById('resultsSection').style.display = 'block';
  Logger.info('system', 'Analiz bitti', { ok: okCount, failed: failCount, unreadable });
  saveToHistory({ ok: okCount, failed: failCount, unreadable, mode });
  refreshLogView();
}

// Görüntüyü bir kez hazırlar, önbellekten döner
async function getPrepared(index) {
  if (!preparedCache.has(index)) {
    preparedCache.set(index, await prepareImage(uploadedFiles[index]));
  }
  return preparedCache.get(index);
}

// ──────── YARDIMCI ────────
function updateProgress(current, total, text) {
  const pct = total > 0 ? Math.round((current / total) * 100) : 0;
  document.getElementById('progressFill').style.width = pct + '%';
  document.getElementById('progressText').textContent = text;
}

function showStatus(id, msg, type) {
  const el = document.getElementById(id);
  el.textContent = msg;
  el.style.color = type === 'error' ? '#ff4444' : '#00ff88';
}

function saveToHistory(summary) {
  const stored = loadJSON(STORAGE_KEYS.history, []);
  stored.push({ date: new Date().toISOString(), ...summary, results: analysisResults.length });
  if (stored.length > 50) stored.shift();
  saveJSON(STORAGE_KEYS.history, stored);
}

function refreshLogView() {
  renderLogView(Logger.all(), document.getElementById('logView'));
}

// ──────── OLAY GÜNLÜĞÜ ────────
function exportLogMarkdown() {
  downloadFile(Logger.toMarkdown(Logger.all()), `coc-olay-gunlugu-${Date.now()}.md`, 'text/markdown');
}

function exportLogJSON() {
  downloadFile(JSON.stringify(Logger.all(), null, 2), `coc-olay-gunlugu-${Date.now()}.json`, 'application/json');
}

function clearLogs() {
  if (!confirm('Tüm olay günlüğü silinsin mi?')) return;
  Logger.clear();
  refreshLogView();
}

function filterLogs() {
  const level = document.getElementById('logLevel').value || null;
  const cat   = document.getElementById('logCategory').value || null;
  renderLogView(Logger.filter(Logger.all(), { level, category: cat }), document.getElementById('logView'));
}

// ──────── EXPORT ────────
function exportMarkdown() {
  let md = `# ⚔️ CoC Savaş Analiz Raporu\n`;
  md += `**Tarih:** ${new Date().toLocaleString('tr-TR')}\n\n---\n\n`;

  analysisResults.forEach((r, i) => {
    md += `## 📸 Görüntü ${i + 1} — ${r._filename || 'bilinmiyor'}\n\n`;
    if (r._error) { md += `❌ Hata: ${r._error}\n\n`; return; }
    if (r.readability?.readable === false || r.parse_error) {
      md += `⚠️ **Ekran tam okunamadı.** ${r.readability?.reason || ''}\n\n`;
    }
    md += `### 🔍 Genel Bakış\n${r.screen_overview?.description || 'Açıklama yok'}\n\n`;
    if (r.strategy_notes) {
      md += `### ⚔️ Strateji Notları\n`;
      md += `**Saldırı Düzeni:** ${r.strategy_notes.attack_pattern || '-'}\n`;
      if (r.strategy_notes.recommendations?.length) {
        md += `\n**Öneriler:**\n`;
        r.strategy_notes.recommendations.forEach(rec => { md += `- ${rec}\n`; });
      }
      if (r.strategy_notes.warnings?.length) {
        md += `\n**⚠️ Uyarılar:**\n`;
        r.strategy_notes.warnings.forEach(w => { md += `- ${w}\n`; });
      }
    }
    md += `\n---\n\n`;
  });

  downloadFile(md, `coc-savas-raporu-${Date.now()}.md`, 'text/markdown');
}

function exportJSON() {
  const data = {
    exported_at: new Date().toISOString(),
    total_images: analysisResults.length,
    results: analysisResults.map(r => {
      const clean = { ...r };
      delete clean._imageUrl; // blob URL'yi dahil etme
      return clean;
    })
  };
  downloadFile(JSON.stringify(data, null, 2), `coc-analiz-${Date.now()}.json`, 'application/json');
}

function copyAll() {
  const text = analysisResults.map((r, i) => {
    if (r._error) return `Görüntü ${i + 1}: HATA - ${r._error}`;
    return `=== Görüntü ${i + 1} ===\n${r.screen_overview?.description || ''}\n${r.strategy_notes?.attack_pattern || ''}`;
  }).join('\n\n');

  navigator.clipboard.writeText(text)
    .then(() => alert('✅ Panoya kopyalandı!'))
    .catch(err => Logger.error('ui', 'Panoya kopyalanamadı', err));
}

function downloadFile(content, filename, type) {
  const blob = new Blob([content], { type });
  const a    = document.createElement('a');
  a.href     = URL.createObjectURL(blob);
  a.download = filename;
  a.click();
}

// ──────── GITHUB'A KAYIT (PAT ile, Contents API) ────────
// Token tarayıcıda localStorage'da tutulur. Sadece kendi reponuza, sadece
// "Contents: Read and write" yetkili fine-grained token kullanın.
async function saveToGitHub() {
  const repo  = localStorage.getItem(STORAGE_KEYS.ghRepo);
  const token = localStorage.getItem(STORAGE_KEYS.ghToken);
  if (!repo || !token) {
    alert('Önce Ayarlar bölümünde GitHub repo (kullanici/repo) ve token girin.');
    return;
  }
  if (!analysisResults.length) {
    alert('Kaydedilecek analiz sonucu yok.');
    return;
  }

  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  const notesPath = `notes/battle_${stamp}.json`;
  const logPath   = `notes/logs/gunluk_${stamp}.md`;
  const notesBody = JSON.stringify({ exported_at: new Date().toISOString(), results: analysisResults.map(({ _imageUrl, ...rest }) => rest) }, null, 2);

  try {
    await putGitHubFile(repo, token, notesPath, notesBody, 'Savaş notları eklendi');
    await putGitHubFile(repo, token, logPath, Logger.toMarkdown(Logger.all()), 'Olay günlüğü eklendi');
    alert(`✅ GitHub'a kaydedildi:\n${notesPath}\n${logPath}`);
    Logger.info('github', 'Notlar ve günlük GitHub\'a kaydedildi', { repo, notesPath, logPath });
  } catch (err) {
    Logger.error('github', 'GitHub kaydı başarısız', err);
    alert(`GitHub kaydı başarısız: ${err.message}`);
  }
}

async function putGitHubFile(repo, token, path, content, message) {
  const bytes = new TextEncoder().encode(content);
  let binary = '';
  bytes.forEach(b => { binary += String.fromCharCode(b); });
  const res = await fetch(`https://api.github.com/repos/${repo}/contents/${path}`, {
    method: 'PUT',
    headers: {
      'Accept': 'application/vnd.github+json',
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({ message, content: btoa(binary) })
  });
  if (!res.ok) {
    const t = await res.text();
    throw new Error(`GitHub ${res.status}: ${t.slice(0, 200)}`);
  }
}
