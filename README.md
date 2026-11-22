# VR3D Player (Android)

Reproductor de video 3D / VR que funciona **sin internet** y convierte videos 2D en 3D con IA **dentro del móvil**.
Hace juego con VR3D Studio (la versión para PC): reconoce sus archivos `_3D_LR.mp4`.

- Abre cualquier video del móvil. Detecta 3D lado a lado, lado a lado comprimido o arriba-abajo (o lo eliges a mano).
- Modos: **VR Cardboard** (dos ojos con corrección de lente), **rojo/cian** y **2D**.
- Ajustes: intercambiar ojos, separación, zoom, corrección de lente. Recuerda dónde te quedaste en cada video.
- **Convertir 2D → 3D**: IA de profundidad (Depth Anything V2 Small) en el propio móvil. El resultado se guarda en Películas/VR3D.
- Requiere Android 10 o superior y un móvil de 64 bits (arm64).

## Cómo obtener el APK sin instalar nada (GitHub Actions)

1. Entra en https://github.com e inicia sesión (o crea una cuenta gratis).
2. Arriba a la derecha: **+ → New repository**. Nombre: `vr3d-player`. Puede ser **Private**. Pulsa *Create repository*.
3. En la página del repositorio vacío pulsa **uploading an existing file**.
4. Descomprime este zip en tu PC y **arrastra TODO el contenido de la carpeta** (incluida la carpeta `.github`) a la página.
   Espera a que suba y pulsa **Commit changes**.
   - Si la carpeta `.github` no aparece en la lista, créala a mano: *Add file → Create new file*, escribe como nombre
     `.github/workflows/build.yml` y pega ahí el contenido del archivo `build.yml` de este proyecto.
5. Abre la pestaña **Actions**. Si pide permiso, pulsa *I understand my workflows, go ahead and enable them*.
   Verás una ejecución "Build APK". Tarda unos 5–10 minutos.
6. Cuando salga con ✅, entra en esa ejecución y baja hasta **Artifacts**: descarga `VR3D-Player-apk`.
7. Descomprime y pasa `app-debug.apk` al móvil (cable, Drive, etc.). Ábrelo y permite *Instalar apps desconocidas* cuando lo pida.

Si la ejecución sale con ❌: abre el paso que falló (marcado en rojo), copia el mensaje de error y pégaselo a Claude.


## Alternativa más rápida para iterar: Android Studio

1. Instala Android Studio (https://developer.android.com/studio) con las opciones por defecto.
2. Descarga el modelo de IA desde
   https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/main/onnx/model_quantized.onnx
   y guárdalo como `app/src/main/assets/depth_model.onnx` (la carpeta `assets` ya existe).
3. *File → Open* y elige esta carpeta. Espera a que termine la sincronización de Gradle (la primera vez descarga varios GB).
   Si pide instalar el SDK de Android 34, acepta. Si sugiere actualizar Gradle/AGP, ignóralo.
4. Para probar en tu móvil: activa *Opciones de desarrollador → Depuración por USB*, conéctalo por cable y pulsa ▶ *Run*.
   Para solo generar el APK: *Build → Build APK(s)*.
5. Los errores salen en la pestaña *Build*: copia el texto y pásaselo a Claude.

## Notas
- El modelo de IA (~27 MB) lo descarga GitHub al compilar y queda dentro del APK; la app no necesita internet.
- Convertir en el móvil es lento y gasta mucha batería: mantén la app abierta y el móvil cargando.
- Es un APK de depuración firmado con una clave de prueba: sirve para uso personal.
