# Mi IPTV — proyecto Android

Proyecto fuente para Android TV / TV Box (Kotlin + AndroidX Media3).

## Funciones incluidas en el código
- Importar listas M3U desde el almacenamiento.
- Reproducir streams compatibles con ExoPlayer.
- Categorías TV EN VIVO, VOD, SERIES, TELENOVELAS, DEPORTES y categorías originales de países/proveedores.
- Clasificación automática de categorías desde `group-title`: VOD/PELÍCULAS, SERIES, TELENOVELAS y DEPORTES.
- Acceso directo a Películas, Series y Telenovelas desde la barra horizontal de categorías.
- Detección adicional de telenovelas por palabras clave en el nombre y de episodios por patrones S01E02 o 1x02.
- Búsqueda, favoritos y orden alfabético.
- Mostrar imágenes desde `tvg-logo` cuando la lista M3U proporciona una URL válida.
- Agregar, editar y eliminar elementos.
- Exportar M3U completo o la categoría seleccionada.
- Exportar respaldo JSON.
- Detección de temporadas/capítulos con patrones S01E02, 1x02 y “Temporada 1 Capítulo 2”.
- Persistencia local.

## Generar el APK automáticamente con GitHub Actions
1. En GitHub crea un repositorio nuevo.
2. Sube **el contenido** de esta carpeta `iptv_player` (no el ZIP dentro de otra carpeta).
3. Abre la pestaña **Actions** del repositorio.
4. Selecciona **Build Mi IPTV APK** y pulsa **Run workflow**. También se ejecuta al subir cambios a `main` o `master`.
5. Cuando termine en verde, abre esa ejecución y descarga el artefacto **MiIPTV-debug-apk**. Dentro estará `app-debug.apk`.

La compilación usa JDK 17 y Gradle 8.7. En cada ejecución, GitHub genera el Gradle Wrapper oficial, compila con `./gradlew` y publica dos archivos descargables: el APK y `MiIPTV-proyecto-con-wrapper.zip` (proyecto con `gradlew`, `gradlew.bat` y `gradle/wrapper/`). La compilación aún debe ejecutarse en tu repositorio para confirmar que genera el APK.

## Compilar localmente
Después de descargar el artefacto del proyecto con Wrapper desde GitHub Actions, descomprimirlo y ejecutar `./gradlew :app:assembleDebug` (en Windows: `gradlew.bat :app:assembleDebug`). Se requiere JDK 17 y Android SDK 35.

## Límites conocidos
- Las carátulas dependen de que la lista incluya URLs `tvg-logo`; no se consulta automáticamente una base externa de películas.
- La agrupación de episodios depende de patrones reconocibles en el nombre.
- El respaldo JSON se exporta; la restauración de JSON aún no está implementada.
- No incluye listas ni contenido IPTV. Carga una lista propia autorizada.

## Administración de listas
La pantalla principal incluye ahora **Administrar lista**: permite cambiar el nombre de la lista guardada, exportar la lista completa en M3U, exportar una categoría concreta y vaciar la lista actual. Los elementos individuales pueden agregarse, editarse (nombre, URL, categoría y logo), marcarse como favoritos o eliminarse desde sus controles.
