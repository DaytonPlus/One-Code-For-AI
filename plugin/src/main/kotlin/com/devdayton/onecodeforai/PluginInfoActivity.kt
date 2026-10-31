package com.devdayton.onecodeforai

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Calendar
import java.util.Locale
import kotlin.math.sin
import kotlin.random.Random

class PluginInfoActivity : Activity() {

    private val repositoryUrl  = "https://github.com/devdayton/one-code-for-ai"
    private val issuesUrl      = "$repositoryUrl/issues"
    private val licenseUrl     = "$repositoryUrl/blob/main/LICENSE"
    private val authorUrl      = "https://github.com/devdayton"
    private val authorName     = "DevDayton"
    private val hostIdePackage = "com.tyron.code"

    private val brandAccent  = CodeAssistTheme.ACCENT
    private val textOnAccent = Color.parseColor("#1A1210")
    private val successGreen = Color.parseColor("#4ADE80")

    private val headerAccent   = Color.parseColor("#FF8C42")
    private val codeAccent     = Color.parseColor("#A855F7")
    private val featuresAccent = Color.parseColor("#22C55E")
    private val stepsAccent    = Color.parseColor("#06B6D4")
    private val techAccent     = Color.parseColor("#14B8A6")
    private val linksAccent    = Color.parseColor("#3B82F6")
    private val creatorAccent  = Color.parseColor("#F97316")
    private val guideAccent    = Color.parseColor("#EC4899")

    private val codeBaseText      = Color.parseColor("#D4D4D4")
    private val codeKeywordColor  = Color.parseColor("#C586C0")
    private val codeStringColor   = Color.parseColor("#CE9178")
    private val codeCommentColor  = Color.parseColor("#6A9955")
    private val codeNumberColor   = Color.parseColor("#B5CEA8")
    private val codeFunctionColor = Color.parseColor("#DCDCAA")

    private val panelFillColor     = Color.parseColor("#131318")
    private val panelFillColorEnd  = Color.parseColor("#0F0F13")

    private val mainHandler = Handler(Looper.getMainLooper())
    private val entryAnimationTargets = mutableListOf<View>()
    private val activeAnimators = mutableListOf<ValueAnimator>()

    private lateinit var mainPageContainer: ScrollView
    private lateinit var guidePageContainer: ScrollView

    // ======================================================================
    // Localización: aplicar el locale del IDE host a esta Activity propia
    // ======================================================================

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageHelper.wrapContextWithIdeLocale(newBase))
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = CodeAssistTheme.BG
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }

        val rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        rootLayout.addView(StaticGridBackgroundView(this), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val contentFrame = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        mainPageContainer = buildMainPage()
        guidePageContainer = buildGuidePage()
        guidePageContainer.visibility = View.GONE

        contentFrame.addView(mainPageContainer)
        contentFrame.addView(guidePageContainer)

        rootLayout.addView(contentFrame)
        setContentView(rootLayout)

        mainHandler.post { playStaggeredEntry() }
    }

    override fun onResume() {
        super.onResume()
        activeAnimators.forEach { runCatching { it.resume() } }
    }

    override fun onPause() {
        super.onPause()
        activeAnimators.forEach { runCatching { it.pause() } }
    }

    override fun onDestroy() {
        activeAnimators.forEach { runCatching { it.cancel() } }
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ======================================================================
    // NAVEGACIÓN ENTRE PÁGINAS
    // ======================================================================

    private fun showGuidePage() {
        mainPageContainer.visibility = View.GONE
        guidePageContainer.visibility = View.VISIBLE
        guidePageContainer.scrollTo(0, 0)
        guidePageContainer.alpha = 0f
        guidePageContainer.animate().alpha(1f).setDuration(300).start()
    }

    private fun showMainPage() {
        guidePageContainer.visibility = View.GONE
        mainPageContainer.visibility = View.VISIBLE
        mainPageContainer.alpha = 0f
        mainPageContainer.animate().alpha(1f).setDuration(300).start()
    }

    // ======================================================================
    // PÁGINA PRINCIPAL
    // ======================================================================

    private fun buildMainPage(): ScrollView {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }

        val contentColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12) + getStatusBarHeightPx(), dp(16), dp(28))
        }

        contentColumn.addView(buildTopBar(false))
        contentColumn.addView(createSpacer(10))
        contentColumn.addView(buildHeaderCard())
        contentColumn.addView(createSpacer(10))
        contentColumn.addView(buildBadgeTagsRow())
        contentColumn.addView(createSpacer(10))
        contentColumn.addView(buildCodeSampleCard())
        contentColumn.addView(createSpacer(10))
        contentColumn.addView(buildFeaturesCard())
        contentColumn.addView(createSpacer(10))
        contentColumn.addView(buildIntegrationStepsCard())
        contentColumn.addView(createSpacer(10))

        contentColumn.addView(buildGuideActionButton())
        contentColumn.addView(createSpacer(10))

        contentColumn.addView(buildLaunchButton())
        contentColumn.addView(createSpacer(10))
        contentColumn.addView(buildTechnicalInfoCard())
        contentColumn.addView(createSpacer(10))
        contentColumn.addView(buildLinksCard())
        contentColumn.addView(createSpacer(10))
        contentColumn.addView(buildCreatorCard())
        contentColumn.addView(createSpacer(18))
        contentColumn.addView(buildFooter())

        scroll.addView(contentColumn, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return scroll
    }

    // ======================================================================
    // PÁGINA DE LA GUÍA DE INSTALACIÓN
    // ======================================================================

    private fun buildGuidePage(): ScrollView {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }

        val contentColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12) + getStatusBarHeightPx(), dp(16), dp(28))
        }

        contentColumn.addView(buildTopBar(true))
        contentColumn.addView(createSpacer(10))

        // Header de la guía
        val headerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(20))
            background = TechPanelDrawable(
                accentColor = guideAccent,
                glowColor = Color.argb(55, Color.red(guideAccent), Color.green(guideAccent), Color.blue(guideAccent)),
                chamferDp = 12f,
                withOrnaments = true,
                density = resources.displayMetrics.density
            )
        }
        headerCard.addView(TextView(this).apply {
            text = getString(R.string.guide_header_title)
            setTextColor(CodeAssistTheme.TEXT_PRIMARY)
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setShadowLayer(dp(6f), 0f, 0f, Color.argb(120, Color.red(guideAccent), Color.green(guideAccent), Color.blue(guideAccent)))
        })
        headerCard.addView(TextView(this).apply {
            text = getString(R.string.guide_header_subtitle)
            setTextColor(CodeAssistTheme.TEXT_SECONDARY)
            textSize = 12f
            setPadding(0, dp(6), 0, 0)
            setLineSpacing(0f, 1.3f)
        })
        contentColumn.addView(headerCard)
        contentColumn.addView(createSpacer(16))

        // Pasos de instalación
        contentColumn.addView(createSectionTitle(getString(R.string.guide_section_steps), guideAccent))

        contentColumn.addView(buildGuideStep(
            "1",
            getString(R.string.guide_step1_title),
            getString(R.string.guide_step1_desc),
            R.drawable.ic_tech_download,
            guideAccent
        ))
        contentColumn.addView(buildGuideStep(
            "2",
            getString(R.string.guide_step2_title),
            getString(R.string.guide_step2_desc),
            R.drawable.ic_tech_check,
            guideAccent
        ))
        contentColumn.addView(buildGuideStep(
            "3",
            getString(R.string.guide_step3_title),
            getString(R.string.guide_step3_desc),
            R.drawable.ic_tech_rocket,
            guideAccent
        ))
        contentColumn.addView(buildGuideStep(
            "4",
            getString(R.string.guide_step4_title),
            getString(R.string.guide_step4_desc),
            R.drawable.ic_tech_settings,
            guideAccent
        ))
        contentColumn.addView(buildGuideStep(
            "5",
            getString(R.string.guide_step5_title),
            getString(R.string.guide_step5_desc),
            R.drawable.ic_tech_back,
            guideAccent
        ))

        contentColumn.addView(createSpacer(24))

        // Botón de volver al final
        val backButton = RippleGlowButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
            registerForEntryAnimation(this)
            addChild(ImageView(this@PluginInfoActivity).apply {
                setImageResource(R.drawable.ic_tech_back)
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) }
                setColorFilter(textOnAccent)
            })
            addChild(TextView(this@PluginInfoActivity).apply {
                text = getString(R.string.guide_back_home)
                setTextColor(textOnAccent)
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            setOnClickListener { showMainPage() }
        }
        contentColumn.addView(backButton)
        contentColumn.addView(createSpacer(18))
        contentColumn.addView(buildFooter())

        scroll.addView(contentColumn, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return scroll
    }

    private fun buildGuideStep(
        number: String,
        title: String,
        description: String,
        iconRes: Int,
        accent: Int
    ): View {
        val stepCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = TechPanelDrawable(
                accentColor = accent,
                glowColor = Color.TRANSPARENT,
                chamferDp = 10f,
                withOrnaments = false,
                density = resources.displayMetrics.density
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }

        val numberBadge = TextView(this).apply {
            text = number
            setTextColor(Color.parseColor("#1A1210"))
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(12) }
            background = TechPanelDrawable(
                accentColor = accent,
                glowColor = Color.TRANSPARENT,
                chamferDp = 6f,
                withOrnaments = false,
                density = resources.displayMetrics.density,
                forceColoredFill = true
            )
        }
        stepCard.addView(numberBadge)

        val textContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textContainer.addView(TextView(this).apply {
            text = title
            setTextColor(CodeAssistTheme.TEXT_PRIMARY)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        textContainer.addView(TextView(this).apply {
            text = description
            setTextColor(CodeAssistTheme.TEXT_SECONDARY)
            textSize = 12f
            setPadding(0, dp(4), 0, 0)
            setLineSpacing(0f, 1.25f)
        })
        stepCard.addView(textContainer)

        stepCard.addView(ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(accent)
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(8) }
        })

        return stepCard
    }

    private fun buildGuideActionButton(): View {
        val button = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(16), dp(20), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            registerForEntryAnimation(this)
            isClickable = true
            isFocusable = true
            setOnClickListener { showGuidePage() }
            background = TechPanelDrawable(
                accentColor = guideAccent,
                glowColor = Color.TRANSPARENT,
                chamferDp = 12f,
                withOrnaments = true,
                density = resources.displayMetrics.density
            )
        }

        button.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_tech_settings)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) }
            setColorFilter(guideAccent)
        })
        button.addView(TextView(this).apply {
            text = getString(R.string.guide_install_button)
            setTextColor(CodeAssistTheme.TEXT_PRIMARY)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        return button
    }

    // ======================================================================
    // TOP BAR
    // ======================================================================

    private fun buildTopBar(isGuidePage: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        registerForEntryAnimation(this)

        if (isGuidePage) {
            addView(ImageView(this@PluginInfoActivity).apply {
                setImageResource(R.drawable.ic_tech_back)
                setColorFilter(CodeAssistTheme.TEXT_SECONDARY)
                background = createRoundedBackground(CodeAssistTheme.SURFACE_RAISED, 10f, CodeAssistTheme.OUTLINE)
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) }
                setPadding(dp(8), dp(8), dp(8), dp(8))
                isClickable = true
                isFocusable = true
                setOnClickListener { showMainPage() }
            })
        } else {
            val statusDot = View(this@PluginInfoActivity).apply {
                background = createRoundedBackground(successGreen, 50f)
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) }
            }
            addView(statusDot)
            animateStatusDotPulse(statusDot)
        }

        addView(TextView(this@PluginInfoActivity).apply {
            text = if (isGuidePage) getString(R.string.guide_title)
                   else getString(R.string.info_title)
            setTextColor(CodeAssistTheme.TEXT_SECONDARY)
            textSize = 11f
            letterSpacing = 0.15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        addView(TextView(this@PluginInfoActivity).apply {
            text = "✕"
            setTextColor(CodeAssistTheme.TEXT_SECONDARY)
            textSize = 16f
            gravity = Gravity.CENTER
            background = createRoundedBackground(CodeAssistTheme.SURFACE_RAISED, 10f, CodeAssistTheme.OUTLINE)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
        })
    }

    // ======================================================================
    // HEADER
    // ======================================================================

    private fun buildHeaderCard(): View {
        val wrapper = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            registerForEntryAnimation(this)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = TechPanelDrawable(
                accentColor = headerAccent,
                glowColor = Color.argb(55, Color.red(headerAccent), Color.green(headerAccent), Color.blue(headerAccent)),
                chamferDp = 12f,
                withOrnaments = true,
                density = resources.displayMetrics.density
            )
        }

        val iconView = ImageView(this).apply {
            setImageResource(R.drawable.ic_tech_rocket)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(14) }
            setColorFilter(headerAccent)
            background = TechPanelDrawable(
                accentColor = headerAccent,
                glowColor = Color.TRANSPARENT,
                chamferDp = 14f,
                withOrnaments = false,
                density = resources.displayMetrics.density
            )
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        container.addView(iconView)
        animateIconGlowPulse(iconView, headerAccent)

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleView = TextView(this).apply {
            textSize = 17f
            setTextColor(CodeAssistTheme.TEXT_PRIMARY)
            setShadowLayer(dp(6f), 0f, 0f, Color.argb(120, Color.red(headerAccent), Color.green(headerAccent), Color.blue(headerAccent)))
        }
        textColumn.addView(titleView)
        playTypewriterEffect(titleView, getString(R.string.app_name))

        textColumn.addView(TextView(this).apply {
            text = getString(R.string.info_subtitle)
            setTextColor(CodeAssistTheme.TEXT_SECONDARY)
            textSize = 11f
            setPadding(0, dp(3), 0, 0)
        })

        val versionPill = TextView(this).apply {
            text = "v${getInstalledVersionName()}"
            setTextColor(textOnAccent)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(14), dp(4))
            background = TechPanelDrawable(
                accentColor = headerAccent,
                glowColor = headerAccent,
                chamferDp = 7f,
                withOrnaments = false,
                density = resources.displayMetrics.density,
                forceColoredFill = true
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        textColumn.addView(versionPill)
        animateTextGlow(versionPill, headerAccent)

        container.addView(textColumn)
        wrapper.addView(container)

        return wrapper
    }

    // ======================================================================
    // BADGE TAGS
    // ======================================================================

    private fun buildBadgeTagsRow(): View {
        val flow = FlowLayout(this)
        registerForEntryAnimation(flow)

        data class BadgeTag(val label: String, val background: Int, val foreground: Int)
        val badges = listOf(
            BadgeTag("Kotlin", Color.parseColor("#7F52FF"), Color.WHITE),
            BadgeTag(
                "Android ${getAndroidVersionName(getMinimumSdk())}+",
                Color.parseColor("#3DDC84"),
                Color.parseColor("#0A1A0A")
            ),
            BadgeTag(
                getString(R.string.badge_open_source),
                Color.parseColor("#4CAF50"),
                Color.WHITE
            ),
            BadgeTag("MIT", Color.parseColor("#2196F3"), Color.WHITE),
            BadgeTag(
                getString(R.string.badge_ai_ready),
                brandAccent,
                textOnAccent
            ),
            BadgeTag(
                "v${getInstalledVersionName()}",
                CodeAssistTheme.SURFACE_RAISED,
                brandAccent
            ),
        )
        badges.forEachIndexed { index, badge ->
            val tagView = createBadgeTag(badge.label, badge.background, badge.foreground)
            flow.addView(tagView)
            tagView.alpha = 0f
            tagView.animate()
                .alpha(1f)
                .setStartDelay(300L + index * 60L)
                .setDuration(350)
                .start()
        }
        return flow
    }

    // ======================================================================
    // CODE SAMPLE
    // ======================================================================

    private fun buildCodeSampleCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = TechPanelDrawable(
                accentColor = codeAccent,
                glowColor = Color.TRANSPARENT,
                chamferDp = 10f,
                withOrnaments = true,
                density = resources.displayMetrics.density
            )
        }
        registerForEntryAnimation(card)

        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        titleBar.addView(createDot(Color.parseColor("#FF5F56")))
        titleBar.addView(createDot(Color.parseColor("#FFBD2E")))
        titleBar.addView(createDot(Color.parseColor("#27C93F")))
        titleBar.addView(TextView(this).apply {
            text = "  one_code_for_ai.kt"
            setTextColor(codeAccent)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(dp(10), 0, 0, 0)
        })
        card.addView(titleBar)

        card.addView(TextView(this).apply {
            text = applyKotlinSyntaxHighlighting(getString(R.string.code_sample))
            setTextColor(codeBaseText)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.45f)
        })

        return card
    }

    // ======================================================================
    // FEATURES
    // ======================================================================

    private fun buildFeaturesCard(): View {
        val card = createPanelCard(featuresAccent)
        registerForEntryAnimation(card)
        card.addView(createSectionTitle(getString(R.string.features_title), featuresAccent))

        val features = listOf(
            R.drawable.ic_tech_code to R.string.feature_1,
            R.drawable.ic_tech_git to R.string.feature_2,
            R.drawable.ic_tech_filter to R.string.feature_3,
            R.drawable.ic_tech_share to R.string.feature_4,
            R.drawable.ic_tech_filter to R.string.feature_5,
            R.drawable.ic_tech_code to R.string.feature_6,
        )
        features.forEachIndexed { index, (iconResource, labelRes) ->
            val row = createIconRow(iconResource, getString(labelRes), featuresAccent)
            card.addView(row)
            row.alpha = 0f
            row.animate()
                .alpha(1f)
                .setStartDelay(500L + index * 60L)
                .setDuration(300)
                .start()
        }
        return card
    }

    // ======================================================================
    // INTEGRATION STEPS
    // ======================================================================

    private fun buildIntegrationStepsCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = TechPanelDrawable(
                accentColor = stepsAccent,
                glowColor = Color.TRANSPARENT,
                chamferDp = 12f,
                withOrnaments = true,
                density = resources.displayMetrics.density
            )
        }
        registerForEntryAnimation(card)
        card.addView(createSectionTitle(getString(R.string.integration_title), stepsAccent))

        data class Step(val number: String, val title: String, val description: String)
        val steps = listOf(
            Step(
                "01",
                getString(R.string.step_1_title),
                getString(R.string.step_1_desc)
            ),
            Step(
                "02",
                getString(R.string.step_2_title),
                getString(R.string.step_2_desc)
            ),
            Step(
                "03",
                getString(R.string.step_3_title),
                getString(R.string.step_3_desc)
            ),
            Step(
                "04",
                getString(R.string.step_4_title),
                getString(R.string.step_4_desc)
            )
        )

        steps.forEachIndexed { index, step ->
            val stepRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                setPadding(0, dp(8), 0, dp(8))
            }

            val badge = TextView(this).apply {
                text = step.number
                setTextColor(Color.parseColor("#051418"))
                textSize = 10f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(30)).apply { marginEnd = dp(12) }
                background = TechPanelDrawable(
                    accentColor = stepsAccent,
                    glowColor = Color.argb(100, Color.red(stepsAccent), Color.green(stepsAccent), Color.blue(stepsAccent)),
                    chamferDp = 9f,
                    withOrnaments = false,
                    density = resources.displayMetrics.density,
                    forceColoredFill = true
                )
            }
            stepRow.addView(badge)

            val descriptionColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            descriptionColumn.addView(TextView(this@PluginInfoActivity).apply {
                text = step.title
                setTextColor(CodeAssistTheme.TEXT_PRIMARY)
                textSize = 12.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            descriptionColumn.addView(TextView(this@PluginInfoActivity).apply {
                text = step.description
                setTextColor(CodeAssistTheme.TEXT_SECONDARY)
                textSize = 11f
                setLineSpacing(0f, 1.2f)
                setPadding(0, dp(2), 0, 0)
            })
            stepRow.addView(descriptionColumn)
            card.addView(stepRow)

            if (index < steps.lastIndex) {
                card.addView(DiagonalDividerView(this, stepsAccent).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(1)
                    ).apply {
                        marginStart = dp(52)
                        topMargin = dp(4)
                        bottomMargin = dp(4)
                    }
                })
            }

            stepRow.alpha = 0f
            stepRow.animate()
                .alpha(1f)
                .setStartDelay(700L + index * 80L)
                .setDuration(350)
                .start()
        }

        return card
    }

    // ======================================================================
    // LAUNCH BUTTON
    // ======================================================================

    private fun buildLaunchButton(): View {
        val button = RippleGlowButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(56)
            )
            registerForEntryAnimation(this)
        }

        button.addChild(ImageView(this).apply {
            setImageResource(R.drawable.ic_tech_rocket)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) }
            setColorFilter(textOnAccent)
        })
        button.addChild(TextView(this).apply {
            text = getString(R.string.open_codeassist)
            setTextColor(textOnAccent)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        button.addChild(TextView(this).apply {
            text = "   ▸"
            setTextColor(textOnAccent)
            textSize = 15f
        })

        button.setOnClickListener { launchCodeAssist() }
        return button
    }

    // ======================================================================
    // TECHNICAL INFO
    // ======================================================================

    private fun buildTechnicalInfoCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = TechPanelDrawable(
                accentColor = techAccent,
                glowColor = Color.TRANSPARENT,
                chamferDp = 12f,
                withOrnaments = true,
                density = resources.displayMetrics.density
            )
        }
        registerForEntryAnimation(card)
        card.addView(createSectionTitle(getString(R.string.tech_info_title), techAccent))

        val ideLocale = LanguageHelper.detectIdeLocale(this)
        val languageDisplay = ideLocale.getDisplayLanguage(ideLocale)
            .replaceFirstChar { it.titlecase(ideLocale) }
        val rows = listOf(
            getString(R.string.tech_info_package) to packageName,
            getString(R.string.tech_info_version) to "v${getInstalledVersionName()}",
            getString(R.string.tech_info_apk) to getFormattedApkSize(),
            getString(R.string.tech_info_min_sdk) to
                "Android ${getAndroidVersionName(getMinimumSdk())} (API ${getMinimumSdk()})",
            getString(R.string.tech_info_target_sdk) to
                "Android ${getAndroidVersionName(getTargetSdk())} (API ${getTargetSdk()})",
            getString(R.string.tech_info_language) to
                "$languageDisplay (${ideLocale.language.uppercase(ideLocale)})",
            getString(R.string.tech_info_status) to getString(R.string.tech_info_status_stable)
        )
        rows.forEachIndexed { index, (label, value) ->
            val row = createInfoRow(label, value)
            card.addView(row)
            row.alpha = 0f
            row.animate()
                .alpha(1f)
                .setStartDelay(600L + index * 40L)
                .setDuration(300)
                .start()
        }
        return card
    }

    // ======================================================================
    // LINKS
    // ======================================================================

    private fun buildLinksCard(): View {
        val card = createPanelCard(linksAccent)
        registerForEntryAnimation(card)
        card.addView(createSectionTitle(getString(R.string.links_title), linksAccent))

        val links = listOf(
            Triple(R.drawable.ic_tech_github, getString(R.string.link_repository), repositoryUrl),
            Triple(R.drawable.ic_tech_bug, getString(R.string.link_report_bug), issuesUrl),
            Triple(R.drawable.ic_tech_license, getString(R.string.link_license), licenseUrl),
        )
        links.forEachIndexed { index, (iconResource, label, url) ->
            val row = createLinkRow(iconResource, label, url, linksAccent)
            card.addView(row)
            row.alpha = 0f
            row.animate()
                .alpha(1f)
                .setStartDelay(550L + index * 80L)
                .setDuration(350)
                .start()
        }
        return card
    }

    // ======================================================================
    // CREATOR
    // ======================================================================

    private fun buildCreatorCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = TechPanelDrawable(
                accentColor = creatorAccent,
                glowColor = Color.TRANSPARENT,
                chamferDp = 14f,
                withOrnaments = true,
                density = resources.displayMetrics.density
            )
        }
        registerForEntryAnimation(card)

        val avatar = TextView(this).apply {
            text = "D"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginEnd = dp(14) }
            setShadowLayer(dp(8f), 0f, 0f, Color.argb(120, Color.red(successGreen), Color.green(successGreen), Color.blue(successGreen)))
            background = TechPanelDrawable(
                accentColor = successGreen,
                glowColor = successGreen,
                chamferDp = 12f,
                withOrnaments = false,
                density = resources.displayMetrics.density,
                forceColoredFill = true
            )
        }
        card.addView(avatar)
        animateTextGlow(avatar, successGreen)

        val infoColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        infoColumn.addView(TextView(this).apply {
            text = authorName
            setTextColor(CodeAssistTheme.TEXT_PRIMARY)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        infoColumn.addView(TextView(this).apply {
            text = getString(R.string.creator_dev)
            setTextColor(CodeAssistTheme.TEXT_SECONDARY)
            textSize = 11f
            setPadding(0, dp(2), 0, 0)
        })
        infoColumn.addView(TextView(this).apply {
            text = "github.com/devdayton"
            setTextColor(creatorAccent)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { openExternalUrl(authorUrl) }
        })
        card.addView(infoColumn)
        return card
    }

    // ======================================================================
    // FOOTER
    // ======================================================================

    private fun buildFooter(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        registerForEntryAnimation(this)

        addView(TextView(this@PluginInfoActivity).apply {
            text = getString(R.string.footer_made_with)
            setTextColor(CodeAssistTheme.TEXT_MUTED)
            textSize = 10f
            gravity = Gravity.CENTER
        })
        addView(TextView(this@PluginInfoActivity).apply {
            text = "© ${Calendar.getInstance().get(Calendar.YEAR)} $authorName  •  v${getInstalledVersionName()}"
            setTextColor(CodeAssistTheme.TEXT_MUTED)
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, 0)
        })
    }

    // ======================================================================
    // COMPONENTES REUTILIZABLES
    // ======================================================================

    private fun createPanelCard(accent: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = TechPanelDrawable(
            accentColor = accent,
            glowColor = Color.TRANSPARENT,
            chamferDp = 10f,
            withOrnaments = true,
            density = resources.displayMetrics.density
        )
    }

    private fun createSectionTitle(label: String, accent: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, dp(10))

        addView(TextView(this@PluginInfoActivity).apply {
            text = "#  "
            setTextColor(accent)
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.1f
        })

        addView(TextView(this@PluginInfoActivity).apply {
            text = label.uppercase(Locale.getDefault())
            setTextColor(CodeAssistTheme.TEXT_PRIMARY)
            textSize = 11f
            letterSpacing = 0.12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
    }

    private fun createDot(color: Int): View = View(this).apply {
        background = createRoundedBackground(color, 50f)
        layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(6) }
    }

    private fun createIconRow(iconResource: Int, label: String, accent: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(5), 0, dp(5))

        addView(ImageView(this@PluginInfoActivity).apply {
            setImageResource(iconResource)
            setColorFilter(accent)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) }
        })
        addView(TextView(this@PluginInfoActivity).apply {
            text = label
            setTextColor(CodeAssistTheme.TEXT_PRIMARY)
            textSize = 12.5f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
    }

    private fun createInfoRow(label: String, value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(4), 0, dp(4))

        addView(TextView(this@PluginInfoActivity).apply {
            text = label
            setTextColor(CodeAssistTheme.TEXT_SECONDARY)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(this@PluginInfoActivity).apply {
            text = value
            setTextColor(CodeAssistTheme.TEXT_PRIMARY)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.END
        })
    }

    private fun createLinkRow(iconResource: Int, label: String, url: String, accent: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        isClickable = true
        isFocusable = true
        setOnClickListener { openExternalUrl(url) }

        addView(ImageView(this@PluginInfoActivity).apply {
            setImageResource(iconResource)
            setColorFilter(accent)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) }
        })
        addView(TextView(this@PluginInfoActivity).apply {
            text = label
            setTextColor(CodeAssistTheme.TEXT_PRIMARY)
            textSize = 12.5f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(this@PluginInfoActivity).apply {
            text = "▸"
            setTextColor(accent)
            textSize = 14f
        })
    }

    private fun createBadgeTag(label: String, backgroundColor: Int, textColor: Int): TextView = TextView(this).apply {
        text = label
        setTextColor(textColor)
        textSize = 11f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(12), dp(6), dp(16), dp(6))
        setShadowLayer(
            dp(6f), 0f, 0f,
            Color.argb(80, Color.red(backgroundColor), Color.green(backgroundColor), Color.blue(backgroundColor))
        )
        background = TechPanelDrawable(
            accentColor = backgroundColor,
            glowColor = Color.TRANSPARENT,
            chamferDp = 8f,
            withOrnaments = false,
            density = resources.displayMetrics.density,
            forceColoredFill = true
        )
        layoutParams = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = dp(8)
            bottomMargin = dp(8)
        }
    }

    private fun createRoundedBackground(color: Int, radiusDp: Float, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * resources.displayMetrics.density
            if (stroke != null) setStroke(1, stroke)
        }

    private fun createSpacer(heightDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(heightDp)
        )
    }

    // ======================================================================
    // ANIMACIONES
    // ======================================================================

    private fun playTypewriterEffect(view: TextView, text: String) {
        val spannable = SpannableStringBuilder(text)
        for (i in text.indices) {
            spannable.setSpan(ForegroundColorSpan(Color.TRANSPARENT), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(StyleSpan(Typeface.BOLD), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        view.text = spannable

        var revealed = 0
        fun revealNext() {
            if (revealed >= text.length) return
            val index = revealed
            spannable.setSpan(ForegroundColorSpan(CodeAssistTheme.TEXT_PRIMARY), index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(StyleSpan(Typeface.BOLD), index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            view.text = spannable
            revealed++
            mainHandler.postDelayed({ revealNext() }, 55L)
        }
        mainHandler.postDelayed({ revealNext() }, 250L)
    }

    private fun animateTextGlow(view: TextView, glowColor: Int) {
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val progress = it.animatedValue as Float
                val radius = dp(4f) + dp(10f) * progress
                val alpha = (60 + 180 * progress).toInt().coerceIn(0, 255)
                view.setShadowLayer(
                    radius, 0f, 0f,
                    Color.argb(alpha, Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor))
                )
                view.invalidate()
            }
        }
        activeAnimators.add(animator)
        animator.start()
    }

    private fun animateStatusDotPulse(dot: View) {
        ObjectAnimator.ofPropertyValuesHolder(
            dot,
            PropertyValuesHolder.ofFloat("alpha", 0.45f, 1f),
            PropertyValuesHolder.ofFloat("scaleX", 0.85f, 1.15f),
            PropertyValuesHolder.ofFloat("scaleY", 0.85f, 1.15f)
        ).apply {
            duration = 1600
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private fun animateIconGlowPulse(icon: View, glowColor: Int) {
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2600
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val progress = it.animatedValue as Float
                val alpha = (40 + 210 * progress).toInt().coerceIn(0, 255)
                val glow = Color.argb(alpha, Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor))
                (icon.background as? TechPanelDrawable)?.setGlowColor(glow)
                icon.invalidate()
            }
        }
        activeAnimators.add(animator)
        animator.start()
    }

    private fun playStaggeredEntry() {
        entryAnimationTargets.forEachIndexed { index, view ->
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(index * 70L)
                .setDuration(430)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
        }
    }

    private fun registerForEntryAnimation(view: View) {
        entryAnimationTargets.add(view)
        view.alpha = 0f
        view.translationY = dp(38f)
    }

    // ======================================================================
    // UTILIDADES
    // ======================================================================

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun getStatusBarHeightPx(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else dp(24)
    }

    private fun applyKotlinSyntaxHighlighting(source: String): SpannableStringBuilder {
        val spannable = SpannableStringBuilder(source)
        val keywords = listOf(
            "val", "var", "fun", "class", "object",
            "return", "if", "else", "when", "for", "while"
        )
        keywords.forEach { keyword ->
            var index = source.indexOf(keyword)
            while (index >= 0) {
                val startOk = index == 0 || !source[index - 1].isLetterOrDigit()
                val endOk = index + keyword.length == source.length ||
                    !source[index + keyword.length].isLetterOrDigit()
                if (startOk && endOk) {
                    spannable.setSpan(
                        ForegroundColorSpan(codeKeywordColor),
                        index, index + keyword.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                index = source.indexOf(keyword, index + keyword.length)
            }
        }
        Regex("\"([^\"]*)\"").findAll(source).forEach {
            spannable.setSpan(ForegroundColorSpan(codeStringColor), it.range.first, it.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        Regex("//[^\n]*").findAll(source).forEach {
            spannable.setSpan(ForegroundColorSpan(codeCommentColor), it.range.first, it.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        Regex("\\b\\d+\\b").findAll(source).forEach {
            spannable.setSpan(ForegroundColorSpan(codeNumberColor), it.range.first, it.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        Regex("\\b[A-Z][A-Za-z0-9_]*\\b").findAll(source).forEach {
            spannable.setSpan(ForegroundColorSpan(codeFunctionColor), it.range.first, it.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return spannable
    }

    private fun getInstalledVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
    } catch (_: Exception) { "1.0.0" }

    private fun getMinimumSdk(): Int = try {
        if (Build.VERSION.SDK_INT >= 24) packageManager.getApplicationInfo(packageName, 0).minSdkVersion else 26
    } catch (_: Exception) { 26 }

    private fun getTargetSdk(): Int = try {
        if (Build.VERSION.SDK_INT >= 24) packageManager.getApplicationInfo(packageName, 0).targetSdkVersion else 36
    } catch (_: Exception) { 36 }

    private fun getAndroidVersionName(sdkLevel: Int): String = when (sdkLevel) {
        21 -> "5.0"; 22 -> "5.1"; 23 -> "6.0"; 24 -> "7.0"; 25 -> "7.1"
        26 -> "8.0"; 27 -> "8.1"; 28 -> "9";   29 -> "10";  30 -> "11"
        31 -> "12";  32 -> "12L"; 33 -> "13";  34 -> "14";  35 -> "15"
        36 -> "16";  else -> sdkLevel.toString()
    }

    private fun getFormattedApkSize(): String = try {
        val apkPath = packageManager.getApplicationInfo(packageName, 0).sourceDir
        val bytes = File(apkPath).length()
        when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
        }
    } catch (_: Exception) { "—" }

    private fun openExternalUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.no_app_for_link), Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchCodeAssist() {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(hostIdePackage)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
            } else {
                Toast.makeText(this, getString(R.string.codeassist_not_installed), Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.codeassist_open_error), Toast.LENGTH_SHORT).show()
        }
    }

    // ======================================================================
    // TECH PANEL DRAWABLE
    // ======================================================================

    private class TechPanelDrawable(
        private val accentColor: Int,
        private var glowColor: Int,
        private val chamferDp: Float,
        private val withOrnaments: Boolean = false,
        private val density: Float,
        private val forceColoredFill: Boolean = false
    ) : Drawable() {

        private val panelPath = Path()
        private val innerFramePath = Path()
        private val ornamentPath = Path()
        private val diamondPath = Path()

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val topHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val bottomShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * density
        }
        private val innerFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.6f * density
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f * density
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                maskFilter = BlurMaskFilter(5f * density, BlurMaskFilter.Blur.OUTER)
            }
        }
        private val ornamentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.argb(240, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        }
        private val diamondPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(240, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        }

        fun setGlowColor(color: Int) {
            glowColor = color
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            val width = bounds.width().toFloat()
            val height = bounds.height().toFloat()
            if (width <= 0f || height <= 0f) return

            val minDimension = minOf(width, height)
            val chamfer = (chamferDp * density).coerceIn(0f, minDimension * 0.3f)

            buildPanelPath(width, height, chamfer)

            if (glowColor != Color.TRANSPARENT) {
                glowPaint.color = glowColor
                canvas.drawPath(panelPath, glowPaint)
            }

            if (forceColoredFill) {
                fillPaint.shader = LinearGradient(
                    0f, 0f, 0f, height,
                    accentColor,
                    adjustAlpha(accentColor, 210),
                    Shader.TileMode.CLAMP
                )
            } else {
                fillPaint.shader = LinearGradient(
                    0f, 0f, 0f, height,
                    Color.parseColor("#131318"),
                    Color.parseColor("#0F0F13"),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawPath(panelPath, fillPaint)

            if (!forceColoredFill) {
                topHighlightPaint.shader = LinearGradient(
                    0f, 0f, 0f, height * 0.4f,
                    intArrayOf(
                        Color.argb(14, 255, 255, 255),
                        Color.argb(6, 255, 255, 255),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.4f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.save()
                canvas.clipPath(panelPath)
                canvas.drawRect(0f, 0f, width, height * 0.4f, topHighlightPaint)
                canvas.restore()

                bottomShadowPaint.shader = LinearGradient(
                    0f, height * 0.7f, 0f, height,
                    intArrayOf(Color.TRANSPARENT, Color.argb(60, 0, 0, 0)),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.save()
                canvas.clipPath(panelPath)
                canvas.drawRect(0f, height * 0.7f, width, height, bottomShadowPaint)
                canvas.restore()
            }

            borderPaint.shader = LinearGradient(
                0f, 0f, width, height,
                Color.argb(230, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
                Color.argb(70, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(panelPath, borderPaint)

            if (withOrnaments) drawOrnaments(canvas, width, height, chamfer)
        }

        private fun adjustAlpha(color: Int, alpha: Int): Int =
            Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

        private fun buildPanelPath(width: Float, height: Float, chamfer: Float) {
            panelPath.reset()
            val radius = minOf(4f * density, width * 0.1f, height * 0.1f)
            panelPath.moveTo(radius, 0f)
            panelPath.lineTo(width - chamfer, 0f)
            panelPath.lineTo(width, chamfer)
            panelPath.lineTo(width, height - radius)
            panelPath.quadTo(width, height, width - radius, height)
            panelPath.lineTo(chamfer, height)
            panelPath.lineTo(0f, height - chamfer)
            panelPath.lineTo(0f, radius)
            panelPath.quadTo(0f, 0f, radius, 0f)
            panelPath.close()
        }

        private fun drawOrnaments(canvas: Canvas, width: Float, height: Float, chamfer: Float) {
            val tickLength = 8f * density
            val inset = 5f * density
            val diamondSize = 2.8f * density
            val innerGap = 5f * density
            val innerChamfer = chamfer * 0.5f
            val innerRadius = 3f * density

            innerFramePath.reset()
            innerFramePath.moveTo(innerGap + innerRadius, innerGap)
            innerFramePath.lineTo(width - innerGap - innerChamfer, innerGap)
            innerFramePath.lineTo(width - innerGap, innerGap + innerChamfer)
            innerFramePath.lineTo(width - innerGap, height - innerGap - innerRadius)
            innerFramePath.quadTo(width - innerGap, height - innerGap, width - innerGap - innerRadius, height - innerGap)
            innerFramePath.lineTo(innerGap + innerChamfer, height - innerGap)
            innerFramePath.lineTo(innerGap, height - innerGap - innerChamfer)
            innerFramePath.lineTo(innerGap, innerGap + innerRadius)
            innerFramePath.quadTo(innerGap, innerGap, innerGap + innerRadius, innerGap)
            innerFramePath.close()
            innerFramePaint.color = Color.argb(70, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
            canvas.drawPath(innerFramePath, innerFramePaint)

            ornamentPath.reset()
            ornamentPath.moveTo(inset + tickLength, inset)
            ornamentPath.lineTo(inset, inset)
            ornamentPath.lineTo(inset, inset + tickLength)
            canvas.drawPath(ornamentPath, ornamentPaint)

            ornamentPath.reset()
            ornamentPath.moveTo(width - inset - tickLength, height - inset)
            ornamentPath.lineTo(width - inset, height - inset)
            ornamentPath.lineTo(width - inset, height - inset - tickLength)
            canvas.drawPath(ornamentPath, ornamentPaint)

            drawDiamond(canvas, width - chamfer - diamondSize * 1.6f, chamfer + diamondSize * 1.6f, diamondSize)
            drawDiamond(canvas, chamfer + diamondSize * 1.6f, height - chamfer - diamondSize * 1.6f, diamondSize)

            ornamentPath.reset()
            ornamentPath.moveTo(width / 2f - tickLength, inset)
            ornamentPath.lineTo(width / 2f + tickLength, inset)
            canvas.drawPath(ornamentPath, ornamentPaint)

            ornamentPath.reset()
            ornamentPath.moveTo(width / 2f - tickLength, height - inset)
            ornamentPath.lineTo(width / 2f + tickLength, height - inset)
            canvas.drawPath(ornamentPath, ornamentPaint)
        }

        private fun drawDiamond(canvas: Canvas, cx: Float, cy: Float, size: Float) {
            diamondPath.reset()
            diamondPath.moveTo(cx, cy - size)
            diamondPath.lineTo(cx + size, cy)
            diamondPath.lineTo(cx, cy + size)
            diamondPath.lineTo(cx - size, cy)
            diamondPath.close()
            canvas.drawPath(diamondPath, diamondPaint)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in API 29")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    // ======================================================================
    // BOTÓN GLASS CON RIPPLE
    // ======================================================================

    private inner class RippleGlowButton(context: Context) : FrameLayout(context) {
        private val backgroundDrawable = GlassButtonBackground(
            chamferDp = 12f,
            density = resources.displayMetrics.density
        )
        private val contentContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        private val rippleOverlay = RadialRippleOverlay(context)

        init {
            background = backgroundDrawable
            addView(contentContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(rippleOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            isClickable = true
            isFocusable = true

            setOnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        backgroundDrawable.setPressed(true)
                        animate().scaleX(0.97f).scaleY(0.97f).setDuration(100)
                            .setInterpolator(DecelerateInterpolator()).start()
                        rippleOverlay.startRipple(event.x, event.y)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        backgroundDrawable.setPressed(false)
                        animate().scaleX(1f).scaleY(1f).setDuration(180)
                            .setInterpolator(DecelerateInterpolator()).start()
                        view.performClick()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        backgroundDrawable.setPressed(false)
                        animate().scaleX(1f).scaleY(1f).setDuration(180)
                            .setInterpolator(DecelerateInterpolator()).start()
                        true
                    }
                    else -> false
                }
            }
        }

        fun addChild(view: View) {
            contentContainer.addView(view)
        }
    }

    private class GlassButtonBackground(
        private val chamferDp: Float,
        private val density: Float
    ) : Drawable() {
        private val panelPath = Path()
        private var pressed = false

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val texturePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.8f * density
            color = Color.argb(18, 255, 220, 180)
        }
        private val topTintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val bottomShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * density
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f * density
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                maskFilter = BlurMaskFilter(5f * density, BlurMaskFilter.Blur.OUTER)
            }
        }

        fun setPressed(value: Boolean) {
            pressed = value
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            val width = bounds.width().toFloat()
            val height = bounds.height().toFloat()
            if (width <= 0f || height <= 0f) return

            val chamfer = (chamferDp * density).coerceIn(0f, minOf(width, height) * 0.3f)
            val radius = 4f * density

            panelPath.reset()
            panelPath.moveTo(radius, 0f)
            panelPath.lineTo(width - chamfer, 0f)
            panelPath.lineTo(width, chamfer)
            panelPath.lineTo(width, height - radius)
            panelPath.quadTo(width, height, width - radius, height)
            panelPath.lineTo(chamfer, height)
            panelPath.lineTo(0f, height - chamfer)
            panelPath.lineTo(0f, radius)
            panelPath.quadTo(0f, 0f, radius, 0f)
            panelPath.close()

            glowPaint.color = if (pressed) {
                Color.argb(240, 255, 170, 80)
            } else {
                Color.argb(130, 255, 170, 80)
            }
            canvas.drawPath(panelPath, glowPaint)

            val baseAlpha = if (pressed) 235 else 205
            fillPaint.shader = LinearGradient(
                0f, 0f, 0f, height,
                Color.argb(baseAlpha, 235, 165, 90),
                Color.argb((baseAlpha - 40).coerceAtLeast(0), 180, 115, 60),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(panelPath, fillPaint)

            canvas.save()
            canvas.clipPath(panelPath)
            val lineSpacing = 3f * density
            var y = lineSpacing
            while (y < height) {
                canvas.drawLine(0f, y, width, y, texturePaint)
                y += lineSpacing
            }
            canvas.restore()

            canvas.save()
            canvas.clipPath(panelPath)
            topTintPaint.shader = LinearGradient(
                0f, 0f, 0f, height * 0.5f,
                intArrayOf(
                    Color.argb(if (pressed) 90 else 60, 255, 210, 150),
                    Color.argb(15, 255, 210, 150),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.4f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, width, height * 0.5f, topTintPaint)
            canvas.restore()

            canvas.save()
            canvas.clipPath(panelPath)
            bottomShadowPaint.shader = LinearGradient(
                0f, height * 0.65f, 0f, height,
                intArrayOf(Color.TRANSPARENT, Color.argb(85, 0, 0, 0)),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, height * 0.65f, width, height, bottomShadowPaint)
            canvas.restore()

            val borderAlpha = if (pressed) 240 else 195
            borderPaint.shader = LinearGradient(
                0f, 0f, width, height,
                Color.argb(borderAlpha, 255, 220, 160),
                Color.argb(90, 210, 130, 60),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(panelPath, borderPaint)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in API 29")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private class RadialRippleOverlay(context: Context) : View(context) {
        private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                maskFilter = BlurMaskFilter(4f * resources.displayMetrics.density, BlurMaskFilter.Blur.NORMAL)
            }
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * resources.displayMetrics.density
        }
        private var originX = 0f
        private var originY = 0f
        private var progress = 0f
        private var animator: ValueAnimator? = null
        private val maxRadius = resources.displayMetrics.widthPixels * 1.2f

        fun startRipple(x: Float, y: Float) {
            originX = x
            originY = y
            progress = 0f
            animator?.cancel()
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 700
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            if (progress <= 0f || progress >= 1f) return
            val radius = maxRadius * progress
            val alpha = ((1f - progress) * 255).toInt().coerceIn(0, 255)
            ripplePaint.color = Color.argb(alpha / 2, 255, 220, 150)
            canvas.drawCircle(originX, originY, radius, ripplePaint)
            ringPaint.color = Color.argb(alpha, 255, 240, 200)
            canvas.drawCircle(originX, originY, radius * 0.92f, ringPaint)
        }
    }

    // ======================================================================
    // FONDO ESTÁTICO CON CUADRÍCULA
    // ======================================================================

    private inner class StaticGridBackgroundView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density

        private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0A0A0C")
        }
        private val cellFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(6, 255, 220, 180)
        }
        private val gridLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.8f * density
            color = Color.argb(18, 255, 255, 255)
        }
        private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        private val cellSize = 32f * density

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            vignettePaint.shader = RadialGradient(
                w / 2f, h * 0.4f, maxOf(w, h) * 0.9f,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(180, 0, 0, 0)),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            canvas.drawRect(0f, 0f, w, h, basePaint)

            val cols = (w / cellSize).toInt() + 1
            val rows = (h / cellSize).toInt() + 1

            for (row in 0 until rows) {
                for (col in 0 until cols) {
                    val left = col * cellSize
                    val top = row * cellSize
                    canvas.drawRect(left, top, left + cellSize, top + cellSize, cellFillPaint)
                }
            }

            for (col in 0..cols) {
                val x = col * cellSize
                canvas.drawLine(x, 0f, x, h, gridLinePaint)
            }
            for (row in 0..rows) {
                val y = row * cellSize
                canvas.drawLine(0f, y, w, y, gridLinePaint)
            }

            canvas.drawRect(0f, 0f, w, h, vignettePaint)
        }
    }

    // ======================================================================
    // DIVISOR DIAGONAL
    // ======================================================================

    private class DiagonalDividerView(
        context: Context,
        private val accentColor: Int
    ) : View(context) {
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(110, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }
        private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            val width = width.toFloat()
            val height = height.toFloat()
            val step = 8f * resources.displayMetrics.density

            var x = 0f
            while (x < width) {
                val nextX = minOf(x + step / 2, width)
                canvas.drawLine(x, height, nextX, 0f, linePaint)
                x = nextX + step / 2
            }
            canvas.drawPath(Path().apply {
                moveTo(0f, height)
                lineTo(step, height)
                lineTo(0f, height - step)
                close()
            }, markerPaint)
        }
    }
}