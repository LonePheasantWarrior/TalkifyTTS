# -*- coding: utf-8 -*-
# @Project : tob_service
# @Company : ByteDance
# @Time    : 2025/7/10 19:01
# @Author  : SiNian
# @FileName: TTSv3HttpDemo.py
# @IDE: PyCharm
# @Motto：  I,with no mountain to rely on,am the mountain myself.
import requests
import json
import base64
import os

# python版本：==3.11

# -------------客户需要填写的参数----------------
appID = ""
accessKey = ""
resourceID = ""
text = "这是一段测试文本，用于测试字节大模型语音合成http单向流式接口效果。"
# ---------------请求地址----------------------
url = "https://openspeech.bytedance.com/api/v3/tts/unidirectional"

def tts_http_stream(url, headers, params, audio_save_path):
    session = requests.Session()
    try:
        print('请求的url:', url)
        print('请求的headers:', headers)
        print('请求的params:\n', params)
        response = session.post(url, headers=headers, json=params, stream=True)
        print(response)
        # 打印response headers
        print(f"code: {response.status_code} header: {response.headers}")
        logid = response.headers.get('X-Tt-Logid')
        print(f"X-Tt-Logid: {logid}")

        # 用于存储音频数据
        audio_data = bytearray()
        total_audio_size = 0
        for chunk in response.iter_lines(decode_unicode=True):
            if not chunk:
                continue
            data = json.loads(chunk)

            if data.get("code", 0) == 0 and "data" in data and data["data"]:
                chunk_audio = base64.b64decode(data["data"])
                audio_size = len(chunk_audio)
                total_audio_size += audio_size
                audio_data.extend(chunk_audio)
                continue
            if data.get("code", 0) == 0 and "sentence" in data and data["sentence"]:
                print("sentence_data:", data)
                continue
            if data.get("code", 0) == 20000000:
                if 'usage' in data:
                    print("usage:", data['usage'])
                break
            if data.get("code", 0) > 0:
                print(f"error response:{data}")
                break

        # 保存音频文件
        if audio_data:
            with open(audio_save_path, "wb") as f:
                f.write(audio_data)
            print(f"文件保存在{audio_save_path},文件大小: {len(audio_data) / 1024:.2f} KB")
            # 确保生成的音频有正确的访问权限
            os.chmod(audio_save_path, 0o644)

    except Exception as e:
        print(f"请求失败: {e}")
    finally:
        response.close()
        session.close()

if __name__ == "__main__":
    # ---------------请求地址----------------------
    headers = {
        "X-Api-App-Id": appID,
        "X-Api-Access-Key": accessKey,
        "X-Api-Resource-Id": resourceID,
        "Content-Type": "application/json",
        "Connection": "keep-alive",
        
        # 表示是否需要用量返回, 默认不添加; 启用后在合成结束时会多一个usage字段
        # "X-Control-Require-Usage-Tokens-Return": "*" 
    }

    payload = {
        "user": {
            "uid": "123123"
        },
        "req_params":{
            "text": "其他人",
            "speaker": "zh_female_cancan_mars_bigtts",
            "audio_params": {
                "format": "mp3",
                "sample_rate": 24000,
                "enable_timestamp": True
            },
            "additions": "{\"explicit_language\":\"zh\",\"disable_markdown_filter\":true, \"enable_timestamp\":true}\"}"
        }
    }

    tts_http_stream(url=url, headers=headers, params=payload, audio_save_path="tts_test.mp3")
