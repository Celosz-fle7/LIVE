# Aturan Kerja Repo `LIVE` (`com.liveStream.app`)

## 1. Isolasi Provider (Anti-Obok Wadah)
- **Lokasi Wajib:** Setiap provider baru wajib berada di paket terisolasi:
  `app/src/main/java/com/liveStream/app/provider/<nama_provider>/`
- **Registrasi 1 Baris:** Cukup daftarkan provider di `ProviderRegistry.kt`.
- **Dilarang Mengubah Wadah:** Jangan menyentuh `:core-contract`, `MainScreen.kt`, `MainMvi.kt`, atau `PlayerActivity.kt` saat menambah provider baru, kecuali ada permintaan fitur UI global dari user.
- **Dekriptor & Proxy Terisolasi:** Jika provider memakai request signing (misal `MeloloSigner`) atau dekripsi stream biner/CENC (`MeloloCencProxy`), taruh di dalam folder `provider/<nama_provider>/` atau `core-rust/` — bukan di `PlayerActivity`.

## 2. Kontrak Wajib Setiap Provider (`DramaProvider`)
Setiap provider yang masuk ke `ProviderRegistry` wajib mengimplementasikan kontrak lengkap berikut:
1. **`iconUrl`:** URL logo resmi provider dari internet (teruji HTTP 200).
2. **`getTabs()` & `getFeed()`:** Peta tab sesuai aplikasi asli + pagination `nextCursor` & `hasMore` (tidak boleh mentok halaman 1).
3. **`getForYouTabs()` & `getForYouFeed()`:** Minimal 2 sub-tab cuplikan (misal `Untukmu`/`Mini Drama` + `Anime`) dengan pagination berkelanjutan (tidak boleh mentok 10 item).
4. **`getFilterLadders()` & `getFilteredFeed()`:** Minimal 1 tangga filter genre/kategori.
5. **`getEpisodes()` & `resolvePlayback()`:**
   - Wajib membawa parameter bahasa/negara `id`/`ID` sampai level playback.
   - Petakan `subtitles` (softsub `.srt`/`.vtt` dengan `langCode`) dan `audioTracks` (default `id`).
   - Jika episode terkunci di server, tandai `isLocked = true` dan kembalikan `streamUrl = ""` (dilarang meminjam stream Episode 1).

## 3. Signing, OTA & CI Build
- **Keystore Tetap:** Gunakan `app/debug.keystore` yang sudah ada. **DILARANG** menghapus atau mengganti keystore agar OTA update di HP tidak gagal (*paket bentrok*).
- **Versioning OTA:** Setiap perubahan yang siap rilis wajib menaikkan `versionCode` (+1) dan `versionName` di `app/build.gradle.kts` agar GitHub Actions otomatis merilis APK & `version.json` ke `Celosz-fle7/LIVE-release`.
- **Visibilitas Actions:** Jika kuota Actions private repo habis, pastikan repo `LIVE` berstatus `Public` sebelum memicu build CI.
