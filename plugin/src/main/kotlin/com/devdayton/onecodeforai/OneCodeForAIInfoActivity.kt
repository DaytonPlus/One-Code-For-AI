package com.devdayton.onecodeforai

import com.devdayton.onecodeforai.R
import com.devdayton.onecodeforai.android.LanguageHelper
import com.devdayton.onecodeforai.android.PluginSettings
import com.devdayton.onecodeforai.ui.CodeAssistTheme

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Plugin info screen.
 *
 * Sections, top to bottom:
 *   1. Header          - // INFO, square close, hairline
 *   2. Hero            - square icon slot, name, subtitle, [vX] [● STABLE]
 *   3. What's New      - // 01 WHAT'S NEW
 *   4. Installation    - // 02 INSTALLATION
 *   5. How to use      - // 03 HOW TO USE
 *   6. Features        - // 04 FEATURES
 *   7. Help & FAQ      - // 05 HELP & FAQ
 *   8. Links           - // 06 LINKS
 *   9. Technical info  - // 07 TECH INFO
 *  10. Footer          - // Made with love + author
 *
 * Localization
 * ------------
 * [attachBaseContext] wraps the base context with the locale returned by
 * [LanguageHelper.wrapContextWithIdeLocale]. That helper honours the
 * user-selected language from the plugin settings page (or falls back to the
 * host IDE language when "Auto" is selected), so an InfoActivity opened
 * *after* the user changes the language starts in the new language
 * automatically, without any additional plumbing here. Already-open instances
 * keep the language they were created with, matching the standard Android
 * behavior for runtime locale changes.
 *
 * Visual design — tech / minimalist
 * ---------------------------------
 * This screen deliberately rejects the "cards and bubbles" pattern that is
 * common in modern mobile UIs. The aesthetic is closer to a development
 * panel (Grafana, VS Code, a terminal): flat surfaces, thin hairlines,
 * angular slots, monospace typography for anything structural, and a single
 * accent colour used as punctuation rather than as fill.
 *
 *  - **No cards.** Sections are just header + body, separated by a 1dp
 *    hairline. This keeps the screen dense and readable, and lets the eye
 *    scan a full section without a rounded container competing for
 *    attention.
 *  - **Monospace everywhere structural.** Section numbers ("01"), labels,
 *    values, the version tag, the status dot text, and the footer are all
 *    monospace. This is the single most defining trait of the tech look:
 *    it reads as machine output, not as marketing copy.
 *  - **Square icon slots.** Feature, link, step and hero icons live inside
 *    a small square slot with a 1dp accent-tinted border and a 2dp corner
 *    radius. The slot is the "cell" of the layout; it gives every row a
 *    consistent left edge without drawing a visible card around it.
 *  - **Terminal punctuation.** Section headers use `// 01 TITLE`, the FAQ
 *    uses a `>` prefix that rotates 90° when the answer expands, links use
 *    a trailing `→`, the version is written as `[v1.0.90]`, and the footer
 *    is prefixed with `//`. These are the small cues that make the screen
 *    read as a dev tool rather than a generic app.
 *  - **Hairlines as structure.** Every section boundary, every technical
 *    info row, and the top bar are separated by a 1dp line at low alpha.
 *    Lines are the only structural element besides spacing.
 *  - **Single accent.** [CodeAssistTheme.ACCENT] is the only colour used
 *    for emphasis; it appears on the `//` marks, section numbers, icon
 *    strokes, the `>` prefix, the `→` arrow and the version tag. Everything
 *    else is one of the theme's neutral text or outline tones.
 *  - **Minimal motion.** A short staggered fade-and-slide entrance for the
 *    sections, an animated rotation for the FAQ chevron, and a subtle
 *    press-scale on the interactive rows. Everything is gated by
 *    [PluginSettings.areAnimationsEnabled].
 *
 * Everything is built with platform widgets only — no AndroidX, no
 * RecyclerView, no custom views. That keeps the plugin's dependency
 * footprint at zero while still allowing expressive visuals.
 *
 * Host package
 * ------------
 * The "Open CodeAssist" row launches the host IDE by package name. The
 * package is resolved from `R.string.host_ide_package` rather than being
 * hardcoded in this class, so a fork or a rebranded host only needs a string
 * override — not a code change. If the string is missing or empty, the row
 * is silently disabled (see [buildLinksSection]).
 *
 * SDK values
 * ----------
 * `minSdk`, `targetSdk`, the version name and the APK size are read from
 * the package manager exactly once during [onCreate] and cached on fields.
 * A previous version read them on every call, which meant several separate
 * `PackageManager` round-trips per render. The public getters on this class
 * return the cached values, so the rest of the screen is unchanged.
 */
class OneCodeForAIInfoActivity : Activity() {

    private val repositoryUrl  = "https://github.com/daytonplus/one-code-for-ai"
    private val issuesUrl      = "$repositoryUrl/issues"
    private val licenseUrl     = "$repositoryUrl/blob/main/LICENSE"
    private val authorUrl      = "https://github.com/daytonplus"

    private var cachedVersionName: String = "?"
    private var cachedApkSize: String = "—"
    private var cachedMinSdk: Int = 26
    private var cachedTargetSdk: Int = 36

    /**
     * Master switch for the entrance and press animations. Read through a
     * getter so a change made in the plugin settings is picked up the next
     * time the screen is opened, without needing to recreate the activity.
     */
    private val animationsEnabled: Boolean
        get() = PluginSettings.areAnimationsEnabled(this)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageHelper.wrapContextWithIdeLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // One-shot PackageManager lookups. See the class KDoc for rationale.
        cachedVersionName = readVersionName()
        cachedApkSize = readApkSize()
        cachedMinSdk = readMinSdk()
        cachedTargetSdk = readTargetSdk()

        // The statusBarColor / navigationBarColor setters were deprecated in
        // API 35 (Android 15) in favour of edge-to-edge + WindowInsetsController.
        // The modern replacement requires androidx.activity.enableEdgeToEdge,
        // which the plugin deliberately does not depend on. The legacy setters
        // are still the only portable way to colour the system bars on the
        // plugin's minSdk (26), so the deprecation is intentionally suppressed
        // rather than worked around.
        @Suppress("DEPRECATION")
        window?.apply {
            statusBarColor = BG
            navigationBarColor = BG
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                isStatusBarContrastEnforced = false
                isNavigationBarContrastEnforced = false
            }
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(32))
        }

        column.addView(buildTopBar())
        column.addView(sectionDivider(topDp = 12, bottomDp = 0))
        column.addView(buildHeroSection())
        column.addView(sectionDivider(topDp = 16, bottomDp = 0))
        column.addView(buildWhatsNewSection())
        column.addView(sectionDivider(topDp = 18, bottomDp = 0))
        column.addView(buildPluginInstallSection())
        column.addView(sectionDivider(topDp = 18, bottomDp = 0))
        column.addView(buildInstallSection())
        column.addView(sectionDivider(topDp = 18, bottomDp = 0))
        column.addView(buildFeaturesSection())
        column.addView(sectionDivider(topDp = 18, bottomDp = 0))
        column.addView(buildHelpSection())
        column.addView(sectionDivider(topDp = 18, bottomDp = 0))
        column.addView(buildLinksSection())
        column.addView(sectionDivider(topDp = 18, bottomDp = 0))
        column.addView(buildTechSection())
        column.addView(spacer(24))
        column.addView(buildFooter())

        scroll.addView(column)
        setContentView(scroll)

        // Stagger the entrance after layout so the child heights are
        // already measured and no section visibly jumps when its animation
        // starts. Guard against a fast finish() before the post runs.
        scroll.post {
            if (!isFinishing && !isDestroyed) playEntrance(column)
        }
    }

    // ---------------------------------------------------------------- sections

    /**
     * Top bar: `// INFO` on the left (accent slashes, muted text), a small
     * square close button on the right (1dp outline, no fill), then a
     * hairline underneath that separates the chrome from the body.
     */
    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
        }

        val label = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        label.addView(TextView(this).apply {
            text = "//"
            setTextColor(CodeAssistTheme.ACCENT)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, dp(8), 0)
        })
        label.addView(TextView(this).apply {
            text = getString(R.string.info_title)
                .uppercase(LanguageHelper.detectIdeLocale(this@OneCodeForAIInfoActivity))
            setTextColor(CodeAssistTheme.TEXT_SECONDARY)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.15f
        })
        bar.addView(label)

        val close = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            isClickable = true
            isFocusable = true
            background = RippleDrawable(
                ColorStateList.valueOf(CodeAssistTheme.ACCENT.withAlpha(40)),
                slotBackground(CodeAssistTheme.OUTLINE.withAlpha(140), radiusDp = 2f),
                null
            )
            contentDescription = getString(R.string.close)
            setOnClickListener { finish() }
        }
        close.addView(TextView(this).apply {
            text = "×"
            setTextColor(CodeAssistTheme.TEXT_SECONDARY)
            textSize = 16f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        })
        attachPressScale(close)
        bar.addView(close)

        return bar
    }

    /**
     * Hero block: square icon slot (48dp, 1dp accent-tinted border, 2dp
     * radius), app name in medium sans, subtitle in muted sans, and a
     * monospace tag row `[vX]  ● STABLE` underneath. No background, no
     * rounded card — the hero is just the first line of the panel.
     */
    private fun buildHeroSection(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }

        // Square icon slot. The 2dp radius is the only rounding on the
        // whole hero — everything else is angular.
        val iconSlot = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                marginEnd = dp(14)
            }
            background = slotBackground(
                CodeAssistTheme.ACCENT.withAlpha(90),
                radiusDp = 2f
            )
        }
        iconSlot.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_tech_rocket)
            setColorFilter(CodeAssistTheme.ACCENT)
            layoutParams = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)
        })
        row.addView(iconSlot)

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        texts.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(CodeAssistTheme.TEXT_PRIMARY)
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = -0.005f
        })

        texts.addView(TextView(this).apply {
            text = getString(R.string.info_subtitle)
            setTextColor(CodeAssistTheme.TEXT_SECONDARY)
            textSize = 11f
            setPadding(0, dp(2), 0, dp(8))
        })

        // Tag row: [v1.0.90]    ● STABLE — all monospace, no chips.
        val tagRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tagRow.addView(TextView(this).apply {
            text = "[v$cachedVersionName]"
            setTextColor(CodeAssistTheme.ACCENT)
            textSize = 10f
            typeface = Typeface.MONOSPACE
        })
        tagRow.addView(TextView(this).apply {
            text = "●"
            setTextColor(CodeAssistTheme.TAG_INCLUDE_TEXT)
            textSize = 8f
            setPadding(dp(12), 0, dp(6), 0)
        })
        tagRow.addView(TextView(this).apply {
            text = getString(R.string.tech_info_status_stable)
                .uppercase(LanguageHelper.detectIdeLocale(this@OneCodeForAIInfoActivity))
            setTextColor(CodeAssistTheme.TAG_INCLUDE_TEXT)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.12f
        })
        texts.addView(tagRow)

        row.addView(texts)
        return row
    }

    /**
     * What's New. Header `// 01 WHAT'S NEW`, then the version line, then
     * one row per changelog item (small icon + text, no container).
     */
    private fun buildWhatsNewSection(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        column.addView(sectionHeader("01", R.string.whats_new_title))

        column.addView(TextView(this).apply {
            text = getString(R.string.whats_new_version, getVersionName())
            setTextColor(CodeAssistTheme.ACCENT)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(2), 0, dp(8))
        })

        val changes = listOf(
            R.string.whats_new_item_1,
            R.string.whats_new_item_2,
            R.string.whats_new_item_3,
            R.string.whats_new_item_4,
            R.string.whats_new_item_5,
            R.string.whats_new_item_6,
            R.string.whats_new_item_7,
            R.string.whats_new_item_8,
            R.string.whats_new_item_9,
            R.string.whats_new_item_10,
        )
        changes.forEach { column.addView(iconRow(R.drawable.ic_tech_check, getString(it))) }

        return column
    }

    /**
     * Installation guide. Header `// 02 INSTALLATION`, subtitle, then
     * numbered steps (01..05) with monospace numbers in the accent colour.
     */
    private fun buildPluginInstallSection(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        column.addView(sectionHeader("02", R.string.guide_header_title))
        column.addView(TextView(this).apply {
            text = getString(R.string.guide_header_subtitle)
            setTextColor(CodeAssistTheme.TEXT_SECONDARY)
            textSize = 11f
            setPadding(0, 0, 0, dp(6))
        })

        data class Step(val num: String, val title: Int, val desc: Int)
        val steps = listOf(
            Step("01", R.string.guide_step1_title, R.string.guide_step1_desc),
            Step("02", R.string.guide_step2_title, R.string.guide_step2_desc),
            Step("03", R.string.guide_step3_title, R.string.guide_step3_desc),
            Step("04", R.string.guide_step4_title, R.string.guide_step4_desc),
            Step("05", R.string.guide_step5_title, R.string.guide_step5_desc),
        )
        steps.forEach { column.addView(stepRow(it.num, getString(it.title), getString(it.desc))) }

        return column
    }

    /**
     * How to use. Header `// 03 HOW TO USE`, then four numbered steps.
     */
    private fun buildInstallSection(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        column.addView(sectionHeader("03", R.string.integration_title))

        data class Step(val num: String, val title: Int, val desc: Int)
        val steps = listOf(
            Step("01", R.string.step_1_title, R.string.step_1_desc),
            Step("02", R.string.step_2_title, R.string.step_2_desc),
            Step("03", R.string.step_3_title, R.string.step_3_desc),
            Step("04", R.string.step_4_title, R.string.step_4_desc),
        )
        steps.forEach { column.addView(stepRow(it.num, getString(it.title), getString(it.desc))) }

        return column
    }

    /**
     * Features list. Header `// 04 FEATURES`, ten rows with small slot
     * icons and single-line labels. No separators — the spacing between
     * rows is enough structure for a list of single-line items.
     */
    private fun buildFeaturesSection(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        column.addView(sectionHeader("04", R.string.features_title))

        val features = listOf(
            R.drawable.ic_tech_filter   to R.string.feature_1,
            R.drawable.ic_tech_git      to R.string.feature_2,
            R.drawable.ic_tech_code     to R.string.feature_3,
            R.drawable.ic_tech_share    to R.string.feature_4,
            R.drawable.ic_tech_folder   to R.string.feature_5,
            R.drawable.ic_tech_chart    to R.string.feature_6,
            R.drawable.ic_tech_check    to R.string.feature_7,
            R.drawable.ic_tech_search   to R.string.feature_8,
            R.drawable.ic_tech_settings to R.string.feature_9,
            R.drawable.ic_tech_rocket   to R.string.feature_10,
        )
        features.forEach { column.addView(iconRow(it.first, getString(it.second))) }

        return column
    }

    /**
     * Help & FAQ. Header `// 05 HELP & FAQ`, subtitle, then one collapsible
     * item per Q/A pair. Each item uses a `>` prefix (terminal style) that
     * rotates 90° when the answer expands. Items are separated by a thin
     * hairline so the list reads as a table rather than a card stack.
     */
    private fun buildHelpSection(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        column.addView(sectionHeader("05", R.string.help_title))
        column.addView(TextView(this).apply {
            text = getString(R.string.help_header_subtitle)
            setTextColor(CodeAssistTheme.TEXT_SECONDARY)
            textSize = 11f
            setPadding(0, 0, 0, dp(4))
        })

        val items = listOf(
            R.string.help_q1  to R.string.help_a1,
            R.string.help_q2  to R.string.help_a2,
            R.string.help_q3  to R.string.help_a3,
            R.string.help_q4  to R.string.help_a4,
            R.string.help_q5  to R.string.help_a5,
            R.string.help_q6  to R.string.help_a6,
            R.string.help_q7  to R.string.help_a7,
            R.string.help_q8  to R.string.help_a8,
            R.string.help_q9  to R.string.help_a9,
            R.string.help_q10 to R.string.help_a10,
        )
        items.forEachIndexed { index, (q, a) ->
            column.addView(helpItem(q, a))
            if (index < items.lastIndex) {
                column.addView(rowDivider())
            }
        }

        return column
    }

    /**
     * Builds a single expandable FAQ row.
     *
     * The `>` prefix is a single monospace glyph in the accent colour. On
     * expand it rotates 90° (pointing down) and the answer body fades in
     * with a short slide; on collapse the same animation is reversed and
     * the body is only then set to `View.GONE`. That "animate then hide"
     * pattern avoids the abrupt jump of a plain visibility toggle.
     */
    private fun helpItem(questionRes: Int, answerRes: Int): View {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val answer = TextView(this).apply {
            text = getString(answerRes)
            setTextColor(CodeAssistTheme.TEXT_SECONDARY)
            textSize = 11f
            setLineSpacing(0f, 1.2f)
            visibility = View.GONE
            setPadding(dp(20), dp(2), 0, dp(10))
        }

        val prefix = TextView(this).apply {
            text = ">"
            setTextColor(CodeAssistTheme.ACCENT)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(dp(14), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val question = TextView(this).apply {
            text = getString(questionRes)
            setTextColor(CodeAssistTheme.TEXT_PRIMARY)
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setLineSpacing(0f, 1.15f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val questionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(9), 0, dp(9))
            isClickable = true
            isFocusable = true
            background = RippleDrawable(
                ColorStateList.valueOf(CodeAssistTheme.ACCENT.withAlpha(24)),
                null,
                null
            )
            setOnClickListener {
                val expanding = answer.visibility != View.VISIBLE
                if (animationsEnabled) {
                    if (expanding) {
                        answer.visibility = View.VISIBLE
                        answer.alpha = 0f
                        answer.translationY = -dp(3).toFloat()
                        answer.animate()
                            .alpha(1f).translationY(0f)
                            .setDuration(180)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                        prefix.animate()
                            .rotation(90f)
                            .setDuration(200)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    } else {
                        answer.animate()
                            .alpha(0f).translationY(-dp(3).toFloat())
                            .setDuration(140)
                            .withEndAction { answer.visibility = View.GONE }
                            .start()
                        prefix.animate()
                            .rotation(0f)
                            .setDuration(200)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    }
                } else {
                    answer.visibility = if (expanding) View.VISIBLE else View.GONE
                    prefix.rotation = if (expanding) 90f else 0f
                }
            }
        }
        questionRow.addView(prefix)
        questionRow.addView(question)

        item.addView(questionRow)
        item.addView(answer)
        return item
    }

    /**
     * Links section. Header `// 06 LINKS`, then one clickable row per link
     * with a small slot icon, label and a trailing `→`. The `→` reads as
     * "go to" without the softness of a chevron. Rows have a subtle ripple
     * and a press-scale effect.
     */
    private fun buildLinksSection(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        column.addView(sectionHeader("06", R.string.links_title))

        column.addView(linkRow(R.drawable.ic_tech_github,  getString(R.string.link_repository), repositoryUrl))
        column.addView(linkRow(R.drawable.ic_tech_bug,     getString(R.string.link_report_bug), issuesUrl))
        column.addView(linkRow(R.drawable.ic_tech_license, getString(R.string.link_license),    licenseUrl))
        column.addView(linkRow(R.drawable.ic_tech_rocket,  getString(R.string.open_codeassist), null) {
            launchCodeAssist()
        })

        return column
    }

    /**
     * Technical information. Header `// 07 TECH INFO`, then a two-column
     * grid: uppercase monospace label on the left (muted), monospace value
     * on the right (primary). Rows are separated by a hairline so the
     * section reads as a table.
     */
    private fun buildTechSection(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        column.addView(sectionHeader("07", R.string.tech_info_title))

        val entries = listOf(
            R.string.tech_info_version    to "v${getVersionName()}",
            R.string.tech_info_package    to packageName,
            R.string.tech_info_min_sdk    to "API ${getMinSdk()}",
            R.string.tech_info_target_sdk to "API ${getTargetSdk()}",
            R.string.tech_info_apk        to getApkSize(),
            R.string.tech_info_language   to "Kotlin / Java 17",
            R.string.tech_info_status     to getString(R.string.tech_info_status_stable),
        )

        entries.forEachIndexed { index, (labelRes, value) ->
            column.addView(infoRow(labelRes, value))
            if (index < entries.lastIndex) {
                column.addView(rowDivider())
            }
        }

        return column
    }

    private fun buildFooter(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.START

        addView(TextView(this@OneCodeForAIInfoActivity).apply {
            text = "// ${getString(R.string.footer_made_with)}"
            setTextColor(CodeAssistTheme.TEXT_MUTED)
            textSize = 10f
            typeface = Typeface.MONOSPACE
        })
        addView(TextView(this@OneCodeForAIInfoActivity).apply {
            text = "// DaytonPlus • ${getString(R.string.creator_dev)}"
            setTextColor(CodeAssistTheme.TEXT_MUTED)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, 0)
            isClickable = true
            isFocusable = true
            background = RippleDrawable(
                ColorStateList.valueOf(CodeAssistTheme.ACCENT.withAlpha(24)),
                null,
                null
            )
            setOnClickListener { openUrl(authorUrl) }
        })
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Section header: `// NN TITLE`.
     *
     * `//` is in the accent colour, the two-digit number is monospace and
     * muted, and the title itself is uppercased with letter-spacing so it
     * reads as a panel header rather than a paragraph. All three pieces
     * share the same baseline and stay on a single line.
     *
     * @param number the two-digit section number, e.g. `"01"`.
     * @param titleRes the string resource for the section title.
     */
    private fun sectionHeader(number: String, titleRes: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(10))
        }

        row.addView(TextView(this).apply {
            text = "//"
            setTextColor(CodeAssistTheme.ACCENT)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, dp(8), 0)
        })

        row.addView(TextView(this).apply {
            text = number
            setTextColor(CodeAssistTheme.TEXT_MUTED)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, dp(10), 0)
        })

        row.addView(TextView(this).apply {
            text = getString(titleRes)
                .uppercase(LanguageHelper.detectIdeLocale(this@OneCodeForAIInfoActivity))
            setTextColor(CodeAssistTheme.TEXT_PRIMARY)
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.15f
        })

        return row
    }

    /**
     * Numbered step row. The number is monospace, accent, two digits
     * (`01`, `02`, …) so the column stays aligned across steps. The title
     * and description stack to the right of it. No badge, no circle —
     * the number is the marker.
     */
    private fun stepRow(number: String, title: String, desc: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(6), 0, dp(6))
        }

        row.addView(TextView(this).apply {
            text = number
            setTextColor(CodeAssistTheme.ACCENT)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(1), dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.WRAP_CONTENT)
        })

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = title
            setTextColor(CodeAssistTheme.TEXT_PRIMARY)
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        texts.addView(TextView(this).apply {
            text = desc
            setTextColor(CodeAssistTheme.TEXT_SECONDARY)
            textSize = 11f
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(texts)
        return row
    }

    /**
     * Feature / changelog row: small square icon slot (16dp, 1dp accent
     * border, 2dp radius) followed by a single-line label. Tight vertical
     * padding so the list stays dense.
     */
    private fun iconRow(iconRes: Int, label: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(5), 0, dp(5))
        }

        val iconSlot = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                marginEnd = dp(10)
                topMargin = dp(1)
            }
            background = slotBackground(
                CodeAssistTheme.ACCENT.withAlpha(70),
                radiusDp = 2f
            )
        }
        iconSlot.addView(ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(CodeAssistTheme.ACCENT)
            layoutParams = FrameLayout.LayoutParams(dp(9), dp(9), Gravity.CENTER)
        })
        row.addView(iconSlot)

        row.addView(TextView(this).apply {
            text = label
            setTextColor(CodeAssistTheme.TEXT_PRIMARY)
            textSize = 12f
            setLineSpacing(0f, 1.18f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        return row
    }

    /**
     * Clickable link row. Icon slot, label, trailing `→`. Ripple feedback
     * over the whole row plus press-scale.
     */
    private fun linkRow(iconRes: Int, label: String, url: String?, action: (() -> Unit)? = null): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
            isFocusable = true
            background = RippleDrawable(
                ColorStateList.valueOf(CodeAssistTheme.ACCENT.withAlpha(24)),
                null,
                null
            )
            setOnClickListener {
                if (action != null) action() else url?.let { openUrl(it) }
            }
        }

        val iconSlot = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                marginEnd = dp(12)
            }
            background = slotBackground(
                CodeAssistTheme.ACCENT.withAlpha(80),
                radiusDp = 2f
            )
        }
        iconSlot.addView(ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(CodeAssistTheme.ACCENT)
            layoutParams = FrameLayout.LayoutParams(dp(11), dp(11), Gravity.CENTER)
        })
        row.addView(iconSlot)

        row.addView(TextView(this).apply {
            text = label
            setTextColor(CodeAssistTheme.TEXT_PRIMARY)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        row.addView(TextView(this).apply {
            text = "→"
            setTextColor(CodeAssistTheme.ACCENT)
            textSize = 14f
            typeface = Typeface.MONOSPACE
        })

        attachPressScale(row)
        return row
    }

    /**
     * Two-column tech info row. Label is uppercase monospace muted, value
     * is monospace primary and end-aligned. Both are 10–11 sp so the grid
     * stays dense.
     */
    private fun infoRow(labelRes: Int, value: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
        }
        row.addView(TextView(this).apply {
            text = getString(labelRes)
                .uppercase(LanguageHelper.detectIdeLocale(this@OneCodeForAIInfoActivity))
            setTextColor(CodeAssistTheme.TEXT_MUTED)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.06f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = value
            setTextColor(CodeAssistTheme.TEXT_PRIMARY)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.END
        })
        return row
    }

    private fun spacer(dpValue: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(dpValue))
    }

    /**
     * Hairline divider between major sections. 1dp, `OUTLINE` at ~70%
     * alpha. Same visual weight as the top bar separator so the whole
     * screen reads as one continuous panel.
     */
    private fun sectionDivider(topDp: Int, bottomDp: Int): View = View(this).apply {
        setBackgroundColor(CodeAssistTheme.OUTLINE.withAlpha(90))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ).apply {
            topMargin = dp(topDp)
            bottomMargin = dp(bottomDp)
        }
    }

    /**
     * Hairline divider between rows inside a section (FAQ, tech info). Same
     * colour as [sectionDivider] but with no vertical margin — the row
     * padding already provides the spacing.
     */
    private fun rowDivider(): View = View(this).apply {
        setBackgroundColor(CodeAssistTheme.OUTLINE.withAlpha(60))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        )
    }

    /**
     * Square slot background used by every icon container on this screen.
     *
     * The slot is what gives the icon rows a consistent left edge without
     * wrapping them in a card. It's a 1dp stroke at the given colour on a
     * 2dp-radius rectangle; the inside is transparent so the parent's
     * background shows through.
     */
    private fun slotBackground(strokeColor: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.TRANSPARENT)
            cornerRadius = radiusDp * resources.displayMetrics.density
            setStroke(1, strokeColor)
        }

    /**
     * Returns the same colour with a different alpha channel.
     *
     * Used throughout this screen to derive the low-alpha accent and
     * outline tones that back the slots, ripples and hairlines. Doing it
     * at the call site (instead of hardcoding a second set of ARGB
     * literals) keeps [CodeAssistTheme] as the single source of truth for
     * the actual palette.
     */
    private fun Int.withAlpha(alpha: Int): Int =
        Color.argb(alpha, Color.red(this), Color.green(this), Color.blue(this))

    /**
     * Attaches the standard press-scale effect (0.97x on down, 1.0x on up)
     * to a clickable view. No-op when animations are disabled.
     */
    private fun attachPressScale(view: View) {
        if (!animationsEnabled) return
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.97f).scaleY(0.97f)
                        .setDuration(80)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f)
                        .setDuration(120)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
            }
            false
        }
    }

    /**
     * Minimal entrance animation. Each top-level child of the content
     * column fades in and slides up by 8dp, staggered by 30ms. Short and
     * unobtrusive on purpose — this is a tech screen, not a landing page.
     */
    private fun playEntrance(root: ViewGroup) {
        if (!animationsEnabled) return
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            child.alpha = 0f
            child.translationY = dp(8).toFloat()
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(i * 30L)
                .setDuration(260)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    // ---------------------------------------------------------------- system info

    private fun getVersionName(): String = cachedVersionName
    private fun getApkSize(): String = cachedApkSize
    private fun getMinSdk(): Int = cachedMinSdk
    private fun getTargetSdk(): Int = cachedTargetSdk

    private fun readVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    private fun readApkSize(): String = try {
        val path = packageManager.getApplicationInfo(packageName, 0).sourceDir
        val bytes = File(path).length()
        when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
        }
    } catch (_: Exception) { "—" }

    private fun readMinSdk(): Int = try {
        packageManager.getApplicationInfo(packageName, 0).minSdkVersion
    } catch (_: Exception) { 26 }

    private fun readTargetSdk(): Int = try {
        packageManager.getApplicationInfo(packageName, 0).targetSdkVersion
    } catch (_: Exception) { 36 }

    // ---------------------------------------------------------------- actions

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.no_app_for_link), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Launches the host CodeAssist IDE by looking up its launch intent.
     *
     * The package name is read from `R.string.host_ide_package` so that forks
     * or rebranded hosts only need a string override. If the string is empty
     * or the package is not installed, the user gets a localized toast.
     */
    private fun launchCodeAssist() {
        val hostPackage = getString(R.string.host_ide_package).trim()
        if (hostPackage.isEmpty()) {
            Toast.makeText(this, getString(R.string.codeassist_not_installed), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = packageManager.getLaunchIntentForPackage(hostPackage)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } else {
                Toast.makeText(this, getString(R.string.codeassist_not_installed), Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.codeassist_open_error), Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        /**
         * Window background. Kept as a single named constant so the activity
         * background, the status bar and the navigation bar all reference
         * the exact same value and cannot drift apart in a future edit.
         */
        const val BG = 0xFF0A0A0C.toInt()
    }
}