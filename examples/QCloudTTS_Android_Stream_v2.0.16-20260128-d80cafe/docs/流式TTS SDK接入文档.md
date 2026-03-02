[toc]

### 0. 接入准备

#### 0.1  SDK 获取

流式TTS Android SDK 以及 Demo 的下载地址：[接入 SDK 下载]()。

#### 0.2 接入须知

- 该SDK需要手机能够连接网络，且Android设备 API Level为 **16即以上版本**。
- 运行 Demo 必须设置
  AppID、SecretID、SecretKey，可在 [API 密钥管理](https://console.cloud.tencent.com/cam/capi) 中获取。

### 1. SDK集成说明

#### 1.1 添加SDK

将下载的SDK文件复制到项目的libs文件夹中，并在项目的build.gradle文件中添加以下代码

```groovy
dependencies {
    implementation fileTree(include: ['*.jar', '*.aar'], dir: 'libs')
    // 流式TTS SDK
    implementation files("libs/stream_tts-release-VERSION.aar")
    // 流式TTS SDK内部依赖的okhttp库（必选）
    implementation 'com.squareup.okhttp3:okhttp:4.9.3'
    // 流式TTS SDK内部依赖的gson库（必选）
    implementation 'com.google.code.gson:gson:2.8.9'
}
```

#### 1.2 添加权限

在项目的AndroidManifest.xml文件中添加SDK需要的权限，例如：

```xml

<uses-permission android:name="android.permission.INTERNET" />
```

### 2. SDK接口使用说明

本章节主要介绍SDK接口调用详细步骤以及接口调用时机（具体使用方法可参考SDK交付Demo内的示例代码)

#### 2.1 构造配置项

``` java
FlowingSpeechSynthesizerRequest request = new FlowingSpeechSynthesizerRequest();
/**************
 * 配置项含义也可参考官网文档: https://cloud.tencent.com/document/product/1073/108595
 **************/
request.setVolume(volume); // 音量大小，范围[-10，10]，对应音量大小。默认为0，代表正常音量，值越大音量越高。
request.setSpeed(speed); // 语速，范围：[-2，6]，分别对应不同语速：-2: 代表0.6倍; -1: 代表0.8倍; 0: 代表1.0倍（默认）; 1: 代表1.2倍; 2: 代表1.5倍; 6: 代表2.5倍
request.setCodec("pcm"); // 返回音频格式：pcm: 返回二进制pcm音频（默认）; mp3: 返回二进制mp3音频
request.setSampleRate(SAMPLE_RATE); // 音频采样率：24000: 24k(部分音色支持); 16000: 16k(默认); 8000: 8k
request.setVoiceType(voiceType); // 音色ID
request.setEnableSubtitle(true); // 是否开启时间戳功能，默认为false。
request.setEmotionCategory("neutral"); // 控制合成音频的情感，仅支持多情感音色使用
request.setEmotionIntensity(100); // 控制合成音频情感程度，取值范围为 [50,200]，默认为 100; 只有 EmotionCategory 不为空时生效。
request.setSessionId(UUID.randomUUID().toString()); // sessionId，需要保持全局唯一（推荐使用 uuid），遇到问题需要提供该值方便服务端排查
request.set(String_key, Object_value); // 设置自定义扩展参数(可选，非必须设置)
/**************
 * 配置项含义也可参考官网文档: https://cloud.tencent.com/document/product/1073/108595
 **************/
```

#### 2.2 账号信息配置

``` java
// 账号信息获取可参考 0.2接入须知
Credential credential = new Credential(appId, secretId, secretKey, token);
```

#### 2.3 构造合成器

```java
// 网络连接的代理，全局唯一即可
private static final SpeechClient proxy = new SpeechClient();
...
FlowingSpeechSynthesizerListener listener = new FlowingSpeechSynthesizerListener() {
    @Override
    public void onSynthesisStart(SpeechSynthesizerResponse response) {//合成开始}
        @Override
        public void onSynthesisEnd (SpeechSynthesizerResponse response){//合成结束}

            @Override
            public void onAudioResult (ByteBuffer buffer){//合成的音频数据}

                @Override
                public void onTextResult (SpeechSynthesizerResponse response){//合成文本的信息}

                    @Override
                    public void onSynthesisCancel () {//合成取消}

                        @Override
                        public void onSynthesisFail (SpeechSynthesizerResponse response){//合成失败}
                        }
                        ;
// 构造合成器
                        FlowingSpeechSynthesizer synthesizer = new FlowingSpeechSynthesizer(proxy, credential, request, listener);
```

#### 2.4 启动合成器

```java
synthesizer.start();
```

#### 2.5 发送文本

```java
// 输入待合成的文本text
synthesizer.process(text);
```

#### 2.6 停止合成器

```java
// 会向服务端发送断链请求，服务端完成已有的合成任务后向客户端返回断链确认，客户端再断开websocket链接
synthesizer.stop();
```

#### 2.7 取消合成

```java
// 会直接断开websocket链接
synthesizer.cancel();
```

### 3. 接口详情

#### 3.1 FlowingSpeechSynthesizer

##### 3.1.1 构造接口

```java
public FlowingSpeechSynthesizer(SpeechClient client,
                                Credential credential,
                                FlowingSpeechSynthesizerRequest request,
                                FlowingSpeechSynthesizerListener listener) throws SynthesizerException
```

功能：流式TTS SDK的核心类的构造接口，注意异常捕获。

参数:

| 参数类型                             | 参数名称       | 参数含义           |
|----------------------------------|------------|----------------|
| SpeechClient                     | client     | 网络连接的代理，全局唯一即可 |
| Credential                       | credential | 鉴权相关信息的实体类     |
| FlowingSpeechSynthesizerRequest  | request    | 语音合成的配置项       |
| FlowingSpeechSynthesizerListener | listener   | 语音合成的关键事件回调    |

##### 3.1.2 启动合成器接口

```java
public void start() throws Exception
```

功能：启动合成器，注意异常捕获。

##### 3.1.3 发送文本接口

```java
public void process(String text) throws Exception
```

功能：发送待合成文本，注意异常捕获。

参数：

| 参数类型   | 参数名称 | 参数含义  |
|--------|------|-------|
| String | text | 待合成文本 |

##### 3.1.4 结束合成接口

```java
/**
 * 结束合成:发送结束合成通知,接收服务端确认
 */
public void stop() throws Exception

/**
 * 结束合成:发送结束合成通知,接收服务端确认, 超时未返回则抛出异常
 */
public void stop(long milliSeconds) throws Exception
```

##### 3.1.5 取消合成接口

```java
public void cancel()
```

功能：取消合成，直接关闭websocket链接，不向服务端发送结束通知。

##### 3.1.6 SDK版本号

```java
public static String version()
```

#### 3.2 FlowingSpeechSynthesizerRequest

SDK配置类，配置项含义也可参考官网文档: https://cloud.tencent.com/document/product/1073/108595

| 类型      | 名称               | 含义                                                                                                                                                                                                             | 默认值   |
|---------|------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------|
| Float   | volume           | 音量大小，范围[-10，10]，对应音量大小。默认为0，代表正常音量，值越大音量越高。                                                                                                                                                                    | 0     |
| Float   | speed            | 语速，范围：[-2，6]，分别对应不同语速：-2: 代表0.6倍; -1: 代表0.8倍; 0: 代表1.0倍（默认）; 1: 代表1.2倍; 2: 代表1.5倍; 6: 代表2.5倍                                                                                                                   | 0     |
| String  | codec            | 返回音频格式：pcm: 返回二进制pcm音频（默认）; mp3: 返回二进制mp3音频                                                                                                                                                                    | pcm   |
| Integer | sampleRate       | 音频采样率：24000: 24k(部分音色支持); 16000: 16k(默认); 8000: 8k                                                                                                                                                             | 16000 |
| Integer | voiceType        | 音色ID，取值可参考官网文档https://cloud.tencent.com/document/product/1073/92668#55924b56-1a73-4663-a7a1-a8dd82d6e823                                                                                                       | 0     |
| Boolean | enableSubtitle   | 是否开启时间戳功能，默认为false                                                                                                                                                                                             | false |
| String  | emotionCategory  | 控制合成音频的情感，仅支持多情感音色使用。取值: neutral(中性)、sad(悲伤)、happy(高兴)、angry(生气)、fear(恐惧)、news(新闻)、story(故事)、radio(广播)、poetry(诗歌)、call(客服)、撒娇(sajiao)、厌恶(disgusted)、震惊(amaze)、平静(peaceful)、兴奋(exciting)、傲娇(aojiao)、解说(jieshuo) | ""    |
| Integer | emotionIntensity | 控制合成音频情感程度，取值范围为 [50,200]，默认为 100；只有 EmotionCategory 不为空时生效。                                                                                                                                                   | 100   |
| String  | sessionId        | 需要保持全局唯一（推荐使用 uuid），遇到问题需要提供该值方便服务端排查                                                                                                                                                                          | ""    |

#### 3.3 Credential

密钥信息实体类，可在 [API 密钥管理](https://console.cloud.tencent.com/cam/capi) 中获取。

| 类型     | 名称        | 含义               | 默认值 |
|--------|-----------|------------------|-----|
| String | appid     | appid            | ""  |
| String | secretId  | secretId,在控制台申请  | ""  |
| String | secretKey | secretKey,在控制台申请 | ""  |
| String | token     | 用于临时授权场景         | ""  |

#### 3.4 FlowingSpeechSynthesizerListener

合成监听类

```java
public abstract class FlowingSpeechSynthesizerListener {
    //合成开始
    public abstract void onSynthesisStart(SpeechSynthesizerResponse response);

    //合成结束
    public abstract void onSynthesisEnd(SpeechSynthesizerResponse response);

    //合成的音频数据
    public abstract void onAudioResult(ByteBuffer data);

    //合成文本的信息
    public abstract void onTextResult(SpeechSynthesizerResponse response);

    //合成取消
    public abstract void onSynthesisCancel();

    //合成失败
    public abstract void onSynthesisFail(SpeechSynthesizerResponse response);
}
```

#### 3.5 SpeechSynthesizerResponse

合成Response

| 类型                                                      | 名称        | 含义                                                  |
|---------------------------------------------------------|-----------|-----------------------------------------------------|
| String                                                  | sessionId | 由客户端在握手阶段生成并赋值在调用参数中                                |
| Integer                                                 | code      | 状态码，0代表正常，非0值表示发生错误                                 |
| Integer                                                 | end       | 该字段返回1时表示文本全部合成结束，客户端收到后需主动关闭 websocket 连接          |
| String                                                  | message   | 错误说明，发生错误时显示这个错误发生的具体原因，随着业务发展或体验优化，此文本可能会经常保持变更或更新 |
| String                                                  | requestId | 音频流唯一 id，由服务端在握手阶段自动生成                              |
| String                                                  | messageId | 本 message 唯一 id                                     |
| [SpeechSynthesizerResult](#3.6 SpeechSynthesizerResult) | result    | 语音合成文本结果                                            |

#### 3.6 SpeechSynthesizerResult

合成结果

| 类型                                                            | 名称        | 含义    |
|---------------------------------------------------------------|-----------|-------|
| [SpeechSynthesizerSubtitle](#3.7 SpeechSynthesizerSubtitle)[] | subtitles | 词列表信息 |

#### 3.7 SpeechSynthesizerSubtitle

合成词信息

| 类型      | 名称         | 含义             |
|---------|------------|----------------|
| String  | text       | 文本信息           |
| Integer | beginTime  | ⽂本对应tts语⾳开始时间戳 |
| Integer | endTime    | ⽂本对应tts语⾳结束时间戳 |
| Integer | beginIndex | 该字在整句中的开始位置    |
| Integer | endIndex   | 该字在整句中的结束位置    |
| String  | phoneme    | 该字的音素          |

### 4. 错误码

| 错误码                             | 错误信息                      |
|---------------------------------|---------------------------|
| CLIENT_CANNOT_BE_NULL(-400)     | client cannot be null     |
| CREDENTIAL_CANNOT_BE_NULL(-401) | credential cannot be null |
| REQUEST_CANNOT_BE_NULL(-402)    | request cannot be null    |
| LISTENER_CANNOT_BE_NULL(-403)   | listener cannot be null   |
| APPID_IS_EMPTY(-404)            | appId cannot be empty     |
| SECRETID_IS_EMPTY(-405)         | secretId cannot be empty  |
| SECRETKEY_IS_EMPTY(-406)        | secretKey cannot be empty |
| START_SYNTHESIZER_FAIL(-407)    | fail to start synthesizer |
| SEND_TEXT_FAIL(-408)            | fail to send text         |
| CONNECT_SERVER_FAIL(-409)       | fail to connect server    |
| INCORRECT_STATE(-410)           | error msg视情况而定            |

### 5. 代码混淆规则配置

```
-keep class com.tencent.cloud.stream.tts.FlowingSpeechSynthesizer { *; }
-keep class com.tencent.cloud.stream.tts.FlowingSpeechSynthesizerRequest { *; }
-keep class com.tencent.cloud.stream.tts.FlowingSpeechSynthesizerListener { *; }
-keep class com.tencent.cloud.stream.tts.SpeechSynthesizer** { *; }
-keep class com.tencent.cloud.stream.tts.FlowingSpeechSynthesizerResponse { *; }
-keep class com.tencent.cloud.stream.tts.core.ws.CommonRequest { *; }
-keepclassmembers class * extends com.tencent.cloud.stream.tts.core.ws.CommonRequest { *; }
```