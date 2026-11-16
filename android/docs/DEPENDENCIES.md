# Dipendenze e attribuzioni Android

Il runtime Android usa dipendenze Gradle fissate. La configurazione `:app:releaseRuntimeClasspath` è stata esaminata il 16 novembre 2026; l'inventario locale di verifica è in `.qa/evidence/runtime-license-inventory.json`. Include anche metadati e vincoli di risoluzione, quindi non rappresenta un conteggio delle librerie effettivamente presenti nel DEX.

| Componente diretto | Versione |
| --- | --- |
| JDK | 17 |
| Gradle Wrapper | 8.13, distribuzione verificata con SHA-256 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin e plugin Compose/serialization | 2.2.21 |
| SDK compilazione/target | 36; minimo 31 |
| Compose BOM | 2025.10.01 |
| CameraX | 1.6.2; override diagnostico 1.7.0-rc02 |
| Media3 | 1.11.1 |
| Coroutines | 1.10.2 |

I file Gradle sono la fonte effettiva delle versioni. Il report diagnostico distingue la versione CameraX compilata, senza assumere che sia sempre quella di base.

Le attribuzioni leggibili dall'app sono in `app/src/main/assets/credits.txt` e nella schermata Informazioni e licenze. Comprendono YouRec (MIT), AndroidX/Kotlin e dipendenze Apache 2.0, Checker Framework qualifiers (MIT) e libyuv (BSD 3-Clause), incluso in CameraX. Il POM di `camera-core:1.6.2` dichiara la licenza aggiuntiva; anche il [build ufficiale CameraX](https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-work-release/camera/camera-core/build.gradle) identifica libyuv. Il relativo [testo di licenza](https://chromium.googlesource.com/libyuv/libyuv/+/refs/heads/main/LICENSE) è conservato con le attribuzioni.

La licenza Checker Framework è stata letta dal JAR effettivamente risolto (`checker-qual:3.43.0`, `META-INF/LICENSE.txt`). Le attribuzioni Apache includono anche Guava, Dagger, AutoValue, ErrorProne, J2ObjC, JSR305, JSpecify e javax/jakarta.inject. FFmpeg genera soltanto campioni sintetici nei test sul computer e non è incluso nell'APK. I modelli AI e i componenti Apple del repository originale non vengono distribuiti nella build Android.
