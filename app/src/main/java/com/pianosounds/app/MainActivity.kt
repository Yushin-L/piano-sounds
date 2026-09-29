package com.pianosounds.app

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.*
import android.os.*
import android.text.InputType
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class MainActivity : Activity() {
    companion object {
        // One owner for the process-wide native engine, including across Activity recreation.
        private val audioWorker = Executors.newSingleThreadExecutor()
    }
    private val main = Handler(Looper.getMainLooper())
    private lateinit var midi: MidiConnection
    private lateinit var audio: AudioManager
    private lateinit var focusRequest: AudioFocusRequest
    private lateinit var connectionText: TextView
    private lateinit var audioText: TextView
    private lateinit var inputText: TextView
    private lateinit var pedalText: TextView
    private lateinit var voicesText: TextView
    private lateinit var piano: PianoView
    private lateinit var preview: Button
    private lateinit var retry: Button
    private lateinit var metronomeToggle: Switch
    private var metronomeBpm = 100
    private var metronomeVolume = 50
    private val count = AtomicLong()
    private val lastNote = AtomicInteger(-1)
    private val velocity = AtomicInteger()
    private val pedals = AtomicInteger()
    private var foreground = false
    private var focus = false
    @Volatile private var epoch = 0
    private var ready = false
    private var starting = false
    @Volatile private var volume = 70
    private var previewEpoch = 0
    private val ink = Color.rgb(35, 52, 43)
    private val green = Color.rgb(40, 94, 75)
    private val muted = Color.rgb(98, 111, 100)
    private val cream = Color.rgb(245, 242, 234)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC
        volume = getPreferences(MODE_PRIVATE).getInt("volume", 70).coerceIn(0, 100)
        metronomeBpm = getPreferences(MODE_PRIVATE).getInt("metronomeBpm", 100).coerceIn(40, 240)
        metronomeVolume = getPreferences(MODE_PRIVATE).getInt("metronomeVolume", 50).coerceIn(0, 100)
        audio = getSystemService(AudioManager::class.java)
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener({ change ->
                focus = change == AudioManager.AUDIOFOCUS_GAIN
                if (focus && foreground) startPlayback() else if (foreground) {
                    suspendPlayback()
                    audioText.text = "다른 앱이 오디오를 사용 중입니다"
                }
            }, main).build()
        createScreen()
        midi = MidiConnection(this, { label, connected ->
            connectionText.text = if (connected) "●  $label" else label
            connectionText.setTextColor(if (connected) green else muted)
        }, { s, a, b ->
            NativeEngine.midi(s, a, b)
            when (s and 0xf0) {
                0x90 -> if (b > 0) { count.incrementAndGet(); lastNote.set(a); velocity.set(b) }
                    else lastNote.compareAndSet(a, -1)
                0x80 -> lastNote.compareAndSet(a, -1)
                0xb0 -> {
                    val bit = 1 shl (s and 15)
                    if (a == 64) pedals.updateAndGet { if (b >= 64) it or bit else it and bit.inv() }
                    if (a == 121 || a == 120) pedals.updateAndGet { it and bit.inv() }
                    if (a == 120 || a == 123) lastNote.set(-1)
                }
            }
            if (s == 0xff) { lastNote.set(-1); pedals.set(0) }
        }, { NativeEngine.panic(); lastNote.set(-1); pedals.set(0) })
    }

    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun TextView.update(value: String) { if (text.toString() != value) text = value }
    private fun background(color: Int, radius: Int = 22) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun label(text: String, size: Float = 15f, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun LinearLayout.space(height: Int) { addView(View(this@MainActivity), LinearLayout.LayoutParams(1, dp(height))) }
    private fun LinearLayout.item(view: View, height: Int = -2) {
        addView(view, LinearLayout.LayoutParams(-1, if (height >= 0) dp(height) else height))
    }
    private fun button(text: String, primary: Boolean = false, click: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 14f
        setTextColor(if (primary) Color.WHITE else green)
        backgroundTintList = ColorStateList.valueOf(if (primary) green else Color.rgb(230, 236, 226))
        minimumHeight = dp(48)
        setOnClickListener { click() }
    }
    private fun createScreen() {
        val scroll = ScrollView(this).apply { setBackgroundColor(cream); isFillViewport = true }
        val body = column().apply { setPadding(dp(22), dp(18), dp(22), dp(24)) }
        scroll.addView(body)
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        body.item(label("PIANO SOUNDS", 12f, green, true).apply { letterSpacing = .17f })
        body.space(7)
        body.item(label("오늘도, 피아노", 31f, ink, true))
        body.item(label("건반을 연결하고 연주를 시작하세요.", 14f, muted))
        body.space(22)

        val instrument = column().apply { background = background(green); setPadding(dp(23), dp(21), dp(23), dp(20)) }
        instrument.item(label("01  /  ACOUSTIC", 11f, Color.rgb(192, 215, 194), true).apply { letterSpacing = .14f })
        instrument.space(14)
        instrument.item(label("Grand Piano", 30f, Color.WHITE).apply { typeface = Typeface.create("serif", Typeface.NORMAL) })
        instrument.item(label("따뜻한 울림, 섬세한 터치", 14f, Color.rgb(222, 231, 217)))
        instrument.space(17)
        instrument.item(label("88 KEYS    ·    STEREO    ·    OFFLINE", 10f, Color.rgb(192, 215, 194)).apply { letterSpacing = .08f })
        body.item(instrument)
        body.space(18)

        val connection = column().apply { background = background(Color.WHITE); setPadding(dp(18), dp(15), dp(18), dp(12)) }
        connection.item(label("건반 연결", 12f, muted, true))
        connectionText = label("USB 건반을 연결해 주세요", 15f, muted, true).apply { id = R.id.connection_status; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        connection.space(6); connection.item(connectionText)
        connection.space(5)
        connection.item(button("연결 확인 · 포트 선택") { showPorts() })
        body.item(connection)
        body.space(17)

        val volumeRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        volumeRow.addView(label("피아노 볼륨", 14f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        val value = label("$volume%", 14f, green, true)
        volumeRow.addView(value); body.item(volumeRow)
        body.item(SeekBar(this).apply {
            id = R.id.volume; max = 100; progress = volume; contentDescription = "피아노 볼륨"
            setPadding(dp(4), 0, dp(4), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    volume = progress; value.text = getString(R.string.volume_percent, progress); NativeEngine.volume(progress / 100f)
                    if (fromUser) getPreferences(MODE_PRIVATE).edit().putInt("volume", progress).apply()
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }, 48)
        val indicators = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        pedalText = label("페달  OFF", 12f, muted)
        voicesText = label("울리는 음  0", 12f, muted).apply { gravity = Gravity.END }
        indicators.addView(pedalText, LinearLayout.LayoutParams(0, -2, 1f))
        indicators.addView(voicesText, LinearLayout.LayoutParams(0, -2, 1f))
        body.item(indicators); body.space(16)
        piano = PianoView(this) { note, down ->
            if (ready) NativeEngine.midi(if (down) 0x9f else 0x8f, note, if (down) 90 else 0)
        }.apply { isEnabled = false }
        body.item(piano, 112)
        body.space(8)
        inputText = label("건반을 누르면 입력이 여기에 표시됩니다", 12f, muted).apply { id = R.id.input_status }
        body.item(inputText); body.space(10)
        val actions = LinearLayout(this)
        preview = button("소리 미리 듣기", true) { playPreview() }.apply { id = R.id.preview; isEnabled = false }
        actions.addView(preview, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6) })
        actions.addView(button("전체 음 정지") { metronomeToggle.isChecked = false; panic() }.apply { id = R.id.panic }, LinearLayout.LayoutParams(0, dp(52), 1f))
        body.item(actions); body.space(12)
        val metronomeCard = column().apply { background = background(Color.WHITE); setPadding(dp(18), dp(12), dp(18), dp(12)) }
        metronomeToggle = Switch(this).apply {
            id = R.id.metronome_toggle; text = "메트로놈 · OFF"; textSize = 16f
            setTextColor(ink); minHeight = dp(48); isEnabled = false
            setOnCheckedChangeListener { _, checked ->
                text = if (checked) "메트로놈 · ON" else "메트로놈 · OFF"
                updateMetronome()
            }
        }
        metronomeCard.item(metronomeToggle)
        val tempoText = button("템포  $metronomeBpm BPM · 직접 입력") {}.apply {
            contentDescription = "메트로놈 BPM 직접 입력, 현재 $metronomeBpm BPM"
        }
        metronomeCard.item(tempoText)
        val tempoSlider = SeekBar(this).apply {
            id = R.id.metronome_bpm; max = 200; progress = metronomeBpm - 40
            contentDescription = "메트로놈 템포, 분당 박자 수"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    metronomeBpm = progress + 40
                    tempoText.text = "템포  $metronomeBpm BPM · 직접 입력"
                    tempoText.contentDescription = "메트로놈 BPM 직접 입력, 현재 $metronomeBpm BPM"
                    updateMetronome()
                    getPreferences(MODE_PRIVATE).edit().putInt("metronomeBpm", metronomeBpm).apply()
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        tempoText.setOnClickListener { showTempoInput { tempoSlider.progress = it - 40 } }
        val tempoControls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        tempoControls.addView(button("−") { tempoSlider.progress = (tempoSlider.progress - 1).coerceAtLeast(0) }.apply {
            contentDescription = "템포 1 BPM 줄이기"
        }, LinearLayout.LayoutParams(dp(56), dp(48)))
        tempoControls.addView(tempoSlider, LinearLayout.LayoutParams(0, dp(48), 1f))
        tempoControls.addView(button("+") { tempoSlider.progress = (tempoSlider.progress + 1).coerceAtMost(200) }.apply {
            contentDescription = "템포 1 BPM 늘리기"
        }, LinearLayout.LayoutParams(dp(56), dp(48)))
        metronomeCard.item(tempoControls)
        val clickText = label("클릭 음량  $metronomeVolume%", 14f, muted)
        metronomeCard.item(clickText)
        metronomeCard.item(SeekBar(this).apply {
            id = R.id.metronome_volume; max = 100; progress = metronomeVolume
            contentDescription = "메트로놈 클릭 음량"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    metronomeVolume = progress; clickText.text = "클릭 음량  $progress%"
                    updateMetronome()
                    getPreferences(MODE_PRIVATE).edit().putInt("metronomeVolume", progress).apply()
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }, 48)
        body.item(metronomeCard); body.space(12)
        audioText = label("피아노 음원을 준비하고 있습니다…", 12f, muted).apply { id = R.id.audio_status; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        body.item(audioText)
        retry = button("오디오 다시 시작") { if (ready || starting) suspendPlayback(); requestPlayback() }
        body.item(retry)
        body.space(6)
        body.item(button("연결 도움말 · 음원 정보") { showHelp() })
        setContentView(scroll)
    }

    private fun showTempoInput(applyTempo: (Int) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
            hint = "40~240 BPM"
            contentDescription = "메트로놈 BPM 입력"
            setText(metronomeBpm.toString())
            selectAll()
        }
        val container = column().apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            item(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("템포 직접 입력")
            .setMessage("40~240 BPM 사이의 정수를 입력하세요.")
            .setView(container)
            .setPositiveButton("적용", null)
            .setNegativeButton("취소", null)
            .create()
        fun submit() {
            val bpm = input.text.toString().trim().toIntOrNull()
            if (bpm == null || bpm !in 40..240) {
                input.error = "40~240 사이의 정수를 입력해 주세요."
                return
            }
            applyTempo(bpm)
            dialog.dismiss()
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
            input.requestFocus()
        }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
    }

    private fun showPorts() {
        midi.refresh()
        val ports = midi.available
        if (ports.isEmpty()) {
            AlertDialog.Builder(this).setTitle("건반 연결 확인")
                .setMessage("KeyLab USB-B → 기존 USB 케이블 → USB-C OTG 어댑터 → Galaxy S25 순서로 연결하세요.\n\n건반 화면이 켜져 있는지 확인해 주세요. 전원이 부족하면 규격에 맞는 건반 전원 또는 전원 공급 USB 허브가 필요합니다.\n\n연결되어도 소리가 없다면 다른 MIDI 앱을 닫고 케이블을 다시 연결해 주세요.")
                .setPositiveButton("다시 검색") { _, _ -> midi.refresh() }.setNegativeButton("닫기", null).show()
        } else AlertDialog.Builder(this).setTitle("연주 입력 포트 선택")
            .setItems(ports.map { it.name }.toTypedArray()) { _, i -> midi.connect(ports[i]) }
            .setNegativeButton("닫기", null).show()
    }
    private fun updateMetronome() {
        NativeEngine.metronome(ready && metronomeToggle.isChecked, metronomeBpm, metronomeVolume)
    }
    private fun showHelp() {
        val notice = assets.open("NOTICE.txt").bufferedReader().use { it.readText() }
        AlertDialog.Builder(this).setTitle("연주 안내")
            .setMessage("• KeyLab의 일반 MIDI 포트를 선택하세요. DAW / MIDIIN2 포트는 조작용일 수 있습니다.\n• 소리가 없다면 휴대폰 미디어 볼륨도 확인하세요.\n• 먼저 휴대폰 스피커나 유선 오디오로 연주해 보세요. 블루투스 오디오는 지연이 커질 수 있습니다.\n• 다른 앱으로 이동하거나 전화가 오면 연주가 정지합니다.\n• 서스테인은 켜짐/꺼짐 방식입니다.\n\n음원 및 라이선스\n$notice")
            .setPositiveButton("닫기", null)
            .setNeutralButton("라이선스 전문") { _, _ ->
                val licenses = listOf("SALAMANDER_LICENSE.txt", "OBOE_LICENSE.txt").joinToString("\n\n") { file ->
                    assets.open(file).bufferedReader().use { it.readText() }
                }
                AlertDialog.Builder(this).setTitle("오픈소스 라이선스").setMessage(licenses).setPositiveButton("닫기", null).show()
            }.show()
    }

    override fun onStart() {
        super.onStart(); foreground = true
        audio.registerAudioDeviceCallback(routeCallback, main)
        midi.start()
        requestPlayback(); main.post(tick)
    }
    private fun requestPlayback() {
        if (!foreground) return
        focus = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (focus) startPlayback() else audioText.text = "오디오를 사용할 수 없습니다. 다시 시작해 주세요."
    }
    private fun startPlayback() {
        if (!foreground || !focus || starting || ready) return
        starting = true
        val token = ++epoch
        audioText.text = "피아노 음원을 준비하고 있습니다…"
        val appAssets = applicationContext.assets
        audioWorker.execute {
            val result = runCatching {
                if (token != epoch) false else {
                    val loaded = NativeEngine.load(appAssets)
                    if (loaded && token == epoch) { NativeEngine.volume(volume / 100f); NativeEngine.start() } else false
                }
            }.getOrDefault(false)
            main.post {
                if (token != epoch || !foreground) return@post
                starting = false; ready = result
                piano.isEnabled = result; preview.isEnabled = result
                metronomeToggle.isEnabled = result
                updateMetronome()
                if (result) audioText.text = "연주 준비 완료"
                else audioText.text = "오디오를 시작하지 못했습니다. 다시 시작해 주세요."
            }
        }
    }
    private fun suspendPlayback() {
        epoch++; starting = false; ready = false
        metronomeToggle.isChecked = false; metronomeToggle.isEnabled = false
        updateMetronome()
        panic(); piano.isEnabled = false; preview.isEnabled = false
        voicesText.text = getString(R.string.active_voices, 0)
        audioWorker.execute { NativeEngine.stop() }
    }
    private fun restartPlayback() {
        if (!foreground || !focus) return
        suspendPlayback(); startPlayback()
    }
    private val routeChanged = Runnable { if (ready) restartPlayback() }
    private val routeCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { scheduleRouteChange() }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { scheduleRouteChange() }
    }
    private fun scheduleRouteChange() {
        if (foreground && ready) { main.removeCallbacks(routeChanged); main.postDelayed(routeChanged, 300) }
    }
    private fun panic() {
        previewEpoch++; piano.releaseAll(); NativeEngine.panic(); lastNote.set(-1); pedals.set(0)
    }
    private fun playPreview() {
        if (!ready) return
        panic()
        val token = previewEpoch
        listOf(60, 64, 67, 72).forEachIndexed { index, note ->
            main.postDelayed({ if (ready && token == previewEpoch) NativeEngine.midi(0x9f, note, 86) }, index * 180L + 30)
            main.postDelayed({ if (ready && token == previewEpoch) NativeEngine.midi(0x8f, note, 0) }, index * 180L + 650)
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!foreground) return
            val stats = NativeEngine.stats()
            if (ready && (stats[7] != 0 || stats[0] == 0)) restartPlayback()
            else if (ready) {
                audioText.update(getString(R.string.audio_ready, stats[1] / 1000f) + if (stats[5] > 0) getString(R.string.audio_xruns, stats[5]) else "")
                voicesText.update(getString(R.string.active_voices, stats[4]))
            }
            pedalText.update(if (pedals.get() != 0) "페달  ON" else "페달  OFF")
            val note = lastNote.get()
            piano.highlight(note)
            inputText.update(if (count.get() == 0L) "건반을 누르면 입력이 여기에 표시됩니다"
                else "입력 ${count.get()}회" + if (note >= 0) " · ${noteName(note)} · 세기 ${velocity.get()}" else " · 다음 음을 기다립니다")
            main.postDelayed(this, 100)
        }
    }
    private fun noteName(note: Int) = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[note % 12] + (note / 12 - 1)
    override fun onStop() {
        foreground = false
        main.removeCallbacks(tick); main.removeCallbacks(routeChanged)
        audio.unregisterAudioDeviceCallback(routeCallback)
        midi.stop(); suspendPlayback(); connectionText.text = "연주 일시 정지"
        audio.abandonAudioFocusRequest(focusRequest); focus = false
        super.onStop()
    }
}
