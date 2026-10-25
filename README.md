# Mi Ampolleta (Tuya BLE, Web Bluetooth)

App web para controlar una ampolleta Tuya/Smart Life por Bluetooth, sin la app oficial ni la nube.

## Archivos
- `index.html`: interfaz (encender/apagar, blanco/color, brillo, temperatura, color, DP manual y log).
- `tuya-ble.js`: protocolo Tuya BLE v3 (handshake, cifrado AES-CBC, CRC, fragmentación, datapoints).
- `test/protocolo.test.js`: prueba contra una ampolleta simulada (`node test/protocolo.test.js "$PWD/tuya-ble.js"`).
- `test/vinculacion.test.js`: prueba la vinculación de una ampolleta sin vincular (`node test/vinculacion.test.js "$PWD/tuya-ble.js"`).

## Credenciales
Carga en la app el `devices.json` que genera `python -m tinytuya wizard` (o pega su contenido). La app toma de ahí
`id`, `key`, `uuid` y el `mapping` de datapoints de cada ampolleta.

Web Bluetooth no muestra la MAC y todas las ampolletas se llaman "TY", así que al conectar la app prueba las credenciales
cargadas hasta que una empareja (unos 6 s por cada intento fallido) y recuerda cuál era para la próxima vez.
Se pueden conectar varias a la vez y controlarlas juntas o por separado.

## Cómo abrirla
Web Bluetooth exige HTTPS o `localhost` y funciona en Chrome/Edge (Android, Windows, Mac, Linux), no en iPhone.
- **Computador:** en esta carpeta corre `python -m http.server 8000` y abre http://localhost:8000 en Chrome.
- **Android:** súbela a GitHub Pages (o cualquier hosting HTTPS) y ábrela en Chrome.

Cierra Smart Life antes de conectar: la ampolleta acepta una sola conexión Bluetooth a la vez.

## Vinculación
Si la ampolleta está sin vincular (por ejemplo, después de un reinicio), la app la vincula sola con la `local_key`
del devices.json, igual que lo haría Smart Life, así que no hace falta abrir Smart Life antes. Cómo funciona,
según el SDK BLE de Tuya: sin vincular, la ampolleta tiene la clave de login en ceros; la app le pide la información
con esa clave y luego le envía el PAIR con uuid + local_key[:6] + device_id, que la ampolleta guarda.

En Windows, Chrome suele cortar la conexión con estas ampolletas apenas conecta; en Android funciona.

## Datapoints de tus ampolletas (TR-BB-01)
1 encendido (bool) · 2 modo (enum: 0 blanco, 1 color, 2 escena, 3 música) · 3 brillo (10–1000) · 4 temperatura (0–1000) ·
5 color (`HHHHSSSSVVVV` en hex: tono 0–360, saturación y valor 0–1000).
Lo que reporta cada ampolleta queda en el log de "Avanzado".

## Aire acondicionado (app Android)
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
- El navegador no puede mandar paquetes UDP, por eso esto está solo en la app Android.
