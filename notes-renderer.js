// ============================================
//  NOT GÖRÜNTÜLEME MOTORU
//  Model çıktısı HTML olarak basılmadan önce kaçışlanır (XSS koruması).
// ============================================

function esc(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function isSafeColor(c) {
  return /^#[0-9a-fA-F]{3,8}$/.test(String(c));
}

function renderList(items, cls = '') {
  return (items || []).map(i => `<span class="tag ${cls}">${esc(i)}</span>`).join('');
}

function renderNote(result, container) {
  const block = document.createElement('div');
  block.className = 'note-block';

  if (result._error) {
    block.innerHTML = `
      <h3>📸 Görüntü ${esc(result._index + 1)} — ${esc(result._filename)}</h3>
      <div class="error-text">❌ Hata: ${esc(result._error)}</div>
      <p class="hint">Ayrıntı için aşağıdaki "Olay Günlüğü" bölümüne bak.</p>
    `;
    container.appendChild(block);
    return;
  }

  const r   = result;
  const coc = r.coc_analysis    || {};
  const str = r.strategy_notes  || {};
  const ovr = r.screen_overview || {};
  const rd  = r.readability     || {};
  const war = coc.war_indicators || {};

  const colorChips = (ovr.dominant_colors || []).filter(isSafeColor).map(c => `
    <div class="color-chip">
      <span class="swatch" style="background:${c}"></span>
      <span>${esc(c)}</span>
    </div>`).join('');

  const eventLog = (r.event_log || []).map(e => {
    const cls  = e.importance === 'HIGH' ? 'red' : e.importance === 'MEDIUM' ? 'yellow' : 'green';
    const icon = e.importance === 'HIGH' ? '🔴' : e.importance === 'MEDIUM' ? '🟡' : '🟢';
    return `<span class="tag ${cls}">${icon} ${esc(e.event)}</span>`;
  }).join('');

  const readableBadge = rd.readable === false || r.parse_error
    ? `<span class="tag red">⚠️ Ekran tam okunamadı</span>`
    : `<span class="tag green">✅ Okundu (${esc(rd.confidence || '?')})</span>`;

  const readBlock = (rd.unclear_regions?.length || rd.blocking_elements?.length || rd.reason)
    ? `<div class="section-title">👁️ Okunabilirlik</div>
       <div class="tag-list">
         ${renderList(rd.unclear_regions, 'yellow')}
         ${renderList(rd.blocking_elements, 'red')}
       </div>
       ${rd.reason ? `<p class="hint">${esc(rd.reason)}</p>` : ''}`
    : '';

  block.innerHTML = `
    <h3>📸 Görüntü ${esc(r._index + 1)} — ${esc(r._filename || '')}</h3>
    <div class="tag-list">${readableBadge}${r.scene_type ? `<span class="tag">🎬 ${esc(r.scene_type)}</span>` : ''}</div>
    ${r._imageUrl ? `<img class="image-thumb" src="${esc(r._imageUrl)}" alt="Ekran görüntüsü">` : ''}

    <div class="section-title">🔍 Genel Bakış</div>
    <p>${esc(ovr.description || 'Açıklama bulunamadı')}</p>

    ${colorChips ? `<div class="section-title">🎨 Dominant Renkler</div><div class="color-changes">${colorChips}</div>` : ''}
    ${coc.game_phase ? `<div class="section-title">🎮 Oyun Fazı</div><p>${esc(coc.game_phase)}</p>` : ''}

    ${Object.keys(war).length ? `
    <div class="section-title">⭐ Savaş Durumu</div>
    <div class="tag-list">
      ${war.stars_earned != null ? `<span class="tag yellow">⭐ Kazanılan: ${esc(war.stars_earned)}</span>` : ''}
      ${war.stars_lost != null ? `<span class="tag red">💀 Kaybedilen: ${esc(war.stars_lost)}</span>` : ''}
      ${war.destruction_percentage != null ? `<span class="tag blue">💥 Yıkım: %${esc(war.destruction_percentage)}</span>` : ''}
      ${war.timer != null ? `<span class="tag">⏱️ ${esc(war.timer)}</span>` : ''}
    </div>` : ''}

    ${readBlock}

    ${str.attack_pattern ? `<div class="section-title">⚔️ Saldırı Düzeni</div><p>${esc(str.attack_pattern)}</p>` : ''}
    ${str.recommendations?.length ? `<div class="section-title">💡 Öneriler</div><div class="tag-list">${renderList(str.recommendations, 'green')}</div>` : ''}
    ${str.warnings?.length ? `<div class="section-title">⚠️ Uyarılar</div><div class="tag-list">${renderList(str.warnings, 'red')}</div>` : ''}
    ${eventLog ? `<div class="section-title">📋 Olay Logu</div><div class="tag-list">${eventLog}</div>` : ''}
    ${r.raw_observations ? `<div class="section-title">📝 Ek Gözlemler</div><p class="hint">${esc(r.raw_observations)}</p>` : ''}
    ${r.parse_error ? `<div class="section-title">🧾 Ham Model Çıktısı</div><pre class="raw">${esc(r.raw_text)}</pre>` : ''}
  `;

  container.appendChild(block);
}

// Günlük panelini doldurur
function renderLogView(entries, container) {
  const rows = entries.slice(-150).reverse().map(e => {
    const details = e.details ? `<details><summary>detay</summary><pre>${esc(JSON.stringify(e.details, null, 2))}</pre></details>` : '';
    return `<div class="log-row log-${esc(e.level)}">
      <span class="log-time">${esc(new Date(e.time).toLocaleTimeString('tr-TR'))}</span>
      <span class="log-level">${esc(e.level.toUpperCase())}</span>
      <span class="log-cat">${esc(e.category)}</span>
      <span class="log-msg">${esc(e.message)}</span>
      ${details}
    </div>`;
  }).join('');
  container.innerHTML = rows || '<p class="hint">Henüz kayıt yok.</p>';
}
