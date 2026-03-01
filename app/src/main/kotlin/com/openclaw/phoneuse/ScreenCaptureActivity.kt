package com.openclaw.phoneuse

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log

/**
 * Transparent activity that requests MediaProjection permission.
 * Launches the system screen capture consent dialog, stores the result,
 * then finishes immediately.
 */
class ScreenCaptureActivity : Activity() {

    companion object {
        private const val TAG = "ScreenCaptureActivity"
        private const val REQUEST_CODE = 9001

        fun requestPermission(context: Context) {
            val intent = Intent(context, ScreenCaptureActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                Log.i(TAG, "Screen capture permission granted")
                ScreenCaptureManager.storePermission(resultCode, data)
            } else {
                Log.w(TAG, "Screen capture permission denied")
                ScreenCaptureManager.storePermission(RESULT_CANCELED, null)
            }
        }
        finish()
    }
}
