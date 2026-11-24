# PyDM for Android

PyDM (Windows download manager) ka Android version. Features:

- **Downloads**: direct links, multi-connection (1-16), pause/resume, queue, speed limit, scheduler
- **YouTube**: video / playlist, quality + format (MP4/MKV/WEBM/MP3/M4A), H.264 ya best codec
- **Channel**: thumbnails (JPG/PNG) + saare titles ka .docx
- **Transcripts**: docx ya txt, ek file ya har video ki alag file
- **Analysis**: kaunse lafz / title patterns par zyada views, Excel (.xlsx) export
- **Share → PyDM**: kisi bhi app (Chrome, YouTube) se link share karein

Saari files phone ke `Downloads/PyDM/` folder mein save hoti hain.
Android 10 ya naya chahiye (minSdk 29).

## APK banane ka tareeqa 1: GitHub Actions (aasaan, PC par kuch install nahi)

1. GitHub par naya repository banayein (e.g. `pydm-android`).
2. Is folder ki **saari files** (hidden `.github` folder samet) us repo mein upload/push karein.
3. Repo ke **Actions** tab mein "Build Android APK" khud chalega (ya "Run workflow" dabayein).
4. 5-10 minute baad run khol kar neeche **Artifacts → PyDM-android-apk** download karein.
5. Zip kholein, `.apk` phone mein copy karein, install karein
   (Settings mein "Install unknown apps" allow karna hoga).

## Tareeqa 2: Android Studio

1. Android Studio (Koala ya naya) mein **Open** → is folder ko chunein.
2. Gradle sync hone dein.
3. **Build → Build APK(s)** (ya phone laga kar Run).

## Ahem baatein

- Pehli baar app khulne par yt-dlp + ffmpeg tayyar hone mein kuch second lagte hain.
- Agar YouTube achanak kaam karna band kar de: Settings → **Update yt-dlp**.
- YouTube kabhi kabhi "bot check" laga deta hai; thori der baad ya Wi-Fi/mobile data badal kar dobara try karein.
- Yeh APK debug key se sign hai (apne istemal / sideload ke liye). Google Play par nahi daal sakte.
- Sirf wohi cheezein download karein jin ke aap haq-daar hon / jin ki YouTube aur copyright ke qawaneen ijazat dein.
