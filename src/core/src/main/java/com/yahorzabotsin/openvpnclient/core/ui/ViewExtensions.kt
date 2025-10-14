package com.yahorzabotsin.openvpnclient.core.ui

import android.view.View
import android.widget.Toast

fun View.setAsStub() {
    setOnClickListener {
        Toast.makeText(context, "Feature in Development", Toast.LENGTH_SHORT).show()
    }
}