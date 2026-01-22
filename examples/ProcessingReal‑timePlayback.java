// DashScope SDK 版本不低于2.21.9
// 2.20.7 及以上版本支持指定 Dylan、Jada与 Sunny 三种音色
import com.alibaba.dashscope.aigc.multimodalconversation.AudioParameters;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.exception.UploadFileException;
import com.alibaba.dashscope.protocol.Protocol;
import com.alibaba.dashscope.utils.Constants;
import io.reactivex.Flowable;
import javax.sound.sampled.*;
import java.util.Base64;

public class Main {
    private static final String MODEL = "qwen3-tts-flash";
    public static void streamCall() throws ApiException, NoApiKeyException, UploadFileException {
        MultiModalConversation conv = new MultiModalConversation();
        MultiModalConversationParam param = MultiModalConversationParam.builder()
                // 新加坡和北京地域的API Key不同。获取API Key：https://help.aliyun.com/zh/model-studio/get-api-key
                // 若没有配置环境变量，请用百炼API Key将下行替换为：.apiKey("sk-xxx")
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .model(MODEL)
                .text("Today is a wonderful day to build something people love!")
                .voice(AudioParameters.Voice.CHERRY)
                .languageType("English") // 建议与文本语种一致，以获得正确的发音和自然的语调。
                .build();
        Flowable<MultiModalConversationResult> result = conv.streamCall(param);
        result.blockingForEach(r -> {
            try {
                // 1. 获取Base64编码的音频数据
                String base64Data = r.getOutput().getAudio().getData();
                byte[] audioBytes = Base64.getDecoder().decode(base64Data);

                // 2. 配置音频格式（根据API返回的音频格式调整）
                AudioFormat format = new AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED,
                        24000, // 采样率（需与API返回格式一致）
                        16,    // 采样位数
                        1,     // 声道数
                        2,     // 帧大小（位数/字节数）
                        24000, // 数据传输率（需与采样率一致）
                        false  // 是否压缩
                );

                // 3. 实时播放音频数据
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
                try (SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info)) {
                    if (line != null) {
                        line.open(format);
                        line.start();
                        line.write(audioBytes, 0, audioBytes.length);
                        line.drain();
                    }
                }
            } catch (LineUnavailableException e) {
                e.printStackTrace();
            }
        });
    }
    public static void main(String[] args) {
        // 以下为北京地域url，若使用新加坡地域的模型，需将url替换为：https://dashscope-intl.aliyuncs.com/api/v1
        Constants.baseHttpApiUrl = "https://dashscope.aliyuncs.com/api/v1";
        try {
            streamCall();
        } catch (ApiException | NoApiKeyException | UploadFileException e) {
            System.out.println(e.getMessage());
        }
        System.exit(0);
    }
}