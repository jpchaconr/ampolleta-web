# Ampolletas (app Android)

App Android para controlar ampolletas Tuya/Smart Life por Bluetooth y aires Kendal/AUX por Wi-Fi, sin las apps
oficiales ni la nube. Está en `android/` (Kotlin + Jetpack Compose, Material 3).

## Descargar
**[ampolletas.apk](https://github.com/jpchaconr/ampolleta-web/releases/download/android-main/ampolletas.apk)**:
lo compila GitHub Actions en cada cambio a `main`. Ábrelo desde el teléfono para instalarlo (Android 8 o superior).

La página de GitHub Pages (`index.html`) es solo ese botón de descarga. La antigua app web con Web Bluetooth
(`tuya-ble.js` y sus pruebas) quedó en el historial de git.

Cierra Smart Life antes de conectar: la ampolleta acepta una sola conexión Bluetooth a la vez.

## Vinculación
La app vincula sola las ampolletas sin vincular: genera sus propias credenciales y se las envía, como lo haría Smart
Life (según el SDK BLE de Tuya: sin vincular, la ampolleta tiene la clave de login en ceros; la app le pide la
información con esa clave y luego le envía el PAIR con uuid + clave + id). Si una aparece "vinculada a otra app",
reiníciala apagando y prendiendo el interruptor 3 veces seguidas hasta que parpadee.

## Datapoints de tus ampolletas (TR-BB-01)
1 encendido (bool) · 2 modo (enum: 0 blanco, 1 color, 2 escena, 3 música) · 3 brillo (10–1000) · 4 temperatura (0–1000) ·
5 color (`HHHHSSSSVVVV` en hex: tono 0–360, saturación y valor 0–1000).
Lo que reporta cada ampolleta queda en el "Registro" de la app (menú ⋮ del inicio).

## Aire acondicionado
Los aires Kendal (fabricados por AUX) con Wi-Fi usan un módulo Broadlink que se configura con la app AC Freedom. La app
Android los controla directo por la red local (UDP, puerto 80), sin la nube, con el protocolo de
[python-broadlink](https://github.com/mjg59/python-broadlink): encendido, temperatura, modo, ventilador, aleta, dormir,
turbo y pantalla, y muestra la temperatura ambiente.
- Para conectar el aire a tu Wi-Fi sin AC Freedom: "Agregar" → "Aire acondicionado" → "Vincular un aire nuevo al Wi-Fi".
  Con el aire en modo vinculación (8 veces "Health" en el control), la app busca la red que crea el aire, se conecta
  sola (Android 10 o superior; Android pide confirmarlo), le envía tu Wi-Fi de casa (2,4 GHz) y luego lo busca en tu red
  y lo agrega. El nombre de tu Wi-Fi se rellena solo (con permiso de ubicación) y la clave queda guardada en el teléfono.
  Así el aire queda sin el bloqueo que pone AC Freedom, que hace que rechace la conexión (error -5).
- El teléfono tiene que estar en el mismo Wi-Fi que el aire.
- En la app: "Agregar" → "Aire acondicionado"; la app lo busca en la red. Si el router le cambia la IP, lo vuelve a buscar solo.
