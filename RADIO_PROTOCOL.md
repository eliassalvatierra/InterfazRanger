# Qué mensajes entiende la radio del estéreo (NWD)

Sacado de leer (solo lectura) `com.nwd.radio` v1.1.0.3 y `com.nwd.kernel` v2.0.3, los APK que ya estaban instalados en el equipo. No se modificó nada del equipo.

> **Nota:** el APK `com.nwd.radio` que llegó estaba cortado al final (le falta el índice del zip), pero el `classes.dex` está completo y es lo que se analizó. El servicio real de la radio vive en otro paquete, `com.nwd.radio.service/.RadioService` (no estaba entre los archivos); su código de cliente sí está incluido en `com.nwd.radio`, y de ahí sale todo lo de abajo.

## Regla clave: la radio solo responde si es la fuente activa

`Settings.System` → `mcu_current_source` (legible por cualquier app, sin permisos). **4 = radio.**
Con cualquier otro valor, la radio ignora las teclas y no emite la frecuencia.

## Mensajes que la radio ACEPTA (broadcasts, receptor dinámico sin permiso)

| Acción | Extras | Qué hace |
|---|---|---|
| `com.nwd.action.ACTION_KEY_VALUE` | `extra_key_value` (**byte**), `extra_key_type` (byte, la radio lo ignora) | Es el mismo aviso que emite el kernel al apretar una tecla física. Ver tabla de teclas. |
| `com.nwd.action.ACTION_TEST_KEY` | `extra_key_value` (**int**) | Igual que la anterior, con int. |
| `com.nwd.action.ACTION_SET_RADIO_FREQUENCE` | `extra_radio_frequence` (**int**) | Sintoniza esa frecuencia en la **banda actual**. FM = MHz×100 (8830 = 88,3). AM = kHz. |
| `com.nwd.action.radiowidget_request_frequence` | — | Pide que la radio repita la frecuencia actual (la emite por `…FREQUENCE_NEW`). |
| `com.nwd.action.ACTION_CHANGE_SOURCE` | `extra_source_id` (byte) | Aviso interno del kernel de que cambió la fuente. No es para cambiar de fuente. |

### Teclas (`extra_key_value`) que entiende la radio

| Valor | Constante del kernel | Acción de la radio |
|---|---|---|
| 4 | `RADIO` | cambiar banda |
| 5 / 6 | `NEXT` / `PREVIOUS` | buscar emisora siguiente / anterior |
| **60 / 59** | `SEARCH_INCREASE` / `SEARCH_DECREASE` | **buscar emisora siguiente / anterior** ← lo que usa ‹ › |
| **16 / 17** | `SEEK_INCREASE` / `SEEK_DECREASE` | **un paso de frecuencia + / −** ← pulsación larga de ‹ › |
| 62 / 63 | `PREFEB_INCREASE` / `PREFEB_DECREASE` | preset siguiente / anterior |
| 46 | `AMS` | auto‑guardado de emisoras |
| 61 | — | INTRO (escaneo corto) |
| 44 / 45 | — | estéreo ON/OFF · LOC/DX |
| 72 / 73 | `FM` / `AM` | pasar a FM / AM |
| 90 / 91 | — | TA / AF (RDS) |

Se evitó a propósito usar 4/5/6 desde el launcher: el kernel también escucha `ACTION_KEY_VALUE` y esas teclas pueden interpretarse como cambio de fuente o teclas multimedia. 16/17/59/60 son exclusivas de la radio.

## Mensajes que la radio EMITE

| Acción | Extras | Cuándo |
|---|---|---|
| `com.nwd.action.ACTION_SEND_RADIO_FREQUENCE_NEW` | `extra_frequence` (**int**), `extra_band` (**byte**) | Cada cambio de frecuencia, **solo si la radio es la fuente activa**. Es el que usa el launcher. |
| `com.nwd.action.ACTION_SEND_RADIO_FREQUENCE` | `extra_radio_frequence` (Parcelable `Frequency`), `extra_radio_prestore_num` (int) | Igual, pero con una clase propia de NWD (el launcher no la usa). |
| `com.nwd.action.ACTION_SEND_SCAN_RADIO_FREQUENCE` | `extra_radio_frequence` (Parcelable) | Frecuencias mientras escanea. |
| `com.nwd.action.ACTION_RADIO_STATE` | `nwd_radio_state` (int) | 0 apagada · 1 sintonizada · 2 buscando · 3 escaneando · 4 intro. |

También deja en `Settings.System` (lectura sin permiso): `nwd_radio_current_freq`, `nwd_radio_current_band`, `nwd_radio_current_freq_index`, `nwd_radio_state`. Esos los escribe la rama de radio con MCU; en radios ARM (Sprd/Allwinner) no está garantizado, por eso el launcher prioriza el broadcast y usa los ajustes solo como respaldo.

## Frecuencia y banda

- `extra_band`: **0‑2 = FM1‑FM3 · 3‑5 = AM1‑AM3 · 6‑8 = FM local 1‑3**.
- FM: entero en pasos de 10 kHz (8750…10800 = 87,50…108,00 MHz; la tabla de regiones de la app también incluye 7600‑9000 y 6500‑7400). AM: kHz (522…1620/1710).
- La app de radio muestra FM con `%.2f`; el launcher muestra 1 decimal si el segundo es 0 (88,3) y 2 si no (88,05).

## Servicio AIDL (alternativa, no usada)

`com.nwd.radio.service/.RadioService`, acción `com.nwd.radio.service.ACTION_RADIO_SERVICE`, descriptor `com.nwd.radio.service.RadioFeature`.
Transacciones: `setCurrentFrequency`=1 · `getCurrentFrequency`=2 · `seek`=3 · `search`=4 · `changeBand`=5 · `AMS`=6 · `INTRO`=7 · `prefeb`=26 · `registCallback`=24 · `sendRadioCommand`=27.
Pide devolver el parcelable `Frequency` (clase NWD) y no se sabe si el servicio exige permiso o está exportado, así que se prefirió el camino por broadcast.

## Cómo comprobarlo en el equipo

Herramientas (mantener apretado "Luces") → **Diagnóstico** muestra: `fuente` (debe ser 4 con la radio sonando), última frecuencia/banda, valores de ajustes y si ya llegó algún aviso de la radio. Si `aviso recibido=false` con la radio sonando, copiá ese texto y se ajusta.
