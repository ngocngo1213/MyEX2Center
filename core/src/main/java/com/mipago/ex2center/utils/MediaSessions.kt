package com.mipago.ex2center.utils

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState

/**
 * Every app's active media sessions. Needs MEDIA_CONTENT_CONTROL (granted to android.uid.system);
 * without it (emulator, non-system build) the list is empty. Binder calls: call off the main thread.
 */
object MediaSessions {

    fun controllers(context: Context): List<MediaController> = try {
        (context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager).getActiveSessions(null)
    } catch (_: Throwable) {
        emptyList()
    }

    /** The playing session, else the most recent one. */
    fun current(context: Context): MediaController? = controllers(context).let { list ->
        list.firstOrNull { it.isPlaying() } ?: list.firstOrNull()
    }

    /** What [pkg] is playing right now ("" when the title is unknown), or null when it is not playing. */
    fun playingTitle(context: Context, pkg: String): String? = controllers(context)
        .firstOrNull { it.packageName == pkg && it.isPlaying() }
        ?.let { c -> try { c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) } catch (_: Throwable) { null } ?: "" }

    private fun MediaController.isPlaying() = try {
        playbackState?.state == PlaybackState.STATE_PLAYING
    } catch (_: Throwable) {
        false // the player died between listing and asking
    }
}
