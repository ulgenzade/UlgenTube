# Değişiklik Günlüğü (Changelog)

Bu projedeki tüm önemli değişiklikler bu dosyada belgelenir.

## [1.0.0] - 2026-11-19

### Eklenenler
- **Snaptube Tarzı Şeffaf Paylaşım Popup'ı (`SharePopupActivity`)**: `ACTION_SEND` ile diğer uygulamalardan paylaşılan bağlantıları şeffaf `ModalBottomSheet` içinde anında analiz etme ve arka planda indirme.
- **Çift Motorlu (Hybrid) Medya Ayrıştırıcı**:
  - `YtDlpEngine`: `youtubedl-android` (`yt-dlp` + `FFmpeg` + `aria2c`) ile gerçek akış analizi (8K/4K/2K/1080p, 120/60/50/30 FPS, HDR / 10-bit, Yüksek Bitrate, AV1/VP9/HEVC/H.264) ve birebir format ID seçimi.
  - `SocialImageExtractor`: X/Twitter (FxTwitter/VxTwitter), Reddit galeri (`.json`), Pinterest (`/originals/`), Instagram/genel web OpenGraph ve doğrudan görsel URL desteği.
  - Tek akışlı platformlarda (Instagram/Twitter/TikTok) gömülü **FFmpeg** ile yalnızca orijinalden düşük çözünürlüklere gerçek ölçekleme.
  - Video içeren her kaynakta `MP3 (320/192/128k)`, `M4A`, `OPUS`, `FLAC` ve `WAV` ses dönüştürme.
- **Kick Platformu Desteği**: `kick.com` ve alt alan adları için birinci sınıf platform tespiti ve kalite seçimi.
- **Arka Plan İndirme Servisi & Galeri Kaydı**: 3 paralel indirme, canlı ilerleme/hız/ETA bildirimi, `MediaStore` (`Movies/UlgenTube`, `Music/UlgenTube`, `Pictures/UlgenTube`) kaydı ve Room veritabanı.
- **Material 3 Expressive + Koyu/Gradient Arayüz**: Ana Sayfa (otomatik pano algılama), İndirmelerim (filtreli kütüphane), Ayarlar (tema, dinamik renk, `aria2c`, uygulama içi `yt-dlp` güncelleme).
- **Güvenlik & Testler**: Explicit intent/MIME koruması, gömülü zararlı şema engelleme, path traversal temizliği, `FileProvider` sıkılaştırması ve JUnit birim testleri.
