package com.kirufetch.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.kirufetch.app.R
import com.kirufetch.app.settings.AppPrefs

/**
 * 首次启动的四页说明。也可以从设置里重新打开。
 * 不依赖 ViewPager，四页就是四次文案替换，省一个依赖。
 */
class OnboardingActivity : AppCompatActivity() {

    private data class Page(
        val title: String,
        val body: String,
        val showGroup: Boolean
    )

    private lateinit var pages: List<Page>
    private var index = 0

    private lateinit var step: TextView
    private lateinit var title: TextView
    private lateinit var body: TextView
    private lateinit var groupCard: View
    private lateinit var next: TextView
    private lateinit var dots: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        pages = listOf(
            Page(
                getString(R.string.onboard_1_title),
                getString(R.string.onboard_1_body),
                false
            ),
            Page(
                getString(R.string.onboard_2_title),
                getString(R.string.onboard_2_body),
                false
            ),
            Page(
                getString(R.string.onboard_3_title),
                getString(R.string.onboard_3_body),
                false
            ),
            Page(
                getString(R.string.onboard_4_title),
                getString(R.string.onboard_4_body),
                true
            )
        )

        step = findViewById(R.id.onboard_step)
        title = findViewById(R.id.onboard_title)
        body = findViewById(R.id.onboard_body)
        groupCard = findViewById(R.id.onboard_group_card)
        next = findViewById(R.id.onboard_next)
        dots = findViewById(R.id.onboard_dots)

        findViewById<View>(R.id.onboard_skip).setOnClickListener { finishOnboarding() }
        next.setOnClickListener {
            if (index < pages.lastIndex) {
                index++
                render()
            } else {
                finishOnboarding()
            }
        }
        findViewById<View>(R.id.onboard_group_card).setOnClickListener {
            copyGroupNumber()
        }

        buildDots()
        render()
    }

    private fun buildDots() {
        val size = (6 * resources.displayMetrics.density).toInt()
        val gap = (6 * resources.displayMetrics.density).toInt()
        dots.removeAllViews()
        pages.indices.forEach { i ->
            val dot = View(this)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.marginEnd = gap
            dot.layoutParams = lp
            dot.setBackgroundResource(R.drawable.bg_dot)
            dots.addView(dot)
        }
    }

    private fun render() {
        val page = pages[index]
        step.text = getString(R.string.onboard_step, index + 1)
        title.text = page.title
        body.text = page.body
        groupCard.visibility = if (page.showGroup) View.VISIBLE else View.GONE
        next.text = getString(
            if (index == pages.lastIndex) R.string.onboard_start else R.string.onboard_next
        )

        // 每次换页做一次轻微上浮，避免"啪"地换掉
        listOf(step, title, body).forEachIndexed { i, view ->
            view.alpha = 0f
            view.translationY = 12 * resources.displayMetrics.density
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay((i * 40).toLong())
                .setDuration(220)
                .start()
        }

        for (i in 0 until dots.childCount) {
            val active = i == index
            val dot = dots.getChildAt(i)
            val size = ((if (active) 6 else 4) * resources.displayMetrics.density).toInt()
            dot.layoutParams = (dot.layoutParams as LinearLayout.LayoutParams).apply {
                width = size
                height = size
            }
            dot.alpha = if (active) 1f else 0.35f
        }
    }

    private fun copyGroupNumber() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("qq", getString(R.string.feedback_group_number)))
        Toast.makeText(this, R.string.onboard_group_copied, Toast.LENGTH_SHORT).show()
    }

    private fun finishOnboarding() {
        AppPrefs(this).onboarded = true
        finish()
    }

    override fun onBackPressed() {
        if (index > 0) {
            index--
            render()
        } else {
            finishOnboarding()
        }
    }
}
