# NovaCam Media (Módulo Xposed / LSPatch)

Módulo de cámara virtual para Android (API 26–34) compatible con **LSPatch (sin root)** y **LSPosed**.

## Características
- **Puente IPC (`MediaBridgeProvider`) + Respaldo público (`/sdcard/DCIM/NovaCam/`):** Permite compartir el video (`.mp4`) o imagen (`.jpg`/`.png`) con la app destino sin depender de permisos de almacenamiento en la app parcheada.
- **Soporte completo Camera1, Camera2 y CameraX:** Intercepta superficies de vista previa (`SurfaceView` / `TextureView`) y flujos `ImageReader` (`YUV_420_888` y `JPEG`).
- **Ajustes en tiempo real:** Rotación (`0°`, `90°`, `180°`, `270°`), espejo horizontal manual y automático en cámara frontal, y modo de escala (`Center Crop` / `Fit Center`).

## Compilación automática en GitHub Actions
1. Crea un repositorio nuevo en GitHub y sube todo el contenido de este proyecto (incluyendo la carpeta oculta `.github/workflows/build-apk.yml`).
2. Ve a la pestaña **Actions** de tu repositorio en GitHub.
3. Ejecuta el flujo **Build NovaCam Media APK** (o espera a que termine tras hacer `push`).
4. Descarga el artefacto **NovaCamMedia-debug** que contiene `app-debug.apk`.
