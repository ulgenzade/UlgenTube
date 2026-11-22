# Değişiklik Günlüğü (Changelog)

Bu projedeki tüm önemli değişiklikler bu dosyada belgelenir.

## [2.1.0] - 2026-11-22

### Yeni Özellikler
- **YouTube Innertube + Android Native `MediaMuxer` Motoru**: 4K (`2160p`), 2K (`1440p`), `1080p60`, `HDR / 10-bit` (`AV1`, `VP9`, `H.264`) akışları doğrudan Innertube (`IOS 20.10.4` / `ANDROID 20.10.38` + `IOS_PRODUCER`) üzerinden ~0.4 saniyede çözme ve Android 10–15+ (`targetSdk 35`) SELinux kısıtına takılmadan yerel `MediaMuxer` ile kayıpsız birleştirme (25 GB akış desteği, 4K60 dinamik 4–16 MB tampon).
- **YouTube Music Albüm & Hızlı Playlist Motoru**: `music.youtube.com/browse/MPREb_...`, `list=OLAK5uy_...` ve `list=PL...` bağlantıları `WEB_REMIX` üzerinden ~0.4 saniyede çözülür; albüm/playlist şarkıları doğrudan Innertube ile yüksek hızda iner.
- **Altyazı (`.vtt`) & Tek Satır Temizleyici**: İmzalı YouTube altyazı indirme, Android 10+ Scoped Storage (`filesDir/subtitles`) dahili oynatıcı CC uyumu ve kaydırmalı VTT çift satır tekilleştirme.
- **Instagram Hikaye, Oturum & Tam Ekran Hesap Girişi**: `InAppCookieLoginDialog` tam ekran yeniden tasarımı (Instagram, YouTube, X, TikTok, Reddit), `X-CSRFToken` + doğrudan `/api/v1/media/{id}/info/` hikaye çıkarıcı ve oturumlu WebView sniffer yedeği.
- **Reddit 18+ (NSFW) & Redgifs v2 HD MP4**: `over18=1` + oturum çerezleri, Reddit yaş kapısı/logo filtresi ve Redgifs v2 geçici token ile doğrudan HD MP4 indirme.
- **İndirme Yönetimi & Akıllı UX**: Duraklat / Devam Et (tekil ve toplu), uzun basarak Çoklu Seçim (Özel Kasa / Toplu Sil), aynı dosya varsa *"İçeriği Göster / Yine de İndir"* onay penceresi, Akıllı Hızlı İndir Hafızası, kapaktan İndirmeden Önizle (`▶`), Codec/Boyut kıyas rozetleri ve Canlı Depolama Çubuğu.
- **5 Kategorili Sayfalandırılmış Ayarlar & Hata Teşhis Günlüğü**: Ayarlar 5 temiz alt sayfaya ayrıldı; hassas verileri maskeleyen `DiagnosticLogManager` ile hatalı indirme kartından, hata penceresinden ve Ayarlar'dan tek tıkla kopyalama / `.txt` dosya paylaşımı eklendi.
- **Dahili PiP Oynatıcı, Mini Müzik Çalar Pro, Stüdyo Pro & Özel Kasa**: Media3 PiP video oynatıcı, 5 bantlı Ekolayzır + LRC şarkı sözleri, 9 araçlı FFmpeg Stüdyo Pro, kanal takibi (RSS), JSON yedekleme/geri yükleme ve SHA-256 PIN/biyometrik kilitli Özel Kasa (Room v4).

### Düzeltmeler
- 1080p/4K indirmelerinin SELinux FFmpeg hatası nedeniyle 360p'ye düşmesi tamamen giderildi.
- Paralel indirme kapalıyken oluşan 90 KB/s YouTube hız kısıtlaması 4 MB sıralı HTTP Range parçalarıyla çözüldü.
- Özel Kasa gizlilik filtresi, Stüdyo scroll taşması, WebM audio MIME çakışması, WebView ve ViewModel bellek sızıntıları giderildi.

---

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
