package com.yahorzabotsin.openvpnclient.tv

import android.os.Bundle
import android.view.View

import androidx.core.content.ContextCompat
import androidx.leanback.app.ErrorSupportFragment
import com.yahorzabotsin.openvpnclient.core.R as coreR

/**
 * This class demonstrates how to extend [ErrorSupportFragment].
 */
class ErrorFragment : ErrorSupportFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = resources.getString(R.string.app_name)
    }

    internal fun setErrorContent() {
        imageDrawable =
            ContextCompat.getDrawable(requireActivity(), androidx.leanback.R.drawable.lb_ic_sad_cloud)
        message = resources.getString(coreR.string.error_fragment_message)
        setDefaultBackground(TRANSLUCENT)

        buttonText = resources.getString(coreR.string.dismiss_error)
        buttonClickListener = View.OnClickListener {
            parentFragmentManager.beginTransaction().remove(this@ErrorFragment).commit()
        }
    }

    companion object {
        private val TRANSLUCENT = true
    }
}