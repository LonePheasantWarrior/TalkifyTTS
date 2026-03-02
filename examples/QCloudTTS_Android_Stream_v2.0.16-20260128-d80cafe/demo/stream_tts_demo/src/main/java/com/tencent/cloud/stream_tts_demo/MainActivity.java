package com.tencent.cloud.stream_tts_demo;


import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.gson.Gson;
import com.tencent.cloud.stream.tts.FlowingSpeechSynthesizer;
import com.tencent.cloud.stream.tts.FlowingSpeechSynthesizerListener;
import com.tencent.cloud.stream.tts.FlowingSpeechSynthesizerRequest;
import com.tencent.cloud.stream.tts.SpeechSynthesizerResponse;
import com.tencent.cloud.stream.tts.TtsConstant;
import com.tencent.cloud.stream.tts.core.exception.SynthesizerException;
import com.tencent.cloud.stream.tts.core.utils.AAILogger;
import com.tencent.cloud.stream.tts.core.utils.ByteUtils;
import com.tencent.cloud.stream.tts.core.ws.Credential;
import com.tencent.cloud.stream.tts.core.utils.Ttsutils;
import com.tencent.cloud.stream.tts.core.ws.SpeechClient;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;


public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    public static final String TAG = MainActivity.class.getSimpleName();
    private static final SpeechClient proxy = new SpeechClient();
    private static final int SAMPLE_RATE = 16000;
    private String appId = "";
    private String secretId = "";
    private String secretKey = "";
    private String token = "";
    private float volume = 0;
    private float speed = 0;
    private int voiceType = 1001;
    // 使用Map优化语速映射逻辑
    private static final Map<Integer, Float> SPEED_MAP = new HashMap<>();
    private static final Map<Integer, String> SPEED_TEXT_MAP = new HashMap<>();

    static {
        SPEED_MAP.put(0, -2f);  // 0.6倍速
        SPEED_MAP.put(1, -1f);  // 0.8倍速
        SPEED_MAP.put(2, 0f);   // 1.0倍速
        SPEED_MAP.put(3, 1f);   // 1.2倍速
        SPEED_MAP.put(4, 2f);   // 1.5倍速
        SPEED_MAP.put(5, 6f);   // 2.5倍速

        SPEED_TEXT_MAP.put(0, "语速(0.6倍): ");
        SPEED_TEXT_MAP.put(1, "语速(0.8倍): ");
        SPEED_TEXT_MAP.put(2, "语速(1.0倍): ");
        SPEED_TEXT_MAP.put(3, "语速(1.2倍): ");
        SPEED_TEXT_MAP.put(4, "语速(1.5倍): ");
        SPEED_TEXT_MAP.put(5, "语速(2.5倍): ");
    }

    // 音色选项数据
    private static final int[] VOICE_TYPES = {1001, 1002, 1003, 1004, 1005, 1007, 1008, 1009, 1010, 1017, 1018};
    private static final String[] VOICE_NAMES = {
            "智瑜", "智聆", "智美", "智云", "智莉", "智娜", "智琪", "智芸", "智华", "智蓉", "智靖"
    };
    private TextView volumeTitleTv;
    private TextView speedTitleTv;
    private TextView msgTv;
    private ScrollView msgScrollView;
    private AudioTrack audioTrack;
    private FlowingSpeechSynthesizer synthesizer = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ((TextView) findViewById(R.id.version)).setText(FlowingSpeechSynthesizer.version());
        initView();

        int bufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM);
    }

    private void initView() {
        Intent intent = getIntent();
        // 添加空值检查
        appId = intent.getStringExtra("appId") != null ? intent.getStringExtra("appId") : "";
        secretId = intent.getStringExtra("secretId") != null ? intent.getStringExtra("secretId") : "";
        secretKey = intent.getStringExtra("secretKey") != null ? intent.getStringExtra("secretKey") : "";
        token = intent.getStringExtra("token") != null ? intent.getStringExtra("token") : "";

        findViewById(R.id.start_tts).setOnClickListener(this);
        findViewById(R.id.stop_tts).setOnClickListener(this);
        findViewById(R.id.cancel_tts).setOnClickListener(this);

        msgScrollView = findViewById(R.id.msg_scrollview);
        msgTv = findViewById(R.id.msg);

        // 音量设置相关
        volumeTitleTv = findViewById(R.id.volume_title);
        SeekBar volumeSeekBar = findViewById(R.id.volume_seekbar);
        volumeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // 获取音量大小的值
                volume = progress - 10;
                // 在这里可以使用音量大小的值进行相应的操作
                volumeTitleTv.setText("音量大小(" + volume + "): ");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // 开始拖动 SeekBar 时的操作
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // 停止拖动 SeekBar 时的操作
            }
        });

        // 语速设置相关 - 使用Map优化逻辑
        speedTitleTv = findViewById(R.id.speed_title);
        SeekBar speedSeekBar = findViewById(R.id.speed_seekbar);
        speedSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // 使用Map获取对应的速度值和文本
                Float speedValue = SPEED_MAP.get(progress);
                String speedText = SPEED_TEXT_MAP.get(progress);

                if (speedValue != null && speedText != null) {
                    speed = speedValue;
                    speedTitleTv.setText(speedText);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // 可以在这里添加开始拖动的逻辑
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // 可以在这里添加停止拖动的逻辑
            }
        });

        Spinner voiceTypeSpinner = findViewById(R.id.voice_type_spinner);

        // 动态生成音色选项列表
        List<String> options = new ArrayList<>();
        for (int i = 0; i < VOICE_NAMES.length; i++) {
            options.add(VOICE_NAMES[i] + " - " + VOICE_TYPES[i]);
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        voiceTypeSpinner.setAdapter(adapter);
        voiceTypeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                voiceType = VOICE_TYPES[position];
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // 处理未选择任何选项的情况
                AAILogger.d(TAG, "No voice type selected");
            }
        });
    }


    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.start_tts) {
            process(appId, secretId, secretKey, token);
        } else if (v.getId() == R.id.stop_tts) {
            if (synthesizer != null) {
                try {
                    synthesizer.stop();
                    audioTrack.stop();
                } catch (Exception e) {
                    String msg = "synthesizer stop exception: " + e;
                    AAILogger.e(TAG, msg);
                    updateMsg(msg);
                }
            }
        } else if (v.getId() == R.id.cancel_tts) {
            if (synthesizer != null) {
                synthesizer.cancel();
            }
        }
    }

    public void process(String appId, String secretId, String secretKey, String token) {
        resetMsg();
        audioTrack.play();
        Credential credential = new Credential(appId, secretId, secretKey, token);
        FlowingSpeechSynthesizerRequest request = new FlowingSpeechSynthesizerRequest();
        /************** 配置项含义也可参考官网文档: https://cloud.tencent.com/document/product/1073/108595 **************/
        request.setVolume(volume); // 音量大小，范围[-10，10]，对应音量大小。默认为0，代表正常音量，值越大音量越高。
        request.setSpeed(speed); // 语速，范围：[-2，6]，分别对应不同语速：-2: 代表0.6倍; -1: 代表0.8倍; 0: 代表1.0倍（默认）; 1: 代表1.2倍; 2: 代表1.5倍; 6: 代表2.5倍
        request.setCodec("pcm"); // 返回音频格式：pcm: 返回二进制pcm音频（默认）; mp3: 返回二进制mp3音频
        request.setSampleRate(SAMPLE_RATE); // 音频采样率：24000: 24k(部分音色支持); 16000: 16k(默认); 8000: 8k
        request.setVoiceType(voiceType); // 音色ID
        request.setEnableSubtitle(true); // 是否开启时间戳功能，默认为false。
        request.setEmotionCategory("neutral");// 控制合成音频的情感，仅支持多情感音色使用
        request.setEmotionIntensity(100); // 控制合成音频情感程度，取值范围为 [50,200]，默认为 100; 只有 EmotionCategory 不为空时生效。
        request.setSessionId(UUID.randomUUID().toString());//sessionId，需要保持全局唯一（推荐使用 uuid），遇到问题需要提供该值方便服务端排查
//        request.setExtendParam(String_key, Object_value);//设置自定义参数


        /************** 配置项含义也可参考官网文档: https://cloud.tencent.com/document/product/1073/108595 **************/

        AAILogger.d(TAG, "session_id: " + request.getSessionId());
        FlowingSpeechSynthesizerListener listener = new FlowingSpeechSynthesizerListener() {//tips：回调方法中应该避免进行耗时操作，如果有耗时操作建议进行异步处理否则会影响websocket请求处理
            byte[] audio = new byte[0];

            @Override
            public void onSynthesisStart(SpeechSynthesizerResponse response) {
                String msg = String.format("%s session_id:%s, %s", "onSynthesisStart", response.getSessionId(), new Gson().toJson(response));
                AAILogger.d(TAG, msg);
                updateMsg(msg);
            }

            @Override
            public void onSynthesisEnd(SpeechSynthesizerResponse response) {
                String msg = String.format("%s session_id:%s, %s", "onSynthesisEnd", response.getSessionId(), new Gson().toJson(response));
                AAILogger.d(TAG, msg);
                updateMsg(msg);

                if ("pcm".equals(request.getCodec())) {
                    // 保存文件
                    String audioFilePath = Ttsutils.responsePcm2Wav(MainActivity.this, 16000, audio, request.getSessionId());
                    AAILogger.d(TAG, "audio file path: " + audioFilePath);
                    // 停止播放
                    audioTrack.stop();
                }
                if ("mp3".equals(request.getCodec())) {
                    // TODO 自行播放 或 保存文件
                }
            }

            @Override
            public void onAudioResult(ByteBuffer buffer) {
                byte[] data = new byte[buffer.remaining()];
                buffer.get(data);
                // 播放pcm
                audioTrack.write(data, 0, data.length);
                audio = ByteUtils.concat(audio, data);
            }

            @Override
            public void onTextResult(SpeechSynthesizerResponse response) {
                AAILogger.d(TAG, String.format("%s session_id:%s, %s", "onTextResult", response.getSessionId(), new Gson().toJson(response)));
            }

            @Override
            public void onSynthesisCancel() {
                updateMsg("onSynthesisCancel");
            }

            /**
             * 错误回调 当发生错误时回调该方法
             * @param response 响应
             */
            @Override
            public void onSynthesisFail(SpeechSynthesizerResponse response) {
                String msg = String.format("%s session_id:%s, %s", "onSynthesisFail", response.getSessionId(), new Gson().toJson(response));
                AAILogger.d(TAG, msg);
                updateMsg(msg);
            }
        };

        String[] texts = {"五位壮士一面向顶峰攀登，一面依托大树和",
                "岩石向敌人射击。山路上又留下了许多具敌",
                "人的尸体。到了狼牙山峰顶，五壮士居高临",
                "下，继续向紧跟在身后的敌人射击。不少敌人",
                "坠落山涧，粉身碎骨。班长马宝玉负伤了，子",
                "弹都打完了，只有胡福才手里还剩下一颗手榴",
                "弹，他刚要拧开盖子，马宝玉抢前一步，夺过",
                "手榴弹插在腰间，他猛地举起一块磨盘大的石",
                "头，大声喊道：“同志们！用石头砸！”顿时，",
                "石头像雹子一样，带着五位壮士的决心，带着",
                "中国人民的仇恨，向敌人头上砸去。山坡上传",
                "来一阵叽里呱啦的叫声，敌人纷纷滚落深谷。"
        };
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    synthesizer = new FlowingSpeechSynthesizer(proxy, credential, request, listener);
                    //synthesizer不可重复使用，每次合成需要重新生成新对象
                    long currentTimeMillis = System.currentTimeMillis();
                    synthesizer.start();
                    AAILogger.d(TAG, "synthesizer start latency : " + (System.currentTimeMillis() - currentTimeMillis) + " ms");
                    for (String text : texts) {
                        AAILogger.d(TAG, "synthesizer process at : " + System.currentTimeMillis() + " ms");
                        long time = System.currentTimeMillis();
                        synthesizer.process(text);
                        AAILogger.d(TAG, "synthesizer process latency : " + (System.currentTimeMillis() - time) + " ms");
//                        Thread.sleep(500);
                    }
                    currentTimeMillis = System.currentTimeMillis();
                    synthesizer.stop();
                    AAILogger.d(TAG, "synthesizer stop latency : " + (System.currentTimeMillis() - currentTimeMillis) + " ms");
                } catch (Exception e) {
                    String msg = "synthesizer exception: " + e;
                    AAILogger.e(TAG, msg);
                    updateMsg(msg);
                }
            }
        }).start();
    }

    private void updateMsg(String msg) {
        new Thread(() -> runOnUiThread(() -> {
            msgTv.append(msg + "\n");
            msgScrollView.fullScroll(ScrollView.FOCUS_DOWN);
        })).start();
    }

    private void resetMsg() {
        new Thread(() -> runOnUiThread(() -> msgTv.setText(""))).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 释放音频资源
        if (audioTrack != null) {
            audioTrack.release();
            audioTrack = null;
        }
        // 释放合成器资源
        if (synthesizer != null) {
            synthesizer.cancel();
            synthesizer = null;
        }
    }
}



