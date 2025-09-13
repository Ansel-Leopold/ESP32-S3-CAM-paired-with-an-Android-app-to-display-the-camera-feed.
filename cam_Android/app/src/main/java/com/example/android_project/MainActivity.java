package com.example.android_project;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends AppCompatActivity {

    private ImageView cameraView;
    private TextView statusText;
    private Button btnReconnect;
    private ImageButton btnExit;
    private CameraStreamTask streamTask;
    private volatile boolean isRunning = false;

    // 👇 替换为你的 ESP32 IP 地址
    private static final String STREAM_URL = "http://192.168.195.98:81/stream";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cameraView = findViewById(R.id.cameraView);
        statusText = findViewById(R.id.statusText);
        btnReconnect = findViewById(R.id.btnReconnect);
        btnExit = findViewById(R.id.btnExit);

        // 设置退出按钮
        btnExit.setOnClickListener(v -> {
            isRunning = false;
            if (streamTask != null) {
                streamTask.cancel(true);
            }
            finish();
        });

        // 设置重连按钮
        btnReconnect.setOnClickListener(v -> {
            btnReconnect.setVisibility(View.GONE);
            updateStatus("正在重连...", R.drawable.ic_wifi);
            startCameraStream();
        });

        startCameraStream();
    }

    private void startCameraStream() {
        if (streamTask != null) {
            streamTask.cancel(true);
        }
        isRunning = true;
        streamTask = new CameraStreamTask();
        streamTask.execute();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isRunning = false;
        if (streamTask != null) {
            streamTask.cancel(true);
        }
    }

    // 🎥 核心：异步获取 MJPEG 流并解析显示
    private class CameraStreamTask extends AsyncTask<Void, Bitmap, Void> {
        @Override
        protected Void doInBackground(Void... voids) {
            HttpURLConnection connection = null;
            BufferedInputStream inputStream = null;

            try {
                URL url = new URL(STREAM_URL);
                connection = (HttpURLConnection) url.openConnection();
                connection.setReadTimeout(10000);
                connection.setConnectTimeout(10000);
                connection.setRequestMethod("GET");
                connection.connect();

                int responseCode = connection.getResponseCode();

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    inputStream = new BufferedInputStream(connection.getInputStream());

                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] boundary = "\r\n--1234567890000".getBytes(); // MJPEG 边界
                    int state = 0;

                    int current;
                    while (isRunning && (current = inputStream.read()) != -1) {
                        buffer.write(current);

                        if (state == 0 && buffer.size() >= boundary.length) {
                            byte[] tail = new byte[boundary.length];
                            System.arraycopy(buffer.toByteArray(), buffer.size() - boundary.length, tail, 0, boundary.length);
                            if (new String(tail).equals(new String(boundary))) {
                                state = 1;
                                buffer.reset();
                            }
                        } else if (state == 1) {
                            if (buffer.size() > 4) {
                                byte[] tail = new byte[4];
                                System.arraycopy(buffer.toByteArray(), buffer.size() - 4, tail, 0, 4);
                                if (new String(tail).equals("\r\n\r\n")) {
                                    state = 2;
                                    buffer.reset();
                                }
                            }
                        } else if (state == 2) {
                            if (buffer.size() > boundary.length) {
                                byte[] tail = new byte[boundary.length];
                                System.arraycopy(buffer.toByteArray(), buffer.size() - boundary.length, tail, 0, boundary.length);
                                if (new String(tail).equals(new String(boundary))) {
                                    byte[] imageData = new byte[buffer.size() - boundary.length];
                                    System.arraycopy(buffer.toByteArray(), 0, imageData, 0, imageData.length);

                                    Bitmap bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.length);
                                    if (bitmap != null) {
                                        publishProgress(bitmap);
                                    }

                                    buffer.reset();
                                    state = 1;
                                }
                            }
                        }
                    }
                } else {
                    int finalCode = responseCode;
                    runOnUiThread(() -> {
                        updateStatus("HTTP 错误: " + finalCode, R.drawable.ic_wifi);
                        btnReconnect.setVisibility(View.VISIBLE);
                    });
                }

            } catch (IOException e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    updateStatus("连接失败: " + e.getMessage(), R.drawable.ic_wifi);
                    btnReconnect.setVisibility(View.VISIBLE);
                });
            } finally {
                try {
                    if (inputStream != null) inputStream.close();
                    if (connection != null) connection.disconnect();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            return null;
        }

        @Override
        protected void onProgressUpdate(Bitmap... bitmaps) {
            if (bitmaps.length > 0 && bitmaps[0] != null) {
                cameraView.setImageBitmap(bitmaps[0]);
                updateStatus("正在播放...", R.drawable.ic_wifi);
            }
        }

        @Override
        protected void onCancelled() {
            isRunning = false;
        }
    }

    // 🎨 统一更新状态文字 + 图标
    private void updateStatus(String text, int iconResId) {
        runOnUiThread(() -> {
            statusText.setText(text);
            Drawable icon = getResources().getDrawable(iconResId, null);
            statusText.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null);
        });
    }
}