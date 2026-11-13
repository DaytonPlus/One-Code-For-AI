package com.devdayton.onecodeforai.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.devdayton.onecodeforai.R
import com.devdayton.onecodeforai.android.LanguageHelper
import com.devdayton.onecodeforai.core.GitUtils
import com.devdayton.onecodeforai.ui.UiFactory.iconButton
import com.devdayton.onecodeforai.ui.UiFactory.pluginString
import com.devdayton.onecodeforai.ui.UiFactory.textView
import com.devdayton.onecodeforai.ui.CodeAssistTheme
import java.io.File

class GitDiffPreviewDialog(
    private val activity: Activity,
    private val projectRoot: File
) {

    fun show(rawReference: String) {
        val reference = rawReference.trim()
        if (reference.isEmpty()) {
            showMessage(R.string.git_ref_empty)
            return
        }

        val resolvedReference = GitUtils.resolveRefShort(projectRoot, reference)
        if (resolvedReference == null) {
            showMessage(R.string.git_ref_invalid)
            return
        }

        val stats = GitUtils.getDiffStats(projectRoot, reference)
        val diff = GitUtils.getDiff(projectRoot, reference)
        val dialog = Dialog(activity).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setCancelable(true)
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                CodeAssistTheme.dp(activity, 16), CodeAssistTheme.dp(activity, 14),
                CodeAssistTheme.dp(activity, 16), CodeAssistTheme.dp(activity, 14)
            )
            background = CodeAssistTheme.roundedBg(
                activity, CodeAssistTheme.BG, 14f, CodeAssistTheme.OUTLINE
            )
        }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val headerText = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerText.addView(
            activity.textView(
                activity.pluginString(R.string.git_diff_title, resolvedReference), 14f,
                CodeAssistTheme.TEXT_PRIMARY, true
            )
        )

        if (stats != null) {
            headerText.addView(
                activity.textView(
                    activity.pluginString(
                        R.string.git_diff_stats,
                        stats.filesChanged, stats.insertions, stats.deletions
                    ),
                    10f, CodeAssistTheme.TEXT_SECONDARY
                ).apply {
                    setPadding(0, CodeAssistTheme.dp(activity, 4), 0, 0)
                }
            )
        }
        header.addView(headerText)
        header.addView(
            activity.iconButton(
                R.drawable.ic_tech_close, activity.pluginString(R.string.close)
            ) { dialog.dismiss() }
        )
        root.addView(header)

        if (stats == null || stats.filesChanged == 0) {
            root.addView(emptyMessage(activity.pluginString(R.string.git_diff_no_changes)))
        } else {
            root.addView(buildChangedFilesList(stats.files))

            root.addView(
                activity.textView(

                    activity.pluginString(R.string.git_diff_content)
                        .uppercase(LanguageHelper.detectIdeLocale(activity)),
                    10f, CodeAssistTheme.TEXT_MUTED, true
                ).apply {
                    setPadding(0, CodeAssistTheme.dp(activity, 10), 0, CodeAssistTheme.dp(activity, 4))
                }
            )

            root.addView(buildDiffContent(diff.orEmpty()))
        }

        dialog.setContentView(root)

        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(
                    (activity.resources.displayMetrics.widthPixels * 0.95f).toInt(),
                    (activity.resources.displayMetrics.heightPixels * 0.88f).toInt()
                )
            }
        }
        dialog.show()
    }

    private fun buildChangedFilesList(files: List<GitUtils.FileChange>): ScrollView {
        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, CodeAssistTheme.dp(activity, 150)
            )
        }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, CodeAssistTheme.dp(activity, 8), 0, CodeAssistTheme.dp(activity, 8))
        }
        scroll.addView(column)

        files.forEach { file ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    CodeAssistTheme.dp(activity, 4), CodeAssistTheme.dp(activity, 4),
                    CodeAssistTheme.dp(activity, 4), CodeAssistTheme.dp(activity, 4)
                )
            }

            val (statusLabel, statusColor) = when (file.status) {
                'A' -> "A" to CodeAssistTheme.TAG_INCLUDE_TEXT
                'D' -> "D" to CodeAssistTheme.TAG_EXCLUDE_TEXT
                'R' -> "R" to CodeAssistTheme.TAG_CONFLICT_TEXT
                else -> "M" to CodeAssistTheme.TEXT_SECONDARY
            }
            row.addView(
                activity.textView(statusLabel, 10f, statusColor, true).apply {
                    typeface = android.graphics.Typeface.MONOSPACE

                    layoutParams = LinearLayout.LayoutParams(
                        CodeAssistTheme.dp(activity, 18), ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
            )
            row.addView(
                activity.textView(file.path, 10f, CodeAssistTheme.TEXT_PRIMARY).apply {
                    typeface = android.graphics.Typeface.MONOSPACE
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    maxLines = 1

                    ellipsize = TextUtils.TruncateAt.MIDDLE
                }
            )
            if (file.insertions > 0 || file.deletions > 0) {
                row.addView(
                    activity.textView(
                        "+${file.insertions} −${file.deletions}",
                        9f, CodeAssistTheme.TEXT_MUTED
                    ).apply {
                        typeface = android.graphics.Typeface.MONOSPACE
                    }
                )
            }
            column.addView(row)
        }
        return scroll
    }

    private fun buildDiffContent(diff: String): ScrollView {
        val verticalScroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = true
            background = CodeAssistTheme.roundedBg(
                activity, CodeAssistTheme.SURFACE, 8f, CodeAssistTheme.OUTLINE
            )

            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val diffText = TextView(activity).apply {
            text = diff
            textSize = 10f
            setTextColor(CodeAssistTheme.TEXT_PRIMARY)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(
                CodeAssistTheme.dp(activity, 8), CodeAssistTheme.dp(activity, 8),
                CodeAssistTheme.dp(activity, 8), CodeAssistTheme.dp(activity, 8)
            )

            setHorizontallyScrolling(true)
        }
        verticalScroll.addView(
            HorizontalScrollView(activity).apply { addView(diffText) }
        )
        return verticalScroll
    }

    private fun emptyMessage(value: String) = activity.textView(
        value, 12f, CodeAssistTheme.TEXT_MUTED
    ).apply {
        gravity = Gravity.CENTER
        setPadding(0, CodeAssistTheme.dp(activity, 24), 0, CodeAssistTheme.dp(activity, 24))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun showMessage(resourceId: Int) {
        Toast.makeText(activity, activity.pluginString(resourceId), Toast.LENGTH_SHORT).show()
    }
}