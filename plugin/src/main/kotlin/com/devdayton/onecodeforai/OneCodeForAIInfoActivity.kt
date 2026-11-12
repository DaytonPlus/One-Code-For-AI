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
 * Plugin info screen — developer-console aesthetic, v2.
 *
 * Layout, top to bottom:
 *
 *   1.  Top bar        — // INFO + close button
 *   2.  Hero           — icon slot, display name, tagline, chip row
 *   3.  What's New     — // 01, version, bulleted changelog
 *   4.  Installation   — // 02, subtitle, numbered steps
 *   5.  Usage          — // 03, subtitle, numbered steps
 *   6.  Features       — // 04, grouped by category, bulleted items
 *   7.  FAQ            — // 05, collapsible Q/A
 *   8.  Links          — // 06, icon + label + → per row
 *   9.  Tech info      — // 07, two-column monospace grid
 *  10.  Footer         — // Made with love + author link
 *
 * ## Visual philosophy (v2)
 *
 * The first version of this screen was "tech / minimalist" but drifted toward
 * "icon soup": every feature, every changelog item, every step and every FAQ
 * question had its own icon. That reads as decoration, not as engineering.
 * v2 applies a strict rule:
 *
 *   **An icon is only used when it carries semantic meaning that the text
 *   itself cannot. Everything else uses monospace glyphs (//, ▸, →, >, ●)
 *   and typographic hierarchy.**
 *
 * Concretely:
 *
 *  - **Hero** — one icon (rocket) to identify the app.
 *  - **Section headers** — no icons. `// NN TITLE` in monospace is enough.
 *  - **Changelog, feature lists** — `▸` prefix in accent monospace. No
 *    icon repetition.
 *  - **Feature categories** — one small icon per *category*, not per item.
 *    The category header carries the visual weight; the items under it
 *    are plain bullets. This is the "grouped list" pattern used by
 *    developer dashboards (Grafana, Sentry, Datadog) and it reads as
 *    information architecture rather than as a feature matrix.
 *  - **Numbered steps** — monospace `01..05` in accent, no icons.
 *  - **FAQ** — `>` prefix that rotates 90° on expand, no per-question icon.
 *  - **Links** — the only place where per-row icons remain, because an
 *    external-link arrow or a brand glyph genuinely disambiguates the row
 *    ("which one goes to GitHub?"). Four icons total.
 *  - **Tech info** — two-column monospace grid, no icons.
 *
 * ## Typographic hierarchy
 *
 * Three type scales, used consistently:
 *
 *  - **Monospace** (10–11sp) for structural chrome: section numbers, chips,
 *    labels, tech info values, the `//` marks, the `▸` bullets.
 *  - **sans-serif-medium** (11–18sp) for titles and question text.
 *  - **sans-serif** (11–12sp) for body copy and answers.
 *
 * Only two text colors are used for emphasis (accent and tag-include-green);
 * every other element is primary, secondary or muted. This is what keeps
 * the screen readable at a glance even though it carries a lot of text.
 *
 * ## Localization
 *
 * [attachBaseContext] wraps the base context with the locale resolved by
 * [LanguageHelper.wrapContextWithIdeLocale], which honours the user's
 * language selection from the settings page. Strings are re-resolved every
 * time the activity is created, so opening the screen after changing the
 * language starts in the new language automatically. Already-open instances
 * keep their previous language, matching standard Android behavior for
 * runtime locale changes.
 *
 * ## Everything is platform widgets
 *
 * No AndroidX, no RecyclerView, no custom views. The plugin's dependency
 * footprint stays at zero while still allowing the expressive tech look
 * via [GradientDrawable], [RippleDrawable], [FrameLayout] slots and
 * monospace typography.
 */
class OneCodeForAIInfoActivity : Activity() {

    private val repositoryUrl = "https://github.com/daytonplus/one-code-for-ai"
    private val issuesUrl     = "$repositoryUrl/issues"
    private val licenseUrl    = "$repositoryUrl/blob/main/LICENSE"
    private val authorUrl     = "https://github.com/daytonplus"

    private var cachedVersionName: String = "?"
    private var cachedApkSize: String = "—"
    private var cachedMinSdk: Int = 26
    private var cachedTargetSdk: Int = 36

    /**
     * Master switch for entrance and press animations. Read through a
     * getter so a change in the plugin settings is picked up on the next
     * screen open without recreating the activity.
     */
    private val animationsEnabled: Boolean
        get() = PluginSettings.areAnimationsEnabled(this)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageHelper.wrapContextWithIdeLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // One-shot PackageManager lookups; the values are cached on fields
        // so the rest of the screen does not have to round-trip through
        // the package manager for every render.
        cachedVersionName = readVersionName()
        cachedApkSize     = readApkSize()
        cachedMinSdk      = readMinSdk()
        cachedTargetSdk   = readTargetSdk()

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
        column.addView(sectionDivider(12, 0))
        column.addView(buildHeroSection())
        column.addView(sectionDivider(20, 0))
        column.addView(buildWhatsNewSection())
        column.addView(sectionDivider(20, 0))
        column.addView(buildInstallSection())
        column.addView(sectionDivider(20, 0))
        column.addView(buildUsageSection())
        column.addView(sectionDivider(20, 0))
        column.addView(buildFeaturesSection())
        column.addView(sectionDivider(20, 0))
        column.addView(buildHelpSection())
        column.addView(sectionDivider(20, 0))
        column.addView(buildLinksSection())
        column.addView(sectionDivider(20, 0))
        column.addView(buildTechSection())
        column.addView(spacer(24))
        column.addView(buildFooter())

        scroll.addView(column)
        setContentView(scroll)

        scroll.post {
            if (!isFinishing && !isDestroyed) playEntrance(column)
        }
    }

    // ---------------------------------------------------------------- sections

    /**
     * Top bar: `// INFO` on the left (accent slashes, muted uppercase label),
     * a small square close button on the right (1dp outline, no fill). A
     * hairline under it separates the chrome from the body.
     *
     * No additional icons: the only affordance on the whole chrome is the
     * close button, and its `×` glyph is a text character, not a drawable.
     */
    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
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
     * Hero block.
     *
     * Structure:
     *
     *   ┌──────────┐   One Code For AI
     *   │  🚀      │   Context generator for CodeAssist
     *   │ 56 × 56  │   [v1.1.0]   ● STABLE   · MIT
     *   └──────────┘
     *
     * The icon slot grew from 48dp to 56dp so it reads as the app's
     * anchor rather than as a decorative square next to the title. The
     * chip row replaces the previous plain-text `[v1.0.90]  ● STABLE`
     * with a consistent bracket-chip style that can be extended later
     * (e.g. `[MIT]`, `[18 LANG]`) without re-laying-out the hero.
     */
    private fun buildHeroSection(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(4))
        }

        val iconSlot = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply {
                marginEnd = dp(16)
            }
            background = slotBackground(
                CodeAssistTheme.ACCENT.withAlpha(90),
                radiusDp = 2f
            )
        }
        iconSlot.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_tech_rocket)
            setColorFilter(CodeAssistTheme.ACCENT)
            layoutParams = FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER)
        })
        row.addView(iconSlot)

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        texts.addView(TextView(this).apply {
            text = getString(R.string.app_display_name)
            setTextColor(CodeAssistTheme.TEXT_PRIMARY)
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = -0.005f
        })

        texts.addView(TextView(this).apply {
            text = getString(R.string.info_subtitle)
            setTextColor(CodeAssistTheme.TEXT_SECONDARY)
            textSize = 11f
            setPadding(0, dp(2), 0, dp(10))
        })

        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        chipRow.addView(monoChip("[v$cachedVersionName]", CodeAssistTheme.ACCENT))
        chipRow.addView(statusDot(
            CodeAssistTheme.TAG_INCLUDE_TEXT,
            getString(R.string.tech_info_status_stable)
        ))
        chipRow.addView(mutedChip("MIT"))
        texts.addView(chipRow)

        row.addView(texts)
        return row
    }

    /**
     * What's New section.
     *
     * Header `// 01 WHAT'S NEW`, version as an accent monospace line,
     * then each changelog item as a `▸` bullet. No per-item icons: the
     * list is already scannable from the accent bullets and the version
     * line, and repeating a checkmark ten times added visual noise
     * without adding information.
     */
    private fun buildWhatsNewSection(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(sectionHeader("01", R.string.whats_new_title))

        column.addView(TextView(this).apply {
            text = getString(R.string.whats_new_version, getVersionName())
            setTextColor(CodeAssistTheme.ACCENT)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(2), 0, dp(10))
        })

        listOf(
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
        ).forEach { column.addView(bulletRow(getString(it))) }

        return column
    }

    /**
     * Installation guide.
     *
     * Header `// 02 INSTALLATION`, a one-line subtitle, then five
     * numbered steps. Numbers are monospace `01..05` in accent; titles
     * are medium sans; descriptions are 11sp secondary. No icons per
     * step — the number *is* the marker.
     */
    private fun buildInstallSection(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(sectionHeader("02", R.string.guide_header_title))
        column.addView(subtitle(getString(R.string.guide_header_subtitle)))

        data class Step(val num: String, val title: Int, val desc: Int)
        listOf(
            Step("01", R.string.guide_step1_title, R.string.guide_step1_desc),
            Step("02", R.string.guide_step2_title, R.string.guide_step2_desc),
            Step("03", R.string.guide_step3_title, R.string.guide_step3_desc),
            Step("04", R.string.guide_step4_title, R.string.guide_step4_desc),
            Step("05", R.string.guide_step5_title, R.string.guide_step5_desc),
        ).forEach { column.addView(stepRow(it.num, getString(it.title), getString(it.desc))) }

        return column
    }

    /**
     * Usage section.
     *
     * Header `// 03 HOW TO USE`, a subtitle describing the export
     * workflow, then four numbered steps. Mirrors [buildInstallSection]
     * structurally so the two read as a single getting-started block
     * split into "setup" and "daily use".
     */
    private fun buildUsageSection(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(sectionHeader("03", R.string.integration_title))
        column.addView(subtitle(getString(R.string.integration_subtitle)))

        data class Step(val num: String, val title: Int, val desc: Int)
        listOf(
            Step("01", R.string.step_1_title, R.string.step_1_desc),
            Step("02", R.string.step_2_title, R.string.step_2_desc),
            Step("03", R.string.step_3_title, R.string.step_3_desc),
            Step("04", R.string.step_4_title, R.string.step_4_desc),
        ).forEach { column.addView(stepRow(it.num, getString(it.title), getString(it.desc))) }

        return column
    }

    /**
     * Features section — **the biggest change** from v1.
     *
     * v1 rendered ten features as ten rows, each with a distinct icon.
     * That was a feature matrix masquerading as a list: it read as
     * decoration and made the screen feel busier than it was.
     *
     * v2 groups the same information into four categories:
     *
     *  - **Export & filtering** — glob filters, presets, tree, stubs,
     *    progress.
     *  - **Git integration** — ref export, diff preview, kill switch.
     *  - **Customization** — theme, language, help, limits.
     *  - **Privacy** — offline, local-only processing.
     *
     * Each category gets **one** small icon on its header (filter, git,
     * settings, lock). The individual features under it are `▸`
     * bullets with no per-item icon. Result: 4 icons instead of 10,
     * plus better grouping — a reader can now see "what does this
     * plugin actually do?" in one glance at four category titles
     * instead of scanning ten one-liners.
     */
    private fun buildFeaturesSection(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(sectionHeader("04", R.string.features_title))

        column.addView(featureCategory(
            iconRes   = R.drawable.ic_tech_filter,
            titleRes  = R.string.feature_cat_export,
            features  = listOf(
                R.string.feature_export_1,
                R.string.feature_export_2,
                R.string.feature_export_3,
                R.string.feature_export_4,
                R.string.feature_export_5,
            )
        ))

        column.addView(featureCategory(
            iconRes   = R.drawable.ic_tech_git,
            titleRes  = R.string.feature_cat_git,
            features  = listOf(
                R.string.feature_git_1,
                R.string.feature_git_2,
                R.string.feature_git_3,
            )
        ))

        column.addView(featureCategory(
            iconRes   = R.drawable.ic_tech_settings,
            titleRes  = R.string.feature_cat_customize,
            features  = listOf(
                R.string.feature_customize_1,
                R.string.feature_customize_2,
                R.string.feature_customize_3,
                R.string.feature_customize_4,
            )
        ))

        column.addView(featureCategory(
            iconRes   = R.drawable.ic_tech_lock,
            titleRes  = R.string.feature_cat_privacy,
            features  = listOf(
                R.string.feature_privacy_1,
                R.string.feature_privacy_2,
            )
        ))

        return column
    }

    /**
     * FAQ section.
     *
     * Header `// 05 FAQ`, subtitle, then one collapsible item per Q/A
     * pair. Each item uses a `>` prefix (terminal style) that rotates 90°
     * on expand. No per-question icon: the rotation is already a
     * meaningful visual cue, and the row-level press feedback (ripple
     * + press-scale on touch) is enough of a hint that the row is
     * interactive.
     */
    private fun buildHelpSection(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(sectionHeader("05", R.string.help_title))
        column.addView(subtitle(getString(R.string.help_header_subtitle)))

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
            if (index < items.lastIndex) column.addView(rowDivider())
        }

        return column
    }

    /**
     * Links section — the one place where **per-row icons are kept**.
     *
     * Four external links (GitHub, Report issue, MIT License, Open
     * CodeAssist) each get a 20dp icon slot, a label and a trailing `→`.
     * The icons are semantically meaningful here: they disambiguate the
     * rows faster than the labels alone ("which one goes to GitHub?").
     * Keeping them is what makes the section feel like a natural link
     * list rather than a table.
     */
    private fun buildLinksSection(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(sectionHeader("06", R.string.links_title))

        column.addView(linkRow(
            R.drawable.ic_tech_github,
            getString(R.string.link_repository),
            repositoryUrl
        ))
        column.addView(linkRow(
            R.drawable.ic_tech_bug,
            getString(R.string.link_report_bug),
            issuesUrl
        ))
        column.addView(linkRow(
            R.drawable.ic_tech_license,
            getString(R.string.link_license),
            licenseUrl
        ))
        column.addView(linkRow(
            R.drawable.ic_tech_rocket,
            getString(R.string.open_codeassist),
            null
        ) { launchCodeAssist() })

        return column
    }

    /**
     * Technical information.
     *
     * Header `// 07 TECH INFO`, then a two-column monospace grid: uppercase
     * muted label on the left, primary value on the right. Rows are
     * separated by a hairline so the section reads as a table. No icons.
     */
    private fun buildTechSection(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
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
            if (index < entries.lastIndex) column.addView(rowDivider())
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
     * `//` is accent, the two-digit number is monospace muted, and the
     * title is uppercased with letter-spacing so it reads as a panel
     * header rather than a paragraph. The three pieces share the same
     * baseline and stay on one line.
     */
    private fun sectionHeader(number: String, titleRes: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(8))
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
     * Section subtitle: one line of secondary text, used after a section
     * header when the section needs a quick orientation sentence.
     */
    private fun subtitle(text: String): View = TextView(this).apply {
        this.text = text
        setTextColor(CodeAssistTheme.TEXT_SECONDARY)
        textSize = 11f
        setLineSpacing(0f, 1.2f)
        setPadding(0, 0, 0, dp(8))
    }

    /**
     * Bullet row used by the changelog and by the feature lists: an accent
     * `▸` in monospace followed by body text.
     *
     * Replaces v1's `iconRow` which took a per-row icon resource. The
     * `▸` glyph is a text character, so it carries no more visual weight
     * than the surrounding text while still being scannable in a long
     * list. This is the biggest contributor to the "less icon soup"
     * goal.
     */
    private fun bulletRow(text: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(TextView(this).apply {
            this.text = "▸"
            setTextColor(CodeAssistTheme.ACCENT)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(2), dp(10), 0)
        })
        row.addView(TextView(this).apply {
            this.text = text
            setTextColor(CodeAssistTheme.TEXT_PRIMARY)
            textSize = 12f
            setLineSpacing(0f, 1.2f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        return row
    }

    /**
     * A feature category block: header with a small icon and an uppercased
     * category name, then the individual features as `▸` bullets.
     *
     * Used four times in [buildFeaturesSection]; keeping this in a single
     * helper is what allows the icons-per-category rule to be applied
     * uniformly.
     */
    private fun featureCategory(iconRes: Int, titleRes: Int, features: List<Int>): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(4))
        }
        header.addView(ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(CodeAssistTheme.ACCENT)
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(8) }
        })
        header.addView(TextView(this).apply {
            text = getString(titleRes)
                .uppercase(LanguageHelper.detectIdeLocale(this@OneCodeForAIInfoActivity))
            setTextColor(CodeAssistTheme.TEXT_PRIMARY)
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.1f
        })
        container.addView(header)

        features.forEach { container.addView(bulletRow(getString(it))) }
        return container
    }

    /**
     * Numbered step row. The number is monospace accent `01..NN` so the
     * column stays aligned across steps. Title and description stack to
     * the right. No badge, no circle, no icon.
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
     * Builds a single expandable FAQ row.
     *
     * The `>` prefix is a single monospace glyph in accent. On expand
     * it rotates 90° (pointing down) and the answer fades in with a
     * short slide; on collapse the animation reverses and the body is
     * only then set to GONE. That "animate then hide" pattern avoids
     * the abrupt jump of a plain visibility toggle.
     */
    private fun helpItem(questionRes: Int, answerRes: Int): View {
        val item = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

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
     * Clickable link row. Icon slot, label, trailing `→`. Ripple over the
     * whole row plus press-scale. One of only four places where the
     * screen keeps a per-row icon — see the class KDoc for the rationale.
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
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) }
            background = slotBackground(CodeAssistTheme.ACCENT.withAlpha(80), radiusDp = 2f)
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
     * is monospace primary and end-aligned.
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

    /** Monospace chip rendered as `[text]` — used by the hero chip row. */
    private fun monoChip(text: String, color: Int): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color)
        textSize = 10f
        typeface = Typeface.MONOSPACE
    }

    /** Muted chip rendered as `· text` — used by the hero chip row. */
    private fun mutedChip(text: String): TextView = TextView(this).apply {
        this.text = "· $text"
        setTextColor(CodeAssistTheme.TEXT_MUTED)
        textSize = 10f
        typeface = Typeface.MONOSPACE
        setPadding(dp(12), 0, 0, 0)
    }

    /** `● TEXT` in the given color — used for the hero's STABLE status. */
    private fun statusDot(color: Int, text: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        row.addView(TextView(this).apply {
            this.text = "●"
            setTextColor(color)
            textSize = 8f
        })
        row.addView(TextView(this).apply {
            this.text = text.uppercase(LanguageHelper.detectIdeLocale(this@OneCodeForAIInfoActivity))
            setTextColor(color)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.12f
            setPadding(dp(5), 0, 0, 0)
        })
        return row
    }

    private fun spacer(dpValue: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(dpValue))
    }

    /** Hairline divider between major sections. */
    private fun sectionDivider(topDp: Int, bottomDp: Int): View = View(this).apply {
        setBackgroundColor(CodeAssistTheme.OUTLINE.withAlpha(90))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ).apply {
            topMargin = dp(topDp)
            bottomMargin = dp(bottomDp)
        }
    }

    /** Hairline divider between rows inside a section (FAQ, tech info). */
    private fun rowDivider(): View = View(this).apply {
        setBackgroundColor(CodeAssistTheme.OUTLINE.withAlpha(60))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        )
    }

    /**
     * Square slot background used by every icon container on this screen.
     * A 1dp stroke at the given colour on a 2dp-radius rectangle; the
     * inside is transparent so the parent's background shows through.
     */
    private fun slotBackground(strokeColor: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.TRANSPARENT)
            cornerRadius = radiusDp * resources.displayMetrics.density
            setStroke(1, strokeColor)
        }

    /**
     * Returns the same colour with a different alpha channel. Used for
     * every low-alpha accent and outline tone on this screen so
     * [CodeAssistTheme] stays the single source of truth for the actual
     * palette.
     */
    private fun Int.withAlpha(alpha: Int): Int =
        Color.argb(alpha, Color.red(this), Color.green(this), Color.blue(this))

    /**
     * Attaches the standard press-scale effect (0.97× on down, 1.0× on up)
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
     * Minimal entrance animation: each top-level child fades in and slides
     * up by 8dp, staggered by 30ms. Short and unobtrusive — this is a
     * tech screen, not a landing page.
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
    private fun getApkSize(): String     = cachedApkSize
    private fun getMinSdk(): Int         = cachedMinSdk
    private fun getTargetSdk(): Int      = cachedTargetSdk

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
     * The package name is read from `R.string.host_ide_package` so forks
     * or rebranded hosts only need a string override. If the string is
     * empty or the package is not installed, the user gets a localized
     * toast.
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
         * Window background. Kept as a single named constant so the
         * activity background, the status bar and the navigation bar all
         * reference the exact same value and cannot drift apart.
         */
        const val BG = 0xFF0A0A0C.toInt()
    }
}