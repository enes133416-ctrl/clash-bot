# Clash Auto Bot (Android)

Clash of Clans ekranını gerçek zamanlı okuyup sahne kurallarına göre otomatik oynatan, root gerektirmeyen Android uygulaması (`minSdk 30`, `targetSdk 34`).

## 1. Depoya Dosyaları Yükleme ve GitHub Actions'tan APK İndirme

1. Bu proje dosyalarını GitHub deponuza yükleyin (push yapın).
2. GitHub deponuzda üst menüden **Actions** sekmesine girin.
3. **Build Android APK** iş akışına (workflow) tıklayın (push sonrası otomatik başlar veya **Run workflow** butonuyla manuel tetiklenebilir).
4. Derleme tamamlandığında (yeşil onay işareti), ilgili çalışmanın içine girip en alttaki **Artifacts** bölümünden **`app-debug.apk`** dosyasını indirin ve ZIP içinden `.apk` dosyasını çıkarın.

## 2. Telefona APK Kurma ve Erişilebilirlik Servisini Açma

1. İndirdiğiniz `app-debug.apk` dosyasını telefonunuza aktarın ve dokunarak kurun (*Bilinmeyen kaynaklardan uygulama yükleme* izni istenirse onaylayın).
2. Android **Ayarlar > Uygulamalar > Clash Auto Bot** ekranına gidin; sağ üstteki üç nokta menüsünden **"Kısıtlanmış ayarlara izin ver"** seçeneği varsa etkinleştirin (Android 13+ cihazlar için).
3. Uygulamayı açıp **"Erişilebilirlik Ayarlarını Aç"** butonuna dokunun (veya **Ayarlar > Erişilebilirlik > İndirilen Uygulamalar** menüsüne gidin).
4. **Clash Auto Bot Erişilebilirlik Servisi**'ni seçip anahtarı **Açık** konuma getirin ve gelen izin penceresini onaylayın.

## 3. Overlay İznini Verme ve Botu Başlatma

1. Uygulama ana ekranındaki **"Overlay (Üstte Gösterme) İzni Ver"** butonuna dokunun ve **Diğer uygulamaların üzerinde göster** iznini açın.
2. Erişilebilirlik servisi açıldığında ekranın üst kısmında sürüklenebilir küçük **BAŞLAT / DURDUR** kontrol çubuğu (overlay) belirecektir.
3. **Clash of Clans** oyununu yatay (landscape) modda açıp köy ana ekranına (Ekran 1) gelin.
4. Yüzen (overlay) penceredeki **BAŞLAT** butonuna dokunun. Durdurmak istediğinizde aynı penceredeki **DURDUR** butonuna basmanız yeterlidir; döngü ve dokunuşlar anında durur.
