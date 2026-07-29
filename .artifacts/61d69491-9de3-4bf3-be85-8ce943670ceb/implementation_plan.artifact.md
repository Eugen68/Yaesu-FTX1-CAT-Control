# Plan de remediere a erorilor de sincronizare

Problema principală identificată este prezența unor resurse duplicat (iconițe) care împiedică compilarea proiectului. Acest lucru se întâmplă frecvent atunci când două calculatoare sincronizează fișiere cu extensii diferite (PNG și WebP) pentru aceeași resursă.

## Probleme identificate

*   **Resurse Duplicat**: Fișierele `ic_launcher.png` și `ic_launcher.webp` (precum și variantele `_round`) coexistă în folderele `mipmap-*`. Android nu permite acest lucru.
*   **Configurație SDK**: Proiectul este setat să folosească `compileSdk 36` (Android 16 preview) și AGP 9.2.1. Dacă acest calculator nu are instalate aceste versiuni de preview, ar putea apărea erori secundare după rezolvarea resurselor.

## Modificări propuse

### Resurse (Curățare)

Voi șterge fișierele WebP duplicat, păstrând versiunile PNG care par a fi cele mai recent actualizate pe acest calculator.

#### [DELETE] [ic_launcher.webp](file:///C:/Users/YO9WHI/AndroidStudioProjects/YaesuCATControlADVQWEN/app/src/main/res/mipmap-hdpi/ic_launcher.webp) (și toate celelalte densități: mdpi, xhdpi, xxhdpi, xxxhdpi)
#### [DELETE] [ic_launcher_round.webp](file:///C:/Users/YO9WHI/AndroidStudioProjects/YaesuCATControlADVQWEN/app/src/main/res/mipmap-hdpi/ic_launcher_round.webp) (și toate celelalte densități)

### Configurație Build (Dacă este necesar)

Dacă după ștergerea resurselor build-ul tot eșuează din cauza versiunii de SDK, voi propune ajustarea `compileSdk` la versiunea 35 (stabilă).

## Plan de verificare

### Verificare automată
1.  Voi rula `gradlew assembleDebug` pentru a confirma că eroarea `mergeDebugResources` a dispărut.
2.  Voi verifica dacă există erori de sintaxă sau referințe lipsă în codul Java/Kotlin.

### Verificare manuală
*   Te voi ruga să verifici dacă iconița aplicației apare corect pe dispozitiv după instalare.
