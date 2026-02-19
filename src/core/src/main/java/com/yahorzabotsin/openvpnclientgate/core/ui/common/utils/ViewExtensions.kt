package com.yahorzabotsin.openvpnclientgate.core.ui.common.utils

import android.view.View
import android.widget.Toast
import com.yahorzabotsin.openvpnclientgate.core.R

fun View.setAsStub() {
    setOnClickListener {
        Toast.makeText(context, R.string.feature_in_development, Toast.LENGTH_SHORT).show()
    }
}


