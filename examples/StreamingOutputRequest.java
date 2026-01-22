// DashScope SDK 版本需要不低于 2.19.0
import com.alibaba.dashscope.aigc.multimodalconversation.AudioParameters;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.exception.UploadFileException;
import com.alibaba.dashscope.utils.JsonUtils;
import com.alibaba.dashscope.utils.Constants;
import io.reactivex.Flowable;

public class Main {
    private static final String MODEL = "qwen3-tts-flash";
    public static void streamCall() throws ApiException, NoApiKeyException, UploadFileException {
        MultiModalConversation conv = new MultiModalConversation();
        MultiModalConversationParam param = MultiModalConversationParam.builder()
                .model(MODEL)
                // 新加坡和北京地域的API Key不同。获取API Key：https://help.aliyun.com/zh/model-studio/get-api-key
                // 若没有配置环境变量，请用阿里云百炼API Key将下行替换为：apiKey("sk-xxx")
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .text("Today is a wonderful day to build something people love!")
                .voice(AudioParameters.Voice.CHERRY)
                .languageType("English")
                .build();
        Flowable<MultiModalConversationResult> result = conv.streamCall(param);
        result.blockingForEach(r -> {System.out.println(JsonUtils.toJson(r));
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