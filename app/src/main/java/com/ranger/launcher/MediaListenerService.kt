package com.ranger.launcher

import android.service.notification.NotificationListenerService

/**
 * Servicio vacío. Existe solo para que Android permita al launcher leer las
 * sesiones de música activas (Spotify, Bluetooth, etc.) una vez que el usuario
 * le da "Acceso a notificaciones". No lee ni guarda notificaciones.
 */
class MediaListenerService : NotificationListenerService()
