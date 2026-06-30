package com.faqxd.livesub.android

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.faqxd.livesub.android.data.SessionLogStore

class SessionLogActivity : AppCompatActivity() {
    private lateinit var logEdit: EditText
    private lateinit var copyBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var closeBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session_log)
        title = getString(R.string.session_log_title)

        logEdit = findViewById(R.id.sessionLogEdit)
        copyBtn = findViewById(R.id.copyLogBtn)
        saveBtn = findViewById(R.id.saveLogBtn)
        closeBtn = findViewById(R.id.closeLogBtn)

        logEdit.setText(SessionLogStore.load(this))

        copyBtn.setOnClickListener {
            val text = logEdit.text.toString()
            SessionLogStore.copyToClipboard(this, text)
            Toast.makeText(this, R.string.session_log_copied, Toast.LENGTH_SHORT).show()
        }

        saveBtn.setOnClickListener {
            SessionLogStore.save(this, logEdit.text.toString())
            Toast.makeText(this, R.string.session_log_saved, Toast.LENGTH_SHORT).show()
        }

        closeBtn.setOnClickListener {
            SessionLogStore.save(this, logEdit.text.toString())
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        SessionLogStore.save(this, logEdit.text.toString())
        finish()
        return true
    }
}
