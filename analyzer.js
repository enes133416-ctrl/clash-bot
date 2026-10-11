// ============================================
//  CLAUDE VISION API — COC SAVAŞ ANALİZÖRÜ
// ============================================

const CLAUDE_API_URL      = 'https://api.anthropic.com/v1/messages';
const CLAUDE_MODEL_DEFAULT = 'claude-sonnet-4-5';
const CLAUDE_MAX_TOKENS   = 6000;
const MAX_IMAGE_EDGE      = 1568;  // Claude'un görüntü için önerdiği üst kenar
const JPEG_QUALITY        = 0.92;
const MAX_RETRIES         = 2;
const RETRYABLE_STATUS    = [408, 409, 429, 500, 502, 503, 529];

const sleep = ms => new Promise(r => setTimeout(r, ms));

function getApiKey() {
  return localStorage.getItem(STORAGE_KEYS.apiKey);
}

function getModel() {
  return localStorage.getItem(STORAGE_KEYS.model) || CLAUDE_MODEL_DEFAULT;
}

// ──────────────────────────────────────────
//  GÖRÜNTÜ HAZIRLAMA
//  Her görüntüyü küçültür ve JPEG'e çevirir:
//  HEIC/BMP/aşırı büyük ekran görüntüleri API'de hata vermesin diye.
// ──────────────────────────────────────────
async function prepareImage(file) {
  if (!file.type.startsWith('image/')) {
    throw new Error(`Desteklenmeyen dosya türü: ${file.type || 'bilinmiyor'}`);
  }

  let bitmap;
  try {
    bitmap = await createImageBitmap(file);
  } catch (e) {
    throw new Error(`Görüntü çözülemedi (${file.name}). Tarayıcı bu formatı okuyamıyor olabilir.`);
  }

  const scale = Math.min(1, MAX_IMAGE_EDGE / Math.max(bitmap.width, bitmap.height));
  const width  = Math.max(1, Math.round(bitmap.width * scale));
  const height = Math.max(1, Math.round(bitmap.height * scale));

  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  canvas.getContext('2d').drawImage(bitmap, 0, 0, width, height);
  bitmap.close?.();

  const dataUrl = canvas.toDataURL('image/jpeg', JPEG_QUALITY);
  const prepared = {
    base64: dataUrl.split(',')[1],
    mimeType: 'image/jpeg',
    width, height,
    originalWidth: bitmap.width, originalHeight: bitmap.height,
    originalType: file.type,
    originalBytes: file.size
  };

  Logger.info('image', `Görüntü hazırlandı: ${file.name}`, {
    original: `${prepared.originalWidth}x${prepared.originalHeight} ${prepared.originalType}`,
    prepared: `${width}x${height} jpeg`,
    base64Chars: prepared.base64.length
  });
  return prepared;
}

// ──────────────────────────────────────────
//  API ÇAĞRISI (retry + detaylı hata kaydı)
// ──────────────────────────────────────────
async function callClaude(payload) {
  const apiKey = getApiKey();
  if (!apiKey) throw new Error('API Key bulunamadı!');

  let lastError = null;
  for (let attempt = 0; attempt <= MAX_RETRIES; attempt++) {
    let response;
    try {
      response = await fetch(CLAUDE_API_URL, {
        method: 'POST',
        headers: {
          'Content-Type':      'application/json',
          'x-api-key':         apiKey,
          'anthropic-version': '2023-06-01',
          // Tarayıcıdan doğrudan çağrı için gerekli (yoksa CORS hatası alınır)
          'anthropic-dangerous-direct-browser-access': 'true'
        },
        body: JSON.stringify(payload)
      });
    } catch (netErr) {
      lastError = new Error(`Ağ hatası: ${netErr.message}`);
      Logger.warn('api', `İstek gönderilemedi (deneme ${attempt + 1})`, { message: netErr.message });
      if (attempt < MAX_RETRIES) { await sleep(1000 * 2 ** attempt); continue; }
      throw lastError;
    }

    if (response.ok) {
      return response.json();
    }

    const bodyText = await response.text();
    let message = response.statusText || 'bilinmeyen hata';
    try { message = JSON.parse(bodyText).error?.message || message; } catch { /* JSON değil */ }

    lastError = new Error(`API Hatası (${response.status}): ${message}`);
    const retryable = RETRYABLE_STATUS.includes(response.status);
    const entry = { status: response.status, message, attempt: attempt + 1, retryable };

    if (retryable && attempt < MAX_RETRIES) {
      Logger.warn('api', `Geçici API hatası, tekrar denenecek`, entry);
      await sleep(1000 * 2 ** attempt);
      continue;
    }
    Logger.error('api', `API isteği başarısız`, entry);
    throw lastError;
  }
  throw lastError;
}

// ──────────────────────────────────────────
//  ANA ANALİZ FONKSİYONU
//  images: [{ base64, mimeType }] — compare modunda [önceki, mevcut]
// ──────────────────────────────────────────
async function analyzeImages(images, mode) {
  const isCompare = images.length > 1;
  const content = [];

  if (isCompare) {
    content.push({ type: 'text', text: '📸 **ÖNCEKİ EKRAN GÖRÜNTÜSÜ:**' });
    content.push({ type: 'image', source: { type: 'base64', media_type: images[0].mimeType, data: images[0].base64 } });
    content.push({ type: 'text', text: '📸 **MEVCUT EKRAN GÖRÜNTÜSÜ:**' });
    const cur = images[images.length - 1];
    content.push({ type: 'image', source: { type: 'base64', media_type: cur.mimeType, data: cur.base64 } });
  } else {
    content.push({ type: 'image', source: { type: 'base64', media_type: images[0].mimeType, data: images[0].base64 } });
  }
  content.push({ type: 'text', text: getUserPrompt(mode, isCompare) });

  const started = Date.now();
  const payload = {
    model: getModel(),
    max_tokens: CLAUDE_MAX_TOKENS,
    system: getCoCSystemPrompt(),
    messages: [{ role: 'user', content }]
  };

  const data = await callClaude(payload);
  const durationMs = Date.now() - started;
  const raw = (data.content || []).filter(b => b.type === 'text').map(b => b.text).join('\n');

  if (data.stop_reason === 'max_tokens') {
    Logger.warn('parse', 'Model yanıtı token limitinde kesildi; JSON eksik olabilir', { max_tokens: CLAUDE_MAX_TOKENS });
  }

  const { data: parsed, error: parseError } = extractJson(raw);
  if (!parsed) {
    Logger.error('parse', 'Model yanıtından JSON çıkarılamadı', { parseError, rawPreview: raw.slice(0, 500) });
    return { ...parseRawText(raw), _meta: { model: payload.model, durationMs, usage: data.usage || null, stop_reason: data.stop_reason } };
  }

  return { ...parsed, _meta: { model: payload.model, durationMs, usage: data.usage || null, stop_reason: data.stop_reason } };
}

// ──────────────────────────────────────────
//  JSON ÇIKARMA — ```json bloğu, ``` bloğu veya ilk { … son } aralığı
// ──────────────────────────────────────────
function extractJson(raw) {
  const text = String(raw || '');
  const candidates = [];
  const fence = text.match(/```(?:json)?\s*([\s\S]*?)```/i);
  if (fence) candidates.push(fence[1]);
  const first = text.indexOf('{');
  const last = text.lastIndexOf('}');
  if (first !== -1 && last > first) candidates.push(text.slice(first, last + 1));

  let lastErr = 'JSON bulunamadı';
  for (const c of candidates) {
    try {
      return { data: JSON.parse(c.trim()), error: null };
    } catch (e) {
      lastErr = e.message;
    }
  }
  return { data: null, error: lastErr };
}

// ──────────────────────────────────────────
//  CLAUDE SYSTEM PROMPT
// ──────────────────────────────────────────
function getCoCSystemPrompt() {
  return `
Sen bir Clash of Clans savaş analisti ve görüntü analiz uzmanısın.

GÖREVIN:
Sana verilen ekran görüntülerini analiz et ve aşağıdaki bilgileri çıkar.
Okuyamadığın, bulanık, kesilmiş veya ekran dışında kalan şeyleri MUTLAKA "readability" alanında belirt.

ÇIKTI FORMATI (her zaman geçerli JSON döndür, başka metin ekleme):
\`\`\`json
{
  "timestamp": "ISO 8601 zaman damgası veya null",
  "scene_type": "war_map | attack | defense | loot | menu | loading | popup | other",

  "readability": {
    "readable": true,
    "confidence": "HIGH | MEDIUM | LOW",
    "unclear_regions": ["okunamayan bölgeler (sol üst, merkez vb.)"],
    "blocking_elements": ["ekranı kapatan pop-up, reklam, yükleme vb."],
    "reason": "Okunamadıysa neden (boş cevap bırakma)"
  },

  "screen_overview": {
    "description": "Ekranda ne olduğunun genel açıklaması (Türkçe, detaylı)",
    "dominant_colors": ["#RRGGBB"],
    "ui_elements": ["tespit edilen UI elementleri"],
    "new_elements": ["önceki görüntüye göre yeni gelen elementler (karşılaştırma modunda)"]
  },

  "coc_analysis": {
    "game_phase": "savaş hazırlık | aktif savaş | savaş sonu | köy görünümü | diğer",
    "war_indicators": {
      "stars_earned": null,
      "stars_lost": null,
      "attacks_used": null,
      "attacks_remaining": null,
      "destruction_percentage": null,
      "timer": null
    },
    "visible_buildings": { "offensive": [], "defensive": [], "resource": [], "special": [] },
    "troops_visible": [],
    "spells_visible": [],
    "heroes_visible": []
  },

  "color_analysis": {
    "fire_explosion_detected": false,
    "health_bars": [],
    "damage_indicators": [],
    "color_changes": []
  },

  "strategy_notes": {
    "attack_pattern": "tespit edilen saldırı düzeni",
    "funnel_detected": false,
    "target_priority": [],
    "weak_points": [],
    "strengths": [],
    "recommendations": [],
    "warnings": []
  },

  "event_log": [
    { "event": "olay açıklaması", "importance": "HIGH | MEDIUM | LOW", "location": "ekran bölgesi" }
  ],

  "raw_observations": "Yukarıdaki kategorilere uymayan tüm gözlemler"
}
\`\`\`

KURALLAR:
1. HER ZAMAN geçerli JSON döndür — eksik alan varsa null veya boş liste kullan
2. Türkçe açıklama yaz
3. Renkleri HEX (#RRGGBB) formatında yaz
4. Emin olmadığın şeyi "belirsiz" olarak işaretle, tahmin uydurma
5. Ekran okunamıyorsa readable=false yap ve nedenini yaz; yine de JSON döndür
6. Zaman bilgisi göremiyorsan timestamp alanına null yaz
`.trim();
}

// ──────────────────────────────────────────
//  KULLANICI PROMPT (MOD'A GÖRE)
// ──────────────────────────────────────────
function getUserPrompt(mode, isCompare) {
  const base = 'Bu Clash of Clans ekran görüntüsünü analiz et ve belirtilen JSON formatında yanıt ver.';

  const modePrompts = {
    full: `${base}
Ekrandaki HER ŞEYİ analiz et:
- Tüm binaları, askerleri, büyüleri tespit et
- Renk değişimlerini (ateş, patlama, hasar) not al
- Kaynak durumunu (altın, iksir, karanlık iksir) oku
- Savaş yıldızlarını ve yüzdeyi say
- Strateji önerileri sun`,

    strategy: `${base}
SADECE STRATEJİ ODAKLI analiz yap:
- Saldırı düzenini tespit et (GoWiPe, LaLoon, Queen Walk vb.)
- Huni yapılmış mı?
- Zayıf noktalara odaklan
- Savunma yerleşimini değerlendir
- Sonraki hamle için öneri sun`,

    color: `${base}
RENK VE GÖRSEL DEĞİŞİM ANALİZİ:
- Hasar görmüş binaların renklerini not al
- Ateş/patlama/duman bölgelerini işaretle
- Sağlık barlarının renklerini (yeşil/sarı/kırmızı) listele
- Ekran genelinde dominant renkleri çıkar
- Animasyon/hareket olan bölgeleri belirt`,

    compare: `${base}
İKİ GÖRÜNTÜ KARŞILAŞTIRMASI (ilk görüntü önceki, ikinci görüntü mevcut):
- İlk ve ikinci görüntü arasındaki farkları listele
- Yıkılan binaları tespit et
- Yeni beliren askerleri/büyüleri not al
- Sağlık barı değişimlerini karşılaştır
- Genel ilerlemeyi (yıldız/yüzde) karşılaştır`
  };

  return modePrompts[mode] || modePrompts.full;
}

// ──────────────────────────────────────────
//  YARDIMCI: PARSE HATASI DURUMU
// ──────────────────────────────────────────
function parseRawText(text) {
  return {
    parse_error: true,
    raw_text: text,
    readability: { readable: false, confidence: 'LOW', unclear_regions: [], blocking_elements: [], reason: 'Model yanıtı JSON olarak okunamadı' },
    screen_overview: { description: text },
    strategy_notes: { recommendations: [], warnings: [] },
    event_log: []
  };
}
