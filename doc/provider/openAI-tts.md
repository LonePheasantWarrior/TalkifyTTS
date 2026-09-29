# Text to speech

> For the complete documentation index, see [llms.txt](/llms.txt). Markdown versions of documentation pages are available by appending `.md` to the page URL.

The Audio API provides a [`speech`](https://developers.openai.com/api/reference/resources/audio/subresources/speech/methods/create) endpoint based on our [GPT-4o mini TTS (text-to-speech) model](https://developers.openai.com/api/docs/models/gpt-4o-mini-tts). It comes with 11 built-in voices and can be used to:

- Narrate a written blog post
- Produce spoken audio in multiple languages
- Give realtime audio output using streaming

Here's an example of the `alloy` voice:

Our [usage policies](https://openai.com/policies/usage-policies) require you
  to provide a clear disclosure to end users that the TTS voice they are hearing
  is AI-generated and not a human voice.

## Quickstart

The `speech` endpoint takes three key inputs:

1. The [model](https://developers.openai.com/api/reference/resources/audio/subresources/speech/methods/create#audio-createspeech-model) you're using
1. The [text](https://developers.openai.com/api/reference/resources/audio/subresources/speech/methods/create#audio-createspeech-input) to be turned into audio
1. The [voice](https://developers.openai.com/api/reference/resources/audio/subresources/speech/methods/create#audio-createspeech-voice) that will speak the output

Here's a simple request example:

Generate spoken audio from input text

```javascript
import fs from "fs";
import path from "path";
import OpenAI from "openai";

const openai = new OpenAI();
const speechFile = path.resolve("./speech.mp3");

const mp3 = await openai.audio.speech.create({
  model: "gpt-4o-mini-tts",
  voice: "coral",
  input: "Today is a wonderful day to build something people love!",
  instructions: "Speak in a cheerful and positive tone.",
});

const buffer = Buffer.from(await mp3.arrayBuffer());
await fs.promises.writeFile(speechFile, buffer);
```

```python
from pathlib import Path
from openai import OpenAI

client = OpenAI()
speech_file_path = Path(__file__).parent / "speech.mp3"

with client.audio.speech.with_streaming_response.create(
    model="gpt-4o-mini-tts",
    voice="coral",
    input="Today is a wonderful day to build something people love!",
    instructions="Speak in a cheerful and positive tone.",
) as response:
    response.stream_to_file(speech_file_path)
```

```go
package main

import (
	"context"
	"io"
	"os"

	"github.com/openai/openai-go/v3"
)

func main() {
	client := openai.NewClient()
	response, err := client.Audio.Speech.New(context.Background(), openai.AudioSpeechNewParams{
		Model:        openai.SpeechModelGPT4oMiniTTS,
		Voice:        openai.AudioSpeechNewParamsVoiceUnion{OfAudioSpeechNewsVoiceString2: openai.String("coral")},
		Input:        "Today is a wonderful day to build something people love!",
		Instructions: openai.String("Speak in a cheerful and positive tone."),
	})
	if err != nil {
		panic(err)
	}
	defer response.Body.Close()

	file, err := os.Create("speech.mp3")
	if err != nil {
		panic(err)
	}
	if _, err := io.Copy(file, response.Body); err != nil {
		panic(err)
	}
	if err := file.Close(); err != nil {
		panic(err)
	}
}
```

```java
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.HttpResponse;
import com.openai.models.audio.speech.SpeechCreateParams;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

try (HttpResponse audio =
    client
        .audio()
        .speech()
        .create(
            SpeechCreateParams.builder()
                .model("gpt-4o-mini-tts")
                .voice("coral")
                .input("Today is a wonderful day to build something people love!")
                .instructions("Speak in a cheerful and positive tone.")
                .build())) {
  Files.copy(audio.body(), Path.of("speech.mp3"), StandardCopyOption.REPLACE_EXISTING);
}
```

```csharp
using OpenAI.Audio;
#pragma warning disable OPENAI001

string key = Environment.GetEnvironmentVariable("OPENAI_API_KEY")!;
string model = "gpt-4o-mini-tts";
AudioClient client = new(model, key);

BinaryData audio = await client.GenerateSpeechAsync(
    "Today is a wonderful day to build something people love!",
    GeneratedSpeechVoice.Coral,
    new SpeechGenerationOptions
    {
        Instructions = "Speak in a cheerful and positive tone.",
    }
);

await File.WriteAllBytesAsync("speech.mp3", audio.ToArray());
```

```ruby
require "openai"

client = OpenAI::Client.new
audio = client.audio.speech.create(
  model: "gpt-4o-mini-tts",
  voice: "coral",
  input: "Today is a wonderful day to build something people love!",
  instructions: "Speak in a cheerful and positive tone."
)
File.binwrite("speech.mp3", audio.read)
```

```bash
curl https://api.openai.com/v1/audio/speech \
  -H "Authorization: Bearer $OPENAI_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4o-mini-tts",
    "input": "Today is a wonderful day to build something people love!",
    "voice": "coral",
    "instructions": "Speak in a cheerful and positive tone."
  }' \
  --output speech.mp3
```

```bash
openai audio:speech create \
  --model gpt-4o-mini-tts \
  --voice coral \
  --instructions "Speak in a cheerful and positive tone." \
  --input "Today is a wonderful day to build something people love!" \
  --output speech.mp3
```


By default, the endpoint outputs an MP3 of the spoken audio, but you can configure it to output any [supported format](#supported-output-formats).

### Text-to-speech models

For intelligent realtime applications, use the `gpt-4o-mini-tts` model, our newest and most reliable text-to-speech model. You can prompt the model to control aspects of speech, including:

- Accent
- Emotional range
- Intonation
- Impressions
- Speed of speech
- Tone
- Whispering

Our other text-to-speech models are `tts-1` and `tts-1-hd`. The `tts-1` model provides lower latency, but at a lower quality than the `tts-1-hd` model.

### Voice options

The TTS endpoint provides 13 built‑in voices to control how speech is rendered from text. **Hear and play with these voices in [OpenAI.fm](https://openai.fm), our interactive demo for trying the latest text-to-speech model in the OpenAI API**. Voices are currently optimized for English.

- `alloy`
- `ash`
- `ballad`
- `coral`
- `echo`
- `fable`
- `nova`
- `onyx`
- `sage`
- `shimmer`
- `verse`
- `marin`
- `cedar`

For best quality, we recommend using `marin` or `cedar`.

Voice availability depends on the model. The `tts-1` and `tts-1-hd` models support a smaller set: `alloy`, `ash`, `coral`, `echo`, `fable`, `onyx`, `nova`, `sage`, and `shimmer`.

If you're using the [Realtime API](https://developers.openai.com/api/docs/guides/realtime), note that the set of available voices is slightly different—see the [realtime conversations guide](https://developers.openai.com/api/docs/guides/realtime-conversations#voice-options) for current realtime voices.

### Streaming realtime audio

The Speech API provides support for realtime audio streaming using [chunk transfer encoding](https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Transfer-Encoding). This means the audio can be played before the full file is generated and made accessible.

Stream spoken audio from input text directly to your speakers

```javascript
import OpenAI from "openai";
import { playAudio } from "openai/helpers/audio";

const openai = new OpenAI();

const response = await openai.audio.speech.create({
  model: "gpt-4o-mini-tts",
  voice: "coral",
  input: "Today is a wonderful day to build something people love!",
  instructions: "Speak in a cheerful and positive tone.",
  response_format: "wav",
});

await playAudio(response);
```

```python
import asyncio

from openai import AsyncOpenAI
from openai.helpers import LocalAudioPlayer

openai = AsyncOpenAI()


async def main() -> None:
    async with openai.audio.speech.with_streaming_response.create(
        model="gpt-4o-mini-tts",
        voice="coral",
        input="Today is a wonderful day to build something people love!",
        instructions="Speak in a cheerful and positive tone.",
        response_format="pcm",
    ) as response:
        await LocalAudioPlayer().play(response)


if __name__ == "__main__":
    asyncio.run(main())
```

```go
package main

import (
	"context"
	"io"
	"os"

	"github.com/openai/openai-go/v3"
)

func main() {
	client := openai.NewClient()
	response, err := client.Audio.Speech.New(context.Background(), openai.AudioSpeechNewParams{
		Model:          openai.SpeechModelGPT4oMiniTTS,
		Voice:          openai.AudioSpeechNewParamsVoiceUnion{OfAudioSpeechNewsVoiceString2: openai.String("coral")},
		Input:          "Today is a wonderful day to build something people love!",
		Instructions:   openai.String("Speak in a cheerful and positive tone."),
		ResponseFormat: openai.AudioSpeechNewParamsResponseFormatWAV,
	})
	if err != nil {
		panic(err)
	}
	defer response.Body.Close()
	if _, err := io.Copy(os.Stdout, response.Body); err != nil {
		panic(err)
	}
}
```

```java
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.HttpResponse;
import com.openai.models.audio.speech.SpeechCreateParams;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

try (HttpResponse audio =
    client
        .audio()
        .speech()
        .create(
            SpeechCreateParams.builder()
                .model("gpt-4o-mini-tts")
                .voice("coral")
                .input("Today is a wonderful day to build something people love!")
                .instructions("Speak in a cheerful and positive tone.")
                .responseFormat(SpeechCreateParams.ResponseFormat.PCM)
                .streamFormat(SpeechCreateParams.StreamFormat.AUDIO)
                .build())) {
  AudioFormat format = new AudioFormat(24_000, 16, 1, true, false);
  String outputPath = System.getenv("OPENAI_EXAMPLE_AUDIO_OUTPUT_PATH");
  if (outputPath == null || outputPath.isBlank()) {
    try (SourceDataLine speakers = AudioSystem.getSourceDataLine(format)) {
      speakers.open(format);
      speakers.start();
      byte[] chunk = new byte[1024];
      int bytesRead;
      while ((bytesRead = audio.body().read(chunk)) != -1) {
        speakers.write(chunk, 0, bytesRead);
      }
      speakers.drain();
    }
  } else {
    try (OutputStream output = Files.newOutputStream(Path.of(outputPath))) {
      long bytes = audio.body().transferTo(output);
      System.out.println(bytes + " audio bytes");
    }
  }
}
```

```ruby
require "openai"

client = OpenAI::Client.new
audio = client.audio.speech.create(
  model: "gpt-4o-mini-tts",
  voice: "alloy",
  input: "Welcome to the OpenAI API.",
  response_format: :pcm,
  stream_format: :audio
)
while (chunk = audio.read(1_024))
  puts(chunk.bytesize)
end
```

```bash
curl https://api.openai.com/v1/audio/speech \
  -H "Authorization: Bearer $OPENAI_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4o-mini-tts",
    "input": "Today is a wonderful day to build something people love!",
    "voice": "coral",
    "instructions": "Speak in a cheerful and positive tone.",
    "response_format": "wav"
  }' | ffplay -i -
```


For the fastest response times, we recommend using `wav` or `pcm` as the response format.

## Supported output formats

The default response format is `mp3`, but other formats like `opus` and `wav` are available.

- **MP3**: The default response format for general use cases.
- **Opus**: For internet streaming and communication, low latency.
- **AAC**: For digital audio compression, preferred by YouTube, Android, iOS.
- **FLAC**: For lossless audio compression, favored by audio enthusiasts for archiving.
- **WAV**: Uncompressed WAV audio, suitable for low-latency applications to avoid decoding overhead.
- **PCM**: Similar to WAV but contains the raw samples in 24kHz (16-bit signed, low-endian), without the header.

## Supported languages

The TTS model generally follows the Whisper model in terms of language support. Whisper [supports the following languages](https://github.com/openai/whisper#available-models-and-languages) and performs well, despite voices being optimized for English:

Afrikaans, Arabic, Armenian, Azerbaijani, Belarusian, Bosnian, Bulgarian, Catalan, Chinese, Croatian, Czech, Danish, Dutch, English, Estonian, Finnish, French, Galician, German, Greek, Hebrew, Hindi, Hungarian, Icelandic, Indonesian, Italian, Japanese, Kannada, Kazakh, Korean, Latvian, Lithuanian, Macedonian, Malay, Marathi, Maori, Nepali, Norwegian, Persian, Polish, Portuguese, Romanian, Russian, Serbian, Slovak, Slovenian, Spanish, Swahili, Swedish, Tagalog, Tamil, Thai, Turkish, Ukrainian, Urdu, Vietnamese, and Welsh.

You can generate spoken audio in these languages by providing input text in the language of your choice.

## Custom voices

Create an approved custom voice from a speaker's consent recording and matching
audio sample. See [Custom voices](https://developers.openai.com/api/docs/guides/custom-voices) for eligibility,
recording requirements, consent phrases, and API requests.

#### Creating a voice

Follow [Create a custom voice](https://developers.openai.com/api/docs/guides/custom-voices#creating-a-voice).

#### Using a voice during speech generation

Pass the created voice ID when generating speech. See the
[speech generation examples](https://developers.openai.com/api/docs/guides/custom-voices#using-a-voice-during-speech-generation).

## Related guides

[Realtime and audio overview



      Choose the right path for voice agents, translation, transcription, and
    speech generation.](https://developers.openai.com/api/docs/guides/realtime)

[Audio and speech concepts



      Review audio modalities, speech tasks, streaming, and request-based APIs.](https://developers.openai.com/api/docs/guides/audio)