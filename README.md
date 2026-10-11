# clash-bot — CoC Savaş Analiz Aracı

Clash of Clans ekran görüntülerini Claude Vision ile analiz eder, savaş notu üretir
ve **olay günlüğü** tutar (hangi ekran okundu, hangi ekran okunamadı, hangi API hatası oldu).

## Kurulum
1. Claude API key al: https://console.anthropic.com (ücretli kredi gerekir).
2. GitHub Pages'i aç: Settings → Pages → Branch `main` → `/ (root)`.
3. Siteyi aç, API key'i gir ve kaydet. Ekran görüntüsü yükle, analiz modunu seç, "Analiz Başlat".

## Ayarlar
- **Model:** varsayılan `claude-sonnet-4-5`; istediğin modeli yazabilirsin.
- **GitHub repo + token (opsiyonel):** "GitHub'a Kaydet" notları `notes/` klasörüne ve günlüğü `notes/logs/` klasörüne ekler.
  Token yalnızca bu tarayıcının localStorage'ında tutulur; fine-grained token'ı sadece ilgili repoya ve "Contents: Read and write" yetkisiyle ver.

## Olay Günlüğü
Her işlem `Logger` ile kaydedilir (localStorage, son 500 kayıt):
- `upload` — dosya ekleme, desteklenmeyen dosyalar
- `image` — görüntü küçültme/JPEG dönüşümü
- `api` — API hataları (HTTP kodu, mesaj, retry denemeleri)
- `parse` — model çıktısından JSON çıkarılamadı / yanıt token limitinde kesildi
- `screen` — ekran okundu / **tam okunamadı** (okunamayan bölgeler, engelleyen pop-up'lar, neden)
- `system` — başlangıç, analiz özeti, yakalanmamış JS hataları
- `github` — kayıt işlemleri

Günlük panelinden filtreleyebilir, `.md` veya `.json` olarak indirebilir ve GitHub'a kaydedebilirsin.
Bunu bana geri gönderirsen hangi ekranda ne olduğunu ve neden takıldığını birlikte düzeltebiliriz.

## Testler
```bash
npm test
```

## Dosyalar
| Dosya | Görev |
|---|---|
| `index.html`, `style.css` | Arayüz |
| `app.js` | Akış, dışa aktarma, GitHub kaydı |
| `analyzer.js` | Görüntü hazırlama, Claude API (retry), JSON çıkarma, prompt |
| `notes-renderer.js` | Not ve günlük görüntüleme (HTML kaçışlı) |
| `storage.js` | localStorage yardımcıları + `Logger` |
| `tests/` | JSON çıkarma ve Logger testleri |

## Sınırlar
- Tarayıcıdan doğrudan Anthropic çağrısı yapılır; API key tarayıcıda saklanır. Sadece kendi kullanımın için uygundur.
- Bu araç ekran görüntüsü **analiz** eder; oyunu otomatik oynatan/tıklayan bir bot değildir.
  Supercell'in kullanım koşulları oyun botlarını yasaklar.
