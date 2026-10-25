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
