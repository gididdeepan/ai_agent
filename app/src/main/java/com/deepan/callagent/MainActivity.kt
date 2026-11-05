package com.deepan.callagent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView

    private val permissions: Array<String>
        get() {
            val list = mutableListOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.ANSWER_PHONE_CALLS
            )
            if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
            return list.toTypedArray()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
        }
        status = TextView(this).apply { textSize = 18f; gravity = Gravity.CENTER }
        val start = Button(this).apply { text = "START"; setOnClickListener { onStartClicked() } }
        val stop = Button(this).apply { text = "STOP"; setOnClickListener { onStopClicked() } }
        val tip = TextView(this).apply {
            text = "Tips:\n1. Phone Settings -> Apps -> Call Agent -> Battery -> Unrestricted\n" +
                    "2. Keep media volume up\n3. Google speech service + internet venum"
            textSize = 13f
            setPadding(0, 48, 0, 0)
        }
        root.addView(status)
        root.addView(start)
        root.addView(stop)
        root.addView(tip)
        setContentView(root)
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        status.text = if (CallAgentService.running) "Agent RUNNING\nCall vandha solluven"
        else "Agent STOPPED"
    }

    private fun allGranted() = permissions.all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun onStartClicked() {
        if (allGranted()) startAgent() else requestPermissions(permissions, 1)
    }

    private fun startAgent() {
        val i = Intent(this, CallAgentService::class.java).setAction(CallAgentService.ACTION_START)
        startForegroundService(i)
        status.text = "Agent RUNNING\nCall vandha solluven"
    }

    private fun onStopClicked() {
        val i = Intent(this, CallAgentService::class.java).setAction(CallAgentService.ACTION_STOP)
        startService(i)
        status.text = "Agent STOPPED"
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (allGranted()) startAgent()
        else Toast.makeText(this, "Ella permission-um allow pannunga", Toast.LENGTH_LONG).show()
    }
}
