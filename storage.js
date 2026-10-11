// ============================================
//  DEPOLAMA + OLAY GÜNLÜĞÜ (LOGGER)
// ============================================

const STORAGE_KEYS = {
  apiKey:   'coc_analyzer_api_key',
  model:    'coc_analyzer_model',
  history:  'coc_analyzer_history',
  logs:     'coc_analyzer_logs',
  ghToken:  'coc_analyzer_gh_token',
  ghRepo:   'coc_analyzer_gh_repo'
};

const MAX_LOG_ENTRIES = 500;

function loadJSON(key, fallback) {
  try {
    const raw = localStorage.getItem(key);
    return raw ? JSON.parse(raw) : fallback;
  } catch {
    return fallback;
  }
}

function saveJSON(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value));
    return true;
  } catch (e) {
    console.warn('localStorage yazılamadı:', key, e);
    return false;
  }
}

// Hata nesnelerini loglanabilir düz nesneye çevirir
function serializeError(err) {
  if (!err) return null;
  if (err instanceof Error) {
    return { name: err.name, message: err.message, stack: (err.stack || '').split('\n').slice(0, 6).join('\n') };
  }
  if (typeof err === 'object') {
    try { return JSON.parse(JSON.stringify(err)); } catch { return { value: String(err) }; }
  }
  return { value: String(err) };
}

const Logger = {
  // level: 'debug' | 'info' | 'warn' | 'error'
  // category: 'upload' | 'image' | 'api' | 'parse' | 'screen' | 'ui' | 'github' | 'system'
  write(level, category, message, details) {
    const entry = {
      id: `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
      time: new Date().toISOString(),
      level,
      category,
      message,
      details: details === undefined ? null : details
    };
    const logs = loadJSON(STORAGE_KEYS.logs, []);
    logs.push(entry);
    while (logs.length > MAX_LOG_ENTRIES) logs.shift();
    saveJSON(STORAGE_KEYS.logs, logs);

    const consoleFn = level === 'error' ? console.error : level === 'warn' ? console.warn : console.log;
    consoleFn(`[COC][${category}] ${message}`, details || '');
    return entry;
  },
  debug(category, message, details) { return this.write('debug', category, message, details); },
  info(category, message, details)  { return this.write('info', category, message, details); },
  warn(category, message, details)  { return this.write('warn', category, message, details); },
  error(category, message, details) { return this.write('error', category, message, serializeError(details) ?? details); },

  all() { return loadJSON(STORAGE_KEYS.logs, []); },

  clear() {
    try { localStorage.removeItem(STORAGE_KEYS.logs); } catch { /* yoksay */ }
  },

  // Seviyeye ve kategoriye göre filtre
  filter(entries, { level = null, category = null } = {}) {
    return entries.filter(e =>
      (!level || e.level === level) && (!category || e.category === category)
    );
  },

  // Okunabilir Markdown raporu
  toMarkdown(entries, title = 'CoC Analiz Olay Günlüğü') {
    const counts = entries.reduce((acc, e) => { acc[e.level] = (acc[e.level] || 0) + 1; return acc; }, {});
    const icon = { debug: '⚪', info: '🔵', warn: '🟡', error: '🔴' };
    let md = `# ${title}\n\n`;
    md += `**Oluşturulma:** ${new Date().toISOString()}\n\n`;
    md += `**Toplam kayıt:** ${entries.length} — ` +
      `🔴 hata: ${counts.error || 0}, 🟡 uyarı: ${counts.warn || 0}, 🔵 bilgi: ${counts.info || 0}\n\n`;
    md += `| Zaman | Seviye | Kategori | Mesaj |\n|---|---|---|---|\n`;
    entries.forEach(e => {
      const msg = String(e.message).replace(/\|/g, '\\|').replace(/\n/g, ' ');
      md += `| ${e.time} | ${icon[e.level] || ''} ${e.level} | ${e.category} | ${msg} |\n`;
    });
    const detailed = entries.filter(e => e.details);
    if (detailed.length) {
      md += `\n## Detaylar\n\n`;
      detailed.forEach(e => {
        md += `### ${e.time} — ${e.category}: ${e.message}\n\`\`\`json\n${JSON.stringify(e.details, null, 2)}\n\`\`\`\n\n`;
      });
    }
    return md;
  }
};

if (typeof window !== 'undefined') {
  window.addEventListener('error', ev => {
    Logger.error('system', 'Yakalanmamış JS hatası', { message: ev.message, source: ev.filename, line: ev.lineno });
  });
  window.addEventListener('unhandledrejection', ev => {
    Logger.error('system', 'Yakalanmamış Promise hatası', ev.reason);
  });
}
