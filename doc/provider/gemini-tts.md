# Text-to-speech generation (TTS)

The Gemini API can transform text input into single-speaker or multi-speaker
audio using Gemini text-to-speech (TTS) generation capabilities.
Text-to-speech generation is
*[controllable](/docs/speech-generation#controllable)*, meaning you can combine
structured turn metadata (`speech_metadata`) and inline vocal tags to guide the
*style*, *accent*, *pace*, and *tone* of the audio.

The TTS capability differs from speech generation provided through the
[Live API](/docs/live-api), which is designed for interactive,
unstructured audio, and multimodal inputs and outputs. While the Live API excels
in dynamic conversational contexts, TTS through the Gemini API
is tailored for scenarios that require exact text recitation with fine-grained
control over style and sound, such as podcast or audiobook generation.

This guide shows you how to generate single-speaker and multi-speaker audio from
text using [Gemini 3.8 Flash TTS](/docs/models/gemini-3.8-flash-tts)
(`gemini-3.8-flash-tts`) and
[Gemini 3.8 Flash-Lite TTS](/docs/models/gemini-3.8-flash-lite-tts)
(`gemini-3.8-flash-lite-tts`).

## Before you begin  {% class="hide-from-toc" id="before-you-begin" %}

Ensure you use a Gemini TTS model listed in the
[Supported models](/docs/speech-generation#supported-models) section. For
optimal results, review
[When to use which model](/docs/speech-generation#when-to-use-which-model) to
select the best model for your workload.

You may find it useful to
[test the Gemini TTS models in AI Studio](https://aistudio.google.com/generate-speech)
before you start building.

{% callout type="note" %}
  TTS models accept text-only inputs and produce audio-only outputs. For a
  complete list of restrictions specific to TTS models, review the
  [Limitations](/docs/speech-generation#limitations) section.

{% /callout %}

## Single-speaker TTS  {% id="single-speaker" %}

To convert text to single-speaker audio with Gemini 3.8 TTS models, pass the
verbatim transcript in `input`, attach turn-level styling using a
`speech_metadata` annotation, and configure your voice in
`generation_config.speech_config`. You can choose a voice from the prebuilt
[voice options](/docs/speech-generation#voices), the Extended Voice Library
(`GET /v1beta/voices`), a custom [Voice design](/docs/voice-design) ID
(`voice_...`), or a [Voice replication](/docs/voice-replication) ID
(`voice_...`, or optional stateless `voicekey_...`).

This example saves the output audio from the model in a WAV file:

{% tabs %}
  {% tab label="Python" %}
  ```python
  import base64
  from google import genai
  
  client = genai.Client()
  
  interaction = client.interactions.create(
      model="gemini-3.8-flash-tts",
      input=[{
          "type": "user_input",
          "content": [{
              "type": "text",
              "text": "Have a wonderful day!",
              "annotations": [{
                  "type": "speech_metadata",
                  "style": "cheerful and friendly",
              }],
          }],
      }],
      response_format={"type": "audio"},
      generation_config={
          "speech_config": [
              {"voice": "Kore"},
          ]
      },
  )
  
  with open("out.wav", "wb") as f:
      f.write(base64.b64decode(interaction.output_audio.data))
  ```

  {% /tab %}

  {% tab label="JavaScript" %}
  ```js
  import {GoogleGenAI} from '@google/genai';
  import * as fs from 'node:fs';
  
  async function main() {
     const client = new GoogleGenAI({});
  
     const interaction = await client.interactions.create({
        model: 'gemini-3.8-flash-tts',
        input: [{
           type: 'user_input',
           content: [{
              type: 'text',
              text: 'Have a wonderful day!',
              annotations: [{
                 type: 'speech_metadata',
                 style: 'cheerful and friendly',
              }],
           }],
        }],
        response_format: { type: 'audio' },
        generation_config: {
           speech_config: [
              { voice: 'Kore' },
           ],
        },
     });
  
     const audioBuffer = Buffer.from(interaction.output_audio.data, 'base64');
  
     fs.writeFileSync('out.wav', audioBuffer);
  }
  await main();
  ```

  {% /tab %}

  {% tab label="Go" %}
  ```go
  package main
  
  import (
      "context"
      "encoding/base64"
      "encoding/binary"
      "log"
      "os"
  
      "google.golang.org/genai"
      "google.golang.org/genai/interactions/models/interactions"
      "google.golang.org/genai/interactions/models/operations"
  )
  
  func saveWaveFile(filename string, pcmData []byte) error {
      f, err := os.Create(filename)
      if err != nil {
          return err
      }
      defer f.Close()
  
      sampleRate := uint32(24000)
      numChannels := uint16(1)
      bitsPerSample := uint16(16)
      byteRate := sampleRate * uint32(numChannels) * uint32(bitsPerSample/8)
      blockAlign := numChannels * (bitsPerSample / 8)
      dataSize := uint32(len(pcmData))
  
      f.WriteString("RIFF")
      binary.Write(f, binary.LittleEndian, uint32(36+dataSize))
      f.WriteString("WAVEfmt ")
      binary.Write(f, binary.LittleEndian, uint32(16))
      binary.Write(f, binary.LittleEndian, uint16(1))
      binary.Write(f, binary.LittleEndian, numChannels)
      binary.Write(f, binary.LittleEndian, sampleRate)
      binary.Write(f, binary.LittleEndian, byteRate)
      binary.Write(f, binary.LittleEndian, blockAlign)
      binary.Write(f, binary.LittleEndian, bitsPerSample)
      f.WriteString("data")
      binary.Write(f, binary.LittleEndian, dataSize)
      _, err = f.Write(pcmData)
      return err
  }
  
  func main() {
      ctx := context.Background()
      client, err := genai.NewClient(ctx, nil)
      if err != nil {
          log.Fatal(err)
      }
  
      generationConfig := &interactions.GenerationConfig{
          SpeechConfig: genai.Ptr(interactions.NewSpeechConfigUnion([]interactions.SpeechConfig{
              {Voice: genai.Ptr("Kore")},
          })),
      }
  
      res, err := client.Interactions.Create(ctx, operations.CreateInteractionRequest{
          Body: operations.NewCreateInteractionRequestBody(interactions.CreateModelInteraction{
              Model: interactions.Model("gemini-3.1-flash-tts-preview"),
              Input: interactions.NewInteractionsInput("Say cheerfully: Have a wonderful day!"),
              ResponseFormat: genai.Ptr(interactions.NewCreateModelInteractionResponseFormat(
                  interactions.NewResponseFormat(interactions.AudioResponseFormat{}),
              )),
              GenerationConfig: generationConfig,
          }),
      })
      if err != nil {
          log.Fatal(err)
      }
  
      if res.Interaction.OutputAudio != nil && res.Interaction.OutputAudio.Data != nil {
          pcmBytes, err := base64.StdEncoding.DecodeString(*res.Interaction.OutputAudio.Data)
          if err != nil {
              log.Fatal(err)
          }
          if err := saveWaveFile("out.wav", pcmBytes); err != nil {
              log.Fatal(err)
          }
      }
  }
  ```

  {% /tab %}

  {% tab label="REST" %}
  ```sh
  curl -X POST "https://generativelanguage.googleapis.com/v1beta/interactions" \
    -H "x-goog-api-key: $GEMINI_API_KEY" \
    -H "Content-Type: application/json" \
    -d '{
      "model": "gemini-3.8-flash-tts",
      "input": [{
        "type": "user_input",
        "content": [{
          "type": "text",
          "text": "Have a wonderful day!",
          "annotations": [{
            "type": "speech_metadata",
            "style": "cheerful and friendly"
          }]
        }]
      }],
      "response_format": {
         "type": "audio"
       },
      "generation_config": {
        "speech_config": [
          { "voice": "Kore" }
        ]
      }
    }' | jq -r '[.steps[] | select(.type=="model_output") | .content[] | select(.type=="audio")] | last | .data' | base64 --decode > out.wav
  ```

  {% /tab %}

{% /tabs %}

In the Python and JavaScript SDKs, you can retrieve generated audio data by
using the `interaction.output_audio` convenience property, which returns the
last generated audio block (in raw REST JSON responses, the base64-encoded audio
is stored in `steps[].content[].data`). For details on convenience properties,
see the
[Interactions overview](/docs/interactions-overview#convenience-properties).

## Multi-speaker TTS  {% id="multi-speaker" %}

For multi-speaker dialogue, configure two speakers in `speech_config.speakers`
and pass each turn as a separate text item with a `speech_metadata` annotation
specifying the `speaker` and optional turn-level `style`. Use
`"mode": "conversational"` for natural turn-taking cadence:

{% tabs %}
  {% tab label="Python" %}
  ```python
  import base64
  from google import genai
  
  client = genai.Client()
  
  interaction = client.interactions.create(
      model="gemini-3.8-flash-tts",
      input=[{
          "type": "user_input",
          "content": [
              {
                  "type": "text",
                  "text": "How's it going today Jane?",
                  "annotations": [{
                      "type": "speech_metadata",
                      "speaker": "Joe",
                      "style": "cheerful and friendly",
                  }],
              },
              {
                  "type": "text",
                  "text": "Not too bad, how about you? Ready to test these new voices?",
                  "annotations": [{
                      "type": "speech_metadata",
                      "speaker": "Jane",
                      "style": "calm and relaxed",
                  }],
              },
          ],
      }],
      response_format={"type": "audio"},
      generation_config={
          "speech_config": {
              "mode": "conversational",
              "speakers": [
                  {"speaker": "Joe", "voice": "Puck"},
                  {"speaker": "Jane", "voice": "Kore"},
              ],
          }
      },
  )
  
  with open("out.wav", "wb") as f:
      f.write(base64.b64decode(interaction.output_audio.data))
  ```

  {% /tab %}

  {% tab label="JavaScript" %}
  ```js
  import {GoogleGenAI} from '@google/genai';
  import * as fs from 'node:fs';
  
  async function main() {
     const client = new GoogleGenAI({});
  
     const interaction = await client.interactions.create({
        model: 'gemini-3.8-flash-tts',
        input: [{
           type: 'user_input',
           content: [
              {
                 type: 'text',
                 text: "How's it going today Jane?",
                 annotations: [{
                    type: 'speech_metadata',
                    speaker: 'Joe',
                    style: 'cheerful and friendly',
                 }],
              },
              {
                 type: 'text',
                 text: 'Not too bad, how about you? Ready to test these new voices?',
                 annotations: [{
                    type: 'speech_metadata',
                    speaker: 'Jane',
                    style: 'calm and relaxed',
                 }],
              },
           ],
        }],
        response_format: { type: 'audio' },
        generation_config: {
           speech_config: {
              mode: 'conversational',
              speakers: [
                 { speaker: 'Joe', voice: 'Puck' },
                 { speaker: 'Jane', voice: 'Kore' },
              ],
           },
        },
     });
  
     const audioBuffer = Buffer.from(interaction.output_audio.data, 'base64');
  
     fs.writeFileSync('out.wav', audioBuffer);
  }
  
  await main();
  ```

  {% /tab %}

  {% tab label="Go" %}
  ```go
  package main
  
  import (
      "context"
      "encoding/base64"
      "encoding/binary"
      "log"
      "os"
  
      "google.golang.org/genai"
      "google.golang.org/genai/interactions/models/interactions"
      "google.golang.org/genai/interactions/models/operations"
  )
  
  func saveWaveFile(filename string, pcmData []byte) error {
      f, err := os.Create(filename)
      if err != nil {
          return err
      }
      defer f.Close()
  
      sampleRate := uint32(24000)
      numChannels := uint16(1)
      bitsPerSample := uint16(16)
      byteRate := sampleRate * uint32(numChannels) * uint32(bitsPerSample/8)
      blockAlign := numChannels * (bitsPerSample / 8)
      dataSize := uint32(len(pcmData))
  
      f.WriteString("RIFF")
      binary.Write(f, binary.LittleEndian, uint32(36+dataSize))
      f.WriteString("WAVEfmt ")
      binary.Write(f, binary.LittleEndian, uint32(16))
      binary.Write(f, binary.LittleEndian, uint16(1))
      binary.Write(f, binary.LittleEndian, numChannels)
      binary.Write(f, binary.LittleEndian, sampleRate)
      binary.Write(f, binary.LittleEndian, byteRate)
      binary.Write(f, binary.LittleEndian, blockAlign)
      binary.Write(f, binary.LittleEndian, bitsPerSample)
      f.WriteString("data")
      binary.Write(f, binary.LittleEndian, dataSize)
      _, err = f.Write(pcmData)
      return err
  }
  
  func main() {
      ctx := context.Background()
      client, err := genai.NewClient(ctx, nil)
      if err != nil {
          log.Fatal(err)
      }
  
      prompt := "TTS the following conversation between Joe and Jane:\n" +
          "Joe: How's it going today Jane?\n" +
          "Jane: Not too bad, how about you?"
  
      generationConfig := &interactions.GenerationConfig{
          SpeechConfig: genai.Ptr(interactions.NewSpeechConfigUnion([]interactions.SpeechConfig{
              {Speaker: genai.Ptr("Joe"), Voice: genai.Ptr("Kore")},
              {Speaker: genai.Ptr("Jane"), Voice: genai.Ptr("Puck")},
          })),
      }
  
      res, err := client.Interactions.Create(ctx, operations.CreateInteractionRequest{
          Body: operations.NewCreateInteractionRequestBody(interactions.CreateModelInteraction{
              Model: interactions.Model("gemini-3.1-flash-tts-preview"),
              Input: interactions.NewInteractionsInput(prompt),
              ResponseFormat: genai.Ptr(interactions.NewCreateModelInteractionResponseFormat(
                  interactions.NewResponseFormat(interactions.AudioResponseFormat{}),
              )),
              GenerationConfig: generationConfig,
          }),
      })
      if err != nil {
          log.Fatal(err)
      }
  
      if res.Interaction.OutputAudio != nil && res.Interaction.OutputAudio.Data != nil {
          pcmBytes, err := base64.StdEncoding.DecodeString(*res.Interaction.OutputAudio.Data)
          if err != nil {
              log.Fatal(err)
          }
          if err := saveWaveFile("out.wav", pcmBytes); err != nil {
              log.Fatal(err)
          }
      }
  }
  ```

  {% /tab %}

  {% tab label="REST" %}
  ```sh
  curl -X POST "https://generativelanguage.googleapis.com/v1beta/interactions" \
    -H "x-goog-api-key: $GEMINI_API_KEY" \
    -H "Content-Type: application/json" \
    -d '{
    "model": "gemini-3.8-flash-tts",
    "input": [{
      "type": "user_input",
      "content": [
        {
          "type": "text",
          "text": "How'\''s it going today Jane?",
          "annotations": [{
            "type": "speech_metadata",
            "speaker": "Joe",
            "style": "cheerful and friendly"
          }]
        },
        {
          "type": "text",
          "text": "Not too bad, how about you? Ready to test these new voices?",
          "annotations": [{
            "type": "speech_metadata",
            "speaker": "Jane",
            "style": "calm and relaxed"
          }]
        }
      ]
    }],
    "response_format": {
         "type": "audio"
       },
    "generation_config": {
      "speech_config": {
        "mode": "conversational",
        "speakers": [
          { "speaker": "Joe", "voice": "Puck" },
          { "speaker": "Jane", "voice": "Kore" }
        ]
      }
    }
  }'
  ```

  {% /tab %}

{% /tabs %}

## Control speech style with metadata and tags  {% id="controllable" %}

Gemini 3.8 TTS treats the `text` field strictly as a verbatim transcript. To
control delivery without having stage directions read aloud, split your
instructions by scope:

*   **Sustained turn-level delivery (`speech_metadata.style`):** Put emotions,
  delivery style, prosody, pacing, and volume that apply across an entire turn
  in the `style` field (for example, `"style": "whispered urgently"`,
  `"style": "out of breath"`, or `"style": "warm and enthusiastic"`).
*   **Point-in-time events (inline tags):** Place momentary non-speech vocal
  bursts or pauses directly inside the transcript using angle brackets (for
  example, `"Wait... <short pause> did you hear that? <sigh>"` or
  `"Excuse me <cough> as I was saying..."`).

See the [Prompting guide](/docs/speech-generation#prompting-guide) for
comprehensive best practices.

## Streaming speech generation  {% id="streaming" %}

You can stream the generated audio as it is being synthesized by setting
`stream: true`. Unlike unary requests (which return a complete WAV file with a
RIFF header), **streaming requests return headerless raw 16-bit signed
little-endian linear PCM (`audio/l16`, 24 kHz, mono) chunks by default** so
audio chunks can be played or concatenated continuously without container
headers.

{% tabs %}
  {% tab label="Python" %}
  ```python
  import base64
  from google import genai
  
  client = genai.Client()
  
  stream = client.interactions.create(
      model="gemini-3.8-flash-tts",
      input=[{
          "type": "user_input",
          "content": [{
              "type": "text",
              "text": "Have a wonderful day!",
              "annotations": [{
                  "type": "speech_metadata",
                  "style": "cheerful and friendly",
              }],
          }],
      }],
      response_format={"type": "audio"},
      generation_config={
          "speech_config": [
              {"voice": "Kore"},
          ]
      },
      stream=True,
  )
  
  for event in stream:
      if event.event_type == "step.delta":
          if event.delta.type == "audio":
              audio_data = base64.b64decode(event.delta.data)
  ```

  {% /tab %}

  {% tab label="JavaScript" %}
  ```js
  import {GoogleGenAI} from '@google/genai';
  
  async function main() {
     const client = new GoogleGenAI({});
  
     const stream = await client.interactions.create({
        model: 'gemini-3.8-flash-tts',
        input: [{
           type: 'user_input',
           content: [{
              type: 'text',
              text: 'Have a wonderful day!',
              annotations: [{
                 type: 'speech_metadata',
                 style: 'cheerful and friendly',
              }],
           }],
        }],
        response_format: { type: 'audio' },
        generation_config: {
           speech_config: [
              { voice: 'Kore' },
           ],
        },
        stream: true,
     });
  
     for await (const event of stream) {
        if (event.event_type === 'step.delta') {
           if (event.delta.type === 'audio') {
              const audioBuffer = Buffer.from(event.delta.data, 'base64');
              // Process the audio buffer
           }
        }
     }
  }
  await main();
  ```

  {% /tab %}

  {% tab label="Go" %}
  ```go
  package main
  
  import (
      "context"
      "log"
  
      "google.golang.org/genai"
      "google.golang.org/genai/interactions/models/interactions"
      "google.golang.org/genai/interactions/models/operations"
  )
  
  func main() {
      ctx := context.Background()
      client, err := genai.NewClient(ctx, nil)
      if err != nil {
          log.Fatal(err)
      }
  
      transcriptRes, err := client.Interactions.Create(ctx, operations.CreateInteractionRequest{
          Body: operations.NewCreateInteractionRequestBody(interactions.CreateModelInteraction{
              Model: interactions.Model("gemini-3.8-flash"),
              Input: interactions.NewInteractionsInput(
                  "Generate a short transcript around 100 words that reads " +
                      "like it was clipped from a podcast by excited herpetologists. " +
                      "The hosts names are Dr. Anya and Liam.",
              ),
          }),
      })
      if err != nil {
          log.Fatal(err)
      }
  
      var transcript string
      if transcriptRes.Interaction.OutputText != nil {
          transcript = *transcriptRes.Interaction.OutputText
      }
  
      generationConfig := &interactions.GenerationConfig{
          SpeechConfig: genai.Ptr(interactions.NewSpeechConfigUnion([]interactions.SpeechConfig{
              {Speaker: genai.Ptr("Dr. Anya"), Voice: genai.Ptr("Kore")},
              {Speaker: genai.Ptr("Liam"), Voice: genai.Ptr("Puck")},
          })),
      }
  
      ttsRes, err := client.Interactions.Create(ctx, operations.CreateInteractionRequest{
          Body: operations.NewCreateInteractionRequestBody(interactions.CreateModelInteraction{
              Model: interactions.Model("gemini-3.1-flash-tts-preview"),
              Input: interactions.NewInteractionsInput(transcript),
              ResponseFormat: genai.Ptr(interactions.NewCreateModelInteractionResponseFormat(
                  interactions.NewResponseFormat(interactions.AudioResponseFormat{}),
              )),
              GenerationConfig: generationConfig,
          }),
      })
      if err != nil {
          log.Fatal(err)
      }
      _ = ttsRes
  }
  ```

  {% /tab %}

  {% tab label="REST" %}
  ```sh
  curl -X POST "https://generativelanguage.googleapis.com/v1beta/interactions" \
    -H "x-goog-api-key: $GEMINI_API_KEY" \
    -H "Content-Type: application/json" \
    --no-buffer \
    -d '{
      "model": "gemini-3.8-flash-tts",
      "input": [{
        "type": "user_input",
        "content": [{
          "type": "text",
          "text": "Have a wonderful day!",
          "annotations": [{
            "type": "speech_metadata",
            "style": "cheerful and friendly"
          }]
        }]
      }],
      "response_format": {
        "type": "audio"
      },
      "generation_config": {
        "speech_config": [
          { "voice": "Kore" }
        ]
      },
      "stream": true
    }'
  ```

  {% /tab %}

{% /tabs %}

## Audio output formats  {% id="audio-output-formats" %}

Gemini 3.8 TTS models use different default audio formats depending on whether
the request is unary or streaming:

*   **Unary requests (`stream=False`):** Return complete **WAV (`audio/wav`)**
  audio with a RIFF header (24 kHz, mono, 16-bit signed little-endian PCM).
  You can write the decoded base64 bytes directly to a `.wav` file without
  manually adding a WAV container.
*   **Streaming requests (`stream=True`):** Return **headerless raw Linear PCM
  (`audio/l16`)** chunks (24 kHz, mono, 16-bit signed little-endian PCM) by
  default so chunks can be streamed or concatenated continuously without
  container headers on each chunk.

You can override the output audio encoding and sample rate in `response_format`:

| `mime_type` value                   | Format     | Description                                                  |
| :---------------------------------- | :--------- | :----------------------------------------------------------- |
| `"audio/wav"` *(unary default)*     | WAV        | Complete WAV file with a RIFF header (24 kHz, mono, 16-bit PCM). |
| `"audio/l16"` *(streaming default)* | Linear PCM | Headerless raw 16-bit signed little-endian linear PCM (24 kHz, mono). Default for streaming requests. |
| `"audio/mulaw"`                     | μ-law      | G.711 μ-law companded audio. Commonly used in North American and Japanese telephony (8 kHz). |
| `"audio/alaw"`                      | A-law      | G.711 A-law companded audio. Commonly used in European and international telephony (8 kHz). |

You can also optionally specify `sample_rate` (for example, `24000`, `16000`, or
`8000` Hz; defaults to `24000` Hz).

The following example requests headerless raw 16-bit PCM (`audio/l16`) at
24 kHz:

{% tabs %}
  {% tab label="Python" %}
  ```python
  import base64
  from google import genai
  
  client = genai.Client()
  
  interaction = client.interactions.create(
      model="gemini-3.8-flash-tts",
      input=[{
          "type": "user_input",
          "content": [{
              "type": "text",
              "text": "Have a wonderful day!",
              "annotations": [{
                  "type": "speech_metadata",
                  "style": "cheerful and friendly",
              }],
          }],
      }],
      response_format={
          "type": "audio",
          "mime_type": "audio/l16",
          "sample_rate": 24000,
      },
      generation_config={
          "speech_config": [
              {"voice": "Kore"},
          ]
      },
  )
  
  with open("out.pcm", "wb") as f:
      f.write(base64.b64decode(interaction.output_audio.data))
  ```

  {% /tab %}

  {% tab label="JavaScript" %}
  ```js
  import {GoogleGenAI} from '@google/genai';
  import * as fs from 'node:fs';
  
  async function main() {
     const client = new GoogleGenAI({});
  
     const interaction = await client.interactions.create({
        model: 'gemini-3.8-flash-tts',
        input: [{
           type: 'user_input',
           content: [{
              type: 'text',
              text: 'Have a wonderful day!',
              annotations: [{
                 type: 'speech_metadata',
                 style: 'cheerful and friendly',
              }],
           }],
        }],
        response_format: {
           type: 'audio',
           mime_type: 'audio/l16',
           sample_rate: 24000,
        },
        generation_config: {
           speech_config: [
              { voice: 'Kore' },
           ],
        },
     });
  
     const audioBuffer = Buffer.from(interaction.output_audio.data, 'base64');
     fs.writeFileSync('out.pcm', audioBuffer);
  }
  await main();
  ```

  {% /tab %}

  {% tab label="Go" %}
  ```go
  package main
  
  import (
      "context"
      "encoding/base64"
      "log"
  
      "google.golang.org/genai"
      "google.golang.org/genai/interactions/models/interactions"
      "google.golang.org/genai/interactions/models/operations"
  )
  
  func main() {
      ctx := context.Background()
      client, err := genai.NewClient(ctx, nil)
      if err != nil {
          log.Fatal(err)
      }
  
      generationConfig := &interactions.GenerationConfig{
          SpeechConfig: genai.Ptr(interactions.NewSpeechConfigUnion([]interactions.SpeechConfig{
              {Voice: genai.Ptr("Kore")},
          })),
      }
  
      res, err := client.Interactions.Create(ctx, operations.CreateInteractionRequest{
          Body: operations.NewCreateInteractionRequestBody(interactions.CreateModelInteraction{
              Model: interactions.Model("gemini-3.1-flash-tts-preview"),
              Input: interactions.NewInteractionsInput("Say cheerfully: Have a wonderful day!"),
              ResponseFormat: genai.Ptr(interactions.NewCreateModelInteractionResponseFormat(
                  interactions.NewResponseFormat(interactions.AudioResponseFormat{}),
              )),
              GenerationConfig: generationConfig,
              Stream:           genai.Ptr(true),
          }),
      })
      if err != nil {
          log.Fatal(err)
      }
  
      stream := res.InteractionSSEStreamEvent
      defer stream.Close()
  
      for stream.Next() {
          event := stream.Value()
          if stepDelta := event.GetDataStepDelta(); stepDelta != nil {
              if audioDelta := stepDelta.GetDeltaAudio(); audioDelta != nil && audioDelta.Data != nil {
                  audioData, err := base64.StdEncoding.DecodeString(*audioDelta.Data)
                  if err != nil {
                      log.Fatal(err)
                  }
                  // Process the audio chunk (e.g. play it or write to a file)
                  _ = audioData
              }
          }
      }
      if err := stream.Err(); err != nil {
          log.Fatal(err)
      }
  }
  ```

  {% /tab %}

  {% tab label="REST" %}
  ```sh
  curl -X POST "https://generativelanguage.googleapis.com/v1beta/interactions" \
    -H "x-goog-api-key: $GEMINI_API_KEY" \
    -H "Content-Type: application/json" \
    -d '{
      "model": "gemini-3.8-flash-tts",
      "input": [{
        "type": "user_input",
        "content": [{
          "type": "text",
          "text": "Have a wonderful day!",
          "annotations": [{
            "type": "speech_metadata",
            "style": "cheerful and friendly"
          }]
        }]
      }],
      "response_format": {
        "type": "audio",
        "mime_type": "audio/l16",
        "sample_rate": 24000
      },
      "generation_config": {
        "speech_config": [
          { "voice": "Kore" }
        ]
      }
    }'
  ```

  {% /tab %}

{% /tabs %}

## Voice options  {% id="voices" %}

Gemini 3.8 TTS supports four ways to select or create voices:

1.  **Prebuilt studio voices:** 30 curated voices listed in the following table.
2.  **Extended Voice Library:** Hundreds of additional voices across languages,
  accents, and character archetypes accessible using `client.voices.list()`
  (`GET /v1beta/voices`).
3.  **[Voice design](/docs/voice-design):** Generate a custom vocal persona from
  a natural-language description in
  [Google AI Studio](https://aistudio.google.com/generate-speech) or using
  `POST /v1beta/voices` (`type="prompted"`, which returns a persistent
  `voice_...` ID and a `sample_audio` WAV preview in `CreateVoice` and
  `GetVoice`).
4.  **[Voice replication](/docs/voice-replication):** Replicate a speaker's
  voice from reference and consent audio in
  [Google AI Studio](https://aistudio.google.com/generate-speech) or using
  `POST /v1beta/voices` (`type="replicated"`, persistent `store=True` by
  default or optional stateless `store=False`).

### Custom voice limits and TTL  {% id="custom-voice-limits" %}

| Voice type                                                | Storage mode  | Quota / limit                                                | Retention (TTL) |
| :-------------------------------------------------------- | :------------ | :----------------------------------------------------------- | :-------------- |
| **Stateful voices** (`voice_...`, prompted or replicated) | `store=True`  | **200 voices per project** (shared across prompted and replicated voices) | **1 year**      |
| **Stateless voice keys** (`voicekey_...`, replicated)     | `store=False` | Client-managed                                               | **7 days**      |

### Prebuilt voices  {% id="prebuilt-voices" %}

{% table %}
  * **Zephyr** -- *Bright*
  * **Puck** -- *Upbeat*
  * **Charon** -- *Informative*

---

  * **Kore** -- *Firm*
  * **Fenrir** -- *Excitable*
  * **Leda** -- *Youthful*

---

  * **Orus** -- *Firm*
  * **Aoede** -- *Breezy*
  * **Callirrhoe** -- *Easy-going*

---

  * **Autonoe** -- *Bright*
  * **Enceladus** -- *Breathy*
  * **Iapetus** -- *Clear*

---

  * **Umbriel** -- *Easy-going*
  * **Algieba** -- *Smooth*
  * **Despina** -- *Smooth*

---

  * **Erinome** -- *Clear*
  * **Algenib** -- *Gravelly*
  * **Rasalgethi** -- *Informative*

---

  * **Laomedeia** -- *Upbeat*
  * **Achernar** -- *Soft*
  * **Alnilam** -- *Firm*

---

  * **Schedar** -- *Even*
  * **Gacrux** -- *Mature*
  * **Pulcherrima** -- *Forward*

---

  * **Achird** -- *Friendly*
  * **Zubenelgenubi** -- *Casual*
  * **Vindemiatrix** -- *Gentle*

---

  * **Sadachbia** -- *Lively*
  * **Sadaltager** -- *Knowledgeable*
  * **Sulafat** -- *Warm*
{% /table %}

### Extended Voice Library and filtering  {% id="voice-library" %}

Beyond the 30 featured studio voices in the preceding table, the **Extended
Voice Library** provides hundreds of additional voices across languages, regional accents,
character personas, and domains. You can browse, filter, and audition the full
Voice Library interactively in
[Google AI Studio](https://aistudio.google.com/generate-speech), or query it
programmatically using `client.voices.list()` (`GET /v1beta/voices`, using
`google-genai` 2.25.0+ / `@google/genai` 2.24.0+).

`ListVoices` returns your custom stored voices (ordered newest first) followed
by prebuilt catalog voices matching your filter criteria. When multiple values
are passed for a list filter, voices matching **any** value in that filter are
returned (`OR`), while distinct filter parameters combine with `AND`:

| Parameter                      | Type        | Description                                                  |
| :----------------------------- | :---------- | :----------------------------------------------------------- |
| `language_code`                | `list[str]` | BCP-47 language tag(s) (for example, `["en-US", "en-GB"]`). Case-insensitive exact match. |
| `region_code`                  | `list[str]` | ISO 3166-1 alpha-2 or UN M.49 region code(s) (for example, `["US", "GB"]`). |
| `accent`                       | `list[str]` | Regional accent descriptor(s) (for example, `["American", "British"]`). |
| `gender`                       | `list[str]` | Perceived gender presentation (`"female"`, `"male"`, or `"neutral"`). |
| `pitch`                        | `list[str]` | Vocal pitch classification (`"low"`, `"medium"`, or `"high"`). |
| `persona`                      | `list[str]` | Vocal persona or character archetype (for example, `["Warm, Friendly"]`, `["Narrator"]`). |
| `contexts` (`context` in REST) | `list[str]` | Optimal usage domain (for example, `["Audiobook", "Conversational", "News"]`). |
| `type` (`type_` in Python)     | `list[str]` | Filter by voice source: `"prebuilt"`, `"prompted"` ([Voice design](/docs/voice-design)), or `"replicated"` ([Voice replication](/docs/voice-replication)). |
| `search`                       | `str`       | Free-text substring search matched case-insensitively against both `display_name` and `description`. |
| `page_size`                    | `int`       | Maximum number of voices returned per page (default `50`, maximum `1000`). |
| `page_token`                   | `str`       | Token from `response.next_page_token` to fetch the next page of results. |

{% tabs %}
  {% tab label="Python" %}
  ```python
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
  ```

  {% /tab %}

  {% tab label="JavaScript" %}
  ```javascript
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
  ```

  {% /tab %}

  {% tab label="REST" %}
  ```sh
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
  ```

  {% /tab %}

{% /tabs %}

## Supported languages  {% id="languages" %}

The TTS models detect the input language automatically.
[Gemini 3.8 Flash TTS](/docs/models/gemini-3.8-flash-tts)
(`gemini-3.8-flash-tts`) supports **over 130 languages**, and
[Gemini 3.8 Flash-Lite TTS](/docs/models/gemini-3.8-flash-lite-tts)
(`gemini-3.8-flash-lite-tts`) supports **over 100 languages**:

| Language                      | Gemini 3.8 Flash TTS | Gemini 3.8 Flash-Lite TTS |
| :---------------------------- | :------------------- | :------------------------ |
| Acehnese (Arab script)        | ✔️                    | ✔️                         |
| Afrikaans                     | ✔️                    | ✔️                         |
| Akan                          | ✔️                    | ✔️                         |
| Amharic                       | ✔️                    | ✔️                         |
| Armenian                      | ✔️                    | ✔️                         |
| Assamese                      | ✔️                    | ✔️                         |
| Awadhi                        | ✔️                    | ✔️                         |
| Balinese                      | ✔️                    | ✔️                         |
| Bangla                        | ✔️                    | ✔️                         |
| Banjar (Arab script)          | ✔️                    | —                         |
| Banjar (Latn script)          | ✔️                    | ✔️                         |
| Bashkir                       | ✔️                    | —                         |
| Basque                        | ✔️                    | ✔️                         |
| Belarusian                    | ✔️                    | ✔️                         |
| Bemba                         | ✔️                    | —                         |
| Bhojpuri                      | ✔️                    | ✔️                         |
| Bosnian                       | ✔️                    | ✔️                         |
| Buginese                      | ✔️                    | ✔️                         |
| Bulgarian                     | ✔️                    | ✔️                         |
| Burmese                       | ✔️                    | —                         |
| Cantonese                     | ✔️                    | ✔️                         |
| Catalan                       | ✔️                    | ✔️                         |
| Cebuano                       | ✔️                    | ✔️                         |
| Central Kurdish               | ✔️                    | ✔️                         |
| Chhattisgarhi                 | ✔️                    | ✔️                         |
| Chinese (Hans script)         | ✔️                    | ✔️                         |
| Chinese (Hant script)         | ✔️                    | ✔️                         |
| Crimean Tatar                 | ✔️                    | —                         |
| Croatian                      | ✔️                    | ✔️                         |
| Czech                         | ✔️                    | ✔️                         |
| Danish                        | ✔️                    | ✔️                         |
| Dutch                         | ✔️                    | ✔️                         |
| Dyula                         | ✔️                    | —                         |
| Dzongkha                      | ✔️                    | —                         |
| Egyptian Arabic               | ✔️                    | ✔️                         |
| English                       | ✔️                    | ✔️                         |
| Estonian                      | ✔️                    | ✔️                         |
| Filipino                      | ✔️                    | ✔️                         |
| Finnish                       | ✔️                    | —                         |
| French                        | ✔️                    | ✔️                         |
| Galician                      | ✔️                    | ✔️                         |
| Ganda                         | ✔️                    | ✔️                         |
| Georgian                      | ✔️                    | ✔️                         |
| German                        | ✔️                    | ✔️                         |
| Greek                         | ✔️                    | ✔️                         |
| Guarani                       | ✔️                    | —                         |
| Gujarati                      | ✔️                    | ✔️                         |
| Haitian Creole                | ✔️                    | ✔️                         |
| Halh Mongolian                | ✔️                    | ✔️                         |
| Hausa                         | ✔️                    | ✔️                         |
| Hebrew                        | ✔️                    | ✔️                         |
| Hindi                         | ✔️                    | ✔️                         |
| Hungarian                     | ✔️                    | ✔️                         |
| Icelandic                     | ✔️                    | ✔️                         |
| Igbo                          | ✔️                    | —                         |
| Iloko                         | ✔️                    | ✔️                         |
| Indonesian                    | ✔️                    | ✔️                         |
| Iranian Persian               | ✔️                    | ✔️                         |
| Italian                       | ✔️                    | ✔️                         |
| Japanese                      | ✔️                    | ✔️                         |
| Javanese                      | ✔️                    | ✔️                         |
| Kabyle                        | ✔️                    | —                         |
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
| Latgalian                     | ✔️                    | —                         |
| Lingala                       | ✔️                    | ✔️                         |
| Lithuanian                    | ✔️                    | —                         |
| Luxembourgish                 | ✔️                    | —                         |
| Macedonian                    | ✔️                    | ✔️                         |
| Magahi                        | ✔️                    | ✔️                         |
| Maithili                      | ✔️                    | ✔️                         |
| Malayalam                     | ✔️                    | ✔️                         |
| Maltese                       | ✔️                    | ✔️                         |
| Manipuri                      | ✔️                    | ✔️                         |
| Marathi                       | ✔️                    | ✔️                         |
| Minangkabau (Arab script)     | ✔️                    | ✔️                         |
| Minangkabau (Latn script)     | ✔️                    | —                         |
| Mizo                          | ✔️                    | ✔️                         |
| Nepali (individual language)  | ✔️                    | ✔️                         |
| Nigerian Fulfulde             | ✔️                    | ✔️                         |
| North Azerbaijani             | ✔️                    | ✔️                         |
| Northern Sotho                | ✔️                    | ✔️                         |
| Northern Uzbek                | ✔️                    | ✔️                         |
| Norwegian Bokmål              | ✔️                    | ✔️                         |
| Norwegian Nynorsk             | ✔️                    | ✔️                         |
| Nyanja                        | ✔️                    | ✔️                         |
| Occitan                       | ✔️                    | —                         |
| Odia (individual language)    | ✔️                    | ✔️                         |
| Pangasinan                    | ✔️                    | —                         |
| Persian (Afghanistan)         | ✔️                    | ✔️                         |
| Polish                        | ✔️                    | ✔️                         |
| Portuguese                    | ✔️                    | ✔️                         |
| Punjabi                       | ✔️                    | ✔️                         |
| Romanian                      | ✔️                    | ✔️                         |
| Russian                       | ✔️                    | ✔️                         |
| Santali                       | ✔️                    | ✔️                         |
| Serbian                       | ✔️                    | ✔️                         |
| Sindhi                        | ✔️                    | —                         |
| Sinhala                       | ✔️                    | ✔️                         |
| Slovak                        | ✔️                    | ✔️                         |
| Slovenian                     | ✔️                    | —                         |
| Somali                        | ✔️                    | —                         |
| South Azerbaijani             | ✔️                    | ✔️                         |
| Southern Pashto               | ✔️                    | ✔️                         |
| Southern Sotho                | ✔️                    | —                         |
| Spanish                       | ✔️                    | ✔️                         |
| Standard Arabic (Arab script) | ✔️                    | ✔️                         |
| Standard Arabic (Latn script) | ✔️                    | ✔️                         |
| Standard Latvian              | ✔️                    | ✔️                         |
| Standard Malay                | ✔️                    | ✔️                         |
| Swahili (individual language) | ✔️                    | —                         |
| Swati                         | ✔️                    | —                         |
| Swedish                       | ✔️                    | —                         |
| Tajik                         | ✔️                    | —                         |
| Tamil                         | ✔️                    | ✔️                         |
| Telugu                        | ✔️                    | ✔️                         |
| Thai                          | ✔️                    | —                         |
| Tigrinya                      | ✔️                    | —                         |
| Tosk Albanian                 | ✔️                    | —                         |
| Turkish                       | ✔️                    | ✔️                         |
| Uyghur                        | ✔️                    | —                         |
| Vietnamese                    | ✔️                    | ✔️                         |

## Supported models  {% id="supported-models" %}

| Model                                                        | Single speaker | Multi-speaker | Voice design | Voice replication |
| :----------------------------------------------------------- | :------------- | :------------ | :----------- | :---------------- |
| [Gemini 3.8 Flash TTS](/docs/models/gemini-3.8-flash-tts) (`gemini-3.8-flash-tts`) | ✔️              | ✔️             | ✔️            | ✔️                 |
| [Gemini 3.8 Flash-Lite TTS](/docs/models/gemini-3.8-flash-lite-tts) (`gemini-3.8-flash-lite-tts`) | ✔️              | ✔️             | ✔️            | ✔️                 |
| [Gemini 3.1 Flash TTS Preview](/docs/models/gemini-3.1-flash-tts-preview) | ✔️              | ✔️             | —            | —                 |
| [Gemini 2.5 Pro Preview TTS](/docs/models/gemini-2.5-pro-preview-tts) | ✔️              | ✔️             | —            | —                 |

### When to use which model  {% id="when-to-use-which-model" %}

Both Gemini 3.8 TTS models share the exact same API schema and prompting format,
allowing you to switch between them with a single parameter change:

*   **Use [Gemini 3.8 Flash TTS](/docs/models/gemini-3.8-flash-tts)
  (`gemini-3.8-flash-tts`)** when maximum acoustic fidelity, nuanced acting,
  and expressive control are top priority. It is ideal for studio-grade
  creative work, complex multi-speaker dialogue, heavy vocal-burst tags,
  difficult pronunciations, regional or minority dialects, and long-form
  narrations requiring rock-solid voice and room-tone stability.
*   **Use [Gemini 3.8 Flash-Lite TTS](/docs/models/gemini-3.8-flash-lite-tts)
  (`gemini-3.8-flash-lite-tts`)** as your fast, cost-efficient workhorse
  replacement for `gemini-3.1-flash-tts-preview`. It is optimized for
  high-volume bulk production, conversational voice agent cascades, read-aloud
  features, reliable voice replication, and everyday single-speaker speech
  across major languages.

### Migration guide  {% id="migration" %}

When upgrading from earlier preview models (`gemini-3.1-flash-tts-preview` or
`gemini-2.5-pro-preview-tts`) to Gemini 3.8 TTS (`gemini-3.8-flash-tts` or
`gemini-3.8-flash-lite-tts`), review these five key changes:

1.  **Separate style from transcript:** Move sustained acting, tone, prosody,
  and pacing instructions (such as `"whispering"`, `"out of breath"`, or
  `"speaking slowly"`) out of plain text and into `speech_metadata.style`.
  Keep `text` strictly as the verbatim transcript plus inline vocal tags.
2.  **Design personas upfront with Voice design:** Replace multi-paragraph
  `"Audio Profile"` or `"Director's Notes"` blocks with a custom voice created
  in [Voice design](/docs/voice-design), then carry that `voice_...` ID
  through your TTS requests with minimal or empty `style` strings.
3.  **Use structured dialogue turns:** For multi-speaker dialogue, pass one
  `content` item per speaker turn with `speech_metadata.speaker` instead of
  embedding `Speaker: ...` prefixes inside a single text block.
4.  **Use angle brackets for inline vocal tags:** Use angle brackets (`<laugh>`,
  `<sigh>`, `<cough>`, `<breath>`, `<short pause>`) for point-in-time human
  vocalizations and pauses. Avoid non-vocal sound-effect tags (such as
  applause or thuds).
5.  **Account for default WAV (`audio/wav`) output on unary requests:** Unlike
  `gemini-3.1-flash-tts-preview` (which returned headerless raw PCM
  `audio/l16` by default), Gemini 3.8 TTS models return complete **WAV
  (`audio/wav`)** audio with a RIFF header (24 kHz, mono, 16-bit PCM) on unary
  requests. You can write decoded audio bytes directly to a `.wav` file
  without manually wrapping them with Python's `wave` module or Node's `wav`
  package. If your existing pipeline expects headerless raw PCM, set
  `response_format` to `{"type": "audio", "mime_type": "audio/l16"}` (see
  [Audio output formats](/docs/speech-generation#audio-output-formats)).

## Prompting guide  {% id="prompting-guide" %}

Gemini 3.8 TTS models treat input text strictly as a **verbatim transcript**.
Unlike earlier preview models where stage directions were embedded in plain
text, Gemini 3.8 TTS separates sustained turn-level directions
(`speech_metadata`) from point-in-time inline vocal tags.

### Style field versus inline tags  {% id="style-vs-inline-tags" %}

Split your performance instructions by scope:

*   **Turn-level delivery (`speech_metadata.style`):** Put sustained delivery
  attributes—such as emotion, prosody, overall pace, or delivery style (like
  `"whispering"`, `"out of breath"`, `"muttering"`, or `"sarcastic"`)—into the
  `style` field of `speech_metadata`. To create a stable character and
  performance across turns, design the persona upfront in
  [Voice design](/docs/voice-design) and use `style` only for optional
  turn-level tweaks.
*   **Point-in-time events (inline tags):** Put momentary non-speech vocal
  bursts, breaths, or pauses inline inside the transcript using angle brackets
  (`<cough>`, `<breath>`, `<sigh>`, `<short pause>`). Use angle brackets
  (`<...>`) for highest audio quality, and stick to human vocalizations rather
  than non-vocal sound effects.

| Scope                                         | Where to place             | Examples                                                     |
| :-------------------------------------------- | :------------------------- | :----------------------------------------------------------- |
| **Turn-level** (sustained across the turn)    | `speech_metadata.style`    | `"angry tone"`, `"speaking rapidly"`, `"out of breath"`, `"whispers"`, `"sarcastic"` |
| **Point-in-time** (occurs at a specific word) | Inline in `text` (`<...>`) | `"<cough> Thank you all for coming tonight! <throat-clearing> As I was saying..."` |

### Pacing and pauses  {% id="pacing-and-pauses" %}

You can control rhythm and silence at three levels of granularity:

*     **Punctuation and ellipses:** Use commas, dashes (`--`), and ellipses
  (`...`) for natural conversational hesitation.

*     **Inline pause tags:** Insert `<short pause>` or `<long pause>` at exact
  points in the script where a speaker should pause:

  ```text
  Hold on, let me think... <short pause> Alright, I've got it.
  ```

*     **Turn-level pace:** Set `"style": "speaking rapidly"` or `"style":
  "speaking slowly"` in `speech_metadata` to control the speaking rate across
  the whole turn.


### Prosody and pitch  {% id="prosody-and-pitch" %}

Use **`speech_metadata.style`** to control prosody, pitch, and inflection across
a turn (for example, `"style": "high pitch, cheerful and excited inflection"` or
`"style": "monotone and flat"`). If the emotion or prosody shifts mid-dialogue,
split the script into separate turns with distinct `style` values for each turn.

### Emphasis  {% id="emphasis" %}

Capitalize specific words in the transcript, combined with punctuation and
inline vocal tags, to place natural vocal stress on key words:

```text
This is a VERY important point!
It was a VERY long day <sigh> ... nobody listens anymore.
```

### Vocal bursts and non-speech sounds  {% id="vocal-bursts" %}

Place non-speech human vocalizations inline using angle brackets (`<...>`) at
the exact point where the sound should occur. Recommended vocal tags include:

{% table %}
  * `<argh>`
  * `<breath>`
  * `<heavy breath>`
  * `<exhales>`

---

  * `<cackle>`
  * `<cheer>`
  * `<chuckle>` / `<chuckles>`
  * `<cough>`

---

  * `<cry>`
  * `<gasp>`
  * `<giggle>`
  * `<groan>`

---

  * `<growl>`
  * `<grunt>`
  * `<grr>`
  * `<hiss>`

---

  * `<laugh>` / `<laughter>`
  * `<moan>`
  * `<pant>`
  * `<pff>` / `<phew>`

---

  * `<scream>`
  * `<shout>`
  * `<shriek>`
  * `<sigh>` / `<sighs>`

---

  * `<sneeze>`
  * `<snicker>`
  * `<snort>`
  * `<sob>`

---

  * `<throat-clearing>`
  * `<tsk>`
  * `<whimper>`
  * `<whispers>` / `<whispering>`

---

  * `<yawn>`
  * `<short pause>`
  * `<long pause>`
{% /table %}

{% callout type="note" %}
  If your transcript is in a non-English language,
  continue to use English inline tags for best results.

{% /callout %}

### Backchannels and overlapping speech  {% id="backchannels" %}

In multi-speaker dialogue, wrap listener reactions in pipe characters
(`|reaction|`) inside a speaker's turn to create natural backchannels or
overlapping speech without breaking into a separate turn per reaction.

*   **Short backchannel exchanges:** Layer brief listener reactions (`|oh hmm|`,
  `|oh really?|`, `|absolutely|`) inside the active speaker's turn:  *   **Turn 1 (Speaker A):** `"So the launch is Thursday |oh hmm| Are we
  actually ready?"`
  *   **Turn 2 (Speaker B):** `"Ready enough |oh really?| The last blocker
  cleared this morning."`
  *   **Turn 3 (Speaker A):** `"Then let's ship it |absolutely| and watch the
  dashboards."`

*   **Overlapping and interleaved speech:** Use multiple pipe segments to
  simulate simultaneous or interleaved speech between two speakers (works best
  with `gemini-3.8-flash-tts`):  *   **Simultaneous countdown/chorus:** `"Let's surprise him on three |ok|
  ready?"` followed by `"one. two. three. |happy| happy |birthday|
  birthday!"`
  *   **Full speaker overlap:** `"Hello |oh| there |my| it |goodness| must
  |gracious| be |would| almost |you| time |look| for |at that| dinner"`


### Consistency across generations and what to avoid  {% id="consistency-and-what-to-avoid" %}

Follow these guidelines to keep vocal identity stable across turns:

*   **Design personas upfront in Voice design instead of long style blocks:**
  Long-form `"Audio Profile"` paragraphs and multi-bullet `"Director's Notes"`
  carried over from earlier models are the most common cause of voice drift.
  Use that same creative intuition upfront in
  [Voice design](/docs/voice-design) to generate a persistent custom
  `voice_...` persona, then carry that voice ID through your TTS calls.
*   **Rely on the voice reference for stability (omit meta-instructions):**
  Gemini 3.8 TTS models are trained to anchor on the audio reference first. Do
  not include instructions telling the model to hold the voice steady (such as
  `"do not switch speaker identity"` or `"maintain identical timbre"`)—extra
  prompt text increases drift. Drop unnecessary style instructions and let the
  model vary naturally around the stable point provided by the voice
  reference.
*   **Do not try to change immutable speaker traits in `style`:** Avoid putting
  age, gender, names, or permanent accent changes in `speech_metadata.style`.
  Instead, pick a regional voice from the Extended Voice Library or create one
  with [Voice design](/docs/voice-design).

### Recommended workflow  {% id="recommended-workflow" %}

1.  **Build the character once:** Create your character in
  [Voice design](/docs/voice-design) or select a regional voice from the
  Extended Voice Library that matches your target language and persona.
2.  **Write natural spoken transcripts with disfluencies:** For maximum
  naturalness, write the `text` as a real spoken transcript—including natural
  conversational disfluencies and hesitations (for example, `"Oh uh yeah I
think... hm, so that's interesting"`).
3.  **Test plain TTS first:** Synthesize your transcript with an empty `style`
  field first—most requests need no `style` instruction at all.
4.  **Add short `style` prompts only for tweaks:** Add a concise `style` string
  (such as `"casual, friendly"` or `"muttering, then reassuring"`) only for
  turns that need a specific delivery adjustment, and reuse that exact short
  string across turns when you want a consistent baseline.

### Multi-turn dialogue and voice agents  {% id="multi-turn-and-voice-agents" %}

When building real-time conversational voice agents or multi-turn applications:

*   Make **one TTS call per turn** as LLM text chunks arrive.
*   Let the configured `voice` (prebuilt, designed `voice_...`, or replicated
  `voice_...` / `voicekey_...`) carry the speaker's identity across
  turns—never re-send a long character persona on each turn.
*   Leave the per-turn `style` field empty, or send one short constant string
  (such as `"casual, friendly"`) for the whole conversation.
*   Split long agent responses into shorter turns rather than reaching for
  stronger style prompts.

## Limitations  {% id="limitations" %}

*   TTS models accept text-only inputs and generate audio-only outputs.
*   Single-request multi-speaker generation (`speech_config.speakers`) supports
  up to 2 speakers using prebuilt voices. To combine custom designed
  (`voice_...`) or replicated (`voice_...` / `voicekey_...`) voices in
  multi-character dialogue, synthesize each speaker's turn individually.
  Because unary requests return `audio/wav` with a 44-byte RIFF header by
  default, request raw PCM (`{"type": "audio", "mime_type": "audio/l16"}`) or
  strip the WAV header from each turn before concatenating the 24kHz PCM
  audio frames.
*   **Custom voice storage limits and TTL:**  *   **Stateful voices (`store=True`, prompted or replicated):** Maximum of
    **200 voices per project** with a **1-year TTL** (time-to-live).
  *   **Stateless voice keys (`store=False`, `voicekey_...`):** **7-day TTL**
    (time-to-live).

*   Review the [Supported languages](/docs/speech-generation#languages) section
  for language coverage.

## What's next  {% id="whats-next" %}

*   Create custom vocal personas from natural language with
  [Voice design](/docs/voice-design).
*   Replicate an existing speaker's voice in
  [Voice replication](/docs/voice-replication).
*   Compare model specifications on the
  [Gemini 3.8 Flash TTS](/docs/models/gemini-3.8-flash-tts) and
  [Gemini 3.8 Flash-Lite TTS](/docs/models/gemini-3.8-flash-lite-tts) model
  pages.
*   Explore interactive bidirectional audio with the [Live API](/docs/live-api).