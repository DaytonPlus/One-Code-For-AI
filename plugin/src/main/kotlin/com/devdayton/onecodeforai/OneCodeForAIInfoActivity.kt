package com.devdayton.onecodeforai

import com.devdayton.onecodeforai.R
import com.devdayton.onecodeforai.android.LanguageHelper
import com.devdayton.onecodeforai.ui.CodeAssistTheme

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
 *   1. Header          - app name, subtitle, version
 *   2. What's New      - recent changelog for the current version
 *   3. Installation    - guide_step1..5 (download, install, open, enable, restart)
 *   4. How to use      - step_1..4 (open project, long-press, choose plugin, export)
 *   5. Features        - feature_1..10 with icons (advanced filtering, presets, etc.)
 *   6. Help & FAQ      - help_q1..10 / help_a1..10 as expandable Q/A items
 *   7. Links           - repository, issues, license, open CodeAssist
 *   8. Technical info  - version, package, min/target SDK, APK size, language, status
 *   9. Footer          - "Made with love" + author
 */
class OneCodeForAIInfoActivity : Activity() {

    private val repositoryUrl  = "https://github.com/daytonplus/one-code-for-ai"
    private val issuesUrl      = "$repositoryUrl/issues"
    private val licenseUrl     = "$repositoryUrl/blob/main/LICENSE"
    private val authorUrl      = "https://github.com/daytonplus"
    private val hostIdePackage = "com.tyron.code"

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageHelper.wrapContextWithIdeLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0A0A0C"))
            isVerticalScrollBarEnabled = false
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(28))
        }

        column.addView(buildTopBar())
        column.addView(spacer(12))
        column.addView(buildHeader())
        column.addView(spacer(12))
        column.addView(buildWhatsNewSection()) // <-- Nueva sección
        column.addView(spacer(16))
        column.addView(buildPluginInstallSection())
        column.addView(spacer(12))
        column.addView(buildInstallSection())
        column.addView(spacer(12))
        column.addView(buildFeaturesSection())
        column.addView(spacer(12))
        column.addView(buildHelpSection())
        column.addView(spacer(12))
        column.addView(buildLinksSection())
        column.addView(spacer(12))
        column.addView(buildTechSection())
        column.addView(spacer(16))
        column.addView(buildFooter())

        scroll.addView(column)
        setContentView(scroll)
    }

    // ---------------------------------------------------------------- sections

    private fun buildTopBar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        addView(TextView(this@OneCodeForAIInfoActivity).apply {
            text = getString(R.string.info_title)
            setTextColor(CodeAssistTheme.TEXT_SECONDARY)
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.1f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        addView(TextView(this@OneCodeForAIInfoActivity).apply {
            text = "✕"
            setTextColor(CodeAssistTheme.TEXT_SECONDARY)
            textSize = 16f
            gravity = Gravity.CENTER
            background = roundedBackground(CodeAssistTheme.SURFACE_RAISED, CodeAssistTheme.OUTLINE, 10f)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
        })
    }

    private fun buildHeader(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }

        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_tech_rocket)
            setColorFilter(CodeAssistTheme.ACCENT)
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(14) }
        })

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(CodeAssistTheme.TEXT_PRIMARY)
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        texts.addView(TextView(this).apply {
            text = getString(R.string.info_subtitle)
            setTextColor(CodeAssistTheme.TEXT_SECONDARY)
            textSize = 11f
            setPadding(0, dp(2), 0, 0)
        })
        texts.addView(TextView(this).apply {
            text = "v${getVersionName()}"
            setTextColor(CodeAssistTheme.ACCENT)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(texts)
        return row
    }

    /**
     * Builds the "What's New" changelog section.
     * Highlights the most important features introduced in the current version.
     */
    private fun buildWhatsNewSection(): View {
        val card = sectionCard()
        card.addView(sectionTitle(R.string.whats_new_title))

        card.addView(TextView(this).apply {
            text = getString(R.string.whats_new_version, getVersionName())
            setTextColor(CodeAssistTheme.ACCENT)
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, 0, 0, dp(8))
        })

        val changes = listOf(
            R.string.whats_new_item_1,
            R.string.whats_new_item_2,
            R.string.whats_new_item_3,
            R.string.whats_new_item_4,
            R.string.whats_new_item_5,
        )
        
        changes.forEach { 
            card.addView(iconRow(R.drawable.ic_tech_check, getString(it))) 
        }
        
        return card
    }

    private fun buildPluginInstallSection(): View {
        val card = sectionCard()
        card.addView(sectionTitle(R.string.guide_header_title))

        card.addView(TextView(this).apply {
            text = getString(R.string.guide_header_subtitle)
            setTextColor(CodeAssistTheme.TEXT_SECONDARY)
            textSize = 11f
            setPadding(0, 0, 0, dp(10))
        })

        data class Step(val num: String, val title: Int, val desc: Int)
        val steps = listOf(
            Step("1", R.string.guide_step1_title, R.string.guide_step1_desc),
            Step("2", R.string.guide_step2_title, R.string.guide_step2_desc),
            Step("3", R.string.guide_step3_title, R.string.guide_step3_desc),
            Step("4", R.string.guide_step4_title, R.string.guide_step4_desc),
            Step("5", R.string.guide_step5_title, R.string.guide_step5_desc),
        )
        steps.forEach { card.addView(stepRow(it.num, getString(it.title), getString(it.desc))) }
        return card
    }

    private fun buildInstallSection(): View {
        val card = sectionCard()
        card.addView(sectionTitle(R.string.integration_title))

        data class Step(val num: String, val title: Int, val desc: Int)
        val steps = listOf(
            Step("1", R.string.step_1_title, R.string.step_1_desc),
            Step("2", R.string.step_2_title, R.string.step_2_desc),
            Step("3", R.string.step_3_title, R.string.step_3_desc),
            Step("4", R.string.step_4_title, R.string.step_4_desc),
        )
        steps.forEach { card.addView(stepRow(it.num, getString(it.title), getString(it.desc))) }
        return card
    }

    private fun buildFeaturesSection(): View {
        val card = sectionCard()
        card.addView(sectionTitle(R.string.features_title))

        val features = listOf(
            R.drawable.ic_tech_filter to R.string.feature_1,
            R.drawable.ic_tech_git    to R.string.feature_2,
            R.drawable.ic_tech_code   to R.string.feature_3,
            R.drawable.ic_tech_share  to R.string.feature_4,
            R.drawable.ic_tech_folder to R.string.feature_5,
            R.drawable.ic_tech_chart  to R.string.feature_6,
            R.drawable.ic_tech_check  to R.string.feature_7,
            R.drawable.ic_tech_search to R.string.feature_8,
            R.drawable.ic_tech_settings to R.string.feature_9,
            R.drawable.ic_tech_rocket to R.string.feature_10,
        )
        features.forEach { card.addView(iconRow(it.first, getString(it.second))) }
        return card
    }

    private fun buildHelpSection(): View {
        val card = sectionCard()
        card.addView(sectionTitle(R.string.help_title))

        card.addView(TextView(this).apply {
            text = getString(R.string.help_header_subtitle)
            setTextColor(CodeAssistTheme.TEXT_SECONDARY)
            textSize = 11f
            setPadding(0, 0, 0, dp(8))
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
        items.forEach { (q, a) -> card.addView(helpItem(q, a)) }
        return card
    }

    private fun helpItem(questionRes: Int, answerRes: Int): View {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }

        val answer = TextView(this).apply {
            text = getString(answerRes)
            setTextColor(CodeAssistTheme.TEXT_SECONDARY)
            textSize = 11f
            setLineSpacing(0f, 1.15f)
            visibility = View.GONE
            setPadding(dp(24), dp(6), 0, dp(6))
        }

        val chevron = TextView(this).apply {
            text = "▸"
            setTextColor(CodeAssistTheme.ACCENT)
            textSize = 11f
            setPadding(0, 0, dp(6), 0)
            layoutParams = LinearLayout.LayoutParams(dp(16), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val question = TextView(this).apply {
            text = getString(questionRes)
            setTextColor(CodeAssistTheme.TEXT_PRIMARY)
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val questionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(6), 0, dp(6))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val expand = answer.visibility != View.VISIBLE
                answer.visibility = if (expand) View.VISIBLE else View.GONE
                chevron.text = if (expand) "▾" else "▸"
            }
        }
        questionRow.addView(chevron)
        questionRow.addView(question)

        item.addView(questionRow)
        item.addView(answer)
        return item
    }

    private fun buildLinksSection(): View {
        val card = sectionCard()
        card.addView(sectionTitle(R.string.links_title))

        card.addView(linkRow(R.drawable.ic_tech_github,  getString(R.string.link_repository), repositoryUrl))
        card.addView(linkRow(R.drawable.ic_tech_bug,     getString(R.string.link_report_bug), issuesUrl))
        card.addView(linkRow(R.drawable.ic_tech_license, getString(R.string.link_license),    licenseUrl))
        card.addView(linkRow(R.drawable.ic_tech_rocket,  getString(R.string.open_codeassist), null) {
            launchCodeAssist()
        })
        return card
    }

    private fun buildTechSection(): View {
        val card = sectionCard()
        card.addView(sectionTitle(R.string.tech_info_title))

        card.addView(infoRow(R.string.tech_info_version,    "v${getVersionName()}"))
        card.addView(infoRow(R.string.tech_info_package,    packageName))
        card.addView(infoRow(R.string.tech_info_min_sdk,    "API ${getMinSdk()}"))
        card.addView(infoRow(R.string.tech_info_target_sdk, "API ${getTargetSdk()}"))
        card.addView(infoRow(R.string.tech_info_apk,        getApkSize()))
        card.addView(infoRow(R.string.tech_info_language,   "Kotlin / Java 17"))
        card.addView(infoRow(R.string.tech_info_status,     getString(R.string.tech_info_status_stable)))
        return card
    }

    private fun buildFooter(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL

        addView(TextView(this@OneCodeForAIInfoActivity).apply {
            text = getString(R.string.footer_made_with)
            setTextColor(CodeAssistTheme.TEXT_MUTED)
            textSize = 10f
            gravity = Gravity.CENTER
        })
        addView(TextView(this@OneCodeForAIInfoActivity).apply {
            text = "DaytonPlus • ${getString(R.string.creator_dev)}"
            setTextColor(CodeAssistTheme.TEXT_MUTED)
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
            isClickable = true
            setOnClickListener { openUrl(authorUrl) }
        })
    }

    // ---------------------------------------------------------------- helpers

    private fun sectionCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = roundedBackground(CodeAssistTheme.SURFACE_RAISED, CodeAssistTheme.OUTLINE, 12f)
    }

    private fun sectionTitle(resId: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        row.addView(TextView(this).apply {
            text = "#"
            setTextColor(CodeAssistTheme.ACCENT)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, dp(6), 0)
        })
        row.addView(TextView(this).apply {
            text = getString(resId).uppercase()
            setTextColor(CodeAssistTheme.TEXT_PRIMARY)
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.1f
        })
        return row
    }

    private fun stepRow(number: String, title: String, desc: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(6), 0, dp(6))
        }
        row.addView(TextView(this).apply {
            text = number
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) }
            background = roundedBackground(CodeAssistTheme.ACCENT, null, 12f)
        })
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = title
            setTextColor(CodeAssistTheme.TEXT_PRIMARY)
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        texts.addView(TextView(this).apply {
            text = desc
            setTextColor(CodeAssistTheme.TEXT_SECONDARY)
            textSize = 11f
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(texts)
        return row
    }

    private fun iconRow(iconRes: Int, label: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(CodeAssistTheme.ACCENT)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(10) }
        })
        row.addView(TextView(this).apply {
            text = label
            setTextColor(CodeAssistTheme.TEXT_PRIMARY)
            textSize = 12f
        })
        return row
    }

    private fun linkRow(iconRes: Int, label: String, url: String?, action: (() -> Unit)? = null): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (action != null) action() else url?.let { openUrl(it) }
            }
        }
        row.addView(ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(CodeAssistTheme.ACCENT)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(10) }
        })
        row.addView(TextView(this).apply {
            text = label
            setTextColor(CodeAssistTheme.TEXT_PRIMARY)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = "›"
            setTextColor(CodeAssistTheme.TEXT_SECONDARY)
            textSize = 16f
        })
        return row
    }

    private fun infoRow(labelRes: Int, value: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(3), 0, dp(3))
        }
        row.addView(TextView(this).apply {
            text = getString(labelRes)
            setTextColor(CodeAssistTheme.TEXT_SECONDARY)
            textSize = 11f
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

    private fun roundedBackground(fill: Int, stroke: Int?, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radiusDp * resources.displayMetrics.density
            if (stroke != null) setStroke(1, stroke)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    // ---------------------------------------------------------------- system info

    private fun getVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    private fun getMinSdk(): Int = try {
        packageManager.getApplicationInfo(packageName, 0).minSdkVersion
    } catch (_: Exception) { 26 }

    private fun getTargetSdk(): Int = try {
        packageManager.getApplicationInfo(packageName, 0).targetSdkVersion
    } catch (_: Exception) { 36 }

    private fun getApkSize(): String = try {
        val path = packageManager.getApplicationInfo(packageName, 0).sourceDir
        val bytes = File(path).length()
        when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
        }
    } catch (_: Exception) { "—" }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.no_app_for_link), Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchCodeAssist() {
        try {
            val intent = packageManager.getLaunchIntentForPackage(hostIdePackage)
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
}