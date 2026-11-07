package com.localinterpreter.tablet

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localinterpreter.tablet.audio.LiveService
import com.localinterpreter.tablet.ui.AppTheme
import com.localinterpreter.tablet.ui.MainScreen

class MainActivity : ComponentActivity() {
    private enum class PendingAction { MIC, SYSTEM }
    private var pendingCourseId: Long? = null
    private var pendingAction = PendingAction.MIC
    private val openLiveSignal = mutableIntStateOf(0)

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            if (pendingAction == PendingAction.SYSTEM) {
                launchProjection(pendingCourseId)
            } else {
                startMicService(pendingCourseId)
            }
        } else {
            Toast.makeText(this, "需要录音权限才能采集麦克风或系统音频", Toast.LENGTH_LONG).show()
        }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "通知权限未开启：后台同传时通知栏不会显示暂停/结束按钮，请回到应用内操作",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private val fileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            startLive { LiveService.startFile(this, pendingCourseId, uri) }
        }
    }

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startLive { LiveService.startSystem(this, pendingCourseId, result.resultCode, result.data!!) }
        } else {
            Toast.makeText(this, "已取消屏幕录制授权", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val openUri = if (savedInstanceState == null && intent?.action == Intent.ACTION_VIEW) intent?.data else null
        val openCourse = intent?.getLongExtra("course_id", -1L)?.takeIf { it > 0 }
        setContent {
            val app = application as App
            val settings by app.settings.state.collectAsStateWithLifecycle()
            val live by app.live.state.collectAsStateWithLifecycle()
            androidx.compose.runtime.LaunchedEffect(live.running) {
                if (live.running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            AppTheme(settings.theme) {
                MainScreen(
                    activity = this,
                    liveSignal = openLiveSignal.intValue,
                    onStartMic = { courseId -> requestMic(courseId) },
                    onStartSystem = { courseId -> requestProjection(courseId) },
                    onStartFile = { courseId ->
                        pendingCourseId = courseId
                        try {
                            fileLauncher.launch(arrayOf("audio/*"))
                        } catch (t: Throwable) {
                            Toast.makeText(this, "没有可用的文件选择器", Toast.LENGTH_LONG).show()
                        }
                    },
                )
            }
        }
        if (openUri != null) {
            openLiveSignal.intValue++
            val st = (application as App).live.state.value
            if (st.running || st.starting) {
                Toast.makeText(this, "请先结束当前同传，再打开新的音频文件", Toast.LENGTH_LONG).show()
            } else {
                window.decorView.post { startLive { LiveService.startFile(this, openCourse, openUri) } }
            }
            // 消费掉 VIEW intent，避免 Activity 重建时重复打开同一个文件。
            setIntent(Intent(this, MainActivity::class.java))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            openLiveSignal.intValue++
            val st = (application as App).live.state.value
            if (st.running || st.starting) {
                Toast.makeText(this, "请先结束当前同传，再打开新的音频文件", Toast.LENGTH_LONG).show()
            } else {
                startLive {
                    LiveService.startFile(
                        this,
                        intent.getLongExtra("course_id", -1L).takeIf { it > 0 },
                        intent.data!!,
                    )
                }
            }
            setIntent(Intent(this, MainActivity::class.java))
        } else {
            setIntent(intent)
        }
    }

    private fun requestMic(courseId: Long?) {
        pendingCourseId = courseId
        pendingAction = PendingAction.MIC
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startMicService(courseId)
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startMicService(courseId: Long?) {
        startLive { LiveService.startMic(this, courseId) }
    }

    /** 前台服务被系统拒绝时给出提示，而不是让 Activity 直接崩掉。 */
    private fun startLive(action: () -> Unit) {
        try {
            action()
        } catch (t: Throwable) {
            val msg = "无法启动同传：${t.message?.take(80) ?: "系统限制了后台录音"}"
            (application as App).live.reportError(msg)
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun requestProjection(courseId: Long?) {
        pendingCourseId = courseId
        pendingAction = PendingAction.SYSTEM
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            launchProjection(courseId)
        }
    }

    private fun launchProjection(courseId: Long?) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}

