<br />

The Gemini API can transform text input into single-speaker or multi-speaker
audio using Gemini text-to-speech (TTS) generation capabilities.
Text-to-speech generation is
*[controllable](https://ai.google.dev/gemini-api/docs/generate-content/speech-generation#controllable)* ,
meaning you can combine structured turn metadata (`speech_metadata`) and inline
vocal tags to guide the *style* , *accent* , *pace* , and *tone* of the audio.
[Try in Google AI Studio](https://aistudio.google.com/generate-speech)

The TTS capability differs from speech generation provided through the
[Live API](https://ai.google.dev/gemini-api/docs/live), which is designed for interactive,
unstructured audio, and multimodal inputs and outputs. While the Live API excels
in dynamic conversational contexts, TTS through the Gemini API
is tailored for scenarios that require exact text recitation with fine-grained
control over style and sound, such as podcast or audiobook generation.

This guide shows you how to generate single-speaker and multi-speaker audio from
text using [Gemini 3.8 Flash TTS](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash-tts)
(`gemini-3.8-flash-tts`) and
[Gemini 3.8 Flash-Lite TTS](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash-lite-tts)
(`gemini-3.8-flash-lite-tts`).

## Before you begin

Ensure you use a Gemini TTS model listed in the
[Supported models](https://ai.google.dev/gemini-api/docs/generate-content/speech-generation#supported-models)
section. For optimal results, review
[When to use which model](https://ai.google.dev/gemini-api/docs/generate-content/speech-generation#when-to-use-which-model)
to select the best model for your workload.

You may find it useful to
[test the Gemini TTS models in AI Studio](https://aistudio.google.com/generate-speech)
before you start building.

> [!NOTE]
> **Note:** TTS models accept text-only inputs and produce audio-only outputs. For a complete list of restrictions specific to TTS models, review the [Limitations](https://ai.google.dev/gemini-api/docs/generate-content/speech-generation#limitations) section.

## Single-speaker TTS

To convert text to single-speaker audio with Gemini 3.8 TTS models, pass the
verbatim transcript in `parts[].text`, attach turn-level styling in
`parts[].speech_metadata`, and configure your voice in
`speechConfig.voiceConfig`. You can pass a prebuilt voice name, an Extended
Voice Library ID, a custom
[Voice design](https://ai.google.dev/gemini-api/docs/generate-content/voice-design) ID (`voice_...`),
or a [Voice replication](https://ai.google.dev/gemini-api/docs/generate-content/voice-replication) ID
(`voice_...`, or optional stateless `voicekey_...`).

This example saves the output audio from the model in a WAV file:

### Python

    from google import genai
    
    client = genai.Client()
    
    response = client.models.generate_content(
        model="gemini-3.8-flash-tts",
        contents=[{
            "role": "user",
            "parts": [{
                "text": "Have a wonderful day!",
                "speech_metadata": {"style": "cheerful and friendly"},
            }],
        }],
        config={
            "response_modalities": ["AUDIO"],
            "speech_config": {
                "voice_config": {"voice": "Kore"}
            },
        },
    )
    
    data = response.candidates[0].content.parts[0].inline_data.data
    with open("out.wav", "wb") as f:
        f.write(data)

### JavaScript

    import {GoogleGenAI} from '@google/genai';
    import * as fs from 'node:fs';
    
    async function main() {
       const ai = new GoogleGenAI({});
    
       const response = await ai.models.generateContent({
          model: 'gemini-3.8-flash-tts',
          contents: [{
             role: 'user',
             parts: [{
                text: 'Have a wonderful day!',
                speech_metadata: { style: 'cheerful and friendly' },
             }],
          }],
          config: {
             responseModalities: ['AUDIO'],
             speechConfig: {
                voiceConfig: { voice: 'Kore' },
             },
          },
       });
    
       const data = response.candidates?.[0]?.content?.parts?.[0]?.inlineData?.data;
       const audioBuffer = Buffer.from(data, 'base64');
    
       fs.writeFileSync('out.wav', audioBuffer);
    }
    await main();

### REST

    curl "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash-tts:generateContent" \
      -H "x-goog-api-key: $GEMINI_API_KEY" \
      -X POST \
      -H "Content-Type: application/json" \
      -d '{
            "contents": [{
              "role": "user",
              "parts": [{
                "text": "Have a wonderful day!",
                "speech_metadata": {
                  "style": "cheerful and friendly"
                }
              }]
            }],
            "generationConfig": {
              "responseModalities": ["AUDIO"],
              "speechConfig": {
                "voiceConfig": {
                  "voice": "Kore"
                }
              }
            }
        }' | jq -r '.candidates[0].content.parts[0].inlineData.data' | \
              base64 --decode > out.wav

## Multi-speaker TTS

For multi-speaker dialogue, configure two speakers in
`multiSpeakerVoiceConfig.speakerVoiceConfigs` using `prebuiltVoiceConfig` and
pass each dialogue turn as a separate `part` with `speech_metadata` specifying
both `speaker` and optional turn-level `style`:

### Python

    from google import genai
    
    client = genai.Client()
    
    response = client.models.generate_content(
        model="gemini-3.8-flash-tts",
        contents=[{
            "role": "user",
            "parts": [
                {
                    "text": "How's it going today Jane?",
                    "speech_metadata": {
                        "speaker": "Joe",
                        "style": "cheerful and friendly",
                    },
                },
                {
                    "text": "Not too bad, how about you? Ready to test these new voices?",
                    "speech_metadata": {
                        "speaker": "Jane",
                        "style": "calm and relaxed",
                    },
                },
            ],
        }],
        config={
            "response_modalities": ["AUDIO"],
            "speech_config": {
                "multi_speaker_voice_config": {
                    "speaker_voice_configs": [
                        {
                            "speaker": "Joe",
                            "voice_config": {
                                "prebuilt_voice_config": {"voice_name": "Puck"}
                            },
                        },
                        {
                            "speaker": "Jane",
                            "voice_config": {
                                "prebuilt_voice_config": {"voice_name": "Kore"}
                            },
                        },
                    ]
                }
            },
        },
    )
    
    data = response.candidates[0].content.parts[0].inline_data.data
    with open("out.wav", "wb") as f:
        f.write(data)

### JavaScript

    import {GoogleGenAI} from '@google/genai';
    import * as fs from 'node:fs';
    
    async function main() {
       const ai = new GoogleGenAI({});
    
       const response = await ai.models.generateContent({
          model: 'gemini-3.8-flash-tts',
          contents: [{
             role: 'user',
             parts: [
                {
                   text: "How's it going today Jane?",
                   speech_metadata: {
                      speaker: 'Joe',
                      style: 'cheerful and friendly',
                   },
                },
                {
                   text: 'Not too bad, how about you? Ready to test these new voices?',
                   speech_metadata: {
                      speaker: 'Jane',
                      style: 'calm and relaxed',
                   },
                },
             ],
          }],
          config: {
             responseModalities: ['AUDIO'],
             speechConfig: {
                multiSpeakerVoiceConfig: {
                   speakerVoiceConfigs: [
                      {
                         speaker: 'Joe',
                         voiceConfig: {
                            prebuiltVoiceConfig: { voiceName: 'Puck' },
                         },
                      },
                      {
                         speaker: 'Jane',
                         voiceConfig: {
                            prebuiltVoiceConfig: { voiceName: 'Kore' },
                         },
                      },
                   ],
                },
             },
          },
       });
    
       const data = response.candidates?.[0]?.content?.parts?.[0]?.inlineData?.data;
       const audioBuffer = Buffer.from(data, 'base64');
    
       fs.writeFileSync('out.wav', audioBuffer);
    }
    
    await main();

### REST

    curl "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash-tts:generateContent" \
      -H "x-goog-api-key: $GEMINI_API_KEY" \
      -X POST \
      -H "Content-Type: application/json" \
      -d '{
        "contents": [{
          "role": "user",
          "parts": [
            {
              "text": "How'\''s it going today Jane?",
              "speech_metadata": {
                "speaker": "Joe",
                "style": "cheerful and friendly"
              }
            },
            {
              "text": "Not too bad, how about you? Ready to test these new voices?",
              "speech_metadata": {
                "speaker": "Jane",
                "style": "calm and relaxed"
              }
            }
          ]
        }],
        "generationConfig": {
          "responseModalities": ["AUDIO"],
          "speechConfig": {
            "multiSpeakerVoiceConfig": {
              "speakerVoiceConfigs": [
                {
                  "speaker": "Joe",
                  "voiceConfig": {
                    "prebuiltVoiceConfig": { "voiceName": "Puck" }
                  }
                },
                {
                  "speaker": "Jane",
                  "voiceConfig": {
                    "prebuiltVoiceConfig": { "voiceName": "Kore" }
                  }
                }
              ]
            }
          }
        }
      }' | jq -r '.candidates[0].content.parts[0].inlineData.data' | \
          base64 --decode > out.wav

## Control speech style with metadata and tags

Gemini 3.8 TTS treats the `text` field strictly as a verbatim transcript. To
control delivery without having stage directions read aloud, split your
instructions by scope:

- **Sustained turn-level delivery (`speech_metadata.style`):** Put emotions, delivery style, prosody, pacing, and volume that apply across an entire turn in `speech_metadata.style` (for example, `"style": "whispered urgently"`, `"style": "out of breath"`, or `"style": "warm and enthusiastic"`).
- **Point-in-time events (inline tags):** Place momentary non-speech vocal bursts or pauses directly inside the transcript using angle brackets (for example, `"Wait... <short pause> did you hear that? <sigh>"` or `"Excuse me <cough> as I was saying..."`).

See the
[Prompting guide](https://ai.google.dev/gemini-api/docs/generate-content/speech-generation#prompting-guide)
for comprehensive best practices.

## Voice options

Gemini 3.8 TTS supports four ways to select or create voices:

1. **Prebuilt studio voices:** 30 curated voices listed in the following table.
2. **Extended Voice Library:** Hundreds of additional voices across languages, accents, and character archetypes accessible using `client.voices.list()` (`GET /v1beta/voices`).
3. **[Voice design](https://ai.google.dev/gemini-api/docs/generate-content/voice-design):** Generate a custom vocal persona from a natural-language description in [Google AI Studio](https://aistudio.google.com/generate-speech) or using `POST /v1beta/voices` (`type="prompted"`, which returns a persistent `voice_...` ID and a `sample_audio` WAV preview in `CreateVoice` and `GetVoice`).
4. **[Voice replication](https://ai.google.dev/gemini-api/docs/generate-content/voice-replication):** Replicate a speaker's voice from reference and consent audio in [Google AI Studio](https://aistudio.google.com/generate-speech) or using `POST /v1beta/voices` (`type="replicated"`, persistent `store=True` by default or optional stateless `store=False`).

### Custom voice limits and TTL

| Voice type                                                | Storage mode  | Quota / limit                                                | Retention (TTL) |
| --------------------------------------------------------- | ------------- | ------------------------------------------------------------ | --------------- |
| **Stateful voices** (`voice_...`, prompted or replicated) | `store=True`  | **200 voices per project** (shared across prompted and replicated voices) | **1 year**      |
| **Stateless voice keys** (`voicekey_...`, replicated)     | `store=False` | Client-managed                                               | **7 days**      |

### Prebuilt voices

|---|---|---|
| **Zephyr** -- *Bright* | **Puck** -- *Upbeat* | **Charon** -- *Informative* |
| **Kore** -- *Firm* | **Fenrir** -- *Excitable* | **Leda** -- *Youthful* |
| **Orus** -- *Firm* | **Aoede** -- *Breezy* | **Callirrhoe** -- *Easy-going* |
| **Autonoe** -- *Bright* | **Enceladus** -- *Breathy* | **Iapetus** -- *Clear* |
| **Umbriel** -- *Easy-going* | **Algieba** -- *Smooth* | **Despina** -- *Smooth* |
| **Erinome** -- *Clear* | **Algenib** -- *Gravelly* | **Rasalgethi** -- *Informative* |
| **Laomedeia** -- *Upbeat* | **Achernar** -- *Soft* | **Alnilam** -- *Firm* |
| **Schedar** -- *Even* | **Gacrux** -- *Mature* | **Pulcherrima** -- *Forward* |
| **Achird** -- *Friendly* | **Zubenelgenubi** -- *Casual* | **Vindemiatrix** -- *Gentle* |
| **Sadachbia** -- *Lively* | **Sadaltager** -- *Knowledgeable* | **Sulafat** -- *Warm* |

### Extended Voice Library and filtering

Beyond the 30 featured studio voices in the preceding table, the **Extended
Voice Library** provides hundreds of additional voices across languages,
regional accents, character personas, and domains. You can browse, filter, and
audition the full Voice Library interactively in
[Google AI Studio](https://aistudio.google.com/generate-speech), or query it
programmatically using `client.voices.list()` (`GET /v1beta/voices`, using
`google-genai` 2.25.0+ / `@google/genai` 2.24.0+).

`ListVoices` returns your custom stored voices (ordered newest first) followed
by prebuilt catalog voices matching your filter criteria. When multiple values
are passed for a list filter, voices matching **any** value in that filter are
returned (`OR`), while distinct filter parameters combine with `AND`:

| Parameter                      | Type        | Description                                                  |
| ------------------------------ | ----------- | ------------------------------------------------------------ |
| `language_code`                | `list[str]` | BCP-47 language tag(s) (for example, `["en-US", "en-GB"]`). Case-insensitive exact match. |
| `region_code`                  | `list[str]` | ISO 3166-1 alpha-2 or UN M.49 region code(s) (for example, `["US", "GB"]`). |
| `accent`                       | `list[str]` | Regional accent descriptor(s) (for example, `["American", "British"]`). |
| `gender`                       | `list[str]` | Perceived gender presentation (`"female"`, `"male"`, or `"neutral"`). |
| `pitch`                        | `list[str]` | Vocal pitch classification (`"low"`, `"medium"`, or `"high"`). |
| `persona`                      | `list[str]` | Vocal persona or character archetype (for example, `["Warm, Friendly"]`, `["Narrator"]`). |
| `contexts` (`context` in REST) | `list[str]` | Optimal usage domain (for example, `["Audiobook", "Conversational", "News"]`). |
| `type` (`type_` in Python)     | `list[str]` | Filter by voice source: `"prebuilt"`, `"prompted"` ([Voice design](https://ai.google.dev/gemini-api/docs/generate-content/voice-design)), or `"replicated"` ([Voice replication](https://ai.google.dev/gemini-api/docs/generate-content/voice-replication)). |
| `search`                       | `str`       | Free-text substring search matched case-insensitively against both `display_name` and `description`. |
| `page_size`                    | `int`       | Maximum number of voices returned per page (default `50`, maximum `1000`). |
| `page_token`                   | `str`       | Token from `response.next_page_token` to fetch the next page of results. |

### Python

    from google import genai
    
    client = genai.Client()
    
    # Filter the Voice Library by language, gender, pitch, domain context, and keyword
    response = client.voices.list(
        language_code=["en-US", "en-GB"],
        gender=["female"],
        pitch=["medium", "low"],
        contexts=["Audiobook", "Conversational"],
        type_=["prebuilt"],
        search="warm",
        page_size=50,
    )
    
    for voice in response.voices or []:
        print(
            f"{voice.id} | {voice.display_name} ({voice.language_code},"
            f" {voice.accent}, {voice.gender}, pitch={voice.pitch}):"
            f" {voice.description}"
        )

### JavaScript

    import { GoogleGenAI } from "@google/genai";
    
    const ai = new GoogleGenAI();
    
    // Filter the Voice Library by language, gender, pitch, domain context, and keyword
    const response = await ai.voices.list({
      language_code: ["en-US", "en-GB"],
      gender: ["female"],
      pitch: ["medium", "low"],
      contexts: ["Audiobook", "Conversational"],
      type: ["prebuilt"],
      search: "warm",
      page_size: 50,
    });
    
    for (const voice of response.voices ?? []) {
      console.log(
        `${voice.id} | ${voice.display_name} (${voice.language_code}, ${voice.accent}, ${voice.gender}, pitch=${voice.pitch}): ${voice.description}`
      );
    }

### REST

    curl -G "https://generativelanguage.googleapis.com/v1beta/voices" \
      -H "x-goog-api-key: $GEMINI_API_KEY" \
      --data-urlencode "language_code=en-US" \
      --data-urlencode "language_code=en-GB" \
      --data-urlencode "gender=female" \
      --data-urlencode "pitch=medium" \
      --data-urlencode "context=Audiobook" \
      --data-urlencode "type=prebuilt" \
      --data-urlencode "search=warm" \
      --data-urlencode "page_size=50"

## Supported languages

The TTS models detect the input language automatically.
[Gemini 3.8 Flash TTS](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash-tts)
(`gemini-3.8-flash-tts`) supports **over 130 languages** , and
[Gemini 3.8 Flash-Lite TTS](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash-lite-tts)
(`gemini-3.8-flash-lite-tts`) supports **over 100 languages**:

| Language                      | Gemini 3.8 Flash TTS | Gemini 3.8 Flash-Lite TTS |
| ----------------------------- | -------------------- | ------------------------- |
| Acehnese (Arab script)        | ✔️                    | ✔️                         |
| Afrikaans                     | ✔️                    | ✔️                         |
| Akan                          | ✔️                    | ✔️                         |
| Amharic                       | ✔️                    | ✔️                         |
| Armenian                      | ✔️                    | ✔️                         |
| Assamese                      | ✔️                    | ✔️                         |
| Awadhi                        | ✔️                    | ✔️                         |
| Balinese                      | ✔️                    | ✔️                         |
| Bangla                        | ✔️                    | ✔️                         |
| Banjar (Arab script)          | ✔️                    | ---                       |
| Banjar (Latn script)          | ✔️                    | ✔️                         |
| Bashkir                       | ✔️                    | ---                       |
| Basque                        | ✔️                    | ✔️                         |
| Belarusian                    | ✔️                    | ✔️                         |
| Bemba                         | ✔️                    | ---                       |
| Bhojpuri                      | ✔️                    | ✔️                         |
| Bosnian                       | ✔️                    | ✔️                         |
| Buginese                      | ✔️                    | ✔️                         |
| Bulgarian                     | ✔️                    | ✔️                         |
| Burmese                       | ✔️                    | ---                       |
| Cantonese                     | ✔️                    | ✔️                         |
| Catalan                       | ✔️                    | ✔️                         |
| Cebuano                       | ✔️                    | ✔️                         |
| Central Kurdish               | ✔️                    | ✔️                         |
| Chhattisgarhi                 | ✔️                    | ✔️                         |
| Chinese (Hans script)         | ✔️                    | ✔️                         |
| Chinese (Hant script)         | ✔️                    | ✔️                         |
| Crimean Tatar                 | ✔️                    | ---                       |
| Croatian                      | ✔️                    | ✔️                         |
| Czech                         | ✔️                    | ✔️                         |
| Danish                        | ✔️                    | ✔️                         |
| Dutch                         | ✔️                    | ✔️                         |
| Dyula                         | ✔️                    | ---                       |
| Dzongkha                      | ✔️                    | ---                       |
| Egyptian Arabic               | ✔️                    | ✔️                         |
| English                       | ✔️                    | ✔️                         |
| Estonian                      | ✔️                    | ✔️                         |
| Filipino                      | ✔️                    | ✔️                         |
| Finnish                       | ✔️                    | ---                       |
| French                        | ✔️                    | ✔️                         |
| Galician                      | ✔️                    | ✔️                         |
| Ganda                         | ✔️                    | ✔️                         |
| Georgian                      | ✔️                    | ✔️                         |
| German                        | ✔️                    | ✔️                         |
| Greek                         | ✔️                    | ✔️                         |
| Guarani                       | ✔️                    | ---                       |
| Gujarati                      | ✔️                    | ✔️                         |
| Haitian Creole                | ✔️                    | ✔️                         |
| Halh Mongolian                | ✔️                    | ✔️                         |
| Hausa                         | ✔️                    | ✔️                         |
| Hebrew                        | ✔️                    | ✔️                         |
| Hindi                         | ✔️                    | ✔️                         |
| Hungarian                     | ✔️                    | ✔️                         |
| Icelandic                     | ✔️                    | ✔️                         |
| Igbo                          | ✔️                    | ---                       |
| Iloko                         | ✔️                    | ✔️                         |
| Indonesian                    | ✔️                    | ✔️                         |
| Iranian Persian               | ✔️                    | ✔️                         |
| Italian                       | ✔️                    | ✔️                         |
| Japanese                      | ✔️                    | ✔️                         |
| Javanese                      | ✔️                    | ✔️                         |
| Kabyle                        | ✔️                    | ---                       |
| Kamba                         | ✔️                    | ✔️                         |
| Kannada                       | ✔️                    | ✔️                         |
| Kashmiri (Arab script)        | ✔️                    | ✔️                         |
| Kashmiri (Deva script)        | ✔️                    | ✔️                         |
| Kazakh                        | ✔️                    | ✔️                         |
| Khmer                         | ✔️                    | ✔️                         |
| Kikuyu                        | ✔️                    | ✔️                         |
| Kinyarwanda                   | ✔️                    | ✔️                         |
| Kongo                         | ✔️                    | ✔️                         |
| Korean                        | ✔️                    | ✔️                         |
| Kyrgyz                        | ✔️                    | ✔️                         |
| Lao                           | ✔️                    | ✔️                         |
| Latgalian                     | ✔️                    | ---                       |
| Lingala                       | ✔️                    | ✔️                         |
| Lithuanian                    | ✔️                    | ---                       |
| Luxembourgish                 | ✔️                    | ---                       |
| Macedonian                    | ✔️                    | ✔️                         |
| Magahi                        | ✔️                    | ✔️                         |
| Maithili                      | ✔️                    | ✔️                         |
| Malayalam                     | ✔️                    | ✔️                         |
| Maltese                       | ✔️                    | ✔️                         |
| Manipuri                      | ✔️                    | ✔️                         |
| Marathi                       | ✔️                    | ✔️                         |
| Minangkabau (Arab script)     | ✔️                    | ✔️                         |
| Minangkabau (Latn script)     | ✔️                    | ---                       |
| Mizo                          | ✔️                    | ✔️                         |
| Nepali (individual language)  | ✔️                    | ✔️                         |
| Nigerian Fulfulde             | ✔️                    | ✔️                         |
| North Azerbaijani             | ✔️                    | ✔️                         |
| Northern Sotho                | ✔️                    | ✔️                         |
| Northern Uzbek                | ✔️                    | ✔️                         |
| Norwegian Bokmål              | ✔️                    | ✔️                         |
| Norwegian Nynorsk             | ✔️                    | ✔️                         |
| Nyanja                        | ✔️                    | ✔️                         |
| Occitan                       | ✔️                    | ---                       |
| Odia (individual language)    | ✔️                    | ✔️                         |
| Pangasinan                    | ✔️                    | ---                       |
| Persian (Afghanistan)         | ✔️                    | ✔️                         |
| Polish                        | ✔️                    | ✔️                         |
| Portuguese                    | ✔️                    | ✔️                         |
| Punjabi                       | ✔️                    | ✔️                         |
| Romanian                      | ✔️                    | ✔️                         |
| Russian                       | ✔️                    | ✔️                         |
| Santali                       | ✔️                    | ✔️                         |
| Serbian                       | ✔️                    | ✔️                         |
| Sindhi                        | ✔️                    | ---                       |
| Sinhala                       | ✔️                    | ✔️                         |
| Slovak                        | ✔️                    | ✔️                         |
| Slovenian                     | ✔️                    | ---                       |
| Somali                        | ✔️                    | ---                       |
| South Azerbaijani             | ✔️                    | ✔️                         |
| Southern Pashto               | ✔️                    | ✔️                         |
| Southern Sotho                | ✔️                    | ---                       |
| Spanish                       | ✔️                    | ✔️                         |
| Standard Arabic (Arab script) | ✔️                    | ✔️                         |
| Standard Arabic (Latn script) | ✔️                    | ✔️                         |
| Standard Latvian              | ✔️                    | ✔️                         |
| Standard Malay                | ✔️                    | ✔️                         |
| Swahili (individual language) | ✔️                    | ---                       |
| Swati                         | ✔️                    | ---                       |
| Swedish                       | ✔️                    | ---                       |
| Tajik                         | ✔️                    | ---                       |
| Tamil                         | ✔️                    | ✔️                         |
| Telugu                        | ✔️                    | ✔️                         |
| Thai                          | ✔️                    | ---                       |
| Tigrinya                      | ✔️                    | ---                       |
| Tosk Albanian                 | ✔️                    | ---                       |
| Turkish                       | ✔️                    | ✔️                         |
| Uyghur                        | ✔️                    | ---                       |
| Vietnamese                    | ✔️                    | ✔️                         |

## Supported models

| Model                                                        | Single speaker | Multi-speaker | Voice design | Voice replication |
| ------------------------------------------------------------ | -------------- | ------------- | ------------ | ----------------- |
| [Gemini 3.8 Flash TTS](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash-tts) (`gemini-3.8-flash-tts`) | ✔️              | ✔️             | ✔️            | ✔️                 |
| [Gemini 3.8 Flash-Lite TTS](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash-lite-tts) (`gemini-3.8-flash-lite-tts`) | ✔️              | ✔️             | ✔️            | ✔️                 |
| [Gemini 3.1 Flash TTS Preview](https://ai.google.dev/gemini-api/docs/models/gemini-3.1-flash-tts-preview) | ✔️              | ✔️             | ---          | ---               |
| [Gemini 2.5 Pro Preview TTS](https://ai.google.dev/gemini-api/docs/models/gemini-2.5-pro-preview-tts) | ✔️              | ✔️             | ---          | ---               |

### When to use which model

Both Gemini 3.8 TTS models share the exact same API schema and prompting format,
allowing you to switch between them with a single parameter change:

- **Use [Gemini 3.8 Flash TTS](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash-tts)
  (`gemini-3.8-flash-tts`)** when maximum acoustic fidelity, nuanced acting, and expressive control are top priority. It is ideal for studio-grade creative work, complex multi-speaker dialogue, heavy vocal-burst tags, difficult pronunciations, regional or minority dialects, and long-form narrations requiring rock-solid voice and room-tone stability.
- **Use [Gemini 3.8 Flash-Lite TTS](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash-lite-tts)
  (`gemini-3.8-flash-lite-tts`)** as your fast, cost-efficient workhorse replacement for `gemini-3.1-flash-tts-preview`. It is optimized for high-volume bulk production, conversational voice agent cascades, read-aloud features, reliable voice replication, and everyday single-speaker speech across major languages.

### Migration guide

When upgrading from earlier preview models (`gemini-3.1-flash-tts-preview` or
`gemini-2.5-pro-preview-tts`) to Gemini 3.8 TTS (`gemini-3.8-flash-tts` or
`gemini-3.8-flash-lite-tts`), review these five key changes:

1. **Separate style from transcript:** Move sustained acting, tone, prosody, and pacing instructions (such as `"whispering"`, `"out of breath"`, or `"speaking slowly"`) out of plain text and into `speech_metadata.style`. Keep `text` strictly as the verbatim transcript plus inline vocal tags.
2. **Design personas upfront with Voice design:** Replace multi-paragraph `"Audio Profile"` or `"Director's Notes"` blocks with a custom voice created in [Voice design](https://ai.google.dev/gemini-api/docs/generate-content/voice-design), then carry that `voice_...` ID through your TTS requests with minimal or empty `style` strings.
3. **Use structured dialogue turns:** For multi-speaker dialogue, pass one `part` per speaker turn with `speech_metadata.speaker` instead of embedding `Speaker: ...` prefixes inside a single text block.
4. **Use angle brackets for inline vocal tags:** Use angle brackets (`<laugh>`, `<sigh>`, `<cough>`, `<breath>`, `<short pause>`) for point-in-time human vocalizations and pauses. Avoid non-vocal sound-effect tags (such as applause or thuds).
5. **Account for default WAV (`AUDIO_WAV`) output on unary requests:** Unlike `gemini-3.1-flash-tts-preview` (which returned headerless raw PCM `AUDIO_L16` by default), Gemini 3.8 TTS models return complete **WAV
   (`AUDIO_WAV`)** audio with a RIFF header (24 kHz, mono, 16-bit PCM) on unary requests. You can write decoded audio bytes directly to a `.wav` file without manually wrapping them with Python's `wave` module or Node's `wav` package. If your existing pipeline expects headerless raw PCM, set `response_format` to `{"audio": {"mime_type": "AUDIO_L16"}}` (see [Audio output formats](https://ai.google.dev/gemini-api/docs/generate-content/speech-generation#audio-output-formats)).

## Prompting guide

Gemini 3.8 TTS models treat input text strictly as a **verbatim transcript** .
Unlike earlier preview models where stage directions were embedded in plain text,
Gemini 3.8 TTS separates sustained turn-level directions (`speech_metadata`)
from point-in-time inline vocal tags.

### Style field versus inline tags

Split your performance instructions by scope:

- **Turn-level delivery (`speech_metadata.style`):** Put sustained delivery attributes---such as emotion, prosody, overall pace, or delivery style (like `"whispering"`, `"out of breath"`, `"muttering"`, or `"sarcastic"`)---into the `style` field of `speech_metadata`. To create a stable character and performance across turns, design the persona upfront in [Voice design](https://ai.google.dev/gemini-api/docs/generate-content/voice-design) and use `style` only for optional turn-level tweaks.
- **Point-in-time events (inline tags):** Put momentary non-speech vocal bursts, breaths, or pauses inline inside the transcript using angle brackets (`<cough>`, `<breath>`, `<sigh>`, `<short pause>`). Use angle brackets (`<...>`) for highest audio quality, and stick to human vocalizations rather than non-vocal sound effects.

| Scope                                         | Where to place             | Examples                                                     |
| --------------------------------------------- | -------------------------- | ------------------------------------------------------------ |
| **Turn-level** (sustained across the turn)    | `speech_metadata.style`    | `"angry tone"`, `"speaking rapidly"`, `"out of breath"`, `"whispers"`, `"sarcastic"` |
| **Point-in-time** (occurs at a specific word) | Inline in `text` (`<...>`) | `"<cough> Thank you all for coming tonight! <throat-clearing> As I was saying..."` |

### Pacing and pauses

You can control rhythm and silence at three levels of granularity:

- **Punctuation and ellipses:** Use commas, dashes (`--`), and ellipses (`...`) for natural conversational hesitation.
- **Inline pause tags:** Insert `<short pause>` or `<long pause>` at exact points in the script where a speaker should pause: `text
  Hold on, let me think... <short pause> Alright, I've got it.`
- **Turn-level pace:** Set `"style": "speaking rapidly"` or `"style": "speaking slowly"` in `speech_metadata` to control the speaking rate across the whole turn.

### Prosody and pitch

Use **`speech_metadata.style`** to control prosody, pitch, and inflection across
a turn (for example, `"style": "high pitch, cheerful and excited inflection"` or
`"style": "monotone and flat"`). If the emotion or prosody shifts mid-dialogue,
split the script into separate turns with distinct `style` values for each turn.

### Emphasis

Capitalize specific words in the transcript, combined with punctuation and inline
vocal tags, to place natural vocal stress on key words:

    This is a VERY important point!
    It was a VERY long day <sigh> ... nobody listens anymore.

### Vocal bursts and non-speech sounds

Place non-speech human vocalizations inline using angle brackets (`<...>`) at
the exact point where the sound should occur. Recommended vocal tags include:

|---|---|---|---|
| `<argh>` | `<breath>` | `<heavy breath>` | `<exhales>` |
| `<cackle>` | `<cheer>` | `<chuckle>` / `<chuckles>` | `<cough>` |
| `<cry>` | `<gasp>` | `<giggle>` | `<groan>` |
| `<growl>` | `<grunt>` | `<grr>` | `<hiss>` |
| `<laugh>` / `<laughter>` | `<moan>` | `<pant>` | `<pff>` / `<phew>` |
| `<scream>` | `<shout>` | `<shriek>` | `<sigh>` / `<sighs>` |
| `<sneeze>` | `<snicker>` | `<snort>` | `<sob>` |
| `<throat-clearing>` | `<tsk>` | `<whimper>` | `<whispers>` / `<whispering>` |
| `<yawn>` | `<short pause>` | `<long pause>` |   |

> [!NOTE]
> **Note:** If your transcript is in a non-English language, continue to use English inline tags for best results.

### Backchannels and overlapping speech

In multi-speaker dialogue, wrap listener reactions in pipe characters
(`|reaction|`) inside a speaker's turn to create natural backchannels or
overlapping speech without breaking into a separate turn per reaction.

- **Short backchannel exchanges:** Layer brief listener reactions (`|oh hmm|`, `|oh really?|`, `|absolutely|`) inside the active speaker's turn:
  - **Turn 1 (Speaker A):** `"So the launch is Thursday |oh hmm| Are we actually ready?"`
  - **Turn 2 (Speaker B):** `"Ready enough |oh really?| The last blocker cleared this morning."`
  - **Turn 3 (Speaker A):** `"Then let's ship it |absolutely| and watch the dashboards."`
- **Overlapping and interleaved speech:** Use multiple pipe segments to simulate simultaneous or interleaved speech between two speakers (works best with `gemini-3.8-flash-tts`):
  - **Simultaneous countdown/chorus:** `"Let's surprise him on three |ok| ready?"` followed by `"one. two. three. |happy| happy |birthday| birthday!"`
  - **Full speaker overlap:** `"Hello |oh| there |my| it |goodness| must |gracious| be |would| almost |you| time |look| for |at that| dinner"`

### Consistency across generations and what to avoid

Follow these guidelines to keep vocal identity stable across turns:

- **Design personas upfront in Voice design instead of long style blocks:** Long-form `"Audio Profile"` paragraphs and multi-bullet `"Director's Notes"` carried over from earlier models are the most common cause of voice drift. Use that same creative intuition upfront in [Voice design](https://ai.google.dev/gemini-api/docs/generate-content/voice-design) to generate a persistent custom `voice_...` persona, then carry that voice ID through your TTS calls.
- **Rely on the voice reference for stability (omit meta-instructions):** Gemini 3.8 TTS models are trained to anchor on the audio reference first. Do not include instructions telling the model to hold the voice steady (such as `"do not switch speaker identity"` or `"maintain identical timbre"`)---extra prompt text increases drift. Drop unnecessary style instructions and let the model vary naturally around the stable point provided by the voice reference.
- **Do not try to change immutable speaker traits in `style`:** Avoid putting age, gender, names, or permanent accent changes in `speech_metadata.style`. Instead, pick a regional voice from the Extended Voice Library or create one with [Voice design](https://ai.google.dev/gemini-api/docs/generate-content/voice-design).

### Recommended workflow

1. **Build the character once:** Create your character in [Voice design](https://ai.google.dev/gemini-api/docs/generate-content/voice-design) or select a regional voice from the Extended Voice Library that matches your target language and persona.
2. **Write natural spoken transcripts with disfluencies:** For maximum naturalness, write the `text` as a real spoken transcript---including natural conversational disfluencies and hesitations (for example, `"Oh uh yeah I think... hm, so that's interesting"`).
3. **Test plain TTS first:** Synthesize your transcript with an empty `style` field first---most requests need no `style` instruction at all.
4. **Add short `style` prompts only for tweaks:** Add a concise `style` string (such as `"casual, friendly"` or `"muttering, then reassuring"`) only for turns that need a specific delivery adjustment, and reuse that exact short string across turns when you want a consistent baseline.

### Multi-turn dialogue and voice agents

When building real-time conversational voice agents or multi-turn applications:

- Make **one TTS call per turn** as LLM text chunks arrive.
- Let the configured `voice` (prebuilt, designed `voice_...`, or replicated `voice_...` / `voicekey_...`) carry the speaker's identity across turns---never re-send a long character persona on each turn.
- Leave the per-turn `style` field empty, or send one short constant string (such as `"casual, friendly"`) for the whole conversation.
- Split long agent responses into shorter turns rather than reaching for stronger style prompts.

## Streaming speech generation

You can stream generated audio as it is being synthesized by the model. Unlike
unary requests (which return a complete WAV file with a RIFF header),
**streaming requests return headerless raw 16-bit signed little-endian linear
PCM (`AUDIO_L16` / `audio/L16;codec=pcm;rate=24000`, 24 kHz, mono) chunks by
default** so audio chunks can be played or concatenated continuously without
container headers:

### Python

    from google import genai
    
    client = genai.Client()
    
    response_stream = client.models.generate_content_stream(
        model="gemini-3.8-flash-tts",
        contents=[{
            "role": "user",
            "parts": [{
                "text": "Have a wonderful day!",
                "speech_metadata": {"style": "cheerful and friendly"},
            }],
        }],
        config={
            "response_modalities": ["AUDIO"],
            "speech_config": {
                "voice_config": {"voice": "Kore"}
            },
        },
    )
    
    for chunk in response_stream:
        try:
            data = chunk.candidates[0].content.parts[0].inline_data.data
            # data contains raw PCM bytes (24kHz, 1-channel, 16-bit)
        except (IndexError, AttributeError):
            pass

### JavaScript

    import {GoogleGenAI} from '@google/genai';
    
    async function main() {
       const ai = new GoogleGenAI({});
    
       const responseStream = await ai.models.generateContentStream({
          model: 'gemini-3.8-flash-tts',
          contents: [{
             role: 'user',
             parts: [{
                text: 'Have a wonderful day!',
                speech_metadata: { style: 'cheerful and friendly' },
             }],
          }],
          config: {
             responseModalities: ['AUDIO'],
             speechConfig: {
                voiceConfig: { voice: 'Kore' },
             },
          },
       });
    
       for await (const chunk of responseStream) {
          const data = chunk.candidates?.[0]?.content?.parts?.[0]?.inlineData?.data;
          if (data) {
             const audioBuffer = Buffer.from(data, 'base64');
             // Process the audio buffer
          }
       }
    }
    await main();

### REST

    curl "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash-tts:streamGenerateContent" \
      -H "x-goog-api-key: $GEMINI_API_KEY" \
      -X POST \
      -H "Content-Type: application/json" \
      -d '{
            "contents": [{
              "role": "user",
              "parts": [{
                "text": "Have a wonderful day!",
                "speech_metadata": {
                  "style": "cheerful and friendly"
                }
              }]
            }],
            "generationConfig": {
              "responseModalities": ["AUDIO"],
              "speechConfig": {
                "voiceConfig": {
                  "voice": "Kore"
                }
              }
            }
        }'

## Audio output formats

Gemini 3.8 TTS models use different default audio formats depending on whether
the request is unary or streaming:

- **Unary requests (`models.generate_content`):** Return complete **WAV
  (`AUDIO_WAV`)** audio with a RIFF header (24 kHz, mono, 16-bit signed little-endian PCM). You can write the decoded audio bytes directly to a `.wav` file without manually adding a WAV container.
- **Streaming requests (`models.generate_content_stream` / `streamGenerateContent`):** Return **headerless raw Linear PCM (`AUDIO_L16`)** chunks (24 kHz, mono, 16-bit signed little-endian PCM) by default so chunks can be streamed or concatenated continuously without container headers on each chunk.

You can override the output audio encoding and sample rate using
`generationConfig.responseFormat.audio`:

| `mimeType` value                    | Format                                | Description                                                  |
| ----------------------------------- | ------------------------------------- | ------------------------------------------------------------ |
| `"AUDIO_WAV"` *(unary default)*     | WAV (`audio/wav`)                     | Complete WAV file with a RIFF header (24 kHz, mono, 16-bit PCM). |
| `"AUDIO_L16"` *(streaming default)* | Linear PCM (`audio/l16`)              | Headerless raw 16-bit signed little-endian linear PCM. Best for streaming, custom audio pipelines, or concatenating multi-turn clips. |
| `"AUDIO_MULAW"`                     | μ-law (`audio/basic` / `audio/mulaw`) | G.711 μ-law companded audio. Commonly used in North American and Japanese telephony (8 kHz). |
| `"AUDIO_ALAW"`                      | A-law (`audio/alaw`)                  | G.711 A-law companded audio. Commonly used in European and international telephony (8 kHz). |

You can also optionally specify `sampleRate` (for example, `24000`, `16000`, or
`8000` Hz; defaults to `24000` Hz).

The following example requests headerless raw 16-bit PCM (`AUDIO_L16`) at
24 kHz:

### Python

    from google import genai
    
    client = genai.Client()
    
    response = client.models.generate_content(
        model="gemini-3.8-flash-tts",
        contents=[{
            "role": "user",
            "parts": [{
                "text": "Have a wonderful day!",
                "speech_metadata": {"style": "cheerful and friendly"},
            }],
        }],
        config={
            "response_modalities": ["AUDIO"],
            "response_format": {
                "audio": {
                    "mime_type": "AUDIO_L16",
                    "sample_rate": 24000,
                }
            },
            "speech_config": {
                "voice_config": {"voice": "Kore"}
            },
        },
    )
    
    data = response.candidates[0].content.parts[0].inline_data.data
    with open("out.pcm", "wb") as f:
        f.write(data)

### JavaScript

    import {GoogleGenAI} from '@google/genai';
    import * as fs from 'node:fs';
    
    async function main() {
       const ai = new GoogleGenAI({});
    
       const response = await ai.models.generateContent({
          model: 'gemini-3.8-flash-tts',
          contents: [{
             role: 'user',
             parts: [{
                text: 'Have a wonderful day!',
                speech_metadata: { style: 'cheerful and friendly' },
             }],
          }],
          config: {
             responseModalities: ['AUDIO'],
             responseFormat: {
                audio: {
                   mimeType: 'AUDIO_L16',
                   sampleRate: 24000,
                },
             },
             speechConfig: {
                voiceConfig: { voice: 'Kore' },
             },
          },
       });
    
       const data = response.candidates?.[0]?.content?.parts?.[0]?.inlineData?.data;
       const audioBuffer = Buffer.from(data, 'base64');
    
       fs.writeFileSync('out.pcm', audioBuffer);
    }
    await main();

### REST

    curl "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash-tts:generateContent" \
      -H "x-goog-api-key: $GEMINI_API_KEY" \
      -X POST \
      -H "Content-Type: application/json" \
      -d '{
            "contents": [{
              "role": "user",
              "parts": [{
                "text": "Have a wonderful day!",
                "speech_metadata": {
                  "style": "cheerful and friendly"
                }
              }]
            }],
            "generationConfig": {
              "responseModalities": ["AUDIO"],
              "responseFormat": {
                "audio": {
                  "mimeType": "AUDIO_L16",
                  "sampleRate": 24000
                }
              },
              "speechConfig": {
                "voiceConfig": {
                  "voice": "Kore"
                }
              }
            }
        }' | jq -r '.candidates[0].content.parts[0].inlineData.data' | \
              base64 --decode > out.pcm

## Limitations

- TTS models accept text-only inputs and generate audio-only outputs.
- Single-request multi-speaker generation (`multiSpeakerVoiceConfig`) supports up to 2 speakers using prebuilt voices. To combine custom designed (`voice_...`) or replicated (`voice_...` / `voicekey_...`) voices in multi-character dialogue, synthesize each speaker's turn individually. Because unary requests return `audio/wav` with a 44-byte RIFF header by default, request raw PCM (`AUDIO_L16`) or strip the WAV header from each turn before concatenating the 24kHz PCM audio frames.
- **Custom voice storage limits and TTL:**
  - **Stateful voices (`store=True`, prompted or replicated):** Maximum of **200 voices per project** with a **1-year TTL** (time-to-live).
  - **Stateless voice keys (`store=False`, `voicekey_...`):** **7-day TTL** (time-to-live).
- Review the [Supported languages](https://ai.google.dev/gemini-api/docs/generate-content/speech-generation#languages) section for language coverage.

## What's next

- Create custom vocal personas from natural language with [Voice design](https://ai.google.dev/gemini-api/docs/generate-content/voice-design).
- Replicate an existing speaker's voice in [Voice replication](https://ai.google.dev/gemini-api/docs/generate-content/voice-replication).
- Compare model specifications on the [Gemini 3.8 Flash TTS](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash-tts) and [Gemini 3.8 Flash-Lite TTS](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash-lite-tts) model pages.
- Explore interactive bidirectional audio with the [Live API](https://ai.google.dev/gemini-api/docs/live).