package com.example.youtubeapp.ui

import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.youtubeapp.R
import com.example.youtubeapp.data.model.Video
import com.example.youtubeapp.data.repository.Recommendations
import com.google.android.material.snackbar.Snackbar

/**
 * Меню ролика с тремя точками (шаг 3 статьи Т—Ж «Как настроить для себя
 * рекомендации „Ютуба“»):
 *
 *  - «Не интересует» — убрать конкретное видео из рекомендаций
 *    (с возможностью отмены, как в „Ютубе“);
 *  - «Не рекомендовать видео с этого канала» — исключить блогера.
 *
 * Состояние хранится локально ([Recommendations]) и применяется ко всем
 * рекомендательным лентам; [onChanged] вызывается после изменения, чтобы
 * вызывающая сторона перерисовала список.
 */
object VideoMenu {

    private const val ID_NOT_INTERESTED = 1
    private const val ID_HIDE_CHANNEL = 2

    fun show(
        activity: AppCompatActivity,
        anchor: View,
        container: View,
        video: Video,
        onChanged: () -> Unit
    ) {
        val popup = PopupMenu(activity, anchor)
        popup.menu.add(0, ID_NOT_INTERESTED, 0, activity.getString(R.string.rec_not_interested))
        popup.menu.add(0, ID_HIDE_CHANNEL, 1, activity.getString(R.string.rec_hide_channel))

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ID_NOT_INTERESTED -> {
                    Recommendations.hideVideo(activity, video.id)
                    onChanged()
                    Snackbar.make(container, R.string.rec_video_hidden, Snackbar.LENGTH_LONG)
                        .setAction(R.string.rec_undo) {
                            Recommendations.unhideVideo(activity, video.id)
                            onChanged()
                        }
                        .show()
                    true
                }
                ID_HIDE_CHANNEL -> {
                    Recommendations.hideChannel(activity, video.channelId)
                    onChanged()
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.rec_channel_hidden, video.channelTitle),
                        Toast.LENGTH_SHORT
                    ).show()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }
}
