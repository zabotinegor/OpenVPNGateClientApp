package com.yahorzabotsin.openvpnclient.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity

/**
 * BrowseErrorActivity shows how to use ErrorFragment.
 */
class BrowseErrorActivity : FragmentActivity() {

    private lateinit var mErrorFragment: ErrorFragment
    private lateinit var mSpinnerFragment: SpinnerFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_error)
        if (savedInstanceState == null) {
            setupFragments()
        }
    }

    private fun setupFragments() {
        mErrorFragment = ErrorFragment()
        mSpinnerFragment = SpinnerFragment()

        supportFragmentManager
            .beginTransaction()
            .add(R.id.error_frame, mErrorFragment)
            .add(R.id.error_frame, mSpinnerFragment)
            .commit()

        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({
            supportFragmentManager
                .beginTransaction()
                .remove(mSpinnerFragment)
                .commit()
            mErrorFragment.setErrorContent()
        }, TIMER_DELAY)
    }

    class SpinnerFragment : Fragment() {
        override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View? {
            val progressBar = ProgressBar(container?.context)
            if (container is FrameLayout) {
                val spinnerWidth = resources.getDimensionPixelSize(R.dimen.spinner_width)
                val spinnerHeight = resources.getDimensionPixelSize(R.dimen.spinner_height)
                val layoutParams =
                    FrameLayout.LayoutParams(spinnerWidth, spinnerHeight, Gravity.CENTER)
                progressBar.layoutParams = layoutParams
            }
            return progressBar
        }
    }

    companion object {
        private const val TIMER_DELAY = 3000L
    }
}
