# ZipVoice⚡: Fast and High-Quality Zero-Shot Text-to-Speech with Flow Matching

This model consists of checkpoints for two fast and high-quality non-autoregressive zero-shot text-to-speech models:

- **ZipVoice**, for single-speaker speech generation. Details in [paper](https://arxiv.org/abs/2506.13053) and [demo](https://zipvoice.github.io/).
- **ZipVoice-Dialog**, for spoken dialogue generation. Details in [paper](https://arxiv.org/abs/2507.09318) and [demo](https://zipvoice-dialog.github.io/).

See our Github repository [ZipVoice](https://github.com/k2-fsa/ZipVoice) for instructions on using our models.

## Explanation of each directory

| Directory                  | Model Type             | Training Data                 | Initialized from           |
| -------------------------- | ---------------------- | ----------------------------- | -------------------------- |
| zipvoice                   | ZipVoice               | Emilia                        | -                          |
| zipvoice_libritts          | ZipVoice               | LibriTTS                      | -                          |
| zipvoice_distill           | ZipVoice-Distill       | Emilia                        | zipvoice/model.pt          |
| zipvoice_distill_libritts  | ZipVoice-Distill       | LibriTTS                      | zipvoice_libritts/model.pt |
| zipvoice_dialog            | ZipVoice-Dialog        | OpenDialog + in-house dataset | zipvoice/model.pt          |
| zipvoice_dialog_opendialog | ZipVoice-Dialog        | OpenDialog                    | zipvoice/model.pt          |
| zipvoice_dialog_stereo     | ZipVoice-Dialog-Stereo | in-house dataset              | zipvoice_dialog/model.pt   |

## Citation

```
@article{zhu2025zipvoice,
      title={ZipVoice: Fast and High-Quality Zero-Shot Text-to-Speech with Flow Matching},
      author={Zhu, Han and Kang, Wei and Yao, Zengwei and Guo, Liyong and Kuang, Fangjun and Li, Zhaoqing and Zhuang, Weiji and Lin, Long and Povey, Daniel},
      journal={arXiv preprint arXiv:2506.13053},
      year={2025}
}

@article{zhu2025zipvoicedialog,
      title={ZipVoice-Dialog: Non-Autoregressive Spoken Dialogue Generation with Flow Matching},
      author={Zhu, Han and Kang, Wei and Guo, Liyong and Yao, Zengwei and Kuang, Fangjun and Zhuang, Weiji and Li, Zhaoqing and Han, Zhifeng and Zhang, Dong and Zhang, Xin and Song, Xingchen and Lin, Long and Povey, Daniel},
      journal={arXiv preprint arXiv:2507.09318},
      year={2025}
}

```

## k2-fsa/ZipVoice 模型文件列表

> huggingface地址为： https://huggingface.co/k2-fsa/ZipVoice/tree/main/zipvoice_distill

[fm_decoder.onnx](https://huggingface.co/k2-fsa/ZipVoice/blob/main/zipvoice_distill/fm_decoder.onnx)

[fm_decoder_int8.onnx](https://huggingface.co/k2-fsa/ZipVoice/blob/main/zipvoice_distill/fm_decoder_int8.onnx)

[model.json](https://huggingface.co/k2-fsa/ZipVoice/blob/main/zipvoice_distill/model.json)

[model.pt](https://huggingface.co/k2-fsa/ZipVoice/blob/main/zipvoice_distill/model.pt)

[model.safetensors](https://huggingface.co/k2-fsa/ZipVoice/blob/main/zipvoice_distill/model.safetensors)

[text_encoder.onnx](https://huggingface.co/k2-fsa/ZipVoice/blob/main/zipvoice_distill/text_encoder.onnx)

[text_encoder_int8.onnx](https://huggingface.co/k2-fsa/ZipVoice/blob/main/zipvoice_distill/text_encoder_int8.onnx)

[tokens.txt](https://huggingface.co/k2-fsa/ZipVoice/blob/main/zipvoice_distill/tokens.txt)

[zipvoice_base.json](https://huggingface.co/k2-fsa/ZipVoice/blob/main/zipvoice_distill/zipvoice_base.json)

