# Değişiklik Günlüğü (Changelog)

Bu projedeki tüm önemli değişiklikler bu dosyada belgelenir.

## [2.0.0] - 2026-11-19

### Yeni Özellikler
- **Varsayılan Tercihle Tek Tıkla Hızlı İndir**: Ayarlar'daki varsayılan video kalitesi ve ses formatına göre indirme penceresinde anında *"Hızlı Video"* ve *"Hızlı Ses"* butonları.
- **SponsorBlock & Bölüm (Chapters) Gömme**: YouTube videolarındaki sponsor/intro/outro bölümlerini otomatik kesme (`--sponsorblock-remove`) ve bölüm işaretlerini gömme (`--embed-chapters`).
- **Tamamlandı Bildiriminden Doğrudan Aç / Paylaş**: Uygulamaya girmeden doğrudan bildirim üzerinden dosyayı açma veya WhatsApp/Telegram'a paylaşma.
- **Duplicate Tespiti & Kütüphane Arama/Sıralama**: Daha önce indirilen bağlantılarda uyarı + kütüphanede başlık/kanal/platform araması ve `En Yeni / En Eski / En Büyük / A → Z` sıralama.
- **3 Seçenekli Gerçek Dosya Silme Onay Diyaloğu**: Geçmişten silerken *"Cihazdan da Sil (Kalıcı)"*, *"Sadece Geçmişten Sil"* ve *"İptal"* seçimi.
- **Yalnızca Wi-Fi Modu**: Mobil veride büyük indirmeleri bekleten ağ koruması.
- **Zaman Aralığı Kırpma (Clip / Trim İndirme)**: Kick, Twitch VOD veya uzun YouTube videolarında yalnızca seçilen başlangıç–bitiş aralığını indirme.
- **Oynatma Listesi (Playlist) Toplu İndirme**: YouTube/SoundCloud listelerinden checkbox ile seçim yapıp 3 paralel kuyrukta Video/MP3 indirme.
- **Altyazı (Subtitles) Seçme & Videoya Gömme**: Mevcut TR/EN ve diğer altyazı dillerini seçip videoya gömme veya `.srt` olarak kaydetme.
- **Çoklu Link (Batch URL) Ayıklama**: Tek metin/paylaşım içindeki birden fazla bağlantıyı toplu indirme.
- **Kaldığı Yerden Sürdürme (Resume)**: Kopan 4K/VOD indirmelerini `.part` dosyaları üzerinden `--continue` / `aria2c -c` ile devam ettirme.
- **Güvenli Çerez (`cookies.txt`) Desteği**: Özel uygulama dizininde Netscape çerez içe aktarma ve temizleme.
- **Çevrimdışı FFmpeg Medya Stüdyosu**: İndirilen veya galeriden seçilen herhangi bir videoyu internet olmadan **MP3'e Çevir**, **WhatsApp/Discord Sıkıştır**, **GIF Oluştur** veya **Sesini Kaldır (Mute)**.
- **Zero-Friction 2.5 sn Sessiz Paylaşım Kapsülü + Quick Settings Tile**: Paylaşımda otomatik varsayılan indirme kapsülü ve bildirim paneli kısayolu.
- **Dahili Media3 Mini Müzik Çalar**: İndirilen müzikleri doğrudan UlgenTube içinden arka planda çalma.
- **Platform Bazlı Alt Klasörleme & Dosya Adı Şablonları**: `Movies/UlgenTube/YouTube/` alt klasörleme ve `{title}`, `{author} - {title}`, `{date}_{title}` şablonları.

### Güvenlik & Mimari
- **3 Turlu Güvenlik Denetimi (21/21 Bulgu Yamalandı)**: `NetworkSecurityGuard` (`SafePublicDns` SSRF/DNS rebinding & kısaltılmış/octal/hex IPv4 koruması), Coil `ImageLoaderFactory`, `yt-dlp --proto http,https`, format ID/selector/height whitelist, `5 MB` / `500 MB` DoS sınırları, `allowBackup="false"`, `usesCleartextTraffic="false"`, daraltılmış `FileProvider`, `canonicalPath` containment, `ErrorMessageSanitizer`, Android 15 `onTimeout` ve Room `MIGRATION_1_2` + `MIGRATION_2_3`.
- **Çoklu Mimari Release Desteği**: `universal`, `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86` APK üretimi ve otomatik GitHub Releases iş akışı.

---

## [1.0.0] - 2026-11-19

### Eklenenler
- Snaptube tarzı şeffaf paylaşım popup'ı (`SharePopupActivity`), çift motorlu medya ayrıştırıcı (`yt-dlp` + Sosyal Medya Görsel/Galeri Extractor), Kick/YouTube/Twitch gerçek FPS/HDR kalite seçimi, FFmpeg küçültme, 7 ses formatı, Neon Play logosu ve Material 3 Expressive arayüz.
