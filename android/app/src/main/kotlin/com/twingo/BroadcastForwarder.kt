package com.twingo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Exists only because intent used in notification pending indents have to be explicitly linked to a
// class.
class BroadcastForwarder : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context?.sendBroadcast(Intent(intent?.action))
    }
}
