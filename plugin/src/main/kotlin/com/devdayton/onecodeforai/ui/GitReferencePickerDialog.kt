package com.devdayton.onecodeforai.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.devdayton.onecodeforai.R
import com.devdayton.onecodeforai.android.LanguageHelper
import com.devdayton.onecodeforai.core.GitUtils
import com.devdayton.onecodeforai.ui.UiFactory.iconButton
import com.devdayton.onecodeforai.ui.UiFactory.pluginString
import com.devdayton.onecodeforai.ui.UiFactory.textView
import com.devdayton.onecodeforai.ui.CodeAssistTheme

class GitReferencePickerDialog(
    private val activity: Activity,
    private val projectRoot: java.io.File,
    private val onReferenceSelected: (String) -> Unit
) {

    fun show() {
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
        header.addView(
            activity.textView(
                activity.pluginString(R.string.git_pick_ref_title), 15f,
                CodeAssistTheme.TEXT_PRIMARY, true
            ).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        header.addView(
            activity.iconButton(
                R.drawable.ic_tech_close, activity.pluginString(R.string.close)
            ) { dialog.dismiss() }
        )
        root.addView(header)

        val tabRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, CodeAssistTheme.dp(activity, 10), 0, CodeAssistTheme.dp(activity, 6))
        }
        val scroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = true }
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(tabRow)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val tabs = mutableListOf<TextView>()
        fun selectTab(index: Int) {
            tabs.forEachIndexed { currentIndex, tab ->
                tab.setTextColor(
                    if (currentIndex == index) CodeAssistTheme.ACCENT
                    else CodeAssistTheme.TEXT_SECONDARY
                )
                tab.background = if (currentIndex == index) {
                    CodeAssistTheme.roundedBg(
                        activity, CodeAssistTheme.SURFACE_RAISED, 8f, CodeAssistTheme.OUTLINE
                    )
                } else {
                    null
                }
            }

            content.removeAllViews()
            when (index) {
                TAB_BRANCHES -> renderBranches(content, dialog)
                TAB_TAGS -> renderTags(content, dialog)
                TAB_COMMITS -> renderCommits(content, dialog)
            }
        }

        listOf(
            activity.pluginString(R.string.git_tab_branches),
            activity.pluginString(R.string.git_tab_tags),
            activity.pluginString(R.string.git_tab_commits)
        ).forEachIndexed { index, label ->
            val tab = activity.textView(label, 12f, CodeAssistTheme.TEXT_SECONDARY, true).apply {
                gravity = Gravity.CENTER
                setPadding(
                    CodeAssistTheme.dp(activity, 12), CodeAssistTheme.dp(activity, 8),
                    CodeAssistTheme.dp(activity, 12), CodeAssistTheme.dp(activity, 8)
                )
                isClickable = true
                isFocusable = true
                setOnClickListener { selectTab(index) }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            tabs.add(tab)
            tabRow.addView(tab)
        }

        selectTab(TAB_BRANCHES)
        dialog.setContentView(root)
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(
                    (activity.resources.displayMetrics.widthPixels * 0.92f).toInt(),
                    (activity.resources.displayMetrics.heightPixels * 0.78f).toInt()
                )
            }
        }
        dialog.show()
    }

    private fun renderBranches(container: LinearLayout, dialog: Dialog) {
        val branches = GitUtils.getBranches(projectRoot)
        if (branches.isEmpty()) {
            container.addView(emptyMessage(activity.pluginString(R.string.git_no_branches)))
            return
        }

        val localBranches = branches.filterNot { it.isRemote }
        val remoteBranches = branches.filter { it.isRemote }

        if (localBranches.isNotEmpty()) {
            container.addView(sectionLabel(activity.pluginString(R.string.git_local_branches)))
            localBranches.forEach { branch ->
                container.addView(
                    referenceRow(
                        branch.name,

                        if (branch.isCurrent) activity.pluginString(R.string.git_current_marker) else null,
                        CodeAssistTheme.TAG_INCLUDE_TEXT
                    ) { selectReference(branch.name, dialog) }
                )
            }
        }

        if (remoteBranches.isNotEmpty()) {
            container.addView(sectionLabel(activity.pluginString(R.string.git_remote_branches)))
            remoteBranches.forEach { branch ->
                container.addView(
                    referenceRow(
                        branch.name,

                        activity.pluginString(R.string.git_remote_marker),
                        CodeAssistTheme.TEXT_SECONDARY
                    ) { selectReference(branch.name, dialog) }
                )
            }
        }
    }

    private fun renderTags(container: LinearLayout, dialog: Dialog) {
        val tags = GitUtils.getTags(projectRoot)
        if (tags.isEmpty()) {
            container.addView(emptyMessage(activity.pluginString(R.string.git_no_tags)))
            return
        }

        tags.forEach { tag ->
            container.addView(
                referenceRow(tag.name, tag.shortHash, CodeAssistTheme.TEXT_MUTED) {
                    selectReference(tag.name, dialog)
                }
            )
        }
    }

    private fun renderCommits(container: LinearLayout, dialog: Dialog) {
        val commits = GitUtils.getRecentCommits(projectRoot, limit = RECENT_COMMIT_LIMIT)
        if (commits.isEmpty()) {
            container.addView(emptyMessage(activity.pluginString(R.string.git_no_commits)))
            return
        }

        commits.forEach { commit ->

            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    CodeAssistTheme.dp(activity, 10), CodeAssistTheme.dp(activity, 8),
                    CodeAssistTheme.dp(activity, 10), CodeAssistTheme.dp(activity, 8)
                )
                background = CodeAssistTheme.selectableBg(activity)
                isClickable = true
                isFocusable = true
                setOnClickListener { selectReference(commit.hash, dialog) }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = CodeAssistTheme.dp(activity, 4)
                }
            }

            val heading = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            heading.addView(
                activity.textView(commit.shortHash, 11f, CodeAssistTheme.ACCENT, true).apply {
                    typeface = android.graphics.Typeface.MONOSPACE
                }
            )
            heading.addView(
                activity.textView("  ·  ${commit.date}", 10f, CodeAssistTheme.TEXT_MUTED).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
            )
            row.addView(heading)

            row.addView(
                activity.textView(commit.author, 10f, CodeAssistTheme.TEXT_SECONDARY).apply {
                    setPadding(0, CodeAssistTheme.dp(activity, 2), 0, 0)
                }
            )

            row.addView(
                activity.textView(commit.message, 12f, CodeAssistTheme.TEXT_PRIMARY).apply {
                    setPadding(0, CodeAssistTheme.dp(activity, 2), 0, 0)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
            )
            container.addView(row)
        }
    }

    private fun selectReference(reference: String, dialog: Dialog) {
        dialog.dismiss()
        onReferenceSelected(reference)
    }

    private fun sectionLabel(value: String) = activity.textView(

        value.uppercase(LanguageHelper.detectIdeLocale(activity)),
        10f, CodeAssistTheme.TEXT_MUTED, true
    ).apply {
        setPadding(0, CodeAssistTheme.dp(activity, 10), 0, CodeAssistTheme.dp(activity, 4))
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

    private fun referenceRow(
        label: String,
        badge: String?,
        badgeColor: Int,
        onClick: () -> Unit
    ): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(
            CodeAssistTheme.dp(activity, 10), CodeAssistTheme.dp(activity, 9),
            CodeAssistTheme.dp(activity, 10), CodeAssistTheme.dp(activity, 9)
        )
        background = CodeAssistTheme.selectableBg(activity)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {

            bottomMargin = CodeAssistTheme.dp(activity, 4)
        }

        addView(
            activity.textView(label, 12f, CodeAssistTheme.TEXT_PRIMARY).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                typeface = android.graphics.Typeface.MONOSPACE
            }
        )
        if (badge != null) {
            addView(
                activity.textView(badge, 9f, badgeColor, true).apply {
                    setPadding(
                        CodeAssistTheme.dp(activity, 6), CodeAssistTheme.dp(activity, 2),
                        CodeAssistTheme.dp(activity, 6), CodeAssistTheme.dp(activity, 2)
                    )
                    background = CodeAssistTheme.roundedBg(
                        activity, CodeAssistTheme.SURFACE_RAISED, 5f
                    )
                }
            )
        }
    }

    private companion object {

        const val TAB_BRANCHES = 0

        const val TAB_TAGS = 1

        const val TAB_COMMITS = 2

        const val RECENT_COMMIT_LIMIT = 60
    }
}