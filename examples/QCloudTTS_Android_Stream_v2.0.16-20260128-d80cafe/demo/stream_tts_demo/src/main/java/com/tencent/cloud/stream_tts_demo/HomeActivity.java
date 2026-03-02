package com.tencent.cloud.stream_tts_demo;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.tencent.cloud.stream.tts.FlowingSpeechSynthesizer;
import com.tencent.cloud.stream.tts.core.utils.AAILogger;


public class HomeActivity extends AppCompatActivity {

    private EditText appIdEt;
    private EditText secretIdEt;
    private EditText secretKeyEt;
    private EditText tokenEt;
    private Button submitBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AAILogger.enableDebug();
        AAILogger.setNeedLogFile(true,getApplicationContext());
        setContentView(R.layout.activity_home);
        ((TextView)findViewById(R.id.version)).setText(FlowingSpeechSynthesizer.version());
        // 初始化组件
        appIdEt = findViewById(R.id.editTextAppId);
        secretIdEt = findViewById(R.id.editTextSecretId);
        secretKeyEt = findViewById(R.id.editTextSecretKey);
        tokenEt = findViewById(R.id.editTextToken);
        submitBtn = findViewById(R.id.buttonSubmit);

        appIdEt.setText("");
        secretIdEt.setText("");
        secretKeyEt.setText("");


        // 设置button的点击事件
        submitBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String appId = appIdEt.getText().toString();
                String secretId = secretIdEt.getText().toString();
                String secretKey = secretKeyEt.getText().toString();
                String token = tokenEt.getText().toString();

                if (TextUtils.isEmpty(appId) || TextUtils.isEmpty(secretId) || TextUtils.isEmpty(secretKey)) {
                    Toast.makeText(HomeActivity.this, "请输入正确的信息", Toast.LENGTH_LONG).show();
                    return;
                }

                Intent intent = new Intent(HomeActivity.this, MainActivity.class);
                intent.putExtra("appId", appId);
                intent.putExtra("secretId", secretId);
                intent.putExtra("secretKey", secretKey);
                intent.putExtra("token", token);

                startActivity(intent);
            }
        });
    }
}
